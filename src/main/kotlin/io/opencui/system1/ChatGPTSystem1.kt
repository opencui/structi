package io.opencui.system1

import io.opencui.core.Configuration
import io.opencui.core.ExtensionBuilder
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType

import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

data class OpenAIMessage(val role: String, val content: String)
fun List<CoreMessage>.convert(): List<OpenAIMessage> {
    return this.map { OpenAIMessage(if (it.user) "user" else "assistant", it.message) }
}

fun extractAfterXMLTag(input: String, tag: String): String {
    val count = input.windowed(tag.length, 1).count { it == tag }

    return when (count) {
        1 -> input.substring(input.indexOf(tag) + tag.length) // Return substring after </think>
        0 -> input // No occurrence, return entire string
        else -> "" // More than one occurrence, return empty string
    }
}


// Feedback is only useful when turns is empty.
data class System1Request(val turns: List<OpenAIMessage>, val feedback: Map<String, Any>? = null)

data class System1Reply(val reply: String) 

data class ChatGPTSystem1(val config: Configuration) : ISystem1 {
    val url = config[urlKey]!! as String
    val thinkFlag: Boolean = config[THINK] as Boolean? ?: false

    val client = WebClient.builder()
      .baseUrl(url)
      .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
      .build()

    override fun response(msgs: List<CoreMessage>, feedback: Map<String, Any>?): String {
        val request = System1Request(msgs.convert(), feedback)
        val response = client.post()
            .body(Mono.just(request), System1Request::class.java)
            .retrieve()
            .bodyToMono(System1Reply::class.java)

        val reply = response.block()!!.reply

        // handle the think.
        return if (reply.startsWith(THINKSTART))
            extractAfterXMLTag(reply, THINKEND)
        else
            reply
    }

    companion object : ExtensionBuilder {
        override fun invoke(p1: Configuration): ISystem1 {
            return ChatGPTSystem1(p1)
        }
        const val urlKey = "url"
        const val THINK = "THINK"
        const val THINKSTART = "<think>"
        const val THINKEND = "</think>"
    }
}
