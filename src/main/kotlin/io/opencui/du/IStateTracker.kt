package io.opencui.du

import com.fasterxml.jackson.annotation.JsonIgnore
import io.opencui.core.*
import io.opencui.core.da.DialogAct
import org.slf4j.LoggerFactory
import java.util.*


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

// Exemplars are used to make decisions for now.
interface Triggerable {
    val utterance: String
    var typedExpression: String
    val ownerFrame: String
    val contextFrame: String?
    val label: String?

    fun clone(): Triggerable
}

/**
 * For now, we assume the most simple expectation, current frame, and current slot, and whether do-not-care
 * is turned on for target slot.
 */
data class ExpectedFrame(
    val frame: String,
    val slot: String? = null,
    @JsonIgnore val slotType: String? = null,
    @JsonIgnore val prompt: List<DialogAct> = emptyList())



/**
 * This can be used to capture the intermediate result from understanding.
 * So that we can save some effort by avoiding repeated work.
 */
open class DuContext(
    open val session: String,
    open val utterance: String,
    open val expectations: DialogExpectations = DialogExpectations(),
    open val duMeta: DUMeta? = null) {

    // These are used to keep the entity values.
    val entityTypeToValueInfoMap = mutableMapOf<String, MutableList<ValueInfo>>()

    var tokens : List<BoundToken>? = null
    val previousTokenByChar = mutableMapOf<Int, Int>()
    val nextTokenByChar = mutableMapOf<Int, Int>()


    val emapByCharStart by lazy { convert() }

    fun getValuesByType(typeName: String): List<String> {
        if (!entityTypeToValueInfoMap.containsKey(typeName)) return emptyList()
        return entityTypeToValueInfoMap[typeName]?.map { utterance.substring(it.start, it.end) } ?: emptyList()
    }


    fun convert(): Map<Int, List<Pair<String, Int>>> {
        // create the char end to token end.
        val endMap = mutableMapOf<Int, Int>()
        for ((index, token) in tokens!!.withIndex()) {
            endMap[token.end] = index + 1
        }

        val result = mutableMapOf<Int, MutableList<Pair<String, Int>>>()
        for((key, spans) in entityTypeToValueInfoMap) {
            for (span in spans) {
                if (!result.containsKey(span.start)) result[span.start] = mutableListOf()
                result[span.start]!!.add(Pair(key, endMap[span.end]!!))
            }
        }
        return result
    }

    fun updateTokens(tkns: List<BoundToken>) {
        tokens = tkns
        for( (index, tkn) in tokens!!.withIndex() ) {
            if(index >  0) {
                previousTokenByChar[tkn.start] = index - 1
            }
            if(index < tokens!!.size - 1) {
                nextTokenByChar[tkn.end] = index + 1
            }
        }
    }

    fun putAll(lmap : Map<String, List<ValueInfo>>) {
        for ((k, vs) in lmap) {
            for (v in vs) {
                entityTypeToValueInfoMap.put(k, v)
            }
        }
    }

    fun containsAllEntityNeeded(entities: List<String>, bot: DUMeta) : Boolean {
        // if the entities is empty, then we already contain all entity needed.
        for (entity in entities) {
            // If we do not have this required entity, it is bad.
            if(findMentions(entity, bot).isEmpty()) return false
        }
        return true
    }

    private fun findMentions(entity: String, bot: DUMeta) : List<ValueInfo> {
        // if we do not have at least one that is not partial match, it is bad.
        var mentions = entityTypeToValueInfoMap[entity]?.filter { !ListRecognizer.isPartialMatch(it.norm()) }
        if (!mentions.isNullOrEmpty()) return mentions
        val entityMeta = bot.getEntityMeta(entity) ?: return emptyList()
        logger.debug("Did not find $entity, trying ${entityMeta.children}")
        for (child in entityMeta.children) {
            mentions = entityTypeToValueInfoMap[child]?.filter { !ListRecognizer.isPartialMatch(it.norm()) }
            if (!mentions.isNullOrEmpty()) return mentions
        }
        return emptyList()
    }

    fun expectedEntityType(bot: DUMeta) : List<String> {
        if (expectations.activeFrames.isEmpty()) return listOf()
        if (expectations.expected?.slot.isNullOrEmpty()) return listOf()

        // TODO: handle the a.b.c case
        val resList = mutableListOf<String>()
        if (expectations.expected!!.slot != null) {
            val expectedType = bot.getSlotType(expectations.expected!!.frame, expectations.expected!!.slot!!)
            resList.add(expectedType)
        } else {
            // Found the frame that has the slot
            for (active in expectations.activeFrames.reversed()) {
                if (active.slot != null) {
                    val activeType = bot.getSlotType(active.frame, active.slot)
                    resList.add(activeType)
                }
            }
        }
        return resList
    }

    fun getSurroundingWordsBonus(slotMeta: DUSlotMeta, entity: ValueInfo): Float {
        var bonus = 0f
        var denominator = 0.0000001f
        // for now, we assume simple unigram model.
        if (slotMeta.prefixes?.isNotEmpty() == true) {
            denominator += 1
            val previousTokenIndex = previousTokenByChar[entity.start]
            if (previousTokenIndex != null) {
                val tkn = tokens!![previousTokenIndex].token
                if (slotMeta.prefixes!!.contains(tkn)) {
                    bonus += 1
                }
            }
        }
        if (slotMeta.suffixes?.isNotEmpty() == true) {
            denominator += 1
            val nextTokenIndex = nextTokenByChar[entity.end]
            if (nextTokenIndex != null) {
                val tkn = tokens!![nextTokenIndex].token
                if (slotMeta.suffixes!!.contains(tkn)) {
                    bonus += 1
                }
            }
        }
        return bonus/denominator
    }

    fun getEntityValue(typeName: String): String? {
        //TODO("Only entity for now, Not yet implemented for frame")
        val spans = entityTypeToValueInfoMap[typeName]
        if (spans.isNullOrEmpty()) {
            return null
        }
        val span = spans[0]

        // If we do not have bestCandidate or we entire utterance is covered by entity.
        return if (utterance.length == span.end && span.start == 0) {
            span.norm()
        } else {
            null
        }
    }

    companion object {
        val logger = LoggerFactory.getLogger(DuContext::class.java)
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
        return activeFrames.map { """{"frame_id":"${it.frame}"}""" }
    }

    fun isFrameCompatible(frameName: String) : Boolean {
        for (aframe in activeFrames) {
            if (aframe.frame.equals(frameName)) return true
        }
        return false
    }

    fun hasExpectation(): Boolean {
        return activeFrames.isNotEmpty()
    }

    // For all the boolean questions, we run a yes/no inference.
    fun isBooleanSlot(): Boolean {
        if (isFrameCompatible(IStateTracker.ConfirmationStatus) ||
            isFrameCompatible(IStateTracker.BoolGateStatus) ||
            isFrameCompatible(IStateTracker.HasMoreStatus)) {
            return true
        }
        val slotType = expected?.slotType
        return slotType == IStateTracker.KotlinBoolean
    }

    override fun toString(): String {
        return activeFrames.toString()
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
        val userSession = UserSession(user)
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
        const val BoolGateStatus = "io.opencui.core.booleanGate.IStatus"
        val FullBoolGateList = listOf("io.opencui.core.booleanGate.Yes", "io.opencui.core.booleanGate.No")

        const val TriggerComponentSkill =  "io.opencui.core.TriggerComponentSkill"
        const val ConfirmationStatus = "io.opencui.core.confirmation.IStatus"
        val FullConfirmationList = listOf("io.opencui.core.confirmation.Yes", "io.opencui.core.confirmation.No")
        const val HasMoreStatus = "io.opencui.core.hasMore.IStatus"
        val FullHasMoreList = listOf("io.opencui.core.hasMore.Yes", "io.opencui.core.hasMore.No")
        const val KotlinBoolean = "kotlin.Boolean"
        const val KotlinString = "kotlin.String"
        const val SlotUpdateOriginalSlot = "originalSlot"

        const val SlotUpdateGenericType = "<T>"
        val IStatusSet = setOf(
            "io.opencui.core.confirmation.IStatus",
            "io.opencui.core.hasMore.IStatus",
            "io.opencui.core.booleanGate.IStatus")

        fun buildTracker(dumeta: DUMeta) : IStateTracker {
            // Instead of expose the implementation to code gen, let's first hide it. So that
            // we can later change this to 
            return DecoderStateTracker(dumeta)
        }

    }
}

