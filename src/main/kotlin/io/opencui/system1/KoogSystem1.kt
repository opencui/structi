package io.opencui.system1

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.AIAgentFunctionalStrategy
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.google.structure.GoogleBasicJsonSchemaGenerator
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.openai.base.structure.OpenAIBasicJsonSchemaGenerator
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.structure.json.JsonStructuredData
import ai.koog.prompt.structure.json.generator.BasicJsonSchemaGenerator
import ai.koog.prompt.structure.json.generator.JsonSchemaGenerator
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



data class KoogAugmentContext(
    val inputSchema: Schema? = null,
    val outputSchema: Schema? = null
): AugmentContext


//

// The content for runner:
// For Action, the content can be user said nothing.
// For Fallback, the content can be user utterance.
// For Function, the content is the json encode of input.
//
//
// Input should be handled as input, members should be handled as state.
// Instead of using {} to refer to member data, build will user kotlin template ${}.
data class KoogFunction(val session: UserSession, val model: ModelConfig, val augmentation: Augmentation) : StructComponent {
    var inputs: Map<String, Any?>? = null

    @Throws(ProviderInvokeException::class)
    override fun invoke(): Flow<System1Event> = flow {
        logger.info("start Koog function.")
        val context = augmentation.context as KoogAugmentContext
        val inputStr = Json.encodeToString(inputs)
        val userMsg = Content.fromParts(Part.fromText(inputStr))

        val label = "function agent"

        // First we need to set up schema for input and output.
        val agent = KoogSystem1Builder.Companion.build(
            label,
            model,
            augmentation.instruction,
            context.inputSchema,
            context.outputSchema,

        )

        // Now we need to get input into agent state (only for input), the slot is embedded in instruction.
        val initialState = ConcurrentHashMap<String, Any>()

        KoogSystem1Builder.sessionService.createSession(
            label,
            session.userId!!,
            initialState,
            session.sessionId!!
        ).blockingGet()

        val runner = Runner(
            agent,
            label,
            KoogSystem1Builder.artifactService,
            KoogSystem1Builder.sessionService
        )

        val agentFlow = KoogSystem1Builder.callAgentAsync(
            userMsg,
            runner,
            session.userId!!,
            session.sessionId!!,
            jsonOutput = false
        )

        agentFlow.collect { emit(it) }

        val sessionService = runner.sessionService()
        val sessionInRunner = sessionService
            .getSession(label, session.userId!!, session.sessionId!!, Optional.empty())
            .blockingGet()

        val value = sessionInRunner?.state()?.get(RESULTKEY)
        logger.info("Koogfunc get: {}", value)

        if (value != null) {
            emit(System1Event.Result(Json.encodeToJsonElement(value)))
        } else {
            emit(System1Event.Result())
        }
    }

    @Throws(ProviderInvokeException::class)
    suspend fun <T> svInvoke(converter: Converter<T>): T = coroutineScope {
        val sink = currentCoroutineContext()[System1Sink]
        var resultEvent: System1Event.Result? = null

        invoke().collect { event ->
            when (event) {
                is System1Event.Result -> {
                    if (resultEvent != null) {
                        throw ProviderInvokeException("there is more than 1 result, expected only 1 result.")
                    }
                    resultEvent = event
                }
                else -> sink?.send(event)
            }
        }

        val finalResult = resultEvent
            ?: throw ProviderInvokeException("there is 0 results, expected only 1 result.")

        return@coroutineScope converter(finalResult.result)
    }

    @Throws(ProviderInvokeException::class)
    suspend fun <T> mvInvoke(converter: Converter<T>): List<T> = coroutineScope {
        val sink = currentCoroutineContext()[System1Sink]
        var resultEvent: System1Event.Result? = null

        invoke().collect { event ->
            when (event) {
                is System1Event.Result -> {
                    if (resultEvent != null) {
                        throw ProviderInvokeException("there is more than 1 result, expected only 1 result.")
                    }
                    resultEvent = event
                }
                else -> sink?.send(event)
            }
        }

        val finalResult = resultEvent
            ?: throw ProviderInvokeException("there is 0 results, expected only 1 result.")

        val jsonArray = finalResult.result as? ArrayNode
            ?: throw ProviderInvokeException("function result is not an array as expected.")

        return@coroutineScope jsonArray.map { converter(it) }
    }

