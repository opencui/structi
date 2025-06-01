package io.opencui.core

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import io.opencui.core.da.DialogAct
import io.opencui.du.*
import io.opencui.logger.Turn
import io.opencui.serialization.Json
import io.opencui.system1.ISystem1
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.utils.addToStdlib.lastIsInstanceOrNull
import org.jetbrains.kotlin.utils.addToStdlib.measureTimeMillisWithResult
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import kotlin.reflect.full.memberProperties
import kotlin.time.Duration


inline fun<T> timing(msg: String, function: () -> T): T {
    val startTime = System.currentTimeMillis()
    val result: T = function.invoke()
    val endTime = System.currentTimeMillis()
    println("$msg consumed ${endTime - startTime} millisecond.")
    return result
}

inline fun<T> List<T>.removeDuplicate(test: (T) -> Boolean): List<T> {
    val res = mutableListOf<T>()
    var occurence = 0
    for (item in this) {
        if (test(item)) {
            occurence += 1
            if (occurence == 1) {
                res.add(item)
            }
        } else {
            res.add(item)
        }
    }
    return res
}


suspend fun <T> Flow<T>.takeFirst(predicate: suspend (T) -> Boolean): T? {
    return try {
        first { predicate(it) }
    } catch (e: NoSuchElementException) {
        null // Return null if no element meets the predicate
    }
}


fun <T> Flow<T>.bufferUntilOrTimeout(
    timeout: Duration,
    predicate: (T) -> Boolean
): Flow<List<T>> = flow {
    val buffer = mutableListOf<T>()
    val startTime = System.currentTimeMillis()

    this@bufferUntilOrTimeout.collect { item ->
        buffer.add(item)

        // Check predicate and emit buffered items if met
        if (predicate(item)) {
            emit(buffer.toList())
            buffer.clear()
        }

        // Check timeout
        if ((System.currentTimeMillis() - startTime) >= timeout.inWholeMilliseconds) {
            if (buffer.isNotEmpty()) emit(buffer.toList())
            buffer.clear()
        }
    }

    // Emit remaining items at the end of the flow
    if (buffer.isNotEmpty()) emit(buffer.toList())
}


class UsageCollector {
    val usages = mutableMapOf<String, Int>()

    fun collect(key: String, value: Int) {
        usages[key] = value
    }
}



/**
 * DialogManager is used to drive a statechart configured by builder using input event created by end user.
 *
 * The computation is turn based. At each turn, it will follow the state transition rules defined by statechart,
 * until it run into turn termination signal, where it will hand turn to user and wait next input.
 *
 * The invocation chain is as follows:
 * DM -> UserSession.{useStep/kernelStep}:List<Action> -> Scheduler.{wait/grow} -> filler.{wait/grow}
 *
 * DialogManager inside SessionManager inside Dispatcher.
 */
class DialogManager {

    companion object {
        val logger: Logger = LoggerFactory.getLogger(DialogManager::class.java)
        val validEndState = setOf(Scheduler.State.INIT, Scheduler.State.POST_ASK, Scheduler.State.RECOVER)
        val CONFIRMATIONSTATUS = io.opencui.core.confirmation.IStatus::class.qualifiedName!!
        val CONFIRMATIONPACKAGE = CONFIRMATIONSTATUS.split(".").subList(0,5).joinToString(".")
        val HASMORESTATUS = io.opencui.core.hasMore.IStatus::class.qualifiedName!!
    }

    /**
     * high level response, called before dialog understanding (DU, DU is handled by this method first, before it
     * calls the low level response method).
     */
    fun response(query: String, frameEvents: List<FrameEvent>, session: UserSession): Pair<Turn, List<DialogAct>> {
        val timeStamp = LocalDateTime.now()
        val expectations = findDialogExpectation(session)

        val convertToFrameEvent = measureTimeMillisWithResult {
            session.hasSystem1 = session.chatbot?.getExtension<ISystem1>() == null
            session.chatbot!!.stateTracker.convert(session, query, DialogExpectations(expectations))
        }
        val duReturnedFrameEvent = convertToFrameEvent.second

        duReturnedFrameEvent.forEach{ it.source = EventSource.USER }
        logger.debug("Du returned frame events : $duReturnedFrameEvent")
        logger.debug("Extra frame events : $frameEvents")
        // If the event is created in the user role, we respect that.
        frameEvents.forEach { if (it.source == null ) { it.source = EventSource.API } }
        val convertedFrameEventList = convertSpecialFrameEvent(session, duReturnedFrameEvent + frameEvents)
        logger.debug("Converted frame events : $convertedFrameEventList")
        val results = response(ParsedQuery(query, convertedFrameEventList), session)

        val dialogActs = results
            .filter { it.botUtterance != null && it.botOwn }
            .map { it.botUtterance!!}.flatten().distinct()

        val turn = Turn(
            query,
            Json.encodeToJsonElement(expectations?.activeFrames ?: emptyList()),
            Json.encodeToJsonElement(duReturnedFrameEvent),
            convertToFrameEvent.first,
            session.chatbot?.agentLang ?: "en"
        )
        turn.timeStamp = timeStamp
        turn.usage = mapOf(
            "input_tokens" to 1,
            "output_tokens" to 1,
            )
        return Pair(turn, dialogActs)
    }

