package io.opencui.system1


import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMProvider
import kotlinx.serialization.json.Json

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * This is a more advanced example showing how to configure various parameters of structured output manually, to fine-tune
 * it for your needs when necessary.
 *
 * Structured output that uses "full" JSON schema.
 * More advanced features are supported, e.g. polymorphism and recursive type references, and schemas can be more complex.
 */

@kotlinx.serialization.Serializable
data class FullWeatherForecast(
    val temperature: Int,
    // properties with default values
    val conditions: String = "sunny",
    // nullable properties
    val precipitation: Int?,
    // nested classes
    val latLon: LatLon,
    // enums
    val pollution: Pollution,
    // polymorphism
    val alert: WeatherAlert,
    // lists
    val news: List<WeatherNews>,
//    // maps (string keys only, some providers don't support maps at all)
//    @property:LLMDescription("Map of weather sources")
//    val sources: Map<String, WeatherSource>
) {
    companion object {
        // Optional examples, to help LLM understand the format better in manual mode
        val exampleForecasts = listOf(
            FullWeatherForecast(
                temperature = 18,
                conditions = "Cloudy",
                precipitation = 30,
                latLon = FullWeatherForecast.LatLon(lat = 34.0522, lon = -118.2437),
                pollution = FullWeatherForecast.Pollution.Medium,
                alert = FullWeatherForecast.WeatherAlert.StormAlert(
                    severity = FullWeatherForecast.WeatherAlert.Severity.Moderate,
                    message = "Possible thunderstorms in the evening",
                    windSpeed = 45.5
                ),
                news = listOf(
                    FullWeatherForecast.WeatherNews(title = "Local news", link = "https://example.com/news"),
                    FullWeatherForecast.WeatherNews(title = "Global news", link = "https://example.com/global-news")
                ),
//            sources = mapOf(
//                "MeteorologicalWatch" to FullWeatherForecast.WeatherSource(
//                    stationName = "MeteorologicalWatch",
//                    stationAuthority = "US Department of Agriculture"
//                ),
//                "MeteorologicalWatch2" to FullWeatherForecast.WeatherSource(
//                    stationName = "MeteorologicalWatch2",
//                    stationAuthority = "US Department of Agriculture"
//                )
//            )
            ),
            FullWeatherForecast(
                temperature = 10,
                conditions = "Rainy",
                precipitation = null,
                latLon = FullWeatherForecast.LatLon(lat = 37.7739, lon = -122.4194),
                pollution = FullWeatherForecast.Pollution.Low,
                alert = FullWeatherForecast.WeatherAlert.FloodAlert(
                    severity = FullWeatherForecast.WeatherAlert.Severity.Severe,
                    message = "Heavy rainfall may cause local flooding",
                    expectedRainfall = 75.2
                ),
                news = listOf(
                    FullWeatherForecast.WeatherNews(title = "Local news", link = "https://example.com/news"),
                    FullWeatherForecast.WeatherNews(title = "Global news", link = "https://example.com/global-news")
                ),
//            sources = mapOf(
//                "MeteorologicalWatch" to WeatherForecast.WeatherSource(
//                    stationName = "MeteorologicalWatch",
//                    stationAuthority = "US Department of Agriculture"
//                ),
//            )
            )
        )
    }

    // Nested classes
    @Serializable
    data class LatLon(
        val lat: Double,
        val lon: Double
    )

    // Nested classes in lists...
    @Serializable
    data class WeatherNews(
        val title: String,
        val link: String
    )

    // ... and maps (but only with string keys!)
    @Suppress("unused")
    @Serializable
    data class WeatherSource(
        val stationName: String,
        val stationAuthority: String
    )

    // Enums
    @Suppress("unused")
    @Serializable
    enum class Pollution {
        None,

        @SerialName("LOW")
        Low,

        @SerialName("MEDIUM")
        Medium,

        @SerialName("HIGH")
        High
    }

    /*
     Polymorphism:
      1. Closed with sealed classes,
      2. Open: non-sealed classes with subclasses registered in json config
         https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/polymorphism.md#registered-subclasses
     */
    @Suppress("unused")
    @Serializable
    sealed class WeatherAlert {
        abstract val severity: Severity
        abstract val message: String

        @Serializable
        enum class Severity { Low, Moderate, Severe, Extreme }

        @Serializable
        data class StormAlert(
            override val severity: Severity,
            override val message: String,
            val windSpeed: Double
        ) : WeatherAlert()

        @Serializable
        data class FloodAlert(
            override val severity: Severity,
            override val message: String,
            val expectedRainfall: Double
        ) : WeatherAlert()

        @Serializable
        data class TemperatureAlert(
            override val severity: Severity,
            override val message: String,
            val threshold: Int, // in Celsius
            val isHeatWarning: Boolean
        ) : WeatherAlert()
    }
}

data class FullWeatherForecastRequest(
    val city: String,
    val country: String
) {
    override fun toString(): String = "Requesting forecast for City: ${city} Country: ${country}"
}


private val json = Json {
    prettyPrint = true
}

suspend fun main() {
    /*
     This structure has a generic schema that is suitable for manual structured output mode.
     But to use native structured output support in different LLM providers you might need to use custom JSON schema generators
     that would produce the schema these providers expect.
     */
    val agentConfig = AIAgentConfig(
        prompt = prompt("weather-forecast") {
            system(
                """
                You are a weather forecasting assistant.
                When asked for a weather forecast, provide a realistic but fictional forecast.
                """.trimIndent()
            )
        },
        model = GoogleModels.Gemini2_5Flash,
        maxAgentIterations = 5
    )

    val executor = MultiLLMPromptExecutor(
        LLMProvider.Google to GoogleLLMClient(""),
    )
    
    val agent = KoogSystem1Builder.build<FullWeatherForecast>(executor,  agentConfig, false)

    val result: FullWeatherForecast = agent.run(FullWeatherForecastRequest(city = "New York", country = "USA").toString())
    println("Agent run result: $result")

}