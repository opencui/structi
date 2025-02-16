package io.opencui.core

import io.opencui.core.hasMore.IStatus
import io.opencui.core.hasMore.Yes
import kotlin.math.min
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import io.opencui.core.hasMore.No
import io.opencui.core.Dispatcher.closeSession
import io.opencui.core.da.DialogAct
import io.opencui.serialization.Json
import java.io.Serializable
import java.lang.RuntimeException
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.KProperty1


/**
 * this file contains the things we need from platform, they will be declared on platform so that they can
 * referenced. The declaration on the platform need not to be complete, but need to be accurate so the
 * reference is always available.
 * TODO: the IFrame that builder can define and use should only have one constructor parameter with session.
 * The special intent like clarification can only be started by bot, so we just need to create
 * specially IIntentBuilder for them.
 */

// One of the main problem, we sometime need to create frame and frame filler together so that filler
// knows what is filled? Can we just use the nullness as indicator for filler to understand what is done?
// Maybe, but need to change the logic to some extent.

// Use this instead of another function for (UserSession) -> IIntent to avoid the overloading ambiguity
// Kotlin treat different all function as functions.
fun interface IFrameBuilder : Serializable {
    fun invoke(session: UserSession): IFrame?
}

fun interface InitIFrameFiller {
    fun init(session: UserSession, filler: FrameFiller<*>)
}

interface FullFrameBuilder: IFrameBuilder, InitIFrameFiller, Serializable {
    override fun init(session: UserSession, filler: FrameFiller<*>) {}
}

data class JsonFrameBuilder(
    @get:JsonIgnore val value: String,
    @JsonIgnore val constructorParameters: List<Any?> = listOf(),
    @JsonIgnore var slotAssignments: Map<String, ()->Any?> = mapOf()
): FullFrameBuilder {
    override fun invoke(session: UserSession) : IFrame? {
        val objectNode = Json.parseToJsonElement(value) as ObjectNode
        val fullyQualifiedName = objectNode.get("@class").asText()
        val packageName = fullyQualifiedName.substringBeforeLast(".")
        val intentName = fullyQualifiedName.substringAfterLast(".")
        return session.construct(packageName, intentName, *constructorParameters.toTypedArray())
    }

    override fun init(session: UserSession, filler: FrameFiller<*>) {
        val jsonString = value
        val jsonObject = Json.parseToJsonElement(jsonString) as ObjectNode
        val className = jsonObject.remove("@class").asText()
        val kClass = session.findKClass(className)!!
        val frame: IFrame = Json.decodeFromJsonElement(jsonObject, kClass) as IFrame
        frame.session = session
        for (k in jsonObject.fieldNames()) {
            val f = filler.fillers[k] ?: continue
            val property = kClass.memberProperties.firstOrNull { it.name == k } as? KProperty1<Any, Any> ?: continue
            val v = property.get(frame)
            f.directlyFill(v)
        }
        for ((k, vg) in slotAssignments) {
            val f = filler.fillers[k] ?: continue
            val v = vg() ?: continue
            f.directlyFill(v)
        }
    }
}

data class EventFrameBuilder(
    val frameEvent: FrameEvent
) : FullFrameBuilder {
    override fun invoke(session: UserSession): IFrame? {
        val ib = intentBuilder(frameEvent)
        return ib.invoke(session)
    }

    override fun init(session: UserSession, filler: FrameFiller<*>) {
        //  remember to use jsonValue.
        filler.initFromJsonValue(frameEvent)

        for ((k, vg) in frameEvent.slotAssignments) {
            val f = filler.fillers[k] ?: continue
            val v = vg() ?: continue
            f.directlyFill(v)
        }
    }
}

fun intentBuilder(frameEvent: FrameEvent): IFrameBuilder {
    val type = frameEvent.type
    val packageName = frameEvent.packageName
    return IFrameBuilder{ session -> session.construct(packageName, type, session, *frameEvent.triggerParameters.toTypedArray()) as? IIntent }
}

fun intentBuilder(fullyQualifiedName: String, vararg args: Any?): IFrameBuilder {
    return IFrameBuilder{
        session ->
            if (fullyQualifiedName.isEmpty() || fullyQualifiedName.lastIndexOf(".") < 0 ) {
                null
            }  else {
                val index = fullyQualifiedName.lastIndexOf(".")
                val packageName = fullyQualifiedName.substring(0, index)
                val className = fullyQualifiedName.substring(index + 1)
                session.construct(packageName, className, session, *args) as? IIntent
            }
    }
}

fun <T:IFrame> intentBuilder(eventFrame: T, updateRules: List<UpdateRule>):IFrameBuilder {
    return IFrameBuilder{ session -> StitchedIntent<T>(session, eventFrame, updateRules) }
}

data class HasMore(
        override var session: UserSession? = null,
        val promptActions: List<Action>,
        val inferFunc: (FrameEvent) -> FrameEvent?,
        val minChecker: () -> Boolean = {true},
        val minPrompts: () -> DialogAct
) : IBotMode, IFrame {
    @JsonIgnore
    var status: IStatus? = null

    @JsonIgnore
    override fun annotations(path: String): List<Annotation> =
        when(path) {
            STATUS -> listOf(
                SlotPromptAnnotation(promptActions),
                ValueCheckAnnotation(
                    {OldValueCheck(
                        session,
                        { status !is No || minChecker() },
                        listOf(Pair(this, STATUS)),
                        minPrompts
                    )}
                )
            )
            else -> listOf()
        }


    fun hasMore(): Boolean {
        return status is Yes
    }

    override fun createBuilder() = object : FillBuilder {
        var frame: HasMore? = this@HasMore
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = ::frame
            val filler = FrameFiller({ tp }, path)
            filler.addWithPath(InterfaceFiller({ tp.get()!!::status }, createFrameGenerator(tp.get()!!.session!!, ISTATUS), inferFunc))
            return filler
        }
    }

    companion object {
        val ISTATUS = IStatus::class.qualifiedName!!
        const val STATUS = "status"
    }
}

data class BoolGate(
    override var session: UserSession? = null,
    val prompts: () -> DialogAct,
    val inferFunc: (FrameEvent) -> FrameEvent?) : IBotMode, IFrame {

    @JsonIgnore
    var status: io.opencui.core.booleanGate.IStatus? = null

    @JsonIgnore
    override fun annotations(path: String): List<Annotation> = when(path) {
        "status" -> listOf(SlotPromptAnnotation(listOf(prompts())))
        else -> listOf()
    }

    override fun createBuilder() = object : FillBuilder {
        var frame: BoolGate? = this@BoolGate
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = ::frame
            val filler = FrameFiller({ tp }, path)
            filler.addWithPath(InterfaceFiller({ tp.get()!!::status }, createFrameGenerator(tp.get()!!.session!!, io.opencui.core.booleanGate.IStatus::class.qualifiedName!!), inferFunc))
            return filler
        }
    }
}

class CloseSession() : ChartAction {
    override fun run(session: UserSession): ActionResult {
        // next time the same channel/id is call, we need a new UserSession.
        // so that botOwn is true.
        closeSession(session.userIdentifier, session.botInfo)
        session.cleanup()
        return ActionResult(createLog("CLEAN SESSION"), true)
    }
}

data class ActionWrapperIntent(override var session: UserSession? = null, val action: Action): IIntent {
    override fun createBuilder() = object : FillBuilder {
        var frame: ActionWrapperIntent? = this@ActionWrapperIntent
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = ::frame
            val filler = FrameFiller({ tp }, path)
            return filler
        }
    }

    override fun searchResponse(): Action? {
        return action
    }
}

//TODO(xiaobo): have a condition is good, but it will be more powerful if it is outside of list.
data class UpdateRule(val condition: () -> Boolean, val action: Action, val score: Int = 0) : Serializable

/**
 * Dynamically created intent from the event frame T and update rules.
 */
data class StitchedIntent<T: IFrame>(
    override var session: UserSession? = null,
    val eventFrame: T,
    val updateRules: List<UpdateRule>): IIntent {

    override fun createBuilder() = object : FillBuilder {
        var frame: StitchedIntent<T>? = this@StitchedIntent
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = ::frame
            val eventFrame = eventFrame
            val filler = FrameFiller({ tp }, path)
            filler.add(eventFrame.createBuilder().invoke(path.join("eventFrame", eventFrame)) as FrameFiller<*>)
            return filler
        }
    }

    override fun searchResponse(): Action? {
        val filteredUpdates = updateRules.filter { it.condition() }
        // TODO(xiaobo): why do we need this score?
        val maxScore = filteredUpdates.map { it.score }.maxOrNull()
        val updateActions = filteredUpdates.filter { it.score == maxScore }.map { it.action }
        return when {
            else -> SeqAction(updateActions)
        }
    }
}

