package io.opencui.system1

import com.fasterxml.jackson.databind.node.ArrayNode
import com.google.adk.JsonBaseModel
import com.google.adk.SchemaUtils
import com.google.genai.types.Schema
import com.google.adk.agents.BaseAgent
import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.events.Event
import com.google.adk.runner.InMemoryRunner
import com.google.adk.runner.Runner
import com.google.adk.sessions.Session
import com.google.adk.tools.Annotations
import com.google.adk.tools.BaseTool
import com.google.adk.tools.FunctionTool
import com.google.adk.tools.ToolContext
import com.google.genai.types.Content
import com.google.genai.types.Part
import io.reactivex.rxjava3.core.Flowable
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Scanner
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import com.google.genai.types.GenerateContentConfig;
import io.opencui.core.CachedMethod3Raw
import io.opencui.core.Emitter
import io.opencui.core.UserSession
import io.opencui.serialization.*
import io.opencui.provider.ProviderInvokeException
import io.reactivex.rxjava3.core.Single
import java.util.Optional
import kotlinx.coroutines.reactive.asFlow
import org.slf4j.LoggerFactory
import java.io.Serializable
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

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
data class AdkConnection(val model: ModelConfig): Serializable {
    // To make this work:
    // create schema
    // create agent,
    // run it with emitter
    // collect value.
    // return value
    // depends on where it is invoked, we do it in the right way.
    var inputSchema: Schema? = null
    var outputSchema: Schema? = null


    // If we find URL, we use it, other
    @Throws(ProviderInvokeException::class)
    fun invoke(session: UserSession, inputs: Map<String, Any?>, augmentation: Augmentation, emitter: Emitter<System1Event>? = null): JsonElement? {
        val inputStr = Json.encodeToString(inputs)
        val userMsg = Content.fromParts(Part.fromText(inputStr))

        val label = "fall back agent"

        // First we need to set up schema for input and output.
        val agent = AdkSystem1Builder.buildForFunc(label, model, augmentation.instruction, inputSchema, outputSchema)

        // Now we need to get input into agent state (only for input), the slot is embedded in instruction.
        val runner = InMemoryRunner(agent)

        val userId = session.userId!!
        val sessionId = session.sessionId!!

        runBlocking {
            AdkSystem1Builder.callAgentAsync(userMsg, runner, userId, sessionId, emitter)
        }

        // now we need to get result from agent.
        val sessionService = runner.sessionService()
        val sessionInRunner = sessionService.getSession(label, userId, sessionId, Optional.empty()).blockingGet()
        return sessionInRunner?.state()?.get("result") as JsonElement?
    }

    @Throws(ProviderInvokeException::class)
    fun <T> svInvoke(session: UserSession, functionMeta: Map<String, Any?>, augmentation: Augmentation, converter: Converter<T>): T {
        val result = invoke(session, functionMeta, augmentation)!!
        assert(result is JsonObject)
        return converter(result)
    }

    @Throws(ProviderInvokeException::class)
    fun <T> mvInvoke(session: UserSession, functionMeta: Map<String, Any?>, augmentation: Augmentation, converter: Converter<T>): List<T> {
        val result = invoke(session, functionMeta, augmentation)!!
        assert(result is ArrayNode)
        val results = mutableListOf<T>()
        result.map { converter(it) }
        return results
    }

    fun close() {
        TODO("Not yet implemented")
    }


    companion object{
        const val URL = "url"
        val logger = LoggerFactory.getLogger(AdkConnection::class.java)
    }

}


data class AdkFallback(val session: UserSession, val model: ModelConfig, val augmentation: Augmentation) : ISystem1Executor {
    override fun invoke(emitter: Emitter<System1Event>?): JsonElement? {
        val userInput = session.currentUtterance() ?: ""

        val userMsg = Content.fromParts(Part.fromText(userInput))
        val label = "fall back agent"
        val agent = AdkSystem1Builder.buildForFallback(label, model, augmentation.instruction, emptyList())
        val runner = InMemoryRunner(agent)

        val userId = session.userId
        val sessionId = session.sessionId

        runBlocking {
            AdkSystem1Builder.callAgentAsync(userMsg, runner, userId!!, sessionId!!, emitter)
        }
        return null
    }

}

