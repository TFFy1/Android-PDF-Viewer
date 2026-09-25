package io.github.tffy1.pdfviewer.pdf

import android.graphics.Bitmap
import android.net.Uri
import io.github.tffy1.pdfviewer.core.model.PageRect
import io.github.tffy1.pdfviewer.core.model.PageSize
import java.io.Closeable

/**
 * CONTRACT (owned by the scaffold; implementation lives in pdf/pdfium/, see docs/ARCHITECTURE.md).
 *
 * Read-only access to PDF documents. Implementations must be safe to call from any
 * coroutine: they serialize native access internally and never block the main thread.
 * All geometry is in page space (see core/model/Geometry.kt).
 */
interface PdfEngine {
    /**
     * Opens [uri] (content:// or file://).
     * @throws PdfPasswordException when a password is required or [password] is wrong.
     * @throws PdfOpenException for unreadable / corrupt files or missing permission.
     */
    suspend fun open(uri: Uri, password: String? = null): PdfDocument
}

interface PdfDocument : Closeable {
    val uri: Uri
    val pageCount: Int

    /** Size of each page in points, rotation already applied. Size == pageCount. */
    val pageSizes: List<PageSize>

    suspend fun metadata(): DocumentMetadata

    /** Document outline. Empty when the PDF has none. */
    suspend fun tableOfContents(): List<TocEntry>

    /**
     * Renders part of a page into [bitmap] (ARGB_8888, reused by callers).
     *
     * Conceptually the whole page is scaled to [scaledPageWidth] x [scaledPageHeight] pixels,
     * and the window of that scaled page starting at ([offsetX], [offsetY]) with the bitmap's
     * size is drawn into [bitmap]. Passing offset 0/0 and a bitmap the size of the scaled page
     * renders the full page. The bitmap is filled white first. Annotations/form widgets that
     * exist inside the PDF are drawn when [renderAnnotations] is true.
     */
    suspend fun renderPage(
        pageIndex: Int,
        bitmap: Bitmap,
        scaledPageWidth: Int,
        scaledPageHeight: Int,
        offsetX: Int = 0,
        offsetY: Int = 0,
        renderAnnotations: Boolean = true,
    )

    /** Convenience: full page render whose width is [widthPx]. */
    suspend fun renderThumbnail(pageIndex: Int, widthPx: Int): Bitmap

    /** Text of a page with one bounding box per char (same indexing as [PageText.text]). */
    suspend fun pageText(pageIndex: Int): PageText

    /** Case-insensitive unless [matchCase]. Returns matches in reading order. */
    suspend fun searchPage(
        pageIndex: Int,
        query: String,
        matchCase: Boolean = false,
        wholeWord: Boolean = false,
    ): List<SearchMatch>

    /** Link annotations on a page. */
    suspend fun links(pageIndex: Int): List<PdfLink>
}

data class DocumentMetadata(
    val title: String?,
    val author: String?,
    val subject: String?,
    val keywords: String?,
    val creator: String?,
    val producer: String?,
    val creationDate: String?,
    val modificationDate: String?,
)

data class TocEntry(
    val title: String,
    /** Target page, or -1 when the entry has no destination. */
    val pageIndex: Int,
    val children: List<TocEntry> = emptyList(),
)

/**
 * [charBoxes] has exactly text.length entries; a char with no geometry
 * (e.g. generated line breaks) gets a zero-size rect at the previous char's position.
 */
data class PageText(
    val pageIndex: Int,
    val text: String,
    val charBoxes: List<PageRect>,
)

data class SearchMatch(
    val pageIndex: Int,
    /** Char index into [PageText.text]. */
    val startIndex: Int,
    val length: Int,
    /** One rect per text line the match spans. */
    val rects: List<PageRect>,
)

sealed interface PdfLink {
    val bounds: PageRect

    data class Internal(override val bounds: PageRect, val targetPageIndex: Int) : PdfLink
    data class External(override val bounds: PageRect, val uri: String) : PdfLink
}

class PdfPasswordException(
    /** true when a password was supplied but it was wrong. */
    val wrongPassword: Boolean,
    cause: Throwable? = null,
) : Exception(if (wrongPassword) "Incorrect password" else "Password required", cause)

class PdfOpenException(message: String, cause: Throwable? = null) : Exception(message, cause)
