package io.github.tffy1.pdfviewer.integration

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Parses PDF date strings (ISO 32000-1 §7.9.4): `D:YYYYMMDDHHmmSSOHH'mm'`.
 *
 * Everything after the year is optional, the "D:" prefix is often missing, and producers are
 * sloppy with the offset (`+01'00'`, `+01'00`, `+0100`, `Z00'00'`, `Z`). Pure Kotlin + java.time
 * (available natively from API 26), so it is unit-tested directly.
 */
object PdfDates {
    /** A parsed date. [offset] is null when the string carries no time zone information. */
    data class PdfDate(val dateTime: LocalDateTime, val offset: ZoneOffset?)

    private val PATTERN = Regex(
        "^(?:D:)?(\\d{4})(\\d{2})?(\\d{2})?(\\d{2})?(\\d{2})?(\\d{2})?" +
            "(?:([Zz+-])(?:(\\d{2})'?(?:(\\d{2})'?)?)?)?$",
    )

    fun parse(raw: String?): PdfDate? {
        val match = PATTERN.matchEntire(raw?.trim().orEmpty()) ?: return null
        val g = match.groupValues
        fun part(index: Int, default: Int): Int = g[index].takeIf { it.isNotEmpty() }?.toInt() ?: default
        return try {
            val dateTime = LocalDateTime.of(
                part(1, 0),
                part(2, 1),
                part(3, 1),
                part(4, 0),
                part(5, 0),
                part(6, 0),
            )
            val offset = when (g[7]) {
                "" -> null
                "Z", "z" -> ZoneOffset.UTC
                else -> {
                    val hours = part(8, 0)
                    val minutes = part(9, 0)
                    val sign = if (g[7] == "-") -1 else 1
                    ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes)
                }
            }
            PdfDate(dateTime, offset)
        } catch (e: DateTimeException) {
            null
        }
    }

    /**
     * Formats [raw] as a localized medium date + short time in [zone]. Dates without offset are
     * shown as written. Returns the raw string unchanged when it cannot be parsed, or null when
     * [raw] is null/blank.
     */
    fun format(
        raw: String?,
        locale: Locale = Locale.getDefault(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String? {
        if (raw.isNullOrBlank()) return null
        val parsed = parse(raw) ?: return raw.trim()
        val local = parsed.offset
            ?.let { parsed.dateTime.atOffset(it).atZoneSameInstant(zone).toLocalDateTime() }
            ?: parsed.dateTime
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
            .format(local)
    }
}
