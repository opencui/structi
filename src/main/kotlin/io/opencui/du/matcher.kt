package io.opencui.du

import java.io.Serializable
import java.util.regex.Pattern


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
    val emap: MutableMap<String, MutableList<SpanInfo>>,
    val duMeta: DUMeta)
interface Matcher {
    fun match(utterance: String, document: ScoredDocument, context: MatchContext) : Boolean
}


class CompositeMatcher(val matchers: List<Matcher>): Matcher {
    override fun match(utterance: String, document: ScoredDocument, context: MatchContext): Boolean {
        for (matcher in matchers) {
            if (matcher.match(utterance, document, context)) return true
        }
        return false
    }
}

class DummyMatcher : Matcher {
    override fun match(utterance: String, document: ScoredDocument, context: MatchContext): Boolean {
        return document.utterance.trim(*StateTracker.punctuation) == utterance.trim(*StateTracker.punctuation)
    }
}

sealed interface TypedExprSegment: Serializable
data class ExprSegment(val expr: String): TypedExprSegment
data class TypeSegment(val type: String): TypedExprSegment



//
class NestedMatcher : Matcher {

    data class InternalMatcher(val utterance: String, val context: MatchContext) {
        val stack = mutableListOf<Pair<Int, ScoredDocument>>()

        // This function try to see if we can use doc to explain utterance from tokenStart.
        // return -1, if there is no match, or position of last token that got matched.
        // We pay attention to typed expression, whenever we see a type, we try to figure out whether
        // one of the production can explain the rest utterance from the current start.
        // utterance start is token based, and doc start is character based.
        fun coverByDoc(uStart: Int, doc: ScoredDocument, dStart: Int): Int {
            return when {
                dStart == doc.typedExpression.length -> uStart
                uStart == context.tokens.size && dStart != doc.typedExpression.length -> -1
                else -> -1
            }
        }
    }

    override fun match(utterance: String, document: ScoredDocument, context: MatchContext): Boolean {
        val internalMatcher = InternalMatcher(utterance, context)
        return internalMatcher.coverByDoc(0, document, 0) == context.tokens.size
    }
}