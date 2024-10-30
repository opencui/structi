package io.opencui.core

import io.opencui.channel.IChannel
import io.opencui.sessionmanager.SessionManager
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*
import io.opencui.core.user.IUserIdentifier
import io.opencui.sessionmanager.ChatbotLoader
import io.opencui.support.ISupport
import java.time.Duration
import java.time.LocalDateTime


/**
 * For receiving purpose, we do not need to implement this, as it is simply a rest controller
 * or a websocket service.
 *
 * Only the channel that can send message/reply out need to implement this, for example RCS.
 */

data class BadRequestException(override val message: String) : RuntimeException()

// Some channels are supported by live agent, and chatbot is just first line defense.
interface IManaged {
    // Chatbot can close this channel.
    fun closeSession(id:String, botInfo: BotInfo) {}

    fun handOffSession(id:String, botInfo: BotInfo, department: String) {}
}

interface Sink {
    fun markSeen(msgId: String?) {}
    fun typing() {}
    fun send(msg: String)

    fun send(session: UserSession, msg: String) {
        session.addBotMessage(msg)
        send(msg)
    }
}

data class ChannelSink(val channel: IChannel, val uid: String, val botInfo: BotInfo): Sink {
    override fun markSeen(msgId: String?) {
        if (channel is IMessageChannel) {
            channel.markSeen(uid, botInfo, msgId)
        }
    }
    override fun typing() {
        if (channel is IMessageChannel) {
            channel.typing(uid, botInfo)
        }
    }
    override fun send(msg: String) {
        channel.send(uid, msg, botInfo)
    }
}

data class SimpleSink(val sink: MutableList<String>): Sink {
    override fun send(msg: String) {
        sink.add(msg)
    }
}


//
// Dispatcher can be used to different restful controller to provide conversational
// interface for various channel.
//
// channelId is in form of channelType:channelLabel, and userId is a channel specific user
// identifier in string.
//
object Dispatcher {
    lateinit var sessionManager: SessionManager
    val logger: Logger = LoggerFactory.getLogger(Dispatcher::class.java)

    // if a user session is in passive for idleTimeInMinutes, it is considered to be idle.
    val idleTimeInMinutes = 5

    // This is used to make sure that we have a singleton to start the task.
    val timer = Timer()

    var botPrefix: String? = null
    // this is deployment wide parameter.
    var memoryBased: Boolean = true

    fun getChatbot(botInfo: BotInfo) : IChatbot {
        return sessionManager.getAgent(botInfo)
    }

    fun getSupport(botInfo: BotInfo): ISupport? {
        return getChatbot(botInfo).getExtension<ISupport>()
    }

    fun closeSession(target: IUserIdentifier, botInfo: BotInfo) {
        if (target.channelType != null && target.channelLabel != null) {
            val channel = getChatbot(botInfo).getChannel(target.channelLabel!!)
            if (channel != null && channel is IManaged) {
                (channel as IManaged).closeSession(target.userId!!, botInfo)
            }
        }
        sessionManager.deleteUserSession(target, botInfo)
    }

    fun send(target: IUserIdentifier, botInfo: BotInfo, msgs: List<String>) {
        val channel = getChatbot(botInfo).getChannel(target.channelLabel!!)
        if (channel != null && channel is IMessageChannel) {
            for (msg in msgs) {
                // Channel like messenger can not take empty message.
                val msgTrimmed = msg.trim()
                if (!msgTrimmed.isNullOrEmpty()) {
                    channel.send(target.userId!!, msg, botInfo)
                }
            }
        } else {
            logger.info("Cann't find ${target.channelType} for ${botInfo}")
        }
    }

    fun getUserSession(userInfo: IUserIdentifier, botInfo: BotInfo): UserSession? {
        return sessionManager.getUserSession(userInfo, botInfo)
    }

    fun createUserSession(userInfo: IUserIdentifier, botInfo: BotInfo): UserSession {
        val session = sessionManager.createUserSession(userInfo, botInfo)
        val support = getSupport(botInfo)
        if (support != null && !support.isInitiated(session)) {
            logger.info("init session now...")
            // TODO(sean): we need to get this back for chatwoot.
            support.initSession(session)
        }

        // This make UserIdentifier available to any frame as global.
        // There are three different use case:
        // 1. single channel only.
        // 2. multichannel but with no omnichannel requirements.
        // 3. multichannel with omnichannel requirement.
        // For #1 and #2, the follow the good enough.
        return session
    }


    fun process(userInfo: IUserIdentifier, botInfo: BotInfo, message: TextPayload) {
        logger.info("process $userInfo: $botInfo with message: $message")
        if (getUserSession(userInfo, botInfo) == null) {
            val userSession = createUserSession(userInfo, botInfo)
            // start the conversation from the Main.
            val events = listOf(FrameEvent("Main", emptyList(), emptyList(), "${botInfo.fullName}"))
            getReply(userSession, message, events)
        }else{
            val userSession = getUserSession(userInfo, botInfo)!!
            getReply(userSession, message)
        }
    }

