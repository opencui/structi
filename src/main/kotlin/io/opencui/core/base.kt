package io.opencui.core

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.node.ObjectNode
import io.opencui.channel.IChannel
import io.opencui.core.da.DialogActRewriter
import io.opencui.core.user.IUserIdentifier
import io.opencui.du.*
import io.opencui.serialization.Json
import io.opencui.sessionmanager.ChatbotLoader
import java.io.Serializable
import java.time.LocalDateTime
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.*
import kotlin.reflect.jvm.isAccessible


// We often need to handle
enum class RelationType {
    EQUAL, NOTEQUAL, LESSTHAN, LESSTHANEQUALTO, GREATERTHAN, GREATERTHANQUALTO;

    companion object {
        private val listRelations = setOf(RelationType.EQUAL, RelationType.NOTEQUAL)
        fun isListCompatible(relationType: RelationType) = relationType in listRelations
    }
}


// This is the serialized condition.
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
@JsonDeserialize(using = CriterionDeserializer::class)
data class Criterion<T>(val relation: RelationType, val reference: T?, val references: List<T>?) {
    constructor(condition: String, ref: T) : this(RelationType.valueOf(condition), ref, null)
    constructor(condition: String, refs: List<T>) : this(RelationType.valueOf(condition), null, refs)
    init {
        if (RelationType.isListCompatible(relation)) {
            check(references != null && reference == null)
        } else {
            check(references == null && reference != null)
        }
    }

}

object CriterionDeserializer : JsonDeserializer<Criterion<*>>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Criterion<*> {
        val node: ObjectNode = p.codec.readTree(p)

        // Extract the type parameter information
        val paramType = node.get("@param").asText()
        val condition = node.get("relation").asText()
        val relation = RelationType.valueOf(condition)

        // Determine the class type for `value` based on `@param_type`
        if (!RelationType.isListCompatible(relation)) {
            val reference = node.get("reference")
            val value = when (paramType) {
                "Int" -> Json.decodeFromJsonElement<Int>(reference)
                "String" -> Json.decodeFromJsonElement<String>(reference)
                "Float" -> Json.decodeFromJsonElement<Float>(reference)
                "Double" -> Json.decodeFromJsonElement<Double>(reference)
                "Boolean" -> Json.decodeFromJsonElement<Boolean>(reference)
                "LocalDateTime" -> Json.decodeFromJsonElement<LocalDateTime>(reference)
                else -> throw IllegalArgumentException("Unsupported type: $paramType")
            }
            return Criterion(condition, value)
        } else {
            val references = node.get("references")
            val values = when (paramType) {
                "Int" -> Json.decodeFromJsonElement<List<Int>>(references)
                "String" -> Json.decodeFromJsonElement<List<String>>(references)
                "Float" -> Json.decodeFromJsonElement<List<Float>>(references)
                "Double" -> Json.decodeFromJsonElement<List<Double>>(references)
                "Boolean" -> Json.decodeFromJsonElement<List<Boolean>>(references)
                "LocalDateTime" -> Json.decodeFromJsonElement<List<LocalDateTime>>(references)
                else -> throw IllegalArgumentException("Unsupported type: $paramType")
            }
            return Criterion(condition, values)
        }
    }
}

fun Criterion<*>.compatible(value: Any) : Boolean {
    return if (!RelationType.isListCompatible(relation)) {
        value as Comparable<Any>
        reference!! as Comparable<Any>
        when (this.relation) {
            RelationType.LESSTHAN -> value < reference
            RelationType.LESSTHANEQUALTO -> value <= reference
            RelationType.GREATERTHAN -> value > reference
            RelationType.GREATERTHANQUALTO -> value >= reference
            else -> throw IllegalArgumentException("Unsupported type: $value")
        }
    } else {
        when (this.relation) {
            RelationType.EQUAL -> references!!.find { it == value } != null
            RelationType.NOTEQUAL -> references!!.find { it == value } == null
            else -> throw IllegalArgumentException("Unsupported type: $value")
        }
    }
}

fun List<Criterion<*>>.compatible(value: Any) : Boolean {
    for (criterion in this) {
        if (!criterion.compatible(value)) return false
    }
    return true
}



