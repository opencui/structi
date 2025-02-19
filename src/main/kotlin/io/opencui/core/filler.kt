package io.opencui.core

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import io.opencui.core.da.DumbDialogAct
import io.opencui.core.hasMore.No
import io.opencui.serialization.Json
import io.opencui.serialization.JsonObject
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstanceOrNull
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.security.SecurityProperties.User
import java.io.Serializable
import kotlin.collections.LinkedHashMap
import kotlin.math.max
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KMutableProperty0
import kotlin.reflect.KProperty1
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties

/**
 * Each filler is a statechart itself, but also a part of larger statechart.
 */

/**
 * This is really like static graph, we first build graph itself, and then let graph run.
 * So it is a bit difficult to keep track of both graph building and graph running. Of course,
 * Graph here is state chart, or nested state machines.
 *
 * To make context read writable, we need to make some assumptions: there is only one frame
 * in focus, which interfaces can be referred as context by small intents. And we can only
 * access it locally, or top frame of filling stack.
 *
 * The correct behavior depends on two things:
 * 1. the session manager need to use isCompatible to filter the frames so that only compatible
 *    frame is push to dm.
 * 2. code generation need to make sure the context slot can be filled, simply by
 *    val filler = session.getFiller(slotName)
 *    if (filler != null and filler.notDone()) {
 *      addFiller(filler)
 *    }
 */

/**
 * On filler, the follow configuration also holds, given events: Map<String, SlotEvent>
 * 1. done(events) == true
 * 2. done(events) == false, hasOpenSlot(events) == true  then {pick(events) != null)
 * 3. done(events) == false, hasOpenSlot(events) == false then {choose(events) != null)
 *
 * For slot, done(events) is simply decided by state.
 * For frame: the done(events) logic should be:
 *      if hasOpenSlot(events) == true: return false.
 *      else for each frame: if frame.state != DONE, or frame.done(events) == false, return false
 *      return true
 */

/**
 * This is used to create the path so that we can find the best annotation. Frame is the host
 * type and attribute is the child, the type claimed on attribute is the declared type.
 *
 * For interface, we add a param with empty string.
 */
data class Branch(val host: IFrame, val attribute: String): Serializable {
    override fun toString(): String = "${host::class.qualifiedName}:${attribute}"
    fun isRoot() = attribute == ParamPath.ROOT
    fun isNotRoot() = attribute != ParamPath.ROOT
}


data class ParamPath(val path: List<Branch>): Serializable {
    constructor(frame: IFrame): this(listOf(Branch(frame, ROOT))) {}

    override fun toString(): String {
        return path.joinToString { "${it.host::class.qualifiedName}:${it.attribute}" }
    }

    fun last() : Branch = path.last()

    val leafAttribute: String
        get() {
            val last = path.last()
            return if (last.isNotRoot()) {
                last.attribute
            } else {
                if (path.size == 1) last.host::class.simpleName!! else path[path.size - 2].attribute
            }
        }

    fun join(a: String, nf: IFrame? = null): ParamPath {
        val last = path.last()
        val list = mutableListOf<Branch>()
        list.addAll(path.subList(0, path.size - 1))
        // Make sure the last meaningful.
        if(!(last.isRoot() || last.attribute.endsWith(DOTITEM))) {
            println("whole = $this")
            println("last = $last | a = $a | nf = $nf")
        }
        list.add(Branch(last.host, a))
        if (nf != null) {
            // throw RuntimeException()
            list.add(Branch(nf, ROOT))
        }
        return ParamPath(list)
    }

    fun root(): IFrame {
        return path[0].host
    }

    fun restAttribute(i: Int) =
        if (i != path.size - 1) {
            path.subList(i, path.size).filter { it.attribute != ROOT }.joinToString(separator = ".") { it.attribute }
        } else {
            path.last().attribute
        }

    inline fun <reified T : Annotation> findAll(): List<T> {
        return path.indices.firstNotNullOfOrNull { idx -> pathFind<T>(idx).takeIf { it.isNotEmpty() } } ?: emptyList()
    }

    // we need a path since granularity of runtime annotations are finer than that of platform's
    inline fun <reified T : Annotation> pathFind(idx: Int): List<T> {
        val rpath = restAttribute(idx)
        val frame = path[idx].host
        val clazz = T::class
        when {
            clazz.isSubclassOf(PromptAnnotation::class) -> {
                val origAnno: List<T> = frame.findAll(rpath)
                if (origAnno.isNotEmpty()) return origAnno
                if (rpath.endsWith(REALTYPE)) {
                    val currentPath = rpath.substringBeforeLast(".$REALTYPE")
                    val interfaceSlotAnno: List<T> = frame.findAll(currentPath)
                    if (interfaceSlotAnno.isNotEmpty()) return interfaceSlotAnno
                }
                if (rpath.endsWith(ITEM)) {
                    val currentPath = rpath.substringBeforeLast(".$ITEM")
                    val mvSlotAnno: List<T> = frame.findAll(currentPath)
                    if (mvSlotAnno.isNotEmpty()) return mvSlotAnno
                }
                return emptyList()
            }
            clazz == IValueRecAnnotation::class -> {
                val finalPath = if (rpath.endsWith("$HAST.status.$REALTYPE")) {
                    rpath.substringBeforeLast(".$HAST.status.$REALTYPE")
                } else if (rpath.endsWith(".$ITEM")) {
                    rpath.substringBeforeLast(".$ITEM")
                } else {
                    rpath
                }
                return frame.findAll(finalPath)
            }
            else -> {
                return frame.findAll(rpath)
            }
        }
    }

    companion object {
        const val ROOT = "this"
        const val ITEM = "_item"
        const val DOTITEM= "._item"
        const val HAST = "_hast"
        const val DOTHAST = "._hast"
        const val REALTYPE = "_realtype"
    }
}

/**
 * This is useful to carry out the default picking. There are two different designs that
 * we can follow: one is to use reflection, another is to use "compiler" technique where
 * we generate the all the things need so that we do need to reflect.
 *
 * How should we handle input events:
 * when we have some input, if it is compatible with existing focus, we should just try to
 * either consume it, or we should save for the later consumption. We can follow the simple
 * strategy for now, every time, we get FrameEvents from end user, we then also handle it
 * right then.
 *
 * User always input FrameEvents, and the frame in each FrameEvent is always set (It might
 * be filled via expectation).
 *
 * There are potentially two stages of input processing: first we decide whether we will need
 * it by current main intent, if we are, then we push it to one stack. If we are not, we push
 * it to another stack (or just throw it away?)
 *
 */
interface IFiller: Compatible, Serializable {
    var parent: ICompositeFiller?
    var path: ParamPath?
    val decorativeAnnotations: MutableList<Annotation>

    val attribute: String
        get() = path!!.leafAttribute

    // Make scheduler state move on to next one, return true if we moved, if there are no legit
    // state to move to return false.
    fun move(session: UserSession, flatEvents: List<FrameEvent>): Boolean = false

