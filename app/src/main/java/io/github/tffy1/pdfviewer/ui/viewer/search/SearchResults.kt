package io.github.tffy1.pdfviewer.ui.viewer.search

import io.github.tffy1.pdfviewer.pdf.SearchMatch

/*
 * Pure helpers behind SearchController. Kept free of Android and coroutines so they are
 * unit-testable. Result lists are always ordered by page, then by position on the page.
 */

/**
 * Moves [current] by [step] within a list of [size] results, wrapping around at both ends.
 * With no focused result (current out of range) "next" lands on the first result and
 * "previous" on the last. Returns -1 for an empty list.
 */
internal fun stepIndex(current: Int, step: Int, size: Int): Int {
    if (size <= 0) return -1
    if (current !in 0 until size) return if (step >= 0) 0 else size - 1
    return (current + step).mod(size)
}

/** Order in which pages are scanned: [startPage] to the end, then wrapping to the beginning. */
internal fun scanOrder(pageCount: Int, startPage: Int): IntArray {
    if (pageCount <= 0) return IntArray(0)
    val first = startPage.coerceIn(0, pageCount - 1)
    return IntArray(pageCount) { (first + it) % pageCount }
}

/** Index of the match at ([pageIndex], [startIndex]) in page-ordered [results], or -1. */
internal fun indexOfMatch(results: List<SearchMatch>, pageIndex: Int, startIndex: Int): Int {
    val found = results.binarySearch { match ->
        val byPage = match.pageIndex.compareTo(pageIndex)
        if (byPage != 0) byPage else match.startIndex.compareTo(startIndex)
    }
    return if (found >= 0) found else -1
}

/** The first match on or after [startPage], else the first match of the document; -1 if none. */
internal fun initialMatchIndex(results: List<SearchMatch>, startPage: Int): Int {
    if (results.isEmpty()) return -1
    val index = results.indexOfFirst { it.pageIndex >= startPage }
    return if (index >= 0) index else 0
}

/**
 * Chooses the focused index after the result list grew. The previously focused match keeps the
 * focus (its index may shift when earlier pages report matches later), so the highlighted
 * result never jumps while the search is still streaming in.
 */
internal fun reconcileIndex(results: List<SearchMatch>, previous: SearchMatch?, startPage: Int): Int {
    if (results.isEmpty()) return -1
    if (previous != null) {
        val index = indexOfMatch(results, previous.pageIndex, previous.startIndex)
        if (index >= 0) return index
    }
    return initialMatchIndex(results, startPage)
}

/** Maps each page to the contiguous range of indices of its matches in page-ordered [results]. */
internal fun groupRangesByPage(results: List<SearchMatch>): Map<Int, IntRange> {
    if (results.isEmpty()) return emptyMap()
    val ranges = HashMap<Int, IntRange>()
    var start = 0
    for (i in 1..results.size) {
        if (i == results.size || results[i].pageIndex != results[start].pageIndex) {
            ranges[results[start].pageIndex] = start until i
            start = i
        }
    }
    return ranges
}
