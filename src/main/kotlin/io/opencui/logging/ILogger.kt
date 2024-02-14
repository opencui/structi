package io.opencui.logging

import io.opencui.serialization.JsonElement
import java.time.Duration
import java.time.LocalDateTime


data class Turn(
    val utterance: String,
    val expectations: JsonElement,  // this should be an array of expectation, each is an object.
    val predictedFrameEvents: JsonElement,   // again an array of events.
    val generatedDialogActs: JsonElement,    // an array of dialog acts.
    val timeStamp: LocalDateTime,
    val duTime: Long,
)  {
    var trueFrameEvents: JsonElement? = null  // this is provided manually when there are mistakes
    var dmTime: Duration? = null // We might need this.
    var nluVersion: String? = null
    var duVersion: String? = null
    lateinit var channelType: String
    lateinit var channelLabel: String
    lateinit var userId: String
}

interface ILogger {
    fun log(turn: Turn)
}