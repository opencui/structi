package io.opencui.du

import com.fasterxml.jackson.annotation.JsonIgnore
import io.opencui.core.*
import io.opencui.core.da.DialogAct
import org.slf4j.LoggerFactory
import java.util.regex.Pattern
import kotlin.collections.ArrayList


fun getName(bot: DUMeta, slot: String, owner: String): String? {
    val slotLabel = "$owner.$slot"
    val triggers = bot.getTriggers(slotLabel)

    return null
}


// We introduce this interface to bridge the encoder based DU and decoder based DU.
interface IExemplar {
    var typedExpression: String
    val ownerFrame: String
    val contextFrame: String?
    val label: String?
    val template: String
    var exactMatch: Boolean
    var possibleExactMatch: Boolean

    // whether there are free generic slot.
    val slotNames : List<String>

    // this is for generic slot (slot with generic type)
    var guessedSlot: DUSlotMeta?
    val usedFramesInType: MutableList<String>

    // var isBound : Boolean

    fun clone(): IExemplar

    fun isCompatible(type: String, packageName: String?) : Boolean {
        return ownerFrame == "${packageName}.${type}"
    }

    fun probes(bot: DUMeta) : String {
        return AngleSlotRegex.replace(typedExpression) {
            val slotTypeName = it.value.removePrefix("<").removeSuffix(">").removeSurrounding(" ")
            val triggers = bot.getTriggers(slotTypeName)
            if (triggers.isNullOrEmpty()) {
                // there are templated expressions that does not have trigger before application.
                "< $slotTypeName >"
            } else {
                "< ${triggers[0]} >"
            }
        }
    }

    companion object {
        val AngleSlotPattern = Pattern.compile("""<(.+?)>""")
        val AngleSlotRegex = AngleSlotPattern.toRegex()
    }
}


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

// Triggerable captures the utterance side understanding, mainly utterance segmentation, its owner.
interface Triggerable {
    val utterance: String
    var owner: String?
}

/**
 * For now, we assume the most simple expectation, current frame, and current slot, and whether do-not-care
 * is turned on for target slot.
 */
data class ExpectedFrame(
    val frame: String,
    val slot: String? = null,
    @JsonIgnore val slotType: String? = null,
    @JsonIgnore val prompt: List<DialogAct> = emptyList()) {

    // For all the boolean questions, we run a yes/no inference.
    fun isBooleanSlot(duMeta: DUMeta): Boolean {
        // whether the expected is the boolean.
        if (slot.isNullOrEmpty())
            return false

        val slotType = duMeta.getSlotType(frame, slot)
        return (slotType in IStateTracker.IStatusSet) || slotType == IStateTracker.KotlinBoolean
    }

    fun toDict() : Map<String, String?> {
        return mapOf("frame" to frame,  "slot" to slot, "slotType" to slotType)
    }
}

// These are the semantic operator that we might support down the road
enum class ValueOperator {
    And,
    Not,
    Or,
    EqualTo,
    LessThan,
    GreaterThan,
    LessThanOrEqualTo;

    companion object {
        fun convert(str: String) : ValueOperator {
            return when (str) {
                "==" -> EqualTo
                else -> throw IllegalArgumentException(str)
            }
        }
    }
}

fun <F,T> List<F>.mapFirst(block: (F) -> T?): T? {
    for (e in this) {
        block(e)?.let { return it }
    }
    return null
}


data class SlotValue(val values: List<String>, val operator: String  = "EqualTo")


// We need to figure out how to resolve the surface value to slot, from surface to type to slot to semantics.
data class OpSlotValue(val slot: String, val surface: String, val type: String, val operator: ValueOperator = ValueOperator.EqualTo) {
    var slotSurroundingBonus : Float = 0f
    var typeSurroundingBonus : Float = 0f
}

/**
 * The helper class need to resolve the entities. Recognized value are type based, extracted value are
 * slot model based.
 */
class SlotValueCandidates {
    val recognizedInfos = mutableListOf<ValueInfo>()
    val extractedInfos = mutableSetOf<OpSlotValue>()

    // Both has nothing to do with value itself.
    // slot context support the same type between different slots.
    val slotSurroundingBonuses = mutableMapOf<String, Float>()