data class AdkAction(val session: UserSession, val model: ModelConfig, val augmentation: Augmentation) : ISystem1Executor {
    override fun invoke(emitter: Emitter<System1Event>?): JsonElement? {
        val label = "fall back agent"
        val agent = AdkSystem1Builder.buildForAction(label, model, augmentation.instruction, emptyList())
        val runner = InMemoryRunner(agent)

        val userId = session.userId
        val sessionId = session.sessionId
        // For action, agent are supposedly only take structured input in the prompt, to generate the response.
        val userMsg = Content.fromParts(Part.fromText(""))
        runBlocking {
            AdkSystem1Builder.callAgentAsync(userMsg, runner, userId!!, sessionId!!, emitter)
        }
        return null
    }
}

// This need to be processed.
data class AdkFunction(val session: UserSession, val model: ModelConfig, val augmentation: Augmentation) : ISystem1Executor {
    override fun invoke(emitter: Emitter<System1Event>?): JsonElement? {
        TODO("Not yet implemented")
    }
}


data class AdkSystem1Builder(val model: ModelConfig) : ISystem1Builder {

    override fun build(
        session: UserSession,
        augmentation: Augmentation
    ): ISystem1Executor {
        return when (augmentation.mode) {
            System1Mode.FALLBACK -> AdkFallback(session, model,augmentation)
            System1Mode.ACTION -> AdkAction(session, model, augmentation)
            System1Mode.FUNCTION -> AdkFunction(session, model, augmentation)
        }
    }


    // These are like static method
    companion object {
        val logger = LoggerFactory.getLogger(AdkSystem1Builder::class.java)

        private fun build(
            label: String,
            model: ModelConfig,
            instruction: String,
            inputSchema: Schema? = null,
            outputSchema: Schema? = null,
            tools: List<BaseTool>? = null
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
                }
                .generateContentConfig(config)
                .build()
        }

        fun buildForFunc(
            label: String,
            model: ModelConfig,
            instruction: String,
            inputSchema: Schema?,
            outputSchema: Schema?
        ): BaseAgent {
            return build(label, model, instruction, inputSchema, outputSchema)
        }

        fun buildForAction(label: String, model: ModelConfig, instruction: String, tools: List<BaseTool>): BaseAgent {
            return build(label, model, instruction, tools = tools)
        }

        fun buildForFallback(label: String, model: ModelConfig, instruction: String, tools:List<BaseTool>): BaseAgent {
            return build(label, model, instruction)
        }

