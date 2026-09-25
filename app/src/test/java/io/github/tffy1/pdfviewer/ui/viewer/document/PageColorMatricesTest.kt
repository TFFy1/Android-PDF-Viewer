package io.github.tffy1.pdfviewer.ui.viewer.document

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageColorMatricesTest {

    @Test
    fun matricesAre4x5() {
        assertEquals(20, PageColorMatrices.night.size)
        assertEquals(20, PageColorMatrices.sepia.size)
    }

    @Test
    fun night_turnsWhitePaperDarkAndBlackTextLight() {
        val paper = PageColorMatrices.apply(PageColorMatrices.night, 255f, 255f, 255f)
        val ink = PageColorMatrices.apply(PageColorMatrices.night, 0f, 0f, 0f)
        assertArrayEquals(floatArrayOf(24f, 24f, 24f), paper, 0.5f)
        assertArrayEquals(floatArrayOf(224f, 224f, 224f), ink, 0.5f)
    }

    @Test
    fun night_keepsHueOfColors() {
        // Dark red stays reddish (red channel dominant) instead of turning cyan.
        val red = PageColorMatrices.apply(PageColorMatrices.night, 150f, 0f, 0f)
        assertTrue(red[0] > red[1] && red[0] > red[2])
    }

    @Test
    fun night_preservesAlpha() {
        val m = PageColorMatrices.night
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f, 0f), m.copyOfRange(15, 20), 0f)
    }

    @Test
    fun sepia_mapsWhiteToPaperAndBlackToBrownInk() {
        val paper = PageColorMatrices.apply(PageColorMatrices.sepia, 255f, 255f, 255f)
        val ink = PageColorMatrices.apply(PageColorMatrices.sepia, 0f, 0f, 0f)
        assertArrayEquals(floatArrayOf(244f, 236f, 216f), paper, 0.5f)
        assertArrayEquals(floatArrayOf(59f, 46f, 34f), ink, 0.5f)
        // Warm: red >= green >= blue on paper.
        assertTrue(paper[0] >= paper[1] && paper[1] >= paper[2])
    }
}