data class CleanupAction(
    val toBeCleaned: List<IFiller>
) : StateAction {
    override fun run(session: UserSession): ActionResult {
        for (fillerToBeCleaned in toBeCleaned) {
            // (TODO: verify whether this indeed is not needed)
            fillerToBeCleaned.clear()
            for (currentScheduler in session.schedulers.reversed()) {
                var index = currentScheduler.size
                for ((i, f) in currentScheduler.withIndex().reversed()) {
                    if (f == fillerToBeCleaned) {
                        index = i
                        break
                    }
                }
                // pop fillers that have gone back to initial state but never pop the root filler in a CleanupAction
                if (index < currentScheduler.size) {
                    var count = currentScheduler.size - index
                    while (count-- > 0 && currentScheduler.size > 1) {
                        val filler = currentScheduler.pop()
                        filler.clear()
                    }
                    break
                }
            }
        }

        return ActionResult(
            createLog("CLEANUP SLOT : ${toBeCleaned.map { it.path!!.path.last() }.joinToString { "target=${it.host.javaClass.name}&slot=${if (it.isRoot()) "" else it.attribute}" }}"),
            true
        )
    }
}

data class CleanupActionBySlot(val toBeCleaned: List<Pair<IFrame, String?>>) : StateAction {
    override fun run(session: UserSession): ActionResult {
        val fillersToBeCleaned = mutableListOf<IFiller>()
        for (slotToBeCleaned in toBeCleaned) {
            val targetFiller = session.findWrapperFillerForTargetSlot(slotToBeCleaned.first, slotToBeCleaned.second) ?: continue
            fillersToBeCleaned += targetFiller
        }

        return CleanupAction(fillersToBeCleaned).wrappedRun(session)
    }
}

data class RecheckAction(val toBeRechecked: List<IFiller>) : StateAction {
    override fun run(session: UserSession): ActionResult {
        for (fillerToBeRechecked in toBeRechecked) {
            (fillerToBeRechecked as? AnnotatedWrapperFiller)?.recheck()
        }

        return ActionResult(
            createLog("RECHECK SLOT : ${toBeRechecked.map { it.path!!.path.last() }.joinToString { "target=${it.host.javaClass.name}&slot=${if (it.isRoot()) "" else it.attribute}" }}"),
            true
        )
    }
}

data class RecheckActionBySlot(val toBeRechecked: List<Pair<IFrame, String?>>) : StateAction {
    override fun run(session: UserSession): ActionResult {
        val fillersToBeRechecked = mutableListOf<AnnotatedWrapperFiller>()
        for (slotToBeCleaned in toBeRechecked) {
            val targetFiller = session.findWrapperFillerForTargetSlot(slotToBeCleaned.first, slotToBeCleaned.second) ?: continue
            fillersToBeRechecked += targetFiller
        }

        return RecheckAction(fillersToBeRechecked).wrappedRun(session)
    }
}

data class ReinitAction(val toBeReinit: List<IFiller>) : StateAction {
    override fun run(session: UserSession): ActionResult {
        for (filler in toBeReinit) {
            (filler as? AnnotatedWrapperFiller)?.reinit()
        }

        return ActionResult(
            createLog("REINIT SLOT : ${toBeReinit.map { it.path!!.path.last() }.joinToString { "target=${it.host.javaClass.name}&slot=${if (it.isRoot()) "" else it.attribute}" }}"),
            true
        )
    }
}

data class ReinitActionBySlot(val toBeRechecked: List<Pair<IFrame, String?>>) : StateAction {
    override fun run(session: UserSession): ActionResult {
        val fillersToBeReinit = mutableListOf<AnnotatedWrapperFiller>()
        for (slot in toBeRechecked) {
            val targetFiller = session.findWrapperFillerForTargetSlot(slot.first, slot.second) ?: continue
            fillersToBeReinit += targetFiller
        }

        return ReinitAction(fillersToBeReinit).wrappedRun(session)
    }
}

data class DirectlyFillAction<T>(
    val generator: () -> T?,
    val filler: AnnotatedWrapperFiller, val decorativeAnnotations: List<Annotation> = listOf()) : StateAction {
    override fun run(session: UserSession): ActionResult {
        val param = filler.path!!.path.last()
        val value = generator() ?: return ActionResult(
            createLog("FILL SLOT value is null for target : ${param.host::class.qualifiedName}, slot : ${if (param.isRoot()) "" else param.attribute}"),
            true
        )
        filler.directlyFill(value)
        filler.decorativeAnnotations.addAll(decorativeAnnotations)
        return ActionResult(
            createLog("FILL SLOT for target : ${param.host::class.qualifiedName}, slot : ${if (param.isRoot()) "" else param.attribute}"),
            true
        )
    }
}

data class DirectlyFillActionBySlot<T>(
    val generator: () -> T?,
    val frame: IFrame?,
    val slot: String?,
    val decorativeAnnotations: List<Annotation> = listOf()) : StateAction {
    override fun run(session: UserSession): ActionResult {
        val wrapFiller = frame?.let { session.findWrapperFillerForTargetSlot(frame, slot) } ?: return ActionResult(
            createLog("cannot find filler for frame : ${if (frame != null) frame::class.qualifiedName else null}, slot : ${slot}"),
            true
        )
        return DirectlyFillAction(generator, wrapFiller, decorativeAnnotations).wrappedRun(session)
    }
}

data class FillAction<T>(
    val generator: () -> T?,
    val filler: IFiller,
    val decorativeAnnotations: List<Annotation> = listOf()) : StateAction {
    override fun run(session: UserSession): ActionResult {
        val param = filler.path!!.path.last()
        val value = generator() ?: return ActionResult(
            createLog("FILL SLOT value is null for target : ${param.host::class.qualifiedName}, slot : ${if (param.isRoot()) "" else param.attribute}"),
            true
        )
        val frameEventList = session.generateFrameEvent(filler, value)
        frameEventList.forEach {
            it.triggered = true
            it.slots.forEach { slot ->
                slot.decorativeAnnotations.addAll(decorativeAnnotations)
            }
        }

        if (frameEventList.isNotEmpty()) session.addEvents(frameEventList)
        return ActionResult(
            createLog("FILL SLOT for target : ${param.host::class.qualifiedName}, slot : ${if (param.isRoot()) "" else param.attribute}"),
            true
        )
    }
}

data class FillActionBySlot<T>(
    val generator: () -> T?,
    val frame: IFrame?,
    val slot: String?,
    val decorativeAnnotations: List<Annotation> = listOf()) : StateAction {
    override fun run(session: UserSession): ActionResult {
        val wrapFiller = frame?.let { session.findWrapperFillerForTargetSlot(frame, slot) } ?: return ActionResult(
            createLog("cannot find filler for frame : ${if (frame != null) frame::class.qualifiedName else null}, slot : ${slot}"),
            true
        )
        if (wrapFiller.targetFiller.isMV()) {
            return DirectlyFillAction(generator, wrapFiller, decorativeAnnotations).wrappedRun(session)
        }
        return FillAction(generator, wrapFiller.targetFiller, decorativeAnnotations).wrappedRun(session)
    }
}

data class MarkFillerDone(val filler: AnnotatedWrapperFiller): StateAction {
    override fun run(session: UserSession): ActionResult {
        filler.markDone()
        return ActionResult(createLog("end filler for: ${filler.targetFiller.attribute}"))
    }
}

data class MarkFillerFilled(val filler: AnnotatedWrapperFiller): StateAction {
    override fun run(session: UserSession): ActionResult {
        filler.markFilled()
        return ActionResult(createLog("end filler for: ${filler.targetFiller.attribute}"))
    }
}

