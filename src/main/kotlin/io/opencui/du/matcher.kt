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
data class MatchContext(
    val tokens: List<BoundToken>,
    val emap: Map<String, List<SpanInfo>>,
    val duMeta: DUMeta) {

    val emapByCharStart = convert()
    fun convert(): Map<Int, List<Pair<String, Int>>> {
        // create the char end to token end.
        val endMap = mutableMapOf<Int, Int>()
        for ((index, token) in tokens.withIndex()) {
            endMap[token.end] = index + 1
        }

        val result = mutableMapOf<Int, MutableList<Pair<String, Int>>>()
        for((key, spans) in emap) {
            for (span in spans) {
                if (!result.containsKey(span.start)) result[span.start] = mutableListOf()
                result[span.start]!!.add(Pair(key, endMap[span.end]!!))
            }
        }
        return result
    }
}
interface Matcher {
    fun match(utterance: String, document: TypedExprSegments, context: MatchContext) : Boolean
}

//
class NestedMatcher : Matcher {

    data class InternalMatcher(val utterance: String, val context: MatchContext) {
        private val analyzer = LanguageAnalzyer.get(context.duMeta.getLang())
        private val duMeta = context.duMeta



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
                if (context.tokens[uStart + index] != token) return -1
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
            val charStart = context.tokens[uStart].start
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
            val charStart = context.tokens[uStart].start
            val entities = context.emapByCharStart[charStart] ?: return -1
            var end = -1
            // for now, we try the longest match.
            for (entity in entities) {
                if (end < entity.second) end = entity.second
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

    }

    override fun match(utterance: String, document: TypedExprSegments, context: MatchContext): Boolean {
        val internalMatcher = InternalMatcher(utterance, context)
        return internalMatcher.coverFind(0, document) == context.tokens.size
    }
}