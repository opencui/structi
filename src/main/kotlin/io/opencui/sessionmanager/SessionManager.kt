package io.opencui.sessionmanager

import io.opencui.core.*
import io.opencui.core.da.DialogAct
import io.opencui.core.da.RequestForDelayDialogAct
import io.opencui.core.user.IUserIdentifier
import io.opencui.kvstore.IKVStore
import io.opencui.logger.Turn
import io.opencui.serialization.Json
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.*
import java.util.*
import java.time.LocalDateTime
import kotlin.reflect.KProperty0
import kotlin.reflect.KProperty1
import kotlin.reflect.jvm.javaField

/**
 * We assume that at no circumstances that user will access more than one language at the same time.
 */
interface ISessionStore {
    fun getSession(channel: String, id:String, botInfo: BotInfo): UserSession?
    fun deleteSession(channel: String, id:String, botInfo: BotInfo)
    fun updateSession(channel: String, id: String, botInfo: BotInfo, session: UserSession)
    fun saveSession(channel: String, id: String, botInfo: BotInfo, session: UserSession)

    companion object {
        fun key(channel: String, id:String, botInfo: BotInfo): String {
            // We do not use language as part of key so that multiple language support is easier.
            return "${botInfo.fullName}:s:${channel}:${id}"
        }

        // This make is easy to catch the session serialization issue.
        fun decodeSession(encodedSession: String, classLoader: ClassLoader) : UserSession? {
            val decodedSession = Base64.getDecoder().decode(encodedSession)
            val objectIn = object : ObjectInputStream(ByteArrayInputStream(decodedSession)) {
                override fun resolveClass(desc: ObjectStreamClass?): Class<*> {
                    try {
                        return Class.forName(desc!!.name, true, classLoader)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    return super.resolveClass(desc)
                }
            }
            val session = objectIn.use {
                it.readObject() as? UserSession
            }



            session?.chatbot =  ChatbotLoader.findChatbot(session?.botInfo!!)
            session?.extensionCache = mutableMapOf()
            return session
        }

        fun encodeSession(session: UserSession) : String {
            // Always keep record of the last touch, so that we can decide whether we want to wake
            // up the conversation.
            session.lastTouch = LocalDateTime.now()
            session.extensionCache == null
            val byteArrayOut = ByteArrayOutputStream()
            val objectOut = ObjectOutputStream(byteArrayOut)
            objectOut.use {
                it.writeObject(session)
                return String(Base64.getEncoder().encode(byteArrayOut.toByteArray()))
            }
        }
    }
}


// Some time, we need to save the bot related information across different versions for all users.
interface IBotStore: IKVStore {
    // For most part, the bot store is keyed on the bot itself, instead of bot + branch.
    // Language should never be part of key, branch can be useful for A/B testing eventually.
    val botInfo: BotInfo

    fun getKey(key: String): String {
        return "agent:${botInfo.fullName}|$key"
    }
}


inline fun <reified R:Any> IBotStore.getKey(prop: KProperty0<R?>): String {
    // T::class gives you the KClass instance for T at runtime.
    val frameClass = prop.javaField?.declaringClass?.kotlin
    val propertyName = prop.name
    val className = frameClass?.qualifiedName
    return "${className}:${prop.name}"
}

inline fun <reified R: Any, > IBotStore.save(prop: KProperty0<R?>) {
    val key = getKey(prop)
    val value = prop.get()
    val valueString = Json.encodeToString(value)
    set(key, valueString)
}

inline fun <reified R: Any> IBotStore.load(prop: KProperty0<R?>): R? {
    val key = getKey(prop)
    val valueString = get(key) ?: return null
    return Json.decodeFromString<R>(valueString)
}


//  We need to return some part asap,
fun <T> batchFirstRest(source: Flow<T>, predicate: (T) -> Boolean) : Flow<List<T>> = flow {
    var foundItem: T? = null
    val rest = mutableListOf<T>()
    source.collect { item ->
        if (predicate(item)) {
            if (foundItem == null) {
                foundItem = item
                emit(listOf(foundItem!!))
            }
        } else {
            rest.add(item)
        }
    }

    // we will always emit something, even an empty list.
    emit(rest)
}


/**
 * The conversion state need to be saved in UserSession so that the same customer can be served
 * by different instances if we need to. Consider this as short term memory for the conversation,
 * we can have long term memory about what user have consumed (but may not include how user got
 * these services).
 *
 * So we need two different interfaces: at dispatcher level, we manage the support channel as
 * well, at session manager level, we simply respond to user query. Of course, we can reply
 * with messages that goes to support as well.
 *
 * SessionManager provide interface that wraps a particular version of agent, so that it is easy
 * for both generic and channel specific rest controller to connect user with bot.
 * This is useful so that different restful api (for different channel) can reuse the same
 * session manager.
 *
 * To support try it now, we need to return response from all channels. We add targetChannels
 * if it is null, we use the source channels, if it is empty list, we return all channels,
 * otherwise, we return the response for every channel specified.
 *
 * For now, bot takes in raw text as query, and returns the raw string that contains the structured
 * messages.
 *
 */
class SessionManager(private val sessionStore: ISessionStore, val botStore: IBotStore? = null) {

    private val dm: DialogManager = DialogManager()

