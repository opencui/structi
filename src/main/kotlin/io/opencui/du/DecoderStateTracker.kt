package io.opencui.du

import io.opencui.core.*
import org.slf4j.LoggerFactory

import io.opencui.core.RuntimeConfig
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import io.opencui.serialization.*
import java.util.*

enum class DugMode {
    SKILL,
    SLOT,
    BINARY,
    SEGMENT
}


// the result from YesNoInference
enum class YesNoResult {
    Affirmative,
    Negative,
    Indifferent,
    Irrelevant
}



fun YesNoResult.toJsonAsBoolean() : String? {
    return when (this) {
        YesNoResult.Affirmative -> Json.encodeToString(true)
        YesNoResult.Negative -> Json.encodeToString(false)
        YesNoResult.Irrelevant -> Json.encodeToString(IStateTracker.DontCareLabel)
        else -> null
    }
}

//
// This is used to bridge encoder and decoder solution
//
data class TriggerDecision(
    override val utterance: String,
    override var owner: String?,  // this could be empty and can be updated by exact match.
    val evidence: List<Exemplar>) : Triggerable {

    fun exactMatch(duContext: DuContext) : String? {
        // Prepare for exact match.
        evidence.map {
            it.typedExpression = it.typedExpression(duContext.duMeta!!)
        }

        val matcher = NestedMatcher(duContext)
        val candidates = evidence
        candidates.map { matcher.markMatch(it) }
        val exactMatches = candidates.find { it.exactMatch }
        return exactMatches?.ownerFrame
    }
}

/**
 * For RAG based solution, there are two different stage, build prompt, and then use model to score using
 * the generated prompt. It is possible that we have two different service, one for prompt (which is agent
 * dependent), and one for low level NLU, which can be shared by multiple agents.
 *
 * The encoder model works the same way, except the retrieval is done in the Kotlin side.
 */

// Most likely, the agent dependent nlu and fine-tuned decoder are co-located, so it is better to
// hide that from user.

data class RestNluService(val url: String) {
    val client: HttpClient = HttpClient.newHttpClient()

    fun shutdown() { }

    companion object {
        val logger = LoggerFactory.getLogger(RestNluService::class.java)
    }

    data class Context(
        val bot: String
    )

    data class Request(
        val mode: DugMode,
        val utterance: String,
        val expectations: List<ExpectedFrame> = emptyList(),
        val slots: List<Map<String, String>> = emptyList(),
        val candidates: Map<String, List<String>> = emptyMap(),
        val questions: List<String> = emptyList(),
        val dialogActs: List<String> = emptyList())

    fun buildRequest(ctxt: Context, text: String, timeoutMillis: Long = 1000L): HttpRequest {
        return HttpRequest.newBuilder()
            .uri(URI.create("$url/v1/predict/${ctxt.bot}"))
            .header("Content-type", "application/json")
            .header("Accept", "application/json")
            .version(HttpClient.Version.HTTP_1_1)
            .POST(HttpRequest.BodyPublishers.ofString(text))
            .build()
    }


    // This returns skills (skills requires attention automatically even not immediately but one by one, not frames)
    fun detectTriggerables(
        ctxt: Context,
        utterance: String,
        expectations: List<ExpectedFrame> = emptyList()): List<TriggerDecision> {

        val input = Request(DugMode.SKILL, utterance, expectations)
        logger.debug("connecting to $url/v1/predict/${ctxt.bot}")
        logger.debug("utterance = $utterance and expectations = $expectations")
        val jsonRequest = Json.encodeToString(input).trimIndent()
        val request: HttpRequest = buildRequest(ctxt, jsonRequest)

        val response: HttpResponse<String> = client.send(request, HttpResponse.BodyHandlers.ofString())
        val body = response.body()
        if (response.statusCode() != 200) {
            logger.error("NLU request error: ${response.toString()}")
            return emptyList()
        }
        return Json.decodeFromString<List<TriggerDecision>>(body)
    }

