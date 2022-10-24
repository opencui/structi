package io.opencui.support

import io.opencui.core.Configuration
import io.opencui.core.IExtension
import io.opencui.core.TextPayload
import io.opencui.core.UserSession

interface ISupport : IExtension {
    // The label for support.
    fun name(): String

    val info: Configuration

    fun isInitiated(session: UserSession) : Boolean

    // This function return the room id in String so that we can forward conversation to right
    // place. By exposing the createRoom, we can potentially have different rooms for same contact
    // at different time, or not, depending on how createRoom is used.
    fun initSession(session: UserSession)

    // This make sure that we keep all the information needed。
    fun postBotMessage(contact: UserSession, content: TextPayload)
    fun postVisitorMessage(contact: UserSession, content: TextPayload)

    // This is used to hand control to live agent.
    fun handOff(contact: UserSession, department:String)
    fun close(session: UserSession)
}