    var active: Boolean = true


    fun addExtractedEvidence(OpSlotValue: OpSlotValue) {
        extractedInfos.add(OpSlotValue)
    }

    fun addRecognizedEvidence(value: ValueInfo) {
        if (value.type != IStateTracker.SlotType) {
            if (recognizedInfos.find { it.type == value.type && it.partialMatch == value.partialMatch } == null) {
                recognizedInfos.add(value)
            }
        } else {
            if (recognizedInfos.find { it.type == value.type && it.value == value.value } == null) {
                recognizedInfos.add(value)
            }
        }
    }

    fun addSlotContextBonus(slot: String, bonus: Float) {
        // Add to the right value
        extractedInfos.filter {it.slot == slot}.map { it.slotSurroundingBonus = bonus}
        slotSurroundingBonuses[slot] = bonus
    }

    private fun slotToValue(duContext: DuContext, frame: String, slot: String, targetSlot: DUSlotMeta?=null) : ValueInfo? {
        val slotMeta = duContext.duMeta!!.getSlotMeta(frame, slot)

        if (slotMeta == null) {
            // We should have data issues.
            EntityEventExtractor.logger.warn("Found no slot named $slot in $frame.")
            return null
        }
        val typeMatched = if (targetSlot == null) {
            // Find recognized value.
            recognizedInfos.find { !it.partialMatch && EntityEventExtractor.isCompatible(it.type, slotMeta.type!!) }
        } else {
            recognizedInfos.find { !it.partialMatch &&
                    EntityEventExtractor.isCompatible(it.type, slotMeta.type!!) &&
                    (it.value as String).startsWith(targetSlot!!.parent!!)
            }
        }
        return typeMatched?.apply { slotName = slot } ?: return null

    }

    fun valueByContext(duContext: DuContext, frame: String, targetSlot: DUSlotMeta?=null) : ValueInfo? {
        val withBonus = slotSurroundingBonuses.filterValues { it > 0f }.toList()
        if (withBonus.isEmpty()) {
            return null
        }
        val slots = withBonus.sortedBy { -it.second }
        if (frame != IStateTracker.SlotUpdate) {
            return slots.mapFirst { slotToValue(duContext, frame, it.first) }
        } else {
            val result = slots.mapFirst { slotToValue(duContext, frame, it.first, targetSlot) }
            return result
        }
    }

    fun valueByExtractionAndContext(duContext: DuContext, frame: String, isGoodValue: (OpSlotValue) -> Boolean) : ValueInfo? {
        val withBonus =  extractedInfos
            .filter { isGoodValue(it) }
            .map { Pair(it.slot, it.slotSurroundingBonus) }
        if (withBonus.isEmpty()) return null
        val slots = withBonus.sortedBy { -it.second }.map { it.first }

        return slots.mapFirst { slotToValue(duContext, frame, it) }
    }
}


// We use this for decide how to interpret the extracted value
data class EntityEventExtractor(val duContext: DuContext){
    val candidateMap = mutableMapOf<Pair<Int, Int>, SlotValueCandidates>()

    // We might want to reuse the recognized entity for all the frames.
    var frame: String = ""
    var slotMetas: List<DUSlotMeta> = emptyList()

    var slotOfSlotType : DUSlotMeta? = null


    fun markUsed(span: Pair<Int, Int>) {
        // Now we go over all the rest of span, if it is incompatible with this span,
        // know them off.
        candidateMap[span]!!.active = false
        for (entry in candidateMap) {
            val key = entry.key
            if (key.first >= span.first && key.first <= span.second) {
                entry.value.active = false
            }
            if (key.second >= span.second && key.second <= span.second) {
                entry.value.active = false
            }
        }
    }

    fun cleanExtracted(frame: String, slotMetas: List<DUSlotMeta>) {
        // This way, we can reuse the recognized part.
        for (entry in candidateMap) {
            entry.value.extractedInfos.clear()
            entry.value.slotSurroundingBonuses.clear()
        }
        this.frame = frame
        this.slotMetas = slotMetas
    }

