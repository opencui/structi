package io.opencui.system1


import com.google.adk.agents.LlmAgent
import com.google.adk.events.Event
import com.google.adk.runner.Runner
import com.google.adk.sessions.InMemorySessionService
import com.google.adk.sessions.Session
import com.google.adk.tools.Annotations
import com.google.adk.tools.FunctionTool
import com.google.genai.types.Content
import com.google.genai.types.Part
import com.google.genai.types.Schema
import io.reactivex.rxjava3.core.Flowable
import java.util.Optional

class LlmAgentExample {

    // --- 1. Define Constants ---
    companion object {
        private const val MODEL_NAME = "gemini-2.0-flash"
        private const val APP_NAME = "capital_agent_tool"
        private const val USER_ID = "test_user_456"
        private const val SESSION_ID_TOOL_AGENT = "session_tool_agent_xyz"
        private const val SESSION_ID_SCHEMA_AGENT = "session_schema_agent_xyz"

        // --- 2. Define Schemas ---

        // Input schema used by both agents
        private val COUNTRY_INPUT_SCHEMA: Schema =
            Schema.builder()
                .type("OBJECT")
                .description("Input for specifying a country.")
                .properties(
                    mapOf(
                        "country" to
                                Schema.builder()
                                    .type("STRING")
                                    .description("The country to get information about.")
                                    .build()
                    )
                )
                .required(listOf("country"))
                .build()

        // Output schema ONLY for the second agent
        private val CAPITAL_INFO_OUTPUT_SCHEMA: Schema =
            Schema.builder()
                .type("OBJECT")
                .description("Schema for capital city information.")
                .properties(
                    mapOf(
                        "capital" to
                                Schema.builder()
                                    .type("STRING")
                                    .description("The capital city of the country.")
                                    .build(),
                        "population_estimate" to
                                Schema.builder()
                                    .type("STRING")
                                    .description("An estimated population of the capital city.")
                                    .build()
                    )
                )
                .required(listOf("capital", "population_estimate"))
                .build()
    }

    // --- 3. Define the Tool (Only for the first agent) ---
    // Retrieves the capital city of a given country.
    @Suppress("unused") // Called via reflection
    fun getCapitalCity(
        @Annotations.Schema(name = "country", description = "The country to get capital for")
        country: String
    ): Map<String, Any> {
        println("\n-- Tool Call: getCapitalCity(country='$country') --")
        val countryCapitals = mapOf(
            "united states" to "Washington, D.C.",
            "canada" to "Ottawa",
            "france" to "Paris",
            "japan" to "Tokyo"
        )

        val result =
            countryCapitals.getOrDefault(
                country.lowercase(), "Sorry, I couldn't find the capital for $country."
            )
        println("-- Tool Result: '$result' --")
        return mapOf("result" to result) // Tools must return a Map
    }

    fun main() {
        val agentExample = LlmAgentExample()
        val capitalTool = FunctionTool.create(agentExample.javaClass, "getCapitalCity")

        // --- 4. Configure Agents ---

        // Agent 1: Uses a tool and output_key
        val capitalAgentWithTool =
            LlmAgent.builder()
                .model(MODEL_NAME)
                .name("capital_agent_tool")
                .description("Retrieves the capital city using a specific tool.")
                .instruction(
                    """
                    You are a helpful agent that provides the capital city of a country using a tool.
                    1. Extract the country name.
                    2. Use the `get_capital_city` tool to find the capital.
                    3. Respond clearly to the user, stating the capital city found by the tool.
                    """.trimIndent()
                )
                .tools(capitalTool)
                .inputSchema(COUNTRY_INPUT_SCHEMA)
                .outputKey("capital_tool_result") // Store final text response
                .build()

        // Agent 2: Uses an output schema
        val structuredInfoAgentSchema =
            LlmAgent.builder()
                .model(MODEL_NAME)
                .name("structured_info_agent_schema")
                .description("Provides capital and estimated population in a specific JSON format.")
                .instruction(
                    """
                    You are an agent that provides country information.
                    Respond ONLY with a JSON object matching this exact schema: ${CAPITAL_INFO_OUTPUT_SCHEMA.toJson()}
                    Use your knowledge to determine the capital and estimate the population. Do not use any tools.
                    """.trimIndent()
                )
                // *** NO tools parameter here - using output_schema prevents tool use ***
                .inputSchema(COUNTRY_INPUT_SCHEMA)
                .outputSchema(CAPITAL_INFO_OUTPUT_SCHEMA) // Enforce JSON output structure
                .outputKey("structured_info_result") // Store final JSON response
                .build()

        // --- 5. Set up Session Management and Runners ---
        val sessionService = InMemorySessionService()

        sessionService.createSession(APP_NAME, USER_ID, null, SESSION_ID_TOOL_AGENT).blockingGet()
        sessionService.createSession(APP_NAME, USER_ID, null, SESSION_ID_SCHEMA_AGENT).blockingGet()

        val capitalRunner = Runner(capitalAgentWithTool, APP_NAME, null, sessionService)
        val structuredRunner = Runner(structuredInfoAgentSchema, APP_NAME, null, sessionService)

        // --- 6. Run Interactions ---
        println("--- Testing Agent with Tool ---")
        agentExample.callAgentAndPrint(
            capitalRunner, capitalAgentWithTool, SESSION_ID_TOOL_AGENT, "{\"country\": \"France\"}"
        )
        agentExample.callAgentAndPrint(
            capitalRunner, capitalAgentWithTool, SESSION_ID_TOOL_AGENT, "{\"country\": \"Canada\"}"
        )

        println("\n\n--- Testing Agent with Output Schema (No Tool Use) ---")
        agentExample.callAgentAndPrint(
            structuredRunner,
            structuredInfoAgentSchema,
            SESSION_ID_SCHEMA_AGENT,
            "{\"country\": \"France\"}"
        )
        agentExample.callAgentAndPrint(
            structuredRunner,
            structuredInfoAgentSchema,
            SESSION_ID_SCHEMA_AGENT,
            "{\"country\": \"Japan\"}"
        )
    }

    // --- 7. Define Agent Interaction Logic ---
    fun callAgentAndPrint(runner: Runner, agent: LlmAgent, sessionId: String, queryJson: String) {
        println(
            "\n>>> Calling Agent: '${agent.name()}' | Session: '$sessionId' | Query: $queryJson"
        )

        val userContent = Content.fromParts(Part.fromText(queryJson))
        val finalResponseContent = arrayOf("No final response received.")
        val eventStream: Flowable<Event> = runner.runAsync(USER_ID, sessionId, userContent)

        // Stream event response
        eventStream.blockingForEach { event: Event ->
            if (event.finalResponse() && event.content().isPresent) {
                event
                    .content()
                    .get()
                    .parts()
                    .flatMap { parts: List<Part> -> if (parts.isEmpty()) Optional.empty() else Optional.of(parts[0]) }
                    .flatMap(Part::text)
                    .ifPresent { text: String -> finalResponseContent[0] = text }
            }
        }

        println("<<< Agent '${agent.name()}' Response: ${finalResponseContent[0]}")

        // Retrieve the session again to get the updated state
        val updatedSession: Session? =
            runner
                .sessionService()
                .getSession(APP_NAME, USER_ID, sessionId, Optional.empty())
                .blockingGet()

        if (updatedSession != null && agent.outputKey().isPresent) {
            // Print to verify if the stored output looks like JSON (likely from output_schema)
            print("--- Session State ['${agent.outputKey().get()}']: ")
            println(updatedSession.state().get(agent.outputKey().get()))
        }
    }
}

fun main() {
    LlmAgentExample().main()
}