data class EndSlot(
    val frame: IFrame?, val slot: String?, val hard: Boolean) : StateAction {
    override fun run(session: UserSession): ActionResult {
        val wrapFiller = frame?.let { session.findWrapperFillerForTargetSlot(frame, slot) } ?: return ActionResult(
            createLog("cannot find filler for frame : ${if (frame != null) frame::class.qualifiedName else null}; slot: ${slot}"),
            true
        )
        return if (hard) MarkFillerDone(wrapFiller).wrappedRun(session) else MarkFillerFilled(wrapFiller).wrappedRun(session)
    }
}

class EndTopIntent : StateAction {
    override fun run(session: UserSession): ActionResult {
        val topFrameFiller = (session.schedule.firstOrNull() as? AnnotatedWrapperFiller)?.targetFiller as? FrameFiller<*>
        // find skills slot of main if there is one, we need a protocol to decide which intent to end
        if (topFrameFiller != null) {
            val currentSkill = (topFrameFiller.fillers["skills"]?.targetFiller as? MultiValueFiller<*>)?.findCurrentFiller()
            val currentIntent = ((currentSkill?.targetFiller as? InterfaceFiller<*>)?.vfiller?.targetFiller as? FrameFiller<*>)?.frame()
            if (currentSkill != null && currentIntent is IIntent) {
                return MarkFillerDone(currentSkill).wrappedRun(session)
            }
        }
        if (topFrameFiller != null && topFrameFiller.frame() is IIntent) {
            return MarkFillerDone((session.schedule.first() as AnnotatedWrapperFiller)).wrappedRun(session)
        }

        return ActionResult(null)
    }
}

abstract class AbstractAbortIntent(override var session: UserSession? = null) : IIntent {
    @JsonIgnore
    override fun annotations(path: String): List<Annotation> = when(path) {
        "intentType" -> listOf(RecoverOnly())
        else -> listOf()
    }

    var intentType: IEntity? = null
    var intent: IIntent? = null

    abstract val builder: (String) -> IEntity?

    override fun createBuilder() = object : FillBuilder {
        var frame: AbstractAbortIntent? = this@AbstractAbortIntent
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = ::frame
            val filler = FrameFiller({ tp }, path)
            filler.addWithPath(EntityFiller({tp.get()!!::intentType}, { s: String? -> intentType?.origValue = s}) { s -> builder(s) })
            return filler
        }
    }

    override fun searchResponse(): Action? {
        return when {
            else -> AbortIntentAction(this)
        }
    }

    open val defaultFailPrompt: (() -> DialogAct)? = null
    open val defaultSuccessPrompt: (() -> DialogAct)? = null
    open val defaultFallbackPrompt: (() -> DialogAct)? = null
    open val customizedSuccessPrompt: Map<String, () -> DialogAct> = mapOf()
}

data class AbortIntentAction(val frame: AbstractAbortIntent) : ChartAction {
    override fun run(session: UserSession): ActionResult {
        val specifiedQualifiedIntentName = frame.intentType?.value
        var targetFiller: AnnotatedWrapperFiller? = null
        val fillersNeedToPop = mutableSetOf<IFiller>()
        val prompts: MutableList<DialogAct> = mutableListOf()
        val mainScheduler = session.mainSchedule
        for (f in mainScheduler.reversed()) {
            fillersNeedToPop.add(f)
            if (f is AnnotatedWrapperFiller && f.targetFiller is FrameFiller<*> && f.targetFiller.frame() is IIntent && (specifiedQualifiedIntentName == null || specifiedQualifiedIntentName == f.targetFiller.qualifiedTypeStr())) {
                targetFiller = f
                break
            }
        }
        if (targetFiller != null) {
            // target intent found
            var abortedFiller: AnnotatedWrapperFiller? = null
            while (mainScheduler.isNotEmpty()) {
                val top = mainScheduler.peek()
                // we only abort child of Multi Value Filler or the root intent; aborting other intents is meaningless
                if (top !in fillersNeedToPop && top is MultiValueFiller<*> && top.abortCurrentChild()) {
                    break
                } else {
                    mainScheduler.pop()
                    if (top is AnnotatedWrapperFiller && top.targetFiller is FrameFiller<*> && top.targetFiller.frame() is IIntent) {
                        abortedFiller = top
                    }
                }
            }

            while (session.schedulers.size > 1) {
                session.schedulers.removeLast()
            }

            val targetIntent = (targetFiller.targetFiller as FrameFiller<*>).frame() as IIntent
            val targetIntentName = with(session) { targetIntent::class.qualifiedName!! }
            val abortIntent = (abortedFiller!!.targetFiller as FrameFiller<*>).frame() as IIntent
            val abortIntentName = with(session) { abortIntent::class.qualifiedName!! }
            frame.intent = abortIntent
            if (frame.customizedSuccessPrompt.containsKey(abortIntentName)) {
                prompts.add(frame.customizedSuccessPrompt[abortIntentName]!!())
            } else {
                if (frame.intentType == null || frame.intentType!!.value == abortIntentName) {
                    frame.defaultSuccessPrompt?.let {
                        prompts.add(it())
                    }
                } else {
                    if (frame.defaultFallbackPrompt != null) {
                        prompts.add(frame.defaultFallbackPrompt!!())
                    } else if (frame.defaultSuccessPrompt != null) {
                        prompts.add(frame.defaultSuccessPrompt!!())
                    }
                }
            }
        } else {
            frame.defaultFailPrompt?.let {
                prompts.add(it())
            }
        }
        return ActionResult(
            prompts,
            createLog(prompts.map { it.templates.pick() }.joinToString { it }), true)
    }
}

data class Confirmation(
    override var session: UserSession? = null,
    val target: IFrame?,
    val slot: String,
    val prompts: () -> DialogAct,
    val implicit: Boolean = false,
    val actions: List<Action>? = null): IIntent {

    override fun annotations(path: String): List<Annotation> = when(path) {
        "status" -> listOf(SlotPromptAnnotation(listOf(LazyAction(prompts))), ConditionalAsk(Condition { !implicit }))
        else -> listOf()
    }

    var status: io.opencui.core.confirmation.IStatus? = null

    override fun createBuilder() = object : FillBuilder {
        var frame: Confirmation? = this@Confirmation
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = ::frame
            val filler = FrameFiller({ tp }, path)
            filler.addWithPath(
                InterfaceFiller(
                    { tp.get()!!::status },
                    createFrameGenerator(tp.get()!!.session!!, io.opencui.core.confirmation.IStatus::class.qualifiedName!!)))
            return filler
        }
    }

    override fun searchResponse(): Action? = when {
        implicit -> prompts()
        status is io.opencui.core.confirmation.No && (target != null || actions != null) -> {
            if (actions != null) {
                SeqAction(actions)
            } else {
                val path = session!!.findActiveFillerPathByFrame(target!!)
                val targetFiller = (
                        if (slot.isNullOrEmpty() || slot == "this")
                            path.lastOrNull()
                        else
                            ((path.lastOrNull() as? AnnotatedWrapperFiller)?.targetFiller as? FrameFiller<*>)?.fillers?.get(slot)
                        ) as? AnnotatedWrapperFiller
                if (targetFiller != null) {
                    SeqAction(
                        CleanupAction(listOf(targetFiller)),
                        RefocusAction(path as List<ICompositeFiller>)
                    )
                } else {
                    null
                }
            }
        }
        else -> null
    }
}

data class FreeActionConfirmation(
    override var session: UserSession? = null,
    val confirmPrompts: () -> DialogAct,
    val actionPrompts: () -> DialogAct,
    val implicit: Boolean = false): IIntent {

    var status: io.opencui.core.confirmation.IStatus? = null
    var action: IIntent? = null

    override fun annotations(path: String): List<Annotation> = when(path) {
        "status" -> listOf(SlotPromptAnnotation(listOf(confirmPrompts())), ConditionalAsk(Condition { !implicit }))
        "action" -> listOf(
            SlotPromptAnnotation(listOf(actionPrompts())),
            ConditionalAsk(Condition { status is io.opencui.core.confirmation.No && !implicit })
        )
        else -> listOf()
    }

    override fun createBuilder() = object : FillBuilder {
        var frame: FreeActionConfirmation? = this@FreeActionConfirmation
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = ::frame
            val filler = FrameFiller({ tp }, path)
            filler.addWithPath(InterfaceFiller({ tp.get()!!::status }, createFrameGenerator(tp.get()!!.session!!, io.opencui.core.confirmation.IStatus::class.qualifiedName!!)))
            filler.addWithPath(InterfaceFiller({ tp.get()!!::action }, createFrameGenerator(tp.get()!!.session!!, io.opencui.core.IIntent::class.qualifiedName!!)))
            return filler
        }
    }

    override fun searchResponse(): Action? = when {
        implicit -> confirmPrompts()
        else -> null
    }
}