    fun initWithRecognized() {
        // We only need to do this once for all the potentially nested frames, if any.
        for (entry in duContext.entityTypeToValueInfoMap) {
            val values = entry.value
            for (value in values) {
                put(value)
            }
        }
    }

    fun put(valueInfo: ValueInfo) {

        // We need to remove the frame slot for SlotType
        if (valueInfo.type == IStateTracker.SlotType) {
            val partsInQualified = valueInfo.value.toString().split(".")
            val slotName = partsInQualified.last()
            val frameName = partsInQualified.subList(0, partsInQualified.size - 1).joinToString(".")
            val duMeta = duContext.duMeta!!
            val slotMeta = duMeta.getSlotMeta(frameName, slotName) ?: return
            if (!duMeta.isEntity(slotMeta.type!!)) return
        }

        val span = Pair(valueInfo.start, valueInfo.end)
        if (!candidateMap.containsKey(span)) {
            candidateMap[span] = SlotValueCandidates()
        }
        val candidates = candidateMap[span]!!
        candidates.addRecognizedEvidence(valueInfo)
    }

    fun put(surface: String, slot: String, type: String, op: String="==") {
        if (surface.isEmpty()) return
        // We do not need to worry about the overlapping for now.
        var startIndex = 0
        while (startIndex < duContext.utterance.length) {
            val index = duContext.utterance.indexOf(surface, startIndex)
            if (index != -1) {
                val span = Pair(index, index + surface.length)
                if (!candidateMap.containsKey(span)) {
                    candidateMap[span] = SlotValueCandidates()
                }
                candidateMap[span]!!.addExtractedEvidence(OpSlotValue(slot, surface, type, ValueOperator.convert(op)))
                startIndex = index + 1
            } else {
                // This exit the loop.
                startIndex = duContext.utterance.length
            }
        }
    }

    fun addExtractedEvidence(results: Map<String, SlotValue>) {
        for (entry in results.entries) {
            val values = entry.value.values.toMutableList()
            for (value in values) {
                put(value, entry.key, entry.value.operator)
            }
        }
    }

    fun resolveType(ducontext: DuContext, slotMetas: List<DUSlotMeta>) {
        // This resolve type.
        for (entry in candidateMap) {
            val span = entry.key
            val evidence = entry.value

            for (slotMeta in slotMetas) {
                val bonus = ducontext.getSurroundingWordsBonus(slotMeta, span.first, span.second)
                evidence.addSlotContextBonus(slotMeta.label, bonus)
            }
        }
    }

    fun resolveByExtractionAndContext(frame: String, results: MutableList<EntityEvent>, isGoodValue: (OpSlotValue) -> Boolean)  {
        // First round we require that we have support, and it needs to be normalizable.
        for (entry in candidateMap) {
            if (!entry.value.active) continue

            //  TODO(sean): how do we handle slot clarification, etc.
            // first we require that we have some slot support.
            val valueInfo = entry.value.valueByExtractionAndContext(duContext, frame, isGoodValue)
            if (valueInfo != null) {
                val value = valueInfo.original(duContext.utterance)
                markUsed(entry.key)
                results.add(EntityEvent.build(valueInfo.slotName!!, value, valueInfo.norm()!!, valueInfo.type))
            }
        }
    }

    fun resolveByContext(frame: String, results: MutableList<EntityEvent>)  {
        // First round we require that we have support, and it needs to be normalizable.
        for (entry in candidateMap) {
            if (!entry.value.active) continue

            //  TODO(sean): how do we handle slot clarification, etc.
            // first we require that we have some slot support.
            val valueInfo = entry.value.valueByContext(duContext, frame, slotOfSlotType)
            if (valueInfo != null) {
                val value = valueInfo.original(duContext.utterance)
                markUsed(entry.key)
                results.add(EntityEvent.build(valueInfo.slotName!!, value, valueInfo.norm()!!, valueInfo.type))
            }
        }
    }

