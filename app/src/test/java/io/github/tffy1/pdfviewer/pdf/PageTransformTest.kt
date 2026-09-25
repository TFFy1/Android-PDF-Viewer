package io.github.tffy1.pdfviewer.pdf

import io.github.tffy1.pdfviewer.core.model.PageRect
import io.github.tffy1.pdfviewer.core.model.PageSize
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

class PageTransformTest {
    /**
     * Transcription of Pdfium's CPDF_Page::UpdateDimensions + GetDisplayMatrixForFloatRect
     * (rotate argument 0) followed by FPDF_PageToDevice's rounding, for a page whose effective
     * box is [left, bottom, right, top] and whose /Rotate is [rotation] quarter turns.
     */
    private class FakePdfiumPage(
        val rotation: Int,
        val left: Double,
        val bottom: Double,
        val right: Double,
        val top: Double,
    ) {
        val size: PageSize = if (rotation % 2 == 0) {
            PageSize((right - left).toFloat(), (top - bottom).toFloat())
        } else {
            PageSize((top - bottom).toFloat(), (right - left).toFloat())
        }

        private fun pageMatrix(x: Double, y: Double): Pair<Double, Double> = when (rotation) {
            0 -> Pair(x - left, y - bottom)
            1 -> Pair(y - bottom, -x + right)
            2 -> Pair(-x + right, -y + top)
            else -> Pair(-y + top, x - left)
        }

        fun toDevice(x: Double, y: Double, sizeX: Int, sizeY: Int): Pair<Int, Int> {
            val (px, py) = pageMatrix(x, y)
            val dx = px * sizeX / size.width
            val dy = sizeY - py * sizeY / size.height
            return Pair(dx.roundToInt(), dy.roundToInt())
        }

        fun transform(): PageTransform {
            val scale = PageTransform.probeScale(size)
            val (w, h) = PageTransform.probeDeviceSize(size, scale)
            val p = PageTransform.PROBE_LENGTH
            val o = toDevice(0.0, 0.0, w, h)
            val xa = toDevice(p, 0.0, w, h)
            val ya = toDevice(0.0, p, w, h)
            return PageTransform.fromDeviceProbes(o.first, o.second, xa.first, xa.second, ya.first, ya.second, scale)
        }
    }

    private fun assertRect(expected: PageRect, actual: PageRect) {
        assertEquals("left", expected.left, actual.left, TOLERANCE)
        assertEquals("top", expected.top, actual.top, TOLERANCE)
        assertEquals("right", expected.right, actual.right, TOLERANCE)
        assertEquals("bottom", expected.bottom, actual.bottom, TOLERANCE)
    }

    @Test
    fun unrotatedPageFlipsY() {
        val t = FakePdfiumPage(0, 0.0, 0.0, 612.0, 792.0).transform()
        // A char box 72pt from the left and 72pt from the top of the page.
        assertRect(PageRect(72f, 60f, 80f, 72f), t.mapRect(72f, 720f, 80f, 732f))
    }

    @Test
    fun cropBoxOriginIsSubtracted() {
        val t = FakePdfiumPage(0, 100.0, 200.0, 500.0, 700.0).transform()
        assertRect(PageRect(0f, 0f, 400f, 500f), t.mapRect(100f, 700f, 500f, 200f))
        assertRect(PageRect(10f, 20f, 30f, 40f), t.mapRect(110f, 680f, 130f, 660f))
    }

    @Test
    fun rotate90MovesUnrotatedTopLeftToTopRight() {
        val page = FakePdfiumPage(1, 0.0, 0.0, 612.0, 792.0)
        assertEquals(PageSize(792f, 612f), page.size)
        val t = page.transform()
        val topLeft = t.mapPoint(0f, 792f)
        assertEquals(792f, topLeft.x, TOLERANCE)
        assertEquals(0f, topLeft.y, TOLERANCE)
        // Horizontal text in user space runs downwards on the displayed page.
        assertRect(PageRect(700f, 72f, 712f, 80f), t.mapRect(72f, 700f, 80f, 712f))
    }

    @Test
    fun rotate180() {
        val t = FakePdfiumPage(2, 0.0, 0.0, 612.0, 792.0).transform()
        // Near the unrotated top-left corner → near the displayed bottom-right corner.
        assertRect(PageRect(532f, 720f, 540f, 732f), t.mapRect(72f, 720f, 80f, 732f))
    }

    @Test
    fun rotate270WithOffsetCropBox() {
        val page = FakePdfiumPage(3, 50.0, 100.0, 450.0, 400.0)
        assertEquals(PageSize(300f, 400f), page.size)
        val t = page.transform()
        // Counter-clockwise quarter turn: unrotated top-right corner → displayed top-left,
        // unrotated bottom-left corner → displayed bottom-right.
        val topRight = t.mapPoint(450f, 400f)
        assertEquals(0f, topRight.x, TOLERANCE)
        assertEquals(0f, topRight.y, TOLERANCE)
        val bottomLeft = t.mapPoint(50f, 100f)
        assertEquals(300f, bottomLeft.x, TOLERANCE)
        assertEquals(400f, bottomLeft.y, TOLERANCE)
        assertRect(PageRect(0f, 0f, 300f, 400f), t.mapRect(50f, 100f, 450f, 400f))
    }

    @Test
    fun hugePagesKeepProbeCoordinatesInRange() {
        val size = PageSize(200_000f, 200_000f)
        val scale = PageTransform.probeScale(size)
        val (w, h) = PageTransform.probeDeviceSize(size, scale)
        assertEquals(10_000_000, w)
        assertEquals(10_000_000, h)
    }

    private companion object {
        const val TOLERANCE = 0.02f
    }
}
