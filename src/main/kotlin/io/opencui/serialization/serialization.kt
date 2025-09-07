package io.opencui.serialization

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.module.jsonSchema.JsonSchemaGenerator
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.PropertyAccessor
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.node.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.jsonSchema.JsonSchema
import io.opencui.core.*
import java.io.*
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.KProperty0
import kotlin.reflect.full.companionObject
import kotlin.reflect.full.companionObjectInstance
import kotlin.reflect.jvm.javaField


/**
 * This will be our serialization tool.
 * value node is jackson's primitive
 */
typealias JsonElement = JsonNode
typealias JsonPrimitive = ValueNode
typealias JsonValue = ValueNode
typealias JsonArray = ArrayNode
typealias JsonObject = ObjectNode
typealias JsonNull = NullNode

fun ObjectNode.containsKey(s:String) : Boolean {
    return this.get(s) != null
}

fun ObjectNode.getObject(key: String) : JsonObject {
    return this.get(key) as JsonObject
}

fun ObjectNode.getElement(key: String) : JsonElement {
    return this.get(key) as JsonElement
}

fun ObjectNode.getJsonObject(key: String) : JsonObject {
    return this.get(key) as JsonObject
}

fun ObjectNode.getJsonArray(key: String) : JsonArray {
    return this.get(key) as JsonArray
}

fun ObjectNode.getObject(keys: List<String>) : JsonObject {
    var obj = this
    for (key in keys ) {
        obj = obj.getObject(key) as JsonObject
    }
    return obj
}

fun ObjectNode.getPrimitive(s: String) : JsonPrimitive {
    return this.get(s) as JsonPrimitive
}

fun ObjectNode.getPrimitiveIfExist(s: String, default: JsonPrimitive? = null) : JsonPrimitive? {
    if (!this.containsKey(s)) return default
    return this.get(s) as JsonPrimitive
}

fun ObjectNode.getString(s: String) : String {
    val obj = this.get(s) as JsonPrimitive
    return obj.asText()
}

fun ObjectNode.getInteger(s: String) : Int {
    val obj = this.get(s) as JsonPrimitive
    return obj.asInt()
}
fun ValueNode.content() : String {
    return this.toString().removeSurrounding("\"");
}

interface Converter<T>: Serializable {
    operator fun invoke(o: JsonElement?) : T
    val isEntity: Boolean
}

fun deserializeIEntity(node: JsonNode, defaultClass: String): IEntity {
    when (node) {
        is ObjectNode -> {
            val keys = node.fieldNames()
            assert(keys.hasNext())
            val type = keys.next()
            val value = (node[type] as TextNode).textValue()
            return Class.forName(type).constructors.first { it.parameters.size == 1 }.newInstance(value) as IEntity
        }
        is ArrayNode -> {
            assert(node.size() == 2)
            val type = (node[0] as TextNode).textValue()
            val value = (node[1] as TextNode).textValue()
            return Class.forName(type).constructors.first { it.parameters.size == 1 }.newInstance(value) as IEntity
        }
        is TextNode -> {
            return Class.forName(defaultClass).constructors.first { it.parameters.size == 1 }.newInstance(node.textValue()) as IEntity
        }
        else -> error("JsonNode type not supported")
    }
}

class InterfaceIEntitySerializer: JsonSerializer<IEntity>() {
    override fun serialize(value: IEntity?, gen: JsonGenerator?, serializers: SerializerProvider?) {
        if (value == null) {
            gen?.writeNull()
            return
        }
        val type = value::class.qualifiedName
        gen!!.writeStartArray()
        gen.writeString(type)
        gen.writeString(value.value)
        gen.writeEndArray()
    }
}

object MapperCache{
    val cache = mutableMapOf<ClassLoader, ObjectMapper>()

    fun getMapper(classLoader: ClassLoader) : ObjectMapper {
        if (cache.containsKey(classLoader)) {
            return cache[classLoader]!!
        } else {
            val mapper = createMapper(classLoader)
            cache[classLoader] = mapper
            return mapper
        }
    }

    fun createMapper(classLoader: ClassLoader): ObjectMapper {
        val mapper = jacksonObjectMapper()
        // support the java time.
        val module = JavaTimeModule()
        module.addDeserializer(
            OffsetDateTime::class.java,
            object: JsonDeserializer<OffsetDateTime>() {
                override fun deserialize(p: JsonParser?, ctxt: DeserializationContext?): OffsetDateTime {
                    return OffsetDateTime.parse(p!!.text, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                }
            }
        )

        // For now, we only handles the deserialization of Criterion<T>.
        module.addDeserializer(Criterion::class.java, CriterionDeserializer)

        module.addSerializer(
            OffsetDateTime::class.java,
            object: JsonSerializer<OffsetDateTime>() {
                override fun serialize(value: OffsetDateTime?, gen: JsonGenerator?, serializers: SerializerProvider?) {
                    gen?.writeString(value!!.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                }
            }
        )
        mapper.registerModule(module)
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
        mapper.enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)

        // get the class loader.
        mapper.typeFactory = mapper.typeFactory.withClassLoader(classLoader)
        return mapper
    }

}


// TODO(sean) maybe chagned to regular class so that we can take session as member.
object Json {
    val mapper = MapperCache.getMapper(Json.javaClass.classLoader)

