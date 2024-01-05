package io.opencui.du


// Exemplars are used to make decisions for now.
interface ContextedExemplar {
    var typedExpression: String
    val ownerFrame: String
    val contextFrame: String?
    val entailedSlots: List<String>
    val label: String?

    // whether it is exact match.
    var exactMatch: Boolean
    // The next two are used for potential exect match.
    var possibleExactMatch: Boolean
    var guessedSlot: DUSlotMeta?

}