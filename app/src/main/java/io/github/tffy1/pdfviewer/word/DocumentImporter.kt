package io.github.tffy1.pdfviewer.word

import android.content.Context
import android.net.Uri
import io.github.tffy1.pdfviewer.integration.PdfIntentRules
import io.github.tffy1.pdfviewer.io.DocumentAccess
import io.github.tffy1.pdfviewer.io.FileNameSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Turns whatever the user opened into something the PDF viewer can show. PDFs pass through
 * untouched; Word (.docx) files are converted on the device into a PDF kept in the app's private
 * "converted" folder and returned as a FileProvider URI. Nothing ever leaves the device.
 */
class DocumentImporter(
    private val context: Context,
    private val documentAccess: DocumentAccess,
) {
    /** @throws IOException when a Word file can't be read or converted. */
    @Throws(IOException::class)
    suspend fun prepareForViewing(uri: Uri): Uri {
        if (documentAccess.isOwnFileProviderUri(uri)) return uri
        val info = documentAccess.queryInfo(uri)
        val isWord = PdfIntentRules.isAcceptableDocx(
            declaredMime = null,
            resolvedMime = { info.mimeType },
            displayName = { info.displayName },
        ) && !PdfIntentRules.isAcceptablePdf(null, { info.mimeType }, { info.displayName })
        if (!isWord) return uri
        return convertDocx(uri, info.displayName)
    }

    private suspend fun convertDocx(uri: Uri, displayName: String): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, CONVERTED_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
        deleteStale(dir)

        val base = FileNameSanitizer.sanitize(displayName.removeSuffix(".docx").removeSuffix(".DOCX"))
        val target = File(dir, "$base.pdf")
        val temp = File(dir, ".$base.${UUID.randomUUID()}.tmp")
        try {
            val document = documentAccess.openInputStream(uri).use { DocxParser.parse(it) }
            temp.outputStream().buffered().use { DocxPdfRenderer().render(document, it) }
            if (target.exists() && !target.delete()) throw IOException("Cannot replace $target")
            if (!temp.renameTo(target)) throw IOException("Cannot rename to $target")
        } finally {
            if (temp.exists()) temp.delete()
        }
        documentAccess.shareableUri(target)
    }

    private fun deleteStale(dir: File) {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(STALE_AFTER_DAYS)
        dir.listFiles()?.forEach { if (it.isFile && it.lastModified() < cutoff) it.delete() }
    }

    companion object {
        /** Sub-directory of filesDir exposed by the FileProvider as "converted". */
        const val CONVERTED_DIR = "converted"
        private const val STALE_AFTER_DAYS = 30L
    }
}
