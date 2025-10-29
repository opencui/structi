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
import com.fasterxml.jackson.databind.node.ArrayNode
import io.opencui.core.UserSession
import io.opencui.provider.ProviderInvokeException
import io.opencui.serialization.Converter
import io.opencui.serialization.Json
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap


// At the runtime, this is used to create agent based on augmentation.
data class KoogSystem1Builder(val model: ModelConfig) : ISystem1Builder {

    override fun build(
        session: UserSession,
        augmentation: Augmentation
    ): ISystem1Component {
        TODO("Not yet implemented")
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

        private data class StreamAccumulator(
            val builder: StringBuilder = StringBuilder(),
            var lastEmittedLength: Int = 0
        )

        fun buildPromptExecutor(model: ModelConfig): PromptExecutor {
            return when(model.family) {
                "openai" -> simpleOpenAIExecutor(model.apikey!!)
                else -> throw RuntimeException("Unsupported model family.")
            }
        }

        // this will need to be selected based on structure of
        fun buildBasicSchemaGenerator(model: ModelConfig) : JsonSchemaGenerator{
            return when(model.family) {
                "openai" -> OpenAIBasicJsonSchemaGenerator
                "gemini" -> GoogleBasicJsonSchemaGenerator
                else -> BasicJsonSchemaGenerator
            }
        }

        fun buildStandardSchemaGenerator(model: ModelConfig) : JsonSchemaGenerator{
            return when(model.family) {
                "openai" -> OpenAIStandardJsonSchemaGenerator
                "gemini" -> GoogleStandardJsonSchemaGenerator
                else -> StandardJsonSchemaGenerator
            }
        }


        inline fun <reified  T> createStrategy(complex: Boolean, augmentation: Augmentation, model: ModelConfig): AIAgentGraphStrategy<String, T> {
             val agentStrategy = strategy<String, T>("default structure output strategy") {
                    val prepareRequest by node<String, String> { request -> augmentation.instruction }

                    @Suppress("DuplicatedCode")
                    val getStructuredOutput by nodeLLMRequestStructured(
                        config = StructuredOutputConfig(
                            // Fallback manual structured output mode, via explicit prompting with additional message, not native model support
                            default = getStructuredOutput<T>(complex, model),
                            byProvider = mapOf(),
                            // Helper parser to attempt a fix if a malformed output is produced.
                            fixingParser = StructureFixingParser(
                                fixingModel = AnthropicModels.Haiku_3_5,
                                retries = 2,
                            ),
                        )
                    )

                    nodeStart then prepareRequest then getStructuredOutput
                    edge(getStructuredOutput forwardTo nodeFinish transformed { it.getOrThrow().structure })
                }
            return agentStrategy
        }

        inline fun <reified T> buildJsonStructure(complex: Boolean, model: ModelConfig, examples: List<T>): JsonStructuredData<T> {
            val schemaGenerator = if (complex) buildStandardSchemaGenerator(model) else buildBasicSchemaGenerator(model)
            val structure = JsonStructuredData.createJsonStructure<T>(
                // Some models might not work well with json schema, so you may try simple, but it has more limitations (no polymorphism!)
                schemaGenerator = schemaGenerator,
                examples = examples
            )
            return structure
        }


        inline fun <reified T> getStructuredOutput(complex: Boolean, model: ModelConfig, examples : List<T> = emptyList()): StructuredOutput<T> {
            val llmProvider = getLLMProvider(model)
            val structure = buildJsonStructure(complex, model, examples)

            return when(llmProvider) {
                LLMProvider.OpenAI -> StructuredOutput.Native(structure)
                LLMProvider.Google -> StructuredOutput.Native(structure)
                else -> StructuredOutput.Manual(structure)
            }
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

        fun getLLMProvider(model: ModelConfig) : LLMProvider {
            return when(model.family) {
                "openai" -> LLMProvider.OpenAI
                "gemini" -> LLMProvider.Google
                "qwen" -> LLMProvider.Alibaba
                "llama" -> LLMProvider.Meta
                else -> throw RuntimeException("Unsupported model family")
            }
        }

        fun buildAIAgentConfig(augmentation: Augmentation, model: ModelConfig) = AIAgentConfig(
            prompt = prompt("weather-forecast-with-tools") {
                system(augmentation.instruction)
            },
            model = buildLLModel(model),
            maxAgentIterations = 10
        )

        inline fun <reified Output> build(
            model: ModelConfig,
            instruction: String,
            toolRegistry: ToolRegistry = ToolRegistry{},
            strategy: AIAgentFunctionalStrategy<String, Output>
        ): AIAgent<String, Output> {

            val promptExecutor = buildPromptExecutor(model)
            val llModel = buildLLModel(model)

            return AIAgent(
                promptExecutor = promptExecutor,
                systemPrompt = instruction,
                llmModel =  llModel,
                toolRegistry = toolRegistry,
                temperature = model.temperature?.toDouble() ?: 0.0,
                strategy = strategy
            )
        }
    }
}