data class ValueCheck(
    override var session: UserSession? = null,
    val conditionActionPairs: List<Pair<()->Boolean, List<Action>>>): IIntent {
    constructor(session: UserSession?, checker: () -> Boolean, actions: List<Action>): this(session, listOf(Pair(checker, actions)))

    override fun createBuilder() = object : FillBuilder {
        var frame: ValueCheck? = this@ValueCheck
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = ::frame
            val filler = FrameFiller({ tp }, path)
            return filler
        }
    }

    override fun searchResponse(): Action? {
        var action: Action? = null
        for (p in conditionActionPairs) {
            if (!p.first()) {
                action = SeqAction(p.second)
                break
            }
        }
        return action
    }
}

data class OldValueCheck(
    override var session: UserSession? = null,
    val checker: () -> Boolean,
    val toBeCleaned: List<Pair<IFrame?, String?>>,
    val prompts: () -> DialogAct
): IIntent {
    override fun createBuilder() = object : FillBuilder {
        var frame: OldValueCheck? = this@OldValueCheck
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = ::frame
            val filler = FrameFiller({ tp }, path)
            return filler
        }
    }

    override fun searchResponse(): Action? = when {
        !checker() -> {
            val targetFillers = mutableListOf<AnnotatedWrapperFiller>()
            var refocusPath: LinkedList<IFiller> = LinkedList()
            for (clean in toBeCleaned) {
                val frame = clean.first!!
                val slot = clean.second
                val path = session!!.findActiveFillerPathForTargetSlot(frame, slot)
                val targetFiller = path.lastOrNull() as? AnnotatedWrapperFiller
                if (targetFiller != null) {
                    targetFillers += targetFiller
                    // calculate first slot in natural order to which we refocus
                    val step = min(refocusPath.size, path.size)
                    var index = 0
                    while (index < step) {
                        if (refocusPath[index] != path[index]) break
                        index++
                    }
                    if (index == 0 || index == step) {
                        if (path.size > refocusPath.size) {
                            refocusPath = path
                        }
                    } else {
                        val frameFiller = refocusPath[index-1] as? FrameFiller<*>
                        if (frameFiller != null) {
                            val ia = frameFiller.fillers.values.indexOf(refocusPath[index])
                            val ib = frameFiller.fillers.values.indexOf(path[index])
                            if (ib < ia) refocusPath = path
                        }
                    }
                }
            }
            if (targetFillers.isNotEmpty() && refocusPath.isNotEmpty()) {
                SeqAction(
                    prompts(),
                    CleanupAction(targetFillers),
                    RefocusAction(refocusPath as List<ICompositeFiller>)
                )
            } else {
                null
            }
        }
        else -> null
    }
}

data class MaxDiscardAction(
    val targetSlot: MutableList<*>, val maxEntry: Int
) : SchemaAction {
    override fun run(session: UserSession): ActionResult {
        val size = targetSlot.size
        if (size > maxEntry) {
            targetSlot.removeAll(targetSlot.subList(maxEntry, targetSlot.size))
        }
        return ActionResult(createLog("DISCARD mv entries that exceed max number, from $size entries to $maxEntry entries"), true)
    }
}

data class MaxValueCheck(
    override var session: UserSession? = null,
    val targetSlotGetter: () -> MutableList<*>?,
    val maxEntry: Int,
    val prompts: () -> DialogAct
): IIntent {
    val targetSlot: MutableList<*>?
        get() {
            return targetSlotGetter()
        }

    override fun createBuilder() = object : FillBuilder {
        var frame: MaxValueCheck? = this@MaxValueCheck
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = ::frame
            val filler = FrameFiller({ tp }, path)
            return filler
        }
    }

    override fun searchResponse(): Action? {
        return when {
            targetSlot != null && targetSlot!!.size > maxEntry -> SeqAction(
                prompts(),
                MaxDiscardAction(targetSlot!!, maxEntry)
            )
            else -> null
        }
    }
}

data class Ordinal(
    @get:JsonIgnore var value: String
): Serializable {
    var origValue: String? = null
    @JsonValue
    override fun toString(): String = value

    fun name(): String {
        val v = value.toInt()
        val remByHundred = v % 100
        if (remByHundred == 11 || remByHundred == 12 || remByHundred == 13) return "${v}th"
        return when (v%10) {
            1 -> "${v}st"
            2 -> "${v}nd"
            3 -> "${v}rd"
            else -> "${v}th"
        }
    }
}

data class NextPage(override var session: UserSession? = null) : IBotMode, IFrame {
    override fun createBuilder() = object : FillBuilder {
        var frame: NextPage? = this@NextPage
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = ::frame
            val filler = FrameFiller({ tp }, path)
            return filler
        }
    }
}

data class PreviousPage(override var session: UserSession? = null) : IBotMode, IFrame {
    override fun createBuilder() = object : FillBuilder {
        var frame: PreviousPage? = this@PreviousPage
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = ::frame
            val filler = FrameFiller({ tp }, path)
            return filler
        }
    }
}

data class FilterCandidate(override var session: UserSession? = null) : IBotMode, IFrame {
    var conditionMapJson: String? = null
    override fun annotations(path: String): List<Annotation> = when(path) {
        "conditionMapJson" -> listOf(RecoverOnly())
        else -> listOf()
    }

    override fun createBuilder() = object : FillBuilder {
        var frame: FilterCandidate? = this@FilterCandidate
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = ::frame
            val filler = FrameFiller({ tp }, path)
            filler.addWithPath(EntityFiller({tp.get()!!::conditionMapJson}) { s -> Json.decodeFromString(s) })
            return filler
        }
    }
}

// We might need to make sure we clean the index slot the right way.
data class AuxiliaryChange(override var session: UserSession? = null) : IBotMode, IFrame {
    override fun annotations(path: String): List<Annotation> = when(path) {
        else -> listOf()
    }

    override fun createBuilder() = object : FillBuilder {
        var frame: AuxiliaryChange? = this@AuxiliaryChange
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = ::frame
            val filler = FrameFiller({ tp }, path)
            return filler
        }
    }
}

abstract class ValueRecSourceWrapper(override var session: UserSession? = null) : IIntent {
    override fun createBuilder() = object : FillBuilder {
        var frame: ValueRecSourceWrapper? = this@ValueRecSourceWrapper
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = ::frame
            val filler = FrameFiller({ tp }, path)
            return filler
        }
    }
}

data class BadCandidate<T>(override var session: UserSession? = null, var value: T?) : IIntent {
    override fun createBuilder() = object : FillBuilder {
        var frame: BadCandidate<T>? = this@BadCandidate
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = ::frame
            val filler = FrameFiller({ tp }, path)
            return filler
        }
    }
}

data class BadIndex(override var session: UserSession? = null, var index: Int) : IIntent {
    override fun createBuilder() = object : FillBuilder {
        var frame: BadIndex? = this@BadIndex
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = ::frame
            val filler = FrameFiller({ tp }, path)
            return filler
        }
    }
}

enum class CompanionType {
    AND, NEGATE, OR, LESSTHAN, LESSTHANEQUALTO, GREATERTHAN, GREATERTHANQUALTO
}

data class Helper<T> (
    val not: MutableList<T> = mutableListOf(),
    val or: MutableList<T> = mutableListOf(),
    val lessThan: MutableList<T> = mutableListOf(),
    val greaterThan: MutableList<T> = mutableListOf(),
    val lessThanEqualTo: MutableList<T> = mutableListOf(),
    val greaterThanEqualTo: MutableList<T> = mutableListOf()) {

    fun clear() {
        not.clear()
        or.clear()
        lessThan.clear()
        greaterThan.clear()
        lessThanEqualTo.clear()
        greaterThanEqualTo.clear()
    }
}

// This turns a closure with receiver
fun <T, P> bindReceiver1(lambda: T.(P) -> Boolean, t: T?): (P) -> Boolean = { p -> t == null || t.lambda(p) }

data class Negate<T>(val filters: List<(T)->Boolean>) : (T) -> Boolean {
    constructor(vararg fs: T): this ( fs.map { f -> { it : T -> it != f} } )
    override fun invoke(t: T): Boolean {
        for (filter in filters) {
            if (filter(t)) return false
        }
        return true
    }
}