/**
 *
 * There are two aspects of type systems: one is on type sides, and another is on the
 * translation from language to schema. On type side, the difference is between is-a
 * and can_be_cast_as, on the translation side, it is rewritten to:
 * how long is your {symptom} ?
 * or make the following not frame/intent trigger
 * start from {beijing}.
 *
 * Without rewriting the training phrase, "how long did you have your {symptom}?" is not useful
 * for headache, stomachache etc.
 *
 */
interface FillBuilder : (ParamPath) -> ICompositeFiller, Serializable

interface PolymorphicFrameGenerator: (String) -> IFrame?, Serializable

fun createFrameGenerator(session: UserSession, interfaceClassName: String) = object: PolymorphicFrameGenerator {
    override operator fun invoke(type: String): IFrame? {
        val interfaceKClass = session.findKClass(interfaceClassName) ?: return null
        val packageName = type.substringBeforeLast(".", interfaceClassName.substringBeforeLast("."))
        val simpleType = type.substringAfterLast(".")
        val frame = session.construct(packageName, simpleType, session)
        return if (interfaceKClass.isInstance(frame)) frame else null
    }
}

fun <T: Any> useClassLoader(classLoader: ClassLoader, block: () -> T): T {
    val oldClassLoader = Thread.currentThread().getContextClassLoader()
    Thread.currentThread().setContextClassLoader(classLoader)
    try {
        return block()
    } finally {
        Thread.currentThread().setContextClassLoader(oldClassLoader)
    }
}

interface ICui: Serializable {

    fun <T: Any> fromTypeNameList(vararg typeName: String) : List<T> {
        val classLoader = this.javaClass.classLoader
        val typeNames = typeName.toList()
        return typeNames.map {
            val kClass = Class.forName(it, true, classLoader).kotlin
            val constructor = kClass.constructors.firstOrNull { constructor ->
                constructor.parameters.size == 1 &&
                constructor.parameters[0].type.classifier == UserSession::class &&
                constructor.parameters[0].type.isMarkedNullable
            }
            constructor?.callBy(constructor.parameters.associateWith { null }) as T
        }
    }


}

/**
 * One should be able to access connection, and even session. The IService contains a set of functions.
 * Service is also attached to session, just like frame.
 */
interface IEntity : ICui {
    var value: String
    var origValue: String?
}

/**
 * Some frames are used at the struct level, so there is no cui really.
 */
interface CuiDisabled : Serializable


/**
 * For value disambiguation, we need to expose some information for the generic implementation
 * for the slot that we bind dynamically.
 * Builder can also do slotNames.random() and typeNames.random().
 */
interface IFrame : ICui {
    var session: UserSession?

    fun getUserIdentifier(): IUserIdentifier? {
        return session
    }

    fun annotations(path: String): List<Annotation> = listOf()

    fun createBuilder(): FillBuilder

    fun getFallback(): CompositeAction? {
        return null
    }

    // slot "this" is a special slot which indicates searching for frame confirmation
    fun searchConfirmation(path: String): IFrame? {
        return null
    }

    fun searchStateUpdateByEvent(event: String): IFrameBuilder? {
        return null
    }
}

inline fun <reified T : Annotation> IFrame.findAll(path: String): List<T> =
    annotations(path).filter { it is T && it.switch() }.map { it as T }

inline fun <reified T: IFrame, R> T.save(prop: KProperty1<T, R>) {
    session?.save(prop, this)
}

inline fun <reified T: IFrame, reified R: Any> T.load(prop: KProperty1<T, R>): R? {
    return session?.load(prop)
}


// Most of the frame/skills are process event generated by DU, but some of the LLM based utterance
// can handle raw utterance. Most skills start with event created by dialog understanding, but there are
// skills that started from raw input. This always need to be the first slot?
interface IRawInputHandler : Serializable {
    var rawUserInput: String?
}

interface IIntent : IFrame {
    // TODO(xiaobo, xiaoyun): all the filling related property should be in filler instead of frame, ideally.
    fun searchResponse(): Action? {
        return null
    }
}

// This is used to make sure
interface IBotMode
interface IKernelIntent: IBotMode, IIntent


// This is useful for decouple the event generation and send to flow.
// Emitter always assume to be String? Or should we make this templated?
interface Emitter<T> {
    operator fun invoke(x: T)
}

