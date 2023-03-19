package io.opencui.du

import io.opencui.core.En
import io.opencui.core.RGBase
import io.opencui.core.Zh
import io.opencui.serialization.*
import org.apache.lucene.analysis.Analyzer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.Serializable
import java.util.*
import java.util.regex.Pattern
import kotlin.collections.ArrayList


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

    fun triggerUpdated(newTriggers: List<String>): DUSlotMeta {

        val meta = DUSlotMeta(label, newTriggers, type, isMultiValue, parent, isHead)
        meta.isMentioned = isMentioned
        meta.prefixes = prefixes
        meta.suffixes = suffixes
        return meta
    }

    fun isGenericTyped(): Boolean {
        return type == "T"
    }
}

// Compute the surrounding words so that we can help extraction.
fun extractSlotSurroundingWords(
    exprByOwners: Map<String, List<Expression>>, analyzer:Analyzer):
        Pair<Map<String, Set<String>>, Map<String, Set<String>>> {
    // frame#slot: prefixes
    val slotPrefixes = mutableMapOf<String, MutableSet<String>>().withDefault { mutableSetOf() }
    // frame#slot: suffixes
    val slotSuffixes = mutableMapOf<String, MutableSet<String>>().withDefault { mutableSetOf() }
    // frame dontcare annotations
    for ((ownerId, expressions) in exprByOwners) {
        // entity dontcare annotations has been processed in above loop
        for (expression in expressions) {
            val typedSegments = Expression.segment(expression.utterance, ownerId)
            if (typedSegments.segments.size <= 1) continue
            println("handling ${expression.utterance}")
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

    val expressionsByFrame: Map<String, List<Expression>>
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

    // TODO(xiaobo): to support head on frame, just make this this function work with entity type.
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

        /**
         * This parses expression json file content into list of expressions, so that we
         * can index them one by one.
         */
        @JvmStatic
        fun parseExpressions(exprOwners: JsonArray, bot: DUMeta): Map<String, List<Expression>> {
            val resmap = mutableMapOf<String, List<Expression>>()
            for (owner in exprOwners) {
                val res = ArrayList<Expression>()
                owner as JsonObject
                val ownerId = getContent(owner["owner_id"])!!
                val expressions = owner["expressions"] ?: continue
                expressions as JsonArray
                for (expression in expressions) {
                    val exprObject = expression as JsonObject
                    val contextObject = exprObject["context"] as JsonObject?
                    val context = parseContext(contextObject)
                    val utterance = getContent(exprObject["utterance"])!!
                    val partialApplicationsObject = exprObject["partial_application"] as JsonArray?
                    val partialApplications = parsePartialApplications(partialApplicationsObject)
                    val label = if (exprObject.containsKey("label")) getContent(exprObject["label"])!! else ""
                    res.add(Expression(ownerId, context, label, toLowerProperly(utterance), partialApplications, bot))
                }
                resmap[ownerId] = res.apply { trimToSize() }
            }
            return resmap
        }

        private fun parseContext(context: JsonObject?) : ExpressionContext? {
            if (context == null) return null
            val frame = getContent(context["frame_id"])!!
            val slot = getContent(context["slot_id"])
            return ExpressionContext(frame, slot)
        }

        private fun parsePartialApplications(context: JsonArray?) : List<String>? {
            if (context == null) return null
            val list = mutableListOf<String>()
            for (index in 0 until context.size()) {
                list.add(getContent(context.get(index))!!)
            }
            return list
        }

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

        private fun getContent(primitive: JsonElement?): String? {
            return (primitive as JsonPrimitive?)?.content()
        }
    }
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


abstract class JsonDUMeta() : DUMeta {
    abstract val entityMetas: Map<String, EntityMeta>
    abstract val slotMetaMap: Map<String, List<DUSlotMeta>>
    abstract val aliasMap: Map<String, List<String>>
    val subtypes: MutableMap<String, List<String>> = mutableMapOf()

    override fun getSubFrames(fullyQualifiedType: String): List<String> {
        return subtypes[fullyQualifiedType] ?: emptyList()
    }

    override fun getEntities(): Set<String> {
        return entityMetas.keys
    }

    override fun getTriggers(name: String): List<String> {
        return aliasMap[name] ?: listOf()
    }

    override fun getEntityMeta(name: String): IEntityMeta? {
        return entityMetas[name]
    }

    override fun getSlotMetas(frame: String): List<DUSlotMeta> {
        return slotMetaMap[frame] ?: listOf()
    }

    override fun isEntity(name: String): Boolean {
        return entityMetas.containsKey(name)
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
}

interface Triggerable {
    val triggers: List<String>
}

interface IEntityMeta {
    val recognizer: List<String>
    val children: List<String>
    fun getSuper(): String?
}

data class EntityMeta(
    override val recognizer: List<String>,
    val parents: List<String> = emptyList(),
    override val children: List<String> = emptyList()
) : java.io.Serializable, IEntityMeta {
    override fun getSuper(): String? {
        return if (parents.isNullOrEmpty()) null else parents[0]
    }

}

data class EntityType(
    val label: String,
    override val recognizer: List<String>,
    val entities: Map<String, List<String>>,
    val parent: String? = null,
    override val children: List<String> = emptyList()): IEntityMeta {
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
data class Expression(
        val owner: String,
        val context: ExpressionContext?,
        val label: String?,
        val utterance: String,
        val partialApplications: List<String>?,
        val bot: DUMeta) {
    fun toMetaExpression(): String {
        return buildTypedExpression(utterance, owner, bot)
    }

    /**
     * TODO: Currently, we only use the frame as context, we could consider to use frame and attribute.
     * This allows for tight control.
     */
    fun buildFrameContext(): String {
        if (context != null) {
            return """{"frame_id":"${context.frame}"}"""
        } else {
            if (frameMap.containsKey(this.owner)) {
                return """{"frame_id":"${frameMap[this.owner]}"}"""
            }
        }
        return "default"
    }

    fun buildSubFrameContext(duMeta: DUMeta): List<String>? {
        if (context != null) {
            val subtypes = duMeta.getSubFrames(context.frame)
            if (subtypes.isNullOrEmpty()) return null
            return subtypes.map {"""{"frame_id":"$it"}"""}
        }
        return null
    }

    fun buildSlotTypes(): List<String> {
        return AngleSlotRegex
                .findAll(utterance)
                .map { it.value.substring(1, it.value.length - 1) }
                .map { bot.getSlotType(owner, it) }
                .toList()
    }

    companion object {
        private val AngleSlotPattern = Pattern.compile("""<(.+?)>""")
        private val AngleSlotRegex = AngleSlotPattern.toRegex()
        val logger: Logger = LoggerFactory.getLogger(Expression::class.java)

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

        // TODO(sean.wu): this should be handled in a more generic fashion.
        private val frameMap = mapOf(
            "io.opencui.core.confirmation.No" to "io.opencui.core.Confirmation",
            "io.opencui.core.confirmation.Yes" to "io.opencui.core.Confirmation",
            "io.opencui.core.hasMore.No" to "io.opencui.core.HasMore",
            "io.opencui.core.hasMore.Yes" to "io.opencui.core.HasMore",
            "io.opencui.core.booleanGate.No" to "io.opencui.core.BoolGate",
            "io.opencui.core.booleanGate.Yes" to "io.opencui.core.BoolGate",
        )
    }
}