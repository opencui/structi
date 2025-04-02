package io.opencui.system1

import io.opencui.core.Augmentation
import io.opencui.core.IExtension
import io.opencui.core.UserSession
import java.io.Serializable


/**
 * It is likely that the users are actually served by two systems: fast and slow.
 * The fast system can take care of many intuitive, automatic thinking that operates quickly and effortlessly.
 * So that builder can focus on more important aspects that needed more deliberation.
 * ISystem1 is used to capture one or more fast system.
 * The implementation, however, does not need to support the energy saving aspects of the system.
 *
 * For now, we assume that all system 1 come with multiple language support.
 * System1 can be smart or dumb, smart system can return empty when it knows it has no good things to say.
 * Dumb system will always return something.
 * For now, we assume the system1 are dumb.
 */





interface ISystem1 : IExtension {
    //  msgs and feedback are mutually exclusive.
    fun response(msgs: List<CoreMessage>): String

    fun response(msgs: List<CoreMessage>, augmentation: Augmentation): String

    fun response(userSession: UserSession): String? {
        // we go through the main scheduler and try to find the first one with no empty augmentation.

        return response(userSession.history)
    }
}

