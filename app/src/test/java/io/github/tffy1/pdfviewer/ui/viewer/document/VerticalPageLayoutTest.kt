package io.github.tffy1.pdfviewer.ui.viewer.document

import io.github.tffy1.pdfviewer.core.model.PageSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerticalPageLayoutTest {
    // Three pages of 500x1000 pt fit to a 1000 px viewport -> 1000x2000 px each, 10 px gaps.
    private val layout = VerticalPageLayout(List(3) { PageSize(500f, 1000f) }, viewportWidth = 1000f, gap = 10f)

    @Test
    fun pagesFitWidthAndStackWithGaps() {
        assertEquals(2f, layout.scales[0], 1e-6f)
        assertEquals(1000f, layout.widths[1], 1e-3f)
        assertEquals(2000f, layout.heights[1], 1e-3f)
        assertEquals(10.0, layout.tops[0], 1e-6)
        assertEquals(2020.0, layout.tops[1], 1e-6)
        assertEquals(4030.0, layout.tops[2], 1e-6)
        assertEquals(6040.0, layout.totalHeight, 1e-6)
        assertEquals(0f, layout.lefts[0], 1e-6f)
    }

    @Test
    fun narrowPagesAreCentered() {
        val veryTall = VerticalPageLayout(listOf(PageSize(100f, 5000f)), viewportWidth = 1000f, gap = 0f)
        // Height is capped to MAX_PAGE_PX, so the page gets narrower than the viewport.
        assertEquals(DocumentViewDefaults.MAX_PAGE_PX, veryTall.heights[0], 1f)
        assertTrue(veryTall.widths[0] < 1000f)
        assertEquals((1000f - veryTall.widths[0]) / 2f, veryTall.lefts[0], 1e-3f)
    }

    @Test
    fun pageAt_findsPageByOffset() {
        assertEquals(0, layout.pageAt(0.0))
        assertEquals(0, layout.pageAt(2015.0))
        assertEquals(1, layout.pageAt(2020.0))
        assertEquals(2, layout.pageAt(99_999.0))
    }

    @Test
    fun visibleRange_includesOnlyIntersectingPages() {
        assertEquals(0..0, layout.visibleRange(0.0, 1500.0))
        assertEquals(0..1, layout.visibleRange(1500.0, 2500.0))
        // Only the gap between page 0 and 1 is on screen.
        assertTrue(layout.visibleRange(2011.0, 2019.0).isEmpty())
        assertEquals(1..2, layout.visibleRange(2015.0, 4500.0))
    }

    @Test
    fun mostVisiblePage_picksLargestVisibleArea() {
        assertEquals(0, layout.mostVisiblePage(1000.0, 2500.0, atEnd = false))
        assertEquals(1, layout.mostVisiblePage(1800.0, 3300.0, atEnd = false))
    }

    @Test
    fun mostVisiblePage_prefersLastPageAtEnd() {
        val slides = VerticalPageLayout(List(4) { PageSize(1600f, 900f) }, viewportWidth = 800f, gap = 0f)
        // Each page is 450 px tall; a 1000 px viewport at the end shows pages 2 and 3 fully (a tie).
        val top = slides.totalHeight - 1000.0
        assertEquals(2, slides.mostVisiblePage(top, slides.totalHeight, atEnd = false))
        assertEquals(3, slides.mostVisiblePage(top, slides.totalHeight, atEnd = true))
    }

    @Test
    fun emptyDocumentIsSafe() {
        val empty = VerticalPageLayout(emptyList(), 1000f, 10f)
        assertEquals(0.0, empty.totalHeight, 0.0)
        assertTrue(empty.visibleRange(0.0, 100.0).isEmpty())
        assertEquals(0, empty.mostVisiblePage(0.0, 100.0, atEnd = false))
    }

    @Test
    fun thousandPagesAreCheap() {
        val big = VerticalPageLayout(List(1000) { PageSize(612f, 792f) }, 1080f, 20f)
        val page = big.pageAt(big.tops[731] + 5.0)
        assertEquals(731, page)
    }
}