    fun processResults2(resultsFlow: Flow<ActionResult>): Flow<DialogAct> {
        return resultsFlow
            .filter { it.botUtterance != null && it.botOwn } // Ensure botUtterance is not null and botOwn is true
            .flatMapConcat { actionResult ->
                // Flatten the list of DialogAct into the flow
                actionResult.botUtterance!!.asFlow()
            }
            .distinctUntilChanged()// Keep only distinct DialogActs based on utterance
    }

    fun responseAsync(query: String, frameEvents: List<FrameEvent>, session: UserSession): Pair<Turn, Flow<DialogAct>> {
        val timeStamp = LocalDateTime.now()
        val expectations = findDialogExpectation(session)

        val convertToFrameEvent = measureTimeMillisWithResult {
            session.chatbot!!.stateTracker.convert(session, query, DialogExpectations(expectations))
        }
        val duReturnedFrameEvent = convertToFrameEvent.second

        duReturnedFrameEvent.forEach{ it.source = EventSource.USER }
        logger.debug("Du returned frame events : $duReturnedFrameEvent")
        logger.debug("Extra frame events : $frameEvents")
        // If the event is created in the user role, we respect that.
        frameEvents.forEach { if (it.source == null ) { it.source = EventSource.API } }
        val convertedFrameEventList = convertSpecialFrameEvent(session, duReturnedFrameEvent + frameEvents)
        logger.debug("Converted frame events : $convertedFrameEventList")

        val results = responseAsync(ParsedQuery(query, convertedFrameEventList), session)

        val dialogActs = processResults2(results)

        val turn = Turn(
            query,
            Json.encodeToJsonElement(expectations?.activeFrames ?: emptyList()),
            Json.encodeToJsonElement(duReturnedFrameEvent),
            convertToFrameEvent.first,
            session.chatbot?.agentLang ?: "en"
        )

        turn.timeStamp = timeStamp
        turn.usage = mapOf(
            "input_tokens" to 1,
            "output_tokens" to 1,
            )
        
        return Pair(turn, dialogActs)
    }


    /**
     * Low level response, after DU is done. Currently we have two versions.
     */
    fun response(pinput: ParsedQuery, session: UserSession): List<ActionResult> = runBlocking {
        responseAsync(pinput, session).toList()
    }

