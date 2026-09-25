package io.github.tffy1.pdfviewer.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNameSanitizerTest {

    @Test
    fun `keeps ordinary names`() {
        assertEquals("Report 2024.pdf", FileNameSanitizer.sanitize("Report 2024.pdf", "pdf"))
        assertEquals("Résumé – final.pdf", FileNameSanitizer.sanitize("Résumé – final.pdf", "pdf"))
    }

    @Test
    fun `keeps extension casing and adds missing extension`() {
        assertEquals("Scan.PDF", FileNameSanitizer.sanitize("Scan.PDF", "pdf"))
        assertEquals("notes.pdf", FileNameSanitizer.sanitize("notes", "pdf"))
        assertEquals("notes.txt.pdf", FileNameSanitizer.sanitize("notes.txt", "pdf"))
    }

    @Test
    fun `removes path separators and reserved characters`() {
        assertEquals("_.._etc_passwd.pdf", FileNameSanitizer.sanitize("../../etc/passwd", "pdf"))
        assertEquals("a_b_c_d_e_f_g_h_i.pdf", FileNameSanitizer.sanitize("a\\b/c:d*e?f\"g<h>i", "pdf"))
        assertEquals("tab_new_line.pdf", FileNameSanitizer.sanitize("tab\tnew\nline", "pdf"))
        val result = FileNameSanitizer.sanitize("/data/data/x/y.pdf", "pdf")
        assertFalse(result.contains('/'))
    }

    @Test
    fun `hidden names and empty input get a fallback`() {
        assertEquals("document.pdf", FileNameSanitizer.sanitize(null, "pdf"))
        assertEquals("document.pdf", FileNameSanitizer.sanitize("", "pdf"))
        assertEquals("document.pdf", FileNameSanitizer.sanitize("...", "pdf"))
        assertEquals("document.pdf", FileNameSanitizer.sanitize(".pdf", "pdf"))
        assertEquals("bashrc.pdf", FileNameSanitizer.sanitize(".bashrc", "pdf"))
        assertEquals("document", FileNameSanitizer.sanitize("  ", null))
    }

    @Test
    fun `collapses whitespace`() {
        assertEquals("a b.pdf", FileNameSanitizer.sanitize("  a     b  .pdf", "pdf"))
    }

    @Test
    fun `without required extension keeps the existing one`() {
        assertEquals("photo.jpeg", FileNameSanitizer.sanitize("photo.jpeg"))
        assertEquals("README", FileNameSanitizer.sanitize("README"))
    }

    @Test
    fun `long names are truncated by utf8 bytes without splitting characters`() {
        val longAscii = "a".repeat(500) + ".pdf"
        val ascii = FileNameSanitizer.sanitize(longAscii, "pdf")
        assertTrue(ascii.endsWith(".pdf"))
        assertTrue(ascii.toByteArray(Charsets.UTF_8).size <= 200)

        val emoji = "📄".repeat(100) // 4 bytes each
        val result = FileNameSanitizer.sanitize(emoji, "pdf")
        assertTrue(result.endsWith(".pdf"))
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= 200)
        val base = result.removeSuffix(".pdf")
        // No lone surrogate at the cut.
        assertEquals(0, base.length % 2)
        assertTrue(base.codePoints().allMatch { it == 0x1F4C4 })
    }
}