    fun resolveRecognizedSlot(frame: String, results: MutableList<EntityEvent>, isGoodValue: (ValueInfo) -> Boolean) {
        val slotMetas = duContext.duMeta!!.getSlotMetas(frame)
        val slotTypes = slotMetas.map { it.type!! }.toSet()
        if (slotTypes.isEmpty()) return

        for (entry in candidateMap) {
            if (!entry.value.active) continue
            val typedCandidates0 = entry.value.recognizedInfos.filter { isGoodValue(it) }
            val typedCandidates = typedCandidates0.filter { isCompatible(it.type, slotTypes)}
            if (typedCandidates.isEmpty()) continue
            if (typedCandidates.size == 1) {
                val valueInfo = typedCandidates[0]
                val value = valueInfo.original(duContext.utterance)

                val compatibleSlots = slotMetas.filter { isCompatible(valueInfo.type, it.type!!) }
                if (compatibleSlots.isNotEmpty()) {
                    if (compatibleSlots.size == 1) {
                        val slotName = compatibleSlots[0].label
                        markUsed(entry.key)
                        results.add(EntityEvent.build(slotName, value, valueInfo.norm()!!, valueInfo.type))
                    } else {
                        // TODO: This has two potential binding, we should use slot clarification.
                    }
                }
            } else {
                // TODO: There are two types associated with this span.
            }
        }
    }


    fun resolveSlot(frame: String, focusedSlot: String? = null): FrameEvent? {
        // We need to do this from multiple rounds
        // Aside from the operator semantic: equals, not, etc. The evidence for value
        // comes from these four possibilities:
        // Slot model, slot context
        // type model, type context

        //  We try to extract the value with the most confidence first.
        val duMeta = duContext.duMeta!!

        val entityEvents = mutableListOf<EntityEvent>()

        // For now, we trust the evidence we found.

        // First round we require that we have support, and it needs to be normalizable.
        // slot model + slot context + any type evidence.
        resolveByExtractionAndContext(frame, entityEvents) { it.slotSurroundingBonus > 0.0f }
        resolveByExtractionAndContext(frame, entityEvents) { it.slotSurroundingBonus == 0.0f }

        // slot model + any type evidence.
        resolveByContext(frame, entityEvents)


        // if it is not normalizable
        for (entry in candidateMap) {
            // No need to handle the ones that is handled
            if (!entry.value.active) continue

            val slotCandidates = entry.value.extractedInfos.toList()

            // if there is no model support, skip
            if (slotCandidates.isEmpty()) continue

            if (slotCandidates.size == 1) {
                val slotName = slotCandidates[0].slot
                val slotValue = slotCandidates[0].surface
                val slotMeta = duContext.duMeta!!.getSlotMeta(frame, slotCandidates[0].slot)
                if (slotMeta != null) {
                    // We only handle entity slot for now, for frame slot.
                    if (duContext.duMeta!!.isEntity(slotMeta.type!!)) {
                        val normalizable = duContext.duMeta!!.getEntityMeta(slotMeta.type)?.normalizable ?: false
                        if (!normalizable) {
                            entityEvents.add(EntityEvent.build(slotName, slotValue, slotValue, slotMeta.type))
                            markUsed(entry.key)
                        } else {
                            //TODO(sean): what happens if it is normalizable?
                            logger.debug("Strange, why are we here?")
                        }
                    }
                } else {
                    // This is where nested slot go, for now, we skip, but we might need to revisit
                    // for the nested slot where their host frame is not on stack. We need to expand the host frame.
                    logger.debug("nested slot for $frame?")
                }
            } else {
                // TODO: we extract slot clarification.
            }
        }

        // TODO(sean): we are potentially missing two rounds here.
        // both type evidence.
        resolveRecognizedSlot(frame, entityEvents) { it.hasTypeBonus() }

        // with just type model.
        resolveRecognizedSlot(frame, entityEvents) { !it.hasTypeBonus() }

        // if it is without extraction support, and some time just partial match.
        val focusedSlotType = if (focusedSlot.isNullOrEmpty()) null else duMeta.getSlotType(frame, focusedSlot)
        if (entityEvents.size == 0 && focusedSlotType != null) {
            val spannedValues = duContext.entityTypeToValueInfoMap[focusedSlotType]
            val normalizable = duMeta.getEntityMeta(focusedSlotType)?.normalizable
            // under condition where we have a unique partial match
            if (spannedValues?.size == 1 && normalizable == true) {
                val value = spannedValues[0]
                val originalValue = value.original(duContext.utterance)
                entityEvents.add(EntityEvent.build(focusedSlot!!, originalValue, value.norm()!!, focusedSlotType))
                markUsed(Pair(value.start, value.end))
            }
        }

        if (!duContext.expectations.isFrameCompatible(frame) || entityEvents.size != 0) {
            return FrameEvent.build(frame, entityEvents)
        }
        return null
    }