    fun isMV(): Boolean = false

    fun onPop() {}

    fun onPush() {}

    // Is filler considered to be done given remaining events.
    // done means can neither move nor grow
    fun done(frameEvents: List<FrameEvent>): Boolean

    fun clear() {
        decorativeAnnotations.clear()
    }

    fun slotAskAnnotation(): PromptAnnotation? {
        val decorativePrompt = decorativeAnnotations.firstIsInstanceOrNull<PromptAnnotation>()
        if (decorativePrompt != null) return decorativePrompt
        return path?.let { it.findAll<PromptAnnotation>().firstOrNull() }
    }

    fun slotInformActionAnnotation(): SlotInformActionAnnotation? {
        return path?.let { it.findAll<SlotInformActionAnnotation>().firstOrNull() }
    }

    fun askStrategy(): AskStrategy {
        return path!!.findAll<AskStrategy>().firstOrNull() ?: AlwaysAsk()
    }

    // fully type for compatible FrameEvent
    fun qualifiedEventType(): String? {
        return null
    }

    fun simpleEventType(): String? {
        var typeStr = qualifiedEventType() ?: return null
        val lastIndex = typeStr.lastIndexOf('.')
        if (lastIndex != -1) {
            typeStr = typeStr.substring(lastIndex + 1)
        }
        return typeStr
    }

    fun isForInterfaceOrMultiValue(): Boolean {
        return this is InterfaceFiller<*> || this is MultiValueFiller<*>
    }
}

// The goal of this to fill the slot from typed string form, to typed form.
abstract class AEntityFiller : IFiller, Committable {
    override var path: ParamPath? = null
    override var parent: ICompositeFiller? = null
    override val decorativeAnnotations: MutableList<Annotation> = mutableListOf()

    // it is important to keep FrameEvent in filler in order to tell whether it is autofilled
    var event: FrameEvent? = null
    var done: Boolean = false

    override fun done(frameEvents: List<FrameEvent>): Boolean = done

    override fun move(session: UserSession, flatEvents: List<FrameEvent>): Boolean {
        val frameEvent: FrameEvent? =
            flatEvents.firstOrNull { isCompatible(it) } ?:
            flatEvents.firstOrNull {
                !it.isUsed && !it.inferredFrom && it.turnId == session.turnId && (this as? Infer)?.infer(it) != null
            }
        if (frameEvent == null) {
            session.schedule.state = Scheduler.State.ASK
        } else {
            session.schedule.state = Scheduler.State.POST_ASK
        }
        return true
    }
}

interface TypedFiller<T> {
    val target: KMutableProperty0<T?>

    fun rawTypeStr(): String {
        val obj = target.get() ?: return target.returnType.toString()
        return obj::class.qualifiedName ?: target.returnType.toString()
    }

    fun qualifiedTypeStr(): String {
        val typeStr = rawTypeStr().let {
            if (it.endsWith("?")) it.dropLast(1) else it
        }
        return typeStr.substringBefore("<")
    }
}

