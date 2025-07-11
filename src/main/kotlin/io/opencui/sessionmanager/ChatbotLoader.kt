package io.opencui.sessionmanager

import io.opencui.core.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.File
import java.net.URLClassLoader
import java.util.regex.Pattern

object ChatbotLoader {
    val logger: Logger = LoggerFactory.getLogger(ChatbotLoader::class.java)
    val chatbotCache = PerpetualCache<String, RecyclableAgentResource>()
    val chatbotPrefixMap = mutableMapOf<String, List<IChatbot>>()
    val pattern = Pattern.compile("[^a-z]", Pattern.CASE_INSENSITIVE)
    var botPrefix: String = ""

    private fun findResource(botInfo: BotInfo): RecyclableAgentResource {
        return chatbotCache[botInfo.lang] ?: loadChatbot(botInfo)
    }

    // load chatbot based on BotInfo and BotVersion
    fun findChatbot(botInfo: BotInfo): IChatbot {
        return findResource(botInfo).chatbot
    }

    fun findClassLoader(botInfo: BotInfo): ClassLoader {
        return findResource(botInfo).classLoader
    }

    private fun loadChatbot(botInfo: BotInfo): RecyclableAgentResource {
        val key = botInfo.lang
        if (!chatbotCache.keys.contains(key)) {
            // chatbotCache[key]?.recycle()
            val file = getJarFile(botInfo)
            logger.info("No $key in ${chatbotCache.keys} so need to load from ${file.absolutePath} for $botInfo")
            val classLoader = URLClassLoader(arrayOf(file.toURI().toURL()), javaClass.classLoader)
            val qualifiedAgentName = "${botInfo.fullName}.Agent"
            val kClass = Class.forName(qualifiedAgentName, true, classLoader).kotlin
            val chatbot = (kClass.constructors.first { it.parameters.isEmpty() }.call() as IChatbot)
            val agent = RecyclableAgentResource(chatbot, classLoader, file.lastModified())
            chatbotCache[key] = agent
            chatbotCache[chatbot.agentLang] = agent
        }
        return chatbotCache[key]!!
    }

    // TODO(sean): to support A/B test, we just need to add the support for branch back.
    fun getJarFile(botInfo: BotInfo): File {
        return File("./jardir/agent-${botInfo.lang}.jar")
    }

    fun findChatbotsByPrefix(botPrefix: String): List<IChatbot> {
        return chatbotPrefixMap[botPrefix]!!
    }

    // This is useful for creating the index.
    fun init(file: File, botPrefix: String) {
        this.botPrefix = botPrefix
        val chatbots = mutableListOf<IChatbot>()
        file.walk()
			.filter { it.toString().endsWith("jar") }
			.forEach {
                val fileName = it.name
                val lastDot = fileName.lastIndexOf('.');
                val lang = fileName.substring(6, lastDot)  // agent-
                // there should not be special letter in lang?
                if (!pattern.matcher(lang).find()) {
                    val classLoader = URLClassLoader(arrayOf(it.toURI().toURL()), javaClass.classLoader)
                    val qualifiedAgentName = "${botPrefix}.Agent"
                    logger.info("load agent :$qualifiedAgentName with $lang from $file using $classLoader")
                    val kClass = Class.forName(qualifiedAgentName, true, classLoader).kotlin
                    val chatbot = kClass.constructors.first { it.parameters.isEmpty() }.call() as IChatbot
                    chatbots.add(chatbot)
                    chatbotCache[lang] = RecyclableAgentResource(chatbot, classLoader, file.lastModified())
                } else {
                    logger.info("$fileName is not in agent-{lang}.jar format.")
                }
			}
        chatbotPrefixMap[botPrefix] = chatbots
    }


    data class RecyclableAgentResource(val chatbot: IChatbot, val classLoader: ClassLoader, val lastModified: Long): Recyclable {
        override fun recycle() {
            chatbot.recycle()
            (classLoader as? Closeable)?.close()
        }
    }
}

