package io.opencui.core

import io.opencui.channel.IChannel
import io.opencui.core.da.DialogAct
import io.opencui.core.da.UserDefinedInform
import io.opencui.sessionmanager.SessionManager
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*
import io.opencui.core.user.IUserIdentifier
import io.opencui.support.ISupport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Duration
import java.time.LocalDateTime
import io.opencui.logger.ILogger
import io.opencui.logger.Turn
import kotlinx.coroutines.flow.collect

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


interface ControlSink {
    val targetChannel: String?
    fun markSeen(msgId: String?) {}
    fun typing() {}
}


// This is useful to create type sink.
data class TypeSink(
    override val targetChannel: String) : ControlSink


interface Sink : ControlSink{
    fun send(msg: String)

    // This is used for supporting fake streaming response back to client.
    // We assume the output from system2 chatbot is not super long, so we only
    // use this method to trigger forced delivery.
    fun flush() {}
}

data class ChannelSink(
    val channel: IChannel,
    val uid: String,
    val botInfo: BotInfo,
    override val targetChannel: String? = null
): Sink {
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

// An extension function.
fun Sink.send(msgMap: Map<String, List<String>>) {
    val msg = if (targetChannel != null && !msgMap[targetChannel].isNullOrEmpty()) msgMap[targetChannel] else msgMap[SideEffect.RESTFUL]
    if (!msg.isNullOrEmpty()) {
        for (text in msg) {
            send(text)
        }
    }
}

data class SimpleSink(override val targetChannel: String? = null): Sink {
    val messages: MutableList<String> = mutableListOf()
    override fun send(msg: String) {
        messages.add(msg)
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

    /**
     * This the place where we can remove extra prompt if we need to.
     */
    fun convertDialogActsToText(session: UserSession, responses: List<DialogAct>, targetChannels: List<String>): Map<String, List<String>> {
        val rewrittenResponses = session.rewriteDialogAct(responses)
        val dialogActPairs = rewrittenResponses.partition { it is UserDefinedInform<*> && it.frameType == "io.opencui.core.System1"}
        val dialogActs = replaceWithSystem1(dialogActPairs.second, dialogActPairs.first)
        return targetChannels.associateWith { k -> dialogActs.map {"""${if (k == SideEffect.RESTFUL) "[${it::class.simpleName}]" else ""}${it.templates.pick(k)}"""} }
    }

    private fun isDonotUnderstand(it: DialogAct): Boolean {
        return it is UserDefinedInform<*> && it.frameType == "io.opencui.core.IDonotGetIt"
    }

    private fun replaceWithSystem1(orig: List<DialogAct>, system1: List<DialogAct>) : List<DialogAct> {
        if (system1.isEmpty()) return orig
        val deduped = orig.removeDuplicate { isDonotUnderstand(it) }

        val res = mutableListOf<DialogAct>()
        for (it in deduped) {
            if (isDonotUnderstand(it)) {
                res.addAll(system1)
            } else {
                res.add(it)
            }
        }
        return res
    }


    fun logTurns(session: UserSession, turn: Turn) {
        val turnLogger = session.chatbot!!.getExtension<ILogger>()
        if (turnLogger != null) {
            logger.info("record turns using turn Logger.")
            // first update the turn so that we know who this user is talking too
            turn.channelType = session.channelType!!
            turn.channelLabel = session.channelLabel ?: "default"
            turn.userId = session.userId!!

            // we then log the turn
            turnLogger.log(turn)
        } else {
            logger.info("Could not find the provider for ILogger")
        }
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

    // This is used to process the inbound messages, thus use Main as context. In a sync fashion.
    fun processInbound(userInfo: IUserIdentifier, botInfo: BotInfo, message: TextPayload) {
        logger.info("process $userInfo: $botInfo with message: $message")
        if (getUserSession(userInfo, botInfo) == null) {
            val userSession = createUserSession(userInfo, botInfo)
            // start the conversation from the Main.
            val events = listOf(FrameEvent("Main", emptyList(), emptyList(), "${botInfo.fullName}"))
            buildSinkThenGetReply(userSession, message, events)
        }else{
            val userSession = getUserSession(userInfo, botInfo)!!
            buildSinkThenGetReply(userSession, message)
        }
    }

    // This is used for outbound message.
    fun processOutbound(userInfo: IUserIdentifier, botInfo: BotInfo, events: List<FrameEvent>) {
        // Notify should be handled as async.
        if (getUserSession(userInfo, botInfo) == null) {
            val userSession = createUserSession(userInfo, botInfo)
            // start the conversation from the Main.
            logger.info("notifyy: There is no existing user session, so create one and process the events right away.")
            val mainEvent = FrameEvent("OutMain", emptyList(), emptyList(), "${botInfo.fullName}")
            // We make sure that we always have some Main as context.
            buildSinkThenGetReply(userSession, null, listOf(mainEvent) + events)
        }else {
            val userSession = getUserSession(userInfo, botInfo)!!
            // We add the event to user session, and let it run.
            // The tricky part is user have the dangling/zombie session.
            val lastTouch = userSession.lastTouch
            if (lastTouch == null) {
                logger.info("notifyy: There is no last touch, so create one and process the events right away.")
                buildSinkThenGetReply(userSession, null, events)
                return
            }

            // Now whether it is considered to be idle
            val duration = Duration.between(lastTouch, LocalDateTime.now())
            if (duration.toMinutes() > idleTimeInMinutes) {
                logger.info("notifyy: Last touch is a while, so create one and process the events right away.")
                buildSinkThenGetReply(userSession, null, events)
            } else {
                if (userSession.isBreak()) {
                    logger.info("notifyy: On break, so create one and process the events right away.")
                    buildSinkThenGetReply(userSession, null, events)
                } else {
                    logger.info("notifyy: Last touch is too soon, not on break, so add to queue right away.")
                    userSession.addEvents(events)
                }
            }
        }
    }

    fun buildSinkThenGetReply(userSession: UserSession, message: TextPayload? = null, events: List<FrameEvent> = emptyList()) {
        val userInfo = userSession.userIdentifier
        val botInfo = userSession.botInfo

        val channel = getChatbot(botInfo).getChannel(userInfo.channelLabel!!)
        if (channel != null) {
            logger.info("Get channel: ${channel.info.toString()} with botOwn=${userSession.autopilotMode}")
            val sink = ChannelSink(channel, userInfo.userId!!, botInfo, userInfo.channelType)
            getReplySink(userSession, message, sink, events)
        } else {
            logger.error("could not find ${userInfo.channelLabel}")
        }
    }

    fun getReplySink(userSession: UserSession, message: TextPayload? = null, sink: Sink? = null, events: List<FrameEvent> = emptyList()) {
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

        logger.info("Support $support with hand off is based on:${userSession.autopilotMode}")

        if (!userSession.autopilotMode && support == null) {
            logger.info("No one own this message!!!")
            throw BadRequestException("No one own this message!!!")
        }

        // always try to send to support
        if (textPaylaod != null) support?.postVisitorMessage(userSession, textPaylaod)
        if(!userSession.autopilotMode){
            logger.info("$support already handed off")
            return
        }
        val query = textPaylaod?.text ?: ""
        if (userSession.autopilotMode) {
            // Let other side know that you are working on it
            logger.info("send hint...")
            if (message?.msgId != null && sink != null) {
                sink.markSeen(message.msgId)
                sink.typing()
            }

            // always add the RESTFUL just in case.
            val sink1 = SimpleSink(sink!!.targetChannel)
            sessionManager.getReplySink(userSession, query, sink1, events)

            for (msg in sink1.messages) {
                support?.postBotMessage(userSession, TextPayload(msg))
            }

            logger.info("send ${sink1.messages} to ${userInfo.channelType}/${userInfo.userId} from ${botInfo}")
            for (msg in sink1.messages) {
                // Channel like messenger can not take empty message.
                val msgTrimmed = msg.trim()
                if (msgTrimmed.isNotEmpty()) {
                    userSession.addBotMessage(msg)
                    sink.send(msg)
                }
            }
        } else {
            if (support == null || !support.info.assist) return
            // assist mode, not need to divide into two parts.
            val sink1 = SimpleSink(userInfo.channelType!!)
            sessionManager.getReplySink(userSession, query, sink1, events)
            for (msg in sink1.messages) {
                support.postBotMessage(userSession, msg as TextPayload)
            }
        }
    }

    fun convert(msgMapFlow: Flow<Map<String, List<String>>>, targetChannel: String): Flow<String> = flow {
        msgMapFlow.collect { msgMap ->
            val msgs =  if (msgMap.containsKey(targetChannel))  msgMap[targetChannel] else msgMap[SideEffect.RESTFUL]
            emit(msgs?.joinToString(" ") ?: "")
        }
    }

    // This is used to process the inbound messages, thus use Main as context. In a sync fashion.
    fun processInboundFlow(userInfo: IUserIdentifier, botInfo: BotInfo, message: TextPayload, sink: ControlSink? = null) : Flow<String> {
        logger.info("process $userInfo: $botInfo with message: $message")
        if (getUserSession(userInfo, botInfo) == null) {
            val userSession = createUserSession(userInfo, botInfo)
            // start the conversation from the Main.
            val events = listOf(FrameEvent("Main", emptyList(), emptyList(), "${botInfo.fullName}"))
            return convert(getReplyFlow(userSession, message, sink, events), sink!!.targetChannel!!)
        }else{
            val userSession = getUserSession(userInfo, botInfo)!!
            return convert(getReplyFlow(userSession, message, sink), sink!!.targetChannel!!)
        }
    }

    // This is useful for phones line, maybe
    fun getReplyFlow(
        userSession: UserSession,
        message: TextPayload? = null,
        sink: ControlSink? = null,  // Only useful for markSeen
        events: List<FrameEvent> = emptyList()): Flow<Map<String, List<String>>> = flow {
        val msgId = message?.msgId

        // if there is no msgId, or msgId is not repeated, we handle message.
        if (msgId != null && !userSession.isFirstMessage(msgId)) {
            logger.info("Not the first time see: $msgId")
            return@flow
        }

        val userInfo = userSession.userIdentifier
        val botInfo = userSession.botInfo
        // For now, we only handle text payload, but we can add other capabilities down the road.
        val textPaylaod = message

        logger.info("Got $textPaylaod from ${userInfo.channelType}:${userInfo.channelLabel}/${userInfo.userId} for ${botInfo}")

        val support = getSupport(botInfo)

        logger.info("Support $support with hand off is based on:${userSession.autopilotMode}")

        if (!userSession.autopilotMode && support == null) {
            logger.info("No one own this message!!!")
            throw BadRequestException("No one own this message!!!")
        }

        // always try to send to support
        if (textPaylaod != null) support?.postVisitorMessage(userSession, textPaylaod)
        if(!userSession.autopilotMode){
            logger.info("$support already handed off")
            return@flow
        }
        val query = textPaylaod?.text ?: ""

        if (userSession.autopilotMode) {
            // Let other side know that you are working on it

            if (message?.msgId != null && sink != null) {
                logger.info("send hint...")
                sink.markSeen(message.msgId)
                sink.typing()
            }

            // always add the RESTFUL just in case.
            val sink1 = SimpleSink(userInfo.channelType!!)
            val batched1 = sessionManager.getReplyFlow(userSession, query, sink1, events)

            // Need to copy the messages for system1, and then emit the messages.
            batched1.collect { item ->
                sink1.send(item)
                sink1.flush()
                emit(item)
            }

            for (msg in sink1.messages) {
                support?.postBotMessage(userSession, TextPayload(msg))
            }

            logger.info("send ${sink1.messages} to ${userInfo.channelType}/${userInfo.userId} from ${botInfo}")
            for (msg in sink1.messages) {
                // Channel like messenger can not take empty message.
                val msgTrimmed = msg.trim()
                if (msgTrimmed.isNotEmpty()) {
                    userSession.addBotMessage(msg)
                }
            }
        } else {
            if (support == null || !support.info.assist) return@flow
            // assist mode, not need to divide into two parts.
            val sink1 = SimpleSink(userInfo.channelType!!)
            sessionManager.getReplySink(userSession, query, sink1, events)
            for (msg in sink1.messages) {
                support.postBotMessage(userSession, msg as TextPayload)
            }
        }
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
        userSession.autopilotMode = false

        if (channel != null && channel is IManaged) {
            (channel as IManaged).handOffSession(target.userId!!, botInfo, department)
        } else {
            getSupport(botInfo)?.handOff(userSession, department)
        }
    }
}