data class Or<T>(val filters: List<(T)->Boolean>) : (T) -> Boolean {
    constructor(vararg fs: T): this ( fs.map { f -> { it : T -> it == f} } )
    override fun invoke(t: T): Boolean {
        if (filters.isEmpty()) return true
        for (filter in filters) {
            if (filter(t)) return true
        }
        return false
    }
}

data class And<T>(val filters: List<(T) -> Boolean>): (T) -> Boolean {
    constructor(vararg fs: (T) -> Boolean) : this(fs.toList())
    override fun invoke(p1: T): Boolean {
        for (filter in filters) {
            if (!filter(p1)) return false
        }
        return true
    }
}

data class LessThan<T: Comparable<T>>(val min: T?) : (T) -> Boolean {
    override fun invoke(p1: T): Boolean {
        return min == null || p1 < min
    }
}

data class LessThanEqualTo<T: Comparable<T>>(val min: T?) : (T) -> Boolean {
    override fun invoke(p1: T): Boolean {
        return min == null || p1 <= min
    }
}

data class GreaterThan<T: Comparable<T>>(val max: T?) : (T) -> Boolean {
    override fun invoke(p1: T): Boolean {
        return max == null || p1 > max
    }
}

data class GreaterThanEqualTo<T: Comparable<T>>(val max: T?) : (T) -> Boolean {
    override fun invoke(p1: T): Boolean {
        return max == null || p1 >= max
    }
}

object ValueFilterBuilder{
    fun <T, P: Comparable<P>> build(
        test: T.(P) -> Boolean,
        originalValue: T?,
        companions: Map<CompanionType, List<T>?>): (P) -> Boolean {
        val filters = mutableListOf<(P)->Boolean>()
        filters.add(bindReceiver1(test, originalValue))
        companions.map{
            if (it.value != null) {
                when (it.key) {
                    CompanionType.AND -> filters.add(And(it.value!!.map { itt -> bindReceiver1(test, itt) }))
                    CompanionType.OR -> filters.add(Or(it.value!!.map { itt -> bindReceiver1(test, itt) }))
                    CompanionType.NEGATE -> filters.add(Negate(it.value!!.map { itt -> bindReceiver1(test, itt) }))
                    else -> throw RuntimeException("Auxiliary slot only support ")
                }
            }
        }
        return And(filters)
    }

    fun <T, P: Comparable<P>> build(
        test: T.(P) -> Boolean,
        originalValue: T?,
        helper: Helper<T>?= null): (P) -> Boolean {
        val filters = mutableListOf<(P)->Boolean>()
        filters.add(bindReceiver1(test, originalValue))
        if (helper != null) {
            if (helper.not.isNotEmpty()) filters.add(Negate(helper.not.map { itt -> bindReceiver1(test, itt) }))
            if (helper.or.isNotEmpty()) filters.add(Or(helper.or.map { itt -> bindReceiver1(test, itt) }))
        }
        return And(filters)
    }


    fun <P: Comparable<P>> build(companions: Map<CompanionType, List<P>?>): (P) -> Boolean {
        val filters = mutableListOf<(P) -> Boolean>()
        companions.map {
            if (it.value != null) {
                when (it.key) {
                    CompanionType.OR -> filters.add(Or(it.value))
                    CompanionType.NEGATE -> filters.add(Negate(it.value))
                    CompanionType.LESSTHAN -> filters.add(LessThan(it.value!!.minOrNull()))
                    CompanionType.LESSTHANEQUALTO -> filters.add(LessThan(it.value!!.minOrNull()))
                    CompanionType.GREATERTHAN -> filters.add(GreaterThan(it.value!!.maxOrNull()))
                    CompanionType.GREATERTHANQUALTO -> filters.add(GreaterThanEqualTo(it.value!!.maxOrNull()))
                    else -> throw RuntimeException("Auxiliary slot only support ")
                }
            }
        }
        return And(filters)
    }

    fun <P: Comparable<P>> build(helper: Helper<P>?): (P) -> Boolean {
        val filters = mutableListOf<(P) -> Boolean>()
        if (helper != null) {
            if (helper.or.isNotEmpty()) filters.add(Or(helper.or))
            if (helper.not.isNotEmpty()) filters.add(Negate(helper.not))
            if (helper.lessThan.isNotEmpty()) filters.add(LessThan(helper.lessThan.minOrNull()))
            if (helper.lessThanEqualTo.isNotEmpty()) filters.add(LessThan(helper.lessThanEqualTo.minOrNull()))
            if (helper.greaterThan.isNotEmpty()) filters.add(GreaterThan(helper.greaterThan.maxOrNull()))
            if (helper.greaterThanEqualTo.isNotEmpty()) filters.add(GreaterThanEqualTo(helper.greaterThan.maxOrNull()))
        }
        return And(filters)
    }
}

