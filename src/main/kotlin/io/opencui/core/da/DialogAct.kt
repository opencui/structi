package io.opencui.core.da

import io.opencui.core.*
import io.opencui.system1.Augmentation
import io.opencui.system1.ISystem1
import java.io.Serializable

// Generation are indirectly produced by LLMs.
interface Generation: EmissionAction


// All system1 should be one of these.
data class KnowledgeTag(val key: String, val value: String)

data class FilteredKnowledge(
    val content: String,
    val fileNameForContent: String,
    val knowledgeLabel: String,
    val tags: List<KnowledgeTag>
)

// This is this generation does the soft generate the response.
open class System1Generation(
    val system1Id: String,
    val templates: () -> DialogAct, // This should be a jinja2 template so that system1 can follow.
    val remoteKnowledge: List<FilteredKnowledge>): Generation {

    override fun run(session: UserSession): ActionResult {
        val system1 = session.chatbot!!.getExtension<ISystem1>(system1Id)

        val dialogAct = templates.invoke()
        val augmentation = Augmentation(
            dialogAct.templates.pick(),
            remoteKnowledge,
        )

        val result = system1?.response(session.history, augmentation)
        val response = mutableListOf<DialogAct>()
        if (result.isNullOrEmpty()) {
            response.add(RawInform(templateOf(result!!)))
        }

        return ActionResult(
            response,
            createLog("AugmentedGeneration"),
            true
        )
    }
}

// This interface represents the dialog act that bot about to take. We start from the list from schema guided
// dialog. Notice the dialog act from user side is absorbed by dialog understanding, so we do not have model
// these explicitly. These represent what bot want to express, not how they express it.
interface DialogAct: EmissionAction {
    var templates: Templates
    override fun run(session: UserSession): ActionResult {
        val success = true
        return ActionResult(
            listOf(this),
            createLog(templates.pick()),
            success
        )
    }

    fun genGroupKey(): String {
        return when (this) {
            is SlotDialogAct -> """${if (context.isNotEmpty()) context.first()::class.qualifiedName else "null"}_${slotName}_${slotType}"""
            is FrameDialogAct -> this.frameType
            else -> this::class.qualifiedName!!
        }
    }
}

interface SlotDialogAct: DialogAct {
    val slotName: String
    val slotType: String
    val context: List<IFrame>
}

// Let's use this to separate the dialog acts into two parts. So that we can hint user with the
// potential delay that will come up.
interface RequestForDelayDialogAct: DialogAct {}

interface FrameDialogAct: DialogAct {
    val frameType: String
}

interface DialogActRewriter : Serializable {
    var result: DialogAct
    operator fun invoke(): DialogAct = result
}

// SLOT DialogAct
data class SlotRequest(
    override val slotName: String,
    override val slotType: String,
    override val context: List<IFrame> = listOf(),
    override var templates: Templates = emptyTemplate()) : SlotDialogAct {
    // this kind of constructor is just for convenience of testcase
    constructor(slotName: String, slotType: String,
                templates: Templates = emptyTemplate()): this(slotName, slotType, listOf(), templates)
}

data class SlotNotify(
    override val slotName: String,
    override val slotType: String,
    override val context: List<IFrame> = listOf(),
    override var templates: Templates = emptyTemplate()) : SlotDialogAct {
    // this kind of constructor is just for convenience of testcase
    constructor(slotName: String, slotType: String,
                templates: Templates = emptyTemplate()): this(slotName, slotType, listOf(), templates)
}

data class SlotRequestMore(
    override val slotName: String,
    override val slotType: String,
    override val context: List<IFrame> = listOf(),
    override var templates: Templates = emptyTemplate()) : SlotDialogAct {
    constructor(slotName: String, slotType: String,
                templates: Templates = emptyTemplate()): this(slotName, slotType, listOf(), templates)
}

data class SlotGate(
    override val slotName: String,
    override val slotType: String,
    override val context: List<IFrame> = listOf(),
    override var templates: Templates = emptyTemplate()) : SlotDialogAct {
    constructor(slotName: String, slotType: String,
                templates: Templates = emptyTemplate()): this(slotName, slotType, listOf(), templates)
}

enum class FailType {
    VC,
    MIN,
    MAX,
}

data class SlotNotifyFailure<T>(
    val target: T,
    override val slotName: String,
    override val slotType: String,
    val failType: FailType,
    override val context: List<IFrame> = listOf(),
    override var templates: Templates = emptyTemplate()) : SlotDialogAct {
    constructor(target: T, slotName: String, slotType: String, failType: FailType,
                templates: Templates = emptyTemplate(),
                context: List<IFrame> = listOf()): this(target, slotName, slotType, failType, context, templates)
}

