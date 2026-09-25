package io.github.tffy1.pdfviewer.pdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/*
 * CONTRACT (scaffold). Owner: PDF-engine agent. Used by the library for document covers.
 * Disk cache of first-page thumbnails in app storage (never in shared storage).
 */
class ThumbnailStore(
    private val context: Context,
    private val engine: PdfEngine,
) {
    /**
     * Striped locks: the same thumbnail is never generated twice concurrently, while
     * different documents can still be processed in parallel. Bounded, unlike a lock per key.
     */
    private val locks = Array(LOCK_STRIPES) { Mutex() }

    private val directory: File get() = File(context.filesDir, DIRECTORY_NAME)

    /** Returns a PNG of page 1, generating it on first use; null if the document can't be opened. */
    suspend fun thumbnailFor(uri: Uri, widthPx: Int = 256): File? {
        val key = cacheKey(uri)
        val width = widthPx.coerceIn(MIN_WIDTH_PX, MAX_WIDTH_PX)
        return lockFor(key).withLock {
            val (file, cached) = withContext(Dispatchers.IO) {
                val file = File(directory, "$key-$width.png")
                file to (file.isFile && file.length() > 0)
            }
            if (cached) file else generate(uri, width, file)
        }
    }

    suspend fun remove(uri: Uri) {
        val key = cacheKey(uri)
        lockFor(key).withLock {
            withContext(Dispatchers.IO) {
                directory.listFiles { file -> file.name.startsWith("$key-") }?.forEach { it.delete() }
            }
        }
    }

    private fun lockFor(key: String): Mutex = locks[(key.hashCode() and Int.MAX_VALUE) % LOCK_STRIPES]

    private suspend fun generate(uri: Uri, widthPx: Int, target: File): File? = try {
        val document = engine.open(uri)
        try {
            val bitmap = document.renderThumbnail(0, widthPx)
            try {
                withContext(Dispatchers.IO) { writePngAtomically(bitmap, target) }
            } finally {
                bitmap.recycle()
            }
        } finally {
            document.close()
        }
        target
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Password-protected, damaged, missing or unreadable: the library shows a placeholder.
        null
    }

    private fun writePngAtomically(bitmap: Bitmap, target: File) {
        val dir = target.parentFile ?: throw IOException("No parent directory for $target")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
        val temp = File(dir, "${target.name}.tmp")
        try {
            temp.outputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)) {
                    throw IOException("PNG encoding failed")
                }
            }
            if (!temp.renameTo(target)) throw IOException("Cannot move thumbnail into place")
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private companion object {
        const val DIRECTORY_NAME = "thumbnails"
        const val LOCK_STRIPES = 16
        const val MIN_WIDTH_PX = 16
        const val MAX_WIDTH_PX = 2048

        /** Ignored by PNG (lossless) but required by the API. */
        const val PNG_QUALITY = 100

        const val HEX_DIGITS = "0123456789abcdef"

        fun cacheKey(uri: Uri): String =
            MessageDigest.getInstance("SHA-1")
                .digest(uri.toString().toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { byte ->
                    val value = byte.toInt() and 0xff
                    "${HEX_DIGITS[value shr 4]}${HEX_DIGITS[value and 0x0f]}"
                }
    }
}
