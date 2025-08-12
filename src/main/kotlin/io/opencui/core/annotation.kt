package io.opencui.core


import io.opencui.core.da.DialogAct
import io.opencui.core.da.SlotRequest
import java.io.Serializable
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty0

/**
 * Dialog behaviors are controlled by largely by annotations, but some of these
 * annotations never makes to dm, and they are fixed IDE/Compiler time (for example,
 * companion attribute, and also training phrases).
 *
 * For rest of the annotation, dm is used to carry out the conversation based on the user
 * input and developer supplied annotation, predefined system rules.
 */
interface Annotation: Serializable {
    // This can be used to switch the annotation off when need to.
    val switch: () -> Boolean
        get() = {true}
}


// It seems that prompt can be language dependent, but DialogAct should not be language
// dependent.
// Action should not be language dependent as well.
// Annotation is just some action? or action builder? or composite action?
//
// We can simplify things in couple directions:
// 1. reduce the prompt with only one implementation, there is no reason to have anything
//    other than LazyPrompt.
// 2. We can remove the annotation, and use action directly.
//
// The long term goal is to host the prompt in the language pack, and have dialog act to
// reference the language dependent prompt.
// So, we need to figure out how to find the action


// LangPack should host the all dialog act,
// There are map from channel to string generator, string generator has types (dialog act).
// Actions are language independent.

data class Condition(private val f: () -> Boolean): () -> Boolean, Serializable {
    override fun invoke(): Boolean = f()
}

data class Prompts(val prompts: List<String>) : Serializable {
    constructor(vararg prompts: String) : this(prompts.toList())

    fun isNotEmpty() = prompts.isNotEmpty()
    fun random() = prompts.random()
}


// This has two sides: how it is used and how it is created, and also just a type.
data class Templates(val channelPrompts: Map<String, Prompts>): Serializable {
    fun pick(channel: String = SideEffect.RESTFUL): String {
        val prompts = channelPrompts[channel] ?: channelPrompts[SideEffect.RESTFUL] ?: return ""
        return if (prompts.isNotEmpty()) prompts.random() else ""
    }
}

fun templateOf(vararg pairs: Pair<String, Prompts>) = Templates(pairs.toMap())
fun templateOf(vararg prompts: String) = Templates(mapOf(SideEffect.RESTFUL to Prompts(*prompts)))

fun emptyTemplate() = Templates(mapOf())

fun <T> convertDialogActGen(source: () -> T, dialogActGen: (T) -> DialogAct): () -> DialogAct {
    return {dialogActGen(source())}
}

fun <T> convertDialogAct(source: T, dialogActGen: (T) -> DialogAct): () -> DialogAct {
    return {dialogActGen(source)}
}

interface PromptAnnotation : Annotation {
    val actions: List<Action>
    operator fun invoke(): Action {
        return SeqAction(actions)
    }
}

/**
 * If we want agent ask user for value to fill the slot, then developer can provide
 * the prompt questions via this annotation. Ideally, we can potentially add exemplars
 * for the answers as well.
 */
data class SlotPromptAnnotation(override val actions: List<Action>) : PromptAnnotation {
    // just for convenience of testcase
    constructor(templates: Templates): this( SlotRequest("", "", templates) )
    constructor(vararg acts: Action): this(acts.toList())
}

data class SlotConditionalPromptAnnotation(override val actions: List<Action>) : PromptAnnotation {
    // just for convenience of testcase
    constructor(picker: () -> Templates): this(listOf(LazyAction { SlotRequest("", "", picker()) } ))
}

data class SlotInformActionAnnotation(override val actions: List<Action>) : PromptAnnotation {
    constructor(vararg acts: Action): this(acts.toList())
}

// We should have changed this to FillStrategy.
interface AskStrategy: Annotation {
    fun canEnter(): Boolean
    fun canNotEnter(): Boolean {
        return !canEnter()
    }
}

data class AlwaysAsk(val condition: Boolean = true): AskStrategy {
    override fun canEnter(): Boolean {
        return true
    }
}

interface EventStrategy : AskStrategy {}

// This is async event strategy, where we expect prompt is Inform.
data class ExternalEventStrategy(val condition: Boolean = true): EventStrategy {
    override fun canEnter(): Boolean {
        return true
    }
}

// This is sync event strategy, where we expect prompt is Request.
data class InternalEventStrategy(val condition: Boolean = true): EventStrategy {
    override fun canEnter(): Boolean {
        return true
    }
}

data class NeverAsk(val condition: Boolean = true): AskStrategy {
    override fun canEnter(): Boolean {
        return true
    }
}

data class ConditionalAsk(val condition: Condition): AskStrategy {
    override fun canEnter(): Boolean {
        return condition()
    }
}

data class RecoverOnly(var condition: Boolean = false): AskStrategy {
    override fun canEnter(): Boolean {
        return condition
    }