    companion object{
        const val RESULTKEY = "result"
        val logger = LoggerFactory.getLogger(KoogFunction::class.java)
    }
}


data class KoogFallback(val session: UserSession, val model: ModelConfig, val augmentation: Augmentation) : ResponseComponent {
    override fun invoke(): Flow<System1Event> {
        val userInput = session.currentUtterance() ?: ""

        val userMsg = Content.fromParts(Part.fromText(userInput))
        val label = "fall back agent"
        emptyList<BaseTool>()
        val agent = KoogSystem1Builder.Companion.build(label, model, augmentation.instruction)


        val userId = session.userId
        val sessionId = session.sessionId
        // Now we need to get input into agent state (only for input), the slot is embedded in instruction.
        val initialState = ConcurrentHashMap<String, Any>()

        // KoogSession to hold the input.
        val KoogSession = KoogSystem1Builder.sessionService.createSession(
            label,
            session.userId!!,
            initialState,
            session.sessionId!!
        ).blockingGet()

        val runner = Runner(
            agent,
            label,
            KoogSystem1Builder.artifactService,
            KoogSystem1Builder.sessionService )

        return KoogSystem1Builder.callAgentAsync(userMsg, runner, userId!!, sessionId!!)
    }
}


data class KoogAction(val session: UserSession, val model: ModelConfig, val augmentation: Augmentation) : ResponseComponent {
    override fun invoke(): Flow<System1Event> {
        val label = "action agent"
        val agent = KoogSystem1Builder.build(label, model, augmentation.instruction, tools = emptyList())


        val userId = session.userId
        val sessionId = session.sessionId

        // Now we need to get input into agent state (only for input), the slot is embedded in instruction.
        val initialState = ConcurrentHashMap<String, Any>()

        // KoogSession to hold the input.
        val KoogSession = KoogSystem1Builder.sessionService.createSession(
            label,
            session.userId!!,
            initialState,
            session.sessionId!!
        ).blockingGet()

        val runner = Runner(
            agent,
            label,
            KoogSystem1Builder.artifactService,
            KoogSystem1Builder.sessionService )

        // For action, agent are supposedly only take structured input in the prompt, to generate the response.
        val userMsg = Content.fromParts(Part.fromText(""))
        return KoogSystem1Builder.callAgentAsync(userMsg, runner, userId!!, sessionId!!)
    }
}


// At the runtime, this is used to create agent based on augmentation.
data class KoogSystem1Builder(val model: ModelConfig) : ISystem1Builder {

    override fun build(
        session: UserSession,
        augmentation: Augmentation
    ): ISystem1Component {
        return when (augmentation.mode) {
            System1Mode.FALLBACK -> KoogFallback(session, model, augmentation)
            System1Mode.ACTION -> KoogAction(session, model, augmentation)
            System1Mode.FUNCTION -> KoogFunction(session, model, augmentation)
        }
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

        //
        fun buildSchemaGenerator(model: ModelConfig) : JsonSchemaGenerator{
            return when(model.family) {
                "openai" -> OpenAIBasicJsonSchemaGenerator
                "gemini" -> GoogleBasicJsonSchemaGenerator
                else -> BasicJsonSchemaGenerator
            }
        }

        inline fun <reified T> buildJsonStructure(value: T, model: ModelConfig, exampleForecasts: List<T>): JsonStructuredData<T> {
            val schemaGenerator = buildSchemaGenerator(model)
            val structure = JsonStructuredData.createJsonStructure<T>(
                // Some models might not work well with json schema, so you may try simple, but it has more limitations (no polymorphism!)
                schemaGenerator = schemaGenerator,
                examples = exampleForecasts
            )
            return structure
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

        inline fun <reified Input, reified Output> build(
            model: ModelConfig,
            instruction: String,
            toolRegistry: ToolRegistry = ToolRegistry{},
            strategy: AIAgentFunctionalStrategy<Input, Output>
        ): AIAgent<Input, Output> {

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
