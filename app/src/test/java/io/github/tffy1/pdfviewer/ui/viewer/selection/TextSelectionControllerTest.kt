package io.github.tffy1.pdfviewer.ui.viewer.selection

import io.github.tffy1.pdfviewer.core.model.PagePoint
import io.github.tffy1.pdfviewer.pdf.PageText
import io.github.tffy1.pdfviewer.ui.viewer.search.FakePdfDocument
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TextSelectionControllerTest {

    private class Doc(private val page: PageText) : FakePdfDocument(1) {
        var loads = 0

        override suspend fun pageText(pageIndex: Int): PageText {
            loads++
            return page
        }
    }

    @Test
    fun longPressSelectsTheWord() = runTest {
        val controller = TextSelectionController(Doc(layoutPage("hello big world")), this)
        controller.selectWordAt(0, PagePoint(65f, 6f)) // on "big"
        advanceUntilIdle()

        val selection = controller.selection.value!!
        assertEquals("big", selection.text)
        assertEquals(6, selection.startIndex)
        assertEquals(9, selection.endIndex)
    }

    @Test
    fun longPressOnEmptyAreaIsNoOp() = runTest {
        val controller = TextSelectionController(Doc(layoutPage("hello")), this)
        controller.selectWordAt(0, PagePoint(300f, 300f))
        controller.selectWordAt(5, PagePoint(5f, 5f)) // page out of range
        advanceUntilIdle()
        assertNull(controller.selection.value)
    }

    @Test
    fun draggingAHandleExtendsAndCrossesTheAnchor() = runTest {
        val doc = Doc(layoutPage("hello big world"))
        val controller = TextSelectionController(doc, this)
        controller.selectWordAt(0, PagePoint(65f, 6f))
        advanceUntilIdle()

        // Drag the end handle (anchor = start 6) to the end of "world".
        controller.dragSelectionTo(0, anchor = 6, point = PagePoint(148f, 6f))
        assertEquals("big world", controller.selection.value!!.text)

        // Keep dragging the same handle past the anchor, into "hello".
        controller.dragSelectionTo(0, anchor = 6, point = PagePoint(12f, 6f))
        val crossed = controller.selection.value!!
        assertEquals(1, crossed.startIndex)
        assertEquals(6, crossed.endIndex)
        assertEquals("ello ", crossed.text)
        assertEquals(1, doc.loads) // page text is cached
    }

    @Test
    fun selectAllAndClear() = runTest {
        val controller = TextSelectionController(Doc(layoutPage("ab", "cd")), this)
        controller.selectAllOnPage(0)
        advanceUntilIdle()
        assertEquals("ab\ncd", controller.selection.value!!.text)
        assertEquals(2, controller.selection.value!!.rects.size)

        controller.clear()
        assertNull(controller.selection.value)
    }
}
