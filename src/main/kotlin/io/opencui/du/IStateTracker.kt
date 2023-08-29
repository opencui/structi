package io.opencui.du

import com.fasterxml.jackson.annotation.JsonIgnore
import io.opencui.core.FrameEvent
import io.opencui.core.IExtension
import io.opencui.core.UserSession
import io.opencui.core.user.IUserIdentifier
import io.opencui.core.user.SimpleUserIdentifier


/**
 * Dialog state tracker takes natural language user utterance, and convert that into frame event
 * based on dialog expectations that summarizes conversation history.
 *
 * For now, this functionality is separated into two levels:
 * 1. lower level nlu where context is not taking into consideration (bert).
 * 2. high level that use the output from low lever api and dialog expectation in context dependent way (kotlin).
 *
 * We will have potentially different lower level apis, for now, we assume the bert based on api
 * which is defined per document. We assume there are two models (intents and slots) for now, and
 * their apis is defined as the corresponding document.
 */


/**
 * For now, we assume the most simple expectation, current frame, and current slot, and whether do-not-care
 * is turned on for target slot.
 */
data class ExpectedFrame(
    val frame: String,
    val slot: String? = null,
    @JsonIgnore val slotType: String? = null,
    @JsonIgnore val allowDontCare: Boolean? = null) {
    fun allowDontCare() : Boolean {
        // TODO(sean) remove the hard code later.
        if (frame == "io.opencui.core.PagedSelectable" && slot == "index") return true
        return allowDontCare == true
    }
}

/**
 * This is used to store the dialog expectation for the current turn.
 * activeFrames is expected to have at least one ExpectedFrame.
 * Each dialog expectation corresponds to a topic (a scheduler at UI
 * level), so we need to understand the openness of the topic so that
 * we can help to understand.
 * The order the activeFrame should be ordered by top first, the top of the scheduler
 * should show up first in the activeFrames.
 */
data class DialogExpectation(val activeFrames: List<ExpectedFrame>) {
    // This is how rest of the code current assumes.
    @JsonIgnore
    val expected: ExpectedFrame = activeFrames[0]
}

/**
 * To support multi topics, we need to one dialog expectation for each topic.
 * TODO(xiaobo): the order should be in reverse last touched order, with first one is last touched.
 */
data class DialogExpectations(val expectations: List<DialogExpectation>) {
    @JsonIgnore
    val activeFrames: List<ExpectedFrame> = expectations.reversed().map{ it.activeFrames }.flatten()
    @JsonIgnore
    val expected: ExpectedFrame? = activeFrames.firstOrNull()

    constructor(vararg expectedFrames: ExpectedFrame): this(listOf(DialogExpectation(expectedFrames.asList())))
    constructor(expectation: DialogExpectation?) : this(if (expectation != null) listOf(expectation) else emptyList())
    constructor() : this(emptyList())

    fun getFrameContext(): List<String> {
        val res = ArrayList<String>()
        // TODO(sean) why we only search frame?
        // For now, we simply add these contexts frame for search.
        for (aframe in activeFrames) {
            res.add("""{"frame_id":"${aframe.frame}"}""")
        }
        return res
    }

    fun isFrameCompatible(frameName: String) : Boolean {
        for (aframe in activeFrames) {
            if (aframe.frame.equals(frameName)) return true
        }
        return false
    }

    fun allowDontCare() : Boolean {
        for (frame in activeFrames) {
            if (frame.allowDontCare()) return true
        }
        return false
    }

    fun hasExpectation(): Boolean {
        return activeFrames.isNotEmpty()
    }
}

/**
 * The main interface for dialog understanding: converts the user utterance into structured semantic
 * representation.
 * We encourage implementation to first support uncased model, so that the same model can be used for voice
 * data without needing to truecase it.
 */
interface IStateTracker : IExtension {
    /**
     * Converts the user utterance into structured semantic representations,
     *
     * @param user dialog session, used for logging purposes.
     * @param putterance what user said in the current turn.
     * @param expectations describes the current state of dialog from chatbot side,
     * @return list of FrameEvents, structural semantic representation of what user said.
     */
    fun convert(user: String, putterance: String, expectations: DialogExpectations = DialogExpectations()): List<FrameEvent> {
        // We keep this so that all the exist test can run.
        val userSession = UserSession(SimpleUserIdentifier(user))
        return convert(userSession, putterance, expectations)
    }

    fun convert(session: UserSession, putterance: String, expectations: DialogExpectations = DialogExpectations()): List<FrameEvent>
    /**
     * Test whether a given entity event is from partial match. Mainly used for potential slot
     */
    // fun isPartialMatch(event: EntityEvent): Boolean

    /**
     * Find related entities of the same entity type given a partial matched event.
     */
    // fun findRelatedEntity(event: EntityEvent): List<String>?

    /**
     * Life cycle method, return resources allocated for this state tracker.
     */
    fun recycle()

    companion object {
        const val FullIDonotKnow = "io.opencui.core.IDonotGetIt"
        const val FullDontCare = "io.opencui.core.DontCare"
        const val SlotUpdate = "io.opencui.core.SlotUpdate"
        const val SlotType = "io.opencui.core.SlotType"
        const val DontCareLabel = "_DontCare"
        const val FullThat = "io.opencui.core.That"
        const val ThatLabel = "{'@class'='io.opencui.core.That'}"
        const val FullBoolGate = "io.opencui.core.BoolGate"
        val FullBoolGateList = listOf("io.opencui.core.booleanGate.Yes", "io.opencui.core.booleanGate.No")

        const val TriggerComponentSkill =  "io.opencui.core.TriggerComponentSkill"
        const val FullConfirmation = "io.opencui.core.Confirmation"
        val FullConfirmationList = listOf("io.opencui.core.confirmation.Yes", "io.opencui.core.confirmation.No")
        const val FullHasMore = "io.opencui.core.HasMore"
        val FullHasMoreList = listOf("io.opencui.core.hasMore.Yes", "io.opencui.core.hasMore.No")
        const val KotlinBoolean = "kotlin.Boolean"
        const val SlotUpdateOriginalSlot = "originalSlot"

        const val SlotUpdateGenericType = "<T>"
    }
}


