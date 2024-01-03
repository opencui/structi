package io.opencui.du

/**
 * The expression on frame with generic type like SlotType need to be resolved to some degree
 * before it can be fed into model.
 */
object SlotTypeResolver : Resolver {
    class InternalResolver(val context:DuContext) {
        private val analyzer = LanguageAnalyzer.get(context.duMeta!!.getLang(), stop = false)
        private val duMeta = context.duMeta!!

        fun resolve(document: ScoredDocument, output: MutableList<ScoredDocument>) {
            // The goal is to create typed expression.
            val segments = Expression.segment(document.utterance, document.ownerFrame).segments
            // For now, we only deal with the use case where the expression contains
            // the slot of the SlotType
            val potentialSlotTypeMetas = context.duMeta!!.getSlotMetas(document.ownerFrame).filter {it.type == SLOTTYPE}
            if (potentialSlotTypeMetas.size > 1) throw  IllegalStateException("Don't support multiple slots with SlotType yet.")
            if (potentialSlotTypeMetas.isEmpty()) {
                output.add(document)
                return
            }

            val actualSlotTypes = segments.filter {
                it is MetaSegment && context.duMeta!!.getSlotType(document.ownerFrame, it.meta) == SLOTTYPE }
            if (actualSlotTypes.isEmpty() || !context.entityTypeToSpanInfoMap.containsKey(SLOTTYPE)) {
                //  TODO: guess the true type for generic type.
                output.add(document)
            } else {
                val matchedSlots = context.entityTypeToSpanInfoMap[SLOTTYPE]!!
                for (matchedSlot in matchedSlots) {
                    val trueSlot = matchedSlot.value as String
                    val tkns = trueSlot.split(".")
                    val frame = tkns.subList(0, tkns.size - 1).joinToString(".")
                    if (!context.expectations.isFrameCompatible(frame)) continue
                    val slotName = tkns.last()
                    val trueType = context.duMeta!!.getSlotType(frame, slotName)!!

                    // for each match, we create a new scored document.
                    val stringBuilder = StringBuilder()
                    for (segment in segments) {
                        if (stringBuilder.length != 0) stringBuilder.append(" ")
                        when (segment) {
                            is ExprSegment -> stringBuilder.append(segment.expr)
                            is MetaSegment -> {
                                val type = context.duMeta!!.getSlotType(document.ownerFrame, segment.meta)
                                val typeKind = context.duMeta!!.typeKind(type)
                                //
                                if (type != SLOTTYPE && typeKind != TypeKind.Generic) {
                                    stringBuilder.append("<${segment.meta}>")
                                } else {
                                    if (type == SLOTTYPE) {
                                        val norm = duMeta.getSlotTriggers()[matchedSlot.value]?.firstOrNull() ?: toString()
                                        stringBuilder.append(norm)
                                    } else {
                                        stringBuilder.append("<$trueType>")
                                    }
                                }
                            }
                        }
                    }
                    val newDoc = ScoredDocument(document.score, document.document)
                    newDoc.typedExpression = stringBuilder.toString()
                    output.add(newDoc)
                }
            }
        }

        companion object {
            const val SLOTTYPE = "io.opencui.core.SlotType"
        }
    }

    // We might produce more than one documents for some input.
    override fun resolve(ducontext: DuContext, before: List<ScoredDocument>): List<ScoredDocument> {
        val result = mutableListOf<ScoredDocument>()
        val internalResolver =  InternalResolver(ducontext)
        before.map {
            internalResolver.resolve(it, result)
        }
        return result
    }
}