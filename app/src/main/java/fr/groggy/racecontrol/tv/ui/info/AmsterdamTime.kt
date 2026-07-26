package fr.groggy.racecontrol.tv.ui.info

import org.threeten.bp.Instant
import org.threeten.bp.LocalDate
import org.threeten.bp.LocalTime
import org.threeten.bp.ZoneId
import org.threeten.bp.ZonedDateTime
import org.threeten.bp.format.DateTimeFormatter

object AmsterdamTime {
    val ZONE: ZoneId = ZoneId.of("Europe/Amsterdam")
    private val display = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm")
    private val displayDate = DateTimeFormatter.ofPattern("EEE d MMM yyyy")

    fun formatUtc(date: String, time: String?): String {
        val instant = parseUtc(date, time) ?: return "$date ${time.orEmpty()}".trim()
        return display.format(instant.atZone(ZONE)) + " AMS"
    }

    fun formatDateOnly(date: String): String {
        return runCatching {
            displayDate.format(LocalDate.parse(date).atStartOfDay(ZONE))
        }.getOrElse { date }
    }

    fun parseUtc(date: String, time: String?): Instant? {
        return runCatching {
            if (time.isNullOrBlank()) {
                LocalDate.parse(date).atStartOfDay(ZoneId.of("UTC")).toInstant()
            } else {
                val t = time.removeSuffix("Z")
                ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(t), ZoneId.of("UTC")).toInstant()
            }
        }.getOrNull()
    }
}
