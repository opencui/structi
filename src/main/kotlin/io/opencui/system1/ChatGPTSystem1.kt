package io.opencui.system1

import io.opencui.core.*
import io.opencui.core.da.KnowledgePart
import io.opencui.serialization.JsonElement
import org.slf4j.LoggerFactory

data class OpenAIMessage(val role: String, val content: String)
fun List<CoreMessage>.convert(): List<OpenAIMessage> {
    return this.map { OpenAIMessage(if (it.user) "user" else "assistant", it.message) }
}

fun extractAfterXMLTag(input: String, tag: String): String {
    val index = input.lastIndexOf(tag)
    return if (index != -1) input.substring(index + tag.length) else ""
}

// Feedback is only useful when turns is empty.
data class System1Request(
    val prompt: String,
    val modelUrl: String,
    val modelFamily: String,
    val modelName: String,
    val modelKey: String,
    val turns: List<OpenAIMessage>,
    val collections: List<KnowledgePart>? = null,
    val temperature: Float = 0.0f,
    val topK: Int =  1
)

data class System1Reply(val reply: String)


data class System1Event(val type: String, val payload: Map<String, Any>)


// augmentation might also change. We have another layer.
interface ISystem1Executor {
    operator fun invoke(emitter: Emitter<System1Event>? = null) : JsonElement?
}

interface ISystem1Builder {
    fun build(session: UserSession, augmentation: Augmentation): ISystem1Executor
}


// the chatGPTSystem1 is rag capable system 1, means it is located with indexing/retrieval capabilities.
// This is mother of all LLM based system 1
data class ChatGPTSystem1(val config: ModelConfig) : ISystem1 {
    val builder: ISystem1Builder = AdkSystem1Builder(config)

    override fun response(session: UserSession, augmentation: Augmentation, emitter: Emitter<*>?): JsonElement? {
        // Now use an interface that can handle all three use cases.
        val system1Executor = builder.build(session, augmentation)
        return system1Executor.invoke(emitter as? Emitter<System1Event>)
    }


    companion object : ExtensionBuilder {
        private val logger: org.slf4j.Logger = LoggerFactory.getLogger(ChatGPTSystem1::class.java)
        override fun invoke(config: Configuration): ISystem1 {
            val url = config[URL]!! as String
            val apikey = config[APIKEY]!! as String
            val family = config[FAMILY]!! as String
            val label = config[LABEL]!! as String
            val temperature: Float = (config["temperature"]!! as String).toFloat()
            val topk: Int = (config["topk"]!! as String).toInt()
            val maxLength: Int = 1024
            val model = ModelConfig(family, label, url = url, apikey = apikey, temperature = temperature, topK = topk, maxOutputTokens = maxLength)
            return ChatGPTSystem1(model)
        }

        const val URL = "model_url"
        const val APIKEY = "model_apikey"
        const val FAMILY = "model_family"
        const val LABEL = "model_label"
        const val THINKSTART = "<think>"
        const val THINKEND = "</think>"
    }
}
