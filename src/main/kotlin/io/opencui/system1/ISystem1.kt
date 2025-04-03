package io.opencui.system1

import io.opencui.core.*
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberFunctions


// The goal of the type system is to figure out
object TypeSystem {

    /**
     * Finds all unique supertypes (classes and interfaces) for a given KClass.
     *
     * @param startClass The KClass to analyze.
     * @return A Set containing all unique super KClasses (including Any, if applicable).
     */
    fun getAllSupertypes(startClass: KClass<*>): Set<KClass<*>> {
        val allSupertypes = mutableSetOf<KClass<*>>()
        val queue = ArrayDeque<KClass<*>>() // Use a queue for breadth-first search

        // Add direct supertypes to start the process
        startClass.supertypes.forEach { kType ->
            (kType.classifier as? KClass<*>)?.let { superClass ->
                if (allSupertypes.add(superClass)) { // Add if not already present
                    queue.addLast(superClass)
                }
            }
        }

        // Process the queue until empty
        while (queue.isNotEmpty()) {
            val currentClass = queue.removeFirst()

            currentClass.supertypes.forEach { kType ->
                (kType.classifier as? KClass<*>)?.let { superClass ->
                    // Add the supertype to the results and the queue if it's new
                    if (allSupertypes.add(superClass)) {
                        queue.addLast(superClass)
                    }
                }
            }
        }

        return allSupertypes
    }

    /**
     * Performs a topological sort on a given KClass and its supertypes.
     * The result orders types such that supertypes appear before their subtypes.
     * Requires the getAllSupertypes function.
     *
     * @param startClass The KClass to start the hierarchy analysis from.
     * @return A List<KClass<*>> representing the topologically sorted types,
     * or null if a cycle is detected (should not happen in valid type hierarchies).
     */
    fun topologicalSortTypes(startClass: KClass<*>): List<KClass<*>> {
        // 1. Get all relevant types (start class + all its supertypes)
        val allTypes = getAllSupertypes(startClass) + startClass // Combine into a single set

        // 2. Build the graph representation within the 'allTypes' set
        //    - Adjacency list: Map<SuperType, List<DirectSubType>>
        //    - In-degree count: Map<SubType, Int> (number of direct supertypes *within the set*)
        val adj = mutableMapOf<KClass<*>, MutableList<KClass<*>>>()
        val inDegree = mutableMapOf<KClass<*>, Int>()

        // Initialize maps for all types in our set
        allTypes.forEach { type ->
            adj[type] = mutableListOf()
            inDegree[type] = 0
        }

        // Populate the graph and in-degrees based on direct supertype relationships
        allTypes.forEach { subType ->
            subType.supertypes.forEach { superKType ->
                (superKType.classifier as? KClass<*>)?.let { superType ->
                    // IMPORTANT: Only consider edges where BOTH types are in our relevant set
                    if (superType in allTypes) {
                        // Add edge from superType to subType
                        adj[superType]?.add(subType)
                        // Increment in-degree of the subType
                        inDegree[subType] = (inDegree[subType] ?: 0) + 1
                    }
                }
            }
        }

        // 3. Kahn's Algorithm
        val queue = ArrayDeque<KClass<*>>()
        val sortedResult = mutableListOf<KClass<*>>()

        // Initialize queue with nodes having in-degree 0 (top-level types in our set)
        allTypes.forEach { type ->
            if (inDegree[type] == 0) {
                queue.addLast(type)
            }
        }

        // Process the queue
        while (queue.isNotEmpty()) {
            val currentType = queue.removeFirst()
            sortedResult.add(currentType)

            // For each direct subtype of the current type (within our set)
            adj[currentType]?.forEach { neighborSubType ->
                // Decrement in-degree of the neighbor
                inDegree[neighborSubType] = (inDegree[neighborSubType] ?: 1) - 1 // Should not be null here
                // If in-degree becomes 0, add it to the queue
                if (inDegree[neighborSubType] == 0) {
                    queue.addLast(neighborSubType)
                }
            }
        }

        if (sortedResult.size != allTypes.size) {
            throw IllegalArgumentException("Error: Cycle detected in type hierarchy (or issue with graph building)!")
        }

        // 4. Check for cycles (if result size != total types, there was a cycle)
        return sortedResult
    }

    fun getAugmentation(obj: IFrame, kClass: KClass<*>): Augmentation? {
        if (kClass.isInstance(obj)) {
            val method = kClass.java.getMethod(methodName) // Get the method using Java reflection
            return method.invoke(obj) as? Augmentation // Invoke the method and cast the result
        }
        return null
    }

    const val methodName = "getFallbackAugmentation"
        //  the parents do not change, so we can cache them.
    val cache = mutableMapOf<KClass<*>, List<KClass<*>>>()

    fun hasMethodImplementation(kClass: KClass<*>, methodName: String): Boolean {
        return kClass.memberFunctions.any { it.name == methodName }
    }

    fun getFallbackTypes(obj: IFrame): List<KClass<*>> {
        val trueType = obj::class
        if (cache.containsKey(trueType)) {
            return cache[trueType]!!
        } else {
            val rawSuperTypes = topologicalSortTypes(trueType)
            val fallbackableSuperTypes = rawSuperTypes
                .filter { it.isSubclassOf(IFrame::class) && hasMethodImplementation(it, methodName) }
            cache[trueType] = fallbackableSuperTypes
            return fallbackableSuperTypes
        }
    }
}


/**
 * It is likely that the users are actually served by two systems: fast and slow.
 * The fast system can take care of many intuitive, automatic thinking that operates quickly and effortlessly.
 * So that builder can focus on more important aspects that needed more deliberation.
 * ISystem1 is used to capture one or more fast system.
 * The implementation, however, does not need to support the energy saving aspects of the system.
 *
 * For now, we assume that all system 1 come with multiple language support.
 * System1 can be smart or dumb, smart system can return empty when it knows it has no good things to say.
 * Dumb system will always return something.
 * For now, we assume the system1 are dumb.
 */

interface ISystem1 : IExtension {
    //  msgs and feedback are mutually exclusive.
    fun response(msgs: List<CoreMessage>, augmentation: Augmentation): String

    companion object {
        fun response(userSession: UserSession): String? {
            // We go through the main scheduler and try to find the first one with no empty augmentation.
            for (filler in userSession.schedule.asReversed()) {
                if (filler !is FrameFiller<*>) continue
                // what happens for frame, interface, and value, and what happens for the supertypes.
                val frame = filler.frame()
                val fallbackTypes = TypeSystem.getFallbackTypes(frame)
                for (fallbackType in fallbackTypes) {
                    val augmentation: Augmentation = TypeSystem.getAugmentation(frame, fallbackType) ?: continue
                    val modelName = augmentation.inferenceConfig.model
                    val system1 = userSession.chatbot!!.getExtension<ISystem1>(modelName) ?: continue
                    val result = system1.response(userSession.history, augmentation)
                    if (result.isEmpty()) continue
                    return result
                }
            }
            return null
        }
    }
}

