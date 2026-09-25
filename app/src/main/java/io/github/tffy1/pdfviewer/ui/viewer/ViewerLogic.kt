package io.github.tffy1.pdfviewer.ui.viewer

import io.github.tffy1.pdfviewer.pdf.PdfOpenException
import java.io.FileNotFoundException
import kotlin.math.roundToInt

/*
 * Pure viewer logic (no Android framework calls) so it can be covered by plain JUnit tests.
 */

/** Why a document could not be opened; drives the icon, message and actions of the error screen. */
enum class ViewerErrorKind { NOT_FOUND, NO_PERMISSION, CORRUPT, OTHER }

/** Clamps [page] to a valid 0-based index of a document with [pageCount] pages (0 when empty). */
fun clampPage(page: Int, pageCount: Int): Int =
    if (pageCount <= 0) 0 else page.coerceIn(0, pageCount - 1)

/**
 * Decides which page the viewer opens on.
 *
 * Priority: the page restored after process death ([savedPage]) > an explicit page from the
 * route ([routePage] >= 0) > the remembered last page (only when [rememberLastPage]) > 0.
 * The result is always clamped to the document.
 */
fun resolveInitialPage(
    savedPage: Int?,
    routePage: Int,
    rememberLastPage: Boolean,
    lastPage: Int?,
    pageCount: Int,
): Int {
    val target = when {
        savedPage != null && savedPage >= 0 -> savedPage
        routePage >= 0 -> routePage
        rememberLastPage && lastPage != null -> lastPage
        else -> 0
    }
    return clampPage(target, pageCount)
}

/** Result of validating the text typed into the "Go to page" dialog. */
sealed interface PageInput {
    data object Empty : PageInput
    data object Invalid : PageInput
    data object OutOfRange : PageInput

    /** [pageIndex] is 0-based. */
    data class Valid(val pageIndex: Int) : PageInput
}

/**
 * Parses a 1-based page number typed by the user. Accepts any Unicode decimal digits
 * (e.g. Arabic-Indic digits from localized keyboards) and surrounding whitespace.
 */
fun parsePageInput(text: String, pageCount: Int): PageInput {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return PageInput.Empty
    if (!trimmed.all { it.isDigit() }) return PageInput.Invalid
    // Digits only, so a null result means the number overflowed Int: definitely out of range.
    val number = trimmed.toIntOrNull() ?: return PageInput.OutOfRange
    return if (number in 1..pageCount) PageInput.Valid(number - 1) else PageInput.OutOfRange
}

/** Maps a scrubber position (0..pageCount-1) to a page index. */
fun sliderValueToPage(value: Float, pageCount: Int): Int =
    if (value.isNaN()) 0 else clampPage(value.roundToInt(), pageCount)

/**
 * Classifies a failure to open a document by walking its cause chain. Permission problems win
 * over "not found" because revoked grants sometimes surface as both.
 */
fun classifyOpenError(error: Throwable): ViewerErrorKind {
    val chain = generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }
        .take(MAX_CAUSE_DEPTH)
        .toList()
    return when {
        chain.any { it is SecurityException } -> ViewerErrorKind.NO_PERMISSION
        chain.any { it is FileNotFoundException } -> ViewerErrorKind.NOT_FOUND
        chain.any { it is PdfOpenException } -> ViewerErrorKind.CORRUPT
        else -> ViewerErrorKind.OTHER
    }
}

private const val MAX_CAUSE_DEPTH = 16

/**
 * File name suggested when saving a copy: the original name with a ".pdf" extension, optionally
 * tagged with [suffix] before the extension ("report.pdf" + "annotated" -> "report (annotated).pdf").
 */
fun suggestedCopyFileName(displayName: String, suffix: String? = null): String {
    val base = displayName.trim()
        .let { if (it.endsWith(PDF_EXTENSION, ignoreCase = true)) it.dropLast(PDF_EXTENSION.length) else it }
        .trim()
        .ifEmpty { DEFAULT_BASE_NAME }
    return if (suffix.isNullOrBlank()) "$base$PDF_EXTENSION" else "$base (${suffix.trim()})$PDF_EXTENSION"
}

private const val PDF_EXTENSION = ".pdf"
private const val DEFAULT_BASE_NAME = "document"
