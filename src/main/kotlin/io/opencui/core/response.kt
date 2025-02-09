package io.opencui.core

import io.opencui.du.DUMeta
import io.opencui.du.getSlotMeta
import java.io.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

// This is a better way to handle language dependence.
// This gives use one way of build language dependent behavior.
// The good thing is, we can easily to expand to many languages.
// Refactor the DM based on this.
sealed interface RGBase: Serializable {
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
            is DayOfWeek -> getDisplayName(TextStyle.FULL, locale)
            is SlotType -> slotTypeExpression(toString())
            is IEntity -> duMeta.getEntityInstances(typeName)[toString()]?.firstOrNull() ?: toString()
            else -> toString()
        }
    }

    private fun slotTypeExpression(slotFull: String) : String? {
        val lastDotIndex = slotFull.lastIndexOf(".")
        if (lastDotIndex < 0) return null
        val frameName = slotFull.substring(0, lastDotIndex)
        val slotName = slotFull.substring(lastDotIndex+1)
        val slotMeta = duMeta.getSlotMeta(frameName, slotName)
        val triggers = slotMeta?.triggers ?: return null
        return triggers.subList(1, triggers.size).firstOrNull()
    }

    fun <T: Any> T.typeExpression() : String? {
        val typeName = this::class.qualifiedName
        return duMeta.getTriggers(typeName!!).firstOrNull{it.isNotBlank()}?: typeName
    }

    // TODO(sean): remove this when we have new code gen example.
    @Deprecated("Use expression.")
    fun <T: Any> T.name(): String? {
        return expression()
    }

    @Deprecated("Use typeExpression.")
    fun <T: Any> T.typeName() : String? {
        return typeExpression()
    }
}

data class Zh(override val duMeta: DUMeta) : RGBase {
    override val locale = Locale.CHINA!!
    override val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)!!
    override val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)!!
    override val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)!!
}

data class En(override val duMeta: DUMeta) : RGBase {
    override val locale = Locale.US!!
    override val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)!!
    override val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)!!
    override val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)!!
}

