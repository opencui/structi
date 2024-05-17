package io.opencui.core.user

import com.fasterxml.jackson.annotation.JsonIgnore
import io.opencui.core.*
import kotlin.reflect.KMutableProperty0

interface IUserIdentifier {
    // TODO: should we make this val instead?
    var userId: String?
    var channelType: String?
    var channelLabel: String?
    var isVerfied: Boolean

    var sessionId: String?
    var messageId: String?

    // return whether the user is verified, this should be initialized by channel.
    var name: PersonName?
    var phone: PhoneNumber?
    var email: Email?

    fun channelId() : String {
        return if (channelLabel == null) channelType!! else "$channelType+$channelLabel"
    }
    fun uuid(): String {
        return "c|$channelType|$channelLabel|$userId"
    }
}

// Support need profile, omnichannel need profile, and bot need profile for payment.
// For omnichannel, the key is uuid which can be verified phone or email.
// For bot/support, phone or email can be useful.
data class UserInfo(
    override var channelType: String?,
    override var userId: String?,
    override var channelLabel: String?,
    override var isVerfied: Boolean = false
) : IUserIdentifier, HashMap<String, Any>() {
    override var sessionId: String? = null
    override var messageId: String? = null

    override var name: PersonName? = null
    override var phone: PhoneNumber? = null
    override var email: Email? = null

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

    override fun createBuilder() = object : FillBuilder {
        var frame: UserIdentifier? = this@UserIdentifier
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            return filler
        }
    }
}