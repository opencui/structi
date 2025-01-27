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


// Feedback is only useful when turns is empty.
data class System1Request(val turns: List<OpenAIMessage>, val feedback: Map<String, Any>? = null)

data class System1Reply(val reply: String)

data class ChatGPTSystem1(val url: String) : ISystem1 {
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
        return response.block()!!.reply
    }

    companion object : ExtensionBuilder {
        override fun invoke(p1: Configuration): ISystem1 {
            val url = p1[urlKey]!! as String
            return ChatGPTSystem1(url)
        }
        const val urlKey = "url"
    }
}
