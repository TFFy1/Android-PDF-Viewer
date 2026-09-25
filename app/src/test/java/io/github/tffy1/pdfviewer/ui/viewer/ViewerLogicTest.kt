package io.github.tffy1.pdfviewer.ui.viewer

import io.github.tffy1.pdfviewer.pdf.PdfOpenException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

class ViewerLogicTest {

    @Test
    fun clampPage_keepsIndexInsideDocument() {
        assertEquals(0, clampPage(-3, 10))
        assertEquals(4, clampPage(4, 10))
        assertEquals(9, clampPage(42, 10))
        assertEquals(0, clampPage(5, 0))
    }

    @Test
    fun resolveInitialPage_prefersSavedThenRouteThenRemembered() {
        assertEquals(7, resolveInitialPage(savedPage = 7, routePage = 3, rememberLastPage = true, lastPage = 5, pageCount = 20))
        assertEquals(3, resolveInitialPage(savedPage = null, routePage = 3, rememberLastPage = true, lastPage = 5, pageCount = 20))
        assertEquals(5, resolveInitialPage(savedPage = null, routePage = -1, rememberLastPage = true, lastPage = 5, pageCount = 20))
    }

    @Test
    fun resolveInitialPage_ignoresLastPageWhenNotRemembering() {
        assertEquals(0, resolveInitialPage(savedPage = null, routePage = -1, rememberLastPage = false, lastPage = 5, pageCount = 20))
        assertEquals(0, resolveInitialPage(savedPage = null, routePage = -1, rememberLastPage = true, lastPage = null, pageCount = 20))
    }

    @Test
    fun resolveInitialPage_clampsStaleValues() {
        // Document got shorter since it was last read.
        assertEquals(9, resolveInitialPage(savedPage = null, routePage = -1, rememberLastPage = true, lastPage = 50, pageCount = 10))
        assertEquals(9, resolveInitialPage(savedPage = null, routePage = 99, rememberLastPage = false, lastPage = null, pageCount = 10))
    }

    @Test
    fun parsePageInput_validatesRangeAndFormat() {
        assertEquals(PageInput.Empty, parsePageInput("", 10))
        assertEquals(PageInput.Empty, parsePageInput("   ", 10))
        assertEquals(PageInput.Valid(0), parsePageInput("1", 10))
        assertEquals(PageInput.Valid(9), parsePageInput(" 10 ", 10))
        assertEquals(PageInput.OutOfRange, parsePageInput("0", 10))
        assertEquals(PageInput.OutOfRange, parsePageInput("11", 10))
        assertEquals(PageInput.OutOfRange, parsePageInput("99999999999999", 10))
        assertEquals(PageInput.Invalid, parsePageInput("-1", 10))
        assertEquals(PageInput.Invalid, parsePageInput("1a", 10))
    }

    @Test
    fun parsePageInput_acceptsNonLatinDigits() {
        // Arabic-Indic "١٢" = 12
        assertEquals(PageInput.Valid(11), parsePageInput("١٢", 20))
    }

    @Test
    fun sliderValueToPage_roundsAndClamps() {
        assertEquals(3, sliderValueToPage(2.6f, 10))
        assertEquals(2, sliderValueToPage(2.4f, 10))
        assertEquals(9, sliderValueToPage(12f, 10))
        assertEquals(0, sliderValueToPage(Float.NaN, 10))
    }

    @Test
    fun classifyOpenError_walksCauseChain() {
        assertEquals(ViewerErrorKind.NOT_FOUND, classifyOpenError(PdfOpenException("x", FileNotFoundException())))
        assertEquals(ViewerErrorKind.NO_PERMISSION, classifyOpenError(PdfOpenException("x", SecurityException())))
        assertEquals(ViewerErrorKind.NO_PERMISSION, classifyOpenError(IOException(SecurityException(FileNotFoundException()))))
        assertEquals(ViewerErrorKind.CORRUPT, classifyOpenError(PdfOpenException("bad xref")))
        assertEquals(ViewerErrorKind.OTHER, classifyOpenError(IllegalStateException()))
    }

    @Test
    fun suggestedCopyFileName_keepsSingleExtension() {
        assertEquals("report.pdf", suggestedCopyFileName("report.pdf"))
        assertEquals("report.pdf", suggestedCopyFileName("report"))
        assertEquals("report (annotated).pdf", suggestedCopyFileName("report.PDF", "annotated"))
        assertEquals("document.pdf", suggestedCopyFileName("  .pdf "))
    }
}
