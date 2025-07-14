package io.opencui.core

import java.lang.reflect.Field

//
// TODO: This is very jdk dependent, so when change jdk, we need to make sure this is tested.
//
object EnvironmentVariableHacker {
    private var cachedField: Field? = null
    private var isInitialized = false

    fun setEnvironmentVariable(key: String, value: String): Boolean {
        if (!isInitialized) {
            initializeField()
        }

        return try {
            cachedField?.let { field ->
                val env = System.getenv()
                @Suppress("UNCHECKED_CAST")
                val map = field.get(env) as MutableMap<String, String>
                map[key] = value
                true
            } ?: false
        } catch (e: Exception) {
            println("Failed to set environment variable: ${e.message}")
            false
        }
    }

    fun removeEnvironmentVariable(key: String): Boolean {
        if (!isInitialized) {
            initializeField()
        }

        return try {
            cachedField?.let { field ->
                val env = System.getenv()
                @Suppress("UNCHECKED_CAST")
                val map = field.get(env) as MutableMap<String, String>
                map.remove(key)
                true
            } ?: false
        } catch (e: Exception) {
            println("Failed to remove environment variable: ${e.message}")
            false
        }
    }

    private fun initializeField() {
        try {
            val env = System.getenv()
            val clazz = env::class.java

            // Try different possible field names based on JRE version
            val possibleNames = arrayOf("m")

            for (name in possibleNames) {
                try {
                    val field = clazz.getDeclaredField(name)
                    field.isAccessible = true
                    cachedField = field
                    println("Successfully initialized with field: $name")
                    break
                } catch (e: NoSuchFieldException) {
                    e.printStackTrace()
                }
            }

            if (cachedField == null) {
                println("Could not find environment field in ${System.getProperty("java.version")}")
            }

        } catch (e: Exception) {
            println("Failed to initialize environment hacker: ${e.message}")
        } finally {
            isInitialized = true
        }
    }

    fun isSupported(): Boolean {
        if (!isInitialized) {
            initializeField()
        }
        return cachedField != null
    }
}


fun printAllEnvVariables() {
    val env = System.getenv()
    println("=== Environment Variables (Sorted) ===")
    env.toSortedMap().forEach { (key, value) ->
        println("$key = $value")
    }
}

fun main() {
    val env = System.getenv()
    val clazz = env::class.java

    println("JRE: ${System.getProperty("java.version")}")
    println("Available fields:")

    clazz.declaredFields.forEach { field ->
        println("- ${field.name}: ${field.type}")
    }

    printAllEnvVariables()
    EnvironmentVariableHacker.setEnvironmentVariable("GOOGLE_API_KEY", "1234")


    println("****************************************************************************")
    printAllEnvVariables()

}

