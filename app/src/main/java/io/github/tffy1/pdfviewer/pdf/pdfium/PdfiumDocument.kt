package io.github.tffy1.pdfviewer.pdf.pdfium

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import io.github.tffy1.pdfviewer.core.model.PageRect
import io.github.tffy1.pdfviewer.core.model.PageSize
import io.github.tffy1.pdfviewer.pdf.DocumentClosedException
import io.github.tffy1.pdfviewer.pdf.DocumentMetadata
import io.github.tffy1.pdfviewer.pdf.PageText
import io.github.tffy1.pdfviewer.pdf.PageTransform
import io.github.tffy1.pdfviewer.pdf.PdfDocument
import io.github.tffy1.pdfviewer.pdf.PdfLink
import io.github.tffy1.pdfviewer.pdf.SearchMatch
import io.github.tffy1.pdfviewer.pdf.TocEntry
import io.github.tffy1.pdfviewer.pdf.findTextMatches
import io.github.tffy1.pdfviewer.pdf.mergeCharBoxesIntoLines
import io.legere.pdfiumandroid.PdfTextPage
import io.legere.pdfiumandroid.api.Bookmark
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import io.legere.pdfiumandroid.PdfDocument as NativeDocument
import io.legere.pdfiumandroid.PdfPage as NativePage

/**
 * A document opened by [PdfiumEngine].
 *
 * Everything that touches native handles runs on [pdfiumDispatcher] (single-threaded), so the
 * page-handle cache and other native state below need no further locking. Only [close] and
 * the text cache are touched from other threads.
 */