@Throws(NoSuchMethodException::class)
fun invokeMethodByReflection(receiver: Any, funName: String, vararg params: Any?): Any? {
    val kClass = receiver::class
    val method = kClass.memberFunctions.firstOrNull { it.name == funName && it.parameters.size == params.size + 1 } ?: throw NoSuchMethodException("no such method : $funName") // param of method include receiver
    return method.call(receiver, *params)
}


/**
 *  this will make it easy to dump the content of a slot as fill action in json object.
 *  client is responsible to render it to builder, and send back the modified value as
 *  the frame event.
 *
 * @param T The concrete IFrame type.
 * @param R The type of the property's value.
 * @param property A KProperty1 reference to the member property (e.g., `MyFrame::myProperty`).
 * @return An ObjectNode representing the dumped property, like `{"propertyName": "propertyValue"}`.
 */
inline fun <reified T: Any> T.buildFillActionForSlot(propertyName: String): ObjectNode {
    // T::class gives you the KClass instance for T at runtime.
    val frameClass: KClass<T> = T::class

    val property = frameClass.memberProperties.find { it.name == propertyName }
        ?: throw IllegalArgumentException("Property '$propertyName' not found on ${frameClass.simpleName}")

    val propertyValue = property.get(this)
    if (propertyValue != null) {
        Json.mapper.createObjectNode().set<JsonNode>("content", Json.mapper.valueToTree(propertyValue))
    } else {
        Json.mapper.createObjectNode().putNull("content")
    }

    // Get the property's type via reflection, so we don't need a second generic parameter `R`.
    val propertyType = property.returnType.classifier as? KClass<*>
        ?: throw IllegalStateException(
            "Cannot build schema for property '$propertyName' with a complex type: ${property.returnType}"
        )

    Json.mapper.createObjectNode().put("type", "artifact")
    Json.mapper.createObjectNode().put("qualifiedName", frameClass.qualifiedName)
    Json.mapper.createObjectNode().put("simpleName", frameClass.simpleName)
    Json.mapper.createObjectNode().put("packageName", frameClass.java.packageName)
    Json.mapper.createObjectNode().put("slotName", propertyName)
    Json.mapper.createObjectNode().set<JsonNode>("schema", Json.encodeToJsonElement(Json.buildSchema(propertyType)))

    return Json.mapper.createObjectNode()
}


// This is used to pass the runtime configure, information needed for all agents.
object RuntimeConfig {
    val configures = mutableMapOf<KClass<*>, Any>()
    inline fun <reified T: Any> put(key: KClass<*>, value: T) {
        if (configures.containsKey(key)) {
            Dispatcher.logger.info("$key is configured twice, was ${configures[key]} now $value")
        }
        configures[key] = value
    }

    inline fun <reified T: Any> update(key: KClass<*>, value: T) {
        configures[key] = value
    }

    inline fun <reified T: Any> get(key: KClass<*>) : T? {
        return configures[key] as T?
    }
}


data class RoutingInfo(val id: String, val intentsDesc: List<String>)


data class BotInfo(
    val fullName: String,
    val lang: String,
    val branch: String
): Serializable

fun botInfo(fullName: String) : BotInfo {
    return BotInfo(fullName, "*", "master")
}

fun botInfo(org: String, bot: String) : BotInfo {
    return BotInfo("${org}.${bot}", "*", "master")
}

fun botInfo(fullName: String, lang: String, branch: String) : BotInfo {
    return BotInfo(fullName, lang, branch)
}

fun botInfo(org: String, bot: String, lang: String, branch: String) : BotInfo {
    return BotInfo("${org}.${bot}", lang, branch)
}

fun botInfo(chatbot: IChatbot) : BotInfo {
    return botInfo(chatbot.orgName, chatbot.agentName, chatbot.agentLang, chatbot.agentBranch)
}

interface Component {
    val orgName: String
    val agentName: String
    val agentBranch: String
    val agentVersion: String
    val agentLang: String
    val timezone: String
}

