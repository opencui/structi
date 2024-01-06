package io.opencui.du

import com.fasterxml.jackson.annotation.JsonIgnore
import io.opencui.core.*
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

// This is used to bridge encoder and decoder solution
data class ExampledLabel(
    override val utterance: String,
    override val ownerFrame: String,
    override val entailedSlots: List<String>,
    override val contextFrame: String? = null,
    override val label: String? = null) : ContextedExemplar {
    override var typedExpression: String = ""
    // for now, we keep it as the last resort.
    override var exactMatch: Boolean = false
    override var possibleExactMatch: Boolean = false

    // this is used for generic typed slot by bert model.
    override var guessedSlot: DUSlotMeta? = null
    override var score: Float = 0.0f

    fun isCompatible(type: String, packageName: String?) : Boolean {
        return ownerFrame == "${packageName}.${type}"
    }

    override fun clone(): ContextedExemplar { return this.copy() }
}

/**
 * This can be used to capture the intermediate result from understanding.
 * So that we can save some effort by avoiding repeated work.
 */
data class DuContext(
    val session: String,
    val utterance: String,
    val expectations: DialogExpectations = DialogExpectations(),
    val duMeta: DUMeta? = null) {
    val entityTypeToSpanInfoMap = mutableMapOf<String, MutableList<SpanInfo>>()
    var tokens : List<BoundToken>? = null
    val previousTokenByChar = mutableMapOf<Int, Int>()
    val nextTokenByChar = mutableMapOf<Int, Int>()


    val emapByCharStart by lazy { convert() }

    // for bert based state tracker only.
    var exemplars : List<ExampledLabel>? = null
    val bestCandidate : ExampledLabel?
        get() = exemplars?.get(0)

    fun convert(): Map<Int, List<Pair<String, Int>>> {
        // create the char end to token end.
        val endMap = mutableMapOf<Int, Int>()
        for ((index, token) in tokens!!.withIndex()) {
            endMap[token.end] = index + 1
        }

        val result = mutableMapOf<Int, MutableList<Pair<String, Int>>>()
        for((key, spans) in entityTypeToSpanInfoMap) {
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

    fun matchedIn(frameNames: List<String>): Boolean {
        // Right now, we only consider the best candidate, but we can extend this to other frames.
        return if (bestCandidate != null) frameNames.contains(bestCandidate!!.label) else false
    }

    fun getEntityValue(typeName: String): String? {
        //TODO("Not yet implemented")
        val spans = entityTypeToSpanInfoMap[typeName]
        if (spans.isNullOrEmpty()) {
            return null
        }
        val span = spans[0]

        // If we do not have bestCandidate or we entire utterance is covered by entity.
        return if (bestCandidate == null || (utterance.length == span.end && span.start ==0) ) {
            span.norm()
        } else {
            null
        }
    }

    fun putAll(lmap : Map<String, List<SpanInfo>>) {
        for ((k, vs) in lmap) {
            for (v in vs) {
                entityTypeToSpanInfoMap.put(k, v)
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

    private fun findMentions(entity: String, bot: DUMeta) : List<SpanInfo> {
        // if we do not have at least one that is not partial match, it is bad.
        var mentions = entityTypeToSpanInfoMap[entity]?.filter { !ListRecognizer.isPartialMatch(it.norm()) }
        if (!mentions.isNullOrEmpty()) return mentions
        val entityMeta = bot.getEntityMeta(entity) ?: return emptyList()
        logger.debug("Did not find $entity, trying ${entityMeta.children}")
        for (child in entityMeta.children) {
            mentions = entityTypeToSpanInfoMap[child]?.filter { !ListRecognizer.isPartialMatch(it.norm()) }
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
            val expectedType = bot.getSlotType(expectations.expected.frame, expectations.expected.slot!!)
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

    fun getSurroundingWordsBonus(slotMeta: DUSlotMeta, entity: SpanInfo): Float {
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
        const val SlotUpdateOriginalSlot = "originalSlot"

        const val SlotUpdateGenericType = "<T>"
        val IStatusSet = setOf(
            "io.opencui.core.confirmation.IStatus",
            "io.opencui.core.hasMore.IStatus",
            "io.opencui.core.booleanGate.IStatus")
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

/**
 * BertStateTracker assumes the underlying nlu module is bert based.
 */
interface LlmStateTracker: IStateTracker {
    val agentMeta: DUMeta

    // If there are multi normalizer propose annotation on the same span, last one wins.
    val normalizers: List<EntityRecognizer>
    val lang: String
    val dontCareForPagedSelectable: DontCareForPagedSelectable

    /**
     * Dialog expectation is used to inform DU module to be sensitive to certain information. This is important
     * as many expression can mean different things, and use expectation can make understanding a bit easy as
     * listening can be more focused.
     * Currently, there are couple different expectations:
     * 1. expecting a slot.
     * 2. expecting multi value.
     * 3. expecting confirmation.
     * 4. expecting value recommendation.
     * Of course, we can have combination of these.
     *
     * The main goal of this method is taking user utterance and convert that into frame events.
     * We follow the following process:
     * 1. find related expressions.
     * 2. use intent model to rerank the expression candidate and pick the best match and determine the frame.
     * 3. use slot model to find values for the slot for the given frame.
     * 4. generate frame events so that dialog engine can process it.
     *
     * Assumptions:
     * 1. We assume that index can be shared by different agent.
     */
    override fun convert(session: UserSession, putterance: String, expectations: DialogExpectations): List<FrameEvent> {
        logger.info("Getting $putterance under $expectations")
        // TODO(sean), eventually need to getLocale from user session, right now doing so break test.
        val utterance = putterance.lowercase(Locale.getDefault()).trim { it.isWhitespace() }
        if (utterance.isEmpty()) return listOf()

        val res0 = convertImpl(session, putterance, expectations)
        val res1 = res0.map { dontCareForPagedSelectable(it) }
        val componentSkillConvert = ComponentSkillConverter(agentMeta, expectations)
        val res2 = res1.map { componentSkillConvert(it) }
        return res2
    }

    fun buildDuContext(session: UserSession, utterance: String, expectations: DialogExpectations): DuContext {
        val ducontext = DuContext(session.userIdentifier.toString(), utterance, expectations, agentMeta)
        var allNormalizers = normalizers.toMutableList()
        // Session and turn based recognizers
        if (session.sessionRecognizer != null) allNormalizers += session.sessionRecognizer!!
        if (session.turnRecognizer != null) allNormalizers += session.turnRecognizer!!

        allNormalizers.recognizeAll(
            utterance,
            ducontext.expectedEntityType(agentMeta),
            ducontext.entityTypeToSpanInfoMap
        )
        ducontext.updateTokens(LanguageAnalyzer.get(agentMeta.getLang(), stop = false)!!.tokenize(utterance))
        return ducontext
    }

    /**
     * Dialog expectation is used to inform DU module to be sensitive to certain information. This is important
     * as many expression can mean different things, and use expectation can make understanding a bit easy as
     * listening can be more focused.
     * Currently, there are couple different expectations:
     * 1. expecting a slot.
     * 2. expecting multi value.
     * 3. expecting confirmation.
     * 4. expecting value recommendation.
     * Of course, we can have combination of these.
     *
     * The main goal of this method is taking user utterance and convert that into frame events.
     * We follow the following process:
     * 1. find related expressions.
     * 2. use intent model to rerank the expression candidate and pick the best match and determine the frame.
     * 3. use slot model to find values for the slot for the given frame.
     * 4. generate frame events so that dialog engine can process it.
     *
     * Assumptions:
     * 1. We assume that index can be shared by different agent.
     */
    fun convertImpl(
        session: UserSession,
        utterance: String,
        expectations: DialogExpectations
    ): List<FrameEvent> {
        val ducontext = buildDuContext(session, utterance, expectations)

        // TODO: support multiple intention in one utterance, abstractively.
        // Find best matched frame, assume one intention in one utterance.
        // this is used to detect frames.
        ducontext.exemplars = detectTriggerables(ducontext)

        // What happens if there are expectations.
        if (expectations.activeFrames.isNotEmpty()) {
            val events = handleExpectations(ducontext)
            if (events != null) return events
        }

        // Now, we have no dialog expectation. There are three different cases:
        // 1. found no candidates. If best candidate is null, return empty list.
        logger.debug("ducontext: $ducontext : ${ducontext.exemplars}")
        if (ducontext.exemplars.isNullOrEmpty()) {
            return listOf(buildFrameEvent(IStateTracker.FullIDonotKnow))
        }

        // 2. found more than one candidate. (currently we do not handle.)
        if (ducontext.exemplars != null && ducontext.exemplars!!.size > 1) {
            val components = ducontext.exemplars!!.map {
                buildFrameEvent(it.ownerFrame).apply { query = utterance }
            }

            // This return the raw frame event, we need to figure out a way to parse it one more time.
            // We may need a new api for this, otherwise, we will waste some parsing of utterance.
            return listOf(buildFrameEvent("io.opencui.core.IntentClarification", listOf(), components))
        }

        // 3. found just one candidate. Now we have one best candidate.
        val bestCandidate = ducontext.exemplars!![0]
        logger.debug("Found the best match ${bestCandidate}")

        // Of course, there are another dimension: whether we have expectation.
        val recognizedFrameType: String = bestCandidate.ownerFrame
        logger.debug("best matched frame: $recognizedFrameType, utterance: ${bestCandidate.typedExpression}")
        if (recognizedFrameType.isNotEmpty()) {
            // 6. matched a new intent
            var extractedEvents = fillSlots(ducontext, recognizedFrameType, null)
            if (extractedEvents.isEmpty()) {
                extractedEvents += buildFrameEvent(recognizedFrameType)
            }
            extractedEvents = addEntailedSlot(bestCandidate, extractedEvents)
            return extractedEvents
        }
        return listOf(buildFrameEvent(IStateTracker.FullIDonotKnow))
    }

    // This is used to recognize the triggerable skills.
    fun detectTriggerables(ducontext: DuContext): List<ExampledLabel>?

    fun handleExpectations(ducontext: DuContext): List<FrameEvent>?

    fun fillSlots(ducontext: DuContext, topLevelFrameType: String, focusedSlot: String?): List<FrameEvent>
    fun fillSlotUpdate(ducontext: DuContext, targetSlot: DUSlotMeta): List<FrameEvent>

    // At the frameevent level, we can reuse standard implementation.
    // given a list of frame event, add the entailed slots to the right frame event.
    fun addEntailedSlot(bestCandidate: ExampledLabel?, frameEvents: List<FrameEvent>): List<FrameEvent> {
        if (bestCandidate == null) return frameEvents
        if (bestCandidate.entailedSlots.isEmpty()) return frameEvents

        val events = mutableListOf<FrameEvent>()

        for (frameEvent in frameEvents) {
            if (bestCandidate.isCompatible(frameEvent.type, frameEvent.packageName)) {
                // merge function slot event with compatible event
                val allSlots = frameEvent.slots.toMutableList()
                allSlots.addAll(bestCandidate.entailedSlots.map { EntityEvent("\"_context\"", it) })
                events.add(FrameEvent(frameEvent.type, allSlots.toList(), packageName = frameEvent.packageName))
            } else {
                events.add(frameEvent)
            }
        }
        return events
    }

    companion object {
        val logger = LoggerFactory.getLogger(LlmStateTracker::class.java)
        // TODO(sean): make sure entity side return this as label for DONTCARE
        const val DONTCARE = "DontCare"
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
    fun resolve(ducontext: DuContext, before: List<ScoredDocument>): List<ScoredDocument>
}


interface ContextedExemplarsTransformer {
    operator fun invoke(origin: List<ContextedExemplar>): List<ContextedExemplar>
}

data class DontCareTransformer(val expectations: DialogExpectations): ContextedExemplarsTransformer {
    override fun invoke(pcandidates: List<ContextedExemplar>): List<ContextedExemplar> {
        // filter out the dontcare candidate if no dontcare is expected.
        val results = mutableListOf<ContextedExemplar>()
        val dontcare = expectations.allowDontCare()
        for (doc in pcandidates) {
            // DontCare phrase should only be useful when there don't care is expected.
            if (doc.ownerFrame == "io.opencui.core.DontCare") {
                if (dontcare) results.add(doc)
            } else {
                results.add(doc)
            }
        }
        return results
    }
}

data class StatusTransformer(val expectations: DialogExpectations): ContextedExemplarsTransformer {
    override fun invoke(pcandidates: List<ContextedExemplar>): List<ContextedExemplar> {
        val frames = expectations.activeFrames.map { it.frame }.toSet()
        // filter out the dontcare candidate if no dontcare is expected.
        val results = mutableListOf<ContextedExemplar>()
        for (doc in pcandidates) {
            if (doc.ownerFrame in IStateTracker.IStatusSet) {
                if (doc.ownerFrame in frames) results.add(doc)
            } else {
                results.add(doc)
            }
        }
        return results
    }
}

data class ChainedExampledLabelsTransformer(val transformers: List<ContextedExemplarsTransformer>) : ContextedExemplarsTransformer {
    constructor(vararg transformers: ContextedExemplarsTransformer): this(transformers.toList())

    override fun invoke(origin: List<ContextedExemplar>): List<ContextedExemplar> {
        var current = origin
        for( transform in transformers) {
            current = transform(current)
        }
        return current
    }
}