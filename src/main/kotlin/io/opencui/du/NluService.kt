package io.opencui.du

import org.slf4j.LoggerFactory

enum class DugMode {
    SKILL,
    SLOT,
    BINARY,
    SEGMENT
}


// the result from YesNoInference
enum class YesNoResult {
    Affirmative,
    Negative,
    Indifferent,
    Irrelevant
}

data class SlotValue(val values: List<String>, val operator: String  = "==")

interface NluService {

    companion object {
        val logger = LoggerFactory.getLogger(NluService::class.java)
    }

    // This returns skills (skills requires attention automatically even not immediately but one by one, not frames)
    fun detectTriggerables(utterance: String, expectations: DialogExpectations): List<Triggerable>

    // handle all slots.
    fun fillSlots(utterance: String, slots: Map<String, DUSlotMeta>, entities: Map<String, List<String>>): Map<String, SlotValue>

    fun yesNoInference(utterance: String, question: List<String>): List<YesNoResult>
}

