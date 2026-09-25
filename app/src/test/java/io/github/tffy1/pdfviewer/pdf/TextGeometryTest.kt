package io.github.tffy1.pdfviewer.pdf

import io.github.tffy1.pdfviewer.core.model.PageRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextGeometryTest {
    @Test
    fun emptyInputGivesNoLines() {
        assertEquals(emptyList<PageRect>(), mergeCharBoxesIntoLines(emptyList()))
    }

    @Test
    fun charsOnOneLineMergeIntoOneRect() {
        val boxes = listOf(
            PageRect(10f, 100f, 16f, 110f), // "l" (tall)
            PageRect(17f, 104f, 22f, 110f), // "o" (x-height)
            PageRect(23f, 108f, 25f, 110f), // "." (tiny)
            PageRect(26f, 100f, 28f, 103f), // "'" (high, no overlap with "o")
        )
        assertEquals(listOf(PageRect(10f, 100f, 28f, 110f)), mergeCharBoxesIntoLines(boxes))
    }

    @Test
    fun lineBreakStartsNewRect() {
        val boxes = listOf(
            PageRect(200f, 100f, 210f, 112f),
            PageRect(211f, 100f, 220f, 112f),
            PageRect(0f, 0f, 0f, 0f), // generated line break placeholder
            PageRect(10f, 114f, 20f, 126f),
            PageRect(21f, 114f, 30f, 126f),
        )
        assertEquals(
            listOf(PageRect(200f, 100f, 220f, 112f), PageRect(10f, 114f, 30f, 126f)),
            mergeCharBoxesIntoLines(boxes),
        )
    }

    @Test
    fun slightlyOverlappingLinesStaySeparate() {
        // Descender of line 1 overlaps the cap height of line 2 by 1pt.
        val boxes = listOf(PageRect(10f, 100f, 20f, 113f), PageRect(10f, 112f, 20f, 122f))
        assertEquals(2, mergeCharBoxesIntoLines(boxes).size)
    }

    @Test
    fun dropCapDoesNotSwallowNextLine() {
        val boxes = listOf(
            PageRect(0f, 100f, 30f, 130f), // drop cap spanning two lines
            PageRect(31f, 100f, 40f, 110f),
            PageRect(31f, 115f, 40f, 125f), // next line, still beside the drop cap
        )
        assertEquals(
            listOf(PageRect(0f, 100f, 40f, 130f), PageRect(31f, 115f, 40f, 125f)),
            mergeCharBoxesIntoLines(boxes),
        )
    }

    @Test
    fun areaLessBoxesAreIgnored() {
        val boxes = listOf(PageRect(5f, 5f, 5f, 5f), PageRect(10f, 50f, 20f, 50f))
        assertEquals(emptyList<PageRect>(), mergeCharBoxesIntoLines(boxes))
    }

    @Test
    fun overlapRatio() {
        assertEquals(1f, verticalOverlapRatio(PageRect(0f, 0f, 1f, 10f), PageRect(0f, 2f, 1f, 4f)), 0.001f)
        assertEquals(0.5f, verticalOverlapRatio(PageRect(0f, 0f, 1f, 10f), PageRect(0f, 5f, 1f, 15f)), 0.001f)
        assertEquals(0f, verticalOverlapRatio(PageRect(0f, 0f, 1f, 10f), PageRect(0f, 20f, 1f, 30f)), 0.001f)
    }

    @Test
    fun zeroSize() {
        assertTrue(PageRect(3f, 4f, 3f, 4f).isZeroSize())
        assertFalse(PageRect(3f, 4f, 3f, 8f).isZeroSize())
    }
}
