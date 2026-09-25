package io.github.tffy1.pdfviewer.integration

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import io.github.tffy1.pdfviewer.R
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.thread

/**
 * Feeds an existing PDF to the Android print framework. The file already is a PDF, so layout is
 * trivial and writing is a plain byte copy (encrypted PDFs are passed through as they are).
 *
 * The system calls this adapter on the main thread; the copy runs on a worker thread and honours
 * the [CancellationSignal]. WriteResultCallback may be invoked from any thread.
 */
internal class PdfPrintAdapter(
    context: Context,
    private val uri: Uri,
    private val documentName: String,
) : PrintDocumentAdapter() {
    private val appContext: Context = context.applicationContext

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal?,
        callback: PrintDocumentAdapter.LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(documentName)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
            .build()
        // Our output does not depend on the attributes, so only the first layout is a change.
        callback.onLayoutFinished(info, oldAttributes == null)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: PrintDocumentAdapter.WriteResultCallback,
    ) {
        thread(name = "PdfPrintWriter") {
            try {
                val completed = appContext.contentResolver.openInputStream(uri)?.use { input ->
                    // Do not close the destination descriptor: the print framework owns it.
                    val output = FileOutputStream(destination.fileDescriptor)
                    copy(input, output, cancellationSignal)
                } ?: throw FileNotFoundException("Unable to open $uri")

                if (completed) {
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } else {
                    callback.onWriteCancelled()
                }
            } catch (e: Exception) {
                // FileNotFound / Security (permission lost) / IO errors all end up here.
                callback.onWriteFailed(appContext.getString(R.string.integration_error_print))
            }
        }
    }

    /** Returns false when cancelled part-way. */
    private fun copy(input: InputStream, output: OutputStream, signal: CancellationSignal?): Boolean {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        while (true) {
            if (signal?.isCanceled == true) return false
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
        }
        output.flush()
        return signal?.isCanceled != true
    }

    private companion object {
        const val COPY_BUFFER_SIZE = 64 * 1024
    }
}
