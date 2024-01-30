package io.opencui.du

import io.opencui.core.*
import org.slf4j.LoggerFactory

import io.opencui.core.RuntimeConfig
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import io.opencui.serialization.*

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

data class SlotValue(val values: List<String>, val operator: String  = "==")

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
    override val owner: String?,  // this could be empty.
    val evidence: List<Exemplar>) : Triggerable

/**
 * For RAG based solution, there are two different stage, build prompt, and then use model to score using
 * the generated prompt. It is possible that we have two different service, one for prompt (which is agent
 * dependent), and one for low level NLU, which can be shared by multiple agents.
 *
 * The encoder model works the same way, except the retrieval is done in the Kotlin side.
 */

// Most likely, the agent dependent nlu and fine-tuned decoder are co-located, so it is better to
// hide that from user.

class RestNluService {
    val client: HttpClient = HttpClient.newHttpClient()
    val url: String = RuntimeConfig.get(RestNluService::class)?: "http://127.0.0.1:3001"

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
    val nluService = RestNluService()

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

        // this layer is important so that we have a centralized place for the post process.
        val res = convertImpl(session, putterance, expectations)

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
            ducontext.expectedEntityType(duMeta),
            ducontext.entityTypeToValueInfoMap
        )
        ducontext.updateTokens(LanguageAnalyzer.get(duMeta.getLang(), stop = false)!!.tokenize(utterance))
        return ducontext
    }

    // This layer is handling the
    fun convertImpl(session: UserSession, utterance: String, expectations: DialogExpectations): List<FrameEvent> {
        val triggerables = detectTriggerables(utterance, expectations)
        logger.debug("getting $triggerables for utterance: $utterance expectations $expectations")

        // We need to update the typedExpression.
        if (triggerables.size == 1 && triggerables[0].owner == null) {
            triggerables[0].evidence.map {
                it.typedExpression = IExemplar.buildTypedExpression(it.template!!, it.ownerFrame, duMeta)
            }
        }

        // TODO: we might need to use exact match to make the hotfix work later.
        if (expectations.hasExpectation()) {
            // We always handle expectations first.
            val duContext = buildDuContext(session, utterance, expectations)
            val needToHandle = triggerables.filter {
                it.owner == null || duMeta.isSystemFrame(it.owner) }
            println(needToHandle)
            if (!needToHandle.isNullOrEmpty()) {
                check(needToHandle.size == 1)
                val events = handleExpectations(duContext, needToHandle[0])
                if (!events.isNullOrEmpty()) {
                    logger.debug("getting $events for $utterance in handleExpectations")
                    return events
                }
            }
        }

        val results = mutableListOf<FrameEvent>()

        // If there are multiple triggerables, we need to handle them one by one.
        for (triggerable in triggerables) {
            val duContext = buildDuContext(session, triggerable.utterance, expectations)
            logger.debug("handling $triggerables for utterance: $utterance")
            val focusedSlot: String? = null
            val events = fillSlots(duContext, triggerable.owner!!, focusedSlot)
            logger.debug("getting $events for $triggerable")
            results.addAll(events)
        }
        return results
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
    private fun handleExpectations(duContext: DuContext, triggerables: Triggerable): List<FrameEvent>? {
        val utterance = duContext.utterance
        val expectations = duContext.expectations

        // We need to check whether we have a binary question. For now, we only handle the top
        // binary question. We assume the first on the top.
        if (expectations.isBooleanSlot()) {
            // The expectation top need to be binary and have prompt.
            val question = expectations.expected!!.prompt!!.map{it.toString()}

            val boolValue = duContext.getEntityValue(IStateTracker.KotlinBoolean)
            if (boolValue != null) {
                val yesNoFlag = when(boolValue) {
                    "true" -> YesNoResult.Affirmative
                    "false" -> YesNoResult.Negative
                    "_DontCare" -> YesNoResult.Indifferent
                    else -> YesNoResult.Irrelevant
                }
                val events = handleBooleanStatus(duContext, yesNoFlag)
                if (events != null) return events
            }

            val status = nluService.yesNoInference(context, utterance, question)[0]
            if (status != YesNoResult.Irrelevant) {
                // First handle the frame wrappers we had for boolean type.
                // what happens we have good match, and these matches are related to expectations.
                // There are at least couple different use cases.
                // TODO(sean): should we start to pay attention to the order of the dialog expectation.
                // Also the stack structure of dialog expectation is not used.
                val events = handleBooleanStatus(duContext, status)
                if (events != null) return events
            }
        }

        // now we need to handle non-boolean types
        // Now we need to figure out what happens for slotupdate.


        // if there is no good match, we need to just find it using slot model.
        // try to fill slot for active frames, assuming the expected!! is the at the beginning.
        for (activeFrame in expectations.activeFrames) {
            val extractedEvents = fillSlots(duContext, activeFrame.frame, activeFrame.slot)
            logger.info("for ${activeFrame} getting event: ${extractedEvents}")
            if (extractedEvents.isNotEmpty()) {
                return extractedEvents
            }
        }

        // TODO: when we have better intent model, we can move this the end of the convert.
        if (expectations.expected!!.slot != null) {
            // First step, handle the basic string case.
            val frame = expectations.expected.frame
            val slot = expectations.expected.slot
            if (slot != null && duMeta.getSlotType(frame, slot) == IStateTracker.KotlinString) {
                return listOf(
                    buildFrameEvent(
                        expectations.expected.frame,
                        listOf(EntityEvent(duContext.utterance, slot))
                    )
                )
            }
        }
        return null
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
        return if (frameName != null) listOf(buildFrameEvent(frameName)) else null
    }

    private fun handleBooleanStatus(duContext: DuContext, flag: YesNoResult): List<FrameEvent>? {
        // First handling
        val expectations = duContext.expectations
        if (expectations.isFrameCompatible(IStateTracker.ConfirmationStatus)) {
            val events = handleBooleanStatusImpl(flag, IStateTracker.FullConfirmationList)
            if (events != null) return events
        }

        // b. boolgate Yes/No
        if (expectations.isFrameCompatible(IStateTracker.BoolGateStatus)) {
            val events = handleBooleanStatusImpl(flag, IStateTracker.FullBoolGateList)
            if (events != null) return events
        }

        // c. hasMore Yes/No
        if (expectations.isFrameCompatible(IStateTracker.HasMoreStatus)) {
            val events = handleBooleanStatusImpl(flag, IStateTracker.FullHasMoreList)
            if (events != null) return events
        }

        // This is used for boolean slot.
        val expected = duContext.expectations.expected
        val flagStr = flag.toJsonAsBoolean()
        // TODO(sean): make sure that expected.slot is not empty here.
        return if (expected != null && flagStr != null && expected.slot != null) {
            listOf(buildFrameEvent(expected.frame, listOf(EntityEvent(flagStr, expected.slot))))
        } else {
            return null
        }
    }

    /**
     * fillSlots is used to create entity event.
     */
    fun fillSlots(ducontext: DuContext, topLevelFrameType: String, focusedSlot: String?): List<FrameEvent> {
        // we need to make sure we include slots mentioned in the intent expression
        val slotMap = when(topLevelFrameType) {
            // IStateTracker.SlotUpdate -> slotTransformBySlotUpdate(ducontext, )
            else ->
                duMeta
                .getNestedSlotMetas(topLevelFrameType, emptyList())
                .filter { it.value.triggers.isNotEmpty() }
        }

        if (slotMap.isEmpty()) {
            logger.debug("Found no slots for $topLevelFrameType")
            return emptyList()
        }

        // we need to make sure we include slots mentioned in the intent expression
        val valuesFound = mutableMapOf<String, List<String>>()
        val slots = slotMap.values.map { it.asMap() }.toList()
        for (slot in slotMap.values) {
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
        if (equalSlotValues.isNotEmpty()) {
            for (result in equalSlotValues) {
                val slotName = result.key
                val slotValues = result.value.distinct()
                // For now, assume the operator are always equal
                for (value in slotValues) {
                    entityEvents.add(EntityEvent(value, slotName))
                }
            }
        }

        return if (!duMeta.isSystemFrame(topLevelFrameType) || entityEvents.size != 0) {
            listOf(buildFrameEvent(topLevelFrameType, entityEvents))
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