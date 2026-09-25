package io.github.tffy1.pdfviewer.ui.viewer.selection

import io.github.tffy1.pdfviewer.core.model.PagePoint
import io.github.tffy1.pdfviewer.core.model.PageRect
import io.github.tffy1.pdfviewer.pdf.PageText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Lays out [lines] like a text extractor would: 10pt wide chars, 12pt tall lines 20pt apart,
 * lines joined by "\r\n" whose chars have zero-size boxes.
 */
internal fun layoutPage(vararg lines: String, pageIndex: Int = 0): PageText {
    val text = StringBuilder()
    val boxes = ArrayList<PageRect>()
    lines.forEachIndexed { lineIndex, line ->
        if (lineIndex > 0) {
            text.append("\r\n")
            repeat(2) { boxes += PageRect(0f, 0f, 0f, 0f) }
        }
        val top = lineIndex * 20f
        line.forEachIndexed { charIndex, c ->
            text.append(c)
            boxes += PageRect(charIndex * 10f, top, charIndex * 10f + 10f, top + 12f)
        }
    }
    return PageText(pageIndex, text.toString(), boxes)
}

class TextGeometryTest {

    // --- word boundaries ---

    @Test
    fun wordSpanExpandsOverLettersAndDigits() {
        assertEquals(CharSpan(0, 5), wordSpanAt("Hello world", 1))
        assertEquals(CharSpan(6, 11), wordSpanAt("Hello world", 10))
        assertEquals(CharSpan(0, 6), wordSpanAt("abc123 x", 4))
        assertEquals(CharSpan(0, 10), wordSpanAt("snake_case x", 2))
    }

    @Test
    fun wordSpanSelectsPunctuationAlone() {
        assertEquals(CharSpan(5, 6), wordSpanAt("Hello, world", 5))
    }

    @Test
    fun wordSpanIsNullOnWhitespaceOrOutOfRange() {
        assertNull(wordSpanAt("Hello world", 5))
        assertNull(wordSpanAt("a\r\nb", 1))
        assertNull(wordSpanAt("abc", 3))
        assertNull(wordSpanAt("", 0))
    }

    @Test
    fun wordSpanKeepsInnerApostrophesOnly() {
        assertEquals(CharSpan(0, 5), wordSpanAt("don't stop", 0))
        assertEquals(CharSpan(0, 5), wordSpanAt("don’t stop", 4))
        assertEquals(CharSpan(0, 4), wordSpanAt("dogs' toys", 1))
        assertEquals(CharSpan(1, 4), wordSpanAt("'cat'", 2))
    }

    @Test
    fun wordSpanSelectsSingleCjkChar() {
        assertEquals(CharSpan(1, 2), wordSpanAt("日本語", 1))
        assertEquals(CharSpan(2, 3), wordSpanAt("あいう", 2))
        // A latin word next to CJK stops at the ideograph.
        assertEquals(CharSpan(0, 3), wordSpanAt("PDF文書", 0))
    }

    @Test
    fun wordSpanHandlesCombiningMarksAndSurrogatePairs() {
        assertEquals(CharSpan(0, 5), wordSpanAt("café bar", 1))
        val bold = "𝐀𝐁 c" // two supplementary letters, then " c"
        assertEquals(CharSpan(0, 4), wordSpanAt(bold, 1))
        assertEquals(CharSpan(0, 4), wordSpanAt(bold, 2))
    }

    // --- nearest char ---

    @Test
    fun nearestCharFindsCharUnderPoint() {
        val page = layoutPage("ab cd")
        assertEquals(1, nearestCharIndex(page.text, page.charBoxes, PagePoint(15f, 6f), 8f))
        assertEquals(4, nearestCharIndex(page.text, page.charBoxes, PagePoint(45f, 6f), 8f))
    }

    @Test
    fun nearestCharSkipsWhitespaceAndRespectsTolerance() {
        val page = layoutPage("ab cd")
        // On the space: the closest visible glyph is picked.
        val onSpace = nearestCharIndex(page.text, page.charBoxes, PagePoint(22f, 6f), 8f)
        assertEquals(1, onSpace)
        assertEquals(4, nearestCharIndex(page.text, page.charBoxes, PagePoint(55f, 6f), 8f))
        assertNull(nearestCharIndex(page.text, page.charBoxes, PagePoint(70f, 6f), 8f))
        assertNull(nearestCharIndex(page.text, page.charBoxes, PagePoint(10f, 40f), 8f))
    }

