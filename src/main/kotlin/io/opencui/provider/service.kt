package io.opencui.provider

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.*
import io.opencui.core.*
import io.opencui.serialization.*
import org.slf4j.LoggerFactory
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.reflect.KParameter
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.instanceParameter

interface Closable {
    fun close()
}

interface IConnection: Closable, IExtension {
    val cfg: Configuration
    fun <T> svInvoke(
        providerMeta: Map<String, String>,
        functionMeta: Map<String, Any?>,
        body: String,
        converter: Converter<T>
    ): T

    fun <T> mvInvoke(
        providerMeta: Map<String, String>,
        functionMeta: Map<String, Any?>,
        body: String,
        converter: Converter<T>
    ): List<T>
}


fun <T : ITemplatedProvider> T.cloneViaReflection(): T {
    val kClass = this::class
    val copyFunction = kClass.declaredFunctions.firstOrNull { it.name == "copy" }
        ?: throw IllegalArgumentException("No copy() method found for ${kClass.simpleName}")

    val instanceParam = copyFunction.instanceParameter!!
    return copyFunction.callBy(mapOf(instanceParam to this)) as T
}




// Templated provider will have connection.
interface ITemplatedProvider : IProvider {
    var provider : IConnection?
    override fun getConfiguration(): Configuration? {
        return provider?.cfg
    }

    // If the implementation is session dependent, one should clone one for that session.
    override fun cloneForSession(userSession: UserSession): IExtension {
        val another = this.cloneViaReflection() as IProvider
        another.session = userSession
        return another
    }
}

class ProviderInvokeException(msg: String): Exception(msg)

// This connection is mainly used for make writing testing easy.
data class SqlConnection(override val cfg: Configuration) : IConnection {
    val url = cfg.url!!
    val user = cfg["user"]!! as String
    val password = cfg["password"]!! as String

    val dbConn: java.sql.Connection

    init {
        val driver = driver()
        Class.forName(driver)
        dbConn = DriverManager.getConnection(url, user, password)
    }

    private fun driver() : String {
        val tokens = url.split(":")
        return when(tokens[1]) {
            "mysql" -> "com.mysql.cj.jdbc.Driver"
            "h2" -> "org.h2.Driver"
            else -> throw IllegalArgumentException("${tokens[1]} is not supported.")
        }
    }

    @Throws(ProviderInvokeException::class)
    fun invoke(body: String): ArrayNode {
        val results = mutableListOf<JsonNode>()
        try {
            val stmt = dbConn.createStatement()
            var hasMoreResult = stmt.execute(body)
            while (hasMoreResult || stmt.updateCount != -1) {
                if (hasMoreResult) {
                    results.clear()
                    val result = stmt.resultSet
                    while (result.next()) {
                        val rsmd = result.metaData
                        val rowMap = mutableMapOf<String, JsonElement>()
                        for (k in 1..rsmd.columnCount) {
                            val columnAlias = rsmd.getColumnLabel(k)
                            val columnValue = result.getString(columnAlias)
                            // TODO: here we can only make TextNode for now
                            rowMap[columnAlias] = Json.makePrimitive(columnValue)
                        }
                        results.add(Json.makeObject(rowMap))
                    }
                }
                // getMoreResults(current) does not update updateCount here; we cannot keep current result open
                hasMoreResult = stmt.moreResults
            }
            stmt.close()
        } catch (e: SQLException) {
            logger.error("fail to exec mysql query : $body; err : ${e.message}")
            throw ProviderInvokeException(e.message ?: "no message")
        }
        val result = ArrayNode(JsonNodeFactory.instance, results)
        logger.debug("Sql Provider result : ${result.toPrettyString()}")
        return result
    }

    @Throws(ProviderInvokeException::class)
    override fun <T> svInvoke(providerMeta: Map<String, String>, functionMeta: Map<String, Any?>, body: String, converter: Converter<T>): T {
        val result = invoke(body)
        return if (result.isEmpty) { converter(null) } else { converter(result[0]) }
    }

    @Throws(ProviderInvokeException::class)
    override fun <T> mvInvoke(providerMeta: Map<String, String>, functionMeta: Map<String, Any?>, body: String, converter: Converter<T>): List<T> {
        val result = invoke(body)
        val results = mutableListOf<T>()
        result.map { results.add(converter(it)) }
        return results
    }

    override fun close() {
        dbConn.close()
    }

    companion object{
        val logger = LoggerFactory.getLogger(SqlConnection::class.java)
    }
}


