package io.github.tffy1.pdfviewer.pdf

import android.content.Context
import android.net.Uri
import java.io.File

/*
 * CONTRACT (scaffold). Owner: PDF-engine agent. Used by the library for document covers.
 * Disk cache of first-page thumbnails in app storage (never in shared storage).
 */
class ThumbnailStore(
    private val context: Context,
    private val engine: PdfEngine,
) {
    /** Returns a PNG of page 1, generating it on first use; null if the document can't be opened. */
    suspend fun thumbnailFor(uri: Uri, widthPx: Int = 256): File? = TODO("pdf engine agent")

    suspend fun remove(uri: Uri) { TODO("pdf engine agent") }
}
