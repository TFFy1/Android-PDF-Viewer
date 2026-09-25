package io.github.tffy1.pdfviewer.ui.library

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.tffy1.pdfviewer.library.catchingNonCancellation
import io.github.tffy1.pdfviewer.pdf.ThumbnailStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads document covers for the library lists: a PNG from [ThumbnailStore] (generated on first
 * use) decoded off the main thread, kept in a small in-memory LRU of decoded bitmaps.
 */
class CoverLoader(
    private val thumbnailStore: ThumbnailStore,
    /** Called when a cover file was produced for [uri], so it can be remembered in the database. */
    private val onCoverFileResolved: suspend (uri: String, path: String) -> Unit,
) {
    private val cache = object : LruCache<String, ImageBitmap>(cacheSizeBytes()) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.asAndroidBitmap().byteCount
    }

    /** Documents that couldn't produce a cover (unreadable, permission lost); not retried this session. */
    private val unavailable: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Rendering a first page opens the whole document, so only a few run at once. */
    private val permits = Semaphore(MAX_CONCURRENT_LOADS)

    fun cached(uri: String): ImageBitmap? = cache.get(uri)

    fun evict(uri: String) {
        cache.remove(uri)
        unavailable.remove(uri)
    }

    /** Returns the cover for [uri], or null when none can be produced. [knownPath] is a cached PNG, if any. */
    suspend fun load(uri: String, knownPath: String?): ImageBitmap? {
        cache.get(uri)?.let { return it }
        return permits.withPermit {
            cache.get(uri) ?: withContext(Dispatchers.IO) { loadUncached(uri, knownPath) }
        }
    }

    private suspend fun loadUncached(uri: String, knownPath: String?): ImageBitmap? {
        val knownFile = knownPath?.let(::File)?.takeIf { it.isFile }
        val file = knownFile
            ?: if (uri in unavailable) {
                null
            } else {
                catchingNonCancellation { thumbnailStore.thumbnailFor(Uri.parse(uri), COVER_WIDTH_PX) }.getOrNull()
            }
        val bitmap = file
            ?.let { catchingNonCancellation { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
            ?.asImageBitmap()
        if (file == null || bitmap == null) {
            unavailable += uri
            return null
        }
        cache.put(uri, bitmap)
        if (file.absolutePath != knownPath) {
            catchingNonCancellation { onCoverFileResolved(uri, file.absolutePath) }
        }
        return bitmap
    }

    private companion object {
        /** Matches ThumbnailStore's default width so every screen shares the same cached PNG. */
        const val COVER_WIDTH_PX = 256
        const val MAX_CONCURRENT_LOADS = 2
        const val MAX_CACHE_BYTES = 16L * 1024 * 1024

        fun cacheSizeBytes(): Int =
            (Runtime.getRuntime().maxMemory() / 16).coerceAtMost(MAX_CACHE_BYTES).toInt()
    }
}