    val schemaGen by lazy { JsonSchemaGenerator(mapper) }

    inline fun <reified T:Any> buildSchema(): JsonSchema {
        return schemaGen.generateSchema(T::class.java) as JsonSchema
    }

    fun buildSchema(kClass: KClass<*>): JsonSchema {
        return schemaGen.generateSchema(kClass.java)
    }


    inline fun <reified R: Any> buildFillAction(prop: KProperty0<R?>): ObjectNode {
        // T::class gives you the KClass instance for T at runtime.
        val frameClass = prop.javaField?.declaringClass?.kotlin
        val propertyName = prop.name

        val propertyValue = prop.get()
        val res = mapper.createObjectNode()

        // Get the property's type via reflection, so we don't need a second generic parameter `R`.
        val propertyType = R::class
        res.put("type", "slot")
        res.put("frameName", frameClass?.simpleName)
        res.put("packageName", frameClass?.java?.packageName)
        res.put("slotName", propertyName)
        res.put("slotType", propertyType.qualifiedName)
        res.set<JsonNode>("schema", encodeToJsonElement(buildSchema(propertyType)))
        res.set<JsonNode>("value", encodeToJsonElement(propertyValue))
        return res
    }


    // If you load a string from disk or network, and you want use it as value of string, you need to
    // first escapeDoubleQuotes so that the resulting string can be interpreted as value of a string.
    fun escapeDoubleQuotes(input: String): String {
        return input.replace("\"", "\\\"")
    }

    fun encodeToString(o: Any?) : String {
        return when(o) {
            else -> mapper.writeValueAsString(o)
        }
    }

    fun <T: Any> decodeFromJsonElement(s: JsonNode, kClass: KClass<T>): T {
        return mapper.treeToValue(s, kClass.java)
    }

    fun <T: Any> decodeFromJsonElement(s: JsonNode, kClass: KClass<T>, classLoader: ClassLoader): T {
        return useClassLoader(classLoader) { mapper.treeToValue(s, kClass.java) }
    }


    fun <T: Any> decodeFromString(s: String, kClass: KClass<T>): T {
        return mapper.readValue(s, kClass.java) as T
    }

    fun <T: Any> decodeFromStringJava(s: String, kClass: Class<T>): T {
        return mapper.readValue(s, kClass) as T
    }


    fun <T: Any> decodeFromString(s: String, kClass: KClass<T>, classLoader: ClassLoader): T {
         return useClassLoader(classLoader) { mapper.readValue(s, kClass.java) as T }
    }

    inline fun <reified T> decodeFromString(s: String) : T {
        return mapper.readValue(s)
    }

    inline fun <reified T> decodeFromString(s: String, classLoader: ClassLoader) : T {
        return useClassLoader(classLoader) { mapper.readValue(s) }
    }

    inline fun <reified T> decodeFromJsonElement(s: JsonElement) : T {
        return mapper.treeToValue(s, T::class.java)
    }

    inline fun <reified T : Any> decodeFromJsonElement(s: JsonElement, classLoader: ClassLoader) : T {
        return useClassLoader(classLoader) { mapper.treeToValue(s, T::class.java) as T }
    }

    fun <T> getFrameConverter(session: UserSession?, clazz: Class<T>): Converter<T> {
        return object : Converter<T> {
            override fun invoke(o: JsonElement?): T {
                if (o == null) {
                    return null as T
                }
                (o as? ObjectNode)?.put("@class", clazz.name)
                val tmpMapper = MapperCache.getMapper(clazz.classLoader)
                val res: T = tmpMapper.treeToValue(o, clazz)
                if (res is IFrame && session != null) {
                    res.session = session
                }
                return res
            }

            override val isEntity: Boolean = false
            override fun toString() : String { return "FrameConverter"}
        }
    }

    fun <T> getEntityConverter(clazz: Class<T>): Converter<T> {
        return object : Converter<T> {
            override fun invoke(o: JsonElement?): T {
                if (o == null) {
                    return null as T
                }
                val t = if (o is ObjectNode) {
                    o.remove("@class")
                    o.elements().next()
                } else {
                    o
                }
                // we don't have a better way to distinguish between TextNode and ArrayNode at present,
                // both can represent entity
                val finalNode = try {
                    decodeFromString<JsonNode>(t.textValue())
                } catch (e: Exception) {
                    t
                }
                val tmpMapper = mapper.copy()
                tmpMapper.typeFactory = tmpMapper.typeFactory.withClassLoader(clazz.classLoader)
                return tmpMapper.treeToValue(finalNode, clazz)
            }

            override val isEntity: Boolean = true
            override fun toString() : String { return "EntityConverter"}
        }
    }

