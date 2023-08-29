package io.opencui.core.user

import com.fasterxml.jackson.annotation.JsonIgnore
import io.opencui.core.*
import kotlin.reflect.KMutableProperty0

interface IUserIdentifier {
    var userId: String?
    var channelType: String?
    var channelLabel: String?

    var sessionId: String?
    var messageId: String?

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
interface IUserProfile: IUserIdentifier {
    var name: String?
    var phone: PhoneNumber?
    var email: String?
    var code: Int?
    var userInputCode: Int?
}

data class UserInfo(
    override var channelType: String?,
    override var userId: String?,
    override var channelLabel: String?
) : IUserProfile, HashMap<String, Any>() {
    override var sessionId: String? = null
    override var messageId: String? = null
    override var name: String? = null
    override var phone: PhoneNumber? = null
    override var email: String? = null
    override var code: Int? = null
    override var userInputCode: Int? = null

    init {
        // safeguard for over fashioned channelType, eventually should go away.
        assert(channelType!!.indexOf('+') == -1)
    }
}


/**
 */
data class UserIdentifier (
    override var userId: String?,
    override var channelType: String? = null,
    override var channelLabel: String? = null
): IUserIdentifier{
    override var sessionId: String? = null
    override var messageId: String? = null
}