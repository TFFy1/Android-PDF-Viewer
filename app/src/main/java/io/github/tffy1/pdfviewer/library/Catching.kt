package io.github.tffy1.pdfviewer.library

import kotlin.coroutines.cancellation.CancellationException

/**
 * Like [runCatching], but never swallows coroutine cancellation. Library work talks to content
 * providers, the database and the PDF engine, any of which may fail on a bad file or a revoked
 * permission; those failures must degrade gracefully instead of crashing the screen.
 */
inline fun <T> catchingNonCancellation(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(t)
    }
