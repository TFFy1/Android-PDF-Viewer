package io.github.tffy1.pdfviewer.pdf.pdfium

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.github.tffy1.pdfviewer.core.model.PageSize
import io.github.tffy1.pdfviewer.io.DocumentAccess
import io.github.tffy1.pdfviewer.pdf.PdfDocument
import io.github.tffy1.pdfviewer.pdf.PdfEngine
import io.github.tffy1.pdfviewer.pdf.PdfOpenException
import io.github.tffy1.pdfviewer.pdf.PdfPasswordException
import io.legere.pdfiumandroid.PdfiumCore
import io.legere.pdfiumandroid.api.Config
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import io.legere.pdfiumandroid.PdfDocument as NativeDocument
import io.legere.pdfiumandroid.api.PdfPasswordException as NativePasswordException

/**
 * [PdfEngine] backed by Pdfium (io.legere:pdfiumandroid).
 *
 * Threading: Pdfium is not thread-safe, so every native call made by this engine and the
 * documents it opens runs on [pdfiumDispatcher], a single-threaded view of Dispatchers.IO.
 * The library additionally wraps each call in its own global re-entrant lock; we never hold
 * that lock across a suspension, so the two cannot deadlock.
 */
class PdfiumEngine(
    private val context: Context,
    private val documentAccess: DocumentAccess,
) : PdfEngine {
    private val pdfiumDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** Runs deferred native clean-up (document close) on [pdfiumDispatcher]. */
    private val cleanupScope = CoroutineScope(SupervisorJob() + pdfiumDispatcher)

    /** Created lazily on [pdfiumDispatcher]: the constructor blocks while native libs load. */
    private var core: PdfiumCore? = null

    override suspend fun open(uri: Uri, password: String?): PdfDocument {
        var descriptor: ParcelFileDescriptor? = null
        var document: PdfiumDocument? = null
        try {
            // Opening the descriptor may be slow (cloud providers download the file first), so
            // it happens on the shared IO pool and does not hold up rendering of other documents.
            withContext(Dispatchers.IO) { descriptor = openSeekableDescriptor(uri) }
            return withContext(pdfiumDispatcher) {
                val native = openNative(checkNotNull(descriptor), password)
                descriptor = null // Closing the native document closes the descriptor now.
                createDocument(uri, native).also { document = it }
            }
        } catch (t: Throwable) {
            // Also covers cancellation after a step completed but before its result was used.
            document?.close()
            descriptor?.closeQuietly()
            throw t
        }
    }

    private fun openNative(descriptor: ParcelFileDescriptor, password: String?): NativeDocument =
        try {
            requireCore().newDocument(descriptor, password)
        } catch (e: NativePasswordException) {
            throw PdfPasswordException(wrongPassword = password != null, cause = e)
        } catch (e: IOException) {
            throw PdfOpenException("Not a valid PDF file or the file is damaged (${e.message})", e)
        } catch (e: RuntimeException) {
            throw PdfOpenException("Unable to open PDF: ${e.message}", e)
        }

    private fun createDocument(uri: Uri, native: NativeDocument): PdfiumDocument =
        try {
            val pageCount = native.getPageCount()
            if (pageCount <= 0) throw PdfOpenException("The PDF has no pages")
            PdfiumDocument(
                uri = uri,
                document = native,
                pageSizes = readPageSizes(native, pageCount),
                pdfiumDispatcher = pdfiumDispatcher,
                cleanupScope = cleanupScope,
            )
        } catch (e: PdfOpenException) {
            runCatching { native.close() }
            throw e
        } catch (e: RuntimeException) {
            runCatching { native.close() }
            throw PdfOpenException("Unable to read PDF pages: ${e.message}", e)
        }

    private fun requireCore(): PdfiumCore =
        core ?: PdfiumCore(context, Config(pageRetentionCount = 0)).also { core = it }

    /**
     * Opens a read-only descriptor Pdfium can seek in. Pipes and sockets (some share/"open with"
     * providers stream data) are spooled into a private temp file first.
     */
    private fun openSeekableDescriptor(uri: Uri): ParcelFileDescriptor {
        val descriptor = try {
            documentAccess.openFileDescriptor(uri)
        } catch (e: FileNotFoundException) {
            throw PdfOpenException("The file was not found or is no longer available", e)
        } catch (e: SecurityException) {
            throw PdfOpenException("Permission to read the file was denied", e)
        } catch (e: IllegalArgumentException) {
            throw PdfOpenException("Unsupported document location: $uri", e)
        } catch (e: IllegalStateException) {
            throw PdfOpenException("The document provider failed to open the file", e)
        }
        if (descriptor.statSize >= 0) return descriptor
        return try {
            spoolToTempFile(descriptor)
        } catch (e: IOException) {
            throw PdfOpenException("Unable to read the file", e)
        }
    }

    private fun spoolToTempFile(stream: ParcelFileDescriptor): ParcelFileDescriptor {
        val dir = File(context.cacheDir, SPOOL_DIR).apply { mkdirs() }
        val temp = File.createTempFile("open", ".pdf", dir)
        try {
            ParcelFileDescriptor.AutoCloseInputStream(stream).use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            return ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
        } finally {
            // The open descriptor keeps the data readable; the name is no longer needed.
            temp.delete()
        }
    }

    private companion object {
        const val SPOOL_DIR = "pdf-spool"

        /** getPageSizes() truncates to Int at this dpi: 7200 dpi = 1/100 point precision. */
        const val SIZE_QUERY_DPI = 7200
        const val SIZE_QUERY_POINTS_PER_UNIT = 72f / SIZE_QUERY_DPI

        /** US Letter, used for pages whose size Pdfium cannot determine. */
        val FALLBACK_PAGE_SIZE = PageSize(612f, 792f)

        /**
         * Page sizes in points with /Rotate applied, without loading or parsing any page:
         * FPDF_GetPageSizeByIndex only reads the page dictionary (crop ∩ media box, rotation).
         */
        fun readPageSizes(document: NativeDocument, pageCount: Int): List<PageSize> {
            val sizes = document.getPageSizes(SIZE_QUERY_DPI)
            return List(pageCount) { index ->
                val size = sizes.getOrNull(index)
                if (size == null || size.width <= 0 || size.height <= 0) {
                    FALLBACK_PAGE_SIZE
                } else {
                    PageSize(
                        width = size.width * SIZE_QUERY_POINTS_PER_UNIT,
                        height = size.height * SIZE_QUERY_POINTS_PER_UNIT,
                    )
                }
            }
        }

        fun ParcelFileDescriptor.closeQuietly() {
            try {
                close()
            } catch (_: IOException) {
                // Nothing useful to do.
            }
        }
    }
}
