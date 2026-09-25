package io.github.tffy1.pdfviewer.tools

/** A 1-based, inclusive range of pages, e.g. `3-7`. */
data class PageRange(val first: Int, val last: Int) {
    init {
        require(first >= 1 && last >= first) { "Invalid page range $first-$last" }
    }

    val size: Int get() = last - first + 1

    /** The same pages as 0-based indices. */
    val indices: IntRange get() = (first - 1) until last

    /** `"5"` for a single page, `"3-7"` otherwise. Used in file names and previews. */
    override fun toString(): String = if (first == last) "$first" else "$first-$last"
}

/** Why a page-range expression was rejected. The UI turns these into messages. */
sealed interface PageRangeError {
    /** Nothing but separators / whitespace was entered. */
    data object Empty : PageRangeError

    /** A part that is neither `N`, `N-M`, `N-` nor `-M`. */
    data class InvalidToken(val token: String) : PageRangeError

    /** A page number that is 0 or larger than the document. [page] is the text as typed. */
    data class PageOutOfRange(val page: String, val pageCount: Int) : PageRangeError

    /** `N-M` with N > M. */
    data class Reversed(val first: Int, val last: Int) : PageRangeError
}

sealed interface PageRangeParseResult {
    data class Valid(val ranges: List<PageRange>) : PageRangeParseResult {
        /** 0-based page indices in the order entered; a page listed twice appears once. */
        val distinctPageIndices: List<Int> get() = ranges.flatMap { it.indices }.distinct()
    }

    data class Invalid(val error: PageRangeError) : PageRangeParseResult
}

/**
 * Parses expressions like `"1-3, 5, 8-"` (1-based, inclusive):
 * - `N` a single page, `N-M` a range, `N-` from N to the last page, `-M` from page 1 to M;
 * - parts are separated by commas or semicolons; whitespace is ignored;
 * - en/em dashes and the minus sign are accepted as hyphens (some keyboards insert them).
 *
 * The ranges are returned in the order entered (order matters for extraction and splitting).
 */
object PageRangeParser {
    private val DASHES = charArrayOf('‐', '‑', '‒', '–', '—', '−')
    private val SINGLE = Regex("""^(\d+)$""")
    private val RANGE = Regex("""^(\d*)\s*-\s*(\d*)$""")

    fun parse(input: String, pageCount: Int): PageRangeParseResult {
        var normalized = input
        DASHES.forEach { normalized = normalized.replace(it, '-') }
        val tokens = normalized.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return PageRangeParseResult.Invalid(PageRangeError.Empty)

        val ranges = ArrayList<PageRange>(tokens.size)
        for (token in tokens) {
            val range = when (val parsed = parseToken(token, pageCount)) {
                is TokenResult.Ok -> parsed.range
                is TokenResult.Error -> return PageRangeParseResult.Invalid(parsed.error)
            }
            ranges += range
        }
        return PageRangeParseResult.Valid(ranges)
    }

    private sealed interface TokenResult {
        data class Ok(val range: PageRange) : TokenResult
        data class Error(val error: PageRangeError) : TokenResult
    }

    private fun parseToken(token: String, pageCount: Int): TokenResult {
        SINGLE.matchEntire(token)?.let { match ->
            val text = match.groupValues[1]
            val page = pageNumber(text, pageCount)
                ?: return TokenResult.Error(PageRangeError.PageOutOfRange(text.trimLeadingZeros(), pageCount))
            return TokenResult.Ok(PageRange(page, page))
        }
        val match = RANGE.matchEntire(token)
            ?: return TokenResult.Error(PageRangeError.InvalidToken(token))
        val startText = match.groupValues[1]
        val endText = match.groupValues[2]
        if (startText.isEmpty() && endText.isEmpty()) {
            return TokenResult.Error(PageRangeError.InvalidToken(token))
        }
        val start = if (startText.isEmpty()) {
            1
        } else {
            pageNumber(startText, pageCount)
                ?: return TokenResult.Error(PageRangeError.PageOutOfRange(startText.trimLeadingZeros(), pageCount))
        }
        val end = if (endText.isEmpty()) {
            // "-" alone was rejected above, so an open end means "to the last page".
            if (pageCount < 1) {
                return TokenResult.Error(PageRangeError.PageOutOfRange(startText.trimLeadingZeros(), pageCount))
            }
            pageCount
        } else {
            pageNumber(endText, pageCount)
                ?: return TokenResult.Error(PageRangeError.PageOutOfRange(endText.trimLeadingZeros(), pageCount))
        }
        if (start > end) return TokenResult.Error(PageRangeError.Reversed(start, end))
        return TokenResult.Ok(PageRange(start, end))
    }

    /** The page number if it is within 1..pageCount, else null. Never overflows. */
    private fun pageNumber(digits: String, pageCount: Int): Int? {
        val trimmed = digits.trimLeadingZeros()
        if (trimmed.length > 9) return null
        val value = trimmed.toInt()
        return value.takeIf { it in 1..pageCount }
    }

    private fun String.trimLeadingZeros(): String = trimStart('0').ifEmpty { "0" }
}
