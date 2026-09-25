package io.github.tffy1.pdfviewer.ui.viewer.sheets

import android.graphics.Bitmap
import android.util.LruCache
import io.github.tffy1.pdfviewer.pdf.PdfDocument
import kotlinx.coroutines.CancellationException

/**
 * Small in-memory LRU of page thumbnails for the "Pages" grid, bounded by bitmap bytes.
 * Lives as long as the open document (held by the viewer's ViewModel), so reopening the
 * sheet is instant. Bitmaps are never recycled manually: Compose may still be drawing them.
 */
class PageThumbnailCache(
    private val document: PdfDocument,
    private val widthPx: Int = THUMBNAIL_WIDTH_PX,
    maxBytes: Int = DEFAULT_MAX_BYTES,
) {
    private val cache = object : LruCache<Int, Bitmap>(maxBytes) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.allocationByteCount
    }

    /** Cached thumbnail of [pageIndex], without rendering. */
    fun peek(pageIndex: Int): Bitmap? = cache.get(pageIndex)

    /** Returns the thumbnail of [pageIndex], rendering it off the main thread if needed; null on failure. */
    suspend fun load(pageIndex: Int): Bitmap? {
        cache.get(pageIndex)?.let { return it }
        val bitmap = try {
            document.renderThumbnail(pageIndex, widthPx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        cache.put(pageIndex, bitmap)
        return bitmap
    }

    companion object {
        const val THUMBNAIL_WIDTH_PX = 180
        private const val DEFAULT_MAX_BYTES = 12 * 1024 * 1024
    }
}