    fun <T> getInterfaceConverter(session: UserSession, clazz: Class<T>): Converter<T> {
        return object : Converter<T> {
            override fun invoke(o: JsonElement?): T {
                if (o == null) {
                    return null as T
                }
                check(o is ObjectNode)
                val clazz = session.findKClass(o.get("@class").asText())!!
                o.remove("@class")
                val tmpMapper = mapper.copy()
                tmpMapper.typeFactory = tmpMapper.typeFactory.withClassLoader(clazz.java.classLoader)
                val res = tmpMapper.treeToValue(o, clazz.java) as T
                if (res is IFrame) {
                    res.session = session
                }
                return res
            }

            override val isEntity: Boolean = IEntity::class.java.isAssignableFrom(clazz)
            override fun toString() : String { return "InterfaceConverter"}
        }
    }

    fun <T> getJsonNodeConverter(clazz: Class<T>): Converter<T> {
        return object : Converter<T> {
            override fun invoke(o: JsonElement?): T {
                return o as T
            }

            override val isEntity: Boolean
                get() = false
        }
    }

    fun <T> getConverter(session: UserSession?, clazz: Class<T>): Converter<T> {
        return if (clazz.isInterface && IEntity::class.java.isAssignableFrom(clazz)) {
            getInterfaceConverter(session!!, clazz)
        } else if (IFrame::class.java.isAssignableFrom(clazz)) {
            getFrameConverter(session, clazz)
        } else if (JsonNode::class.java.isAssignableFrom(clazz)) {
            getJsonNodeConverter(clazz)
        } else {
            getEntityConverter(clazz)
        }
    }

    inline fun <reified T> getConverter(session: UserSession? = null) : Converter<T> {
        return getFrameConverter(session, T::class.java)
    }

    // database closure can only yield ObjectNode, we need to strip it for entity value type
    inline fun <reified T> getStripperConverter() : Converter<T> {
        return getEntityConverter(T::class.java)
    }

    fun findMapping(s: KClass<*>, t: KClass<*>): Map<String, String> {
        val tm = ((if (t.companionObject != null) t.companionObject!!.members.firstOrNull { it.name == "mappings" } else null)?.call(t.companionObjectInstance) as? Map<String, Map<String, String>>)?.get(s.qualifiedName)
        if (tm != null) return tm
        val sm = ((if (s.companionObject != null) s.companionObject!!.members.firstOrNull { it.name == "mappings" } else null)?.call(s.companionObjectInstance) as? Map<String, Map<String, String>>)?.get(t.qualifiedName)
        if (sm != null) {
            val reverseMap = mutableMapOf<String, String>()
            for ((k, v) in sm) {
                reverseMap[v] = k
            }
            //TODO looks like return is missing here:
            // return reverseMap
        }
        return mapOf()
    }

    inline fun <reified S: IFrame, reified T: IFrame> mappingConvert(s: S) : T {
        val sourceKClass = S::class
        val targetKClass = T::class
        val mapping = findMapping(sourceKClass, targetKClass)
        val objectNode = encodeToJsonElement(s) as ObjectNode
        objectNode.remove("session")
        for ((o, n) in mapping) {
            val v = objectNode.remove(o)
            if (v != null) {
                objectNode.replace(n, v)
            }
        }
        return decodeFromJsonElement<T>(objectNode).apply { this.session = (s as IFrame).session }
    }

    fun parseToJsonElement(s: String) : JsonNode {
        return mapper.readTree(s)
    }

    fun <T> encodeToJsonElement(s: T) : JsonElement {
        return mapper.valueToTree(s)
    }

    inline fun <reified T> makePrimitive(s: T): JsonElement {
      return when (T::class) {
          Int::class -> IntNode(s as Int)
          Float::class -> FloatNode(s as Float)
          Boolean::class -> if (s as Boolean) BooleanNode.TRUE else BooleanNode.FALSE
          String::class -> TextNode(s as String)
          else -> throw RuntimeException("Not a primitive type")
      }
    }

    fun makeObject(maps: Map<String, JsonNode> = mapOf()) : ObjectNode {
        val result = ObjectNode(JsonNodeFactory.instance)
        for ((k, v) in maps.entries) {
            result.set<JsonNode>(k, v)
        }
        return result
    }

    fun makeArray(lists: List<JsonElement> = listOf()) : JsonArray {
        val array = ArrayNode(JsonNodeFactory.instance)
        for (it in lists) array.add(it)
        return array
    }
}

inline fun  <reified T> serialize(session: T) : String {
    val byteArrayOut = ByteArrayOutputStream()
    val objectOut = ObjectOutputStream(byteArrayOut)
    objectOut.writeObject(session)
    return String(Base64.getEncoder().encode(byteArrayOut.toByteArray()))
}

inline fun <reified T> deserialize(encodedSession: String, classLoader: ClassLoader) : T? {
    val decodedSession = Base64.getDecoder().decode(encodedSession)
    val objectIn = object : ObjectInputStream(ByteArrayInputStream(decodedSession)) {
        override fun resolveClass(desc: ObjectStreamClass?): Class<*> {
            return Class.forName(desc!!.name, true, classLoader)
        }
    }
    return objectIn.readObject() as? T
}