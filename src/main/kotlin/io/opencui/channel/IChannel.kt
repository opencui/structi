package io.opencui.channel

import io.opencui.core.*
import io.opencui.core.user.IUserIdentifier
import io.opencui.serialization.Json
import io.opencui.serialization.JsonObject

interface IChannel : IExtension {
    val info: Configuration?

    // In case we can not get user info from the message itself.
    fun getIdentifier(botInfo: BotInfo, id: String): IUserIdentifier?

    data class Status(val message: String)

    // Channel implementation need to decode the message into the actual format that was
    // used by that channel. We assume that message is json encoding in string for now.
    // contact is also a channel dependent notation for user.
    fun send(id: String, payloadStr: String, botInfo: BotInfo, source: IUserIdentifier? = null): IChannel.Status {
        val payloadTrimed =  payloadStr.trim()
        println("playloadTrimed: $payloadTrimed")
        if (payloadTrimed[0] != '{') {
            // we got pure text
            sendWhitePayload(id, textMessage(payloadTrimed), botInfo, source)
        } else {
            // Now we assume that we are getting formatted payload
            val payloadJson = Json.parseToJsonElement(payloadTrimed)
            val type = payloadJson["type"].asText()
            if ( type in setOf("text", "rich", "listText", "listRich")) {
                val payload : IWhitePayload = Json.decodeFromJsonElement(payloadJson)
                sendWhitePayload(id, payload, botInfo, source)
            } else {
                sendRawPayload(id, payloadJson as JsonObject, botInfo, source)
            }
        }
        return IChannel.Status("works")
    }

    fun sendWhitePayload(id: String, rawMessage: IWhitePayload, botInfo: BotInfo, source: IUserIdentifier? = null): IChannel.Status
    fun sendRawPayload(uid: String, rawMessage: JsonObject, botInfo: BotInfo, source: IUserIdentifier? = null): IChannel.Status

    // Channel is the runtime facing abstraction. We also need to connect, this is typically
    // triggered externally in an operational environment, outside the agent life cycle, for outbound purpose.
    // After this is triggered, the third party will connect to companion controllers as usual.
    fun connect(id: String, botInfo: BotInfo? = null) {}

    // this is used to let client know that bot/agent decide to close the session.
    fun close(id: String, botInfo: BotInfo? = null) {}
}