    companion object {
        val logger = LoggerFactory.getLogger(EntityEventExtractor::class.java)

        fun isCompatible(type: String, slotTypes: Set<String>): Boolean {
            return type in slotTypes || "T" in slotTypes
        }

        fun isCompatible(valueType: String, slotType: String): Boolean {
            return slotType == "T" || slotType == valueType
        }
    }
}


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

    val expectedFrames by lazy { expectations.activeFrames }
    val normalizers = mutableListOf<EntityRecognizer>()

    val emapByCharStart by lazy { convert() }

    fun getValuesByType(typeName: String): List<ValueInfo> {
        if (!entityTypeToValueInfoMap.containsKey(typeName)) return emptyList()
        return entityTypeToValueInfoMap[typeName]?.filter{ !it.partialMatch }?: emptyList()
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


    fun expectedEntityType(expectations: DialogExpectations) : List<String> {
        if (expectations.activeFrames.isEmpty()) return listOf()

        // TODO: handle the a.b.c case
        val resList = mutableListOf<String>()
        // Found the frame that has the slot
        for (active in expectations.activeFrames.reversed()) {
            if (active.slot != null) {
                val activeType = duMeta!!.getSlotType(active.frame, active.slot)
                resList.add(activeType)
            }
        }

        return resList
    }

    fun cleanUp(expectations: DialogExpectations) {
        val expectedValues = mutableListOf<ValueInfo>()
        for (expectation in expectations.activeFrames) {
            if (!expectation.slot.isNullOrEmpty()) {
                val slotType = duMeta!!.getSlotType(expectation.frame, expectation.slot)
                val localValues = entityTypeToValueInfoMap[slotType]
                if (!localValues.isNullOrEmpty()) {
                    expectedValues.addAll(localValues)
                }
            }
        }

        val keysToBeRemoved = mutableListOf<String>()
        for (key in entityTypeToValueInfoMap.keys) {
            val values = entityTypeToValueInfoMap[key]
            if (!values.isNullOrEmpty()) {
                values.removeAll { covered(it, expectedValues) }
                if (values.isEmpty()) {
                    keysToBeRemoved.add(key)
                }
            }
        }

        for (key in keysToBeRemoved) {
            entityTypeToValueInfoMap.remove(key)
        }
    }

    fun covered(value: ValueInfo, values: List<ValueInfo>) : Boolean {
        for (lvalue in values) {
            if(value.start >= lvalue.start && value.end < lvalue.end ||
                value.start > lvalue.start && value.end <= lvalue.end)
                return true
        }
        return false
    }


    fun getSurroundingWordsBonus(slotMeta: DUSlotMeta, entity: ValueInfo): Float {
        return getSurroundingWordsBonus(slotMeta, entity.start, entity.end)
    }

    fun getSurroundingWordsBonus(slotMeta: DUSlotMeta, start: Int, end: Int): Float {
        var prefix_bonus = 0f
        // for now, we assume simple unigram model.
        if (slotMeta.prefixes?.isNotEmpty() == true) {
            val previousTokenIndex = previousTokenByChar[start]
            if (previousTokenIndex != null) {
                val tkn = tokens!![previousTokenIndex].token
                if (slotMeta.prefixes!!.contains(tkn)) {
                    prefix_bonus += 1
                }
            }
        }

        var suffix_bonus = 0f
        if (slotMeta.suffixes?.isNotEmpty() == true) {
            val nextTokenIndex = nextTokenByChar[end]
            if (nextTokenIndex != null) {
                val tkn = tokens!![nextTokenIndex].token
                if (slotMeta.suffixes!!.contains(tkn)) {
                    suffix_bonus += 1
                }
            }
        }
        return (prefix_bonus + suffix_bonus) as Float
    }


    // We assign the entity to one of the slot in the slotMetas.
    fun resolveSlot(entity: ValueInfo, slotMetas: List<DUSlotMeta>) {
        val scores = ArrayList<Float>(slotMetas.size)
        for (slotMeta in slotMetas) {
            scores.add(getSurroundingWordsBonus(slotMeta, entity))
        }

        // If we have clear winner, we assign that, otherwise, we assign all slot to it.
        val maxScore = scores.maxByOrNull {it}!!
        val indexes = scores.indexesOf {it == maxScore}
        val slots = indexes.map { slotMetas[it].label }

        // Clear it first then assign new slot labels
        entity.possibleSlots.clear()
        if (slots.size == 1) entity.possibleSlots.addAll(slots)
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
            if (aframe.frame == frameName) return true
        }
        return false
    }

    fun hasExpectation(): Boolean {
        return activeFrames.isNotEmpty()
    }

    override fun toString(): String {
        return activeFrames.toString()
    }
}