    // handle all slots.
    fun fillSlots(
        ctxt: Context,
        utterance: String,
        slots: List<Map<String, String>>,
        valueCandidates: Map<String, List<String>>): Map<String, SlotValue> {
        val input = Request(DugMode.SLOT, utterance, slots = slots, candidates =  valueCandidates)
        logger.debug("connecting to $url/v1/predict/${ctxt.bot}")
        logger.debug("utterance = $utterance and expectations = $slots, entities = $valueCandidates")
        val request: HttpRequest = buildRequest(ctxt, Json.encodeToString(input))

        val response: HttpResponse<String> = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            logger.error("NLU request error: ${response.toString()}")
            return emptyMap()
        }

        return Json.decodeFromString<Map<String, SlotValue>>(response.body())
    }

    fun yesNoInference(ctxt: Context, utterance: String, questions: List<String>): List<YesNoResult> {
        val input = Request(DugMode.BINARY, utterance, questions = questions)
        logger.debug("connecting to $url/v1/predict")
        logger.debug("utterance = $utterance and questions = $questions")
        val request: HttpRequest = buildRequest(ctxt, Json.encodeToString(input))

        val response: HttpResponse<String> = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            logger.error("NLU request error: ${response.toString()}")
            return emptyList()
        }
        return Json.decodeFromString<List<YesNoResult>>(response.body())
    }
}


/**
 * DecoderStateTracker assumes the underlying nlu module has decoder.
 */
data class DecoderStateTracker(val duMeta: DUMeta, val forced_tag: String? = null) : IStateTracker {
    // If there are multi normalizer propose annotation on the same span, last one wins.
    val normalizers = defaultRecognizers(duMeta)

    // We can turn this on if we need to.
    val alwaysExactMatch = false
    val nluService = RestNluService(RuntimeConfig.get(RestNluService::class)?: "http://127.0.0.1:3001")

    val context : RestNluService.Context by lazy {
        val tag = if (forced_tag.isNullOrEmpty()) {
            "${duMeta.getOrg()}_${duMeta.getLabel()}_${duMeta.getLang()}_${duMeta.getBranch()}"
        } else {
            forced_tag
        }
        RestNluService.Context(tag)
    }

    val dontCareForPagedSelectable = DontCareForPagedSelectable()

    val useSlotLabel = true

    // Eventually we should use this new paradigm.
    // First, we detect triggereables this should imply skill understanding.
    // then we first handle the expectation
    // then we fill the slot.

    // For now, we assume single intent input, and we need a model before this
    // to cut multiple intent input into multiple single intent ones.

    fun buildPostProcessor(expectations: DialogExpectations): FrameEventProcessor {
        // this build the post processors
        return ChainedFrameEventProcesser(
            dontCareForPagedSelectable,        // The first is to resolve the don't care for pagedselectable.
            ComponentSkillConverter(duMeta, expectations)
        )
    }

    override fun convert(session: UserSession, putterance: String, expectations: DialogExpectations): List<FrameEvent> {
        // if it is empty, we can return immediately.
        if (putterance.trim().isEmpty()) {
            return emptyList()
        }
        // TODO(sean), eventually need to getLocale from user session, right now doing so break test.
        val utterance = putterance.lowercase(Locale.getDefault()).trim { it.isWhitespace() }

        // this layer is important so that we have a centralized place for the post process.
        val res = convertImpl(session, utterance, expectations)

        // get the post process done
        val postProcess = buildPostProcessor(expectations)
        return res.map { postProcess(it) }
    }

    fun buildDuContext(session: UserSession, utterance: String, expectations: DialogExpectations): DuContext {
        val ducontext = DuContext(session.userIdentifier.toString(), utterance, expectations, duMeta)
        var allNormalizers = normalizers.toMutableList()
        // Session and turn based recognizers
        if (session.sessionRecognizer != null) allNormalizers += session.sessionRecognizer!!
        if (session.turnRecognizer != null) allNormalizers += session.turnRecognizer!!

        allNormalizers.recognizeAll(
            utterance,
            ducontext.expectedEntityType(expectations),
            ducontext.entityTypeToValueInfoMap
        )

        ducontext.cleanUp(expectations)

        ducontext.updateTokens(LanguageAnalyzer.get(duMeta.getLang(), stop = false)!!.tokenize(utterance))
        return ducontext
    }