    fun enable() {
        condition = true
    }

    fun disable() {
        if (condition) {
            condition = false
        }
    }
}

data class BoolGateAsk(val generator: () -> DialogAct): AskStrategy {
    override fun canEnter(): Boolean {
        return true
    }
}

// This is used to create the filter (dataflow program).
data class FilterAnnotation<T, W>(
    val filter: (W) -> Boolean,
    val batch: (List<T>) -> List<W>) : kotlin.Annotation


interface IValueRecAnnotation: Annotation

data class ValueRecAnnotation(val recFrameGen: () -> IFrame, val showOnce: Boolean = false): IValueRecAnnotation

data class TypedValueRecAnnotation<T>(val recFrameGen: T?.() -> IFrame, val showOnce: Boolean = false): IValueRecAnnotation

data class ConfirmationAnnotation(val confirmFrameGetter: ()->IFrame?): Annotation

data class ValueCheckAnnotation(val checkFrame: () -> IFrame, override val switch: () -> Boolean = {true}): Annotation

data class MinMaxAnnotation(val min: Int, val minGen: () -> DialogAct, val max: Int, val maxGen: () -> DialogAct): Annotation

data class SlotInitAnnotation(val action: Action): Annotation

data class SlotDoneAnnotation(val condition: () -> Boolean, val actions: List<Action>): Annotation

data class DialogActCustomizationAnnotation(val dialogActName: String, val templateGen: (DialogAct) -> Templates): Annotation


enum class SystemAnnotationType(val typeName: String) {
    IDonotGetIt("io.opencui.core.IDonotGetIt"),
    IntentSuggestion("io.opencui.core.IntentSuggestion"),
    ValueClarification("io.opencui.core.ValueClarification"),
    ResumeIntent("io.opencui.core.ResumeIntent"),
    System1Skill("io.opencui.core.System1")
}



// The goal of annotation is to support the following interface on IFrame:
//  fun annotations(path: String): List<Annotation> = listOf()
//  fun createBuilder(p: KMutableProperty0<out Any?>? = null): FillBuilder
//  slot "this" is a special slot which indicates searching for frame confirmation
//  fun searchConfirmation(slot: String): IFrame?
//  fun searchStateUpdateByEvent(event: String): IFrameBuilder?
//  fun searchResponse(): Action?

// There are two type/instances are involved: Context, and Owner, with Owner's behavior are dependent on
// Context. Furthermore, the context impact are from most specific to most general, which is no context at all.

class ActionBuilders{

    val creators = mutableMapOf<KClass<out IFrame>, (IFrame) -> Action?>()

    inline fun <reified T : IFrame> register(noinline creater: (IFrame) -> Action?) {
        creators[T::class] = creater
    }

    inline fun <reified  T: IFrame> searchResponse(p: IFrame): Action? {
        return creators[T::class]!!(p)
    }
}

class AnnotationBuilders {
    val creators = mutableMapOf<KClass<out IFrame>, (IFrame, ParamPath) -> List<Annotation>>()

    inline fun <reified T : IFrame> register(noinline creater: (IFrame, ParamPath) -> List<Annotation>) {
        creators[T::class] = creater
    }

    inline fun <reified  T: IFrame> searchResponse(p: IFrame, slot: ParamPath): List<Annotation> {
        return creators[T::class]!!(p, slot)
    }
}

class ConfirmationBuilders {
    val creators = mutableMapOf<KClass<out IFrame>, (IFrame, ParamPath) -> IFrame?>()

    inline fun <reified T : IFrame> register(noinline creater: (IFrame, ParamPath) -> IFrame?) {
        creators[T::class] = creater
    }

    inline fun <reified  T: IFrame> searchResponse(p: IFrame, slot: ParamPath): IFrame? {
        return creators[T::class]!!(p, slot)
    }
}

class StateUpdateByEventBuilders {
    val creators = mutableMapOf<KClass<out IFrame>, (IFrame, String) -> IFrameBuilder?>()
    inline fun <reified T : IFrame> register(noinline creater: (IFrame, String) -> IFrameBuilder?) {
        creators[T::class] = creater
    }

    inline fun <reified  T: IFrame> searchResponse(p: IFrame, slot: String): IFrameBuilder? {
        return creators[T::class]!!(p, slot)
    }
}

// This can manage all the builder creation so that we do not have to create this separate method for this.
class FillCreatorManager {
    val creators = mutableMapOf<KClass<out IFrame>, (p: KMutableProperty0<out Any?>?) -> FillBuilder>()

    inline fun <reified T : IFrame> register(noinline creater: (p: KMutableProperty0<out Any?>?) -> FillBuilder) {
        creators[T::class] = creater
    }

    inline fun <reified  T: IFrame> createBuilder(p: KMutableProperty0<out Any?>?): FillBuilder {
        return creators[T::class]!!(p)
    }
}