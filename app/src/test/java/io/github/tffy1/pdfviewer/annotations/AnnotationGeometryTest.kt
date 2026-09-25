package io.github.tffy1.pdfviewer.annotations

import io.github.tffy1.pdfviewer.core.model.PagePoint
import io.github.tffy1.pdfviewer.core.model.PageRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class AnnotationGeometryTest {

    // ---- Simplification ----------------------------------------------------------------

    @Test
    fun simplifyDropsCollinearPoints() {
        val line = (0..100).map { PagePoint(it.toFloat(), it * 0.5f) }
        assertEquals(listOf(PagePoint(0f, 0f), PagePoint(100f, 50f)), AnnotationGeometry.simplify(line, 0.1f))
    }

    @Test
    fun simplifyKeepsCorners() {
        val points = (0..50).map { PagePoint(it.toFloat(), 0f) } + (1..50).map { PagePoint(50f, it.toFloat()) }
        assertEquals(
            listOf(PagePoint(0f, 0f), PagePoint(50f, 0f), PagePoint(50f, 50f)),
            AnnotationGeometry.simplify(points, 0.5f),
        )
    }

    @Test
    fun simplifiedCurveStaysWithinTolerance() {
        val circle = (0..720).map {
            val a = it / 720.0 * 2 * PI
            PagePoint((100 + 50 * cos(a)).toFloat(), (100 + 50 * sin(a)).toFloat())
        }
        val tolerance = 0.5f
        val simplified = AnnotationGeometry.simplify(circle, tolerance)
        assertTrue("should shrink a lot, was ${simplified.size}", simplified.size < circle.size / 5)
        assertEquals(circle.first(), simplified.first())
        assertEquals(circle.last(), simplified.last())
        for (p in circle) {
            val distance = simplified.zipWithNext().minOf { (a, b) -> AnnotationGeometry.distanceToSegment(p, a, b) }
            assertTrue(distance <= tolerance + 1e-3f)
        }
    }

    @Test
    fun simplifyRemovesDuplicatesAndNonFinitePoints() {
        val points = listOf(PagePoint(1f, 1f), PagePoint(1f, 1f), PagePoint(Float.NaN, 2f), PagePoint(1f, 1f))
        assertEquals(listOf(PagePoint(1f, 1f)), AnnotationGeometry.simplify(points, 0.5f))
        assertEquals(emptyList<PagePoint>(), AnnotationGeometry.simplify(emptyList(), 0.5f))
    }

    @Test
    fun simplifyHandlesVeryLongStrokes() {
        // Zig-zag that keeps every point: must not overflow the stack.
        val points = (0 until 10_000).map { PagePoint(it.toFloat(), if (it % 2 == 0) 0f else 10f) }
        assertEquals(points.size, AnnotationGeometry.simplify(points, 1f).size)
    }

    // ---- Smoothing ---------------------------------------------------------------------

    @Test
    fun smoothPolylineKeepsEndpointsAndPassesThroughMidpoints() {
        val points = listOf(PagePoint(0f, 0f), PagePoint(10f, 0f), PagePoint(10f, 10f), PagePoint(20f, 10f))
        val smooth = AnnotationGeometry.smoothPolyline(points, segmentsPerCurve = 4)
        assertEquals(points.first(), smooth.first())
        assertEquals(points.last(), smooth.last())
        assertTrue(smooth.contains(PagePoint(10f, 5f)))
        assertEquals(1 + 2 * 4 + 1, smooth.size)
    }

    @Test
    fun smoothPolylineLeavesShortStrokesAlone() {
        val two = listOf(PagePoint(0f, 0f), PagePoint(5f, 5f))
        assertEquals(two, AnnotationGeometry.smoothPolyline(two))
    }

    // ---- Hit testing -------------------------------------------------------------------

    private fun ink(id: Long, vararg points: PagePoint, width: Float = 2f) = PageAnnotation(
        id = id,
        pageIndex = 0,
        type = AnnotationType.INK,
        color = 0,
        content = AnnotationContent.Ink(listOf(InkStroke(points.toList(), width))),
    )

    private fun markup(id: Long, rect: PageRect) = PageAnnotation(
        id = id,
        pageIndex = 0,
        type = AnnotationType.HIGHLIGHT,
        color = 0,
        content = AnnotationContent.Markup(listOf(rect)),
    )

    private fun note(id: Long, anchor: PagePoint) = PageAnnotation(
        id = id,
        pageIndex = 0,
        type = AnnotationType.NOTE,
        color = 0,
        content = AnnotationContent.Note(anchor),
    )

    @Test
    fun distanceToSegment() {
        val a = PagePoint(0f, 0f)
        val b = PagePoint(10f, 0f)
        assertEquals(5f, AnnotationGeometry.distanceToSegment(PagePoint(5f, 5f), a, b), 1e-4f)
        assertEquals(5f, AnnotationGeometry.distanceToSegment(PagePoint(15f, 0f), a, b), 1e-4f)
        assertEquals(5f, AnnotationGeometry.distanceToSegment(PagePoint(3f, 4f), a, a), 1e-4f)
    }

    @Test
    fun inkHitUsesToleranceAndStrokeWidth() {
        val stroke = ink(1, PagePoint(0f, 0f), PagePoint(100f, 0f), width = 4f)
        assertTrue(AnnotationGeometry.hitTest(stroke, PagePoint(50f, 6.9f), 5f, 0f))
        assertFalse(AnnotationGeometry.hitTest(stroke, PagePoint(50f, 7.1f), 5f, 0f))
        assertFalse(AnnotationGeometry.hitTest(stroke, PagePoint(110f, 0f), 5f, 0f))
    }

    @Test
    fun markupHitInsideRectOrWithinTolerance() {
        val highlight = markup(1, PageRect(10f, 10f, 50f, 20f))
        assertTrue(AnnotationGeometry.hitTest(highlight, PagePoint(30f, 15f), 0f, 0f))
        assertTrue(AnnotationGeometry.hitTest(highlight, PagePoint(52f, 15f), 3f, 0f))
        assertFalse(AnnotationGeometry.hitTest(highlight, PagePoint(55f, 15f), 3f, 0f))
    }

    @Test
    fun noteHitUsesIconRadius() {
        val n = note(1, PagePoint(100f, 100f))
        assertTrue(AnnotationGeometry.hitTest(n, PagePoint(108f, 100f), 2f, 7f))
        assertFalse(AnnotationGeometry.hitTest(n, PagePoint(110f, 100f), 2f, 7f))
    }

    @Test
    fun findHitReturnsTopMostAndPrefersNotes() {
        val bottom = markup(1, PageRect(0f, 0f, 100f, 100f))
        val top = ink(2, PagePoint(0f, 50f), PagePoint(100f, 50f))
        val n = note(3, PagePoint(50f, 50f))
        assertSame(top, AnnotationGeometry.findHit(listOf(bottom, top), PagePoint(50f, 50f), 2f, 5f))
        assertSame(bottom, AnnotationGeometry.findHit(listOf(bottom, top), PagePoint(50f, 20f), 2f, 5f))
        assertSame(n, AnnotationGeometry.findHit(listOf(n, bottom, top), PagePoint(50f, 50f), 2f, 5f))
        assertNull(AnnotationGeometry.findHit(listOf(bottom, top), PagePoint(200f, 200f), 2f, 5f))
    }

    @Test
    fun boundsIncludeHalfStrokeWidth() {
        val stroke = ink(1, PagePoint(10f, 10f), PagePoint(20f, 30f), width = 4f)
        assertEquals(PageRect(8f, 8f, 22f, 32f), stroke.bounds)
    }

    @Test
    fun markupLinesStayWithinTheTextLine() {
        val rect = PageRect(0f, 100f, 50f, 114f)
        assertTrue(AnnotationGeometry.underlineY(rect) in rect.top..rect.bottom)
        assertEquals(107f, AnnotationGeometry.strikeoutY(rect), 1e-4f)
        assertEquals(1.12f, AnnotationGeometry.markupLineWidth(rect), 1e-4f)
        assertEquals(0.75f, AnnotationGeometry.markupLineWidth(PageRect(0f, 0f, 1f, 1f)), 1e-4f)
        assertEquals(3f, AnnotationGeometry.markupLineWidth(PageRect(0f, 0f, 1f, 100f)), 1e-4f)
    }

    @Test
    fun clampToPage() {
        assertEquals(PagePoint(0f, 50f), AnnotationGeometry.clampToPage(PagePoint(-5f, 50f), 100f, 200f))
        assertEquals(PagePoint(100f, 200f), AnnotationGeometry.clampToPage(PagePoint(150f, 250f), 100f, 200f))
    }
}
