package io.opencui.core

import java.io.Serializable
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty1
import kotlin.reflect.KProperty2

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


interface Frame{}

data class A (var a: Int): Frame{}

data class B (var B: Int): Frame{}

data class Slot(val frame: Frame, val attribute: String): Serializable {
    override fun toString(): String = "${frame::class.qualifiedName}:${attribute}"
}

inline fun <reified T: Frame> slot(frame: T, s: String): Slot {
    return Slot(frame, s)
}

data class Person(val name: String, val age: A)




fun main() {
    val person = Person("Bob", A(25))
    val property: KProperty1<Person, A> = Person::age

    val maps = mutableMapOf<List<KProperty1<*, *>>, String>()
    maps[listOf(Person::name)] = "ws"
    println(property.name)
    println(maps)
    val property1: KProperty1<A, Int> = A::a
    val value: A = property.get(person)
    val value1: Int = property1.get(value)
    println(value1) // Output: 25
}


