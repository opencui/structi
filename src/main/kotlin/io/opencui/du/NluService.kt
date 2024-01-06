package io.opencui.du

import org.slf4j.LoggerFactory

enum class DugMode {
    SKILL,
    SLOT,
    BINARY,
    SEGMENT
}

enum class BinaryResult {
    TRUE,
    FALSE,
    DONTCARE,
    IRRELEVANT
}

data class SlotValue(val operator: String, val values: List<String>)

interface NluService {

    companion object {
        val logger = LoggerFactory.getLogger(NluService::class.java)
    }

    // This returns skills (skills requires attention automatically even not immediately but one by one, not frames)
    fun detectTriggerables(utterance: String, expectations: DialogExpectations): List<ExampledLabel>

    // handle all slots.
    fun fillSlots(utterance: String, slots: Map<String, DUSlotMeta>, entities: Map<String, List<String>>): Map<String, SlotValue>

    fun yesNoInference(utterance: String, question: String): BinaryResult
}

