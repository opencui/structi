package io.opencui.system1

import com.fasterxml.jackson.databind.node.ArrayNode
import com.google.genai.types.Schema
import com.google.adk.agents.BaseAgent
import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.artifacts.InMemoryArtifactService
import com.google.adk.events.Event
import com.google.adk.runner.Runner
import com.google.adk.sessions.InMemorySessionService
import com.google.adk.tools.BaseTool
import com.google.genai.types.Content
import com.google.genai.types.Part
import com.google.genai.types.GenerateContentConfig;
import io.opencui.core.UserSession
import io.opencui.serialization.*
import io.opencui.provider.ProviderInvokeException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Optional
import kotlinx.coroutines.reactive.asFlow
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException


// This can be used.
data class AdkAugmentContext(
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
data class AdkFunction(val session: UserSession, val model: ModelConfig,  val augmentation: Augmentation) : StructComponent {
    var inputs: Map<String, Any?>? = null

    @Throws(ProviderInvokeException::class)
    override fun invoke(): Flow<System1Event> = flow {
        logger.info("start adk function.")
        val context = augmentation.context as AdkAugmentContext
        val inputStr = Json.encodeToString(inputs)
        val userMsg = Content.fromParts(Part.fromText(inputStr))

        val label = "function agent"

        // First we need to set up schema for input and output.
        val agent = AdkSystem1Builder.Companion.build(
            label,
            model,
            augmentation.instruction,
            context.inputSchema,
            context.outputSchema,
            selfContained = true
        )

        // Now we need to get input into agent state (only for input), the slot is embedded in instruction.
        val initialState = ConcurrentHashMap<String, Any>()

        AdkSystem1Builder.sessionService.createSession(
            label,
            session.userId!!,
            initialState,
            session.sessionId!!
        ).blockingGet()

        val runner = Runner(
            agent,
            label,
            AdkSystem1Builder.artifactService,
            AdkSystem1Builder.sessionService
        )

        val agentFlow = AdkSystem1Builder.callAgentAsync(
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
        logger.info("adkfunc get: {}", value)

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
        val logger = LoggerFactory.getLogger(AdkFunction::class.java)
    }
}


data class AdkFallback(val session: UserSession, val model: ModelConfig, val augmentation: Augmentation) : ResponseComponent {
    override fun invoke(): Flow<System1Event> {
        val userInput = session.currentUtterance() ?: ""

        val userMsg = Content.fromParts(Part.fromText(userInput))
        val label = "fall back agent"
        emptyList<BaseTool>()
        val agent = AdkSystem1Builder.Companion.build(label, model, augmentation.instruction)


        val userId = session.userId
        val sessionId = session.sessionId
        // Now we need to get input into agent state (only for input), the slot is embedded in instruction.
        val initialState = ConcurrentHashMap<String, Any>()

        // adkSession to hold the input.
        val adkSession = AdkSystem1Builder.sessionService.createSession(
            label,
            session.userId!!,
            initialState,
            session.sessionId!!
        ).blockingGet()

        val runner = Runner(
            agent,
            label,
            AdkSystem1Builder.artifactService,
            AdkSystem1Builder.sessionService )

        return AdkSystem1Builder.callAgentAsync(userMsg, runner, userId!!, sessionId!!)
    }
}


data class AdkAction(val session: UserSession, val model: ModelConfig, val augmentation: Augmentation) : ResponseComponent {
    override fun invoke(): Flow<System1Event> {
        val label = "action agent"
        val agent = AdkSystem1Builder.build(label, model, augmentation.instruction, tools = emptyList())


        val userId = session.userId
        val sessionId = session.sessionId

        // Now we need to get input into agent state (only for input), the slot is embedded in instruction.
        val initialState = ConcurrentHashMap<String, Any>()

        // adkSession to hold the input.
        val adkSession = AdkSystem1Builder.sessionService.createSession(
            label,
            session.userId!!,
            initialState,
            session.sessionId!!
        ).blockingGet()

        val runner = Runner(
            agent,
            label,
            AdkSystem1Builder.artifactService,
            AdkSystem1Builder.sessionService )

        // For action, agent are supposedly only take structured input in the prompt, to generate the response.
        val userMsg = Content.fromParts(Part.fromText(""))
        return AdkSystem1Builder.callAgentAsync(userMsg, runner, userId!!, sessionId!!)
    }
}


// At the runtime, this is used to create agent based on augmentation.
data class AdkSystem1Builder(val model: ModelConfig) : ISystem1Builder {

    override fun build(
        session: UserSession,
        augmentation: Augmentation
    ): ISystem1Component {
        return when (augmentation.mode) {
            System1Mode.FALLBACK -> AdkFallback(session, model, augmentation)
            System1Mode.ACTION -> AdkAction(session, model, augmentation)
            System1Mode.FUNCTION -> AdkFunction(session, model, augmentation)
        }
    }

    // These are like static method
    companion object {
        val logger = LoggerFactory.getLogger(AdkSystem1Builder::class.java)
        // use shared state for now.
        val sessionService = InMemorySessionService()
        val artifactService = InMemoryArtifactService()

        private data class StreamAccumulator(
            val builder: StringBuilder = StringBuilder(),
            var lastEmittedLength: Int = 0
        )

        fun build(
            label: String,
            model: ModelConfig,
            instruction: String,
            inputSchema: Schema? = null,
            outputSchema: Schema? = null,
            tools: List<BaseTool>? = null,
            selfContained: Boolean = false
        ): BaseAgent {
            // This copy the configuration to adk agent.
            val config = GenerateContentConfig.builder()
                .apply {
                    if (model.temperature != null) {
                        this.temperature(model.temperature)
                    }
                    if (model.topK != null) {
                        this.topK(model.topK.toFloat())
                    }
                    if (model.maxOutputTokens != null) {
                        this.maxOutputTokens(model.maxOutputTokens)
                    }
                }
                .build()

            return LlmAgent.builder()
                .name(label)
                .model(model.label)  // for adk, we only use label, but we should make sure the family.
                .instruction(instruction)
                .apply {
                    if (inputSchema != null) {
                        this.inputSchema(inputSchema)
                    }
                    if (outputSchema != null) {
                        this.outputSchema(outputSchema)
                        this.outputKey("result")
                    }
                    if (tools != null) {
                        this.tools(tools)
                    }
                    if (selfContained) {
                        this.disallowTransferToPeers(true)
                        this.disallowTransferToParent(true)
                    }
                }
                .generateContentConfig(config)
                .build()
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

                // Use Google ADK Java async streaming
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
                logger.info("ADK run cancelled: ${e.stackTraceToString()}")
            } catch (e: Exception) {
                logger.error("Error processing ADK events: ${e.message}", e)
                emit(System1Event.Error(e.message.toString()))
            }
        }
    }
}