        suspend fun callAgentAsync(
            content: Content,  // for action, this should be empty, for
            runner: Runner,
            userId: String,
            sessionId: String,
            emitter: Emitter<System1Event>?,
            bufferUp: Boolean = true
        ) {
            logger.info("User Query: $content")
            // Not really useful, but keep it for now.
            val serverSideTextBuffers = mutableMapOf<Pair<String, String>, String>()
            try {
                // We need to config the runner later.
                val runConfig = RunConfig.builder().build()


                // Use Google ADK Java async streaming
                val eventFlow = runner.runAsync(userId, sessionId, content, runConfig).asFlow()

                eventFlow.collect { event: Event ->
                    if (event.author() == "user") return@collect

                    val currentBufferKey = Pair(event.invocationId(), event.author())

                    logger.info("Processing event: author=${event.author()}, partial=${event.partial()}")

                    // Handle text content, only handle the first part?
                    val parts = event.content().orElse(null)?.parts()?.orElse(null)
                    parts?.firstOrNull()?.text()?.orElse(null)?.let { textPart ->
                        val doNotBuffer = !event.partial().orElse(false) || bufferUp

                        if (!doNotBuffer) {
                            serverSideTextBuffers.merge(currentBufferKey, textPart) { old, new -> old + new }
                        } else {
                            val finalText = (serverSideTextBuffers.remove(currentBufferKey) ?: "") + textPart
                            val trimmedText = finalText.trim()

                            // Try to parse as JSON first
                            if (trimmedText.startsWith("{") && trimmedText.endsWith("}")) {
                                try {
                                    val jsonData = Json.parseToJsonElement(trimmedText)
                                    emitter?.invoke(
                                        System1Event(
                                            "json_response", mapOf(
                                                "author" to event.author(),
                                                "data" to jsonData,
                                                "invocation_id" to event.invocationId(),
                                            )
                                        )
                                    )
                                } catch (e: Exception) {
                                    logger.warn("JSON parse error for final text from ${event.author()}: ${e.message}")
                                    emitter?.invoke(
                                        System1Event(
                                            "response", mapOf(
                                                "author" to event.author(),
                                                "source" to "${event.author()}|${event.invocationId()}",
                                                "message" to trimmedText,
                                                "invocation_id" to event.invocationId(),
                                            )
                                        )
                                    )
                                }
                            } else {
                                emitter?.invoke(
                                    System1Event(
                                        "response", mapOf(
                                            "author" to event.author(),
                                            "source" to "${event.author()}|${event.invocationId()}",
                                            "message" to trimmedText,
                                            "invocation_id" to event.invocationId(),
                                        )
                                    )
                                )
                            }
                        }
                    }

                    // Handle artifacts
                    event.actions()?.artifactDelta()?.forEach { (key, value) ->
                        emitter?.invoke(
                            System1Event(
                                "json", mapOf(
                                    "type" to "artifact",
                                    "source" to "${event.author()}|${event.invocationId()}",
                                    "author" to event.author(),
                                    "filename" to key,
                                    "version" to value
                                )
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                logger.error("Error processing ADK events: ${e.message}", e)
                emitter?.invoke(System1Event("stream_error", mapOf("content" to "Error: ${e.message}")))
            }
        }
    }

}


object MultiToolAgent { // Changed to object for static-like behavior in Kotlin

    private const val USER_ID = "student"
    private const val NAME = "multi_tool_agent"

    // The run your agent with Dev UI, the ROOT_AGENT should be a global public static variable.
    @JvmField // Use @JvmField to expose as a static field in Java bytecode
    val ROOT_AGENT: BaseAgent = initAgent()

    private fun initAgent(): BaseAgent {

        val CAPITAL_OUTPUT = Schema.builder()
            .type("OBJECT")
            .description("Schema for capital city information.")
            .properties(
                mapOf(
                    "capital" to Schema.builder()
                        .type("STRING")
                        .description("The capital city of the country.")
                        .build()
                )
            )
            .build()

        return LlmAgent.builder()
            .name(NAME)
            .model("gemini-2.0-flash")
            .description("Agent to answer questions about the time and weather in a city.")
            .instruction(
                "You are a helpful agent who can answer user questions about the time and weather" +
                    " in a city."
            )
            .tools(
                FunctionTool.create(MultiToolAgent::class.java, "getCurrentTime"),
                FunctionTool.create(MultiToolAgent::class.java, "getWeather")
            )
            .build()
    }

    @JvmStatic // Use @JvmStatic to make this function callable statically from Java
    @Annotations.Schema(description = "")
    fun getCurrentTime(
        @Annotations.Schema(description = "The name of the city for which to retrieve the current time")
        city: String
    ): Map<String, String> {
        val normalizedCity = Normalizer.normalize(city, Normalizer.Form.NFD)
            .trim()
            .lowercase()
            .replace("(\\p{IsM}+|\\p{IsP}+)".toRegex(), "")
            .replace("\\s+".toRegex(), "_")

        return ZoneId.getAvailableZoneIds().stream()
            .filter { zid: String -> zid.lowercase().endsWith("/$normalizedCity") }
            .findFirst()
            .map { zid: String ->
                mapOf(
                    "status" to "success",
                    "report" to "The current time in " +
                        city +
                        " is " +
                        ZonedDateTime.now(ZoneId.of(zid))
                            .format(DateTimeFormatter.ofPattern("HH:mm")) +
                        "."
                )
            }
            .orElse(
                mapOf(
                    "status" to "error",
                    "report" to "Sorry, I don't have timezone information for $city."
                )
            )
    }

    @JvmStatic // Use @JvmStatic to make this function callable statically from Java
    fun getWeather(
        @Annotations.Schema(description = "The name of the city for which to retrieve the weather report")
        city: String
    ): Map<String, String> {
        return if (city.lowercase() == "new york") {
            mapOf(
                "status" to "success",
                "report" to "The weather in New York is sunny with a temperature of 25 degrees Celsius (77 degrees Fahrenheit)."
            )
        } else {
            mapOf(
                "status" to "error",
                "report" to "Weather information for $city is not available."
            )
        }
    }


    @JvmStatic // Use @JvmStatic to make the main function callable statically from Java
    fun main(args: Array<String>) {

        val runner = InMemoryRunner(ROOT_AGENT)

        val session: Session = runner
            .sessionService()
            .createSession(NAME, USER_ID)
            .blockingGet()

        Scanner(System.`in`, StandardCharsets.UTF_8).use { scanner ->
            while (true) {
                print("\nYou > ")
                val userInput = scanner.nextLine()

                if ("quit".equals(userInput, ignoreCase = true)) {
                    break
                }

                val userMsg = Content.fromParts(Part.fromText(userInput))
                val events: Flowable<Event> = runner.runAsync(USER_ID, session.id(), userMsg)

                print("\nAgent > ")
                events.blockingForEach { event: Event -> println(event.stringifyContent()) }
            }
        }
    }
}


