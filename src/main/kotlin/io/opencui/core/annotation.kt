package io.opencui.core


import io.opencui.core.da.DialogAct
import io.opencui.core.da.SlotRequest
import kotlinx.coroutines.runBlocking
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
// TODO: Need to remove runBlocking eventually.
data class Condition(private val f: suspend () -> Boolean): () -> Boolean, Serializable {
    override fun invoke(): Boolean = runBlocking { f() }
}

data class Prompts(val prompts: List<suspend ()->String>) : Serializable {
    constructor(vararg prompts: suspend ()-> String) : this(prompts.toList())

    fun isNotEmpty() = prompts.isNotEmpty()
    fun random() = prompts.random()
}


interface DialogActRender : Serializable{
    suspend fun pick(channel: String = SideEffect.RESTFUL): String
    suspend fun render() : Map<String, List<String>>
}

// This has two sides: how it is used and how it is created, and also just a type.
data class Templates(val channelPrompts: Map<String, Prompts>): DialogActRender {
    override suspend fun pick(channel: String): String {
        val prompts = channelPrompts[channel] ?: channelPrompts[SideEffect.RESTFUL] ?: return ""
        return if (prompts.isNotEmpty()) prompts.random().invoke() else ""
    }

    override suspend fun render() : Map<String, List<String>> {
        return channelPrompts.mapValues{ (_, prompts) -> prompts.prompts.map { it() } }
    }
}

data class LlmDialogActRender(val dialogAct: DialogAct) : DialogActRender{
    override suspend fun pick(channel: String): String {
        TODO("Not yet implemented")
    }

    override suspend fun render() : Map<String, List<String>> {
        TODO("Not yet implemented")
    }
}

//
inline fun <T, R> withSuspend(receiver: T, crossinline block: suspend T.() -> R): suspend ()-> R = { receiver.block() }
inline fun <S, T, R> withSuspend(scope: S, receiver: T, crossinline block: suspend S.(T) -> R): suspend ()-> R = { scope.block(receiver) }

// This is a specialized version of withSuspend that automatically gets the language context (rgLang)
// from the IFrame it's called on. This is for prompts that only need the language context.
inline fun <R> IFrame.withLang(crossinline block: suspend RGBase.() -> R): suspend () -> R {
    return withSuspend(this.session!!.rgLang, block)
}

// This is an overloaded version for prompts that need both the language context and another value (the receiver).
inline fun <T, R> IFrame.withLang(receiver: T, crossinline block: suspend RGBase.(T) -> R): suspend () -> R {
    return withSuspend(this.session!!.rgLang, receiver, block)
}


suspend fun <T> Iterable<T>.joinToStringSuspend(
    separator: CharSequence = ", ",
    prefix: CharSequence = "",
    postfix: CharSequence = "",
    limit: Int = -1,
    truncated: CharSequence = "...",
    transform: (suspend (T) -> CharSequence)? = null
): String {
    val buffer = StringBuilder()
    buffer.append(prefix)

    var count = 0
    for (element in this) {
        if (++count > 1) buffer.append(separator)
        if (limit < 0 || count <= limit) {
            val text = if (transform != null) transform(element) else element.toString()
            buffer.append(text)
        } else break
    }
    if (limit >= 0 && count > limit) buffer.append(truncated)
    buffer.append(postfix)
    return buffer.toString()
}

//
// Eventually, the template encodes how to render some structured information (dialog action) into
// natural language, per language and channel. But we want to basically remove this responsibility
// from builder, while still allow them to have some channel level instruction. We should add instruction
// at channel level, and make LLM support  (language, channel style, dialogact, history) -> text.
//
fun templateOf(vararg pairs: Pair<String, Prompts>) = Templates(pairs.toMap())
fun templateOf(vararg prompts: suspend ()->String) = Templates(mapOf(SideEffect.RESTFUL to Prompts(*prompts)))
fun templateOf(vararg prompts: String) = Templates(mapOf(SideEffect.RESTFUL to Prompts(prompts.toList().map{ {it}} )))

fun emptyTemplate() = Templates(emptyMap())

fun <T> convertDialogActGen(source: suspend () -> T, dialogActGen: suspend (T) -> DialogAct): suspend () -> DialogAct {
    return {dialogActGen(source())}
}

fun <T> convertDialogAct(source: T, dialogActGen: suspend (T) -> DialogAct): suspend () -> DialogAct {
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

// Asking strategy also encode behavior.
data class BoolGateAsk(val generator: () -> DialogAct): AskStrategy {
    override fun canEnter(): Boolean {
        return true
    }
}


// The annotation should be used to build filler, they are currently
// just a container, or markup, for creating actions.
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