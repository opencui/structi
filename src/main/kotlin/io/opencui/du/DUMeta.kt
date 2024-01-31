package io.opencui.du

import io.opencui.core.En
import io.opencui.core.RGBase
import io.opencui.core.Zh
import org.apache.lucene.analysis.Analyzer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.Serializable
import java.util.*
import java.util.regex.Pattern



/**
 * This is mainly used as raw source of the information.
 * isHead is created to indicate whether current slot is head of the hosting frame.
 */
// TODO (xiaobo): please fill the triggers and prompts, so that DU have enough information.
data class DUSlotMeta(
        val label: String,                           // this is language independent.
        val triggers: List<String> = emptyList(),    // this is language dependent.
        val type: String?=null,
        // TODO (@flora default false, compiler need to pass annotation from platform)
        val isMultiValue: Boolean? = false,
        val parent: String? = null,
        val isHead: Boolean = false) {
    // Used to capture whether this slot is mentioned in the expression.
    var isMentioned: Boolean = false
    var prefixes: Set<String>?=null
    var suffixes: Set<String>?=null

    fun typeReplaced(newType: String, newTriggers: List<String>): DUSlotMeta {
        val meta = DUSlotMeta(label, newTriggers, newType, isMultiValue, parent, isHead)
        meta.isMentioned = isMentioned
        meta.prefixes = prefixes
        meta.suffixes = suffixes
        return meta
    }

    fun isGenericTyped(): Boolean {
        return type == "T"
    }

    fun asMap(useSlotLabel: Boolean=true) : Map<String, String> {
        // TODO(sean): use slot label.
        return mapOf(
            "name" to label,
            "description" to triggers[0]
        )
    }

}

// Compute the surrounding words so that we can help extraction.
fun extractSlotSurroundingWords(
    exprByOwners: Map<String, List<Exemplar>>, analyzer:Analyzer):
        Pair<Map<String, Set<String>>, Map<String, Set<String>>> {
    // frame#slot: prefixes
    val slotPrefixes = mutableMapOf<String, MutableSet<String>>().withDefault { mutableSetOf() }
    // frame#slot: suffixes
    val slotSuffixes = mutableMapOf<String, MutableSet<String>>().withDefault { mutableSetOf() }
    // frame dontcare annotations
    for ((ownerId, expressions) in exprByOwners) {
        // entity dontcare annotations has been processed in above loop
        for (expression in expressions) {
            val typedSegments = Exemplar.segment(expression.template, ownerId)
            if (typedSegments.segments.size <= 1) continue
            println("handling ${expression.template}")
            // first get parts tokenized.
            val tknMap = mutableMapOf<Int, List<BoundToken>>()
            for ((i, part) in typedSegments.segments.withIndex()) {
                if (part is ExprSegment) {
                    tknMap[i] = analyzer.tokenize(part.expr)
                }
            }
            for ((i, part) in typedSegments.segments.withIndex()) {
                if (part is MetaSegment) {
                    val key = "$ownerId:${part.meta}"
                    if (!slotSuffixes.containsKey(key)) slotSuffixes[key] = mutableSetOf()
                    if (!slotPrefixes.containsKey(key)) slotPrefixes[key] = mutableSetOf()
                    if (i > 0 && !tknMap[i - 1].isNullOrEmpty()) {
                        slotPrefixes[key]!!.add(tknMap[i - 1]!!.last().token)
                    }
                    if (i < typedSegments.segments.size - 1 && !tknMap[i+1].isNullOrEmpty()) {
                        slotSuffixes[key]!!.add(tknMap[i + 1]!!.first().token)
                    }
                }
            }
        }
    }
    return Pair(slotPrefixes, slotSuffixes)
}

interface LangBase {
    fun getLang(): String

    fun getTriggers(name: String): List<String> {
        return listOf()
    }
}


interface ExtractiveMeta : LangBase {
    fun getEntities(): Set<String>

    fun getEntityInstances(name: String): Map<String, List<String>> // for runtime, all labels and normalized form

    fun getEntityMeta(name: String): IEntityMeta? // string encoding of JsonArray of JsonObject

    fun getSlotTriggers(): Map<String, List<String>> = emptyMap()

    val expressionsByFrame: Map<String, List<Exemplar>>
}


interface DUMeta : ExtractiveMeta {

    fun getLabel(): String
    fun getVersion(): String { return ""}
    fun getBranch(): String { return "master"}
    fun getOrg(): String { return "" }
    fun getTimezone(): String { return "america/los_angeles" }

