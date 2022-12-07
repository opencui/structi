package io.opencui.du


// For only the candidates that are returned from retrieval phase, we do nested matcher.
// There are two different places that we need nested matching:
// 1. Find the exact match. This requires us to test whether utterance and examplar are the same, and
//    at the same time keep the filling information. This matches against the exemplar on the container frame.
// 2. Find the slot frame match, this only matching the exemplar on the slot type.
//
// For now, we assume the tokenization is done by Lucene, for each token we also keep the character offset in
// the original utterance so that we can. 

/**
 * extractive matching of potentially nested expression. This is useful for simpler noun phrases
 * where the structure is relatively stable, and QA model alone is not a good indicate.
 * We will also use this for exact matching, where it is not as efficient as we like.
 * We can have a list of matcher with more and more complexity.
 *
 */
interface Matcher {
    fun match(document: ScoredDocument) : Boolean
}

//
class NestedMatcher(val context: DUContext) : Matcher {
    private val analyzer = LanguageAnalyzer.get(context.duMeta!!.getLang(), stop = false)
    private val duMeta = context.duMeta!!
    var trueType : String? = null

    // This function try to see if we can use doc to explain utterance from tokenStart.
    // return -1, if there is no match, or position of last token that got matched.
    // We pay attention to typed expression, whenever we see a type, we try to figure out whether
    // one of the production can explain the rest utterance from the current start.
    // utterance start is token based, and doc start is character based.
    fun coverFind(uStart: Int, doc: TypedExprSegments): Int {
        var start = uStart
        for(segment in doc.segments) {
            val end = when(segment) {
                is TypeSegment -> typeMatch(start, segment)
                is ExprSegment -> exprMatch(start, segment)
            }
            if (end == -1) return -1
            start = end
        }
        return start
    }

    //
    fun exprMatch(uStart: Int, doc: ExprSegment): Int {
        val tokens = analyzer!!.tokenize(doc.expr)
        for ((index, token) in tokens.withIndex()) {
            val userToken = context.tokens!![uStart + index].token
            val docToken = token.token
            if (userToken != docToken) return -1
        }
        return uStart + tokens.size
    }

    fun typeMatch(uStart: Int, doc: TypeSegment): Int {
        // Go through every exemplar, and find one that matches.
        return when(duMeta.typeKind(doc.type)) {
            TypeKind.Entity -> entityCover(uStart, doc.type)
            TypeKind.Frame -> frameMatch(uStart, doc.type)
            TypeKind.Generic -> genericMatch(uStart)
        }
    }

    // Try to find some entities to cover this, using the longest match.
    fun entityCover(uStart: Int, entityType: String): Int {
        val charStart = context.tokens!![uStart].start
        val entities = context.emapByCharStart[charStart] ?: return -1
        var end = -1
        // for now, we try the longest match.
        for (entity in entities) {
            if(isSubEntityOf(entity.first, entityType)) {
                if (end < entity.second) end = entity.second
            }
        }
        return end
    }

    fun genericMatch(uStart: Int): Int {
        // TODO: we need to consider this under expectation.
        val charStart = context.tokens!![uStart].start
        val entities = context.emapByCharStart[charStart] ?: return -1
        var end = -1
        // for now, we try the longest match.
        for (entity in entities) {
            if (end < entity.second && entity.first == trueType) end = entity.second
        }
        return end
    }

    fun frameMatch(uStart: Int, frameType: String): Int {
        val expressions = duMeta.expressionsByFrame[frameType] ?: return -1
        var last = -1
        for (expression in expressions) {
            val typeExpr = Expression.segment(expression.toTypedExpression(), frameType)
            val res = coverFind(uStart, typeExpr)
            if (res > last) last = res
        }
        return last
    }

    fun isSubEntityOf(first: String, second:String): Boolean {
        var parent : String? = first
        while (parent != null) {
            if (parent == second) return true
            parent = duMeta.getEntityMeta(first)?.getSuper()
        }
        return false
    }


    override fun match(document: ScoredDocument): Boolean {
        // We need to figure out whether there are special match.
        val segments = Expression.segment(document.typedExpression, document.ownerFrame)
        val slotTypes = segments.segments.filter { it is TypeSegment && it.type == SLOTTYPE}
        if (slotTypes.size > 1) throw IllegalStateException("Don't support multiple slots with SlotType yet.")
        if (slotTypes.size == 0 || context.entityTypeToSpanInfoMap[SLOTTYPE].isNullOrEmpty()) {
            trueType = null
            return coverFind(0, segments) == context.tokens!!.size
        } else {
            val matchedSlots = context.entityTypeToSpanInfoMap[SLOTTYPE]!!
            for (matchedSlot in matchedSlots) {
                val trueSlot = matchedSlot.value as String
                val tkns = trueSlot.split(".")
                val frame = tkns.subList(0, tkns.size - 1).joinToString(".")
                if (!context.expectations.isFrameCompatible(frame)) continue
                val slotName = tkns.last()
                trueType = context.duMeta!!.getSlotType(frame, slotName)
                return coverFind(0, segments) == context.tokens!!.size
            }
            return false
        }

    }

    companion object {
        const val SLOTTYPE = "io.opencui.core.SlotType"
    }
}