    fun getUserSession(channel: IUserIdentifier, botInfo: BotInfo): UserSession? {
        // We try to deserialize the saved session, but if we can not, return null as if we
        // did not see this user before.
        return try {
            sessionStore.getSession(channel.channelId(), channel.userId!!, botInfo)
        } catch (e: Exception) {
            sessionStore.deleteSession(channel.channelId(), channel.userId!!, botInfo)
            null
        }
    }

    fun createUserSession(rawUser: IUserIdentifier, botInfo: BotInfo): UserSession {
        sessionStore.deleteSession(rawUser.channelId(), rawUser.userId!!, botInfo)
        val bot = ChatbotLoader.findChatbot(botInfo)
        val createdSession = bot.createUserSession(rawUser.channelType!!, rawUser.userId!!, channelLabel = rawUser.channelLabel)

        // Try to use the info from message.
        createdSession.isVerfied = rawUser.isVerfied

        // If the channel had extra way to get user identifier, use that.
        val lchannel = if (rawUser.channelLabel != null)  bot.getChannel(rawUser.channelLabel!!) else null
        val identifier = if (lchannel != null && rawUser.userId != null) lchannel!!.getIdentifier(botInfo, rawUser.userId!!) else null
         if (identifier != null) {
            createdSession.name = identifier.name
            createdSession.phone = identifier.phone
            createdSession.email = identifier.email
        } else {
            createdSession.name  = rawUser.name
            createdSession.phone = rawUser.phone
            createdSession.email = rawUser.email
        }

        sessionStore.saveSession(rawUser.channelId(), rawUser.userId!!, botInfo, createdSession)

        logger.info("create session with bot version: ${createdSession.chatbot?.agentBranch}")
        return createdSession
    }

    /**
     * This deletes the corresponding user session so that next conversation starts from scratch.
     * Of course the user history (services enjoyed) should be saved somehow.
     */
    fun deleteUserSession(channel: IUserIdentifier, botInfo: BotInfo) {
        sessionStore.deleteSession(channel.channelId(), channel.userId!!, botInfo)
    }

    fun updateUserSession(channel: IUserIdentifier, botInfo: BotInfo, session: UserSession) {
        sessionStore.updateSession(channel.channelId(), channel.userId!!, botInfo, session)
    }

    fun updateTurn(turn: Turn, dialogActs: List<DialogAct>, session: UserSession) {
        turn.dialogActs = Json.encodeToJsonElement(dialogActs)
        Dispatcher.logTurns(session, turn)
        updateUserSession(session.userIdentifier, session.botInfo, session)
    }

    /**
     * This method will be called when contact submit an query along with events (should we allow this?)
     * And we need to come up with reply to move the conversation forward.
     *
     * event: the extra signal encoded.
     *
     * session: if the bot can handle it, we should keep botOwn as true, if bot can not, it should
     *          set the botOwn as false, and inform support.
     *
     * channel: the format that is expected.
     */

    fun getReplySync(
        session: UserSession,
        query: String,
        targetChannel: String? = null,
        events: List<FrameEvent> = emptyList()
    ): Map<String, List<String>> {
        logger.info("Got events:")
        logger.info(Json.encodeToString(events))

        session.targetChannel = if (targetChannel == null)  listOf(SideEffect.RESTFUL) else listOf(targetChannel, SideEffect.RESTFUL)

        val res = dm.responseAsync(query, events, session)

        val dialogActs = runBlocking { res.second.toList() }

        val turn = res.first
        updateTurn(turn, dialogActs, session)

        return Dispatcher.convertDialogActsToText(session, dialogActs, session.targetChannel)
    }

    fun getReplyFlow(
        session: UserSession,
        query: String,
        targetChannel: String,
        events: List<FrameEvent> = emptyList()
    ) : Flow<Map<String, List<String>>> = flow {
        val dialogActFlow = getDialogActFlow(session, query, targetChannel, events)
        dialogActFlow.collect {
            item -> Dispatcher.convertDialogActsToText(session, listOf(item), session.targetChannel)
        }
    }

    fun getDialogActFlow(
        session: UserSession,
        query: String,
        targetChannel: String,
        events: List<FrameEvent> = emptyList()
    ) : Flow<DialogAct> = flow {
        logger.info("Got events:" + Json.encodeToString(events))
        session.targetChannel = listOf(targetChannel, SideEffect.RESTFUL)

        val batched = getReplyFlowInside(session, query, targetChannel, events)
        val dialogActs = mutableListOf<DialogAct>()
        batched.second.collect { item ->
            for (dialogAct in item) {
                emit(dialogAct)
                dialogActs.add(dialogAct)
            }
        }

        val turn = batched.first
        updateTurn(turn, dialogActs, session)
    }


    private fun getReplyFlowInside(
        session: UserSession,
        query: String,
        targetChannel: String? = null,
        events: List<FrameEvent> = emptyList()
    ) : Pair<Turn, Flow<List<DialogAct>>> {
        session.targetChannel = if (targetChannel == null)  listOf(SideEffect.RESTFUL) else listOf(targetChannel, SideEffect.RESTFUL)
        val res = dm.responseAsync(query, events, session)
        val predicate : (DialogAct) -> Boolean = { item -> item is RequestForDelayDialogAct }
        val batched = batchFirstRest(res.second, predicate)
        return Pair(res.first, batched)
    }


    /**
     * Return the best implementation for given agentId.
     */
    fun getAgent(botInfo: BotInfo): IChatbot {
        return ChatbotLoader.findChatbot(botInfo)
    }

    companion object {
        private val master = "master"
        private val logger: Logger = LoggerFactory.getLogger(SessionManager::class.java)
    }
}