    // This layer is handling the
    fun convertImpl(session: UserSession, putterance: String, expectations: DialogExpectations): List<FrameEvent> {
        val triggerables = detectTriggerables(putterance, expectations)
        logger.debug("getting $triggerables for utterance: $putterance expectations $expectations")

        // need to be handled under context.
        val needToHandle = triggerables.filter { it.owner == null || isSystemFrame(it.owner!!) }

        // TODO: we might need to use exact match to make the hotfix work later.
        // We only goes into this when we have expectation, but the truth is we always have expectation.
        val results = mutableListOf<FrameEvent>()
        for (triggerable in needToHandle) {
            val utterance = triggerable.utterance

            // We always handle expectations first.
            val duContext = buildDuContext(session, triggerable.utterance, expectations)

            val exactOwner = triggerable.exactMatch(duContext)

            if (exactOwner != triggerable.owner && exactOwner != null) {
                logger.debug("The exactOnwer is different from guessed owner.")
                triggerable.owner = exactOwner
            }

            // Test whether it is crud, if so we hand them separately
            if (isPickValue(triggerable.owner)) {
                val expectedFrames = duContext.expectedFrames.filter {it.slot != null }
                val events = handleExpectations(duContext, expectedFrames, triggerable)
                if (!events.isNullOrEmpty()) {
                    logger.debug("getting $events for $utterance in handleExpectations")
                    // This is an opportunity for filtering the events again.
                    // if event agrees with one of expectation, and
                    results.addAll(events)
                }
            } else if (isUpdateSlot(triggerable.owner)) {
                // now we handle slot update not working yet.
                handleSlotUpdate(duContext, triggerable)
            } else {
                // now we handle the no slot update cases.
                val events = handleExpectations(duContext, duContext.expectedFrames, triggerable)
                if (!events.isNullOrEmpty()) {
                    logger.debug("getting $events for $utterance in handleExpectations")
                    // This is an opportunity for filtering the events again.
                    // if event agrees with one of expectation, and
                    results.addAll(events)
                }
            }
        }

        // The rest should be payload triggerables.
        val leftToHandle = triggerables.filter { it.owner != null && !isSystemFrame(it.owner!!) }
        // If there are multiple triggerables, we need to handle them one by one.
        for (triggerable in leftToHandle) {
            val utterance = triggerable.utterance
            val duContext = buildDuContext(session, triggerable.utterance, expectations)

            logger.debug("handling $triggerables for utterance: $utterance")
            val events = fillSlots(duContext, triggerable.owner!!, null)
            logger.debug("getting $events for $triggerable")
            results.addAll(events)
        }

        return if (results.size != 0) {
            results
        } else {
            listOf(FrameEvent.build(IStateTracker.FullIDonotKnow))
        }
    }

