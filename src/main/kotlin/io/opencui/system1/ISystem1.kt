package io.opencui.system1

import io.opencui.core.*
import io.opencui.core.da.DialogAct
import io.opencui.core.da.RawInform
import io.opencui.core.da.System1Inform
import io.opencui.serialization.JsonElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.*
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberFunctions
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.full.declaredFunctions

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

    fun executeScopedMethod(obj: IFrame, kClass: KClass<*>, methodName: String): CompositeAction? {
        if (kClass.isInstance(obj)) {
            val method = kClass.java.getMethod(methodName) // Get the method using Java reflection
            return method.invoke(obj) as? CompositeAction // Invoke the method and cast the result
        }
        return null
    }

    //  the parents do not change, so we can cache them.
    val cache = mutableMapOf<KClass<*>, List<KClass<*>>>()

    fun implementsOrOverridesInheritedSignature(kClass: KClass<*>, methodName: String): Boolean {
        // 1. Find the CONCRETE method declared in the target class
        val concreteMethodInClass = kClass.declaredFunctions.find {
            it.name == methodName && !it.isAbstract
        } ?: return false // No concrete implementation declared here

        // 2. Get the parameter signature of the concrete method
        val parameterTypes = concreteMethodInClass.parameters.drop(1).map { it.type }

        // 3. Check if ANY direct supertype has a member with the same signature
        var signatureFoundInSupertype = false
        for (supertype in kClass.supertypes) {
            val superClass = supertype.classifier as? KClass<*> ?: continue

            // Look for a matching method signature among the parent's members (can be abstract or concrete)
            val matchingSignatureInSuper = superClass.memberFunctions.find { superMethod ->
                superMethod.name == methodName &&
                superMethod.parameters.drop(1).map { it.type } == parameterTypes
            }

            if (matchingSignatureInSuper != null) {
                signatureFoundInSupertype = true
                break // Found a matching signature, no need to check other supertypes
            }
        }

        // 4. Return true only if both conditions are met
        return signatureFoundInSupertype
    }

    fun getTypesWithMethod(obj: IFrame, methodName: String): List<KClass<*>> {
        val trueType = obj::class
        if (cache.containsKey(trueType)) {
            return cache[trueType]!!
        } else {
            val rawSuperTypes = topologicalSortTypes(trueType)
            val fallbackableSuperTypes = rawSuperTypes
                .filter { it != IFrame::class && it.isSubclassOf(IFrame::class) && implementsOrOverridesInheritedSignature(it, methodName) }
            cache[trueType] = fallbackableSuperTypes
            return fallbackableSuperTypes
        }
    }
}


// Aside from the dialog understanding, builder can actually control the different mode that system1 can be used.
enum class System1Mode {
    FUNCTION,
    ACTION,
    FALLBACK
}

// This is used to separate the reason for bot event.
sealed class System1Event {
    data class Result(val result: JsonElement?=null): System1Event()

    // This is also dialog act.
    data class Response(val dialogAct: DialogAct): System1Event() {
        constructor(templates: Templates) : this(System1Inform.response(templates))
        constructor(payload: String): this( templateOf(payload))
    }

    data class Reason(val dialogAct: DialogAct): System1Event() {
        constructor(templates: Templates) : this(System1Inform.think(templates))
        constructor(payload: String): this( templateOf(payload))
    }

    data class Error(val dialogAct: DialogAct): System1Event() {
        constructor(templates: Templates) : this(System1Inform.error(templates))
        constructor(payload: String): this( templateOf(payload))
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

//
// The main responsibility for triggering system1 is to convert the context from opencui level
// to system1 level (via json), and back.
// Which fallback to be executed is decided dynamically, based on the type hierarchy of the context.
//
// Action does not deal with state, it generates flow<string>.
// function are executed to return something.

data class ToolReference(val context: KClass<*>, val instance: String, val method: String)
data class ToolSetReference(val context: KClass<*>, val instance: String)

interface AugmentContext{}

// This is all the information we need for LLM to perform.
data class Augmentation(
    val instruction: String, // This should be a jinja2 template so that system1 can follow.
    val mode: System1Mode = System1Mode.FALLBACK) {
    var context: AugmentContext? = null
    var source: String? = null
}

// For now, we only support this, but we can potentially support other.
enum class System1Type {
    GooggleADK
}


// Should we make this defined distributedly, or centrally defined like this.
data class ModelConfig(
    val family: String,
    val label: String,
    val providerType: System1Type = System1Type.GooggleADK,
    val url: String? = null,
    val apikey: String? = null,
    val temperature: Float? = null,
    val topK: Int? = null,
    val maxOutputTokens: Int? = null,
    val tags: List<String> = emptyList()
)

enum class ModelSize {
    ENTRY, MIDDLE, ADVANCED, EXPERT
}


// we might have other criteria later.
data class ModelSpec(
    val family: String,
    val size: ModelSize,
    val jsonOutput: Boolean?) {
    fun match(other: ModelSpec): Boolean {
        if (jsonOutput != null && jsonOutput != other.jsonOutput) return false
        // For now, we require family to match.
        return  family == other.family && size >= other.size
    }
}


// Assume you are inside a suspend function
suspend fun Flow<String>.joinToString(sep: String = ", "): String {
    // fold() suspends and processes the flow to produce a single value
    val stringBuilder = fold(StringBuilder()) { builder, value ->
        if (builder.isNotEmpty()) {
            builder.append(sep)
        }
        builder.append(value)
    }
    return stringBuilder.toString()
}

// In order to preserve the standard function semantics, while emit thinking event, we
// rely on coroutine context.
class System1Sink(
    private val emit: suspend (System1Event) -> Unit
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<System1Sink>
    suspend fun send(t: System1Event) = emit(t)
}


//
// All system1 configure use the ChatGPTSystem1 provider config meta.
// System1 will be used for two different things: returning structured output, and/or emitting responses.
// System1 will be connection level,
interface ISystem1 : IExtension {
    // All three modes are entered from here.
    fun response(session: UserSession, augmentation: Augmentation): Flow<System1Event>

