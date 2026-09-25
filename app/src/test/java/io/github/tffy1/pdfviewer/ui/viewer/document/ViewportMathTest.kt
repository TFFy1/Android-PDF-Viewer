package io.github.tffy1.pdfviewer.ui.viewer.document

import io.github.tffy1.pdfviewer.core.model.PageSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportMathTest {

    @Test
    fun clampScroll_keepsScrollInsideLargerContent() {
        assertEquals(0.0, clampScroll(-50.0, 1000.0, 400.0), 0.0)
        assertEquals(600.0, clampScroll(900.0, 1000.0, 400.0), 0.0)
        assertEquals(250.0, clampScroll(250.0, 1000.0, 400.0), 0.0)
    }

    @Test
    fun clampScroll_centersSmallerContent() {
        // 200 px content in a 400 px viewport starts at 100 px -> scroll -100.
        assertEquals(-100.0, clampScroll(37.0, 200.0, 400.0), 0.0)
        assertEquals(0.0, clampScroll(37.0, 400.0, 400.0), 0.0)
    }

    @Test
    fun zoomAround_keepsFocusedContentPointFixed() {
        val scroll = 300.0
        val focus = 120f
        val oldZoom = 1.5f
        val newZoom = 3f
        val contentPoint = (scroll + focus) / oldZoom
        val newScroll = zoomAround(scroll, oldZoom, newZoom, focus)
        assertEquals(contentPoint * newZoom - newScroll, focus.toDouble(), 1e-9)
    }

    @Test
    fun zoomAround_identityWhenZoomUnchanged() {
        assertEquals(123.0, zoomAround(123.0, 2f, 2f, 50f), 1e-9)
    }

    @Test
    fun scrollToReveal_doesNotMoveWhenVisible() {
        assertEquals(100.0, scrollToReveal(150.0, 200.0, 100.0, 400.0), 0.0)
    }

    @Test
    fun scrollToReveal_centersSpanThatFits() {
        // Span 1000..1100 (center 1050) in a 400 px viewport -> scroll 850.
        assertEquals(850.0, scrollToReveal(1000.0, 1100.0, 0.0, 400.0), 0.0)
    }

    @Test
    fun scrollToReveal_alignsStartOfSpanLargerThanViewport() {
        assertEquals(1000.0, scrollToReveal(1000.0, 2000.0, 0.0, 400.0), 0.0)
    }

    @Test
    fun fitInsideScale_usesLimitingDimension() {
        val portrait = PageSize(600f, 800f)
        assertEquals(1f, fitInsideScale(portrait, 600f, 1600f), 1e-6f)
        assertEquals(0.5f, fitInsideScale(portrait, 600f, 400f), 1e-6f)
    }

    @Test
    fun maxZoomFor_boundsPagePixelsAndClampsToRange() {
        assertEquals(DocumentViewDefaults.MAX_ZOOM, maxZoomFor(1000f), 0f)
        assertEquals(DocumentViewDefaults.MAX_PAGE_PX / 8000f, maxZoomFor(8000f), 1e-6f)
        assertEquals(1f, maxZoomFor(100_000f), 0f)
        assertEquals(DocumentViewDefaults.MAX_ZOOM, maxZoomFor(0f), 0f)
    }

    @Test
    fun baseRenderSize_keepsSizeWhenSmall() {
        assertEquals(PixelSize(1080, 1400), baseRenderSize(1080f, 1400f))
    }

    @Test
    fun baseRenderSize_capsWidthAndPixels() {
        val wide = baseRenderSize(4096f, 1000f)
        assertEquals(2048, wide.width)
        assertEquals(500, wide.height)

        val tall = baseRenderSize(1000f, 20_000f)
        assertTrue(tall.width.toLong() * tall.height <= DocumentViewDefaults.MAX_BASE_PIXELS.toLong() + tall.width + tall.height)
        assertEquals(20f, tall.height.toFloat() / tall.width, 0.05f)
    }

    @Test
    fun baseRenderSize_neverZero() {
        assertEquals(PixelSize(1, 1), baseRenderSize(0f, 0f))
    }

    @Test
    fun pagerTargetPage_nearestWhenSlow() {
        assertEquals(3, pagerTargetPage(3, 0.3f, 0f, 400f, 10))
        assertEquals(4, pagerTargetPage(3, 0.6f, 0f, 400f, 10))
        assertEquals(2, pagerTargetPage(3, -0.6f, 0f, 400f, 10))
    }

    @Test
    fun pagerTargetPage_flingTurnsPageInDragDirection() {
        assertEquals(4, pagerTargetPage(3, 0.1f, 1000f, 400f, 10))
        assertEquals(2, pagerTargetPage(3, -0.1f, -1000f, 400f, 10))
    }

    @Test
    fun pagerTargetPage_flingBackCancelsTurn() {
        assertEquals(3, pagerTargetPage(3, 0.7f, -1000f, 400f, 10))
    }

    @Test
    fun pagerTargetPage_clampsToDocument() {
        assertEquals(9, pagerTargetPage(9, 0.8f, 2000f, 400f, 10))
        assertEquals(0, pagerTargetPage(0, -0.8f, -2000f, 400f, 10))
        assertEquals(0, pagerTargetPage(0, 0f, 0f, 400f, 0))
    }

    @Test
    fun sanitized_replacesBrokenSizes() {
        assertEquals(PageSize(100f, 200f), PageSize(100f, 200f).sanitized())
        assertEquals(DocumentViewDefaults.FALLBACK_PAGE_SIZE, PageSize(0f, 200f).sanitized())
        assertEquals(DocumentViewDefaults.FALLBACK_PAGE_SIZE, PageSize(Float.NaN, 200f).sanitized())
    }
}
