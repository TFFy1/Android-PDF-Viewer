package io.github.tffy1.pdfviewer.ui.viewer.links

import io.github.tffy1.pdfviewer.core.model.PagePoint
import io.github.tffy1.pdfviewer.core.model.PageRect
import io.github.tffy1.pdfviewer.pdf.PdfLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkRulesTest {

    private val big = PdfLink.Internal(PageRect(0f, 0f, 200f, 100f), targetPageIndex = 4)
    private val small = PdfLink.External(PageRect(50f, 40f, 90f, 52f), "https://example.com")

    @Test
    fun hitTestPicksTheSmallestContainingLink() {
        assertSame(small, hitTestLinks(listOf(big, small), PagePoint(60f, 45f)))
        assertSame(big, hitTestLinks(listOf(big, small), PagePoint(150f, 80f)))
    }

    @Test
    fun hitTestAllowsASmallTolerance() {
        val links = listOf(small)
        assertSame(small, hitTestLinks(links, PagePoint(93f, 45f)))
        assertSame(small, hitTestLinks(links, PagePoint(60f, 55.5f)))
        assertNull(hitTestLinks(links, PagePoint(95f, 45f)))
        assertNull(hitTestLinks(emptyList(), PagePoint(0f, 0f)))
    }

    @Test
    fun exactHitWinsOverToleranceHit() {
        val tiny = PdfLink.Internal(PageRect(100f, 110f, 103f, 113f), targetPageIndex = 1)
        // Inside `big` (exactly) and within tolerance of `tiny`.
        assertSame(big, hitTestLinks(listOf(big, tiny), PagePoint(101f, 98f)))
    }

    @Test
    fun hitTestHandlesInvertedRects() {
        val inverted = PdfLink.Internal(PageRect(90f, 52f, 50f, 40f), targetPageIndex = 2)
        assertSame(inverted, hitTestLinks(listOf(inverted), PagePoint(60f, 45f)))
    }

    @Test
    fun allowListAcceptsWebMailAndPhone() {
        assertTrue(isAllowedExternalUri("https://example.com/path?q=1"))
        assertTrue(isAllowedExternalUri("http://example.com"))
        assertTrue(isAllowedExternalUri("mailto:someone@example.com"))
        assertTrue(isAllowedExternalUri("tel:+15550100"))
        assertTrue(isAllowedExternalUri(normalizeExternalUri("HTTPS://Example.com")!!))
    }

    @Test
    fun allowListRejectsDangerousOrUnknownSchemes() {
        assertFalse(isAllowedExternalUri("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(isAllowedExternalUri("file:///sdcard/secret.txt"))
        assertFalse(isAllowedExternalUri("content://com.example/data"))
        assertFalse(isAllowedExternalUri("javascript:alert(1)"))
        assertFalse(isAllowedExternalUri("market://details?id=x"))
        assertFalse(isAllowedExternalUri("example.com/no-scheme"))
        assertFalse(isAllowedExternalUri(""))
        assertFalse(isAllowedExternalUri("https://"))
        assertFalse(isAllowedExternalUri("mailto:"))
    }

    @Test
    fun allowListRejectsControlAndBidiCharacters() {
        assertFalse(isAllowedExternalUri("https://example.com/‮gpj.exe"))
        assertFalse(isAllowedExternalUri("https://example.com/a\nb"))
    }

    @Test
    fun normalizeTrimsLowercasesSchemeAndAddsHttpToBareWww() {
        assertEquals("https://Example.com/A", normalizeExternalUri("  HTTPS://Example.com/A \n"))
        assertEquals("http://www.example.com", normalizeExternalUri("www.example.com"))
        assertEquals("mailto:a@b.c", normalizeExternalUri("MailTo:a@b.c"))
        assertEquals("foo bar", normalizeExternalUri("foo bar"))
        assertNull(normalizeExternalUri("   "))
    }

    @Test
    fun hostIgnoresUserInfoAndPort() {
        assertEquals("example.com", externalUriHost("https://example.com/path"))
        assertEquals("evil.example", externalUriHost("https://bank.com@evil.example/login"))
        assertEquals("evil.example", externalUriHost("https://user:pw@evil.example:8443/"))
        assertEquals("[::1]", externalUriHost("http://[::1]:8080/"))
        assertEquals("example.com", externalUriHost("https://example.com?x=1"))
        assertNull(externalUriHost("mailto:a@b.c"))
        assertNull(externalUriHost("https://"))
        assertNull(externalUriHost("https:example.com"))
    }
}
