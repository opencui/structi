package io.opencui.system1

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.AIAgentFunctionalStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
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
import kotlinx.serialization.serializer

data class KoogAugmentContext(
    val inputSchema: Schema? = null,
    val outputSchema: Schema? = null
): AugmentContext


//
// For now, we only support LLMAgent as left module, so there is no need for collaboration between agents.
// Since the LLM execute the things from scratch every time, and then there are prefix based caching, so
// for now, we assume that instruction are instantiated with slot value every time it is evaluated.
// We will not be using {} from state for collaboration purpose. In other words, instruction capture slot values.
//
// There are two thing we need to do: create agent (create schema, prepare input, and then run it and handle output).
// it will be used for two different use cases: action, and function.
//
// but we will really build Llm Agent.

//
// Question, for fallback, should we allow build to use slot value? if we do, should we keep
// history? For now, we assume that they can, but the history is not useful. As we are use it
// a single turn solution.
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
            selfContained = true
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
            label: String,
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

        // Now we return three different message: json for function, text for action/fallback, and error
        // The system 1 inform is just an envelope, its type is only useful for figuring out the source.
        fun callAgentAsync(
            content: Content,  // for action, this should be empty, for
            runner: Runner,
            userId: String,
            sessionId: String,
            jsonOutput: Boolean = false,
            bufferUp: Boolean = false
        ): Flow<System1Event> = flow {
            logger.info("User Query: $content")
            // Not really useful, but keep it for now.

            val textAccumulators = mutableMapOf<Pair<String, String>, StreamAccumulator>()
            try {
                // We need to config the runner later.
                val runConfig = RunConfig.builder().build()

                // Use Google Koog Java async streaming
                val eventFlow = runner.runAsync(userId, sessionId, content, runConfig).asFlow()

                eventFlow.collect { event: Event ->
                    if (event.author() == "user") return@collect

                    val currentBufferKey = Pair(event.invocationId(), event.author())

                    logger.info("Processing event: author={}, partial={}", event.author(), event.partial())

                    val parts = event.content().orElse(null)?.parts()?.orElse(null)
                    parts?.firstOrNull()?.text()?.orElse(null)?.let { textPart ->
                        val isPartial = event.partial().orElse(false)

                        if (jsonOutput) {
                            val accumulator = textAccumulators.getOrPut(currentBufferKey) { StreamAccumulator() }
                            accumulator.builder.append(textPart)

                            if (!isPartial) {
                                val resultPayload = accumulator.builder.toString().trim()
                                textAccumulators.remove(currentBufferKey)
                                try {
                                    emit(System1Event.Result(Json.parseToJsonElement(resultPayload)))
                                } catch (e: Exception) {
                                    logger.warn("JSON parse error for final text from ${resultPayload}: ${e.message}")
                                    emit(System1Event.Error(e.message.toString()))
                                }
                            }
                        } else {
                            val accumulator = textAccumulators.getOrPut(currentBufferKey) { StreamAccumulator() }
                            accumulator.builder.append(textPart)

                            val shouldEmitPartial = !bufferUp && isPartial
                            val shouldEmitFinal = !isPartial

                            if (shouldEmitPartial) {
                                val toEmit = accumulator.builder.substring(accumulator.lastEmittedLength)
                                if (toEmit.isNotEmpty()) {
                                    accumulator.lastEmittedLength = accumulator.builder.length
                                    emit(System1Event.Reason(toEmit))
                                }
                            }

                            if (shouldEmitFinal) {
                                val previouslyEmitted = accumulator.lastEmittedLength
                                val finalText = when {
                                    bufferUp -> accumulator.builder.toString().trim()
                                    previouslyEmitted == 0 -> accumulator.builder.toString().trim()
                                    else -> accumulator.builder.substring(previouslyEmitted)
                                }
                                accumulator.lastEmittedLength = accumulator.builder.length
                                textAccumulators.remove(currentBufferKey)
                                if (finalText.isNotEmpty()) {
                                    emit(System1Event.Response(finalText))
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                logger.info("Koog run cancelled: ${e.stackTraceToString()}")
            } catch (e: Exception) {
                logger.error("Error processing Koog events: ${e.message}", e)
                emit(System1Event.Error(e.message.toString()))
            }
        }
    }
}
