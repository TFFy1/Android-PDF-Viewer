package io.github.tffy1.pdfviewer.ui.viewer.search

import io.github.tffy1.pdfviewer.core.model.PageRect
import io.github.tffy1.pdfviewer.pdf.SearchMatch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchControllerTest {

    private fun match(page: Int, start: Int) = SearchMatch(page, start, 3, listOf(PageRect(0f, 0f, 1f, 1f)))

    private class Doc(pages: Int, private val hits: (Int) -> List<SearchMatch>) : FakePdfDocument(pages) {
        val calls = mutableListOf<Pair<Int, String>>()

        override suspend fun searchPage(
            pageIndex: Int,
            query: String,
            matchCase: Boolean,
            wholeWord: Boolean,
        ): List<SearchMatch> {
            calls += pageIndex to query
            return hits(pageIndex)
        }
    }

    @Test
    fun resultsAreOrderedByPageAndFocusStaysOnFirstHitFromStartPage() = runTest {
        val byPage = mapOf(1 to listOf(match(1, 0), match(1, 10)), 3 to listOf(match(3, 5)))
        val doc = Doc(5) { byPage[it].orEmpty() }
        val controller = SearchController(doc, this, startPage = { 2 })

        controller.search("  foo ")
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals("foo", state.query)
        assertEquals(listOf(match(1, 0), match(1, 10), match(3, 5)), state.results)
        assertEquals(match(3, 5), state.currentMatch)
        assertFalse(state.isSearching)
        assertEquals(1f, state.progress)
        assertEquals(listOf(2, 3, 4, 0, 1), doc.calls.map { it.first })
    }

    @Test
    fun typingIsDebouncedAndOnlyTheLastQueryRuns() = runTest {
        val doc = Doc(2) { emptyList() }
        val controller = SearchController(doc, this)

        controller.search("a")
        advanceTimeBy(100)
        controller.search("ab")
        advanceTimeBy(100)
        controller.search("abc")
        advanceUntilIdle()

        assertEquals(setOf("abc"), doc.calls.map { it.second }.toSet())
        assertEquals("abc", controller.state.value.query)
        assertTrue(controller.state.value.results.isEmpty())
        assertFalse(controller.state.value.isSearching)
    }

    @Test
    fun nextAndPreviousWrapAround() = runTest {
        val doc = Doc(2) { page -> listOf(match(page, 0)) }
        val controller = SearchController(doc, this)
        controller.search("x")
        advanceUntilIdle()
        assertEquals(0, controller.state.value.currentIndex)

        controller.next()
        assertEquals(1, controller.state.value.currentIndex)
        controller.next()
        assertEquals(0, controller.state.value.currentIndex)
        controller.previous()
        assertEquals(1, controller.state.value.currentIndex)
    }

    @Test
    fun blankQueryClears() = runTest {
        val doc = Doc(1) { listOf(match(0, 0)) }
        val controller = SearchController(doc, this)
        controller.search("x")
        advanceUntilIdle()
        assertEquals(1, controller.state.value.results.size)

        controller.search("   ")
        assertEquals(SearchState(), controller.state.value)
    }

    @Test
    fun failingPageDoesNotAbortTheSearch() = runTest {
        val doc = Doc(3) { page ->
            if (page == 1) throw IllegalStateException("broken page")
            listOf(match(page, 0))
        }
        val controller = SearchController(doc, this)
        controller.search("x")
        advanceUntilIdle()
        assertEquals(listOf(match(0, 0), match(2, 0)), controller.state.value.results)
    }

    @Test
    fun resultsAreCapped() = runTest {
        val doc = Doc(5) { page -> List(3_000) { match(page, it) } }
        val controller = SearchController(doc, this)
        controller.search("e")
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(10_000, state.results.size)
        assertTrue(state.isTruncated)
        assertFalse(state.isSearching)
        assertEquals(4, doc.calls.size)
    }
}