data class PagedSelectable<T: Any> (
    override var session: UserSession? = null,
    var suggestionIntentBuilder: FullFrameBuilder?,
    val kClass: () -> KClass<T>,
    var promptTemplate: (List<T>) -> DialogAct,
    var pageSize: Int = 5,
    var target: IFrame? = null,
    var slot: String? = null,
    var hard: Boolean = false,
    @JsonIgnore var zeroEntryActions: List<Action> = listOf(),
    @JsonIgnore var valueOutlierPrompt: ((BadCandidate<T>) -> DialogAct)? = null,
    @JsonIgnore var indexOutlierPrompt: ((BadIndex) -> DialogAct)? = null,
    @JsonIgnore var singleEntryPrompt: ((T) -> DialogAct)? = null,
    @JsonIgnore var implicit: Boolean = false,
    @JsonIgnore var autoFillSwitch: () -> Boolean = {true},
    @JsonIgnore var candidateListProvider: (() -> List<T>)? = null
): IIntent {
    // So that we can use the old construction.
    constructor(
        session: UserSession? = null,
        valuesProvider: (() -> List<T>)? = null,
        kClass: () -> KClass<T>,
        promptTemplate: (List<T>) -> DialogAct,
        pageSize: Int = 5,
        target: IFrame? = null,
        slot: String? = null,
        hard: Boolean = false,
        zeroEntryActions: List<Action> = listOf(),
        valueOutlierPrompt: ((BadCandidate<T>) -> DialogAct)? = null,
        indexOutlierPrompt: ((BadIndex) -> DialogAct)? = null,
        singleEntryPrompt: ((T) -> DialogAct)? = null,
        implicit: Boolean = false,
        autoFillSwitch: () -> Boolean = {true}
    ) : this (session, null, kClass, promptTemplate, pageSize, target, slot, hard, zeroEntryActions, valueOutlierPrompt, indexOutlierPrompt, singleEntryPrompt, implicit, autoFillSwitch, valuesProvider)

    init {
        // make one of them are true
        assert((candidateListProvider == null).xor(suggestionIntentBuilder == null))
    }

    val sepConfirm by lazy {
        singleEntryPrompt?.let {
            Confirmation(
                session, this, "index",
                {it(pick()!!)},
                implicit, actions = zeroEntryActions.filter { it !is DialogAct })
        }
    }

    var suggestionIntent: IIntent? = suggestionIntentBuilder?.invoke(session!!) as IIntent?

    val candidatesRaw : List<T>
        get() =  if (suggestionIntentBuilder != null) {
            getPropertyValueByReflection(suggestionIntent!!, "result") as? List<T> ?: listOf()
        } else {
            candidateListProvider!!()
        }

    val candidates: List<T>
        get() { return candidatesRaw.filter(matcher) }


    val lastPage: Int
        get() = candidates.size / pageSize - if (candidates.size % pageSize == 0) 1 else 0

    var page: Int? = null

    var index: Ordinal? = null

    val payload: List<T>
        get() {
            val p = if (page == null) 0 else page!!
            return candidates.subList(p * pageSize, min((p + 1) * pageSize, candidates.size)).toList()
        }

    var conditionMap: ObjectNode? = null
    private val matcher: (T) -> Boolean = { t: T ->
        if (conditionMap == null) {
            true
        } else {
            if (t is IFrame) {
                var res = true
                for (entry in conditionMap!!.fields()) {
                    val target: String? = if (entry.key == "@class") {
                        Json.encodeToString(t!!::class.qualifiedName!!)
                    } else {
                        val memberCallable = t!!::class.members.firstOrNull { it.name == entry.key }
                        memberCallable?.call(t)?.let {
                            Json.encodeToString(it)
                        }
                    }
                    if (!entry.value.isArray) {
                        res = false
                        break
                    }
                    res = res && (entry.value as ArrayNode).firstOrNull { target == it.toString() } != null
                    if (!res) break
                }
                res
            } else {
                if (conditionMap!!.size() >= 2) {
                    var values: ArrayNode? = null
                    for (f in conditionMap!!.fieldNames()) {
                        if (f == "@class") continue
                        values = conditionMap!!.get(f) as? ArrayNode
                        break
                    }
                    values?.firstOrNull { Json.encodeToString(t as Any) == it.toString() } != null
                } else {
                    true
                }
            }
        }
    }

    fun nextPage(): Int {
        var p = if (page == null) 1 else page!! + 1
        if (p > lastPage) p = 0
        return p
    }

    fun prevPage(): Int {
        var p = if (page == null) -1 else page!! - 1
        if (p < 0) p = lastPage
        return p
    }

    fun select(conditionMapJson: String): String {
        val condition = if (conditionMap == null) {
            ObjectNode(JsonNodeFactory.instance)
        } else {
            conditionMap!!.deepCopy()
        }
        val additionalConditionMap = Json.parseToJsonElement(conditionMapJson)
        if (additionalConditionMap.isObject) {
            for (entry in additionalConditionMap.fields()) {
                if (!entry.value.isArray) continue
                if ((entry.value as ArrayNode).size() == 0) {
                    condition!!.remove(entry.key)
                    continue
                }
                if (condition!!.has(entry.key)) {
                    condition.replace(entry.key, entry.value)
                } else {
                    condition.set(entry.key, entry.value)
                }
            }
        }
        return condition.toString()
    }

    fun isConditionEmpty(): Boolean {
        return conditionMap == null || conditionMap!!.isEmpty
    }

    fun pickWithIndex(index: Int): T? {
        return payload[index - 1]
    }

    fun pick(): T? = pickWithIndex(index!!.value.toInt())

    fun constructUserChoice(): ObjectNode? {
        val obj = ObjectNode(JsonNodeFactory.instance)
        return if (!isConditionEmpty()) {
            for (entry in conditionMap!!.fields()) {
                val arr = entry.value as? ArrayNode
                if (arr != null && arr.size() == 1) {
                    obj.replace(entry.key, arr[0])
                }
            }
            obj
        } else {
            null
        }
    }

    fun generateCandidate(): Any? {
        return if (candidates.isEmpty()) {
            constructUserChoice()
        } else {
            pick()
        }
    }

    fun singleEntryAutoFill(): Boolean {
        return candidates.size == 1 && isConditionEmpty() && autoFillSwitch() && hard
    }

    fun shrinkToSingleEntry(): Boolean {
        return candidates.size == 1 && !isConditionEmpty()
    }

    fun outlierValue(): Boolean {
        return candidates.isEmpty() && !isConditionEmpty() && hard
    }

    fun generateAutoFillIndex(): Ordinal? {
        return if (singleEntryAutoFill() || shrinkToSingleEntry()) Ordinal("1")
                else if (outlierValue()) Ordinal("-1")
                else null
    }

    fun isIndexValid(): Boolean {
        val size = payload.size
        val indexValue = index?.value?.toIntOrNull()
        return indexValue != null && indexValue >= 1 && indexValue <= size
    }

    fun findTargetFiller(): AnnotatedWrapperFiller? {
        if (target == null) return null
        return session!!.findWrapperFillerForTargetSlot(target!!, slot)
    }

    @JsonIgnore
    val _check_index = ValueCheck(session, {isIndexValid()}, listOf(LazyAction {
        if (outlierValue())
            SeqAction(
                convertDialogAct(
                    BadCandidate(session, Json.getConverter(session, kClass().java).invoke(constructUserChoice())),
                    valueOutlierPrompt!!
                )(),
                ReinitActionBySlot(listOf(Pair(this, "index"))),
                CleanupActionBySlot(listOf(Pair(this, "page"), Pair(this, "conditionMap"), Pair(this, "index"))))
        else SeqAction(
            convertDialogAct(BadIndex(session, index!!.value.toInt()), indexOutlierPrompt!!)(),
            ReinitActionBySlot(listOf(Pair(this, "index"))),
            CleanupActionBySlot(listOf(Pair(this, "index"))))
    }))

    override fun annotations(path: String): List<Annotation> = when(path) {
        "index" -> listOf(
            SlotPromptAnnotation(listOf(LazyAction(convertDialogActGen({ payload }, promptTemplate)))),
            ConditionalAsk(Condition { candidates.isNotEmpty() || outlierValue() }),
            SlotInitAnnotation(FillActionBySlot({ generateAutoFillIndex() }, this, "index")),
            ValueCheckAnnotation({_check_index}),
            ConfirmationAnnotation { searchConfirmation("index") }
        )
        "conditionMap" -> listOf(NeverAsk())
        "page" -> listOf(NeverAsk())
        else -> listOf()
    }

    override fun searchConfirmation(path: String): IFrame? {
        return when (path) {
            "index" -> if (singleEntryAutoFill() && singleEntryPrompt != null) sepConfirm else null
            else -> null
        }
    }

    override fun searchStateUpdateByEvent(event: String): IFrameBuilder? {
        val nextPage = NextPage(session)
        val previousPage = PreviousPage(session)
        val filterCandidate = FilterCandidate(session)
        val auxiliaryChange = AuxiliaryChange(session)
        // println("Seeing event: ${event} with index = $index  and Candidate")
        return when (event) {
            NEXTPAGE -> intentBuilder<NextPage>(
                nextPage,
                listOf(
                    UpdateRule({ with(nextPage) { true } },
                        DirectlyFillActionBySlot({ with(nextPage) { nextPage() } }, this, "page")
                    )
                )
            )
            PREVIOUSPAGE -> intentBuilder<PreviousPage>(
                previousPage,
                listOf(
                    UpdateRule({ with(previousPage) { true } },
                        DirectlyFillActionBySlot({ with(previousPage) { prevPage() } }, this, "page")
                    )
                )
            )
            FILTERCANDIDATE -> intentBuilder<FilterCandidate>(
                filterCandidate,
                listOf(
                    UpdateRule({ with(filterCandidate) { conditionMapJson != null } },
                        SeqAction(
                            DirectlyFillActionBySlot(
                                { with(filterCandidate) { Json.decodeFromString<ObjectNode>(select(conditionMapJson!!)) } },
                                this,
                                "conditionMap"
                            ),
                            DirectlyFillActionBySlot({ with(filterCandidate) { 0 } }, this, "page"),
                            ReinitActionBySlot(listOf(Pair(this, "index"))),
                            CleanupActionBySlot(listOf(Pair(this, "index")))
                        )
                    )
                )
            )
            AUXILIARYCHANGE -> intentBuilder<AuxiliaryChange>(
                auxiliaryChange,
                listOf(
                    UpdateRule({ with(auxiliaryChange) { true } },
                        SeqAction(
                            ReinitActionBySlot(listOf(Pair(this, "index"))),
                            CleanupActionBySlot(listOf(Pair(this, "index")))
                        )
                    )
                )
            )
            else -> null
        }
    }

    override fun searchResponse(): Action? {
        val candidate = generateCandidate()
        return when {
            candidates.isEmpty() && isConditionEmpty() && hard ->
                SeqAction(zeroEntryActions)
            candidate != null ->
                SeqAction(FillAction({candidate}, findTargetFiller()!!.targetFiller, listOf<Annotation>()))
            else -> null
        }
    }

    override fun createBuilder() = object: FillBuilder {
        var frame: PagedSelectable<T>? = this@PagedSelectable
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = ::frame
            val filler = FrameFiller({ tp }, path)

            with(filler) {
                if (suggestionIntentBuilder != null) {
                    val suggestIntentFiller =
                        suggestionIntent!!.createBuilder().invoke(path.join("suggestionIntent", suggestionIntent)) as FrameFiller<*>
                    add(suggestIntentFiller)
                    //customize suggestion intent filler
                    fillers["suggestionIntent"]!!.disableResponse()
                    suggestionIntentBuilder!!.init(session!!, suggestIntentFiller as FrameFiller<*>)
                }
                addWithPath(EntityFiller({tp.get()!!::page}) { s -> Json.decodeFromString(s) })
                addWithPath(EntityFiller({tp.get()!!::conditionMap}) { s ->
                    val s1 = Json.decodeFromString<String>(s)
                    Json.decodeFromString(s1)
                })
                addWithPath(EntityFiller({tp.get()!!::index}) { s -> Json.decodeFromString(s) })
            }
            return filler
        }
    }

    companion object {
        val NEXTPAGE = io.opencui.core.NextPage::class.qualifiedName!!
        val PREVIOUSPAGE = io.opencui.core.PreviousPage::class.qualifiedName!!
        val FILTERCANDIDATE = io.opencui.core.FilterCandidate::class.qualifiedName!!
        val AUXILIARYCHANGE = io.opencui.core.AuxiliaryChange::class.qualifiedName!!
    }
}

