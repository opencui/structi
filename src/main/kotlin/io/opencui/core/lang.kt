package io.opencui.core

import io.opencui.du.DUMeta
import java.io.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// This is a better way to handle language dependence.
// This gives use one way of build language dependent behavior.
// The good thing is, we can easily to expand to many languages.
// Refactor the DM based on this.
sealed interface RGLang: Serializable {
    val locale: Locale
    val formatter: DateTimeFormatter
    val dateFormatter: DateTimeFormatter
    val timeFormatter: DateTimeFormatter
    val duMeta: DUMeta

    fun <T: Any> T.expression(): String? {
        val typeName = this::class.qualifiedName!!
        return when(this) {
            is LocalDateTime -> formatter.format(this)
            is LocalDate ->  dateFormatter.format(this)
            is LocalTime -> timeFormatter.format(this)
            is String -> toString()
            is IEntity -> duMeta.getEntityInstances(typeName)[toString()]?.firstOrNull() ?: toString()
            else -> null
        }
    }

    fun <T: Any> T.typeExpression() : String? {
        val typeName = this::class.qualifiedName
        return duMeta.getTriggers(typeName!!).firstOrNull()?: typeName
    }
}

data class Zh(override val duMeta: DUMeta) : RGLang {
    override val locale = Locale.CHINA!!
    override val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).withZone(ZoneId.of("CTT", ZoneId.SHORT_IDS))!!
    override val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(ZoneId.of("CTT", ZoneId.SHORT_IDS))!!
    override val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale).withZone(ZoneId.of("CTT", ZoneId.SHORT_IDS))!!
}

data class En(override val duMeta: DUMeta) : RGLang {
    override val locale = Locale.US!!
    override val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).withZone( ZoneId.of("UTC"))!!
    override val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone( ZoneId.of("UTC"))!!
    override val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale).withZone( ZoneId.of("UTC"))!!
}

