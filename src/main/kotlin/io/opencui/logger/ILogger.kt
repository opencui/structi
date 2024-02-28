package io.opencui.logger

import io.opencui.core.Configuration
import io.opencui.core.ExtensionBuilder
import io.opencui.core.IExtension
import io.opencui.serialization.JsonElement
import java.sql.*
import java.time.LocalDateTime
import org.postgresql.util.PGobject
import org.slf4j.Logger
import org.slf4j.LoggerFactory

data class Turn(
    val utterance: String,
    val expectations: JsonElement,  // this should be an array of expectation, each is an object.
    val predictedFrameEvents: JsonElement,   // again an array of events.
    val dialogActs: JsonElement,    // an array of dialog acts.
    val timeStamp: LocalDateTime,
    val duTime: Long,
)  {
    var trueFrameEvents: JsonElement? = null  // this is provided manually when there are mistakes
    var dmTime: Long? = null // We might need this.
    var nluVersion: String? = null
    var duVersion: String? = null
    var channelType: String? = null
    var channelLabel: String? = null
    var userId: String? = null
}

interface ILogger: IExtension {
    fun log(turn: Turn): Boolean
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


    override fun log(turn: Turn): Boolean {
        val sqlStatement = """INSERT INTO logger.turn VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
   
        val builder = Inserter(conn.prepareStatement(sqlStatement));
        builder.add(turn.channelType)
        builder.add(turn.channelLabel)
        builder.add(turn.userId)
        builder.add(turn.utterance)
        builder.add(turn.expectations)
        builder.add(turn.predictedFrameEvents)
        builder.add(turn.dialogActs)
        builder.add(turn.timeStamp)
        builder.add(turn.trueFrameEvents)
        builder.add(turn.nluVersion)
        builder.add(turn.duVersion)
        builder.add(turn.dmTime)
        builder.add(turn.duTime)
        try {
            val result = builder.stmt.executeUpdate()
            if (result <= 0) {
                logger.info("Insert $turn has issues.")
            } else {
                logger.info("inserted one line to ${info[URL]}.")
            }
        } catch (e: SQLException) {
            logger.info("Insert $turn got ${e.toString()}")
        }
        return true
    }
    
    companion object : ExtensionBuilder<ILogger> {
        val logger: Logger = LoggerFactory.getLogger(JdbcLogger::class.java)

        const val TABLE : String = "turn"
        const val URL: String = "pgUrl"
        const val DRIVER: String = "driver"

        override fun invoke(p1: Configuration): ILogger {
            logger.info("Creating JdbcLogger for ${p1[URL]}")
            return JdbcLogger(p1)
        }

    }
}