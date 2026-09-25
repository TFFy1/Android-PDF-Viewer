package io.github.tffy1.pdfviewer.ui.viewer.document

import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.tffy1.pdfviewer.pdf.PdfDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One render request in the engine's window semantics: the page is scaled to
 * [scaledPageWidth] x [scaledPageHeight] px and the [width] x [height] window at
 * ([offsetX], [offsetY]) is rendered. A base render is the whole scaled page.
 */
internal data class RenderKey(
    val pageIndex: Int,
    val scaledPageWidth: Int,
    val scaledPageHeight: Int,
    val offsetX: Int,
    val offsetY: Int,
    val width: Int,
    val height: Int,
) {
    companion object {
        fun base(pageIndex: Int, size: PixelSize) =
            RenderKey(pageIndex, size.width, size.height, 0, 0, size.width, size.height)

        fun tile(pageIndex: Int, scaledPageWidth: Int, scaledPageHeight: Int, rect: TileRect) =
            RenderKey(pageIndex, scaledPageWidth, scaledPageHeight, rect.left, rect.top, rect.width, rect.height)
    }
}

/**
 * Renders page bitmaps for one document through a priority queue (at most one render in the
 * engine at a time) and keeps them in an LRU memory cache sized from the heap.
 *
 * Evicted bitmaps are never recycled: Compose may still be drawing them, and the GC frees them
 * once no page references them.
 */
internal class PageRenderer(private val document: PdfDocument) {
    private val cache = object : LruCache<RenderKey, ImageBitmap>(cacheSizeBytes()) {
        override fun sizeOf(key: RenderKey, value: ImageBitmap): Int = value.width * value.height * 4
    }
    private val queue = PriorityLock()

    fun cached(key: RenderKey): ImageBitmap? = cache.get(key)

    /**
     * Returns the rendered bitmap, from the cache when possible. [priority] is evaluated when the
     * render queue picks its next job (lower first). Returns null when rendering failed.
     */
    suspend fun render(key: RenderKey, priority: () -> Int): ImageBitmap? {
        cache.get(key)?.let { return it }
        return queue.withLock(priority) {
            // Another page item may have rendered the same key while we waited.
            cache.get(key) ?: renderUncached(key)
        }
    }

    fun clear() {
        cache.evictAll()
    }

    private suspend fun renderUncached(key: RenderKey): ImageBitmap? {
        val bitmap = try {
            withContext(Dispatchers.Default) {
                Bitmap.createBitmap(key.width, key.height, Bitmap.Config.ARGB_8888)
            }
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Out of memory allocating ${key.width}x${key.height} for page ${key.pageIndex}", e)
            cache.evictAll()
            return null
        }
        try {
            document.renderPage(
                pageIndex = key.pageIndex,
                bitmap = bitmap,
                scaledPageWidth = key.scaledPageWidth,
                scaledPageHeight = key.scaledPageHeight,
                offsetX = key.offsetX,
                offsetY = key.offsetY,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Rendering page ${key.pageIndex} failed", e)
            return null
        }
        bitmap.prepareToDraw()
        return bitmap.asImageBitmap().also { cache.put(key, it) }
    }

    private companion object {
        const val TAG = "PageRenderer"

        fun cacheSizeBytes(): Int =
            (Runtime.getRuntime().maxMemory() / 6).coerceIn(16L * 1024 * 1024, Int.MAX_VALUE.toLong()).toInt()
    }
}