    @Test
    fun nearestCharPrefersTheLineUnderThePoint() {
        // Short first line, long second line: a point right of the first line's end but
        // vertically inside it must stay on the first line.
        val page = layoutPage("a", "bbbbb")
        val index = nearestCharIndex(page.text, page.charBoxes, PagePoint(45f, 10f))
        assertEquals(0, index)
    }

    @Test
    fun caretIndexUsesTheCharHalves() {
        val page = layoutPage("abc")
        assertEquals(1, caretIndexAt(page.text, page.charBoxes, PagePoint(12f, 6f)))
        assertEquals(2, caretIndexAt(page.text, page.charBoxes, PagePoint(18f, 6f)))
        assertEquals(3, caretIndexAt(page.text, page.charBoxes, PagePoint(100f, 6f)))
        assertEquals(0, caretIndexAt(page.text, page.charBoxes, PagePoint(-20f, 6f)))
        assertNull(caretIndexAt("", emptyList(), PagePoint(0f, 0f)))
    }

    @Test
    fun spanBetweenCaretsOrdersAndNeverCollapses() {
        assertEquals(CharSpan(2, 5), spanBetweenCarets(anchor = 5, caret = 2, length = 10))
        assertEquals(CharSpan(2, 5), spanBetweenCarets(anchor = 2, caret = 5, length = 10))
        assertEquals(CharSpan(3, 4), spanBetweenCarets(anchor = 3, caret = 3, length = 10))
        assertEquals(CharSpan(9, 10), spanBetweenCarets(anchor = 10, caret = 10, length = 10))
        assertEquals(CharSpan(0, 10), spanBetweenCarets(anchor = -3, caret = 42, length = 10))
        assertNull(spanBetweenCarets(0, 0, 0))
    }

    // --- line merging ---

    @Test
    fun mergeLineRectsProducesOneRectPerLine() {
        val page = layoutPage("ab cd", "ef")
        val rects = mergeLineRects(page.text, page.charBoxes, CharSpan(0, page.text.length))
        assertEquals(
            listOf(PageRect(0f, 0f, 50f, 12f), PageRect(0f, 20f, 20f, 32f)),
            rects,
        )
    }

    @Test
    fun mergeLineRectsCoversOnlyTheSpan() {
        val page = layoutPage("ab cd", "ef")
        // "cd\r\ne"
        val rects = mergeLineRects(page.text, page.charBoxes, CharSpan(3, 8))
        assertEquals(
            listOf(PageRect(30f, 0f, 50f, 12f), PageRect(0f, 20f, 10f, 32f)),
            rects,
        )
    }

    @Test
    fun mergeLineRectsWorksRightToLeft() {
        val text = "abc"
        // Logical order runs right-to-left on the page.
        val boxes = listOf(PageRect(20f, 0f, 30f, 12f), PageRect(10f, 1f, 20f, 12f), PageRect(0f, 0f, 10f, 11f))
        assertEquals(listOf(PageRect(0f, 0f, 30f, 12f)), mergeLineRects(text, boxes, CharSpan(0, 3)))
    }

    @Test
    fun mergeLineRectsIgnoresWhitespaceOnlySpans() {
        val page = layoutPage("ab", "cd")
        assertEquals(emptyList<PageRect>(), mergeLineRects(page.text, page.charBoxes, CharSpan(2, 4)))
    }

    // --- text & selection building ---

    @Test
    fun cleanSelectedTextNormalizesLineBreaksAndDropsControls() {
        assertEquals("a\nb\ncde\tf", cleanSelectedText("a\r\nb\rc\u0002d￾e\tf"))
    }

    @Test
    fun visibleSpanTrimsWhitespace() {
        assertEquals(CharSpan(2, 4), visibleSpan("  ab \r\n"))
        assertNull(visibleSpan(" \r\n "))
    }

    @Test
    fun buildSelectionCarriesTextAndLineRects() {
        val page = layoutPage("ab cd", "ef", pageIndex = 3)
        val selection = buildSelection(page, CharSpan(3, 9))!!
        assertEquals(3, selection.pageIndex)
        assertEquals(3, selection.startIndex)
        assertEquals(9, selection.endIndex)
        assertEquals("cd\nef", selection.text)
        assertEquals(listOf(PageRect(30f, 0f, 50f, 12f), PageRect(0f, 20f, 20f, 32f)), selection.rects)
    }

    @Test
    fun buildSelectionIsNullWithoutVisibleChars() {
        val page = layoutPage("ab", "cd")
        assertNull(buildSelection(page, CharSpan(2, 4)))
    }
}
