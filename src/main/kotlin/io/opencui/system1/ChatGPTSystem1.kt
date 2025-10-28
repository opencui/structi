package io.opencui.system1

import io.opencui.core.*
import io.opencui.core.da.KnowledgePart
import kotlinx.coroutines.flow.Flow
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



// the chatGPTSystem1 is rag capable system 1, means it is located with indexing/retrieval capabilities.
// This is mother of all LLM based system 1
data class ChatGPTSystem1(val config: ModelConfig) : ISystem1 {
    val builder: ISystem1Builder = AdkSystem1Builder(config)

    override fun response(session: UserSession, augmentation: Augmentation): Flow<System1Event> {
        // Now use an interface that can handle all three use cases.
        // For now we assume the instruction is dynamically created so that there is no need to
        // cache system1. Instead, we assume the prefix caching of the LLM will kick in.
        val system1Executor = builder.build(session, augmentation)
        return system1Executor.invoke()
    }

    companion object : ExtensionBuilder {
        private val logger: org.slf4j.Logger = LoggerFactory.getLogger(ChatGPTSystem1::class.java)
        override fun invoke(config: Configuration): ISystem1 {
            println("configure for system1: ${config}")
            val url = config[URL] as String?
            val apikey = config[APIKEY] as String?
            val family = config[FAMILY]!! as String
            val label = config[LABEL]!! as String
            val temperatureStr = config[TEMPERATURE] as String?
            val temperature: Float = if (temperatureStr.isNullOrEmpty()) 0.0f else temperatureStr.toFloat()
            val topkStr = config[TOPK]!! as String?
            val topk: Int = if (topkStr.isNullOrEmpty()) 1 else topkStr.toInt()
            val maxOutputTokensStr = config[maxOutputTokens] as String?
            val maxLength: Int = if (maxOutputTokensStr.isNullOrEmpty()) defaultMaxOutputTokens else maxOutputTokensStr.toInt()
            val model = ModelConfig(family, label, url = url, apikey = apikey, temperature = temperature, topK = topk, maxOutputTokens = maxLength)
            return ChatGPTSystem1(model)
        }

        const val URL = "model_url"
        const val APIKEY = "model_apikey"
        const val FAMILY = "model_family"
        const val LABEL = "model_label"
        const val TOPK = "topk"
        const val maxOutputTokens = "max_output_tokens"
        const val TEMPERATURE = "temperature"
        const val THINKSTART = "<think>"
        const val THINKEND = "</think>"
        const val defaultMaxOutputTokens: Int = 8192
    }
}
