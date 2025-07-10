package io.opencui.core

import io.opencui.channel.IChannel
import io.opencui.core.user.IUserIdentifier
import io.opencui.system1.ChatGPTSystem1
import io.opencui.system1.ISystem1
import io.opencui.system1.ModelSize
import io.opencui.system1.ModelSpec
import org.slf4j.LoggerFactory
import java.io.Serializable
import kotlin.reflect.KClass

// This is used for creating the instance for T, useful for channels and supports.
/***
 * Section for configuration.
 */
interface IExtension {
    fun getConfiguration(): Configuration?  = null

    fun getSetting(key: String): Any?  {
        return getConfiguration()?.get(key)
    }

    // If the implementation is session dependent, one should clone one for that session.
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

    // For system1 binding.
    fun toModleSpecs() : ModelSpec {
        val size = ModelSize.valueOf((this[ISystem1.MODELSIZE] as String?)?.uppercase() ?: ModelSize.ADVANCED.name)
        val jsonOutput = (this[ISystem1.STRUCTUREDOUTPUT] as String?)?.toBoolean() ?: false
        return ModelSpec(label!!, size, jsonOutput)
    }


    /**
     * Copy all key-value pairs from another configuration, except "label"
     * @param other the configuration to copy from
     */
    fun copyFrom(other: Configuration) {
        val currentLabel = this.label

        // Copy all entries from other configuration
        for ((key, value) in other) {
            if (key in ignoringProperties) {  // Skip the label key
                this[key] = value
            }
        }

        // Restore original label if it was stored in the map
        // (Note: the val label property is separate from map entries)
        println("Copied from ${other.label} to $currentLabel, preserving label")
    }


    /**
     * if the assist is true, chatbot will continue to send the automatical response to support so that
     * live agent can decide what to do with this. For support.
     */
    val assist: Boolean
        get() = this["assist"] == true

    val _public_keys: Set<String> = (this["_public_keys"] as String?)?.split(",")?.toSet() ?: emptySet()

    // For templated provider.
    val conn: String
        get() = this["conn"]!! as String

    val url: String?
        get() = this["url"] as String?

    override fun toString(): String {
        return """$label:${super.toString()}"""
    }

    fun getSetting(key: String): Any? {
        return if (key in _public_keys) {
            return super.get(key)
        } else {
            null
        }
    }

    fun id() : String = "$label"

    companion object {
        const val DEFAULT = "default"
        val ignoringProperties = setOf("label", "topK", "temperature")
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
    val builderByLabel = mutableMapOf<String, ExtensionBuilder>()
    val configurationsByInterface = mutableMapOf<KClass<*>, MutableList<Configuration>>()
    // This is used to narrow
    val labelsByInterface = mutableMapOf<KClass<*>, MutableList<String>>()

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

    inline fun <reified T:IExtension> get(): T? {
        val labels = labelsByInterface[T::class] ?: return null
        if (labels.isEmpty()) return null
        return get(labels.first())
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

    inline fun <reified T:IExtension> findAllConfigurations() : List<Configuration> {
        if (!labelsByInterface.containsKey(T::class)) {
            labelsByInterface[T::class] = mutableListOf<String>()
        }
        return labelsByInterface[T::class]!!.mapNotNull { Configuration.get(it) }
    }


    // bind system1 requirement.
    fun bindSystem1() {
        // We only handle ChatGPTSystem1.
        val configurations = findAllConfigurations<ChatGPTSystem1>()
        val (bound, unbound) = ISystem1.separateConfigurations(configurations) { it -> it.url != null }

        // TODO: sort the bound based on the cost performance
        val boundPairs = bound.map { Pair(it, it.toModleSpecs())}
        for (item in unbound) {
            val bestMatch = ISystem1.bestMatch(item, boundPairs)
            if (bestMatch == null) throw IllegalArgumentException("could not found system1 that can handle ${item.label}")
            item.copyFrom(bestMatch)
        }
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
