package io.opencui.system1

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonTypeInfo
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.superclasses
import kotlin.reflect.jvm.javaField

// Placeholder imports for GenAI types.
// This version correctly assumes the nested enum structure: `com.google.genai.types.Type.Known`
// And that the Schema.Builder's .type() method accepts a String.
import com.google.genai.types.Schema
import com.google.genai.types.Type

/**
 * Maps a Kotlin KClass to the nested com.google.genai.types.Type.Known enum.
 */
fun mapKotlinTypeToGenAISchemaType(kClass: KClass<*>): Type.Known {
    return when (kClass) {
        String::class -> Type.Known.STRING
        Int::class, Long::class, Short::class, Byte::class -> Type.Known.INTEGER
        Float::class, Double::class -> Type.Known.NUMBER
        Boolean::class -> Type.Known.BOOLEAN
        List::class, Set::class, Array::class -> Type.Known.ARRAY
        else -> {
            // isData is an extension from kotlin-reflect, ensure you have the dependency.
            if (kClass.isData || !kClass.java.isPrimitive) {
                Type.Known.OBJECT
            } else {
                throw IllegalArgumentException("Unsupported Kotlin type for GenAI Schema: ${kClass.simpleName}")
            }
        }
    }
}

/**
 * Converts a Kotlin data class's KClass to a com.google.genai.types.Schema.
 *
 * This function uses reflection to build a schema that represents the data class,
 * including nested objects and lists. It respects @JsonProperty for naming and
 * nullability for the 'required' field.
 *
 * @param T The type of the Kotlin data class.
 * @return A com.google.genai.types.Schema representation of the data class.
 * @throws IllegalArgumentException if a type cannot be mapped or for other reflection issues.
 */
fun <T : Any> KClass<T>.toGenAISchema(): Schema {
    // Top-level schema must be an object.
    val schemaBuilder = Schema.builder()
        .type(Type.Known.OBJECT.name) // Use the string name of the enum

    val propertiesMap = mutableMapOf<String, Schema>()
    val requiredProperties = mutableListOf<String>()

    this.memberProperties.forEach { prop ->
        // Use @JsonProperty value if present, otherwise use the property name.
        val propName = prop.javaField?.getAnnotation(JsonProperty::class.java)?.value ?: prop.name

        // Recursively create the schema for the property using its KType.
        val propertySchema = createSchemaFromKType(prop.returnType)
        propertiesMap[propName] = propertySchema

        // Non-nullable properties are required.
        if (!prop.returnType.isMarkedNullable) {
            requiredProperties.add(propName)
        }
    }

    val typeInfo = this.resolveJsonTypeInfo()
    if (typeInfo?.use == JsonTypeInfo.Id.CLASS) {
        val discriminator = typeInfo.property.takeIf { it.isNotBlank() } ?: "@class"
        if (!propertiesMap.containsKey(discriminator)) {
            val discriminatorSchemaBuilder = Schema.builder().type(Type.Known.STRING.name)
            val qualifier = this.qualifiedName
            if (qualifier != null) {
                discriminatorSchemaBuilder.description("Concrete type identifier. Use \"$qualifier\".")
            }
            propertiesMap[discriminator] = discriminatorSchemaBuilder.build()
        }
        if (!requiredProperties.contains(discriminator)) {
            requiredProperties.add(discriminator)
        }
    }

    if (propertiesMap.isNotEmpty()) {
        schemaBuilder.properties(propertiesMap)
    }
    if (requiredProperties.isNotEmpty()) {
        schemaBuilder.required(requiredProperties) // Assuming .required(List<String>)
    }

    return schemaBuilder.build()
}

/**
 * Helper function to create a Schema from a KType.
 * This is the core of the conversion, handling primitives, objects, and arrays.
 */
private fun createSchemaFromKType(type: KType): Schema {
    val classifier = type.classifier
    require(classifier is KClass<*>) { "Unsupported type classifier: $classifier" }

    val schemaTypeEnum = mapKotlinTypeToGenAISchemaType(classifier) // Returns Type.Known
    val schemaBuilder = Schema.builder().type(schemaTypeEnum.name)   // Use the string name

    when (schemaTypeEnum) {
        Type.Known.OBJECT -> {
            // For nested objects, recursively call the main conversion function.
            // This check ensures we don't try to generate a schema for generic 'Any'.
            if (classifier.isData || (!classifier.java.isPrimitive && classifier != Any::class)) {
                // A nested object's schema is the *entire result* of the recursive call.
                return classifier.toGenAISchema()
            }
        }
        Type.Known.ARRAY -> {
            // For arrays, we need to define the schema of their items.
            val itemType = type.arguments.firstOrNull()?.type
            require(itemType != null) { "List property must have a type argument. Star projections are not supported." }

            // Recursively create the schema for the list items and set it.
            val itemSchema = createSchemaFromKType(itemType)
            schemaBuilder.items(itemSchema)
        }
        // For STRING, INTEGER, NUMBER, BOOLEAN, no further action is needed.
        else -> {}
    }

    return schemaBuilder.build()
}

private fun KClass<*>.resolveJsonTypeInfo(): JsonTypeInfo? {
    this.findAnnotation<JsonTypeInfo>()?.let { return it }
    for (superClass in this.superclasses) {
        val info = superClass.resolveJsonTypeInfo()
        if (info != null) {
            return info
        }
    }
    return null
}