// LlmStateTracker always try to recognize frame first, and then slot.
// We assume the output from recognizer should be taken seriously, or dependable, the quality fix should
// be inside the recognizer, not patching outside of recognizer.
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


    fun isSystemFrame(frame: String?): Boolean {
        return frame?.startsWith("io.opencui.core") ?: false
    }

    fun isUpdateSlot(frame: String?): Boolean {
        return frame == SlotUpdate
    }

    fun isPickValue(frame: String?) : Boolean {
        return frame == PickValue
    }

    fun isPickNotValue(frame: String?) : Boolean {
        return frame == PickNotValue
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

    fun isSlotMatchedWithHead(duMeta: DUMeta, valueInfo: ValueInfo, activeFrame: String): Boolean {
        val spanTargetSlot = valueInfo.value.toString()
        val parts = spanTargetSlot.split(".")
        val spanTargetFrame = parts.subList(0, parts.size - 1).joinToString(separator = ".")
        val slotName = parts.last()
        val slotMeta = duMeta.getSlotMeta(spanTargetFrame, slotName)!!
        if (spanTargetSlot.startsWith(activeFrame) && duMeta.isEntity(slotMeta.type!!)) return true

        val spanTargetFrameHasHead = duMeta.getSlotMetas(spanTargetFrame).any { it.isHead }
        // now we need to figure out whether active Frame as a frame slot of this time.
        val matchedFrameSlots = duMeta.getSlotMetas(activeFrame).filter { it.type == spanTargetFrame }
        return spanTargetFrameHasHead && matchedFrameSlots.size == 1
    }

    fun isSlotMatched(duMeta: DUMeta, valueInfo: ValueInfo, activeFrame: String): Boolean {
        val spanTargetSlot = valueInfo.value.toString()
        val parts = spanTargetSlot.split(".")
        val spanTargetFrame = parts.subList(0, parts.size - 1).joinToString(separator = ".")
        val slotName = parts.last()
        val slotMeta = duMeta.getSlotMeta(spanTargetFrame, slotName)!!
        return (spanTargetSlot.startsWith(activeFrame) && duMeta.isEntity(slotMeta.type!!))
    }

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
        const val HasMore = "io.opencui.core.HasMore"
        const val TriggerComponentSkill =  "io.opencui.core.TriggerComponentSkill"
        const val ConfirmationStatus = "io.opencui.core.confirmation.IStatus"
        val FullConfirmationList = listOf("io.opencui.core.confirmation.Yes", "io.opencui.core.confirmation.No")
        const val HasMoreStatus = "io.opencui.core.hasMore.IStatus"
        val FullHasMoreList = listOf("io.opencui.core.hasMore.Yes", "io.opencui.core.hasMore.No")
        const val KotlinBoolean = "kotlin.Boolean"
        const val KotlinString = "kotlin.String"
        const val ValueSymbol = "<>"
        const val PickValue = "io.opencui.core.PickValue"
        const val PickNotValue = "io.opencui.core.PickNotValue"
        const val PickOrtValue = "io.opencui.core.PickOrValue"
        const val PagedSelectable = "io.opencui.core.PagedSelectable"
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

        fun onlyHandleOneSlot(): List<FrameEvent> {
            // TODO: add something more concrete so that user would know.
            return listOf(FrameEvent.build(IStateTracker.FullIDonotKnow))
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
            return FrameEvent.build(
                "io.opencui.core.PagedSelectable",
                listOf(EntityEvent(value = """"1"""", attribute = "index"))
            )
        }
        return event
    }
}



interface FrameEventsProcessor {
    operator fun invoke(input: MutableList<FrameEvent>)
}


class HasNoMoreCleaner: FrameEventsProcessor {
    override fun invoke(input: MutableList<FrameEvent>) {
        val hasNoMore = input.find { it.type == "No" && it.packageName == "io.opencui.core.hasMore"}
        val pagedSelectable = input.find { it.type == "PagedSelectable"}
        if (hasNoMore != null && pagedSelectable != null) {
            input.removeIf { it.type == "No" && it.packageName == "io.opencui.core.hasMore" }
        }
    }

}

fun filter(filters: List<FrameEventsProcessor>, input: MutableList<FrameEvent>) {
    for (proc in filters) {
        proc(input)
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
        val matched = expectedFrames.firstOrNull {
            expectedFrame -> duMeta.getSlotMetas(expectedFrame.frame).find { it.type == p1.fullType } != null
        }

        if (!duMeta.isSkill(p1.fullType) || matched == null) {
            return p1
        } else {
            val componentSlot = duMeta.getSlotMetas(matched.frame).firstOrNull { it.type == p1.fullType }!!
            val entityEvents = listOf(
                EntityEvent.build("compositeSkillName", matched.frame),
                EntityEvent.build("componentSkillName", componentSlot.type!!)
            )
            return FrameEvent.build(IStateTracker.TriggerComponentSkill, entityEvents)
        }
    }
}

/**
 * When the current active frames contains a skill for the new skill.
 */
data class RawInputSkillConverter(val duMeta: DUMeta) {
    // This is used to detect whether one class implements another class

    fun invoke(p1: FrameEvent, utterance: String): FrameEvent {
        if (!duMeta.isSkill(p1.fullType)) {
            return p1
        }
        // val clazz = Class.forName(p1.fullType)
        val clazz = this::class.java.classLoader.loadClass(p1.fullType)
        // no entity events, no frame slot events.
        if (!IRawInputHandler::class.java.isAssignableFrom(clazz) && p1.slots.isEmpty() && p1.frames.isEmpty()) {
            return p1;
        } else {
            val entityEvents = listOf(
                EntityEvent.build("rawUserInput", utterance),
            )
            return FrameEvent.build(p1.fullType, entityEvents)
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
    fun resolve(ducontext: DuContext, before: List<IExemplar>): List<IExemplar>
}


interface ContextedExemplarsTransformer {
    operator fun invoke(origin: List<Triggerable>): List<Triggerable>
}


data class StatusTransformer(val expectations: DialogExpectations): ContextedExemplarsTransformer {
    override fun invoke(pcandidates: List<Triggerable>): List<Triggerable> {
        val frames = expectations.activeFrames.map { it.frame }.toSet()
        // filter out the dontcare candidate if no dontcare is expected.
        val results = mutableListOf<Triggerable>()
        for (doc in pcandidates) {
            if (doc.owner in IStateTracker.IStatusSet) {
                if (doc.owner in frames) results.add(doc)
            } else {
                results.add(doc)
            }
        }
        return results
    }
}

data class ChainedExampledLabelsTransformer(val transformers: List<ContextedExemplarsTransformer>) : ContextedExemplarsTransformer {
    constructor(vararg transformers: ContextedExemplarsTransformer): this(transformers.toList())

    override fun invoke(origin: List<Triggerable>): List<Triggerable> {
        var current = origin
        for( transform in transformers) {
            current = transform(current)
        }
        return current
    }
}

inline fun <E> Iterable<E>.indexesOf(predicate: (E) -> Boolean)
    = mapIndexedNotNull{ index, elem -> index.takeIf{ predicate(elem) } }