abstract class AbstractValueClarification<T: Any>(
    override var session: UserSession? = null,
    open val getClass: () -> KClass<T>,
    open val source: List<T>,
    open var targetFrame: IFrame,
    open var slot: String): IIntent {

    abstract var target: T?
    abstract fun _rec_target(it: T?): PagedSelectable<T>


    fun targetSlotAlias(): String {
        return session!!.chatbot!!.duMeta.getSlotMetas(targetFrame::class.qualifiedName!!).firstOrNull { it.label == slot }?.triggers?.firstOrNull() ?: ""
    }

    fun normalize(t: T): String {
        return session!!.chatbot!!.duMeta.getEntityInstances(t::class.qualifiedName!!)[t.toString()]?.firstOrNull() ?: t.toString()
    }

    fun findTargetFiller(): AnnotatedWrapperFiller? {
        return session!!.findWrapperFillerForTargetSlot(targetFrame, slot)
    }

    override fun createBuilder() = object : FillBuilder {
        var frame: AbstractValueClarification<T>? = this@AbstractValueClarification
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = ::frame
            val filler = FrameFiller({ tp }, path)
            when {
                getClass().isSubclassOf(IFrame::class) -> {
                    val cFiller = (target as IFrame).createBuilder().invoke(path.join("target", target as IFrame)) as FrameFiller<*>
                    filler.add(cFiller)
                }
                getClass().isAbstract -> {
                    val cFiller = InterfaceFiller({ tp.get()!!::target }, createFrameGenerator(tp.get()!!.session!!, getClass().qualifiedName!!))
                    filler.addWithPath(cFiller)
                }
                else -> {
                    val cFiller = EntityFiller({ tp.get()!!::target }) { s -> Json.decodeFromString(s, getClass())}
                    filler.addWithPath(cFiller)
                }
            }
            return filler
        }
    }

    override fun searchResponse(): Action? = when {
        else -> FillAction({ target }, findTargetFiller()!!.targetFiller, listOf<Annotation>())
    }
}

data class SlotType(@get:JsonIgnore override var value: String) : IEntity, Serializable {
    @JsonIgnore var session: UserSession? = null
    @JsonIgnore override var origValue: String? = null
    @JsonValue
    override fun toString() : String = value
}


// This is currently used for both single value and multi value slots.
abstract class SlotCrudBase<T: Any>(override var session: UserSession? = null): IIntent {
    var originalSlot: SlotType? = null
    var oldValue: T? = null
    var newValue: T? = null
    var index: Ordinal? = null // for multi-value slot update
    var originalValue: T? = null
    var confirm: io.opencui.core.confirmation.IStatus? = null

    fun buildT(s: String): T {
        val slotFiller = findOriginalSlotFiller()?.targetFiller
        val type = if (slotFiller is EntityFiller<*>) slotFiller.qualifiedTypeStr() else if (slotFiller is MultiValueFiller<*>) slotFiller.qualifiedTypeStrForSv() else null
        return (if (type != null) Json.decodeFromString(s, session!!.findKClass(type)!!) else Json.decodeFromString<String>(s)) as T
    }

    fun originalValue(): T? {
        return originalValue
    }

    fun originalValueInit(): T? {
        val f = findTargetFiller()?.targetFiller as? TypedFiller<T>
        return f?.target?.get()
    }

    fun isMV(): Boolean {
        return findOriginalSlotFiller()?.targetFiller is MultiValueFiller<*>
    }

    fun findIndexCandidates(ordinal: Ordinal?): List<Ordinal> {
        if (ordinal != null) return listOf(ordinal)
        val res = mutableListOf<Ordinal>()
        val fillers = (findOriginalSlotFiller()!!.targetFiller as MultiValueFiller<*>).fillers
        for (iv in fillers.withIndex()) {
            if (iv.value.done(emptyList()) &&
                (oldValue == null ||
                        oldValue == (iv.value.targetFiller as? EntityFiller<*>)?.target?.get())) {
                res += Ordinal((iv.index + 1).toString())
            }
        }
        return res
    }

    fun validateSlotIndex(): Boolean {
        return (findOriginalSlotFiller()!!.targetFiller as MultiValueFiller<*>).fillers.size >= index!!.value.toInt()
    }

    fun getValueByIndex(index: Ordinal): T? {
        val targetFiller = findOriginalSlotFiller()?.targetFiller
        if (targetFiller !is MultiValueFiller<*>) return null
        if (targetFiller.fillers.size < index.value.toInt()) return null
        return (targetFiller.fillers[index.value.toInt()-1].targetFiller as TypedFiller<T>).target.get()
    }

    fun findTargetFiller(): AnnotatedWrapperFiller? {
        val f = findOriginalSlotFiller()
        if (f?.targetFiller !is MultiValueFiller<*>) return f
        val mvf = f.targetFiller as MultiValueFiller<*>
        if (index == null || index!!.value.toInt() <= 0 || mvf.fillers.size < index!!.value.toInt()) return null
        return mvf.fillers.get(index!!.value.toInt() - 1)
    }

    fun findOriginalSlotFiller(): AnnotatedWrapperFiller? {
        if (originalSlot == null) return null
        val filter = { f: IFiller ->
            val param = f.path!!.path.last()
            val slotName = if (param.isRoot()) param.host::class.qualifiedName!! else "${param.host::class.qualifiedName}.${param.attribute}"
            f is AnnotatedWrapperFiller && originalSlot?.value == slotName
        }
        var topFiller = session!!.mainSchedule.firstOrNull() as? AnnotatedWrapperFiller
        if (((topFiller?.targetFiller as? FrameFiller<*>)?.fillers?.get("skills")?.targetFiller as? MultiValueFiller<*>)?.findCurrentFiller() != null) {
            topFiller = ((topFiller.targetFiller as FrameFiller<*>).fillers["skills"]!!.targetFiller as MultiValueFiller<*>).findCurrentFiller()
        }
        val candidate = session!!.findFillerPath(topFiller, filter)
        return candidate.lastOrNull() as? AnnotatedWrapperFiller
    }

    fun needConfirm(): Boolean {
        return oldValue != null && originalValue() != null && oldValue.toString() != originalValue().toString()
    }

    // informNewValuePrompt and askNewValuePrompt are used in other frames, so they need to be locked before SlotUpdate ends
    abstract val informNewValuePrompt: () -> DialogAct
    abstract val askNewValuePrompt: () -> DialogAct
    abstract val oldValueDisagreePrompt: () -> DialogAct
    abstract val doNothingPrompt: () -> DialogAct
    abstract val askIndexPrompt: () -> DialogAct
    abstract val wrongIndexPrompt: () -> DialogAct
    abstract val indexRecPrompt: (List<Ordinal>) -> DialogAct

    fun genNewValueConfirmAnnotation(): ConfirmationAnnotation {
        // we need to lock the prompt here to avoid this SlotUpdate being cleared
        val informNewValueDialogAct = informNewValuePrompt()
        val confirmFrame = Confirmation(session, this, "newValue", {informNewValueDialogAct}, implicit = true)
        return ConfirmationAnnotation { confirmFrame }
    }
    fun genPromptAnnotation(): SlotPromptAnnotation {
        // we need to lock the prompt here to avoid this SlotUpdate being cleared
        val slotPromptDialogAct = askNewValuePrompt()
        return SlotPromptAnnotation(listOf(slotPromptDialogAct))
    }

