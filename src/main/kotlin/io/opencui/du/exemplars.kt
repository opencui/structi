package io.opencui.du


/**
 * One of the key functionality for framely is providing dialog understanding that is
 * hot fixable by regular dev team, instead of by specialized nlu team.
 * The main control that we give regular dev team for control the dialog understanding behavior
 * is the expressions: which describe the mapping from templated surface form text to semantic frame under
 * given context (specified by semantic frame).
 *
 * Instead of keep these in the json, it might be better to keep them as kotlin code, particularly
 * with internal DSL.
 *
 * ExpressionsBuilder is used to build the expressions for one semantic frame. And ExpressionBuilder
 * is used to build one expression that can then be attached to one semantic frame, over and over.
 *
 * The package expression should be integrated into bot scope DuMeta.
 */
data class ExemplarBuilder(val template: String) {
    var label: String? = null
    var contextFrame: String? = null
    var contextSlot: String? = null

    fun label(l: String) {
        label = l
    }

    fun context(f: String, a: String? = null) {
        contextFrame = f
        if (!a.isNullOrEmpty()) {
            contextSlot = a
        }
    }

    fun DontCare() {
        label("DontCare")
    }

    fun getExemplar(owner: String): Exemplar {
        return Exemplar(owner, template, label, contextFrame, contextSlot)
    }
}

class FrameExemplarBuilder (val owner_id: String){
    val expressions = mutableListOf<Exemplar>()
    val subTypes = mutableListOf<String>()


    fun utterance(u: String, init: ExemplarBuilder.() -> Unit = {}) {
        val s = ExemplarBuilder(u)
        s.init()
        expressions.add(s.getExemplar(owner_id))
    }

    // We need to add this so that we make figure out where is the subtype.
    fun subTypes(vararg types: String) {
        subTypes.addAll(types)
    }

    fun toJsonObject() : List<Exemplar> {
        return expressions
    }

    companion object{
        const val OWNERID = "owner_id"
        const val EXPRESSIONS = "expressions"

    }
}

class EntityTypeBuilder(val t: String) {
    val recognizers  = mutableListOf<String>()
    val entities = mutableMapOf<String, List<String>>()
    var parent : String? = null
    var children: List<String> = mutableListOf()
    var pattern: String? = null
    var normalizable: Boolean = true

    fun parent(p : String) {
        parent = p
    }

    fun normalizable(flag: Boolean=true) {
        normalizable = flag
    }

    fun pattern(p: String) {
        pattern = p
    }

    fun children(c: List<String>) {
        children = c
    }

    fun children(vararg c : String) {
        children = c.toList()
    }

    fun entity(u: String, vararg exprs: String) {
        entities[u] = exprs.toList()
    }

    fun recognizer(r: String) {
        recognizers.add(r)
    }

    fun toEntityType() : EntityType {
        return EntityType(t, recognizers, entities, parent, children, pattern, normalizable = normalizable)
    }
}


/**
 * There are two levels of the information needed by du, some at schema level, like get entities,
 * which should be the same for different language; some at language level, where different language should
 * have different implementation (but it should be singleton).
 *
 */
interface LangPack {
    val frames : Map<String, List<Exemplar>>
    val entityTypes: Map<String, EntityType>
    val frameSlotMetas: Map<String, List<DUSlotMeta>>
    val typeAlias: Map<String, List<String>>
    val skills: Set<String>  // We sometime need to whether a qualified name is skill.

    fun frame(ownerId: String, init: FrameExemplarBuilder.() -> Unit) : List<Exemplar> {
        val p = FrameExemplarBuilder(ownerId)
        p.init()
        return p.toJsonObject()
    }

    fun entityType(type: String, init: EntityTypeBuilder.() -> Unit) : EntityType {
        val p = EntityTypeBuilder(type)
        p.init()
        return p.toEntityType()
    }
}