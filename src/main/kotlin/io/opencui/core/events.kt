package io.opencui.core

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.databind.node.ValueNode
import io.opencui.serialization.*
import org.slf4j.LoggerFactory
import java.io.Serializable


// This is used for reference.
// Reference can be used to find the referent in the context.
interface Reference
data class That(val slot: String? = null): Reference {}
data class ListThat(val index: Int, val slot: String? = null): Reference {}
data class ContentThat<T>(val value: T): Reference {}


enum class EventSource {
    @JsonProperty("user") USER,   // From user query.
    @JsonProperty("api") API,  // From api.
}


/**
 * This is used to keep the input from user. But it can be produced by some rules.
 * In general, builder can not define the label that starts with _, or they are {} wrapped.
 * NOTE:
 * value == "" means that "does not care"
 * value == "{}" means that we need to find actual value from contextual referent, there are different references.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class EntityEvent(
    val value: String,
    val attribute: String): Serializable {
    var type: String? = null
    @JsonIgnore
    var isUsed: Boolean = false
    var origValue: String? = null

    // If it is null, it is from USER.
    var source : EventSource? = null

    var isLeaf: Boolean = true

    var semantic : CompanionType = CompanionType.AND

    fun toCompanion(companionType: CompanionType) : EntityEvent {
        return EntityEvent(value, "${attribute}_", type).apply { semantic = companionType }
    }

    fun toLongForm() : String {
        return """EntityEvent(value=$value, attribute=$attribute, isLeaf=$isLeaf, type=$type)"""
    }

    constructor(value: String, attribute: String, type: String?) : this(value, attribute) {
        this.type = type
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EntityEvent) return false

        val otherEvent = other as EntityEvent ?: return false
        return value == otherEvent.value && attribute == otherEvent.attribute
    }

    override fun hashCode(): Int {
        return 31 * value.hashCode() + attribute.hashCode()
    }

    // TODO(sean) what is this used for?
    @JsonIgnore
    val decorativeAnnotations: MutableList<Annotation> = mutableListOf()

    companion object {
        fun build(key: String, value: String): EntityEvent {
            return EntityEvent(value=""""$value"""", attribute=key)
        }
        fun build(key: String, porigValue: String, value: String, ptype: String): EntityEvent {
            return EntityEvent(value=value, attribute=key).apply{
                type = ptype
                origValue = porigValue
            }
        }
    }
}



/**
 * This is used for specify proposed template match, each contains one trigger, and
 * multiple slot filling.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class FrameEvent(
        var type: String,
        val slots: List<EntityEvent> = emptyList(),
        val frames: List<FrameEvent> = emptyList(),
        var packageName: String? = null): Serializable {
    // This and attribute define the context,
    var attribute: String? = null
    var ownerType: String? = null
    var query: String? = null

    // Event can be used in two different modes: direct fill or cui fill. For direct fill,
    // we should use the jsonValue to encode the value. When jsonValue is not null, slots/frames should
    // empty.
    var jsonValue: JsonObject? = null

    fun toCompanion(companionType: CompanionType) : FrameEvent {
        return FrameEvent(type, slots.map { it.toCompanion(companionType) }, frames, packageName)
        // return FrameEvent(type, slots.map { it.toCompanion(companionType) } + slots.map { it.toOriginal(companionType) }, frames, packageName)
    }

    fun allAttributes() : Set<String?> {
        return slots.map {it.attribute}.toSet().union(frames.map {it.attribute}.toSet())
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FrameEvent) return false

        val otherEvent = other as FrameEvent ?: return false

        val attributes = allAttributes()
        val otherAttributes = otherEvent.allAttributes()

        if (attributes.size != otherAttributes.size) return false
        if (!attributes.containsAll(otherAttributes)) return false
        return type == otherEvent.type && attribute == otherEvent.attribute && packageName == otherEvent.packageName
    }

    override fun hashCode(): Int {
        val subAttributes = allAttributes()
        var result = type.hashCode()
        result = 31 * result + attribute.hashCode()
        result = 31 * result + subAttributes.hashCode()
        result = 31 * result + (packageName?.hashCode() ?: 0)
        return result
    }

    fun updateSemantic(companionType: CompanionType) {
        for (event in slots) {
            event.semantic = companionType
        }
    }

    val qualifiedName :String
        get() = "${packageName}.$type"

    @JsonIgnore
    var triggered: Boolean = false

    @JsonIgnore
    var inferredFrom: Boolean = false

    @JsonIgnore
    var refocused: Boolean = false

    @JsonIgnore
    var typeUsed: Boolean = false

    // This is used by OpaqueFiller to mark entire frame event is usable.
    @get:JsonIgnore
    var allUsed: Boolean = false

    // TODO (sean: there are some inconsistency here.
    // This seems to mean that this event is at least partially used. But why partially used is useful?
    @get:JsonIgnore
    val isUsed: Boolean
        get() = typeUsed || slots.firstOrNull { it.isUsed } != null || frames.firstOrNull { it.isUsed } != null

    @get:JsonIgnore
    val consumed : Boolean
        get() = allUsed || slots.firstOrNull { !it.isUsed } == null && frames.firstOrNull { !it.usedUp } == null

    @get:JsonIgnore
    val usedUp: Boolean
        get() = allUsed || ((slots.isNotEmpty() || frames.isNotEmpty()) && consumed) || (slots.isEmpty() && frames.isEmpty() && typeUsed)

    @get:JsonIgnore
    val activeEntitySlots: List<EntityEvent>
        get() = slots.filter {!it.isUsed }

    @get:JsonIgnore
    val activeFrameSlots: List<FrameEvent>
        get() = frames.filter {!it.isUsed }

    @get:JsonIgnore
    val fullType: String
        get() = "${packageName}.$type"

    @JsonIgnore
    val triggerParameters: MutableList<Any?> = mutableListOf()

    @JsonIgnore
    val triggerParameterInMap: MutableMap<String, Any?> = mutableMapOf()

    @JsonIgnore
    var slotAssignments: MutableMap<String, ()->Any?> = mutableMapOf()

    @JsonIgnore
    var turnId : Int = -1

    fun updateTurnId(pturnId: Int) {
        turnId = pturnId
    }

    var source: EventSource? = null

    companion object {
        val logger = LoggerFactory.getLogger(this::class.java)
        // TODO(xiaobo): is this frameName simple or qualified?
        fun fromJson(frameName: String, jsonElement: JsonElement): FrameEvent {
            logger.info("Converting ${jsonElement} to event")
            val slotEvents = mutableListOf<EntityEvent>()
            if (jsonElement is JsonObject) {
                for ((k, v) in jsonElement.fields()) {
                    if (k == "@class") continue
                    if (v is ValueNode && v !is JsonNull) {
                        slotEvents += EntityEvent(v.toString(), k)
                    } else if (v is ArrayNode) {
                        assert(v.size() == 2)
                        if (v.size() > 1 && v[1] != null) {
                            slotEvents += EntityEvent((v[1] as ValueNode).toString(), k).apply {
                                type = (v[0] as TextNode).textValue()
                            }
                        } else {
                            logger.warn("Key: $k missing value in $v")
                        }
                    }
                }
            }
            return FrameEvent(frameName, slotEvents)
        }

        fun build(topLevelFrame: String,
            slots: List<EntityEvent> = listOf(),
            frames: List<FrameEvent> = listOf()
        ): FrameEvent {
            val parts = topLevelFrame.splitToSequence(".")
            val packageName = parts.toList().subList(0, parts.count() - 1).joinToString(".", truncated = "")
            return FrameEvent(parts.last(), slots, frames, packageName)
        }
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ParsedQuery(
    val query: String,
    var frames: List<FrameEvent>) {
    init {
        for (event in frames) {
            event.query = query
        }
    }
}