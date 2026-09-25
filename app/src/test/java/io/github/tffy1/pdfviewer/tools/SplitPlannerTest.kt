package io.github.tffy1.pdfviewer.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SplitPlannerTest {

    @Test
    fun everyNPagesSplitsEvenly() {
        assertEquals(
            listOf(PageRange(1, 3), PageRange(4, 6), PageRange(7, 9)),
            SplitPlanner.everyNPages(pageCount = 9, pagesPerFile = 3),
        )
    }

    @Test
    fun lastChunkMayBeShorter() {
        assertEquals(
            listOf(PageRange(1, 4), PageRange(5, 8), PageRange(9, 10)),
            SplitPlanner.everyNPages(pageCount = 10, pagesPerFile = 4),
        )
    }

    @Test
    fun onePagePerFile() {
        assertEquals(
            listOf(PageRange(1, 1), PageRange(2, 2), PageRange(3, 3)),
            SplitPlanner.everyNPages(pageCount = 3, pagesPerFile = 1),
        )
    }

    @Test
    fun chunkLargerThanDocumentGivesOneFile() {
        assertEquals(listOf(PageRange(1, 5)), SplitPlanner.everyNPages(pageCount = 5, pagesPerFile = 5))
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroPagesPerFileIsRejected() {
        SplitPlanner.everyNPages(pageCount = 5, pagesPerFile = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyDocumentIsRejected() {
        SplitPlanner.everyNPages(pageCount = 0, pagesPerFile = 1)
    }

    @Test
    fun parsePagesPerFile() {
        assertEquals(3, SplitPlanner.parsePagesPerFile(" 3 ", pageCount = 10))
        assertEquals(10, SplitPlanner.parsePagesPerFile("10", pageCount = 10))
        assertNull(SplitPlanner.parsePagesPerFile("", pageCount = 10))
        assertNull(SplitPlanner.parsePagesPerFile("0", pageCount = 10))
        assertNull(SplitPlanner.parsePagesPerFile("11", pageCount = 10))
        assertNull(SplitPlanner.parsePagesPerFile("-2", pageCount = 10))
        assertNull(SplitPlanner.parsePagesPerFile("2.5", pageCount = 10))
        assertNull(SplitPlanner.parsePagesPerFile("99999999999", pageCount = 10))
    }

    @Test
    fun partFileNames() {
        assertEquals("report_p1-3.pdf", SplitPlanner.partFileName("report.pdf", PageRange(1, 3)))
        assertEquals("report_p7.pdf", SplitPlanner.partFileName("report.PDF", PageRange(7, 7)))
    }

    @Test
    fun baseNameStripsShortExtensionsOnly() {
        assertEquals("report", FileNames.baseName("report.pdf"))
        assertEquals("photo", FileNames.baseName("photo.jpeg"))
        assertEquals("v1.2 notes", FileNames.baseName("v1.2 notes.pdf"))
        assertEquals("no extension", FileNames.baseName("no extension"))
        assertEquals("hidden", FileNames.baseName(".hidden"))
    }

    @Test
    fun sanitizeReplacesIllegalCharacters() {
        assertEquals("a_b_c_d", FileNames.sanitize("a/b\\c:d"))
        assertEquals("what_", FileNames.sanitize("what?"))
        assertEquals("document", FileNames.sanitize("  ...  "))
        assertEquals("document", FileNames.sanitize(""))
        assertEquals(80, FileNames.sanitize("x".repeat(200)).length)
    }

    @Test
    fun withSuffix() {
        assertEquals("report_merged.pdf", FileNames.withSuffix("report.pdf", "merged"))
        assertEquals("scan_2024_merged.pdf", FileNames.withSuffix("scan:2024.pdf", "merged"))
    }
}
