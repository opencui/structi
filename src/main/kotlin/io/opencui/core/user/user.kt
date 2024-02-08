package io.opencui.core.user

import com.fasterxml.jackson.annotation.JsonIgnore
import io.opencui.core.*
import io.opencui.serialization.Json
import kotlin.reflect.KMutableProperty0

interface IUserIdentifier {
    var userId: String?
    var channelType: String?
    var channelLabel: String?

    var sessionId: String?
    var messageId: String?

    // return whether the user is verified, this should be initialized by channel.
    var isVerfied: Boolean
    var name: String?
    var phone: PhoneNumber?
    var email: String?

    fun channelId() : String {
        return if (channelLabel == null) channelType!! else "$channelType+$channelLabel"
    }
    fun uuid(): String {
        return "c|$channelType|$channelLabel|$userId"
    }

    fun toJson() : String {
        return Json.encodeToString(
            mapOf(
                "userId" to userId,
                "channelType" to channelType,
                "channelLabel" to channelLabel,
                "seessionId" to sessionId,
                "messageId" to messageId
            )
        )
    }
}

// Support need profile, omnichannel need profile, and bot need profile for payment.
// For omnichannel, the key is uuid which can be verified phone or email.
// For bot/support, phone or email can be useful.
data class UserInfo(
    override var channelType: String?,
    override var userId: String?,
    override var channelLabel: String?
) : IUserIdentifier, HashMap<String, Any>() {
    override var sessionId: String? = null
    override var messageId: String? = null

    override var isVerfied: Boolean = false
    override var name: String? = null
    override var phone: PhoneNumber? = null
    override var email: String? = null

    init {
        // safeguard for over fashioned channelType, eventually should go away.
        assert(channelType!!.indexOf('+') == -1)
    }
}


/**
 */
class UserIdentifier (
    override var session: UserSession?
): IUserIdentifier by session!!, ISingleton {

    @JsonIgnore
    override lateinit var filler: FrameFiller<*>

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame: UserIdentifier? = this@UserIdentifier
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            return filler
        }
    }
}