    val _check_index by lazy {
        OldValueCheck(session, {validateSlotIndex()}, listOf(Pair(this, "index")),
                wrongIndexPrompt)
    }

    val _rec_index = {it: Ordinal? ->
        PagedSelectable(session,
            {findIndexCandidates(it)},
            {Ordinal::class},
            indexRecPrompt,
            target = this, slot = "index", hard = true, zeroEntryActions = listOf(EndSlot(this, "index", true)))
    }

    override fun annotations(path: String): List<Annotation> = when(path) {
        "originalSlot" -> listOf(NeverAsk())
        "oldValue" -> listOf(NeverAsk())
        "newValue" -> listOf(NeverAsk())
        "index" -> listOf(
            ConditionalAsk(Condition { isMV() }),
            ValueCheckAnnotation({_check_index}),
            TypedValueRecAnnotation<Ordinal>({_rec_index(this)}),
            SlotPromptAnnotation(listOf(LazyAction(askIndexPrompt))))
        "originalValue" -> listOf(NeverAsk(), SlotInitAnnotation(DirectlyFillActionBySlot({originalValueInit()},  this, "originalValue")))
        "confirm" -> listOf(
            ConditionalAsk(Condition { needConfirm() }),
            SlotPromptAnnotation(listOf(LazyAction(oldValueDisagreePrompt))))
        else -> listOf()
    }

    override fun createBuilder() = object : FillBuilder {
        var frame: SlotCrudBase<T>? = this@SlotCrudBase
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = ::frame
            val filler = FrameFiller({ tp }, path)
            val originalSlotFiller = EntityFiller({tp.get()!!::originalSlot}) { s -> Json.decodeFromString<SlotType>(s).apply { this.session = this@SlotCrudBase.session } }
            filler.addWithPath(originalSlotFiller)
            val oFiller = EntityFiller({tp.get()!!::oldValue}) { s -> buildT(s)}
            filler.addWithPath(oFiller)
            val nFiller = EntityFiller({tp.get()!!::newValue}) { s -> buildT(s)}
            filler.addWithPath(nFiller)
            val iFiller = EntityFiller({tp.get()!!::index}) { s -> Json.decodeFromString(s)}
            filler.addWithPath(iFiller)
            val originalValueFiller = EntityFiller({tp.get()!!::originalValue}) { s -> buildT(s)}
            filler.addWithPath(originalValueFiller)
            filler.addWithPath(
                InterfaceFiller({ tp.get()!!::confirm }, createFrameGenerator(tp.get()!!.session!!, io.opencui.core.confirmation.IStatus::class.qualifiedName!!)))
            return filler
        }
    }
}


abstract class AbstractSlotUpdate<T: Any>(override var session: UserSession? = null): SlotCrudBase<T>(session) {
    override fun searchResponse(): Action? = when {
        confirm !is io.opencui.core.confirmation.No -> {
            val filler = findTargetFiller()
            if (filler == null) {
                doNothingPrompt()
            } else {
                var path: List<IFiller> = listOf()
                for (s in session!!.schedulers) {
                    path = session!!.findFillerPath(s.firstOrNull(), { it == filler })
                    if (path.isNotEmpty()) break
                }
                if (newValue == null) {
                    val promptAnnotation = genPromptAnnotation()
                    SeqAction(
                        CleanupAction(listOf(filler)),
                        UpdatePromptAction(filler, promptAnnotation),
                        RefocusAction(path as List<ICompositeFiller>)
                    )
                } else {
                    val newValueConfirmFrameAnnotation = genNewValueConfirmAnnotation()
                    SeqAction(
                        FillAction({ TextNode(newValue.toString()) },
                            filler.targetFiller,
                            listOf(newValueConfirmFrameAnnotation)),
                        CleanupAction(listOf(filler)),
                        RefocusAction(path as List<ICompositeFiller>)
                    )
                }
            }
        }
        else -> null
    }
}

// For single valued slot, do we allow them to delete? (Maybe change from choice A to doesn't care?, only in
// rare condition, typically, if user want to change, they will change to another choice, so it will be slotupdate)
// this is used to delete item in the multivalued slot.
abstract class AbstractSlotDelete<T: Any>(override var session: UserSession? = null): SlotCrudBase<T>(session) {
    override fun searchResponse(): Action? = when {
        confirm !is io.opencui.core.confirmation.No -> {
            val filler = findTargetFiller()
            if (filler == null) {
                doNothingPrompt()
            } else {
                var path: List<IFiller> = listOf()
                for (s in session!!.schedulers) {
                    path = session!!.findFillerPath(s.firstOrNull(), { it == filler })
                    if (path.isNotEmpty()) break
                }
                if (newValue == null) {
                    val promptAnnotation = genPromptAnnotation()
                    SeqAction(
                        CleanupAction(listOf(filler)),
                        UpdatePromptAction(filler, promptAnnotation),
                        RefocusAction(path as List<ICompositeFiller>)
                    )
                } else {
                    val newValueConfirmFrameAnnotation = genNewValueConfirmAnnotation()
                    SeqAction(
                        FillAction({ TextNode(newValue.toString()) },
                            filler.targetFiller,
                            listOf(newValueConfirmFrameAnnotation)),
                        CleanupAction(listOf(filler)),
                        RefocusAction(path as List<ICompositeFiller>)
                    )
                }
            }
        }
        else -> null
    }
}


// This is used for append new item to multi value slot.
abstract class AbstractSlotAppend<T: Any>(override var session: UserSession? = null): SlotCrudBase<T>(session) {
    override fun searchResponse(): Action? = when {
        confirm !is io.opencui.core.confirmation.No -> {
            val filler = findTargetFiller()
            if (filler == null) {
                doNothingPrompt()
            } else {
                var path: List<IFiller> = listOf()
                for (s in session!!.schedulers) {
                    path = session!!.findFillerPath(s.firstOrNull(), { it == filler })
                    if (path.isNotEmpty()) break
                }
                if (newValue == null) {
                    val promptAnnotation = genPromptAnnotation()
                    SeqAction(
                        CleanupAction(listOf(filler)),
                        UpdatePromptAction(filler, promptAnnotation),
                        RefocusAction(path as List<ICompositeFiller>)
                    )
                } else {
                    val newValueConfirmFrameAnnotation = genNewValueConfirmAnnotation()
                    SeqAction(
                        FillAction({ TextNode(newValue.toString()) },
                            filler.targetFiller,
                            listOf(newValueConfirmFrameAnnotation)),
                        CleanupAction(listOf(filler)),
                        RefocusAction(path as List<ICompositeFiller>)
                    )
                }
            }
        }
        else -> null
    }
}


data class UpdatePromptAction(
    val wrapperTarget: AnnotatedWrapperFiller?,
    val prompt: SlotPromptAnnotation): SchemaAction {
    override fun run(session: UserSession): ActionResult {
        wrapperTarget?.targetFiller?.decorativeAnnotations?.clear()
        wrapperTarget?.targetFiller?.decorativeAnnotations?.add(prompt)
        return ActionResult(createLog("UPDATED PROMPTS for filler ${wrapperTarget?.attribute}"), true)
    }
}

data class PhoneNumber(
    @get:JsonIgnore
    override var value: String
) : IEntity {
    override var origValue: String? = null

    @JsonValue
    override fun toString(): String = value

    companion object {
        @JsonIgnore
        val valueGood: ((String) -> Boolean)? = { true }
    }
}

data class Email(
    @get:JsonIgnore
    override var value: String
) : IEntity {
    override var origValue: String? = null

    @JsonValue
    override fun toString(): String = value

    companion object {
        @JsonIgnore
        val valueGood: ((String) -> Boolean)? = { true }
    }
}

data class PersonName(
    @get:JsonIgnore
    override var value: String
) : IEntity {
    override var origValue: String? = null

    @JsonValue
    override fun toString(): String = value

    companion object {
        @JsonIgnore
        val valueGood: ((String) -> Boolean)? = { true }
    }
}

class IntentClarification(
    @JsonInclude(JsonInclude.Include.NON_NULL)
    override var session: UserSession? = null
) : IBotMode, IFrame{
    var utterance: String? = null

    var source: Array<IIntent>? = null

    var target: IIntent? = null

    override fun createBuilder() = object : FillBuilder {
        var frame: IntentClarification? = this@IntentClarification
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = ::frame
            val filler = FrameFiller({ tp }, path)
            return filler
        }
    }
}
