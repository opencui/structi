package io.opencui.system1

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.AIAgentFunctionalStrategy
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.agent.entity.AIAgentStrategy
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeLLMRequestStructured
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.google.structure.GoogleBasicJsonSchemaGenerator
import ai.koog.prompt.executor.clients.google.structure.GoogleStandardJsonSchemaGenerator
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.openai.base.structure.OpenAIBasicJsonSchemaGenerator
import ai.koog.prompt.executor.clients.openai.base.structure.OpenAIStandardJsonSchemaGenerator
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.structure.StructureFixingParser
import ai.koog.prompt.structure.StructuredOutput
import ai.koog.prompt.structure.StructuredOutputConfig
import ai.koog.prompt.structure.json.JsonStructuredData
import ai.koog.prompt.structure.json.generator.BasicJsonSchemaGenerator
import ai.koog.prompt.structure.json.generator.JsonSchemaGenerator
import ai.koog.prompt.structure.json.generator.StandardJsonSchemaGenerator
import io.opencui.core.UserSession
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap


// The agent function is so simply because of the type based koog design.
data class KoogFunction<T>(val session: UserSession, val agent: AIAgent<Unit, T>) : IFuncComponent<T> {
    override suspend fun invoke(): T {
        return agent.run(Unit)
    }
}


object StructureOutputConfigurator {
    inline fun <reified T> getConfig(
        basic: Boolean,
        examples: List<T> = emptyList(),
        fixModel: LLModel = AnthropicModels.Haiku_3_5): StructuredOutputConfig<T> {

        val genericStructure = JsonStructuredData.createJsonStructure<T>(
            // Some models might not work well with json schema, so you may try simple, but it has more limitations (no polymorphism!)
            schemaGenerator = if (basic) BasicJsonSchemaGenerator else StandardJsonSchemaGenerator,
            examples = examples,
        )


        val openAiStructure = JsonStructuredData.createJsonStructure<T>(
            schemaGenerator = if (basic) OpenAIBasicJsonSchemaGenerator else OpenAIStandardJsonSchemaGenerator,
            examples = examples
        )

        val googleStructure = JsonStructuredData.createJsonStructure<T>(
            schemaGenerator = if (basic) GoogleBasicJsonSchemaGenerator else GoogleStandardJsonSchemaGenerator,
            examples = examples
        )

        val config = StructuredOutputConfig(
            byProvider = mapOf(
                // Native modes leveraging native structured output support in models, with custom definitions for LLM providers that might have different format.
                LLMProvider.OpenAI to StructuredOutput.Native(openAiStructure),
                LLMProvider.Google to StructuredOutput.Native(googleStructure),
                // Anthropic does not support native structured output yet.
                LLMProvider.Anthropic to StructuredOutput.Manual(genericStructure),
            ),

            // Fallback manual structured output mode, via explicit prompting with additional message, not native model support
            default = StructuredOutput.Manual(genericStructure),

            // Helper parser to attempt a fix if a malformed output is produced.
            fixingParser = StructureFixingParser(
                fixingModel = fixModel,
                retries = 2,
            ),
        )
        return config
    }
}


// At the runtime, this is used to create agent based on augmentation.
data class KoogSystem1Builder(val model: ModelConfig) : ISystem1Builder {

    inline fun <reified T> build(
        session: UserSession,
        augmentation: Augmentation
    ): IFuncComponent<T> {
        val agent = build<T>(model, augmentation)
        return KoogFunction<T>(session, agent)
    }

    override suspend fun renderThinking(
        session: UserSession,
        clasName: String,
        methodName: String,
        augmentation: Augmentation
    ) {
        TODO("Not yet implemented")
    }


    // These are like static method
    companion object {
        val logger = LoggerFactory.getLogger(KoogSystem1Builder::class.java)
        // use shared state for now.
        val executors = ConcurrentHashMap<String, PromptExecutor>()

        fun getPromptExecutor(model: ModelConfig): PromptExecutor {
            if (model.family !in executors.keys) {
                val executor = when (model.family) {
                    "openai" -> simpleOpenAIExecutor(model.apikey!!)
                    "gemini" -> simpleGoogleAIExecutor(model.apikey!!)
                    else -> throw RuntimeException("Unsupported model family.")
                }
                executors[model.family] = executor
            }
            return executors[model.family]!!
        }


        inline fun <reified  T> createStrategy(basic: Boolean): AIAgentGraphStrategy<Unit, T> {
             val agentStrategy = strategy<Unit, T>("default structure output strategy") {
                    val prepareRequest by node<Unit, String> {
                        "" // System prompt already carries the instruction; no user message.
                    }

                    @Suppress("DuplicatedCode")
                    val getStructuredOutput by nodeLLMRequestStructured(
                        config = StructureOutputConfigurator.getConfig<T>(basic)
                    )

                    nodeStart then prepareRequest then getStructuredOutput
                    edge(getStructuredOutput forwardTo nodeFinish transformed { it.getOrThrow().structure })
                }
            return agentStrategy
        }

        fun buildLLModel(model: ModelConfig): LLModel {
            return when(model.family) {
                "openai" -> when(model.label) {
                    "gtp4o" -> OpenAIModels.Chat.GPT4o
                    else -> throw RuntimeException("Unsupported model")
                }
                else -> throw RuntimeException("Unsupported model")
            }
        }

        inline fun <reified Output> build(
            model: ModelConfig,
            augmentation: Augmentation,
            toolRegistry: ToolRegistry = ToolRegistry{},
        ): AIAgent<Unit, Output> {

            val promptExecutor = getPromptExecutor(model)
            val llModel = buildLLModel(model)
            val instruction = augmentation.instruction

            return AIAgent(
                promptExecutor = promptExecutor,
                systemPrompt = instruction,
                llmModel =  llModel,
                toolRegistry = toolRegistry,
                temperature = model.temperature?.toDouble() ?: 0.0,
                strategy = createStrategy(augmentation.basicOutput)
            )
        }
    }
}