    fun responseAsync(pinput: ParsedQuery, session: UserSession): Flow<ActionResult> = flow {
        session.turnId += 1
        session.addUserMessage(pinput.query)

        logger.info("session state before turn ${session.turnId} : ${pinput}")
        val frameEvents = pinput.frames

        // Handle empty events case
        if (frameEvents.isEmpty()) {
            session.findSystemAnnotation(SystemAnnotationType.IDonotGetIt)
                ?.searchResponse()
                ?.wrappedRun(session)
                ?.let {
                    emit(it)
                    return@flow
                }
        }

        session.addEvents(frameEvents)
        val actionResults = mutableListOf<ActionResult>()
        var botOwn = session.autopilotMode
        var maxRound = 100 // prevent one session from taking too many resources
        do {
            var schedulerChanged = false
            while (session.schedulers.size > 1) {
                val top = session.schedulers.last()
                if (top.isEmpty()) {
                    session.schedulers.removeLast()
                    schedulerChanged = true
                } else {
                    break
                }
            }

            var currentTurnWorks = session.userStep()
            if (schedulerChanged && currentTurnWorks.isEmpty()) {
                session.schedule.state = Scheduler.State.RESCHEDULE
                currentTurnWorks = session.userStep()
            }
            // Make sure it is empty or just one action.
            check(currentTurnWorks.isEmpty() || currentTurnWorks.size == 1)

            if (currentTurnWorks.isNotEmpty()) {
                try {
                    assert(currentTurnWorks.size == 1)
                    val result = currentTurnWorks[0].wrappedRun(session).apply {
                        this.botOwn = botOwn
                    }
                    actionResults += result
                    emit(result)
                } catch (e: Exception) {
                    session.schedule.state = Scheduler.State.RECOVER
                    throw e
                }
            }

            botOwn = session.autopilotMode
            if (--maxRound <= 0) break
        } while (currentTurnWorks.isNotEmpty())

        if (!validEndState.contains(session.schedule.state)) {
            val currentState = session.schedule.state
            session.schedule.state = Scheduler.State.RECOVER
            throw Exception("END STATE of scheduler is invalid STATE : $currentState")
        }

        logger.info("session state after turn ${session.turnId} : ${session.toSessionString()}")

        val system1Response = getSystem1Response(session, frameEvents)

        // (TODO): why do we need this?
        if (actionResults.isEmpty() && session.schedule.isNotEmpty() && session.lastTurnRes.isNotEmpty()) {
            system1Response.collect{ it -> emit(it) }
            session.lastTurnRes.forEach { emit(it) }
        } else {
            session.lastTurnRes = actionResults
            system1Response.collect { it -> emit(it) }
        }
    }

    fun getSystem1Response(session: UserSession, frameEvents: List<FrameEvent>): Flow<ActionResult> = flow {
        val actionResults = mutableListOf<ActionResult>()
        val userFrameEvents = frameEvents.filter { it.source == EventSource.USER }

        logger.info("Inside system1...")
        logger.info(userFrameEvents.toString())
        // If we do not understand, we fall back to system1
        if (userFrameEvents.size == 1 && userFrameEvents[0].type == "IDonotGetIt") {
            logger.info("inside system1 with ${userFrameEvents}")
            val responses = ISystem1.response(session)
            // No need to add system-wide reminder, it is builder's decision.
            responses.collect { item ->
                // You can add logic here if needed, but for simple re-emission, just emit
                emit(item)
            }
        }
    }


    fun findDialogExpectation(session: UserSession): DialogExpectation? {
        val entity = session.schedule.lastOrNull()
        if (session.schedule.isEmpty() || entity == null || entity.askStrategy() is ExternalEventStrategy) return null
        val topFrameWrapperFiller = session.schedule.filterIsInstance<AnnotatedWrapperFiller>().lastOrNull { it.targetFiller is FrameFiller<*> }!!
        return DialogExpectation(findExpectedFrames(session, topFrameWrapperFiller))
    }


    private fun getDialogActs(session: UserSession, filler: IFiller): List<DialogAct> {
        val actions = filler.slotAskAnnotation()?.actions ?: emptyList()
        return SeqAction(actions).wrappedRun(session).botUtterance!!
    }

