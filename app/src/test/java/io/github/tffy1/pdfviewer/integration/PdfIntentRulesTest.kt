package io.github.tffy1.pdfviewer.integration

import io.github.tffy1.pdfviewer.integration.PdfIntentRules.MimeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfIntentRulesTest {

    @Test
    fun `only content and file schemes are supported`() {
        assertTrue(PdfIntentRules.isSupportedScheme("content"))
        assertTrue(PdfIntentRules.isSupportedScheme("file"))
        assertTrue(PdfIntentRules.isSupportedScheme("CONTENT"))
        assertFalse(PdfIntentRules.isSupportedScheme("http"))
        assertFalse(PdfIntentRules.isSupportedScheme("https"))
        assertFalse(PdfIntentRules.isSupportedScheme("android.resource"))
        assertFalse(PdfIntentRules.isSupportedScheme(null))
    }

    @Test
    fun `mime classification ignores case and parameters`() {
        assertEquals(MimeKind.PDF, PdfIntentRules.classifyMime("application/pdf"))
        assertEquals(MimeKind.PDF, PdfIntentRules.classifyMime("Application/PDF"))
        assertEquals(MimeKind.PDF, PdfIntentRules.classifyMime("application/pdf; charset=binary"))
        assertEquals(MimeKind.PDF, PdfIntentRules.classifyMime("application/x-pdf"))
        assertEquals(MimeKind.GENERIC, PdfIntentRules.classifyMime("application/octet-stream"))
        assertEquals(MimeKind.GENERIC, PdfIntentRules.classifyMime("*/*"))
        assertEquals(MimeKind.GENERIC, PdfIntentRules.classifyMime(null))
        assertEquals(MimeKind.GENERIC, PdfIntentRules.classifyMime("  "))
        assertEquals(MimeKind.OTHER, PdfIntentRules.classifyMime("image/png"))
        assertEquals(MimeKind.OTHER, PdfIntentRules.classifyMime("text/plain"))
    }

    @Test
    fun `declared pdf is accepted without resolving anything`() {
        val accepted = PdfIntentRules.isAcceptablePdf(
            declaredMime = "application/pdf",
            resolvedMime = { throw AssertionError("must not resolve") },
            displayName = { throw AssertionError("must not query name") },
        )
        assertTrue(accepted)
    }

    @Test
    fun `declared specific non-pdf type is rejected`() {
        assertFalse(PdfIntentRules.isAcceptablePdf("image/jpeg", { "application/pdf" }, { "a.pdf" }))
    }

    @Test
    fun `resolved pdf type wins over generic declared type`() {
        assertTrue(
            PdfIntentRules.isAcceptablePdf(
                declaredMime = "application/octet-stream",
                resolvedMime = { "application/pdf" },
                displayName = { throw AssertionError("must not query name") },
            ),
        )
        assertTrue(PdfIntentRules.isAcceptablePdf(null, { "application/pdf" }, { null }))
    }

    @Test
    fun `generic types need a pdf file name`() {
        assertTrue(PdfIntentRules.isAcceptablePdf("application/octet-stream", { null }, { "Invoice.PDF" }))
        assertTrue(PdfIntentRules.isAcceptablePdf(null, { "application/octet-stream" }, { "report.pdf " }))
        assertFalse(PdfIntentRules.isAcceptablePdf("application/octet-stream", { null }, { "archive.zip" }))
        assertFalse(PdfIntentRules.isAcceptablePdf(null, { null }, { null }))
        assertFalse(PdfIntentRules.isAcceptablePdf(null, { null }, { "pdf" }))
    }

    @Test
    fun `resolved specific non-pdf type rejects even with pdf name`() {
        assertFalse(PdfIntentRules.isAcceptablePdf(null, { "image/png" }, { "fake.pdf" }))
    }

    @Test
    fun `pdf extension check`() {
        assertTrue(PdfIntentRules.hasPdfExtension("a.pdf"))
        assertTrue(PdfIntentRules.hasPdfExtension("A.Pdf"))
        assertFalse(PdfIntentRules.hasPdfExtension("a.pdf.exe"))
        assertFalse(PdfIntentRules.hasPdfExtension("apdf"))
        assertFalse(PdfIntentRules.hasPdfExtension(null))
    }

    @Test
    fun `private directory detection`() {
        val dirs = listOf("/data/user/0/io.github.tffy1.pdfviewer", "/data/user_de/0/io.github.tffy1.pdfviewer/")
        assertTrue(PdfIntentRules.isInsideAnyDirectory("/data/user/0/io.github.tffy1.pdfviewer", dirs))
        assertTrue(
            PdfIntentRules.isInsideAnyDirectory("/data/user/0/io.github.tffy1.pdfviewer/databases/app.db", dirs),
        )
        assertTrue(PdfIntentRules.isInsideAnyDirectory("/data/user_de/0/io.github.tffy1.pdfviewer/x.pdf", dirs))
        // Sibling package sharing a prefix is not inside.
        assertFalse(PdfIntentRules.isInsideAnyDirectory("/data/user/0/io.github.tffy1.pdfviewer.debug/a.pdf", dirs))
        assertFalse(PdfIntentRules.isInsideAnyDirectory("/storage/emulated/0/Download/a.pdf", dirs))
    }

    @Test
    fun `root or empty directory entries never match everything`() {
        assertFalse(PdfIntentRules.isInsideAnyDirectory("/storage/a.pdf", listOf("/", "")))
    }
}