class EntityFiller<T>(
    val buildSink: () -> KMutableProperty0<T?>,
    val origSetter: ((String?) -> Unit)? = null,
    val builder: (String, String?) -> T?) : AEntityFiller(), TypedFiller<T> {
    constructor(buildSink: () -> KMutableProperty0<T?>,
                origSetter: ((String?) -> Unit)? = null,
                builder: (String) -> T?): this(buildSink, origSetter, {s, _ -> builder(s)})

    override val target: KMutableProperty0<T?>
        get() = buildSink()

    var value: String? = null
    var origValue: String? = null
    var valueGood: ((String, String?) -> Boolean)? = null

    init {
        valueGood = {
            s, t ->
            try {
                builder(s, t) != null
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    // We will check if there are negate value.
    override fun done(frameEvents: List<FrameEvent>): Boolean {
        // If we find value with alternative value, we handle it.
        for (frameEvent in frameEvents) {
            val related = frameEvent.slots.find {
                    it.attribute == attribute
                            && !it.isUsed
                            && it.semantic != CompanionType.AND
            }
            if (related != null) return false
        }
        return done
    }

    override val attribute: String
        get() = if (super.attribute.endsWith("._item")) super.attribute.substringBeforeLast("._item") else super.attribute

    override fun clear() {
        value = null
        origValue = null
        event = null
        origSetter?.invoke(null)
        target.set(null)
        done = false
        super.clear()
    }

    override fun qualifiedEventType(): String {
        val frameType = path!!.last().host::class.qualifiedName!!.let {
            if (it.endsWith("?")) it.dropLast(1) else it
        }
        return frameType.substringBefore("<")
    }

    override fun isCompatible(frameEvent: FrameEvent): Boolean {
        return simpleEventType() == frameEvent.type && frameEvent.activeEntitySlots.any { it.attribute == attribute }
    }

    override fun commit(frameEvent: FrameEvent): Boolean {
        val related = frameEvent.slots.find { it.attribute == attribute && !it.isUsed }!!
        related.isUsed = true

        if (related.semantic == CompanionType.AND) {
            if (valueGood != null && !valueGood!!.invoke(related.value, related.type)) return false
            target.set(builder.invoke(related.value, related.type))
            value = related.value
            origValue = related.origValue
            event = frameEvent
            decorativeAnnotations.clear()
            decorativeAnnotations.addAll(related.decorativeAnnotations)
            origSetter?.invoke(origValue)
            done = true
        }

        if (related.semantic == CompanionType.NEGATE) {
            val newValue = builder.invoke(related.value, related.type)
            val oldValue = target.get()
            if (oldValue == newValue) {
                clear()
            }
        }

        return true
    }

    companion object {
        inline fun <reified T> build(
            session: UserSession,
            noinline buildSink: () -> KMutableProperty0<T?>
        ): EntityFiller<T> {
            val fullName = T::class.java.canonicalName
            val builder: (String) -> T? = { s ->
                Json.decodeFromString(s, session!!.findKClass(fullName)!!) as? T
            }
            return EntityFiller(buildSink, null, builder)
        }
    }
}



class RealTypeFiller(
    override val target: KMutableProperty0<String?>,
    val inferFun: ((FrameEvent) -> FrameEvent?)? = null,
    val checker: (String) -> Boolean,
    val callback: (FrameEvent) -> Unit
): AEntityFiller(), TypedFiller<String>, Infer {
    var value: String? = null
    var origValue: String? = null
    var valueGood: ((String, String?) -> Boolean)? = null

    init {
        valueGood = {
            s, _ ->
            try {
                checker(s)
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    override fun commit(frameEvent: FrameEvent): Boolean {
        frameEvent.typeUsed = true

        val type = if (frameEvent.packageName.isNullOrEmpty()) {
            frameEvent.type
        } else {
            "${frameEvent.packageName}.${frameEvent.type}" }

        if (valueGood != null && !valueGood!!.invoke(type, null)) return false
        target.set(type)
        value = type
        origValue = type
        event = frameEvent
        done = true
        callback(frameEvent)
        return true
    }

    override fun infer(frameEvent: FrameEvent): FrameEvent? {
        return inferFun?.invoke(frameEvent)
    }

    override fun clear() {
        value = null
        origValue = null
        target.set(null)
        done = false
        super.clear()
    }

    override fun isCompatible(frameEvent: FrameEvent): Boolean {
        val type = if (frameEvent.packageName.isNullOrEmpty()) {
            frameEvent.type
        } else {
            "${frameEvent.packageName}.${frameEvent.type}"
        }
        return !frameEvent.isUsed && checker(type)
    }
}

/**
 * The filling site is used to host the actual filling on slot. We maintain a stack of it in
 * schedule, the schedule is considered to be ready if the top of stack is open: or is not closed
 * and also have at least one open slot filler need to be filled.
 *
 * It is active when we pop its open slot filler to focus, and start to fill it. This is done by
 * first check whether there are material that we can already consume, and if we can, we will pick
 * the slot that has proposed filling already.
 *
 * When ready, there are three different cases:
 * 1. there is not proposed fillings, we just start to pick the first slot that is NOT close for filling.
 * 2. there is proposed fillings for this site, we just pick these instead (what happens if it is filled,
 *    we should do something based on the annotation).
 * 3. after we consume all the local fillings, we will check whether there are out of context fillings
 *    that is annotated as branch, if so, we will try to change schedule so that we can fill these
 *    branching slot.
 *
 * note: for the no branch and no local things, we just let it stay there, waiting to be consumed.
 */
interface ICompositeFiller : IFiller {
    // if the top filler is unable to move, we grow scheduler
    fun grow(session: UserSession, flatEvents: List<FrameEvent>): Boolean = false
}

// The goal of this to fill the slot from typed string form, to typed form.
abstract class AFrameFiller : ICompositeFiller, Committable {
    override var path: ParamPath? = null
    override var parent: ICompositeFiller? = null
    override val decorativeAnnotations: MutableList<Annotation> = mutableListOf()

    // it is important to keep FrameEvent in filler in order to tell whether it is autofilled
    var event: FrameEvent? = null
    var done: Boolean = false

    override fun done(frameEvents: List<FrameEvent>): Boolean = done

    override fun move(session: UserSession, flatEvents: List<FrameEvent>): Boolean {
        val frameEvent: FrameEvent? =
            flatEvents.firstOrNull { isCompatible(it) } ?:
            flatEvents.firstOrNull {
                !it.isUsed && !it.inferredFrom && it.turnId == session.turnId && (this as? Infer)?.infer(it) != null
            }
        if (frameEvent == null) {
            session.schedule.state = Scheduler.State.ASK
        } else {
            session.schedule.state = Scheduler.State.POST_ASK
        }
        return true
    }
}



// This filler is used to fill the helper so that we do not have to mess up the state for the
// original filler.
class HelperFiller<T>(
    override val target: KMutableProperty0<Helper<T>?>,
    val helper: Helper<T>,
    val builder: (String, String?) -> T?
) :  AFrameFiller(), TypedFiller<Helper<T>> {

    var valueGood: ((String, String?) -> Boolean)? = null
    var entityEvent : EntityEvent? = null

    init {
        valueGood = {
                s, t ->
            try {
                builder(s, t) != null
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    override fun clear() {
        // For now, we support two level clear.
        if (entityEvent != null) {
            // small clear remove the entityEvent so that next clear will clear everything.
            if (entityEvent!!.semantic == CompanionType.NEGATE) {
                // if the last event is negation, clear should remove that from not.
                val typedValue = builder.invoke(entityEvent!!.value, entityEvent!!.type)
                helper.not.removeIf { it == typedValue }
            }

            entityEvent = null
        } else {
            helper.clear()
            super.clear()
        }
        // for helper, we are always done.
        super.clear()
        done = false
    }

    override fun qualifiedEventType(): String {
        val frameType = path!!.last().host::class.qualifiedName!!.let {
            if (it.endsWith("?")) it.dropLast(1) else it
        }
        return frameType.substringBefore("<")
    }

   override val attribute: String
        get() = if (super.attribute.endsWith("._item"))
            super.attribute.substringBeforeLast("._item")
        else
            super.attribute

    override fun isCompatible(frameEvent: FrameEvent): Boolean {
        val typeAgree = simpleEventType() == frameEvent.type
        val attributeMatch = frameEvent.activeEntitySlots.any { it.attribute == attribute }
        return typeAgree && attributeMatch
    }

    override fun commit(frameEvent: FrameEvent): Boolean {
        val related = frameEvent.slots.find { it.attribute == attribute && !it.isUsed }!!
        related.isUsed = true

        if (valueGood != null && !valueGood!!.invoke(related.value, related.type)) return false

        val typedValue = builder.invoke(related.value, related.type) ?: return true

        entityEvent = related

        if (related.semantic == CompanionType.AND) {
            // We mainly need to remove the value from
            helper.not.removeIf{ it == typedValue }
            done = true
        }

        if (related.semantic == CompanionType.NEGATE) {
            helper.not.add(typedValue)
            done = true
        }

        // TODO: add support for other semantics
        return true
    }

    companion object {
        inline fun <reified T> build(
            session: UserSession,
            noinline buildSink: () -> KMutableProperty0<T?>
        ): EntityFiller<T> {
            val fullName = T::class.java.canonicalName
            val builder: (String) -> T? = { s ->
                Json.decodeFromString(s, session!!.findKClass(fullName)!!) as? T
            }
            return EntityFiller(buildSink, null, builder)
        }
    }
}

// We need to swap instead of set.
fun <T : Any> swap(obj1: T, obj2: T) {
    if (obj1::class != obj2::class) {
        throw IllegalArgumentException("Objects must be of the same class")
    }

    obj1::class.memberProperties.forEach { property ->
        if (property is KMutableProperty<*>) {
            val value1 = property.getter.call(obj1)
            val value2 = property.getter.call(obj2)

            property.setter.call(obj1, value2)
            property.setter.call(obj2, value1)
        }
    }
}


// Used with composite for VR and also when frame has not cui (all string slots).
// (TODO): this should be at the slot level, instead of container level.
class OpaqueFiller<T>(
    val buildSink: () -> KMutableProperty0<T?>,
    val declaredType: String,
    val builder: (JsonObject) -> T?) : AFrameFiller(), TypedFiller<T> {

    override val target: KMutableProperty0<T?>
        get() = buildSink()

    var value: FrameEvent? = null
    var valueGood: ((JsonObject) -> Boolean)? = null

    init {
        valueGood = {
            s -> try { builder(s) != null }
            catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    override val attribute: String
        get() = if (super.attribute.endsWith("._item"))
            super.attribute.substringBeforeLast("._item")
        else
            super.attribute

    override fun clear() {
        value = null
        event = null
        target.set(null)
        done = false
        super.clear()
    }

    override fun qualifiedEventType(): String {
        return target()!!::class.java.canonicalName
    }

    override fun isCompatible(frameEvent: FrameEvent): Boolean {
        // For now, we assume the frameEvent need to match at the top level.
        // if attribute does not agree, it is not compatiable.
        if (frameEvent.attribute != null && frameEvent.attribute != attribute) return false
        // if there is no attribute or attribute agrees, we check the type agreement.
        return declaredType == frameEvent.fullType
    }

    override fun commit(frameEvent: FrameEvent): Boolean {
        frameEvent.typeUsed = true
        frameEvent.allUsed = true

        // For now, it only works with concrete types.
        val jsonObject = toJson(frameEvent)

        if (valueGood != null && !valueGood!!.invoke(jsonObject)) return false

        val newObject = builder.invoke(jsonObject) ?: return false

        // If there is no value in it, we assign, if there is already value, we swap.
        if (target.get() == null) {
            target.set(builder.invoke(jsonObject))
        } else {
            swap(target.get()!!, newObject)
        }

        value = frameEvent
        event = frameEvent
        decorativeAnnotations.clear()
        // decorativeAnnotations.addAll(related.decorativeAnnotations)
        done = true
        return true
    }

    companion object {
        val regex = "^\"|\"$".toRegex()

        // To handle the nested frame events, we need to used recursion.
        fun frameToMap(event: FrameEvent) : Map<String, Any> {
            // check(event.attribute != null)
            // (TODO): add support for frames, and interface type.
            val values = mutableMapOf<String, Any>()
            for (slot in event.slots) {
                // We need to prevent double encode.
                values[slot.attribute] = slot.value.replace(regex, "")
            }
            //  We handle the nested frame event for concrete types.
            for (slot in event.frames) {
                check(slot.attribute != null) {"Nested frame $slot need to have attributes"}
                if (slot.jsonValue == null) {
                    values[slot.attribute!!] = frameToMap(slot)
                } else {
                    values[slot.attribute!!] = slot.jsonValue!!
                }
            }
            return values
        }

        //
        fun toJson(event: FrameEvent) : JsonObject {
            // check(event.attribute != null)
            // (TODO): add support interface type.
            val valueJson = if (event.jsonValue == null) {
                val values = frameToMap(event)
                Json.encodeToJsonElement(values) as JsonObject
            } else {
                // when jsonValue is not null, it can not have slots/frames.
                check(event.slots.isEmpty())
                check(event.frames.isEmpty())
                event.jsonValue as JsonObject
            }

            valueJson.put("@class", event.qualifiedName)
            return valueJson
        }

        inline fun <reified T> build(
            session: UserSession,
            noinline buildSink: () -> KMutableProperty0<T?>
        ) : OpaqueFiller<T> {
            val fullName = T::class.java.canonicalName
            val builder: (JsonObject) -> T? = {
                    s -> Json.decodeFromJsonElement(s, session!!.findKClass(fullName)!!, session.chatbot!!.getLoader()) as? T
            }
            return OpaqueFiller(buildSink, fullName, builder)
        }
    }
}

/**
 * This interface provide the support for finding the right filler based on path. The
 * filler it returns is the payload carrying filler, not Interface/Multi(Slot/Frame)
 * that is used as syntactical sugar.
 */
interface MappedFiller {
    // use outside and inside to make sure that we only inform once.
    var inside: Boolean

    fun get(s: String): IFiller?

    fun frame(): IFrame
}

interface Compatible {
    // decide whether frameEvent can be consumed by the filler
    fun isCompatible(frameEvent: FrameEvent): Boolean
}

interface Infer {
    // infer value from FrameEvent other than those which can be consumed directly
    fun infer(frameEvent: FrameEvent): FrameEvent?
}

interface Committable {
    // commit is responsible for mark the FrameEvent that it used
    fun commit(frameEvent: FrameEvent): Boolean
}

/**
 * Only one instance per user session are created for  implementation of this interface.
 */
interface ISingleton : IFrame {
    var filler: ICompositeFiller
}

// Does this handles nested properties correctly?
fun shallowCoverProperties(from: Any, to: Any) {
    val fromClass = from::class
    val toClass = to::class

    // Make sure the target class has the same properties as the source class
    fromClass.members.filterIsInstance<kotlin.reflect.KMutableProperty1<Any, *>>()
        .forEach { property ->
            val targetProperty = toClass.members.find { it.name == property.name }
            if (targetProperty is kotlin.reflect.KMutableProperty1<*, *>) {
                // Only we have value on from side for this property.
                val value = property.get(from)
                if (value != null) {
                    targetProperty.setter.call(to, property.get(from))
                }
            }
        }
}


// Filler for a slot needs to access the annotation attached to type as well as slot on the host.
class AnnotatedWrapperFiller(val targetFiller: IFiller, val isSlot: Boolean = true): ICompositeFiller {
    val boolGatePackage = io.opencui.core.booleanGate.IStatus::class.java.`package`.name
    val hasMorePackage = io.opencui.core.hasMore.IStatus::class.java.`package`.name
    override var parent: ICompositeFiller? = null

    override var path: ParamPath? = targetFiller.path

    override val decorativeAnnotations: MutableList<Annotation> = mutableListOf()

    override fun slotAskAnnotation(): PromptAnnotation? {
        return targetFiller.slotAskAnnotation()
    }

    private fun infer(frameEvent: FrameEvent): FrameEvent? {
        return if (targetFiller.isCompatible(frameEvent)) FrameEvent("Yes", packageName = boolGatePackage ) else null
    }

    var slotCrudFlag = false

    fun disableResponse() {
        needResponse = false
    }

    var startedFill = false

    fun directlyFill(a: Any) {
        val ltarget = (targetFiller as TypedFiller<in Any>).target
        val vtarget = ltarget.get()
        // There are so many potential problem here.
        if (a is IFrame && vtarget != null) {
            shallowCoverProperties(a, vtarget)
        } else {
            ltarget.set(a)
        }
        markDone()
    }

    fun markFilled() {
        markedFilled = true
    }

    // We change this so that we can also un mark done on this.
    fun markDone(flag: Boolean = true) {
        markedDone = flag
    }

    fun recheck() {
        checkFiller = null
    }

    fun reinit() {
        stateUpdateFiller?.clear()
    }

    val boolGate: BoolGate? by lazy {
        val askStrategy = askStrategy()
        if (askStrategy is BoolGateAsk) {
            BoolGate(path!!.root().session, askStrategy.generator, ::infer)
        } else {
            null
        }
    }

    val boolGateFiller: AnnotatedWrapperFiller? by lazy {
        (boolGate?.createBuilder()?.invoke(path!!.join("$attribute._gate", boolGate)) as? FrameFiller<*>)?.let {
            val res = AnnotatedWrapperFiller(it, false)
            res.parent = this@AnnotatedWrapperFiller
            it.parent = res
            res
        }
    }

    fun initRecommendationFiller(session: UserSession, frameEvent: FrameEvent?): AnnotatedWrapperFiller? {
        val node = if (frameEvent != null) {
            if (targetFiller is EntityFiller<*>) {
                val slot = frameEvent.slots.first { it.attribute == targetFiller.attribute }
                slot.isUsed = true
                val value = Json.parseToJsonElement(slot.value)
                val type = slot.type!!
                Json.decodeFromJsonElement(value, session.findKClass(type)!!)
            } else {
                val obj = ObjectNode(JsonNodeFactory.instance)
                val type = frameEvent.fullType
                obj.replace("@class", TextNode(type))
                frameEvent.typeUsed = true
                for (slot in frameEvent.slots) {
                    slot.isUsed = true
                    obj.replace(slot.attribute, Json.parseToJsonElement(slot.value))
                }
                Json.decodeFromJsonElement(obj, session.findKClass(type)!!)
            }
        } else {
            null
        }
        val annotation = path?.let { it.findAll<IValueRecAnnotation>().firstOrNull() } ?: return null
        val recFrame = when (annotation) {
            is ValueRecAnnotation -> {
                annotation.recFrameGen()
            }

            is TypedValueRecAnnotation<*> -> {
                (annotation.recFrameGen as Any?.() -> IFrame).invoke(node)
            }

            else -> {
                throw Exception("IValueRecAnnotation type not supported")
            }
        }
        return (recFrame.createBuilder().invoke(path!!.join("$attribute._rec", recFrame)) as FrameFiller<*>).let {
            val res = AnnotatedWrapperFiller(it, false)
            res.parent = this@AnnotatedWrapperFiller
            it.parent = res
            res
        }
    }

    var recommendationFiller: AnnotatedWrapperFiller? = null
    private var _stateUpdateFiller: AnnotatedWrapperFiller? = null

    var stateUpdateFiller: AnnotatedWrapperFiller?
    get() {
        if (_stateUpdateFiller == null) {
            val slotInitAnnotation =
                path!!.findAll<SlotInitAnnotation>().firstOrNull<SlotInitAnnotation>() ?: return null

            val updateIntent = ActionWrapperIntent(path!!.root().session, slotInitAnnotation.action)
            val updateFiller =
                updateIntent.createBuilder().invoke(path!!.join("$attribute._update", updateIntent)) as FrameFiller<*>

            // We need to create slot updater.
            val res = AnnotatedWrapperFiller(updateFiller, false)
            res.slotCrudFlag = true
            res.parent = this@AnnotatedWrapperFiller
            updateFiller.parent = res
            _stateUpdateFiller = res
        }
        return _stateUpdateFiller
    }
    set(value) {
        _stateUpdateFiller = value
    }

    val stateUpdateDone: Boolean
        get() {
            return stateUpdateFiller?.done(emptyList()) != false
        }

    val recommendationDone: Boolean
        get() {
            // we save some effort in annotations; use "mvSlot" as key to ValueRecAnnotation instead of mvSlot._hast and mvSlot._item
            // if we encounter path that ends with ._hast or ._item we omit those suffix to find ValueRecAnnotation,
            // so we have to disable vr for mvSlot for now
            // maybe we will need vr for mvSlot and mvSlot._hast and mvSlot._item respectively in the future
            return targetFiller is MultiValueFiller<*> ||
                    path?.let { it.findAll<IValueRecAnnotation>().firstOrNull() } == null ||
                    recommendationFiller?.done(emptyList()) == true
        }

    fun initCheckFiller(): AnnotatedWrapperFiller? {
        val checkFrame = path!!.findAll<ValueCheckAnnotation>().firstOrNull()?.checkFrame?.invoke() ?: return null
        return (checkFrame.createBuilder().invoke(path!!.join("$attribute._check", checkFrame)) as FrameFiller<*>).let {
            val res = AnnotatedWrapperFiller(it, false)
            res.parent = this@AnnotatedWrapperFiller
            it.parent = res
            res
        }
    }

    var confirmationFillers: List<AnnotatedWrapperFiller> = listOf()
    var checkFiller: AnnotatedWrapperFiller? = null

    val confirmDone: Boolean
        get() {
            val confirmFrameList = mutableListOf<IFrame>()
            if (startedFill) {
                val confirmFrame = path!!.findAll<ConfirmationAnnotation>().firstOrNull()?.confirmFrameGetter?.invoke()
                if (confirmFrame != null) confirmFrameList += confirmFrame
            }
            val decorativeConfirm =
                targetFiller
                    .decorativeAnnotations
                    .firstIsInstanceOrNull<ConfirmationAnnotation>()?.confirmFrameGetter?.invoke()

            if (decorativeConfirm != null) confirmFrameList += decorativeConfirm
            val currentFrames = confirmationFillers.mapNotNull { (it.targetFiller as? FrameFiller<*>)?.frame() }.toSet()
            return confirmFrameList.isEmpty() ||
                    (confirmFrameList.toSet() == currentFrames &&
                            confirmationFillers.map { it.done(emptyList()) }.fold(true) { acc, b -> acc && b })
        }

    val checkDone: Boolean
        get() {
            return path!!.findAll<ValueCheckAnnotation>()
                .firstOrNull() == null || checkFiller?.done(emptyList()) == true
        }

    var resultFiller: AnnotatedWrapperFiller? = (targetFiller as? FrameFiller<*>)?.fillers?.get("result")

    val resultDone: Boolean
        get() {
            return resultFiller?.done(emptyList()) != false
        }

    // response on/off switch
    var needResponse: Boolean = true
        get() = (targetFiller as? FrameFiller<*>)?.frame() is IIntent && field
        set(value) {
            field = value
        }

    var responseDone: Boolean = false

    var markedFilled: Boolean = false
    var markedDone: Boolean = false

    val ancestorTerminated: Boolean
        get() {
            var res = false
            var p: ICompositeFiller? = parent
            var c: ICompositeFiller = this
            while (p != null) {
                // check and confirm should be enabled if direct parent is marked filled
                if (p is AnnotatedWrapperFiller && (p.markedDone || (p.markedFilled && c != p.checkFiller && c !in p.confirmationFillers))) {
                    res = true
                    break
                }
                c = p
                p = p.parent
            }
            return res
        }

    override fun grow(session: UserSession, flatEvents: List<FrameEvent>): Boolean {
        startedFill = true
        val schedule = session.schedule
        if (boolGateFiller?.done(flatEvents) == false) {
            schedule.push(boolGateFiller!!)
            return true
        }
        val done = targetFiller.done(flatEvents)
        if (!done) {
            val frameEvent: FrameEvent? = flatEvents.firstOrNull { targetFiller.isCompatible(it) }
            // If the stateUpdate is not done, and there is no unprocessed event, let's schedule it.
            if (!stateUpdateDone && frameEvent == null) {
                println("pushed stateUpdateFiller: $path")
                schedule.push(stateUpdateFiller!!)
                return true
            }
            // HasMore has a VR that does not handle HasMore FrameEvent; it's special here
            if (!recommendationDone
                && (frameEvent == null ||
                        (targetFiller !is AEntityFiller &&
                                frameEvent.source != null &&
                                frameEvent.packageName != hasMorePackage) ||
                        (targetFiller is AEntityFiller && frameEvent.slots.firstOrNull { !it.isLeaf } != null))) {
                if (recommendationFiller == null || frameEvent != null) {
                    recommendationFiller = initRecommendationFiller(session, frameEvent)
                }
                val slotPromptAnnotation = targetFiller.slotAskAnnotation()
                if (slotPromptAnnotation != null) {
                    recommendationFiller!!.decorativeAnnotations.add(slotPromptAnnotation)
                }
                schedule.push(recommendationFiller!!)
                return true
            }
            if (askStrategy() !is NeverAsk || frameEvent != null) {
                targetFiller.parent = this
                schedule.push(targetFiller)
                return true
            }
        } else {
            //value check
            if (!checkDone) {
                this.checkFiller = initCheckFiller()
                schedule.push(checkFiller!!)
                return true
            }

            // value confirm
            val confirmFrame =
                path?.let { it.findAll<ConfirmationAnnotation>().firstOrNull() }?.confirmFrameGetter?.invoke()
            val decorativeConfirm = targetFiller.decorativeAnnotations.firstIsInstanceOrNull<ConfirmationAnnotation>()?.confirmFrameGetter?.invoke()
            val confirmFrameList = mutableListOf<IFrame>()
            if (confirmFrame != null) confirmFrameList += confirmFrame
            if (decorativeConfirm != null) confirmFrameList += decorativeConfirm
            if (confirmFrameList.isEmpty()) {
                confirmationFillers = listOf()
            } else {
                if (confirmationFillers.isEmpty() || confirmationFillers.mapNotNull { (it.targetFiller as? FrameFiller<*>)?.frame() }.toSet() != confirmFrameList.toSet()) {
                    confirmationFillers = confirmFrameList.mapNotNull {
                        (it.createBuilder().invoke(path!!.join("$attribute._confirm", it)) as FrameFiller<*>).let {
                            val cf = AnnotatedWrapperFiller(it, false)
                            cf.parent = this@AnnotatedWrapperFiller
                            it.parent = cf
                            cf
                        }
                    }
                    schedule.push(confirmationFillers.first())
                    return true
                } else {
                    val firstNotDone = confirmationFillers.firstOrNull { !it.done(emptyList()) }
                    if (firstNotDone != null) {
                        schedule.push(firstNotDone)
                        return true
                    }
                }
            }

            // result value for FrameFiller
            if (!resultDone) {
                resultFiller!!.parent = this
                schedule.push(resultFiller!!)
                return true
            }
        }
        return false
    }

    override fun move(session: UserSession, flatEvents: List<FrameEvent>): Boolean {
        val boolGateStatus = boolGateFiller?.done(flatEvents)
        if (boolGateStatus == false) return false
        val filledDone = filled(session.activeEvents)
        val postFilledDone = postFillDone()
        if (filledDone && postFilledDone && needResponse && !responseDone) {
            session.schedule.state = Scheduler.State.RESPOND
            return true
        }
        return false
    }

    override fun onPush() {
        recommendationFiller?.responseDone = false
        (askStrategy() as? RecoverOnly)?.enable()
    }

    private fun lastClauseInDone(frameEvents: List<FrameEvent>) : Boolean {
        val filledFlag  = filled(frameEvents)
        val postFilledDoneFlag = postFillDone()
        return filledFlag && postFilledDoneFlag && (!needResponse || responseDone)
    }


    override fun done(frameEvents: List<FrameEvent>): Boolean {
        val canNotEnter = !canEnter(frameEvents)
        val res = markedDone
                || canNotEnter
                || lastClauseInDone(frameEvents)
        if (slotCrudFlag) println("done: $res with canNotEnter: $canNotEnter")
        return res
    }

    fun canEnter(frameEvents: List<FrameEvent>): Boolean {
        val askStrategy =  askStrategy()
        val askStrategyNotMet = (askStrategy is ConditionalAsk && !askStrategy.canEnter())
                || (askStrategy is NeverAsk && stateUpdateDone && frameEvents.firstOrNull { isCompatible(it) } == null)
                || (askStrategy is RecoverOnly && stateUpdateDone && !askStrategy.canEnter() && frameEvents.firstOrNull { isCompatible(it) } == null)
                || (askStrategy is BoolGateAsk && boolGate!!.status is io.opencui.core.booleanGate.No && frameEvents.firstOrNull { isCompatible(it) } == null)
        return !responseDone && !askStrategyNotMet && !ancestorTerminated
    }

    fun filled(frameEvents: List<FrameEvent>): Boolean {
        return markedFilled || targetFiller.done(frameEvents)
    }

    fun postFillDone(): Boolean {
        // println("                                              checkDone : ${checkDone} && confirmDone : ${confirmDone} && resultDone ${resultDone}")
        return checkDone && confirmDone && resultDone
    }

    override fun clear() {
        (askStrategy() as? RecoverOnly)?.disable()
        recommendationFiller?.clear()
        recommendationFiller = null
        boolGateFiller?.clear()
        for (confirmFiller in confirmationFillers) {
            confirmFiller.clear()
        }
        confirmationFillers = listOf()
        checkFiller = null
        targetFiller.clear()
        stateUpdateFiller = null
        _stateUpdateFiller = null
        responseDone = false
        needResponse = true
        markedDone = false
        markedFilled = false
        startedFill = false
        super.clear()
    }

    override fun isCompatible(frameEvent: FrameEvent): Boolean {
        return targetFiller.isCompatible(frameEvent)
    }
}

/**
 * The invariance is following
 * if it is not done: if hasOpenSlot() is true, we pick works, or choose works. Either way,
 * we should make sure focus.parent == this.
 */
class FrameFiller<T: IFrame>(
    val buildSink: () -> KMutableProperty0<T?>,
    override var path: ParamPath?
) : ICompositeFiller, MappedFiller, TypedFiller<T>, Committable {

    override val target: KMutableProperty0<T?>
        get() = buildSink()

    override fun isCompatible(frameEvent: FrameEvent) : Boolean {
        return frameEvent.type == simpleEventType() && (frameEvent.activeEntitySlots.isNotEmpty())
    }

    override fun qualifiedEventType(): String? {
        val frameType = path!!.last().host::class.qualifiedName!!.let {
            if (it.endsWith("?")) it.dropLast(1) else it
        }
        return frameType.substringBefore("<")
    }

    override fun frame(): IFrame {
        return path!!.last().host
    }

    override fun get(s: String): IFiller? {
        return fillers[s]
    }

    fun initFromJsonValue(frameEvent: FrameEvent) {
        val classLoader = target.get()!!.javaClass.classLoader
        val className = frameEvent.qualifiedName
        val kClass = classLoader.findKClass(className)
        if (kClass != null) {
            val jsonObject = frameEvent.jsonValue
            logger.info("initFromJsonValue with $jsonObject")
            if (jsonObject != null) {
                val frame: IFrame = Json.decodeFromJsonElement(jsonObject, kClass) as IFrame
                for (k in jsonObject.fieldNames()) {
                    val f = this.fillers[k] ?: continue
                    val property = kClass.memberProperties.firstOrNull { it.name == k } as? KProperty1<Any, Any> ?: continue
                    val v = property.get(frame)
                    f.directlyFill(v)
                }
            }
        }
    }

    override fun commit(frameEvent: FrameEvent): Boolean {
        frameEvent.typeUsed = true
        committed = true
        return true
    }

    override var parent: ICompositeFiller? = null
    override val decorativeAnnotations: MutableList<Annotation> = mutableListOf()
    var fillers = LinkedHashMap<String, AnnotatedWrapperFiller>()
    var committed = false

    // use outside and inside to make sure that we only inform once.
    override var inside = false

    fun add(filler: IFiller) {
        val wrapper = AnnotatedWrapperFiller(filler)
        wrapper.parent = this
        filler.parent = wrapper
        fillers[filler.attribute] = wrapper
    }

    fun addWithPath(filler: IFiller) {
        // we have to set path first, it is the prerequisite of attribute, frame and many things else
        setChildPath(filler)
        add(filler)
    }

    fun setChildPath(filler: IFiller) {
        check(filler is TypedFiller<*>)
        filler.path = if (filler is FrameFiller<*>) path!!.join(filler.target.name, filler.target.get()) else path!!.join(filler.target.name)
    }

    fun findNextChildFiller(frameEvents: List<FrameEvent>): AnnotatedWrapperFiller? {
        // result is for output.
        val results0 =  fillers.filterNot { it.key == "result" }.values
        val results1 = results0.filter { !it.done(frameEvents) }
        return results1.firstOrNull()
    }

    /**
     * Do static check first, then contextual check. If there are work to do, return false.
     */
    override fun done(frameEvents: List<FrameEvent>): Boolean {
        val a = findNextChildFiller(frameEvents)
        val askStrategy = askStrategy()
        return a == null && (askStrategy !is ExternalEventStrategy || committed)
    }

    override fun clear() {
        fillers.values.forEach {
            it.clear()
        }
        committed = false
        super.clear()
    }

    // Choose picks up the right frame to ask.
    override fun grow(session: UserSession, flatEvents: List<FrameEvent>): Boolean {
        val schedule = session.schedule
        val filler = findNextChildFiller(flatEvents) ?: return false
        schedule.push(filler)
        return true
    }

    override fun move(session: UserSession, flatEvents: List<FrameEvent>): Boolean {
        if (committed) return false
        if (!inside) {
            val currentFrame = frame()
            // We need to make sure we jump out of grow.
            if (currentFrame !is IBotMode) {
                // concrete frame that is not iintent turn the current schedule to be OUTSIDE
                session.schedule.side = Scheduler.Side.OUTSIDE
            } else {
                inside = true
            }
        }

        if (askStrategy() is ExternalEventStrategy) {
            session.schedule.state = Scheduler.State.ASK
            return true
        }
        return false
    }

    companion object {
       val logger = LoggerFactory.getLogger(FrameFiller::class.java)
    }
}


//
// Any one of the subtype will be useful there.
// for interface filler to work, we always need to ask what "implementation" will we work on next.
// There are two different ways of doing interfaces: inside frame
// THe trick we use to solve the prompt issue is:
// insert an "" attribute in the path for the interface, so we can always look for a.b.c
// in any frame.
//
class InterfaceFiller<T>(
    val buildSink: () -> KMutableProperty0<T?>,
    val factory: (String) -> IFrame?,
    val typeConverter: ((FrameEvent) -> FrameEvent?)? = null
) : ICompositeFiller, TypedFiller<T> {
    override val target: KMutableProperty0<T?>
        get() = buildSink()
    override fun isCompatible(frameEvent: FrameEvent) : Boolean {
        return askFiller.isCompatible(frameEvent)
    }

    override var parent: ICompositeFiller? = null
    override var path: ParamPath? = null
    override val decorativeAnnotations: MutableList<Annotation> = mutableListOf()


    var realtype: String? = null
    val askFiller: AnnotatedWrapperFiller by lazy {
        val entityFiller = RealTypeFiller(::realtype, inferFun = typeConverter, checker = { s -> factory.invoke(s) != null }, ) { event -> buildVFiller(event) }
        entityFiller.path = this.path!!.join("$attribute._realtype")
        val af = AnnotatedWrapperFiller(entityFiller, false)
        af.parent = this
        entityFiller.parent = af
        af
    }
    var vfiller: AnnotatedWrapperFiller? = null

    override fun done(frameEvents: List<FrameEvent>): Boolean {
        return (vfiller != null && vfiller!!.done(frameEvents))
    }

    override fun clear() {
        askFiller.clear()
        vfiller?.clear()
        vfiller = null
        target.set(null)
        super.clear()
    }

    override fun grow(session: UserSession, flatEvents: List<FrameEvent>): Boolean {
        val schedule = session.schedule
        if (realtype == null) {
            schedule.push(askFiller)
            return true
        } else if (!askFiller.done(flatEvents)) {
            schedule.push(askFiller)
            return true
        }
        if (vfiller!!.done(flatEvents)) return false
        schedule.push(vfiller!!)
        return true
    }

    // If we already know the realtype (for whatever reason), we just make it ready for grow.
    private fun buildVFiller(frameEvent: FrameEvent) {
        checkNotNull(realtype)
        val f = factory.invoke(realtype!!)!!
        val frameFiller = f.createBuilder().invoke(path!!.join("$attribute._realfiller", f)) as FrameFiller<*>

        // if there are jsonValue, use it to init.
        frameFiller.initFromJsonValue(frameEvent)

        // Make sure that we assign the empty value so any update will show
        // up for matching. for now, we do not support the casting in the condition.
        vfiller = AnnotatedWrapperFiller(frameFiller)
        vfiller!!.parent = this
        frameFiller.parent = vfiller
        // We do not have anything special annotation wise needed to included
        target.set(frameFiller.target.get() as T)
    }
}

// MV can be defined for abstract type, or concrete type, with/without recomemndation.
// ValueRec is a filler as well.
class MultiValueFiller<T>(
    val buildSink: () -> KMutableProperty0<MutableList<T>?>,
    val buildItemFiller: (KMutableProperty0<T?>) -> IFiller
) : ICompositeFiller, TypedFiller<MutableList<T>> {
    val hasMorePackage = io.opencui.core.hasMore.IStatus::class.java.`package`.name
    override val target: KMutableProperty0<MutableList<T>?>
        get() = buildSink()

    override var path: ParamPath? = null
    override var parent: ICompositeFiller? = null
    override val decorativeAnnotations: MutableList<Annotation> = mutableListOf()

    private val minMaxAnnotation: MinMaxAnnotation? by lazy {
        path!!.findAll<MinMaxAnnotation>().firstOrNull()
    }

    // This is not used for filling at all, just so that we can test filler type.
    private val singleTargetFiller : IFiller by lazy { createTFiller(-1) }

    enum class SvType {
        ENTITY,
        FRAME,
        INTERFACE,
    }

    // need this wrapper to hold multiple properties of T
    inner class Wrapper(val index: Int) : Serializable {
        var tValue: T? = null
            set(value) {
                field = value
                if (value != null && index >= 0) {
                    val size = target.get()!!.size
                    if (index == size) {
                        target.get()!!.add(value)
                    } else {
                        target.get()!![index] = value
                    }
                }
            }
    }


    val svType: SvType
        get() = when (singleTargetFiller) {
            is AEntityFiller -> SvType.ENTITY
            is AFrameFiller -> SvType.ENTITY   // this is frame by should be treated as entity.
            is FrameFiller<*> -> SvType.FRAME
            is InterfaceFiller<*> -> SvType.INTERFACE
            else -> error("no such sv type")
        }

    private fun infer(frameEvent: FrameEvent): FrameEvent? {
        return if (singleTargetFiller.isCompatible(frameEvent)) {
            FrameEvent("Yes", packageName = hasMorePackage)
        } else {
            null
        }
    }

    override fun qualifiedEventType(): String? = singleTargetFiller.qualifiedEventType()
    override fun isMV(): Boolean = true

    fun qualifiedTypeStrForSv(): String? {
        return if (fillers.size > 0) {
            (fillers[0].targetFiller as TypedFiller<*>).qualifiedTypeStr()
        } else {
            (singleTargetFiller as TypedFiller<*>).qualifiedTypeStr()
        }
    }

    override fun isCompatible(frameEvent: FrameEvent): Boolean {
        return singleTargetFiller.isCompatible(frameEvent)
    }

    private val hasMoreAttribute = "_hast"
    var hasMore: HasMore? = null
    var hasMoreFiller: AnnotatedWrapperFiller? = null

    fun buildHasMore() {
        hasMore = HasMore(
            path!!.root().session, slotAskAnnotation()!!.actions, ::infer,
            if (minMaxAnnotation == null) {{true}} else {{target.get()!!.size >= minMaxAnnotation!!.min}},
            minMaxAnnotation?.minGen ?: { DumbDialogAct() })
        val hasMoreFrameFiller = (hasMore!!.createBuilder().invoke(path!!.join("$attribute.$hasMoreAttribute", hasMore)) as FrameFiller<*>)
        hasMoreFiller = AnnotatedWrapperFiller(hasMoreFrameFiller, false)
        hasMoreFrameFiller.parent = hasMoreFiller
        hasMoreFiller!!.parent = this
    }

    fun clearHasMore() {
        hasMoreFiller?.clear()
        hasMoreFiller = null
        hasMore = null
    }

    // We keep all the component filler so that we can change things around.
    val fillers = mutableListOf<AnnotatedWrapperFiller>()

    fun findCurrentFiller(): AnnotatedWrapperFiller? {
        return fillers.firstOrNull { !it.done(emptyList()) }
    }

    private fun createTFiller(index: Int): IFiller {
        // This is hosting the single item outside of list.
        val wrapper = Wrapper(index)
        return buildItemFiller(wrapper::tValue).apply {
            if (this !is FrameFiller<*>) {
                this.path = this@MultiValueFiller.path!!.join("${this@MultiValueFiller.attribute}._item")
            }
        }
    }

    private fun addFiller(filler: AnnotatedWrapperFiller) {
        fillers.add(filler)
    }

    fun abortCurrentChild(): Boolean {
        if (singleTargetFiller is AEntityFiller) return false
        if (singleTargetFiller is AFrameFiller) return false
        val currentFiller = findCurrentFiller() ?: return false
        if (currentFiller == fillers.lastOrNull()) {
            fillers.removeLast()
            hasMoreFiller?.clear()
            hasMoreFiller = null
            hasMore = null
            return true
        }
        return false
    }

    override fun done(frameEvents: List<FrameEvent>): Boolean {
        // We need to first make sure we got started.
        val currentFiller = findCurrentFiller()
        val maxCount = minMaxAnnotation?.max ?: Int.MAX_VALUE
        return target.get() != null &&
                currentFiller == null &&
                (hasMore?.status is No ||
                        (target.get()!!.size >= maxCount && frameEvents.firstOrNull { isCompatible(it) } == null))
    }

    override fun clear() {
        hasMoreFiller?.clear()
        hasMoreFiller = null
        hasMore = null
        fillers.clear()
        target.get()?.clear()
        target.set(null)
        super.clear()
    }

    fun append(session: UserSession) {
        val maxCount = minMaxAnnotation?.max ?: Int.MAX_VALUE
        if (target.get()!!.size < maxCount) {
            clearHasMore()
            val ffiller = createTFiller(fillers.size)
            val wrapper = AnnotatedWrapperFiller(ffiller)
            ffiller.parent = wrapper
            wrapper.parent = this
            addFiller(wrapper)
            session.schedule.push(wrapper)
        }
    }

    override fun grow(session: UserSession, flatEvents: List<FrameEvent>): Boolean {
        val schedule = session.schedule
        if (target.get() == null) {
            target.set(mutableListOf())
            val vrec = path?.let { it.findAll<IValueRecAnnotation>().firstOrNull() }
            // When MV is not on abstract item, and there is no value rec define on it
            // and there is no FrameEvent that need to be take care of.
            if (vrec == null) {
                // Is this just a dangling filler?
                if (singleTargetFiller is MappedFiller) {
                    val ffiller = createTFiller(fillers.size)
                    val wrapper = AnnotatedWrapperFiller(ffiller)
                    ffiller.parent = wrapper
                    wrapper.parent = this
                    addFiller(wrapper)
                    schedule.push(wrapper)
                    return true
                }
            }
        }

        check(hasMore?.status !is No)
        val currentFiller = findCurrentFiller()
        if (currentFiller != null) {
            schedule.push(currentFiller)
        } else if ((hasMoreFiller == null || !hasMoreFiller!!.done(flatEvents)) && flatEvents.firstOrNull { isCompatible(it) } == null) {
            if (hasMoreFiller == null) {
                buildHasMore()
            }
            schedule.push(hasMoreFiller!!)
        } else { //if something is mentioned but last filler is done, we assume it is for the next value
            clearHasMore()
            val ffiller = createTFiller(fillers.size)
            val wrapper = AnnotatedWrapperFiller(ffiller)
            ffiller.parent = wrapper
            wrapper.parent = this
            addFiller(wrapper)
            schedule.push(wrapper)
        }
        return true
    }
}