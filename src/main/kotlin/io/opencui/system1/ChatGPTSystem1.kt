package io.opencui.system1

import io.opencui.core.*
import io.opencui.core.da.FilteredKnowledge
import io.opencui.core.da.System1Generation
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType

import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

data class OpenAIMessage(val role: String, val content: String)
fun List<CoreMessage>.convert(): List<OpenAIMessage> {
    return this.map { OpenAIMessage(if (it.user) "user" else "assistant", it.message) }
}

fun extractAfterXMLTag(input: String, tag: String): String {
    val index = input.lastIndexOf(tag)
    return if (index != -1) input.substring(index + tag.length) else ""
}

data class InferenceConfig(
    val temperature: Float,
    val topK: Int,
    val maxLength: Int = 1)


// Feedback is only useful when turns is empty.
data class System1Request(
    val prompt: String,
    val modelUrl: String,
    val modelFamily: String,
    val modelName: String,
    val modelKey: String,
    val contexts: List<String>,
    val turns: List<OpenAIMessage>,
    val collections: List<FilteredKnowledge>? = null,
    val temperature: Float = 0.0f,
    val topK: Int =  1
)

data class System1Reply(val reply: String)


// the chatGPTSystem1 is rag capable system 1, means it is located with indexing/retrieval capabilities.
data class ChatGPTSystem1(val config: Configuration) : ISystem1 {
    private val url = config[URL]!! as String
    private val apikey = config[APIKEY]!! as String
    private val family = config[FAMILY]!! as String
    private val label = config[LABEL]!! as String
    private val temperature: Float = 0.0f
    private val topk: Int = 1
    private val maxLength: Int = 1024

    val client = WebClient.builder()
      .baseUrl(config[SYSTEM1URL]!! as String)
      .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
      .build()

    override fun response(msgs: List<CoreMessage>, augmentation: Augmentation): String {
        val request = System1Request(
            prompt = augmentation.instruction,
            modelUrl = url,
            modelFamily = family,
            modelName = label,
            modelKey = apikey,
            contexts = augmentation.localKnowledge,
            turns = msgs.convert(),
            collections = augmentation.remoteKnowledge,
            temperature = temperature,
            topK = topk
        )

        val response = client.post()
            .uri("/generate")
            .body(Mono.just(request), System1Request::class.java)
            .retrieve()
            .bodyToMono(System1Reply::class.java)

        val reply = response.block()!!.reply.trim()
        logger.info("system1 response: $reply")

        // handle the think.
        return if (reply.startsWith(THINKSTART))
            extractAfterXMLTag(reply, THINKEND)
        else
            reply
    }

    companion object : ExtensionBuilder {
        private val logger: org.slf4j.Logger = LoggerFactory.getLogger(ChatGPTSystem1::class.java)
        override fun invoke(p1: Configuration): ISystem1 {
            return ChatGPTSystem1(p1)
        }

        const val SYSTEM1URL = "url"
        const val URL = "model_url"
        const val APIKEY = "model_apikey"
        const val FAMILY = "model_family"
        const val LABEL = "model_label"
        const val THINKSTART = "<think>"
        const val THINKEND = "</think>"
    }
}