    companion object {
        val logger: Logger = LoggerFactory.getLogger(ISystem1::class.java)
        const val MODELSIZE = "model_size"
        const val STRUCTUREDOUTPUT = "structured_output"
        const val MODELFAMILY = "model_family"

        // Use english for now.
        suspend fun renderThinking(session: UserSession, clasName: String, methodName: String, augmentation: Augmentation, system1Builder: ISystem1Builder)  {
            val botStore = Dispatcher.sessionManager.botStore
            if (botStore != null) {
                val key = "summarize:$clasName:$methodName"
                var value = botStore.get(key)
                if (value == null) {
                    val instruction =
                        """Generate a detailed verb phrase to describe what LLM does using the following instruction:  ${augmentation.instruction}."""
                    val summaryAugmentation = Augmentation(instruction, mode = System1Mode.FALLBACK)
                    val system1Action = system1Builder.build(session, summaryAugmentation) as AdkFallback

                    val dialogActs = system1Action.invoke()
                        .filterIsInstance<System1Event.Response>()
                        .map { it.dialogAct }

                    // need to save so that we do not have to run this over and over.
                    value = dialogActs.map { it.templates.pick() }.joinToString("\n")
                    // remember to save so that
                    botStore.set(key, value)
                    logger.info("Save thinking for $key")
                }

                if (value.isNotEmpty()) {
                    logger.info("Emit reason: $value")
                    val sink = currentCoroutineContext()[System1Sink]
                    sink?.send(System1Event.Reason( value))
                }
            }
        }

        /**
         * Separates all configurations into two lists based on a predicate
         * @param predicate function that returns true for configurations that should go in the first list
         * @return Pair of lists: (matching configurations, non-matching configurations)
         */
        fun separateConfigurations(configurables: List<Configuration>, predicate: (Configuration) -> Boolean): Pair<List<Configuration>, List<Configuration>> {
            val matching = mutableListOf<Configuration>()
            val nonMatching = mutableListOf<Configuration>()

            for (config in configurables) {
                if (predicate(config)) {
                    matching.add(config)
                } else {
                    nonMatching.add(config)
                }
            }

            return Pair(matching, nonMatching)
        }

        // Remember the best part rely on sort that is currently missing.
        fun bestMatch(target: Configuration, boundConfiguration: List<Pair<Configuration, ModelSpec>>): Configuration? {
            val targetSpec = target.toModleSpecs()
            for (item in boundConfiguration) {
                if (item.second.match(targetSpec)) {
                    return item.first
                }
            }
            return null
        }

        const val methodName = "getFallback"
        // This method is used to dynamically figure out what most relevant system1 to use.
        fun response(userSession: UserSession): Flow<ActionResult> = flow {
            // We go through the main scheduler and try to find the first one with no empty augmentation.
            logger.info("inside system1 response with ${userSession.schedule.size} filters.")
            for (filler in userSession.schedule.asReversed()) {
                if (filler !is FrameFiller<*>) {
                    continue
                } else {
                    logger.info("inside system1 response with filler $filler")
                }
                // what happens for frame, interface, and value, and what happens for the supertypes.
                val frame = filler.frame()
                logger.info("inside system1 response with frame $frame")
                val fallbackTypes = TypeSystem.getTypesWithMethod(frame, methodName)
                logger.info("inside system1 response with fallback types $fallbackTypes with ${fallbackTypes.size}")
                for (fallbackType in fallbackTypes) {
                    logger.info("inside system1 response with fallback type: $fallbackType")
                    val system1: CompositeAction = TypeSystem.executeScopedMethod(frame, fallbackType, methodName) ?: continue
                    val result = system1.wrappedRun(userSession)
                    logger.info("inside system1 response with result: $result")

                    if (result.botUtterance.isNullOrEmpty()) continue
                    logger.info("inside system1 response with bot utterance: ${result.botUtterance}")
                    emit(result)
                }
            }
        }
    }
}
