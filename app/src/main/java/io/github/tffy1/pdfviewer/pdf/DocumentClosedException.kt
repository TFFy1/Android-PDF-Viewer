package io.github.tffy1.pdfviewer.pdf

import kotlinx.coroutines.CancellationException

/**
 * Thrown by [PdfDocument] calls made (or still queued) after [PdfDocument.close].
 *
 * It is a [CancellationException] on purpose: a render or search that loses the race with
 * closing the document just ends quietly, like a cancelled coroutine, instead of crashing.
 */
class DocumentClosedException : CancellationException("PDF document is closed")
