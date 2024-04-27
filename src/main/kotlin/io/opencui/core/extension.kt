package io.opencui.core

import io.opencui.channel.IChannel
import io.opencui.core.user.IUserIdentifier
import io.opencui.core.user.UserInfo
import org.slf4j.LoggerFactory
import java.io.Serializable
import kotlin.reflect.KClass

// This is used for creating the instance for T, useful for channels and supports.
/***
 * Section for configuration.
 */
interface IExtension {
    fun cloneForSession(userSession: UserSession): IExtension {
        return this
    }
}

// The configurable should be able to used in old way, and property way.
open class Configuration(val label: String): Serializable, HashMap<String, Any>() {
    init {
        val oldCfg = configurables.get(label)
        if (oldCfg == null) {
            configurables[label] = this
        } else {
            println("Hmmm..., there are already a configure labeled as $label.")
        }
    }

    /**
     * if the assist is true, chatbot will continue to send the automatical response to support so that
     * live agent can decide what to do with this. For support.
     */
    val assist: Boolean
        get() = this["assist"] == true

    // For templated provider.
    val conn: String
        get() = this["conn"]!! as String

    val url: String
        get() = this["url"]!! as String

    override fun toString(): String {
        return """$label:${super.toString()}"""
    }

    fun id() : String = "$label"

    companion object {
        const val DEFAULT = "default"
        val configurables = mutableMapOf<String, Configuration>()

        fun get(triple: String): Configuration? {
            return configurables[triple]
        }

        fun startsWith(key: String, prefixes: Set<String>) : String? {
            return prefixes.firstOrNull{key.startsWith(it)}
        }

        fun loadFromProperty() {
            val props = System.getProperties()
            val cfgPrefixes = mutableMapOf<String, Configuration>()
            for (info in configurables) {
                val cfg = info.value
                cfgPrefixes[cfg.id()] = cfg
            }

            for (key in props.keys()) {
                if (key is String) {
                    val match = startsWith(key, cfgPrefixes.keys)
                    if (match != null) {
                        val remainder = key.substring(match.length)
                        val cfg =  cfgPrefixes[match]
                        if (cfg != null) {
                            cfg[remainder] = props.getProperty(key)
                        }
                    }
                }
            }
        }
    }
}

interface ExtensionBuilder : (Configuration) -> IExtension

/**
 * This class holds a builder for each channel, and create a channel instance for given chatbot
 * on the first time it was requested.
 */
class ExtensionManager {
    val holder = mutableMapOf<String, IExtension>()
    val builderByLabel: MutableMap<String, ExtensionBuilder> = mutableMapOf()

    // This is used to narrow
    val labelsByInterface = mutableMapOf<KClass<*>, MutableList<String>>()

    inline fun <reified T:IExtension> getLabels(): List<String> {
       return labelsByInterface[T::class] ?: emptyList()
    }

    fun <T: IExtension> get(label: String) : T? {
        if (!holder.containsKey(label)) {
            val builder = builderByLabel[label]
            if (builder != null) {
                val config = Configuration.get(label)
                if (config != null) {
                    holder[label] = builder.invoke(config)
                }
            }
        }
        return holder[label] as T?
    }

    // This is used to create the builder
    fun addBuilder(label: String, builder: ExtensionBuilder, init: ConfiguredBuilder.()->Unit) {
        val configuredBuilder = ConfiguredBuilder(label)
        configuredBuilder.init()
        builderByLabel[label] = builder
    }

    // This is used to create the mapping from interface type to label.
    inline fun <reified T:IExtension> attach(label: String) {
        if (!labelsByInterface.containsKey(T::class)) {
            labelsByInterface[T::class] = mutableListOf<String>()
        }
        labelsByInterface[T::class]!!.add(label)
    }

    companion object {
        val logger = LoggerFactory.getLogger(ExtensionManager::class.java)
    }
}


data class ConfiguredBuilder(val label: String) {
    val config = Configuration(label)
    fun put(key: String, value: Any) {
        config[key] = value
    }
}


