package io.github.tffy1.pdfviewer.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageEditsTest {

    private fun order(pages: List<PageEdit>) = pages.map { it.sourceIndex }

    @Test
    fun normalizeRotation() {
        assertEquals(0, PageEdits.normalizeRotation(0))
        assertEquals(90, PageEdits.normalizeRotation(450))
        assertEquals(270, PageEdits.normalizeRotation(-90))
        assertEquals(180, PageEdits.normalizeRotation(-540))
        assertEquals(0, PageEdits.normalizeRotation(720))
    }

    @Test
    fun rotateOnlySelectedPages() {
        val pages = PageEdits.rotate(PageEdits.initial(3), setOf(0, 2), 90)
        assertEquals(listOf(90, 0, 90), pages.map { it.rotationDelta })
        val back = PageEdits.rotate(pages, setOf(0), -90)
        assertEquals(listOf(0, 0, 90), back.map { it.rotationDelta })
    }

    @Test
    fun deleteSelectedPages() {
        assertEquals(listOf(1, 3), order(PageEdits.delete(PageEdits.initial(4), setOf(0, 2))))
    }

    @Test
    fun moveEarlierSingle() {
        assertEquals(listOf(0, 2, 1, 3), order(PageEdits.moveEarlier(PageEdits.initial(4), setOf(2))))
    }

    @Test
    fun moveEarlierAtStartIsNoOp() {
        assertEquals(listOf(0, 1, 2), order(PageEdits.moveEarlier(PageEdits.initial(3), setOf(0))))
    }

    @Test
    fun moveEarlierBlockMovesTogether() {
        assertEquals(listOf(0, 2, 3, 1, 4), order(PageEdits.moveEarlier(PageEdits.initial(5), setOf(2, 3))))
    }

    @Test
    fun moveEarlierBlockAtStartStaysAndOthersMove() {
        // 0,1 already at the start; 3 moves one step towards them.
        assertEquals(listOf(0, 1, 3, 2), order(PageEdits.moveEarlier(PageEdits.initial(4), setOf(0, 1, 3))))
    }

    @Test
    fun moveLaterSingle() {
        assertEquals(listOf(0, 2, 1, 3), order(PageEdits.moveLater(PageEdits.initial(4), setOf(1))))
    }

    @Test
    fun moveLaterAtEndIsNoOp() {
        assertEquals(listOf(0, 1, 2), order(PageEdits.moveLater(PageEdits.initial(3), setOf(2))))
    }

    @Test
    fun moveLaterBlockMovesTogether() {
        assertEquals(listOf(0, 3, 1, 2, 4), order(PageEdits.moveLater(PageEdits.initial(5), setOf(1, 2))))
    }

    @Test
    fun movesKeepRotation() {
        val rotated = PageEdits.rotate(PageEdits.initial(3), setOf(2), 180)
        val moved = PageEdits.moveEarlier(rotated, setOf(2))
        assertEquals(PageEdit(2, 180), moved[1])
    }

    @Test
    fun isUnchanged() {
        assertTrue(PageEdits.isUnchanged(PageEdits.initial(3), 3))
        assertTrue(PageEdits.isUnchanged(PageEdits.rotate(PageEdits.initial(3), setOf(1), 360), 3))
        assertFalse(PageEdits.isUnchanged(PageEdits.rotate(PageEdits.initial(3), setOf(1), 90), 3))
        assertFalse(PageEdits.isUnchanged(PageEdits.moveLater(PageEdits.initial(3), setOf(0)), 3))
        assertFalse(PageEdits.isUnchanged(PageEdits.delete(PageEdits.initial(3), setOf(2)), 3))
    }
}
