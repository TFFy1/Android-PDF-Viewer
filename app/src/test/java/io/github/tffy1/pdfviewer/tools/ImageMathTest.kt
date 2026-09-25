package io.github.tffy1.pdfviewer.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageMathTest {

    private val eps = 0.01f

    private fun assertPoint(expected: Pair<Float, Float>, actual: Pair<Float, Float>) {
        assertEquals(expected.first, actual.first, eps)
        assertEquals(expected.second, actual.second, eps)
    }

    // Stored-image corners in the unit square (u right, v up): top-left of the stored image is (0, 1).
    private val storedTopLeft = 0f to 1f
    private val storedTopRight = 1f to 1f
    private val storedBottomLeft = 0f to 0f

    // Target rectangle x=10, y=20, w=100, h=200 → corners on the page (PDF y up).
    private val x = 10f
    private val y = 20f
    private val w = 100f
    private val h = 200f
    private val pageTopLeft = x to y + h
    private val pageTopRight = x + w to y + h
    private val pageBottomLeft = x to y
    private val pageBottomRight = x + w to y

    private fun matrix(orientation: Int) = ImageLayout.orientationMatrix(orientation, x, y, w, h)

    private fun PdfMatrix.at(p: Pair<Float, Float>) = map(p.first, p.second)

    @Test
    fun normalOrientationKeepsCorners() {
        val m = matrix(ImageLayout.ORIENTATION_NORMAL)
        assertPoint(pageTopLeft, m.at(storedTopLeft))
        assertPoint(pageTopRight, m.at(storedTopRight))
        assertPoint(pageBottomLeft, m.at(storedBottomLeft))
    }

    @Test
    fun rotate90MovesTopLeftToTopRight() {
        val m = matrix(ImageLayout.ORIENTATION_ROTATE_90)
        assertPoint(pageTopRight, m.at(storedTopLeft))
        assertPoint(pageBottomRight, m.at(storedTopRight))
        assertPoint(pageTopLeft, m.at(storedBottomLeft))
    }

    @Test
    fun rotate180() {
        val m = matrix(ImageLayout.ORIENTATION_ROTATE_180)
        assertPoint(pageBottomRight, m.at(storedTopLeft))
        assertPoint(pageBottomLeft, m.at(storedTopRight))
        assertPoint(pageTopRight, m.at(storedBottomLeft))
    }

    @Test
    fun rotate270MovesTopLeftToBottomLeft() {
        val m = matrix(ImageLayout.ORIENTATION_ROTATE_270)
        assertPoint(pageBottomLeft, m.at(storedTopLeft))
        assertPoint(pageTopLeft, m.at(storedTopRight))
        assertPoint(pageBottomRight, m.at(storedBottomLeft))
    }

    @Test
    fun flips() {
        val horizontal = matrix(ImageLayout.ORIENTATION_FLIP_HORIZONTAL)
        assertPoint(pageTopRight, horizontal.at(storedTopLeft))
        assertPoint(pageBottomRight, horizontal.at(storedBottomLeft))

        val vertical = matrix(ImageLayout.ORIENTATION_FLIP_VERTICAL)
        assertPoint(pageBottomLeft, vertical.at(storedTopLeft))
        assertPoint(pageTopLeft, vertical.at(storedBottomLeft))
    }

    @Test
    fun transposeAndTransverse() {
        val transpose = matrix(ImageLayout.ORIENTATION_TRANSPOSE)
        assertPoint(pageTopLeft, transpose.at(storedTopLeft))
        assertPoint(pageBottomLeft, transpose.at(storedTopRight))
        assertPoint(pageTopRight, transpose.at(storedBottomLeft))

        val transverse = matrix(ImageLayout.ORIENTATION_TRANSVERSE)
        assertPoint(pageBottomRight, transverse.at(storedTopLeft))
        assertPoint(pageTopRight, transverse.at(storedTopRight))
        assertPoint(pageBottomLeft, transverse.at(storedBottomLeft))
    }

    @Test
    fun a4PortraitImageIsCenteredWithMargins() {
        // 1000x2000 portrait image on A4 with 36pt margins.
        val placement = ImageLayout.place(1000, 2000, ImageLayout.ORIENTATION_NORMAL, ImagePageSize.A4, ImageMargin.LARGE)
        assertEquals(ImageLayout.A4_WIDTH, placement.pageWidth, eps)
        assertEquals(ImageLayout.A4_HEIGHT, placement.pageHeight, eps)
        val areaHeight = ImageLayout.A4_HEIGHT - 72f
        val drawWidth = areaHeight / 2f
        // Height-limited: fills the height, centered horizontally.
        assertEquals(drawWidth, placement.matrix.a, eps)
        assertEquals(areaHeight, placement.matrix.d, eps)
        assertEquals((ImageLayout.A4_WIDTH - drawWidth) / 2f, placement.matrix.e, eps)
        assertEquals(36f, placement.matrix.f, eps)
    }

    @Test
    fun landscapeImageGetsLandscapePage() {
        val placement = ImageLayout.place(3000, 2000, ImageLayout.ORIENTATION_NORMAL, ImagePageSize.LETTER, ImageMargin.NONE)
        assertEquals(ImageLayout.LETTER_HEIGHT, placement.pageWidth, eps)
        assertEquals(ImageLayout.LETTER_WIDTH, placement.pageHeight, eps)
    }

    @Test
    fun exifRotationDecidesPageOrientation() {
        // Stored landscape, displayed portrait after a 90° rotation.
        val placement = ImageLayout.place(4000, 3000, ImageLayout.ORIENTATION_ROTATE_90, ImagePageSize.A4, ImageMargin.NONE)
        assertEquals(ImageLayout.A4_WIDTH, placement.pageWidth, eps)
        assertEquals(ImageLayout.A4_HEIGHT, placement.pageHeight, eps)
        // The displayed image (3:4) is width-limited: it spans the full page width.
        val topRight = placement.matrix.map(0f, 1f)
        assertEquals(ImageLayout.A4_WIDTH, topRight.first, eps)
    }

    @Test
    fun fitImagePageMatchesAspectRatio() {
        val placement = ImageLayout.place(800, 400, ImageLayout.ORIENTATION_NORMAL, ImagePageSize.FIT_IMAGE, ImageMargin.SMALL)
        assertEquals(ImageLayout.FIT_LONG_SIDE + 36f, placement.pageWidth, eps)
        assertEquals(ImageLayout.FIT_LONG_SIDE / 2f + 36f, placement.pageHeight, eps)
        assertEquals(18f, placement.matrix.e, eps)
        assertEquals(18f, placement.matrix.f, eps)
        assertEquals(ImageLayout.FIT_LONG_SIDE, placement.matrix.a, eps)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyImageIsRejected() {
        ImageLayout.place(0, 10, ImageLayout.ORIENTATION_NORMAL, ImagePageSize.A4, ImageMargin.NONE)
    }

    @Test
    fun sampleSizeKeepsDecodeWithinTwiceTheBudget() {
        assertEquals(1, ImageSampling.sampleSize(1000, 1000, 4_000_000))
        assertEquals(1, ImageSampling.sampleSize(2000, 3000, 4_000_000))
        // 48 MP → 12 MP at 2 (still > 8), 3 MP at 4.
        assertEquals(4, ImageSampling.sampleSize(8000, 6000, 4_000_000))
        val sample = ImageSampling.sampleSize(12000, 9000, 2_000_000)
        assertTrue((12000L / sample) * (9000L / sample) <= 4_000_000)
    }

    @Test
    fun targetSizeFitsBudgetAndKeepsAspect() {
        assertEquals(800 to 600, ImageSampling.targetSize(800, 600, 1_000_000))
        val (tw, th) = ImageSampling.targetSize(4000, 3000, 3_000_000)
        assertTrue(tw.toLong() * th <= 3_000_000)
        assertEquals(4f / 3f, tw.toFloat() / th, 0.01f)
        assertEquals(1 to 1, ImageSampling.targetSize(1, 1, 1))
    }

    @Test
    fun subsampling() {
        assertEquals(1, ImageSampling.subsampling(1000, 1000, 2_000_000))
        assertEquals(2, ImageSampling.subsampling(4000, 3000, 2_500_000))
        assertEquals(3, ImageSampling.subsampling(6000, 4500, 2_500_000))
    }

    @Test
    fun replacementNeedsTenPercentSavings() {
        assertTrue(ImageSampling.isWorthReplacing(1000, 899))
        assertFalse(ImageSampling.isWorthReplacing(1000, 900))
        assertFalse(ImageSampling.isWorthReplacing(1000, 0))
    }

    @Test
    fun percentSaved() {
        assertEquals(67, ImageSampling.percentSaved(300, 100))
        assertEquals(0, ImageSampling.percentSaved(100, 150))
        assertEquals(0, ImageSampling.percentSaved(0, 10))
    }
}