/***
 * Section for support.
 */

/**
 * Three things needed to work together to make support work:
 * a. messenger system where support agent can get context, and send replies.
 * b. ISupport implementation on the dispatcher side that can move information to right place.
 * c. dispatcher endpoint that can forward the message back to customers via correct channel.
 *
 * Conversation has two different mode, bot control and agent control.
 */

/***
 * Section for channels
 */
/**
 * For receiving purpose, we do not need to implement this, as it is simply a rest controller
 * or a websocket service.
 *
 * Only the channel that can send message/reply out need to implement this, for example RCS.
 */

interface IMessageChannel : IChannel {
    override fun sendWhitePayload(id: String, rawMessage: IWhitePayload, botInfo: BotInfo, source: IUserIdentifier?): IChannel.Status {
        preSend()
        val result =  when (rawMessage) {
            is TextPayload -> sendSimpleText(id, rawMessage, botInfo, source)
            is RichPayload -> sendRichCard(id, rawMessage, botInfo, source)
            is ListTextPayload -> sendListText(id, rawMessage, botInfo, source)
            is ListRichPayload -> sendListRichCards(id, rawMessage, botInfo, source)
        }
        postSend()
        return result
    }

    fun sendSimpleText(uid: String, rawMessage: TextPayload, botInfo: BotInfo, source: IUserIdentifier? = null) : IChannel.Status
    fun sendRichCard(uid: String, rawMessage: RichPayload, botInfo: BotInfo, source: IUserIdentifier? = null) : IChannel.Status
    fun sendListText(uid: String, rawMessage: ListTextPayload, botInfo: BotInfo, source: IUserIdentifier? = null) : IChannel.Status
    fun sendListRichCards(uid: String, rawMessage: ListRichPayload, botInfo: BotInfo, source: IUserIdentifier? = null) : IChannel.Status

    // This is the opportunity for channel to handle channel dependent messages.


    // This is used to let other side know that we are working with their request.
    fun typing(uid: String, botInfo: BotInfo) {}

    fun markSeen(uid: String, botInfo: BotInfo, messageId: String?=null) {}

    fun preSend() {}

    fun postSend() {}
}


/***
 * Section for providers
 */

//
// There are couple different concerns we need to deal with:
// 1. initialization from configurable (potentially from system property).
// 2. lazy init and instance accessible from end point. (implementation need to implement builder in companion)
// 3. We need some manager to manage the implementation instance, in case one service need more than one implementation.
//
/**
 * One should be able to access connection, and even session. The IService contains a set of functions.
 * Service is also attached to session, just like frame.
 */
interface IService: Serializable, IExtension

// After received the call from the third party, we create the corresponding event, and send to listener.
interface EventListener: Serializable {
    fun accept(botInfo: BotInfo, userInfo: UserInfo, event: FrameEvent)
}

class DispatcherEventListener : EventListener {
    override fun accept(botInfo: BotInfo, userInfo: UserInfo, event: FrameEvent) {
        Dispatcher.process(userInfo, botInfo, listOf(event))
    }
}

// This is for hosting endpoints, so that we can compose things.
interface IListener {
    fun addListener(listener: EventListener)
}



// All IProvider are the base for implementation. We need two separate type hierarchy
// The object should
interface IProvider : IService, IExtension {
    // This is to access the user somehow.
    var session: UserSession?
}

/**
 * For each service, we need to code gen a property of its service manager.
 */
interface ServiceManager<T>: Serializable {
    fun UserSession.get(): T
}

// We can potentially have a channel dependent service manager.
data class SimpleManager<T: IExtension>(val builder: () -> T) : ServiceManager<T> {
    // This will create
    var provider: T? = null

    override fun UserSession.get(): T {
        if (provider == null) {
            provider = builder.invoke()
        }
        return provider!!
    }
}


// We have two kind of provider: native provider, and templated provider.
// For each service, we code gen a manager, and then a service property.
// The service property should use get to get the actual provider.