    fun notify(userInfo: IUserIdentifier, botInfo: BotInfo, events: List<FrameEvent>) {
        // Notify should be handled as async.
        if (getUserSession(userInfo, botInfo) == null) {
            val userSession = createUserSession(userInfo, botInfo)
            // start the conversation from the Main.
            logger.info("notifyy: There is no existing user session, so create one and process the events right away.")
            val mainEvent = FrameEvent("OutMain", emptyList(), emptyList(), "${botInfo.fullName}")
            // We make sure that we always have some Main as context.
            getReply(userSession, null, listOf(mainEvent) + events)
        }else {
            val userSession = getUserSession(userInfo, botInfo)!!
            // We add the event to user session, and let it run.
            // The tricky part is user have the dangling/zombie session.
            val lastTouch = userSession.lastTouch
            if (lastTouch == null) {
                logger.info("notifyy: There is no last touch, so create one and process the events right away.")
                getReply(userSession, null, events)
                return
            }

            // Now whether it is considered to be idle
            val duration = Duration.between(lastTouch, LocalDateTime.now())
            if (duration.toMinutes() > idleTimeInMinutes) {
                logger.info("notifyy: Last touch is a while, so create one and process the events right away.")
                getReply(userSession, null, events)
            } else {
                if (userSession.isBreak()) {
                    logger.info("notifyy: On break, so create one and process the events right away.")
                    getReply(userSession, null, events)
                } else {
                    logger.info("notifyy: Last touch is too soon, not on break, so add to queue right away.")
                    userSession.addEvents(events)
                }
            }
        }
    }

    fun process(userInfo: IUserIdentifier, botInfo: BotInfo, events: List<FrameEvent>) {
        val userSession  = if (getUserSession(userInfo, botInfo) == null) {
            createUserSession(userInfo, botInfo)
        }else {
            getUserSession(userInfo, botInfo)!!
        }
        // start the conversation from the Main.
        getReply(userSession, null, events)
    }


    fun getReply(userSession: UserSession, message: TextPayload? = null, events: List<FrameEvent> = emptyList()) {
        val userInfo = userSession.userIdentifier
        val botInfo = userSession.botInfo

        val channel = getChatbot(botInfo).getChannel(userInfo.channelLabel!!)
        if (channel != null) {
            logger.info("Get channel: ${channel.info.toString()} with botOwn=${userSession.botOwn}")
            val sink = ChannelSink(channel, userInfo.userId!!, botInfo)

            getReply(userSession, message, sink, events)
        } else {
            logger.error("could not find ${userInfo.channelLabel}")
        }
    }

    fun getReply(userSession: UserSession, message: TextPayload? = null, sink: Sink, events: List<FrameEvent> = emptyList()) {
        val msgId = message?.msgId

        // if there is no msgId, or msgId is not repeated, we handle message.
        if (msgId != null && !userSession.isFirstMessage(msgId)) {
            logger.info("Not the first time see: $msgId")
            return
        }

        val userInfo = userSession.userIdentifier
        val botInfo = userSession.botInfo
        // For now, we only handle text payload, but we can add other capabilities down the road.
        val textPaylaod = message

        logger.info("Got $textPaylaod from ${userInfo.channelType}:${userInfo.channelLabel}/${userInfo.userId} for ${botInfo}")

        val support = getSupport(botInfo)

        logger.info("Support $support with hand off is based on:${userSession.botOwn}")

        if (!userSession.botOwn && support == null) {
            logger.info("No one own this message!!!")
            throw BadRequestException("No one own this message!!!")
        }

        // always try to send to support
        if (textPaylaod != null) support?.postVisitorMessage(userSession, textPaylaod)
        if(!userSession.botOwn){
            logger.info("$support already handed off")
            return
        }
        val query = textPaylaod?.text ?: ""
        if (userSession.botOwn) {
            // Let other side know that you are working on it
            logger.info("send hint...")
            if (message?.msgId != null) {
                sink.markSeen(message.msgId)
                sink.typing()
            }

            // always add the RESTFUL just in case.
            val msgs = getReplyForChannel(userSession, query, userInfo.channelType!!, events)

            for (msg in msgs) {
                support?.postBotMessage(userSession, TextPayload(msg))
            }

            logger.info("send $msgs to ${userInfo.channelType}/${userInfo.userId} from ${botInfo}")
            for (msg in msgs) {
                // Channel like messenger can not take empty message.
                val msgTrimmed = msg.trim()
                if (!msgTrimmed.isNullOrEmpty()) {
                    sink.send(userSession, msg)
                }
            }
        } else {
            if (support == null || !support.info.assist) return
            // assist mode.
            val msgs = getReplyForChannel(userSession, query, userInfo.channelType!!, events)
            for (msg in msgs) {
                support.postBotMessage(userSession, msg as TextPayload)
            }
        }
    }

    private fun getReplyForChannel(
        session: UserSession,
        query: String,
        targetChannel: String,
        events: List<FrameEvent> = emptyList()): List<String> {
        val msgMap = sessionManager.getReply(session, query, listOf(targetChannel, SideEffect.RESTFUL), events)
        val msg = if (!msgMap[targetChannel].isNullOrEmpty()) msgMap[targetChannel] else msgMap[SideEffect.RESTFUL]
        logger.info("get $msg for channel $targetChannel")
        return msg!!
    }

    // This is called to trigger handoff.
    fun handOffSession(target: IUserIdentifier, botInfo: BotInfo, department:String) {
        logger.info("handoff ${target.userId} at ${target.channelType} on ${botInfo} with depatment ${department}")


        val channel = getChatbot(botInfo).getChannel(target.channelLabel!!)
        if (channel == null) {
            logger.info("Channel ${target.channelType} not found.")
        }

        // remember to change botOwn to false.
        val userSession = sessionManager.getUserSession(target, botInfo)!!
        userSession.botOwn = false

        if (channel != null && channel is IManaged) {
            (channel as IManaged).handOffSession(target.userId!!, botInfo, department)
        } else {
            getSupport(botInfo)?.handOff(userSession, department)
        }
    }
}