fun ClassLoader.findKClass(className: String) : KClass<*>? {
    return try {
        when (className) {
            "kotlin.Int" -> Int::class
            "kotlin.Float" -> Float::class
            "kotlin.String" -> String::class
            "kotlin.Boolean" -> Boolean::class
            else -> {
                val kClass = Class.forName(className, true, this).kotlin
                kClass
            }
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * The chatbot implementation will be used to hold the data/filler together.
 * TODO: why this is NOT implement AgentMeta?
 */
abstract class IChatbot : Component {
    abstract val duMeta: DUMeta

    override val orgName: String = duMeta.getOrg()
    override val agentName: String = duMeta.getLabel()
    override val agentBranch: String = duMeta.getBranch()
    override val agentVersion: String = duMeta.getVersion()
    override val agentLang: String = duMeta.getLang()
    override val timezone: String = duMeta.getTimezone()

    fun findKClass(className: String): KClass<*>? {
        return getLoader().findKClass(className)
    }

    // Do we have support connected behind bot?
    abstract val stateTracker: IStateTracker

    // This is used to host extension managers.
    val extensions: ExtensionManager = ExtensionManager()

    // This is used for hosting dialog act rewrite rule.
    abstract val rewriteRules: List<KClass<out DialogActRewriter>>

    // This is designed for routing conversation to right department when needed.
    abstract val routing: Map<String, RoutingInfo>

    fun getChannel(label: String) : IChannel? {
        return extensions.get<IChannel>(label)
    }

    inline fun <reified T:IExtension> getExtension(label: String): T? {
        return extensions.get(label)
    }

    inline fun <reified T : IExtension> getExtension(): T? {
        val labels = extensions.labelsByInterface[T::class] ?: emptyList()
        if (labels.isEmpty()) return null
        return extensions.get(labels[0])
    }

    fun executeByLabel(label: String, funcName: String, parameters: Map<String, Any>): Any? {
        val extension = extensions.get<IExtension>(label)

        if (extension == null) {
            Dispatcher.logger.error("Could not find extension for module : $label")
            return null
        }

        return executeFunc(extension, funcName, parameters)
    }

    fun executeByLabel(label: String, funcName: String, parameters: List<Any?>): Any? {
        val extension = extensions.get<IExtension>(label)

        if (extension == null) {
            Dispatcher.logger.error("Could not find extension for module : $label")
            return null
        }

        return executeFunc(extension, funcName, parameters)
    }

    fun executeByInterface(interfaceName: String, funcName: String, parameters: Map<String, Any>) : Any? {
        // Try to get class using this class loader.
        val type = useClassLoader(getLoader()) { Class.forName(interfaceName).kotlin }

        val labels = extensions.labelsByInterface[type] ?: emptyList()
        Dispatcher.logger.info("executeByInterface: found ${labels.size} labels")

        if (labels.isEmpty()) return null

        val extension = extensions.get(labels[0]) as IExtension?
        if (extension == null) {
            Dispatcher.logger.error("Could not find extension for module : $interfaceName and ${labels[0]}")
            return null
        }
        return executeFunc(extension, funcName, parameters)
    }
    
    private fun executeFunc(extension: IExtension, funcName: String, parameters: Map<String, Any>) : Any? {
        val kClass = extension::class
        val function = kClass.declaredFunctions.find { it.name == funcName }

        if (function == null) {
            Dispatcher.logger.error("Could not find function for module : $funcName")
            return null
        }

        val result = function.let {
            it.isAccessible = true
            val parameterValues = it.parameters.drop(1).map { param -> parameters[param.name] }
            it.call(extension, *parameterValues.toTypedArray())
        }

        return result
    }

    // This calls from
    fun executeByInterface(interfaceName: String, funcName: String, parameters: List<Any?>) : Any? {
        // Try to get class using this class loader.
        val type = useClassLoader(getLoader()) { Class.forName(interfaceName).kotlin }

        val labels = extensions.labelsByInterface[type] ?: emptyList()
        Dispatcher.logger.info("executeByInterface: found ${labels.size} labels")

        if (labels.isEmpty()) return null

        val extension = extensions.get(labels[0]) as IExtension?
        if (extension == null) {
            Dispatcher.logger.error("Could not find extension for module : $interfaceName and ${labels[0]}")
            return null
        }
        return executeFunc(extension, funcName, parameters)
    }
    
    private fun executeFunc(extension: IExtension, funcName: String, parameters: List<Any?>) : Any? {
        val kClass = extension::class
        val function = kClass.declaredFunctions.find { it.name == funcName }

        if (function == null) {
            Dispatcher.logger.error("Could not find function for module : $funcName")
            return null
        }

        Dispatcher.logger.info("Executing $funcName with parameters $parameters")
        val result = function.let {
            it.isAccessible = true
            it.call(extension, *parameters.toTypedArray())
        }

        return result
    }

    fun getConfiguration(label: String): Configuration? {
        return Configuration.get(label)
    }

    fun createUserSession(channelType: String, user: String, channelLabel: String?): UserSession{
        return UserSession(user, channelType, channelLabel, chatbot = this)
    }

    open fun recycle() {
    }

    fun getLoader() : ClassLoader = ChatbotLoader.findClassLoader(botInfo(orgName, agentName, agentLang, agentBranch))

    operator fun invoke(p1: String, session: UserSession, packageName: String? = null): FillBuilder? {
        // hardcode for clean session
        val revisedPackageName = packageName ?: this.javaClass.packageName
        return session.construct(revisedPackageName, p1, session)?.createBuilder()
    }

    // Add this to isolate the guava issue.
    fun checkGuava() {
        try {
            val builderClass = com.google.common.collect.ImmutableMap.Builder::class.java
            val methods = builderClass.declaredMethods.map { it.name }
            Dispatcher.logger.info("ImmutableMap.Builder methods: $methods")
            Dispatcher.logger.info("Has buildOrThrow: ${methods.contains("buildOrThrow")}")

            val location = builderClass.protectionDomain.codeSource.location
            Dispatcher.logger.info("Guava loaded from: $location")

            val classLoader = builderClass.classLoader
            Dispatcher.logger.info("Loaded by classloader: $classLoader")
        } catch (e: Exception) {
            Dispatcher.logger.error("Error checking Guava in dynamic module: ${e.message}")
        }
    }


    companion object {
        val ExpressionPath = "expression.json"
        val EntityPath = "entity.json"
        val EntityMetaPath = "entity_meta.json"
        val SlotMetaPath = "slot_meta.json"
        val AliasPath = "alias.json"

        fun parseEntityToMapByNT(entity: String, entries: String): Map<String, List<String>> {
            println("processing $entity with $entries")
            // content are encoded by newline and tab.
            val lines = entries.split("\n").map{ it.split("\t") }

            val nempties = lines.count{ it[0] == ""}
            val nnemptties = lines.count{ it[0] != ""}

            assert (nempties == 0 || nnemptties == 0)

            val map: MutableMap<String, List<String>> = mutableMapOf()
            for (l in lines.withIndex()) {
                val norm =  if (nnemptties != 0) l.value[0] else "${entity}.${l.index}"
                map[norm] = l.value.subList(1, l.value.size)
            }
            return map
        }

        fun loadDUMetaDsl(langPack: LangPack, classLoader: ClassLoader, org: String, agent: String, lang: String, branch: String, version: String, timezone: String = "america/los_angeles"): DUMeta {
            return loadDUMetaDsl(langPack, org, agent, lang, branch, version, timezone)
        }

        fun loadDUMetaDsl(langPack: LangPack, org: String, agent: String, lang: String, branch: String, version: String, timezone: String): DUMeta {
            return object : DslDUMeta() {
                override val entityTypes = langPack.entityTypes
                override val slotMetaMap = langPack.frameSlotMetas
                override val aliasMap = langPack.typeAlias
                override val skills = langPack.skills

                init {
                    val surroundings = extractSlotSurroundingWords(expressionsByFrame, LanguageAnalyzer.get(lang, stop=false)!!)
                    for ((frame, slots) in slotMetaMap) {
                        for (slot in slots) {
                            slot.prefixes = surroundings.first["$frame:${slot.label}"]
                            slot.suffixes = surroundings.second["$frame:${slot.label}"]
                        }
                    }
                }

                override fun getSubFrames(fullyQualifiedType: String): List<String> { return subtypes[fullyQualifiedType] ?: emptyList() }

                override fun getOrg(): String = org
                override fun getLang(): String = lang
                override fun getLabel(): String = agent
                override fun getVersion(): String = version
                override fun getBranch(): String = branch
                override fun getTimezone(): String = timezone

                override fun getEntityInstances(name: String): Map<String, List<String>> {
                    return langPack.entityTypes[name]!!.entities
                }

                override val expressionsByFrame: Map<String, List<Exemplar>>
                    get() = langPack.frames
            }
        }
    }
}