    fun handleSlotUpdate(duContext: DuContext, triggerable: TriggerDecision) : List<FrameEvent>? {
         logger.debug("enter slot update.")
         // We need to figure out which slot user are interested in first.
         val slotTypeSpanInfo = duContext.entityTypeToValueInfoMap[IStateTracker.SlotType]
         // Make sure there are slot type entity matches.
         if (slotTypeSpanInfo != null) {
             // We assume the expectation is stack, with most recent frames in the end
             for (activeFrame in duContext.expectations.activeFrames) {
                 val matchedSlotList = slotTypeSpanInfo.filter { isSlotMatched(duMeta, it, activeFrame.frame) }
                 if (matchedSlotList.isNullOrEmpty()) {
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
                 val slotsInActiveFrame = duContext.duMeta!!.getSlotMetas(activeFrame.frame)

                 val targetEntitySlot = slotsInActiveFrame.find { it.label == slotName }
                 if (targetEntitySlot != null) {
                     return fillSlotUpdate(duContext, targetEntitySlot)
                 } else {
                     // This find the headed frame slot.
                     val targetFrameType =
                         partsInQualified.subList(0, partsInQualified.size - 1).joinToString(separator = ".")
                     val targetEntitySlot = duMeta.getSlotMetas(targetFrameType).find { it.label == slotName }!!
                     return fillSlotUpdate(duContext, targetEntitySlot)
                 }
             }
         } else {
             // TODO: now we need to handle the case for: change to tomorrow
             // For now we assume there is only one generic type.
             val bestCandidate = triggerable.evidence.find { it.guessedSlot != null}
             if (bestCandidate != null) {
                 return fillSlotUpdate(duContext, bestCandidate.guessedSlot!!)
             }
         }
        return emptyList()
    }

    private fun fillSlotUpdate(duContext: DuContext, targetSlot: DUSlotMeta): List<FrameEvent> {
        val topLevelFrameType = IStateTracker.SlotUpdate
        val slotMapAft = slotTransformBySlotUpdate(duContext, targetSlot)
        return fillSlots(duContext, slotMapAft, topLevelFrameType, null)
    }

    fun detectTriggerables(utterance: String, expectations: DialogExpectations): List<TriggerDecision> {
        // TODO(sean): how do we resolve the type for generic type?
        // We assume true/false or null here.
        val pcandidates = nluService.detectTriggerables(context, utterance, expectations.activeFrames)

        val candidates = ChainedExampledLabelsTransformer(
            StatusTransformer(expectations)
        ).invoke(pcandidates)

        // Do we really need this?
        // First, try to exact match expressions
        // now find the intent best explain the utterance
        // First we check whether we know enough about
        if (candidates.isEmpty()) {
            logger.debug("Got no match for ${utterance}.")
        }

        // TODO: another choice is to return here and ask user to choose one interpretation.
        if (candidates.size > 1) {
            logger.debug("StateTracker.convert there is too many good matches for ${utterance}.")
        }

        // We might need to consider return multiple possibilities if there is no exact match.
        return candidates.map {it as TriggerDecision }
    }


    // When there is expectation presented.
    // For each active expectation, we do the following:
    // 1. check what type the focused slot is,
    // 2. if it is boolean/IStatus, run Yes/No inference.
    // 3. run fillSlot for the target frame.
    private fun handleExpectations(duContext: DuContext, expectedFrames: List<ExpectedFrame>, triggerable: Triggerable): List<FrameEvent>? {
        val utterance = duContext.utterance

        // Ideally, we should pay attention to
        val results = mutableListOf<FrameEvent>()
        val lowResults = mutableListOf<FrameEvent>()
        for (expectedFrame in expectedFrames) {
            val frame = expectedFrame.frame
            val slot = expectedFrame.slot

            // We need to check whether we have a binary question. For now, we only handle the top
            // binary question. We assume the first on the top.
            if (expectedFrame.isBooleanSlot(duMeta)) {
                // The expectation top need to be binary and have prompt.
                val question = expectedFrame.prompt?.firstOrNull()?.templates?.pick() ?: ""

                // Hard binding to boolean value.
                val boolValue = duContext.getEntityValue(IStateTracker.KotlinBoolean)
                if (boolValue != null) {
                    val yesNoFlag = when (boolValue) {
                        "true" -> YesNoResult.Affirmative
                        "false" -> YesNoResult.Negative
                        "_DontCare" -> YesNoResult.Indifferent
                        else -> YesNoResult.Irrelevant
                    }
                    val events = handleBooleanStatus(duContext, yesNoFlag)
                    if (events != null) results.addAll(events)
                }

                val status = nluService.yesNoInference(context, utterance, listOf<String>(question))[0]
                if (status != YesNoResult.Irrelevant) {
                    // First handle the frame wrappers we had for boolean type.
                    // what happens we have good match, and these matches are related to expectations.
                    // There are at least couple different use cases.
                    // TODO(sean): should we start to pay attention to the order of the dialog expectation.
                    // Also the stack structure of dialog expectation is not used.
                    val events = handleBooleanStatus(duContext, status)
                    if (events != null) results.addAll(events)
                }
            } else if (slot != null && duMeta.getSlotType(frame, slot) == IStateTracker.KotlinString) {
                // Now w handle the string slot, this is really low priority.
                // only where owner == null
                if (triggerable.owner == null) {
                    lowResults.add(FrameEvent.build(frame, listOf(EntityEvent(duContext.utterance, slot))))
                }
            } else {
                // if there is no good match, we need to just find it using slot model.
                // try to fill slot for active frames, assuming the expected!! is the at the beginning.
                val extractedEvents = fillSlots(duContext, expectedFrame.frame, expectedFrame.slot)
                logger.info("for ${expectedFrame} getting event: ${extractedEvents}")
                if (extractedEvents.isNotEmpty()) {
                    results.addAll(extractedEvents)
                }
            }
        }

        // We now return the events.
        return if (!results.isNullOrEmpty()) {
            val hasPayload = results.filter { !it.packageName!!.startsWith("io.opencui.core")}.isNullOrEmpty()
            if (hasPayload) {
               results.removeIf{ it.packageName == IStateTracker.HasMore}
            }
            results
        } else if (lowResults.isNotEmpty()){
            lowResults
        } else {
            null
        }
    }

    // This need to called if status is expected.
    private fun handleBooleanStatusImpl(boolValue: YesNoResult, valueChoices: List<String>): List<FrameEvent>? {
        // if we have extractive match.
        val frameName = when (boolValue) {
            YesNoResult.Affirmative -> valueChoices[0]
            YesNoResult.Negative -> valueChoices[1]
            YesNoResult.Indifferent -> IStateTracker.FullDontCare
            else -> null
        }
        return if (frameName != null) listOf(FrameEvent.build(frameName)) else null
    }

    private fun handleBooleanStatus(duContext: DuContext, flag: YesNoResult): List<FrameEvent>? {
        val expected = duContext.expectations.expected
        val slotType = duMeta.getSlotType(expected!!.frame, expected.slot!!)

        // First handling
        val expectations = duContext.expectations
        if (slotType == IStateTracker.ConfirmationStatus) {
            val events = handleBooleanStatusImpl(flag, IStateTracker.FullConfirmationList)
            if (events != null) return events
        }

        // b. boolgate Yes/No
        if (slotType == IStateTracker.BoolGateStatus) {
            val events = handleBooleanStatusImpl(flag, IStateTracker.FullBoolGateList)
            if (events != null) return events
        }

        // c. hasMore Yes/No
        if (slotType == IStateTracker.HasMoreStatus) {
            val events = handleBooleanStatusImpl(flag, IStateTracker.FullHasMoreList)
            if (events != null) return events
        }

        // This is used for boolean slot.
        val flagStr = flag.toJsonAsBoolean()
        // TODO(sean): make sure that expected.slot is not empty here.
        return if (expected != null && flagStr != null && expected.slot != null) {
            listOf(FrameEvent.build(expected.frame, listOf(EntityEvent(flagStr, expected.slot))))
        } else {
            return null
        }
    }


    /**
     * fillSlots is used to create entity event.
     */
    fun fillSlots(ducontext: DuContext, topLevelFrameType: String, focusedSlot: String?): List<FrameEvent> {
        // we need to make sure we include slots mentioned in the intent expression
        // We only need description for slots with no direct fill.
        val slotMapBef = duMeta
            .getNestedSlotMetas(topLevelFrameType, emptyList())
            .filter { !it.value.isDirectFilled }

        // if not direct filled,
        val slotMapAft = slotMapBef.filter { it.value.triggers.isNotEmpty()  }

        if (slotMapBef.isEmpty()) {
            logger.debug("Found no slots for $topLevelFrameType")
            return listOf(FrameEvent.build(topLevelFrameType))
        }

        if (slotMapBef.size != slotMapAft.size) {
            val slotsMissing = slotMapBef.filter { it.value.triggers.isEmpty() }.map {it.key }.toList()
            throw BadConfiguration("Missing triggers for slots $slotsMissing on $topLevelFrameType")
        }

        return fillSlots(ducontext, slotMapAft, topLevelFrameType, focusedSlot)
    }

    fun fillSlots(
        ducontext: DuContext,
        slotMetaMap: Map<String, DUSlotMeta>,
        topLevelFrameType: String,
        focusedSlot: String?): List<FrameEvent> {
        // we need to make sure we include slots mentioned in the intent expression
        val valuesFound = mutableMapOf<String, List<String>>()

        val slots = slotMetaMap.values.map { it.asMap() }.toList()
        for (slot in slotMetaMap.values) {
            val slotType = slot.type
            if (slotType != null) {
                val slotLabel = slot.label
                valuesFound[slotLabel] = ducontext.getValuesByType(slotType)
            }
        }

        // For now, we only focus on the equal operator
        val equalSlotValues = mutableMapOf<String, MutableList<String>>()
        val results = nluService.fillSlots(context, ducontext.utterance, slots, valuesFound)
        logger.debug("got $results from fillSlots for ${ducontext.utterance} on $slots with $valuesFound")

        for (entry in results.entries) {
            if(entry.value.operator == "==") {
                equalSlotValues[entry.key] = entry.value.values.toMutableList()
            }
        }

        // Now incorporate recognizer evidence.
        for (entry in valuesFound.entries) {
            if (entry.key !in equalSlotValues.keys) {
                equalSlotValues[entry.key] = mutableListOf()
            }
            equalSlotValues[entry.key]!!.addAll(entry.value)
        }


        val entityEvents = mutableListOf<EntityEvent>()
        // Let us try to merge the evidence from recognizer.
        val focusedSlotType = if (focusedSlot.isNullOrEmpty()) null else duMeta.getSlotType(topLevelFrameType, focusedSlot)
        for (slot in slotMetaMap.values) {
            val slotType = slot.type!!
            val slotName = slot.label

            // for the focused slot, we use unique partial match.
            val spanedValues = ducontext.entityTypeToValueInfoMap[slotType]?.filter {
                ! it.partialMatch || (it.type == focusedSlotType && results.containsKey(slotName) && (it.origValue in results[slotName]!!.values))
            }

            val origNormValueMap = spanedValues?.map { it.original(ducontext.utterance) to it.norm() }?.toMap()

            // Most of the type are normalizable, except person name for now.
            val normalizable = duMeta.getEntityMeta(slotType)?.normalizable

            if (slotName in equalSlotValues) {
                val slotValues = equalSlotValues[slotName]!!.distinct()
                // For now, assume the operator are always equal
                for (value in slotValues) {
                    if (normalizable == true) {
                        if (!origNormValueMap.isNullOrEmpty() && origNormValueMap.containsKey(value)) {
                            entityEvents.add(EntityEvent.build(slotName, value, origNormValueMap[value]!!, slotType))
                        }
                    } else {
                        entityEvents.add(EntityEvent.build(slotName, value, value, slotType))
                    }
                }
            }
        }

        // not
        return if (!ducontext.expectations.isFrameCompatible(topLevelFrameType) || entityEvents.size != 0) {
                listOf(FrameEvent.build(topLevelFrameType, entityEvents))
        } else {
            emptyList()
        }
    }

    // given a list of frame event, add the entailed slots to the right frame event.
    override fun recycle() {
        nluService.shutdown()
    }

    companion object : ExtensionBuilder<IStateTracker> {
        val logger = LoggerFactory.getLogger(DecoderStateTracker::class.java)

        // TODO(sean): make sure entity side return this as label for DONTCARE
        override fun invoke(p1: Configuration): IStateTracker {
            TODO("Not yet implemented")
        }
    }
}