    // This can be used recursively for cases like
    private fun findExpectedFrames(session: UserSession, topFrameWrapperFiller: AnnotatedWrapperFiller): List<ExpectedFrame> {
        assert(topFrameWrapperFiller.targetFiller is FrameFiller<*>)
        val topFrame = (topFrameWrapperFiller.targetFiller as FrameFiller<*>).frame()
        val res = mutableListOf<ExpectedFrame>()
        when (topFrame) {
            is PagedSelectable<*> -> {
                //TODO(xiaobo) what is the actual order in the stack?
                val targetFiller = (topFrameWrapperFiller.parent as? AnnotatedWrapperFiller)?.targetFiller
                if (targetFiller != null) {
                    val potentialHasMoreFiller = targetFiller.parent?.parent?.parent?.parent
                    if ((potentialHasMoreFiller as? FrameFiller<*>)?.frame() is HasMore) {
                        // hardcode status for HasMore
                        val typeStr = io.opencui.core.hasMore.IStatus::class.qualifiedName!!
                        val frameName = HasMore::class.qualifiedName!!
                        val dialogActs = getDialogActs(session, potentialHasMoreFiller)
                        res += ExpectedFrame(frameName, "status", typeStr, dialogActs)
                        val potentialMVFiller = potentialHasMoreFiller.parent?.parent
                        if (potentialMVFiller != null) {
                            findExpectationByFiller(session, potentialMVFiller)?.let {
                                res += it
                            }
                        }
                    } else {
                        val recTargetExp = findExpectationByFiller(session, targetFiller)
                        if (recTargetExp != null) res += recTargetExp
                    }
                }
                // No need to
                res += ExpectedFrame(PagedSelectable::class.qualifiedName!!, "index", Ordinal::class.qualifiedName!!)
            }
            is Confirmation -> {
                val typeStr = io.opencui.core.confirmation.IStatus::class.qualifiedName!!
                val dialogActs = getDialogActs(session, topFrameWrapperFiller)
                res += ExpectedFrame(Confirmation::class.qualifiedName!!, "status", typeStr, dialogActs)
                val targetFiller = (topFrameWrapperFiller.parent as? AnnotatedWrapperFiller)?.targetFiller
                if (targetFiller != null) {
                    val potentialPagedSelectableFiller = targetFiller.parent?.parent
                    if (potentialPagedSelectableFiller is FrameFiller<*> && potentialPagedSelectableFiller.frame() is PagedSelectable<*>) {
                        val pageFrame = potentialPagedSelectableFiller.frame() as PagedSelectable<*>
                        val recTarget = pageFrame.target
                        val recSlot = pageFrame.slot
                        if (recTarget != null) {
                            val recTargetFiller = session.findWrapperFillerWithFrame(recTarget)
                            val expectedTargetFiller = if (recSlot.isNullOrEmpty()) {
                                recTargetFiller?.targetFiller
                            } else {
                                (recTargetFiller?.targetFiller as? FrameFiller<*>)?.fillers?.get(recSlot)?.targetFiller
                            }
                            if (expectedTargetFiller != null) {
                                findExpectationByFiller(session, expectedTargetFiller)?.let {
                                    res += it
                                }
                            }
                            if (expectedTargetFiller is MultiValueFiller<*>) {
                                val typeStr = io.opencui.core.hasMore.IStatus::class.qualifiedName!!
                                val dialogActs = getDialogActs(session, expectedTargetFiller)
                                res += ExpectedFrame(HasMore::class.qualifiedName!!, "status", typeStr)
                            }
                        }
                    }
                    findExpectationByFiller(session, targetFiller)?.let {
                        res += it
                    }
                }
            }
            is HasMore -> {
                val typeStr = io.opencui.core.hasMore.IStatus::class.qualifiedName!!
                val dialogActs = getDialogActs(session, topFrameWrapperFiller)
                res += ExpectedFrame(HasMore::class.qualifiedName!!, "status", typeStr, dialogActs)
                val multiValueFiller = topFrameWrapperFiller.parent as? MultiValueFiller<*>
                if (multiValueFiller != null) {
                    findExpectationByFiller(session, multiValueFiller)?.let {
                        res += it
                    }
                }
            }
            is BoolGate -> {
                val typeStr = io.opencui.core.booleanGate.IStatus::class.qualifiedName!!
                val dialogActs = getDialogActs(session, topFrameWrapperFiller)
                res += ExpectedFrame(BoolGate::class.qualifiedName!!, "status", typeStr)
                val targetFiller = (topFrameWrapperFiller.parent as? AnnotatedWrapperFiller)?.targetFiller
                if (targetFiller != null) {
                    if (targetFiller is FrameFiller<*>) {
                        val parent = targetFiller.parent?.parent as? FrameFiller<*>
                        if (parent != null) {
                            res += ExpectedFrame(parent.qualifiedEventType()!!, targetFiller.attribute, targetFiller.qualifiedTypeStr())
                        }
                    }
                    findExpectationByFiller(session, targetFiller)?.let {
                        res += it
                    }
                }
            }
            else -> {
                val frameFiller = topFrameWrapperFiller.targetFiller
                val index = session.schedule.indexOf(frameFiller)
                val focusFiller = if (index != -1 && session.schedule.size > index+1) session.schedule[index+1] as? AnnotatedWrapperFiller else null
                val focus = focusFiller?.attribute

                if (frameFiller.qualifiedTypeStr() != Confirmation::class.qualifiedName!!
                    && (focusFiller?.targetFiller as? InterfaceFiller<*>)?.qualifiedTypeStr() == CONFIRMATIONSTATUS) {
                    val dialogActs = getDialogActs(session, frameFiller)
                    res += ExpectedFrame(Confirmation::class.qualifiedName!!, "status", CONFIRMATIONSTATUS, dialogActs)
                }
                // TODO (sean), why we only care about the top of stack?
                res += ExpectedFrame(frameFiller.qualifiedEventType()!!, focus, (focusFiller?.targetFiller as? TypedFiller<*>)?.qualifiedTypeStr())

                // Now we check whether it is a frame with head.
                val slotMetas = session.chatbot!!.duMeta.getSlotMetas(frameFiller.qualifiedTypeStr())
                if (slotMetas.any { it.isHead }) {
                    val targetFiller = topFrameWrapperFiller.parent
                    if (targetFiller != null) {
                        findExpectationByFiller(session, targetFiller)?.let {
                            res += it
                        }
                    }
                }
            }
        }
        if (res.firstOrNull { it.frame == HasMore::class.qualifiedName } == null && session.schedule.firstOrNull { it is FrameFiller<*> && it.frame() is HasMore } != null) {
            // check if there is undone HasMore
            val filler = session.schedule.firstOrNull { it is FrameFiller<*> && it.frame() is HasMore } as IFiller
            val dialogActs = getDialogActs(session, filler)
            res += ExpectedFrame(HasMore::class.qualifiedName!!, "status", HASMORESTATUS, dialogActs)
        }
        return res
    }

