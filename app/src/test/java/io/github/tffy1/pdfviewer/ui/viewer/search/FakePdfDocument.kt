package io.github.tffy1.pdfviewer.ui.viewer.search

import android.graphics.Bitmap
import android.net.Uri
import io.github.tffy1.pdfviewer.core.model.PageSize
import io.github.tffy1.pdfviewer.pdf.DocumentMetadata
import io.github.tffy1.pdfviewer.pdf.PageText
import io.github.tffy1.pdfviewer.pdf.PdfDocument
import io.github.tffy1.pdfviewer.pdf.PdfLink
import io.github.tffy1.pdfviewer.pdf.SearchMatch
import io.github.tffy1.pdfviewer.pdf.TocEntry

/** Minimal in-memory [PdfDocument] for controller tests; override what a test needs. */
internal open class FakePdfDocument(final override val pageCount: Int) : PdfDocument {
    override val uri: Uri get() = throw UnsupportedOperationException()
    override val pageSizes: List<PageSize> = List(pageCount) { PageSize(612f, 792f) }

    override suspend fun metadata(): DocumentMetadata =
        DocumentMetadata(null, null, null, null, null, null, null, null)

    override suspend fun tableOfContents(): List<TocEntry> = emptyList()

    override suspend fun renderPage(
        pageIndex: Int,
        bitmap: Bitmap,
        scaledPageWidth: Int,
        scaledPageHeight: Int,
        offsetX: Int,
        offsetY: Int,
        renderAnnotations: Boolean,
    ) {
        throw UnsupportedOperationException()
    }

    override suspend fun renderThumbnail(pageIndex: Int, widthPx: Int): Bitmap = throw UnsupportedOperationException()

    override suspend fun pageText(pageIndex: Int): PageText = PageText(pageIndex, "", emptyList())

    override suspend fun searchPage(
        pageIndex: Int,
        query: String,
        matchCase: Boolean,
        wholeWord: Boolean,
    ): List<SearchMatch> = emptyList()

    override suspend fun links(pageIndex: Int): List<PdfLink> = emptyList()

    override fun close() = Unit
}
