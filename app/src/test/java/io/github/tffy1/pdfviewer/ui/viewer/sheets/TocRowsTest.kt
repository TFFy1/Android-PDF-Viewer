package io.github.tffy1.pdfviewer.ui.viewer.sheets

import io.github.tffy1.pdfviewer.pdf.TocEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TocRowsTest {

    private val toc = listOf(
        TocEntry("Intro", 0),
        TocEntry(
            "Part 1", 2,
            children = listOf(
                TocEntry("Chapter 1", 3),
                TocEntry("Chapter 2", 8, children = listOf(TocEntry("Section 2.1", 9))),
            ),
        ),
        TocEntry("Appendix", 20),
    )

    @Test
    fun flattenToc_showsOnlyExpandedChildren() {
        val collapsed = flattenToc(toc, emptySet())
        assertEquals(listOf("0", "1", "2"), collapsed.map { it.key })
        assertEquals(listOf(false, true, false), collapsed.map { it.hasChildren })

        val expanded = flattenToc(toc, setOf("1", "1/1"))
        assertEquals(listOf("0", "1", "1/0", "1/1", "1/1/0", "2"), expanded.map { it.key })
        assertEquals(listOf(0, 0, 1, 1, 2, 0), expanded.map { it.depth })
        assertEquals(true, expanded[1].expanded)
    }

    @Test
    fun flattenToc_ignoresExpandedKeysOfHiddenParents() {
        val rows = flattenToc(toc, setOf("1/1"))
        assertEquals(listOf("0", "1", "2"), rows.map { it.key })
    }

    @Test
    fun currentTocKey_findsDeepestSectionAtOrBeforePage() {
        assertEquals("0", currentTocKey(toc, 1))
        assertEquals("1/0", currentTocKey(toc, 5))
        assertEquals("1/1/0", currentTocKey(toc, 12))
        assertEquals("2", currentTocKey(toc, 30))
        assertNull(currentTocKey(listOf(TocEntry("Late", 5)), 2))
    }

    @Test
    fun ancestorKeys_listsAllParents() {
        assertEquals(setOf("1", "1/1"), ancestorKeys("1/1/0"))
        assertEquals(emptySet<String>(), ancestorKeys("3"))
    }
}