    private fun findExpectationByFiller(session: UserSession, filler: IFiller): ExpectedFrame? {
        when (filler) {
            is EntityFiller<*> -> {
                val frameName = filler.qualifiedEventType() + (extractSlotType(filler)?.let { "$${it}" } ?: "")
                val typeStr = filler.qualifiedTypeStr()
                val dialogActs = getDialogActs(session, filler)
                return ExpectedFrame(frameName, filler.attribute, typeStr, dialogActs)
            }
            is FrameFiller<*> -> {
                val index = session.schedule.indexOf(filler)
                val focusFiller = if (index != -1 && session.schedule.size > index+1) session.schedule[index+1] as? AnnotatedWrapperFiller else null
                val frameName = filler.qualifiedEventType()!!
                val typeStr = (focusFiller?.targetFiller as? TypedFiller<*>)?.qualifiedTypeStr()
                val dialogActs = if (focusFiller == null) emptyList() else getDialogActs(session, focusFiller)
                return ExpectedFrame(frameName, focusFiller?.attribute, typeStr, dialogActs)
            }
            is InterfaceFiller<*> -> {
                val parent = filler.parent?.parent
                if (parent != null) return findExpectationByFiller(session, parent)
            }
            is MultiValueFiller<*> -> {
                when (filler.svType) {
                    MultiValueFiller.SvType.ENTITY -> {
                        val typeStr = filler.qualifiedTypeStrForSv()
                        return ExpectedFrame(filler.qualifiedEventType()!!, filler.attribute, typeStr)
                    }
                    MultiValueFiller.SvType.FRAME -> return ExpectedFrame(filler.qualifiedEventType()!!)
                    MultiValueFiller.SvType.INTERFACE -> {
                        val frameFiller = filler.parent?.parent as? FrameFiller<*>
                        if (frameFiller != null) {
                            val typeStr = filler.qualifiedTypeStrForSv()
                            return ExpectedFrame(frameFiller.qualifiedEventType()!!, filler.attribute, typeStr)
                        }
                    }
                }
            }
        }
        return null
    }

    // extract ValueClarification slot type
    private fun extractSlotType(filler: EntityFiller<*>): String? {
        val clarifyFrame = (filler.parent?.parent as? FrameFiller<*>)?.frame() as? AbstractValueClarification<*>
        if (clarifyFrame != null) {
            val target = clarifyFrame.targetFrame
            val slot = clarifyFrame.slot
            val propertyType = target::class.memberProperties.firstOrNull { it.name == slot }?.returnType?.toString()
            if (propertyType != null) {
                return if (propertyType.endsWith("?")) propertyType.substring(0, propertyType.length-1) else propertyType
            }
        }
        return null
    }

