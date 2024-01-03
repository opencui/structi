package io.opencui.du

import io.opencui.core.*
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import kotlinx.coroutines.async
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.math.max
import kotlin.math.min


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


/**
 * BertStateTracker assumes the underlying nlu module is bert based.
 */
data class BertStateTracker(
    override val agentMeta: DUMeta,
    val intentK: Int = 32,
    val slotValueK: Int = 3,
    val intentSureThreshold: Float = 0.5f,
    val intentPossibleThreshold: Float = 0.1f,
    val slotThreshold: Float = 0.5f,
    val expectedSlotBonus: Float = 1.6f,
    val prefixSuffixBonus: Float = 1.0f,
    val caseSensitivity: Boolean = false
) : LlmStateTracker {

    private val expressedSlotBonus: Float = 5.0f

    // If there are multi normalizer propose annotation on the same span, last one wins.
    override val normalizers = defaultRecognizers(agentMeta)
    val nluModel: NLUModel = TfRestBertNLUModel()
    private val searcher = ExpressionSearcher(agentMeta)

    override val lang = agentMeta.getLang().lowercase(Locale.getDefault())
    override val dontCareForPagedSelectable = DontCareForPagedSelectable()

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
    override fun convertImpl(
        session: UserSession,
        utterance: String,
        expectations: DialogExpectations
    ): List<FrameEvent> {

        val ducontext = buildDuContext(session, utterance, expectations)

        // TODO: support multiple intention in one utterance, abstractively.
        // Find best matched frame, assume one intention in one utterance.

        ducontext.candidates = recognizeFrame(ducontext)
        val candidates = ducontext.candidates

        // What happens if there are expectations.
        if (expectations.activeFrames.isNotEmpty()) {
            // TODO(sean): assuming single intent here.
            if (candidates?.size != 1
                || candidates[0].ownerFrame.startsWith("io.opencui.core")
                || expectations.isFrameCompatible(candidates[0].ownerFrame)) {
                val events = convertWithExpectation(ducontext)
                if (events != null) return events
            }
        }

        // Now, we have no dialog expectation. There are three different cases:
        // 1. found no candidates. If best candidate is null, return empty list.
        logger.debug("ducontext: $ducontext : ${ducontext.candidates}" )
        if (candidates.isNullOrEmpty()) {
            return listOf(buildFrameEvent(IStateTracker.FullIDonotKnow))
        }

        // 2. found more than one candidate. (currently we do not handle.)
        if (candidates.size > 1) {
            val components = ducontext.candidates!!.map {
                buildFrameEvent(it.ownerFrame).apply { query = utterance }
            }

            // This return the raw frame event, we need to figure out a way to parse it one more time.
            // We may need a new api for this, otherwise, we will waste some parsing of utterance.
            return listOf(buildFrameEvent("io.opencui.core.IntentClarification", listOf(), components))
        }

        // 3. found just one candidate. Now we have one best candidate.
        val bestCandidate = candidates[0]
        ducontext.bestCandidate = bestCandidate
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

    fun isPartialMatch(event: EntityEvent): Boolean {
        return ListRecognizer.isPartialMatch(event.value)
    }


    /**
     * There are four different things we can do to improve the implementation here.
     * 1. implement the intent clarification so that we can resolve the ambiguity.
     * 2. make use of the expressions of the same intent using voting.
     * 3. make use of negative expression for hotfix.
     * 4. use retrieval score if needed.
     *
     * The goal of this function is to recognize what frames user meant, and then the best matching
     * expressions.
     *
     */
    // For now, we assume single intent input, and we need a model before this
    // to cut multiple intent input into multiple single intent ones.
    private fun recognizeFrame(ducontext: DuContext): List<ScoredDocument>? {
        // recognize entities in utterance
        val emap = ducontext.entityTypeToSpanInfoMap
        val utterance = ducontext.utterance
        val expectations = ducontext.expectations

        val pcandidates = searcher.search(utterance, expectations, emap)
        if (pcandidates.isEmpty()) {
            logger.debug("find no expression match for $utterance")
            return null
        }

        val candidates0 = dontCareFilter(pcandidates, expectations)
        val candidates = statusFilter(candidates0, expectations)

        // First, try to exact match expressions
        val matcher = NestedMatcher(ducontext)
        candidates.map{ matcher.markMatch(it) }
        val exactMatches = candidates.filter {it.exactMatch}
        exactMatches.map{ it.score += 2.0f }
        if (exactMatches.isNotEmpty()) {
            return pickDocViaFrames(exactMatches)
        }

        // If we have potential exact match, we use that as well.
        val possibleExactMatches = candidates.filter { it.possibleExactMatch }
        if (possibleExactMatches.isNotEmpty()) {
            return pickDocViaFrames(possibleExactMatches)
        }

        // now get the model score the similarity between each candidate and user utterance.
        // TODO: add resolve to simplify the model matching, ideally do matching in two different steps.
        // one with replacement, one without replacement, like what we do right now.
        val rcandidates = SlotTypeResolver.resolve(ducontext, candidates)
        val probes = rcandidates.map { it.probes(agentMeta) }
        logger.debug("intent model, utterance: $utterance, probes: $probes")
        val intentResults = nluModel.predictIntent(lang, utterance, probes)

        if (intentResults == null) {
            logger.info("Did not get any return from NLU server => do not understand.")
            return null
        }

        logger.info(intentResults.toString())
        for (i in 0 until intentResults.size) {
            candidates[i].score = intentResults[i].prob
            if (!ducontext.containsAllEntityNeeded(candidates[i].slotTypes, agentMeta)) {
                candidates[i].score = 0f
                candidates[i].exactMatch = false
            }
            logger.debug("[recognizeFrame] Candidate: ${probes[i]}, yProb: ${intentResults[i].prob} after: ${candidates[i].score}")
        }

        // now find the intent best explain the utterance
        // First we check whether we know enough about
        val goodCandidatesSize = candidates.filter { it.score > intentSureThreshold }.size
        if (goodCandidatesSize <= 0) {
            logger.debug("Got no match for ${utterance}.")
            return null
        }

        // TODO: another choice is to return here and ask user to choose one interpretation.
        if (goodCandidatesSize > 1) {
            logger.debug("StateTracker.convert there is too many good matches for ${utterance}.")
        }

        // now we pick the best one to operate on.
        val iIndex = candidates.withIndex().maxByOrNull { it.value.score }!!.index
        val bestCandidate = candidates[iIndex]
        logger.debug("[recognizeFrame convert] best candidate: ${bestCandidate.typedExpression}")

        // We might need to consider return multiple possibilities if there is no exact match.
        return pickDocViaFrames(candidates)
    }

    private fun pickDocViaFrames(candidates: List<ScoredDocument>): List<ScoredDocument> {
        // Return the best match for each frame.
        val frames : Map<String, List<ScoredDocument>> = candidates.groupBy { it.ownerFrame }
        return frames.values
            .map { it.sortedByDescending{it.score}[0] }
            .filter { it.score > intentSureThreshold }
            .sortedByDescending { it.score }
    }

    private fun dontCareFilter(
        pcandidates: List<ScoredDocument>,
        expectations: DialogExpectations
    ): List<ScoredDocument> {
        // filter out the dontcare candidate if no dontcare is expected.
        val results = mutableListOf<ScoredDocument>()
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

    private fun statusFilter(
        pcandidates: List<ScoredDocument>,
        expectations: DialogExpectations
    ): List<ScoredDocument> {
        val frames = expectations.activeFrames.map { it.frame }.toSet()
        // filter out the dontcare candidate if no dontcare is expected.
        val results = mutableListOf<ScoredDocument>()
        for (doc in pcandidates) {
            if (doc.ownerFrame in IStateTracker.IStatusSet) {
                if (doc.ownerFrame in frames) results.add(doc)
            } else {
                results.add(doc)
            }
        }
        return results
    }


    private fun notBeginning(segments: List<String>, index: Int): Boolean {
        return index < segments.size && segments[index].startsWith("##")
    }

    private fun notEnding(segments: List<String>, index: Int): Boolean {
        return index + 1 < segments.size && segments[index + 1].startsWith("##")
    }

    /**
     * Required slot names can be in the form of a.b.c, which will not be in getSlotMeta. So we need
     * to handle these. For now, we assume all the top level slots are also required. This assumption
     * can be changed down the road.
     */
    private fun getSlotMetas(
        frame: String,
        required: List<String> = emptyList()
    ): Map<String, DUSlotMeta> {
        // Including all the top level slots.
        val slotsMetaMap = agentMeta.getSlotMetas(frame).map { it.label to it }.toMap().toMutableMap()

        // We will test the nested slot at the top level, note that only handles the ones has head.
        for (slot in slotsMetaMap.keys.toList()) {
            val slotMeta = agentMeta.getSlotMeta(frame, slot)
            if (slotMeta != null) {
                val nestedMetas = agentMeta.getSlotMetas(slotMeta.type!!)
                for (nestedSlotMeta in nestedMetas) {
                    if (nestedSlotMeta.isHead) {
                        // What we need here to create the SlotMeta, based on the Outside meta,
                        slotsMetaMap["$slot.${nestedSlotMeta.label}"] = nestedSlotMeta
                    }
                }
            }
        }
        
        // include all the required slot matas.
        for (slot in required) {
            slotsMetaMap[slot]?.isMentioned = true
        }
        return slotsMetaMap
    }

    // When there is expectation presented.
    override fun convertWithExpectation(ducontext: DuContext): List<FrameEvent>? {
        val expectations = ducontext.expectations
        logger.debug(
            "${ducontext.bestCandidate} enter convertWithExpection ${expectations.isFrameCompatible(IStateTracker.ConfirmationStatus)} and ${
                ducontext.matchedIn(
                    IStateTracker.FullConfirmationList
                )
            }"
        )

        // what happens we have good match, and these matches are related to expectations.
        // There are at least couple different use cases.
        // TODO(sean): should we start to pay attention to the order of the dialog expectation.
        // Also the stack structure of dialog expectation is not used.
        // a. confirm Yes/No
        if (expectations.isFrameCompatible(IStateTracker.ConfirmationStatus)) {
            val events = handleExpectedBoolean(ducontext, IStateTracker.FullConfirmationList)
            if (events != null) return events
        }

        // b. boolgate Yes/No
        if (expectations.isFrameCompatible(IStateTracker.BoolGateStatus)) {
            val events = handleExpectedBoolean(ducontext, IStateTracker.FullBoolGateList)
            if (events != null) return events
        }

        // c. hasMore Yes/No
        if (expectations.isFrameCompatible(IStateTracker.HasMoreStatus)) {
            val events = handleExpectedBoolean(ducontext, IStateTracker.FullHasMoreList)
            if (events != null) return events
        }

        // d. match Dontcare expression abstractively
        if (ducontext.bestCandidate?.ownerFrame == IStateTracker.FullDontCare && expectations.hasExpectation()) {
            logger.debug("enter dontcare check.")
            // There are two cases where we have DontCare:
            // the best candidate has no context or its context matches expectations
            val bestCandidate = ducontext.bestCandidate!!
            // we need to go through all the expectation
            for (expected in ducontext.expectations.activeFrames) {
                if (!expected.allowDontCare()) continue
                if (bestCandidate.contextFrame == null || bestCandidate.contextFrame == expected.frame) {
                    val slotType = agentMeta.getSlotType(expected.frame, expected.slot!!)
                    // TODO: handle the frame slot case.
                    if (agentMeta.isEntity(slotType)) {
                        return listOf(
                            buildFrameEvent(
                                expected.frame,
                                listOf(EntityEvent("\"_DontCare\"", expected.slot))
                            )
                        )
                    }
                }
            }
        }

        // Now we need to figure out what happens for slotupdate.
        if (ducontext.bestCandidate?.ownerFrame == IStateTracker.SlotUpdate && expectations.hasExpectation()) {
            logger.debug("enter slot update.")
            // We need to figure out which slot user are interested in first.
            val slotTypeSpanInfo = ducontext.entityTypeToSpanInfoMap[IStateTracker.SlotType]
            // Make sure there are slot type entity matches.
            if (slotTypeSpanInfo != null) {
                // We assume the expectation is stack, with most recent frames in the end
                for (activeFrame in ducontext.expectations.activeFrames) {
                    val matchedSlotList = slotTypeSpanInfo.filter { isSlotMatched(it, activeFrame.frame) }
                    if (matchedSlotList.isEmpty()) {
                        continue
                    }

                    // Dedup first.
                    val matchedSlots = matchedSlotList.groupBy { it.value.toString() }
                    if (matchedSlots.size > 1) {
                        throw RuntimeException("Can not mapping two different slot yet")
                    }

                    // check if the current frame has the slot we cared about and go with that.
                    val spanInfo = matchedSlotList[0]
                    val partsInQualified = spanInfo.value.toString().split(".")
                    val slotName = partsInQualified.last()
                    val slotsInActiveFrame = agentMeta.getSlotMetas(activeFrame.frame)

                    val targetEntitySlot = slotsInActiveFrame.find { it.label == slotName }
                    if (targetEntitySlot != null) {
                        return fillSlotUpdate(ducontext, targetEntitySlot)
                    } else {
                        // This find the headed frame slot.
                        val targetFrameType = partsInQualified.subList(0, partsInQualified.size - 1).joinToString(separator = ".")
                        val targetEntitySlot = agentMeta.getSlotMetas(targetFrameType).find { it.label == slotName }!!
                        return fillSlotUpdate(ducontext, targetEntitySlot)
                    }
                }
            } else {
                // TODO: now we need to handle the case for: change to tomorrow
                // For now we assume there is only one generic type.
                val bestCandidate = ducontext.bestCandidate!!
                val targetSlot = bestCandidate.guessedSlot!!
                return fillSlotUpdate(ducontext, targetSlot)
            }
        }

        // if there is no good match, we need to just find it using slot model.
        val extractedEvents0 = fillSlots(ducontext, expectations.expected!!.frame, expectations.expected.slot)
        if (extractedEvents0.isNotEmpty()) {
            return extractedEvents0
        }

        // try to fill slot for active frames
        for (activeFrame in expectations.activeFrames) {
            val extractedEvents = fillSlots(ducontext, activeFrame.frame, activeFrame.slot)
            logger.info("for ${activeFrame} getting event: ${extractedEvents}")
            if (extractedEvents.isNotEmpty()) {
                return extractedEvents
            }
        }

        // TODO: when we have better intent model, we can move this the end of the convert.
        if (expectations.expected.slot != null) {
            // First step, handle the basic string case.
            val frame = expectations.expected.frame
            val slot = expectations.expected.slot
            if (agentMeta.getSlotType(frame, slot).equals("kotlin.String")) {
                return listOf(
                    buildFrameEvent(
                        expectations.expected.frame,
                        listOf(EntityEvent(ducontext.utterance, slot))
                    )
                )
            }
        }
        return null
    }

    private fun isSlotMatched(spanInfo: SpanInfo, activeFrame: String): Boolean {
        val spanTargetSlot = spanInfo.value.toString()
        val parts = spanTargetSlot.split(".")
        val spanTargetFrame = parts.subList(0, parts.size - 1).joinToString(separator = ".")
        val slotName = parts.last()
        val slotMeta = agentMeta.getSlotMeta(spanTargetFrame, slotName)!!
        if (spanTargetSlot.startsWith(activeFrame) && agentMeta.isEntity(slotMeta.type!!)) return true

        val spanTargetFrameHasHead = agentMeta.getSlotMetas(spanTargetFrame).any { it.isHead }
        // now we need to figure out whether active Frame as a frame slot of this time.
        val matchedFrameSlots = agentMeta.getSlotMetas(activeFrame).filter {it.type == spanTargetFrame}
        return spanTargetFrameHasHead && matchedFrameSlots.size == 1
    }


    // This need to called if status is expected.
    private fun handleExpectedBoolean(ducontext: DuContext, valueChoices: List<String>): List<FrameEvent>? {
        if (ducontext.matchedIn(valueChoices)) {
            return listOf(buildFrameEvent(ducontext.bestCandidate?.label!!))
        }

        // if we have extractive match.
        val boolValue = ducontext.getEntityValue(IStateTracker.KotlinBoolean)
        if (boolValue != null) {
            val frameName = when (boolValue) {
                "true" -> valueChoices[0]
                "false" -> valueChoices[1]
                else -> null
            }
            if (frameName != null) return listOf(buildFrameEvent(frameName))
        }
        return null
    }

    /**
     * fillSlots is used to create entity event.
     */
    private fun fillSlots(
        ducontext: DuContext,
        topLevelFrameType: String,
        focusedSlot: String?
    ): List<FrameEvent> {
        val qualifiedSlotNamesInExpr = ducontext.bestCandidate?.slotNames() ?: emptyList()
        // we need to make sure we include slots mentioned in the intent expression
        val slotMap = getSlotMetas(topLevelFrameType, qualifiedSlotNamesInExpr).filter { it.value.triggers.isNotEmpty() }
        return fillSlots(slotMap, ducontext, topLevelFrameType, focusedSlot)
    }

    private fun fillSlots(
        slotMap: Map<String, DUSlotMeta>,
        ducontext: DuContext,
        topLevelFrameType: String,
        focusedSlot: String?
    ): List<FrameEvent> {
        // we need to make sure we include slots mentioned in the intent expression
        val utterance = ducontext.utterance
        // Switch to just first slot name, triggers is not a good name, unfortunately, but.
        val slotProbes = slotMap.values.map { it.triggers[0] }.toList()
        logger.info("slot model, utterance: $utterance, probes: $slotProbes, frame: $topLevelFrameType, slots: $focusedSlot")

        var spredict: Deferred<UnifiedModelResult>? = null
        // skip slot model when utterance is one token, we should use a better check based on reconginizer
        // since slot model is assumed to extract slots with hints of context
        // TODO(sean): we should use a smarter check in case the entity itself is longer than one token.
        if (utterance.splitToSequence(' ').toList().size > 1) {
            spredict = GlobalScope.async { nluModel.predictSlot(lang, utterance, slotProbes) }
        }

        val result = extractEntityEvents(ducontext, topLevelFrameType, slotMap, focusedSlot, spredict).toMutableList()
        if (result.isEmpty()) return result
        return if (result.find {
                topLevelFrameType.startsWith(it.packageName!!) && topLevelFrameType.endsWith(it.type)
                        || it.packageName == "io.opencui.core"} != null) {
            result
        } else {
            // Make sure that we have at least one topLevelFrameType
            listOf(buildFrameEvent(topLevelFrameType)) + result
        }
    }


    private fun fillSlotUpdate(ducontext: DuContext, targetSlot: DUSlotMeta): List<FrameEvent> {
        // we need to make sure we include slots mentioned in the intent expression
        val utterance = ducontext.utterance
        val slotMapBef = getSlotMetas(IStateTracker.SlotUpdate)
        val slotMapTransformed = mutableMapOf<String, DUSlotMeta>()

        // we need to rewrite the slot map to replace all the T into actual slot type.
        for ((key, slotMeta) in slotMapBef) {
            // We can not fill slot without triggers.
            if (slotMeta.isGenericTyped()) {
                // NOTE: assume the pattern for generated type is <T>
                val targetTrigger = targetSlot.triggers[0]
                val newTriggers = slotMeta.triggers.map { it.replace(IStateTracker.SlotUpdateOriginalSlot, targetTrigger)}
                slotMapTransformed[key] = slotMeta.typeReplaced(targetSlot.type!!, newTriggers)
            } else {
                slotMapTransformed[key] = slotMeta
            }
        }

        val slotMapAft = slotMapTransformed.filter { it.value.triggers.isNotEmpty() }

        // Switch to just first slot name, triggers is not a good name, unfortunately, but.
        val slotProbes = slotMapAft.values.map { it.triggers[0] }.toList()
        logger.info("slot model, utterance: $utterance, probes: $slotProbes, ${targetSlot.type}")

        var spredict: Deferred<UnifiedModelResult>? = null
        // skip slot model when utterance is one token, we should use a better check based on reconginizer
        // since slot model is assumed to extract slots with hints of context
        // TODO(sean): we should use a smarter check in case the entity itself is longer than one token.
        if (utterance.splitToSequence(' ').toList().size > 1) {
            spredict = GlobalScope.async { nluModel.predictSlot(lang, utterance, slotProbes) }
        }

        return extractEntityEvents(ducontext, IStateTracker.SlotUpdate, slotMapAft, null, spredict).toMutableList()
    }


    /**
     * Given the expected frame/slot, also required slots, and also slot map result,
     * we extract the frame event rooted for frame type.
     */
    private fun extractEntityEvents(
        ducontext: DuContext,
        frameType: String,
        requiredSlotMap: Map<String, DUSlotMeta>,
        expectedSlot: String?,
        dpredict: Deferred<UnifiedModelResult>?
    ): List<FrameEvent> = runBlocking {

        // this map is from type to annotation.
        val utterance = ducontext.utterance

        val result: UnifiedModelResult = dpredict?.await()
            ?: UnifiedModelResult(
                listOf(utterance),
                (1..(3 * requiredSlotMap.size)).map { 0f },
                (1..(requiredSlotMap.size)).map { listOf(0f) },
                (1..(requiredSlotMap.size)).map { listOf(0f) },
                listOf(),
                listOf()
            )

        val eventMap = extractSlotValues(ducontext, expectedSlot, requiredSlotMap, result)

        // This stitch together the nested frame event, in no nested fashion for now.
        val res = ArrayList<FrameEvent>()
        for (key in eventMap.keys) {
            var type: String
            if (key == "") {
                type = frameType
                res.add(buildFrameEvent(type, eventMap[key]!!.toList()))
            } else {
                // We do not know how to deal with the nested structure yet.
                // For now, just create the frame for the innermost frame
                // and ignore the
                val parts = key.split(".")
                type = run {
                    var type1 = frameType
                    for (simpleName in parts) {
                        type1 = agentMeta.getSlotType(type1, simpleName)
                    }
                    type1
                }
                res.add(buildFrameEvent(type, eventMap[key]!!.toList()))
            }
        }
        logger.info("res: $res")
        return@runBlocking res
    }

    fun extractSlotValues(
        ducontext: DuContext,
        expectedSlot: String?,
        slotMap: Map<String, DUSlotMeta>,
        result: UnifiedModelResult
    ): Map<String, List<EntityEvent>> {
        logger.info("extractSlotValues: class logits: $result.class_logits")
        // for now: for each slot we check whether its class_logits high enough,
        // if it is, we find the span for it. we assume all top level slots are required.
        val emap = ducontext.entityTypeToSpanInfoMap

        val slots = slotMap.keys.toList()
        // First let's add support for frame slot in entirety.
        val eventMap = mutableMapOf<String, MutableList<EntityEvent>>()
        val candidateSpans = mutableListOf<ScoredSpan>()
        for ((index, slot) in slots.withIndex()) {
            val nameParts = slot.split(".")
            val path = nameParts.subList(0, nameParts.size - 1).joinToString(".")
            val slotType = (slotMap[slot] ?: error("slot doesn't exist!")).type
            if (index >= result.startLogitss.size) throw RuntimeException("the probes size and return size does not match")

            // (TODO: sean): This implies that we always have DONTCARE at the first entry? Strange.
            // Also, this is not the right way to get multi value to work.
            val recognizedDontCare = emap.containsKey(slotType) && emap[slotType]!![0].value.toString() == DONTCARE
            if (result.classLogits[index * 3 + 1] > slotThreshold || emap.containsKey(slotType) && !recognizedDontCare) {
                // Find all the occurrences of candidates for this slot.
                val slotCandidates = extractValue(ducontext, slotMap[slot]!!, result.get(index), emap[slotType])
                if (slotCandidates != null) {
                    if (slot == expectedSlot) slotCandidates.apply { forEach { it.score += expectedSlotBonus } }
                    slotCandidates.apply { forEach { it.attribute = slot } }
                    candidateSpans.addAll(slotCandidates)
                }
            } else if (result.classLogits[index * 3 + 2] > slotThreshold || recognizedDontCare) {
                // DontCare. Right now, we do not really have the training data for this.
                if (agentMeta.isEntity(slotType!!)) {
                    eventMap.put(path, EntityEvent("", slot))
                } else {
                    eventMap.put(path, EntityEvent("{}", slot))
                }
            }
        }

        val spans = resolveSpanOverlap(candidateSpans, slotMap)

        // closure for add virtual
        fun addVirtual(typeName: String): String {
            val tokens = typeName.split(".").toMutableList()
            val index = tokens.size - 1
            tokens[index] = "Virtual${tokens[index]}"
            return tokens.joinToString(".")
        }

        for (span in spans) {
            val nameParts = span.attribute!!.split(".")
            val path = nameParts.subList(0, nameParts.size - 1).joinToString(".")
            val lastPart = nameParts[nameParts.size - 1]
            val entityLabel = span.norm!!
            logger.debug("handle entity with label = $entityLabel")
            val event = if (!span.leaf) {
                // TODO(sean): this is virtual node
                EntityEvent(entityLabel, lastPart).apply {
                    origValue = span.value
                    type = addVirtual(span.type!!)
                    isLeaf = false
                }
            } else {
                EntityEvent(entityLabel, lastPart).apply {
                    origValue = span.value
                    type = span.type
                }
            }
            // We need to have some form of explain away, if the entity occurs in the expression
            // There might be some ambiguity here.
            val matched = ducontext.utterance.substring(span.start, span.end)
            logger.debug("got matched: $matched from ${ducontext.bestCandidate?.typedExpression} with $event")
            if (ducontext.bestCandidate == null || ducontext.bestCandidate!!.typedExpression.indexOf(matched) == -1) {
                logger.debug("span is: $span. putting $event with ${event.type}")
                eventMap.put(path, event)
            }
        }
        return eventMap
    }

    // remove overlapped spans with lower score
    private fun resolveSpanOverlap(
        spans: MutableList<ScoredSpan>,
        slotMap: Map<String, DUSlotMeta>
    ): ArrayList<ScoredSpan> {
        val goodSpans = ArrayList<ScoredSpan>()
        spans.sortByDescending { it.score }
        for (span in spans) {
            // no overlapped ranges
            if (goodSpans.find {
                    max(it.start, span.start) < min(it.end, span.end)
                            || (it.attribute == span.attribute && slotMap[it.attribute]!!.isMultiValue == false)
                } == null) {
                goodSpans.add(span)
            }
        }
        return goodSpans
    }

    /**
     * TODO: we detect span separately for each span. But it will be useful if we do it globally.
     * We assume the probability on ## token can be neglected, we can ensure this at early stage of pipeline.
     */
    data class ScoredSpan(
        val start: Int,
        val end: Int,
        var score: Float = 0.0f,
        var value: String? = null,
        var norm: String? = null,
        var type: String? = null,
        var attribute: String? = null,
        var traceInfo: HashMap<String, String>? = null,
        var recongizedEntity: Boolean = false,
        var recongizedSlot: Boolean = false,
        var leaf: Boolean = true
    )

    fun extractValue(
        duContext: DuContext,
        slotMeta: DUSlotMeta,
        prediction: SlotPrediction,
        entities: List<SpanInfo>? = null
    ): List<ScoredSpan>? {
        logger.info("handle ${duContext.utterance} for $slotMeta. with ${slotMeta.isMentioned}")
        val startIndexes = top(slotValueK, prediction.startLogits)
        val endIndexes = top(slotValueK, prediction.endLogits)

        // pay attention that IntRange accepts endInclusive as a parameter by convention
        val spans = mutableMapOf<IntRange, ScoredSpan>()
        if (prediction.classLogit > slotThreshold) {
            for (start in startIndexes) {
                for (end in endIndexes) {
                    if (notBeginning(prediction.segments, start.index)) continue
                    if (notEnding(prediction.segments, end.index)) continue
                    if (start.index > end.index) continue
                    // (TODO: make sure we cut things right on the tf side.
                    if (end.index >= prediction.startLogits.size) continue
                    val ch_start = prediction.segStarts[start.index].toInt()
                    val ch_end = prediction.segEnds[end.index].toInt()
                    spans[IntRange(ch_start, ch_end - 1)] =
                        ScoredSpan(ch_start, ch_end, start.value + end.value).apply {
                            traceInfo = hashMapOf(
                                "class_logit" to "$prediction.classLogit",
                                "start_logit" to start.value.toString(),
                                "end_logit" to end.value.toString()
                            )
                        }
                }
            }
        }

        // Now use extractive entity information.
        if (entities != null) {
            for (entity in entities) {
                val span = IntRange(entity.start, entity.end - 1)
                val bonus = duContext.getSurroundingWordsBonus(slotMeta, entity)
                if (spans.containsKey(span)) {
                    spans[span]!!.score += entity.score + bonus
                    spans[span]!!.norm = entity.norm()
                    spans[span]!!.type = entity.type
                    spans[span]!!.traceInfo!!["recognizer_score"] = entity.score.toString()
                    spans[span]!!.traceInfo!!["prefix_suffix_bonus"] = bonus.toString()
                    spans[span]!!.recongizedEntity = true
                    spans[span]!!.leaf = entity.leaf
                } else {
                    // let's add this as well.
                    spans[span] = ScoredSpan(
                        entity.start,
                        entity.end,
                        entity.score + bonus,
                        "",
                        entity.norm(),
                        entity.type
                    ).apply {
                        traceInfo = hashMapOf(
                            "recognizer_score" to entity.score.toString(),
                            "prefix_suffix_bonus" to bonus.toString()
                        )
                        recongizedEntity = true
                        leaf = entity.leaf
                    }
                }

                // regardless we add the expressed bonus.
                if (slotMeta.isMentioned) {
                    spans[span]!!.score += expressedSlotBonus
                    spans[span]!!.traceInfo!!["expressed_slot_bonus"] = expressedSlotBonus.toString()
                }

                // Not adding score for partial match.
                if (ListRecognizer.isPartialMatch(spans[span]?.value)) {
                    spans[span]!!.score -= bonus
                }
            }
        }

        return if (spans.isEmpty()) {
            null
        } else {
            // topK score > threshold spans
            var ret = spans.values.toList().filter { it.score > slotThreshold }
            ret = ret.sortedByDescending { it.score }.subList(0, min(ret.size, slotValueK))
            // fill value
            ret.apply {
                forEach {
                    it.value = duContext.utterance.substring(it.start, it.end)
                    if (it.norm.isNullOrEmpty()) it.norm = it.value
                }
            }
            return ret
        }
    }

    // given a list of frame event, add the entailed slots to the right frame event.
    private fun addEntailedSlot(bestCandidate: ScoredDocument?, frameEvents: List<FrameEvent>): List<FrameEvent> {
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


    override fun recycle() {
        nluModel.shutdown()
    }

    companion object : ExtensionBuilder<IStateTracker> {
        val logger = LoggerFactory.getLogger(BertStateTracker::class.java)
        // TODO(sean): make sure entity side return this as label for DONTCARE
        const val DONTCARE = "DontCare"
        override fun invoke(p1: Configuration): IStateTracker {
            TODO("Not yet implemented")
        }
    }
}

