package io.opencui.sessionmanager

import io.opencui.core.*
import io.opencui.sessionmanager.ChatbotLoader.chatbotCache
import io.opencui.sessionmanager.ChatbotLoader.genKey
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.File
import java.net.URLClassLoader
import java.util.regex.Pattern

object ChatbotLoader {
    val logger: Logger = LoggerFactory.getLogger(ChatbotLoader::class.java)
    val chatbotCache = PerpetualCache<String, RecyclableAgentResource>()
    val pattern = Pattern.compile("[^a-z]", Pattern.CASE_INSENSITIVE)
    var botPrefix: String = ""

    // load chatbot based on BotInfo and BotVersion
    fun findChatbot(botInfo: BotInfo): IChatbot {
        return chatbotCache[botInfo.lang]?.chatbot ?: loadChatbot(botInfo).chatbot
    }

    fun findClassLoader(botInfo: BotInfo): ClassLoader {
        return chatbotCache[botInfo.lang]?.classLoader ?: loadChatbot(botInfo).classLoader
    }

    private fun loadChatbot(botInfo: BotInfo): RecyclableAgentResource {
        val key = botInfo.lang
        logger.info("No $key in ${chatbotCache.keys} so need to load from ${file.absolutePath} for $botInfo")
        if (!chatbotCache.keys.contains(key)) {
            // chatbotCache[key]?.recycle()
            val file = getJarFile(botInfo)
            val classLoader = URLClassLoader(arrayOf(file.toURI().toURL()), javaClass.classLoader)
            val qualifiedAgentName = "${botInfo.org}.${botInfo.agent}.Agent"
            val kClass = Class.forName(qualifiedAgentName, true, classLoader).kotlin
            val chatbot = (kClass.constructors.first { it.parameters.isEmpty() }.call() as IChatbot)
            chatbotCache[key] = RecyclableAgentResource(chatbot, classLoader, file.lastModified())
        }
        return chatbotCache[key]!!
    }

    fun getJarFile(botInfo: BotInfo): File {
        // return File("./jardir/${botInfo.org}_${botInfo.agent}_${botInfo.lang}_${botInfo.branch}.jar")
        return File("./jardir/agent-${botInfo.lang}.jar")
    }

    // This is useful for creating the index.
    fun init(file: File, botPrefix: String) {
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
                    logger.info("load agent :$qualifiedAgentName with $lang from $file")
                    val kClass = Class.forName(qualifiedAgentName, true, classLoader).kotlin
                    val chatbot = kClass.constructors.first { it.parameters.isEmpty() }.call() as IChatbot
                    chatbotCache[lang] = RecyclableAgentResource(chatbot, classLoader, file.lastModified())
                } else {
                    logger.info("$fileName is not in agent-{lang}.jar format.")
                }
			}
    }

    data class RecyclableAgentResource(val chatbot: IChatbot, val classLoader: ClassLoader, val lastModified: Long): Recyclable {
        override fun recycle() {
            chatbot.recycle()
            (classLoader as? Closeable)?.close()
        }
    }
}

