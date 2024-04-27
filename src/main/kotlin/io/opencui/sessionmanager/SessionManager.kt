package io.opencui.sessionmanager

import io.opencui.core.*
import io.opencui.core.da.DialogAct
import io.opencui.core.da.UserDefinedInform
import io.opencui.core.user.IUserIdentifier
import io.opencui.kvstore.IKVStore
import io.opencui.logger.ILogger
import org.slf4j.Logger
import org.slf4j.LoggerFactory

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
    }
}


// Some time, we need to save the bot related information across different versions for all users.
interface IBotStore: IKVStore {
    val botInfo: BotInfo
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
    fun getReply(
        session: UserSession,
        query: String,
        targetChannels: List<String>,
        events: List<FrameEvent> = emptyList()
    ): Map<String, List<String>> {
        assert(targetChannels.isNotEmpty())
        session.targetChannel = targetChannels
        val turnAndActs = dm.response(query, events, session)
        val responses = turnAndActs.second

        val turnLogger =  session.chatbot!!.getExtension<ILogger>()
        if (turnLogger != null) {
            logger.info("record turns using turn Logger.")
            // first update the turn so that we know who this user is talking too
            val turn = turnAndActs.first
            turn.channelType = session.channelType!!
            turn.channelLabel = session.channelLabel ?: "default"
            turn.userId = session.userId!!

            // we then log the turn
            turnLogger.log(turn)
        } else {
            logger.info("Could not find the provider for ILogger")
        }

        updateUserSession(session.userIdentifier, session.botInfo, session)
        return convertDialogActsToText(session, responses, session.targetChannel)
    }

    /**
     * This the place where we can remove extra prompt if we need to.
     */
    private fun convertDialogActsToText(session: UserSession, responses: List<DialogAct>, targetChannels: List<String>): Map<String, List<String>> {
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