    fun getSlotMetas(frame: String) : List<DUSlotMeta>
    fun typeKind(name: String): TypeKind {
        if (isEntity(name)) return TypeKind.Entity
        if (name.startsWith("io.opencui.generic.")) return TypeKind.Generic
        if (name.length == 1 && name[0].isUpperCase()) return TypeKind.Generic
        return TypeKind.Frame
    }

    fun isEntity(name: String) : Boolean  // given a name, return true if it's entity

    // TODO(xiaobo): to support head on frame, just make  function work with entity type.
    fun getSubFrames(fullyQualifiedType: String): List<String> { return emptyList() }

    fun getRGLang(local: String = "") : RGBase {
        return when(getLang()) {
            "zh" -> Zh(this)
            "en" -> En(this)
            else -> throw IllegalStateException("Does not support ${getLang()} yet.")
        }
    }

    fun getSlotMetas(expectation: DialogExpectations): List<DUSlotMeta> {
        return expectation.activeFrames.map{getSlotMetas(it.frame)}.flatten()
    }

    companion object {
        const val OWNERID = "owner_id"
        const val EXPRESSIONS = "expressions"
        const val CONTEXT = "context"
        const val TYPEID = "frame_id" // this is type id.
        const val UTTERANCE = "utterance"
        private val LessGreaterThanRegex = Regex("(?<=[<>])|(?=[<>])")

        // "My Phone is $PhoneNumber$" -> "my phone is $PhoneNumber$"
        fun toLowerProperly(utterance: String): String {
            val parts = utterance.split(LessGreaterThanRegex)
            val lowerCasedUtterance = StringBuffer()
            var lowerCase = true
            for (part in parts) {
                if (part == ">") lowerCase = true
                if (!lowerCase) {
                    lowerCasedUtterance.append(part)
                } else {
                    lowerCasedUtterance.append(part.lowercase(Locale.getDefault()))
                }
                if (part == "<") lowerCase = false
            }
            return lowerCasedUtterance.toString()
        }
    }
}

