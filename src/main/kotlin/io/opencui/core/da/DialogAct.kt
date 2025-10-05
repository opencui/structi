package io.opencui.core.da

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.opencui.core.*
import io.opencui.system1.Augmentation
import io.opencui.system1.System1Mode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.Serializable

// Generation are indirectly produced by LLMs.
interface Generation: EmissionAction


// All system1 should be one of these.
data class KnowledgeTag(val key: String, val value: String?=null)

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,             // use the type name
    include = JsonTypeInfo.As.PROPERTY,     // include it as a property in JSON
    property = "type"                       // the property name in JSON
)
@JsonSubTypes(
    JsonSubTypes.Type(value = FilePart::class, name = "FilePart"),
    JsonSubTypes.Type(value = RetrievablePart::class, name = "RetrievablePart")
)
interface KnowledgePart

// FilePart will be used as anonymous knowledge.
data class FilePart(val content: String, val type: String="txt") : KnowledgePart
data class RetrievablePart(val name: String, val tags: List<KnowledgeTag>) : KnowledgePart

// This is this generation does the soft generate the response.
open class System1Generation(
    val system1Id: String,
    val templates: Templates, // This should be a jinja2 template so that system1 can follow.
    val packageName: String,
    var mode: System1Mode = System1Mode.FALLBACK
  ): Generation {


    override fun run(session: UserSession): ActionResult {
        logger.info("Start of System1Generation with $system1Id")
        val system1Builder = session.getSystem1Builder(system1Id, packageName)

        val augmentation = Augmentation(
            templates.pick(),
            mode = mode
        )

        val system1Action = system1Builder.build(session, augmentation)

        val channel = Channel<System1Inform>(Channel.UNLIMITED)
        system1Action.invoke(
            object : Emitter<System1Inform> {
                override suspend fun invoke(x: System1Inform) {
                    println("Emitter: Emitting '$x'") // Added for clarity
                    channel.trySend(x) // trySend is non-suspending
                }
            })
        channel.close()

        // We can decide handle flow or result, here.
        // the system1 should already return the System1Inform.
        val actionResult = ActionResult(
            channel.receiveAsFlow(),
            createLog("AugmentedGeneration"),
            true
        )
        logger.info("End of System1Generation with $system1Id")
        return actionResult
    }
    companion object {
        val logger: Logger = LoggerFactory.getLogger(System1Generation::class.java)
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
            is FrameDialogAct<*> -> this.frameType
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

interface FrameDialogAct<T>: DialogAct {
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
    override var templates: Templates = emptyTemplate()) : FrameDialogAct<T>

data class FrameInform<T>(
    val target: T?,
    override val frameType: String,
    override var templates: Templates = emptyTemplate()) : FrameDialogAct<T>

data class FrameOffer<T>(
    val value: List<T>,
    override val frameType: String,
    override var templates: Templates = emptyTemplate()): FrameDialogAct<T>

data class FrameOfferSepInform<T>(
    val value: T,
    override val frameType: String,
    override var templates: Templates = emptyTemplate()) : FrameDialogAct<T>

data class FrameOfferZepInform<T>(
    override val frameType: String,
    override var templates: Templates = emptyTemplate()) : FrameDialogAct<T>

data class FrameOfferOutlier<T>(
    val value: T,
    override val frameType: String,
    override var templates: Templates = emptyTemplate()) : FrameDialogAct<T>

open class UserDefinedInform<T>(
    val target: T,
    override val frameType: String,
    override var templates: Templates = emptyTemplate()) : FrameDialogAct<T> {
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

data class System1Inform(val type: String, override var templates: Templates = emptyTemplate()) : DialogAct {
    constructor(type: String, payload: String): this(type, templateOf(payload))

    companion object {
        const val JSON = "json"
        const val ERROR = "error"
        const val TEXT = "text"
        const val THINK = "thinking"
    }
}

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