data class SlotConfirm<T>(
    val target: T?,
    override val slotName: String,
    override val slotType: String,
    override val context: List<IFrame> = listOf(),
    override var templates: Templates = emptyTemplate()) : SlotDialogAct {
    constructor(target: T?, slotName: String, slotType: String,
                templates: Templates = emptyTemplate(),
                context: List<IFrame> = listOf()): this(target, slotName, slotType, context, templates)
}

data class SlotInform<T>(
    val target: T?,
    override val slotName: String,
    override val slotType: String,
    override val context: List<IFrame> = listOf(),
    override var templates: Templates = emptyTemplate()) : SlotDialogAct {
    constructor(target: T?, slotName: String, slotType: String,
                templates: Templates = emptyTemplate(),
                context: List<IFrame> = listOf()): this(target, slotName, slotType, context, templates)
}

data class SlotOffer<T>(
    val value: List<T>,
    override val slotName: String,
    override val slotType: String,
    override val context: List<IFrame> = listOf(),
    override var templates: Templates = emptyTemplate()): SlotDialogAct {
    constructor(value: List<T>, slotName: String, slotType: String,
                templates: Templates = emptyTemplate(),
                context: List<IFrame> = listOf()): this(value, slotName, slotType, context, templates)
}

data class SlotOfferSepInform<T>(
    val value: T,
    override val slotName: String,
    override val slotType: String,
    override val context: List<IFrame> = listOf(),
    override var templates: Templates = emptyTemplate()) : SlotDialogAct {
    constructor(value: T, slotName: String, slotType: String,
                templates: Templates = emptyTemplate(),
                context: List<IFrame> = listOf()): this(value, slotName, slotType, context, templates)
}

data class SlotOfferZepInform(
    override val slotName: String,
    override val slotType: String,
    override val context: List<IFrame> = listOf(),
    override var templates: Templates = emptyTemplate()) : SlotDialogAct {
    constructor(slotName: String, slotType: String,
                templates: Templates = emptyTemplate(),
                context: List<IFrame> = listOf()): this(slotName, slotType, context, templates)
}

data class SlotOfferOutlier<T>(
    val value: T,
    override val slotName: String,
    override val slotType: String,
    override val context: List<IFrame> = listOf(),
    override var templates: Templates = emptyTemplate()) : SlotDialogAct {
    constructor(value: T, slotName: String, slotType: String,
                templates: Templates = emptyTemplate(),
                context: List<IFrame> = listOf()): this(value, slotName, slotType, context, templates)
}

// FRAME DialogAct
data class FrameConfirm<T>(
    val target: T?,
    override val frameType: String,
    override var templates: Templates = emptyTemplate()) : FrameDialogAct

data class FrameInform<T>(
    val target: T?,
    override val frameType: String,
    override var templates: Templates = emptyTemplate()) : FrameDialogAct

data class FrameOffer<T>(
    val value: List<T>,
    override val frameType: String,
    override var templates: Templates = emptyTemplate()): FrameDialogAct

data class FrameOfferSepInform<T>(
    val value: T,
    override val frameType: String,
    override var templates: Templates = emptyTemplate()) : FrameDialogAct

data class FrameOfferZepInform(
    override val frameType: String,
    override var templates: Templates = emptyTemplate()) : FrameDialogAct

data class FrameOfferOutlier<T>(
    val value: T,
    override val frameType: String,
    override var templates: Templates = emptyTemplate()) : FrameDialogAct

open class UserDefinedInform<T>(
    val target: T,
    override val frameType: String,
    override var templates: Templates = emptyTemplate()) : FrameDialogAct {
    constructor(target: T, templates: Templates): this(target, target!!::class.qualifiedName!!, templates)
}

// SPECIAL Component DialogAct for Composite DialogAct
data class SlotOfferSepInformConfirm<T>(
    val target: T,
    override val slotName: String,
    override val slotType: String,
    override val context: List<IFrame> = listOf(),
    override var templates: Templates = emptyTemplate()) : SlotDialogAct

// used as a placeholder for DialogAct that is not configured yet; this kind of DialogAct should not be called at runtime
class DumbDialogAct : DialogAct {
    override var templates: Templates = TODO("Not yet implemented")
}

data class RawInform(override var templates: Templates = emptyTemplate()) : DialogAct

// This might be useful to capture the system1 response.
data class ForwardDialogAct(val msg: String): DialogAct {
     override var templates: Templates = templateOf(msg)
}

// This is used during the slot asking,
data class AskRequestForDelayDialogAct(val msg: String): RequestForDelayDialogAct {
    override var templates: Templates = templateOf(msg)
}

// This is used after the slot asking, when we need to check the user response.
data class FillRequestForDelayDialogAct(val msg: String): RequestForDelayDialogAct {
    override var templates: Templates = templateOf(msg)
}

// This is used if we need before we execute the response, that might take a bit of time.
data class ResponseRequestForDelayDialogAct(val msg: String): RequestForDelayDialogAct {
    override var templates: Templates = templateOf(msg)
}