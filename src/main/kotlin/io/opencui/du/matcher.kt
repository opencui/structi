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
    fun markMatch(document: IExemplar)
}


data class MatchState(
    val pos: Int,
    val usedFrames: List<String> = emptyList(),
) {
    fun addFrame(frame: String) : MatchState {
        return MatchState(pos, usedFrames + listOf(frame))
    }
}

//
class NestedMatcher(val context: DuContext) : Matcher {
    private val analyzer = LanguageAnalyzer.get(context.duMeta!!.getLang(), stop = false)
    private val duMeta = context.duMeta!!
    private var trueType : String? = null

    // This function try to see if we can use doc to explain utterance from tokenStart.
    // return -1, if there is no match, or position of last token that got matched.
    // We pay attention to typed expression, whenever we see a type, we try to figure out whether
    // one of the production can explain the rest utterance from the current start.
    // utterance start is token based, and doc start is character based.
    private fun coverFind(uStart: MatchState, doc: MetaExprSegments): MatchState {
        var start = uStart
        for(segment in doc.segments) {
            val end = when(segment) {
                is MetaSegment -> typeMatch(start, segment)
                is ExprSegment -> exprMatch(start, segment)
            }
            if (end.pos == -1) return MatchState(-1)
            start = end
        }
        return start
    }

    //
    private fun exprMatch(uStart: MatchState, doc: ExprSegment): MatchState {
        val tokens = analyzer!!.tokenize(doc.expr)
        for ((index, token) in tokens.withIndex()) {
            if (uStart.pos + index >= context.tokens!!.size) return MatchState(-1)
            val userToken = context.tokens!![uStart.pos + index].token
            val docToken = token.token
            if (userToken != docToken) return MatchState(-1)
        }
        return MatchState(uStart.pos + tokens.size, uStart.usedFrames)
    }

    private fun typeMatch(uStart: MatchState, doc: MetaSegment): MatchState {
        // Go through every exemplar, and find one that matches.
        return when(duMeta.typeKind(doc.meta)) {
            TypeKind.Entity -> entityCover(uStart, doc.meta)
            TypeKind.Frame -> frameMatch(uStart, doc.meta)
            TypeKind.Generic -> genericMatch(uStart)
        }
    }

    // Try to find some entities to cover this, using the longest match.
    private fun entityCover(uStart: MatchState, entityType: String): MatchState {
        // TODO: why we got this point need to be checked.
        if (uStart.pos >= context.tokens!!.size) return MatchState(-1)
        val charStart = context.tokens!![uStart.pos].start
        val entities = context.emapByCharStart[charStart] ?: return MatchState(-1)
        var end = -1
        // for now, we try the longest match.
        for (entity in entities) {
            if(isSubEntityOf(entity.first, entityType)) {
                if (end < entity.second) end = entity.second
            }
        }
        return MatchState(end, uStart.usedFrames)
    }

    private fun genericMatch(uStart: MatchState): MatchState {
        // TODO: we need to consider this under expectation.
        val charStart = context.tokens!![uStart.pos].start
        val entities = context.emapByCharStart[charStart] ?: return MatchState(-1)
        var end = -1
        // for now, we try the longest match.
        for (entity in entities) {
            if (end < entity.second && entity.first == trueType) end = entity.second
        }
        return MatchState(end, uStart.usedFrames)
    }

    private fun frameMatch(uStart: MatchState, frameType: String): MatchState {
        val expressions = duMeta.getExpressions(frameType) ?: return MatchState(-1)

        // Easy to trace.
        var last = MatchState(-1)
        for (expression in expressions) {
            val typeExpr = Exemplar.segment(expression.typedExpression(context.duMeta!!), frameType)
            val res = coverFind(uStart, typeExpr)
            if (res.pos > last.pos) last = res
        }
        return last.addFrame(frameType)
    }

    private fun isSubEntityOf(first: String, second:String): Boolean {
        var parent : String? = first
        while (parent != null) {
            if (parent == second) return true
            parent = duMeta.getEntityMeta(first)?.getSuper()
        }
        return false
    }

    override fun markMatch(document: IExemplar) {
        // We need to figure out whether there are special match.
        val segments = Exemplar.segment(document.typedExpression, document.ownerFrame)
        val slotTypes = segments.segments.filter { it is MetaSegment && it.meta == SLOTTYPE}
        if (slotTypes.size > 1) throw IllegalStateException("Don't support multiple slots with SlotType yet.")
        if (slotTypes.isEmpty() || context.entityTypeToValueInfoMap[SLOTTYPE].isNullOrEmpty()) {
            if (!segments.useGenericType()) {
                trueType = null
                val matchState = coverFind(MatchState(0), segments)
                document.exactMatch = matchState.pos == context.tokens!!.size
                document.usedFramesInType.addAll(matchState.usedFrames)
            } else {
                // Here we find a potential exact match, based on guess a slot.
                // Another choice when there are multiple choices we clarify.
                val slots = duMeta.getSlotMetas(context.expectations)
                for (slot in slots) {
                    if (context.entityTypeToValueInfoMap.containsKey(slot.type)) {
                        trueType = slot.type
                        val matchState = coverFind(MatchState(0), segments)
                        val matched = matchState.pos == context.tokens!!.size
                        if (matched) {
                            // TODO: how do we handle the
                            document.exactMatch = true
                            assert(document.ownerFrame == "io.opencui.core.SlotUpdate")
                            document.guessedSlot = slot
                            // find the first one and escape.
                            break
                        }
                    }
                }
            }
        } else {
            // For updateSlot
            val matchedSlots = context.entityTypeToValueInfoMap[SLOTTYPE]!!
            for (matchedSlot in matchedSlots) {
                val trueSlot = matchedSlot.value as String? ?: continue
                val tkns = trueSlot.split(".")
                val frame = tkns.subList(0, tkns.size - 1).joinToString(".")
                if (!context.expectations.isFrameCompatible(frame)) continue
                val slotName = tkns.last()
                trueType = context.duMeta!!.getSlotType(frame, slotName)
                val matchState =  coverFind(MatchState(0), segments)
                document.exactMatch = matchState.pos == context.tokens!!.size
            }
        }
    }

    companion object {
        const val SLOTTYPE = "io.opencui.core.SlotType"
    }
}