// Use extension
fun DUMeta.getNestedSlotMetas(
        frame: String,
        required: List<String> = emptyList()
    ): Map<String, DUSlotMeta> {
    // Including all the top level slots.
    val slotsMetaMap = getSlotMetas(frame).map { it.label to it }.toMap().toMutableMap()

    // We will test the nested slot at the top level, note that only handles the ones has head.
    for (slot in slotsMetaMap.keys.toList()) {
        val slotMeta = getSlotMeta(frame, slot)
        if (slotMeta != null) {
            val nestedMetas = getSlotMetas(slotMeta.type!!)
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


// If the frame is system frame or not.
fun DUMeta.isSystemFrame(frame: String): Boolean {
    return frame.startsWith("io.opencui.core")
}


fun DUMeta.getSlotMeta(frame:String, pslots:String) : DUSlotMeta? {
    // We can handle the nested slots if we need to.
    val slots = pslots.split(".")
    var owner = frame
    for(slot in slots.subList(0, slots.size - 1)) {
        owner = getSlotMetas(owner).firstOrNull{it.label == slot}!!.type!!
    }
    val lastSlot = slots.last()
    return getSlotMetas(owner).firstOrNull {it.label == lastSlot}
}

fun DUMeta.getSlotType(frame: String, slot:String) : String {
    return getSlotMeta(frame, slot)?.type ?: ""
}

enum class TypeKind {
    Entity, Frame, Generic
}

abstract class DslDUMeta() : DUMeta {
    abstract val entityTypes: Map<String, EntityType>
    abstract val slotMetaMap: Map<String, List<DUSlotMeta>>
    abstract val aliasMap: Map<String, List<String>>
    val subtypes: MutableMap<String, List<String>> = mutableMapOf()

    override fun getSubFrames(fullyQualifiedType: String): List<String> {
        return subtypes[fullyQualifiedType] ?: emptyList()
    }

    override fun getSlotTriggers(): Map<String, List<String>> {
        val results = mutableMapOf<String, List<String>>()
        for ((frame, metas) in slotMetaMap) {
            for (slotMeta in metas) {
                results["${frame}.${slotMeta.label}"] = slotMeta.triggers
            }
        }
        return results
    }

    override fun getEntities(): Set<String> {
        return entityTypes.keys
    }

    override fun getTriggers(name: String): List<String> {
        return aliasMap[name] ?: listOf()
    }

    override fun getEntityMeta(name: String): IEntityMeta? {
        return entityTypes[name]
    }

    override fun getSlotMetas(frame: String): List<DUSlotMeta> {
        return slotMetaMap[frame] ?: listOf()
    }

    override fun isEntity(name: String): Boolean {
        return entityTypes.containsKey(name)
    }
}


interface IEntityMeta {
    val recognizer: List<String>
    val children: List<String>
    val normalizable: Boolean
    fun getSuper(): String?
}

data class EntityMeta(
    override val recognizer: List<String>,
    val parents: List<String> = emptyList(),
    override val children: List<String> = emptyList()
) : Serializable, IEntityMeta {
    override val normalizable: Boolean
        get() = true

    override fun getSuper(): String? {
        return if (parents.isNullOrEmpty()) null else parents[0]
    }
}

data class EntityType(
    val label: String,
    override val recognizer: List<String>,
    val entities: Map<String, List<String>>,
    val parent: String? = null,
    override val children: List<String> = emptyList(),
    val pattern: String? = null,
    override val normalizable: Boolean = true): Serializable, IEntityMeta {
    override fun getSuper(): String? {
        return parent
    }


}

data class ExpressionContext(val frame: String, val slot: String?)
sealed interface TypedExprSegment: Serializable {
    val start: Int
    val end: Int
}
data class ExprSegment(val expr: String, override val start: Int, override val end: Int): TypedExprSegment
data class MetaSegment(val meta: String, override val start: Int, override val end: Int): TypedExprSegment

data class MetaExprSegments(val frame: String, val typedExpr: String, val segments: List<TypedExprSegment>) {
    fun useGenericType() : Boolean {
        return segments.find {it is MetaSegment && it.meta == "T"} != null
    }
}


data class Exemplar(
    override val ownerFrame: String,
    override val template: String,
    override val label: String? = null,
    override val contextFrame: String? = null,
    val contextSlot: String? = null
) : IExemplar {

    override lateinit var typedExpression: String

    // whether it is exact match.
    override var exactMatch: Boolean = false

    // The next two are used for potential exect match.
    override var possibleExactMatch: Boolean = false

    override fun clone(): IExemplar {
        return this.copy()
    }

    // use constructor to change internal structure.
    constructor(owner:String, context: ExpressionContext?, label: String?, utterance: String, bot: DUMeta) :
            this(owner, utterance, label, context?.frame, context?.slot)

    fun toMetaExpression(duMeta: DUMeta): String {
        return buildTypedExpression(template, ownerFrame, duMeta)
    }

    /**
     * TODO: Currently, we only use the frame as context, we could consider to use frame and attribute.
     * This allows for tight control.
     */
    fun buildFrameContext(): String {
        if (contextFrame != null) {
            return """{"frame_id":"${contextFrame}"}"""
        }
        return "default"
    }

    fun buildSlotTypes(duMeta: DUMeta): List<String> {
        return AngleSlotRegex
                .findAll(template)
                .map { it.value.substring(1, it.value.length - 1) }
                .map { duMeta.getSlotType(ownerFrame, it) }
                .toList()
    }

    companion object {
        private val AngleSlotPattern = Pattern.compile("""<(.+?)>""")
        private val AngleSlotRegex = AngleSlotPattern.toRegex()
        val logger: Logger = LoggerFactory.getLogger(Exemplar::class.java)

        /**
         * Currently, we append entity type to user utterance, so that we can retrieve back
         * the expression that contains both triggering and slot.
         * I think the better way of doing this is to use extra field. This way, we do not
         * have to parse things around.
         */
        @JvmStatic
        fun buildTypedExpression(utterance: String, owner: String, agent: DUMeta): String {
            return AngleSlotRegex.replace(utterance)
            {
                val slotName = it.value.removePrefix("<").removeSuffix(">").removeSurrounding(" ")
                "< ${agent.getSlotType(owner, slotName)} >"
            }
        }

        fun segment(expression: String, owner: String): MetaExprSegments {
            val matcher = AngleSlotPattern.matcher(expression)
            val result = mutableListOf<TypedExprSegment>()
            var lastStart = 0

            while (matcher.find()) {
                val start = matcher.start()
                val end = matcher.end()
                if (start > lastStart) result.add(
                    ExprSegment(
                        expression.substring(lastStart, start).trim(),
                        lastStart,
                        start
                    )
                )
                lastStart = end
                result.add(MetaSegment(expression.substring(start + 1, end - 1).trim(), start, end))
            }

            if (lastStart < expression.length) result.add(
                ExprSegment(
                    expression.substring(lastStart, expression.length).trim(), lastStart, expression.length
                )
            )
            return MetaExprSegments(owner, expression, result)
        }
    }
}
