package io.github.tffy1.pdfviewer.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageRangeParserTest {

    private fun valid(input: String, pageCount: Int = 10): List<PageRange> {
        val result = PageRangeParser.parse(input, pageCount)
        assertTrue("Expected valid for '$input' but was $result", result is PageRangeParseResult.Valid)
        return (result as PageRangeParseResult.Valid).ranges
    }

    private fun error(input: String, pageCount: Int = 10): PageRangeError {
        val result = PageRangeParser.parse(input, pageCount)
        assertTrue("Expected invalid for '$input' but was $result", result is PageRangeParseResult.Invalid)
        return (result as PageRangeParseResult.Invalid).error
    }

    @Test
    fun singlePage() {
        assertEquals(listOf(PageRange(5, 5)), valid("5"))
    }

    @Test
    fun mixedExpressionKeepsOrder() {
        assertEquals(
            listOf(PageRange(1, 3), PageRange(5, 5), PageRange(8, 10)),
            valid("1-3, 5, 8-"),
        )
        assertEquals(listOf(PageRange(8, 10), PageRange(1, 2)), valid("8-,1-2"))
    }

    @Test
    fun openStartMeansFromFirstPage() {
        assertEquals(listOf(PageRange(1, 4)), valid("-4"))
    }

    @Test
    fun openEndMeansToLastPage() {
        assertEquals(listOf(PageRange(7, 10)), valid("7-"))
        assertEquals(listOf(PageRange(10, 10)), valid("10-"))
    }

    @Test
    fun whitespaceSemicolonsAndEmptyPartsAreTolerated() {
        assertEquals(
            listOf(PageRange(2, 4), PageRange(6, 6)),
            valid("  2 - 4 ;; 6 , "),
        )
    }

    @Test
    fun typographicDashesAreAccepted() {
        assertEquals(listOf(PageRange(1, 3)), valid("1–3"))
        assertEquals(listOf(PageRange(2, 5)), valid("2—5"))
        assertEquals(listOf(PageRange(4, 10)), valid("4−"))
    }

    @Test
    fun leadingZerosAreIgnored() {
        assertEquals(listOf(PageRange(3, 7)), valid("003-07"))
    }

    @Test
    fun wholeDocument() {
        assertEquals(listOf(PageRange(1, 1)), valid("1-1", pageCount = 1))
        assertEquals(listOf(PageRange(1, 10)), valid("1-10"))
    }

    @Test
    fun emptyInputIsRejected() {
        assertEquals(PageRangeError.Empty, error(""))
        assertEquals(PageRangeError.Empty, error("   "))
        assertEquals(PageRangeError.Empty, error(" , ;"))
    }

    @Test
    fun invalidTokensAreReported() {
        assertEquals(PageRangeError.InvalidToken("abc"), error("1, abc"))
        assertEquals(PageRangeError.InvalidToken("-"), error("-"))
        assertEquals(PageRangeError.InvalidToken("1-2-3"), error("1-2-3"))
        assertEquals(PageRangeError.InvalidToken("1 2"), error("1 2"))
        assertEquals(PageRangeError.InvalidToken("+3"), error("+3"))
        assertEquals(PageRangeError.InvalidToken("2.5"), error("2.5"))
    }

    @Test
    fun pageZeroIsOutOfRange() {
        assertEquals(PageRangeError.PageOutOfRange("0", 10), error("0"))
        assertEquals(PageRangeError.PageOutOfRange("0", 10), error("0-3"))
        assertEquals(PageRangeError.PageOutOfRange("0", 10), error("000"))
    }

    @Test
    fun pagesPastTheEndAreOutOfRange() {
        assertEquals(PageRangeError.PageOutOfRange("11", 10), error("11"))
        assertEquals(PageRangeError.PageOutOfRange("12", 10), error("3-12"))
        assertEquals(PageRangeError.PageOutOfRange("12", 10), error("12-"))
        assertEquals(PageRangeError.PageOutOfRange("15", 10), error("-15"))
    }

    @Test
    fun hugeNumbersDoNotOverflow() {
        assertEquals(PageRangeError.PageOutOfRange("99999999999999", 10), error("99999999999999"))
    }

    @Test
    fun reversedRangeIsReported() {
        assertEquals(PageRangeError.Reversed(5, 3), error("5-3"))
    }

    @Test
    fun firstErrorWins() {
        assertEquals(PageRangeError.InvalidToken("x"), error("x, 50"))
    }

    @Test
    fun emptyDocumentRejectsEverything() {
        assertEquals(PageRangeError.PageOutOfRange("1", 0), error("1", pageCount = 0))
        assertEquals(PageRangeError.PageOutOfRange("1", 0), error("1-", pageCount = 0))
    }

    @Test
    fun distinctPageIndicesAreZeroBasedAndDeduplicated() {
        val result = PageRangeParser.parse("3-4, 1, 4, 2-3", 10) as PageRangeParseResult.Valid
        assertEquals(listOf(2, 3, 0, 1), result.distinctPageIndices)
    }

    @Test
    fun pageRangeHelpers() {
        val range = PageRange(3, 5)
        assertEquals(3, range.size)
        assertEquals(2..4, range.indices)
        assertEquals("3-5", range.toString())
        assertEquals("7", PageRange(7, 7).toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun pageRangeRejectsInvalidBounds() {
        PageRange(4, 2)
    }
}
