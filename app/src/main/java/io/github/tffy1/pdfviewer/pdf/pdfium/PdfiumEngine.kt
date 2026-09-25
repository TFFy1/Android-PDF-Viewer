package io.github.tffy1.pdfviewer.pdf.pdfium

import android.content.Context
import android.net.Uri
import io.github.tffy1.pdfviewer.io.DocumentAccess
import io.github.tffy1.pdfviewer.pdf.PdfDocument
import io.github.tffy1.pdfviewer.pdf.PdfEngine

/* Owner: PDF-engine agent. Constructor signature is used by AppContainer. */
class PdfiumEngine(
    private val context: Context,
    private val documentAccess: DocumentAccess,
) : PdfEngine {
    override suspend fun open(uri: Uri, password: String?): PdfDocument {
        TODO("pdf engine agent")
    }
}