internal class PdfiumDocument(
    override val uri: Uri,
    private val document: NativeDocument,
    override val pageSizes: List<PageSize>,
    private val pdfiumDispatcher: CoroutineDispatcher,
    private val cleanupScope: CoroutineScope,
) : PdfDocument {
    override val pageCount: Int get() = pageSizes.size

    private val closed = AtomicBoolean(false)

    /** Open page handles, least recently used first. Confined to [pdfiumDispatcher]. */
    private val openPages = LinkedHashMap<Int, PageHandles>(MAX_OPEN_PAGES + 1, 0.75f, true)

    /** Extracted page text, least recently used first. Guarded by its own monitor. */
    private val textCache = LinkedHashMap<Int, PageText>(MAX_CACHED_TEXTS + 1, 0.75f, true)

    @Volatile
    private var toc: List<TocEntry>? = null

    override suspend fun metadata(): DocumentMetadata = onPdfium {
        val meta = document.getDocumentMeta()
        DocumentMetadata(
            title = meta.title.cleanMeta(),
            author = meta.author.cleanMeta(),
            subject = meta.subject.cleanMeta(),
            keywords = meta.keywords.cleanMeta(),
            creator = meta.creator.cleanMeta(),
            producer = meta.producer.cleanMeta(),
            creationDate = meta.creationDate.cleanMeta(),
            modificationDate = meta.modDate.cleanMeta(),
        )
    }

    override suspend fun tableOfContents(): List<TocEntry> {
        toc?.let { return it }
        return onPdfium {
            toc ?: toTocEntries(document.getTableOfContents(), depth = 1, budget = intArrayOf(MAX_TOC_ENTRIES))
                .also { toc = it }
        }
    }

    override suspend fun renderPage(
        pageIndex: Int,
        bitmap: Bitmap,
        scaledPageWidth: Int,
        scaledPageHeight: Int,
        offsetX: Int,
        offsetY: Int,
        renderAnnotations: Boolean,
    ) {
        checkPageIndex(pageIndex)
        require(scaledPageWidth > 0 && scaledPageHeight > 0) {
            "Invalid scaled page size ${scaledPageWidth}x$scaledPageHeight"
        }
        onPdfium {
            renderInto(pageIndex, bitmap, scaledPageWidth, scaledPageHeight, offsetX, offsetY, renderAnnotations)
        }
    }

    override suspend fun renderThumbnail(pageIndex: Int, widthPx: Int): Bitmap {
        checkPageIndex(pageIndex)
        require(widthPx > 0) { "Invalid thumbnail width $widthPx" }
        val size = pageSizes[pageIndex]
        val width = widthPx.coerceAtMost(MAX_THUMBNAIL_SIDE)
        val height = (width * size.height / size.width).roundToInt().coerceIn(1, MAX_THUMBNAIL_SIDE)
        return onPdfium {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                renderInto(pageIndex, bitmap, width, height, 0, 0, renderAnnotations = true)
            } catch (e: RuntimeException) {
                bitmap.recycle()
                throw e
            }
            bitmap
        }
    }

    override suspend fun pageText(pageIndex: Int): PageText {
        checkPageIndex(pageIndex)
        cachedText(pageIndex)?.let { return it }
        return onPdfium {
            cachedText(pageIndex) ?: extractText(pageIndex).also { text ->
                synchronized(textCache) {
                    textCache[pageIndex] = text
                    textCache.trimTo(MAX_CACHED_TEXTS)
                }
            }
        }
    }

    override suspend fun searchPage(
        pageIndex: Int,
        query: String,
        matchCase: Boolean,
        wholeWord: Boolean,
    ): List<SearchMatch> {
        if (query.isBlank()) return emptyList()
        // Only this page's text extraction runs on the Pdfium thread; matching does not.
        val text = pageText(pageIndex)
        return withContext(Dispatchers.Default) {
            findTextMatches(text.text, query, matchCase, wholeWord).map { range ->
                SearchMatch(
                    pageIndex = pageIndex,
                    startIndex = range.first,
                    length = range.last - range.first + 1,
                    rects = mergeCharBoxesIntoLines(text.charBoxes.subList(range.first, range.last + 1)),
                )
            }
        }
    }

    override suspend fun links(pageIndex: Int): List<PdfLink> {
        checkPageIndex(pageIndex)
        return onPdfium { readLinks(pageIndex) }
    }

    /**
     * Marks the document closed and releases native resources on the Pdfium thread once the
     * call currently running there (if any) finishes. Calls queued behind it, and all later
     * calls, fail fast with [DocumentClosedException]. Never blocks the caller.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(textCache) { textCache.clear() }
        cleanupScope.launch {
            openPages.values.forEach { it.close() }
            openPages.clear()
            try {
                document.close() // also closes the file descriptor
            } catch (_: RuntimeException) {
                // Already closed or native failure: nothing left to release.
            }
        }
    }

    /** Runs [block] on the Pdfium thread unless the document is (or gets) closed first. */
    private suspend inline fun <T> onPdfium(crossinline block: () -> T): T {
        if (closed.get()) throw DocumentClosedException()
        return withContext(pdfiumDispatcher) {
            if (closed.get()) throw DocumentClosedException()
            block()
        }
    }

    private fun checkPageIndex(pageIndex: Int) {
        if (pageIndex !in 0 until pageCount) {
            throw IndexOutOfBoundsException("Page $pageIndex out of range 0 until $pageCount")
        }
    }

    // ---- Everything below runs on pdfiumDispatcher ----

    /** Returns the (cached) handles of a page, or null when Pdfium cannot load it. */
    private fun pageHandles(pageIndex: Int): PageHandles? {
        openPages[pageIndex]?.let { return it }
        val page = try {
            document.openPage(pageIndex)
        } catch (_: RuntimeException) {
            null
        } ?: return null
        val handles = PageHandles(page, pageSizes[pageIndex])
        openPages[pageIndex] = handles
        val eldest = openPages.entries.iterator()
        while (openPages.size > MAX_OPEN_PAGES && eldest.hasNext()) {
            val evicted = eldest.next().value
            eldest.remove()
            evicted.close()
        }
        return handles
    }

    private fun renderInto(
        pageIndex: Int,
        bitmap: Bitmap,
        scaledPageWidth: Int,
        scaledPageHeight: Int,
        offsetX: Int,
        offsetY: Int,
        renderAnnotations: Boolean,
    ) {
        require(!bitmap.isRecycled && bitmap.isMutable) { "Bitmap must be mutable and not recycled" }
        require(bitmap.config == Bitmap.Config.ARGB_8888) { "Bitmap must be ARGB_8888" }
        bitmap.eraseColor(Color.WHITE)
        // An unloadable (damaged) page is left blank rather than failing the whole viewer.
        val page = pageHandles(pageIndex)?.page ?: return
        // Pdfium draws the page scaled to drawSize at (startX, startY) in the bitmap, so a
        // window starting at (offsetX, offsetY) of the scaled page means a negative start.
        page.renderPageBitmap(
            bitmap,
            -offsetX,
            -offsetY,
            scaledPageWidth,
            scaledPageHeight,
            renderAnnot = renderAnnotations,
            textMask = false,
            canvasColor = Color.WHITE,
            pageBackgroundColor = Color.WHITE,
        )
    }

    private fun cachedText(pageIndex: Int): PageText? = synchronized(textCache) { textCache[pageIndex] }

    private fun extractText(pageIndex: Int): PageText {
        val empty = PageText(pageIndex, "", emptyList())
        val handles = pageHandles(pageIndex) ?: return empty
        val textPage = handles.textPage() ?: return empty
        val count = textPage.textPageCountChars().coerceAtMost(MAX_CHARS_PER_PAGE)
        if (count <= 0) return empty

        // One UTF-16 unit per char index is expected; fall back to per-char reads otherwise.
        val bulk = textPage.textPageGetText(0, count)?.takeIf { it.length == count }
        val chars = CharArray(count) { i -> sanitizeChar(bulk?.get(i) ?: textPage.textPageGetUnicode(i)) }

        val transform = handles.transform()
        val boxes = ArrayList<PageRect>(count)
        var previous: PageRect? = null
        for (i in 0 until count) {
            val box = textPage.textPageGetCharBox(i)
            // RectF from Pdfium here: user space, top is the larger y.
            val rect = if (box == null || !box.hasGeometry()) {
                previous?.let { PageRect(it.right, it.bottom, it.right, it.bottom) } ?: ZERO_RECT
            } else {
                transform.mapRect(box.left, box.bottom, box.right, box.top)
            }
            boxes += rect
            previous = rect
        }
        return PageText(pageIndex, String(chars), boxes)
    }

    private fun readLinks(pageIndex: Int): List<PdfLink> {
        val handles = pageHandles(pageIndex) ?: return emptyList()
        val links = try {
            handles.page.getPageLinks()
        } catch (_: RuntimeException) {
            return emptyList()
        }
        val transform = handles.transform()
        return links.mapNotNull { link ->
            val b = link.bounds
            val bounds = transform.mapRect(b.left, b.top, b.right, b.bottom)
            val target = link.destPageIdx
            val uri = link.uri?.trim()
            when {
                bounds.width <= 0f || bounds.height <= 0f -> null
                target != null && target in 0 until pageCount -> PdfLink.Internal(bounds, target)
                !uri.isNullOrEmpty() -> PdfLink.External(bounds, uri)
                else -> null
            }
        }
    }

    private fun toTocEntries(bookmarks: List<Bookmark>, depth: Int, budget: IntArray): List<TocEntry> {
        val entries = ArrayList<TocEntry>(minOf(bookmarks.size, budget[0]))
        for (bookmark in bookmarks) {
            if (budget[0] <= 0) break
            budget[0]--
            val children = if (depth < MAX_TOC_DEPTH && bookmark.children.isNotEmpty()) {
                toTocEntries(bookmark.children, depth + 1, budget)
            } else {
                emptyList()
            }
            val target = bookmark.pageIdx
            entries += TocEntry(
                title = bookmark.title.orEmpty().replace(WHITESPACE_RUN, " ").trim(),
                pageIndex = if (target in 0L until pageCount.toLong()) target.toInt() else -1,
                children = children,
            )
        }
        return entries
    }

    /** Native handles of one page. The text page must be closed before its page. */
    private class PageHandles(val page: NativePage, private val size: PageSize) {
        private var openedTextPage: PdfTextPage? = null
        private var cachedTransform: PageTransform? = null

        fun textPage(): PdfTextPage? = openedTextPage ?: try {
            page.openTextPage().also { openedTextPage = it }
        } catch (_: RuntimeException) {
            null
        }

        /** User space → page space, derived from Pdfium's own page→device mapping. */
        fun transform(): PageTransform = cachedTransform ?: computeTransform().also { cachedTransform = it }

        private fun computeTransform(): PageTransform = try {
            val scale = PageTransform.probeScale(size)
            val (width, height) = PageTransform.probeDeviceSize(size, scale)
            val probe = PageTransform.PROBE_LENGTH
            val origin = page.mapPageCoordsToDevice(0, 0, width, height, 0, 0.0, 0.0)
            val xAxis = page.mapPageCoordsToDevice(0, 0, width, height, 0, probe, 0.0)
            val yAxis = page.mapPageCoordsToDevice(0, 0, width, height, 0, 0.0, probe)
            PageTransform.fromDeviceProbes(origin.x, origin.y, xAxis.x, xAxis.y, yAxis.x, yAxis.y, scale)
        } catch (_: RuntimeException) {
            // Unrotated page with its box at the origin: flip y only.
            PageTransform(1.0, 0.0, 0.0, -1.0, 0.0, size.height.toDouble())
        }

        fun close() {
            try {
                openedTextPage?.close()
            } catch (_: RuntimeException) {
                // Released together with the document anyway.
            }
            openedTextPage = null
            try {
                page.close()
            } catch (_: RuntimeException) {
                // Released together with the document anyway.
            }
        }
    }

    private companion object {
        /** Open Pdfium pages kept for rendering/text; each can hold parsed content. */
        const val MAX_OPEN_PAGES = 10
        const val MAX_CACHED_TEXTS = 12
        const val MAX_CHARS_PER_PAGE = 100_000
        const val MAX_THUMBNAIL_SIDE = 4096
        const val MAX_TOC_DEPTH = 32
        const val MAX_TOC_ENTRIES = 5000

        val ZERO_RECT = PageRect(0f, 0f, 0f, 0f)
        val WHITESPACE_RUN = Regex("\\s+")

        fun android.graphics.RectF.hasGeometry(): Boolean =
            left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
                (left != right || top != bottom)

        /** Pdfium marks hyphens at line ends with U+0002; other controls are not text. */
        fun sanitizeChar(c: Char): Char = when {
            c == '\u0002' -> '-'
            c == '\r' || c == '\n' || c == '\t' -> c
            c < ' ' || c == '￾' || c == '￿' -> ' '
            else -> c
        }

        fun String?.cleanMeta(): String? = this?.replace("\u0000", "")?.trim()?.takeIf { it.isNotEmpty() }

        fun <K, V> LinkedHashMap<K, V>.trimTo(maxSize: Int) {
            val eldest = entries.iterator()
            while (size > maxSize && eldest.hasNext()) {
                eldest.next()
                eldest.remove()
            }
        }
    }
}
