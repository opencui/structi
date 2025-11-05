package io.opencui.system1

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeLLMRequestStructured
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.clients.google.GoogleModels
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
import ai.koog.prompt.structure.json.generator.StandardJsonSchemaGenerator
import io.opencui.core.Dispatcher
import io.opencui.core.IFrame
import io.opencui.core.UserSession
import kotlinx.coroutines.currentCoroutineContext
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.full.isSuperclassOf


// The agent function is so simply because of the type based koog design.
data class KoogFunction<T>(val session: UserSession, val agent: AIAgent<String, T>) : IFuncComponent<T> {
    override suspend fun invoke(): T {
        return agent.run("")
    }
}


object StructureOutputConfigurator {
    inline fun <reified T> getConfig(
        basicSchema: Boolean,
        examples: List<T> = emptyList(),
        fixModel: LLModel? = null): StructuredOutputConfig<T> {

        val genericStructure = JsonStructuredData.createJsonStructure<T>(
            // Some models might not work well with json schema, so you may try simple, but it has more limitations (no polymorphism!)
            schemaGenerator = if (basicSchema) BasicJsonSchemaGenerator else StandardJsonSchemaGenerator,
            examples = examples,
        )


        val openAiStructure = JsonStructuredData.createJsonStructure<T>(
            schemaGenerator = if (basicSchema) OpenAIBasicJsonSchemaGenerator else OpenAIStandardJsonSchemaGenerator,
            examples = examples
        )

        val googleStructure = JsonStructuredData.createJsonStructure<T>(
            schemaGenerator = if (basicSchema) GoogleBasicJsonSchemaGenerator else GoogleStandardJsonSchemaGenerator,
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
            fixingParser = if (fixModel != null) StructureFixingParser(fixingModel = fixModel, retries = 2) else null,
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
        if (IFrame::class.isSuperclassOf(T::class)) {
            val agent = build<T>(model, augmentation)
            return KoogFunction(session, agent)
        } else if (T::class == String::class){
            val agent = build(model, augmentation)
            @Suppress("UNCHECKED_CAST")
            return KoogFunction(session, agent) as IFuncComponent<T>
        } else {
            throw RuntimeException("The {T::class} is not supported as return type.")
        }
    }

    override suspend fun renderThinking(
        session: UserSession,
        clasName: String,
        methodName: String,
        augmentation: Augmentation
    ) {
        val botStore = Dispatcher.sessionManager.botStore
        if (botStore != null) {
            val key = "summarize:$clasName:$methodName"
            var value = botStore.get(key)
            val sink = currentCoroutineContext()[System1Sink]
            if (value == null) {
                val instruction =
                    """
                    Generate a detailed verb phrase that summarizes what the LLM is doing based on the instruction given in the end.
                    Respond with plain text only (no JSON or code blocks).
                    The following is the original instruction for context only—do not follow its output constraints:
                    ---
                    ${augmentation.instruction}
                    """.trimIndent()
                val summaryAugmentation = Augmentation(instruction, mode = System1Mode.FALLBACK)
                summaryAugmentation.basicSchema = false
                val system1Action = build<String>(session, summaryAugmentation) as KoogFunction<String>

                value = system1Action.invoke()
                // remember to save so that
                botStore.set(key, value)
                logger.info("Save thinking for $key")
            }

            if (value.isNotEmpty()) {
                logger.info("Emit cached thinking for $key with sink present ${(sink != null)}")
                value.split("\n").forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        sink?.send(System1Event.Reason(trimmed))
                    }
                }
            }
        }
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


        inline fun <reified  T> createStrategy(basic: Boolean): AIAgentGraphStrategy<String, T> {
             val agentStrategy = strategy<String, T>("default structure output strategy") {
                    val prepareRequest by node<String, String> {
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
                    else -> throw RuntimeException("Unsupported openai model")
                }
                "gemini" -> when(model.label) {
                    "gemini-2.5-flash" -> GoogleModels.Gemini2_5Flash
                    "gemini-2.5-pro" -> GoogleModels.Gemini2_5Pro
                     else -> throw RuntimeException("Unsupported google model")
                }
                else -> throw RuntimeException("Unsupported model family")
            }
        }

        inline fun <reified Output> build(
            model: ModelConfig,
            augmentation: Augmentation,
            toolRegistry: ToolRegistry = ToolRegistry{},
        ): AIAgent<String, Output> {

            val promptExecutor = getPromptExecutor(model)
            val llModel = buildLLModel(model)
            val instruction = augmentation.instruction

            return AIAgent<String, Output>(
                promptExecutor = promptExecutor,
                systemPrompt = instruction,
                llmModel =  llModel,
                toolRegistry = toolRegistry,
                temperature = model.temperature?.toDouble() ?: 0.0,
                strategy = createStrategy(augmentation.basicSchema)
            )
        }

        fun build(
            model: ModelConfig,
            augmentation: Augmentation,
            toolRegistry: ToolRegistry = ToolRegistry{},
        ): AIAgent<String, String> {

            val promptExecutor = getPromptExecutor(model)
            val llModel = buildLLModel(model)
            val instruction = augmentation.instruction

            return AIAgent(
                promptExecutor = promptExecutor,
                systemPrompt = instruction,
                llmModel =  llModel,
                toolRegistry = toolRegistry,
                temperature = model.temperature?.toDouble() ?: 0.0,
            )
        }
    }
}