interface FrameEventProcessor {
    operator fun invoke(input: FrameEvent) : FrameEvent
}

class DontCareForPagedSelectable: FrameEventProcessor {
    override operator fun invoke(event: FrameEvent) : FrameEvent {
        if (event.type == "PagedSelectable" &&
            event.slots.size == 1 &&
            event.slots[0].attribute == "index" &&
            event.slots[0].value == "\"_DontCare\""
        ) {
            return buildFrameEvent(
                "io.opencui.core.PagedSelectable",
                listOf(EntityEvent(value = """"1"""", attribute = "index"))
            )
        }
        return event
    }
}


/**
 * When the current active frames contains a skill for the new skill.
 */
data class ComponentSkillConverter(
    val duMeta: DUMeta,
    val dialogExpectation: DialogExpectations) : FrameEventProcessor {

    private val expectedFrames = dialogExpectation.expectations.map { it.activeFrames }.flatten()

    override fun invoke(p1: FrameEvent): FrameEvent {
        val matched = expectedFrames.firstOrNull { expectedFrame ->
            duMeta.getSlotMetas(expectedFrame.frame).find { it.type == p1.fullType } != null
        }

        return if (matched == null) {
            return p1
        } else {
            val componentSlot = duMeta.getSlotMetas(matched.frame).firstOrNull { it.type == p1.fullType}!!
            val entityEvents = listOf(
                buildEntityEvent("compositeSkillName", matched.frame),
                buildEntityEvent("componentSkillName", componentSlot.type!!)
            )
            return buildFrameEvent(IStateTracker.TriggerComponentSkill, entityEvents)
        }
    }
}

data class ChainedFrameEventProcesser(val processers: List<FrameEventProcessor>) : FrameEventProcessor {
    constructor(vararg transformers: FrameEventProcessor): this(transformers.toList())
    override fun invoke(p1: FrameEvent): FrameEvent {
        var current = p1
        for( transform in processers) {
            current = transform(current)
        }
        return current
    }
}


fun buildFrameEvent(
    topLevelFrame: String,
    slots: List<EntityEvent> = listOf(),
    frames: List<FrameEvent> = listOf()
): FrameEvent {
    val parts = topLevelFrame.splitToSequence(".")
    val packageName = parts.toList().subList(0, parts.count() - 1).joinToString(".", truncated = "")
    return FrameEvent(parts.last(), slots, frames, packageName)
}


fun buildEntityEvent(key: String, value: String): EntityEvent {
    return EntityEvent(value=""""$value"""", attribute=key)
}


// LlmStateTracker always try to recognize frame first, and then slot.
// We assume the output from recognizer should be taken seriously, or dependable, the quality fix should
// be inside the recognizer, not patching outside of recognizer.



// return the top k items from the collection.
fun <T : Comparable<T>> top(k: Int, collection: Iterable<T>): List<IndexedValue<T>> {
    val topList = ArrayList<IndexedValue<T>>()
    for ((index, logit) in collection.withIndex()) {
        if (topList.size < k || logit > topList.last().value) {
            topList.add(IndexedValue(index, logit))
            topList.sortByDescending { it.value }
            if (topList.size > k) {
                topList.removeAt(k)
            }
        }
    }
    return topList
}

fun <K, V> MutableMap<K, MutableList<V>>.put(key: K, value: V) {
    if (!this.containsKey(key)) {
        put(key, mutableListOf())
    }
    get(key)!!.add(value)
}


interface Resolver {
    fun resolve(ducontext: BertDuContext, before: List<ScoredDocument>): List<ScoredDocument>
}


