package io.github.tffy1.pdfviewer.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSearchTest {
    @Test
    fun caseInsensitiveByDefault() {
        assertEquals(listOf(0..4, 12..16), findTextMatches("Hello there hello", "HELLO"))
    }

    @Test
    fun matchCase() {
        assertEquals(listOf(12..16), findTextMatches("Hello there hello", "hello", matchCase = true))
    }

    @Test
    fun spaceInQueryMatchesLineBreak() {
        val text = "the quick\r\nbrown fox"
        assertEquals(listOf(4..15), findTextMatches(text, "quick brown"))
        assertEquals(listOf(4..15), findTextMatches(text, "  quick   brown "))
    }

    @Test
    fun matchesDoNotOverlap() {
        assertEquals(listOf(0..1, 2..3), findTextMatches("aaaa", "aa"))
    }

    @Test
    fun wholeWord() {
        val text = "cat concat cat_s cats (cat)"
        assertEquals(listOf(0..2, 23..25), findTextMatches(text, "cat", wholeWord = true))
    }

    @Test
    fun wholeWordRetriesAfterRejectedCandidate() {
        // First candidate "ab" inside "aab" is rejected; the real word follows.
        assertEquals(listOf(4..5), findTextMatches("aab ab", "ab", wholeWord = true))
    }

    @Test
    fun wholeWordIgnoresNonWordQueryEdges() {
        assertEquals(listOf(3..5), findTextMatches("foo(x)bar", "(x)", wholeWord = true))
    }

    @Test
    fun blankQueryOrTextFindsNothing() {
        assertEquals(emptyList<IntRange>(), findTextMatches("abc", "   "))
        assertEquals(emptyList<IntRange>(), findTextMatches("", "a"))
    }

    @Test
    fun foldingKeepsIndexesAligned() {
        // "ß".uppercase() would be "SS"; per-char folding must not shift indexes.
        assertEquals(listOf(7..9), findTextMatches("Straße ÉTÉ", "été"))
    }

    @Test
    fun wordChars() {
        assertTrue(isWordChar('a'))
        assertTrue(isWordChar('7'))
        assertTrue(isWordChar('́')) // combining acute accent
        assertFalse(isWordChar(' '))
        assertFalse(isWordChar('-'))
    }
}
