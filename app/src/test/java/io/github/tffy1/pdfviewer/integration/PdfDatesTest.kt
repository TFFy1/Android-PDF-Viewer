package io.github.tffy1.pdfviewer.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class PdfDatesTest {

    @Test
    fun `parses full date with apostrophe offset`() {
        val date = PdfDates.parse("D:20230105133045+01'00'")
        assertEquals(LocalDateTime.of(2023, 1, 5, 13, 30, 45), date?.dateTime)
        assertEquals(ZoneOffset.ofHours(1), date?.offset)
    }

    @Test
    fun `parses negative offset with minutes`() {
        val date = PdfDates.parse("D:19990209153925-03'30'")
        assertEquals(LocalDateTime.of(1999, 2, 9, 15, 39, 25), date?.dateTime)
        assertEquals(ZoneOffset.ofHoursMinutes(-3, -30), date?.offset)
    }

    @Test
    fun `accepts sloppy offsets`() {
        assertEquals(ZoneOffset.ofHours(2), PdfDates.parse("D:20200101000000+02'00")?.offset)
        assertEquals(ZoneOffset.ofHours(2), PdfDates.parse("D:20200101000000+0200")?.offset)
        assertEquals(ZoneOffset.ofHours(2), PdfDates.parse("D:20200101000000+02")?.offset)
        assertEquals(ZoneOffset.UTC, PdfDates.parse("D:20200101000000Z")?.offset)
        assertEquals(ZoneOffset.UTC, PdfDates.parse("D:20200101000000Z00'00'")?.offset)
    }

    @Test
    fun `missing prefix and partial dates`() {
        assertEquals(LocalDateTime.of(2021, 6, 30, 8, 5, 0), PdfDates.parse("202106300805")?.dateTime)
        assertEquals(LocalDateTime.of(2021, 1, 1, 0, 0, 0), PdfDates.parse("D:2021")?.dateTime)
        assertEquals(LocalDateTime.of(2021, 7, 1, 0, 0, 0), PdfDates.parse("D:202107")?.dateTime)
        assertNull(PdfDates.parse("D:2021")?.offset)
    }

    @Test
    fun `invalid dates return null`() {
        assertNull(PdfDates.parse(null))
        assertNull(PdfDates.parse(""))
        assertNull(PdfDates.parse("yesterday"))
        assertNull(PdfDates.parse("D:20231301000000"))
        assertNull(PdfDates.parse("D:20230230000000"))
        assertNull(PdfDates.parse("D:20230101250000"))
        assertNull(PdfDates.parse("D:20230101000000+25'00'"))
        assertNull(PdfDates.parse("D:2023-01-01"))
    }

    @Test
    fun `format converts to the requested zone`() {
        val expected = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(Locale.US)
            .format(LocalDateTime.of(2023, 1, 5, 12, 30, 45))
        assertEquals(expected, PdfDates.format("D:20230105133045+01'00'", Locale.US, ZoneId.of("UTC")))
    }

    @Test
    fun `format keeps local time when no offset is given`() {
        val expected = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(Locale.GERMANY)
            .format(LocalDateTime.of(2023, 1, 5, 13, 30, 45))
        assertEquals(expected, PdfDates.format("D:20230105133045", Locale.GERMANY, ZoneId.of("Asia/Tokyo")))
    }

    @Test
    fun `format falls back to raw text`() {
        assertEquals("Last Tuesday", PdfDates.format("  Last Tuesday ", Locale.US, ZoneId.of("UTC")))
        assertNull(PdfDates.format(null))
        assertNull(PdfDates.format("   "))
    }
}
