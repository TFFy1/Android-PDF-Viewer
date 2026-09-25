package io.github.tffy1.pdfviewer.ui.viewer.search

import io.github.tffy1.pdfviewer.core.model.PageRect
import io.github.tffy1.pdfviewer.pdf.SearchMatch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchResultsTest {

    private fun match(page: Int, start: Int) = SearchMatch(page, start, 3, listOf(PageRect(0f, 0f, 1f, 1f)))

    private val results = listOf(match(0, 4), match(0, 20), match(2, 1), match(5, 0), match(5, 9))

    @Test
    fun stepIndexWrapsAroundBothEnds() {
        assertEquals(1, stepIndex(0, 1, 3))
        assertEquals(0, stepIndex(2, 1, 3))
        assertEquals(2, stepIndex(0, -1, 3))
        assertEquals(1, stepIndex(2, -1, 3))
    }

    @Test
    fun stepIndexWithoutFocusStartsAtTheMatchingEnd() {
        assertEquals(0, stepIndex(-1, 1, 3))
        assertEquals(2, stepIndex(-1, -1, 3))
        assertEquals(0, stepIndex(7, 1, 3))
    }

    @Test
    fun stepIndexOnEmptyResultsIsNone() {
        assertEquals(-1, stepIndex(-1, 1, 0))
        assertEquals(-1, stepIndex(0, -1, 0))
    }

    @Test
    fun scanOrderStartsAtStartPageAndWraps() {
        assertArrayEquals(intArrayOf(2, 3, 4, 0, 1), scanOrder(5, 2))
        assertArrayEquals(intArrayOf(0, 1, 2), scanOrder(3, 0))
        assertArrayEquals(intArrayOf(2, 0, 1), scanOrder(3, 99))
        assertArrayEquals(intArrayOf(0, 1), scanOrder(2, -4))
        assertArrayEquals(intArrayOf(), scanOrder(0, 0))
    }

    @Test
    fun indexOfMatchFindsByPageAndStart() {
        assertEquals(0, indexOfMatch(results, 0, 4))
        assertEquals(3, indexOfMatch(results, 5, 0))
        assertEquals(4, indexOfMatch(results, 5, 9))
        assertEquals(-1, indexOfMatch(results, 2, 2))
        assertEquals(-1, indexOfMatch(emptyList(), 0, 0))
    }

    @Test
    fun initialMatchIsFirstAtOrAfterStartPage() {
        assertEquals(0, initialMatchIndex(results, 0))
        assertEquals(2, initialMatchIndex(results, 1))
        assertEquals(3, initialMatchIndex(results, 5))
        assertEquals(0, initialMatchIndex(results, 6)) // nothing after: wrap to the first match
        assertEquals(-1, initialMatchIndex(emptyList(), 0))
    }

    @Test
    fun reconcileKeepsFocusOnTheSameMatchWhenEarlierResultsArrive() {
        val partial = listOf(match(5, 0), match(5, 9))
        val focused = partial[0]
        assertEquals(3, reconcileIndex(results, focused, startPage = 4))
    }

    @Test
    fun reconcileWithoutFocusUsesStartPage() {
        assertEquals(3, reconcileIndex(results, null, startPage = 4))
        assertEquals(-1, reconcileIndex(emptyList(), null, startPage = 0))
    }

    @Test
    fun groupRangesByPageCoversEachPageContiguously() {
        val ranges = groupRangesByPage(results)
        assertEquals(mapOf(0 to 0..1, 2 to 2..2, 5 to 3..4), ranges)
        assertEquals(emptyMap<Int, IntRange>(), groupRangesByPage(emptyList()))
    }
}