    fun convertSpecialFrameEvent(session: UserSession, events: List<FrameEvent>): List<FrameEvent> {
        if (session.schedule.isNotEmpty()) {
            if (events.size == 1 && events.first().slots.isEmpty() && session.isOpenIntent(events.first())) {
                val frame = ((session.schedule.firstOrNull { it is AnnotatedWrapperFiller && it.targetFiller is FrameFiller<*> && it.targetFiller.frame()::class.qualifiedName == events.first().fullType } as? AnnotatedWrapperFiller)?.targetFiller as? FrameFiller<*>)?.frame()
                if (frame != null) {
                    val fullResumeIntent = SystemAnnotationType.ResumeIntent.typeName
                    val packageName = fullResumeIntent.substringBeforeLast(".")
                    val type = fullResumeIntent.substringAfterLast(".")
                    return listOf(FrameEvent(type, packageName = packageName).apply { slotAssignments["intent"] = {frame} })
                }
            } else {
                var pagedSelectableFiller: FrameFiller<*>? = null
                val lastFrameFiller = session.schedule.lastIsInstanceOrNull<FrameFiller<*>>()
                if (lastFrameFiller != null && lastFrameFiller.frame() is PagedSelectable<*> && events.firstOrNull { it.type == "PagedSelectable" } == null) {
                    pagedSelectableFiller = lastFrameFiller
                } else if (lastFrameFiller != null && lastFrameFiller.frame() is Confirmation && events.firstOrNull { (it.type == "Yes" || it.type == "No") && it.packageName == CONFIRMATIONPACKAGE } == null) {
                    val activeFrameFillers = session.schedule.filterIsInstance<FrameFiller<*>>()
                    if (activeFrameFillers.size > 1) {
                        val potentialPagedSelectableFiller = activeFrameFillers[activeFrameFillers.size - 2] as? FrameFiller<*>
                        if (potentialPagedSelectableFiller?.frame() is PagedSelectable<*>) {
                            pagedSelectableFiller = potentialPagedSelectableFiller
                        }
                    }
                }
                if (pagedSelectableFiller != null) {
                    val recFrame: PagedSelectable<*> = pagedSelectableFiller.frame() as PagedSelectable<*>
                    val targetFrame = recFrame.target ?: return events
                    val frameWrapperFiller = session.findWrapperFillerWithFrame(targetFrame) ?: return events
                    val childSlot = recFrame.slot
                    val targetFiller = if (childSlot.isNullOrEmpty()) {
                        frameWrapperFiller
                    } else {
                        (frameWrapperFiller.targetFiller as? FrameFiller<*>)?.fillers?.get(childSlot) ?: return events
                    }

                    val res: MutableList<FrameEvent> = mutableListOf()
                    for (event in events) {
                        if (targetFiller.isCompatible(event.apply { source = EventSource.USER })) {
                            val nonFunctionCallSlotEvents: MutableList<EntityEvent> = mutableListOf()
                            val classArrNode = ArrayNode(JsonNodeFactory.instance, listOf(TextNode(event.fullType)))
                            val objNode = ObjectNode(JsonNodeFactory.instance)
                            objNode.replace("@class", classArrNode)
                            for (entityEvent in event.slots) {
                                if (targetFiller.isCompatible(FrameEvent(event.type, slots = listOf(entityEvent), packageName = event.packageName).apply { source = EventSource.USER })) {
                                    // if (session.chatbot!!.stateTracker.isPartialMatch(entityEvent)) {
                                    //    val candidateValues = session.chatbot!!.stateTracker.findRelatedEntity(entityEvent)?.map { TextNode(it) }
                                    //    if (!candidateValues.isNullOrEmpty()) {
                                    //        objNode.replace(entityEvent.attribute, ArrayNode(JsonNodeFactory.instance, candidateValues))
                                    //    }
                                    // } else {
                                        val arrNode = ArrayNode(JsonNodeFactory.instance, listOf(Json.parseToJsonElement(entityEvent.value)))
                                        objNode.replace(entityEvent.attribute, arrNode)
                                    //}
                                } else {
                                    nonFunctionCallSlotEvents += entityEvent
                                }
                            }
                            res += FrameEvent(FilterCandidate::class.simpleName!!, slots = listOf(EntityEvent(TextNode(objNode.toString()).toString(), "conditionMapJson")), packageName = FilterCandidate::class.qualifiedName!!.substringBeforeLast("."))
                            if (nonFunctionCallSlotEvents.isNotEmpty()) {
                                res += FrameEvent(event.type, slots = nonFunctionCallSlotEvents, packageName = event.packageName)
                            }
                        } else if (targetFrame::class.qualifiedName == event.qualifiedName) {
                            res += event
                            res += FrameEvent(AuxiliaryChange::class.simpleName!!, slots = listOf(), packageName = AuxiliaryChange::class.qualifiedName!!.substringBeforeLast("."))
                        } else {
                            res += event
                        }
                    }
                    return res
                }
            }
        }
        return events
    }
}