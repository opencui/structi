package io.opencui.core

import kotlin.reflect.KFunction

/**
 * One of the key issue with recommendation is we need to be able to adjust the recommendation
 * based on the changes on the auxiliary slots, so that we can give user the most relevant choices.
 *
 */

// Function to wrap another function with a fixed parameter and unknown other parameters
data class wrapFunction<T>(val pageSize: Int, val targetFunction: KFunction<*>) {

    var start: Int = 0

    operator fun invoke(vararg otherParams: Any) : T {
        // Create an array with fixedParam1 followed by otherParams
        val allParams = arrayOf(start, pageSize, *otherParams)

        // Use reflection to call the target function with allParams
        return targetFunction.call(*allParams) as T
    }
}

// This is used for filter the slot
interface Filter<T>: (T) -> Boolean


class TwoStageRecommender<T, R>(val source: () -> List<T>, val convert: (T) -> R) : () -> List<R> {




    override operator fun invoke(): List<R> {
        return source().map {convert(it) }
    }

}

fun test(x: Int, y: Int, z: String) {
    println("$x: $y : $z")
}

fun main() {
    val tester = ::test

    val wrapper = wrapFunction<Unit>(5, tester)

    wrapper(1, "welcome here")
}