package io.opencui.logger

import com.fasterxml.jackson.annotation.JsonProperty
import io.opencui.core.Configuration
import io.opencui.core.ExtensionBuilder
import io.opencui.core.IExtension
import io.opencui.core.UserSession
import io.opencui.serialization.Json
import io.opencui.serialization.JsonElement
import java.sql.*
import java.time.LocalDateTime
import org.postgresql.util.PGobject
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import java.util.*
import org.apache.kafka.common.serialization.StringSerializer
import java.io.Closeable


// Turn is used to save the conversation history.
data class Turn(
    val utterance: String,
    val expectations: JsonElement,  // this should be an array of expectation, each is an object.
    val predictedFrameEvents: JsonElement,   // again an array of events.
    val duTime: Long,
    val lang: String
)  {
    // an array of dialog acts.
    var dialogActs: JsonElement?= null
    var trueFrameEvents: JsonElement? = null  // this is provided manually when there are mistakes
    @JsonProperty("timestamp")
    var timeStamp: LocalDateTime? = null
    var dmTime: Long? = null // We might need this.
    var nluVersion: String? = null

    @JsonProperty("agent_id")
    var agentId: String? = null

    @JsonProperty("org_id")
    var orgId: String? = null

    var type: String? = null
    var source: String? = null
    var duVersion: String? = null
    var channelType: String? = null
    var channelLabel: String? = null
    var sessionId: String? = null
    // along with channelType and channel label, this uniquely identify a
    var messageId: String? = null
    var userId: String? = null
    var input: Map<String, Any>? = null
    var output: Map<String, Any>? = null
    var usage: Map<String, Any>? = null
}

interface ILogger: IExtension {
    fun log(turn: Turn, session: UserSession): Boolean
}

data class Inserter(val stmt: PreparedStatement) {
    var index: Int = 1
    inline fun <reified T> add(value: T) {
        stmt.add(index, value)
        index += 1
    }

    inline fun <reified T> PreparedStatement.add(index: Int, v: T?) {
        if (v == null) {
            this.setNull(index, Types.NULL)
        } else {
            when (v) {
                is String -> this.setString(index, v)
                is Long -> this.setLong(index, v)
                is LocalDateTime -> this.setTimestamp(index, Timestamp.valueOf(v))
                is JsonElement -> this.setObject(index, PGobject().apply { type = "json"; value = v.toString() })
                else -> throw RuntimeException("not ready for this type")
            }
        }
    }
}

data class JdbcLogger(val info: Configuration): ILogger {
    init {
        Class.forName("org.postgresql.Driver")
    }

    val conn : Connection by lazy {
        DriverManager.getConnection(info[URL] as String)
    }

    override fun log(turn: Turn, session: UserSession): Boolean {
        val builder = Inserter(conn.prepareStatement(sqlStatement));
        builder.add(turn.channelType)
        builder.add(turn.channelLabel)
        builder.add(turn.userId)
        builder.add(turn.utterance)
        builder.add(turn.expectations)
        builder.add(turn.predictedFrameEvents)
        builder.add(turn.dialogActs)
        builder.add(turn.dmTime)
        builder.add(turn.lang)
        try {
            val result = builder.stmt.executeUpdate()
            if (result <= 0) {
                logger.info("Insert $turn has issues.")
            } else {
                logger.info("inserted one line to ${info[URL]}.")
            }
            builder.stmt.close()
        } catch (e: SQLException) {
            logger.info("Insert $turn got ${e.toString()}")
        }
        return true
    }
    
    companion object : ExtensionBuilder {
        val logger: Logger = LoggerFactory.getLogger(JdbcLogger::class.java)
        val sqlStatement = """INSERT INTO "logger"."Turn" ("channelType", "channelLabel", "userId", "utterance", "expectations", "predictedFrameEvents", "dialogActs", "duTime", "lang") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        const val TABLE : String = "turn"
        const val URL: String = "pgUrl"
        const val DRIVER: String = "driver"

        override fun invoke(p1: Configuration): ILogger {
            logger.info("Creating JdbcLogger for ${p1[URL]}")
            return JdbcLogger(p1)
        }

    }
}

// Eventually, we should move the KafkaLogger as it support async operation.
class KafkaLogger(val info: Configuration): ILogger, Closeable{
    val conn : KafkaProducer<String, String> = KafkaProducer<String, String>(getProducerProperties(info))
    val botId = info[BotId]!! as String
    val orgId = info[OrgId]!! as String

    fun getProducerProperties(info: Configuration): Properties {
        val broker = info[KAFKA_BROKER]!! as String
        return Properties().apply {
            put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker)
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.ACKS_CONFIG, "all")
        }
    }

    override fun close() {
        conn.close()
    }

    override fun log(turn: Turn, session: UserSession): Boolean {
        turn.orgId = orgId
        turn.agentId = botId
        val jsonValue = Json.encodeToString(turn)
        val key = session.botInfo.fullName
        try {
            val record = ProducerRecord(topic, key, jsonValue)
            conn.send(record) { metadata, exception ->
                if (exception != null) {
                    // Handle the exception
                    logger.error("Error sending record(key=$key): ${exception.message}")
                } else {
                    // Log success
                    logger.info(
                        "Sent record(key=$key value=$jsonValue) to partition=${metadata.partition()} offset=${metadata.offset()}"
                    )
                }
            }
        } catch (e: Exception) {
            logger.error("Error producing messages: ${e.message}")
            e.printStackTrace()
        }
        return true
    }

    companion object : ExtensionBuilder {
        val logger: Logger = LoggerFactory.getLogger(KafkaLogger::class.java)
        val KAFKA_BROKER = "kafkaBroker"
        val BotId = "botId"
        val OrgId = "orgId"
        const val topic = "bill"

        override fun invoke(p1: Configuration): ILogger {
            return KafkaLogger(p1)
        }
    }
}