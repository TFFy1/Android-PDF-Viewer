package io.github.tffy1.pdfviewer.annotations

import io.github.tffy1.pdfviewer.core.model.PagePoint
import io.github.tffy1.pdfviewer.core.model.PageRect
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Pure geometry helpers for annotations (page space, points). */
object AnnotationGeometry {

    /** Bounding box of an annotation's geometry. Ink bounds include half the stroke width. */
    fun bounds(content: AnnotationContent): PageRect = when (content) {
        is AnnotationContent.Markup ->
            content.rects.reduceOrNull { acc, rect -> acc.union(rect) } ?: PageRect(0f, 0f, 0f, 0f)
        is AnnotationContent.Ink -> {
            var left = Float.POSITIVE_INFINITY
            var top = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            var bottom = Float.NEGATIVE_INFINITY
            for (stroke in content.strokes) {
                val half = stroke.width / 2f
                for (p in stroke.points) {
                    left = min(left, p.x - half)
                    top = min(top, p.y - half)
                    right = max(right, p.x + half)
                    bottom = max(bottom, p.y + half)
                }
            }
            if (left > right) PageRect(0f, 0f, 0f, 0f) else PageRect(left, top, right, bottom)
        }
        is AnnotationContent.Note -> PageRect(content.anchor.x, content.anchor.y, content.anchor.x, content.anchor.y)
    }

    // ---- Stroke processing ---------------------------------------------------------------

    /**
     * Simplifies a polyline with the Ramer–Douglas–Peucker algorithm: points closer than
     * [tolerance] to the simplified line are dropped. Consecutive duplicates are removed first.
     * The first and last points are always kept. Iterative, so long strokes can't overflow the stack.
     */
    fun simplify(points: List<PagePoint>, tolerance: Float): List<PagePoint> {
        val deduped = ArrayList<PagePoint>(points.size)
        for (p in points) {
            if (!p.x.isFinite() || !p.y.isFinite()) continue
            if (deduped.isEmpty() || deduped.last() != p) deduped.add(p)
        }
        if (deduped.size <= 2 || tolerance <= 0f) return deduped

        val keep = BooleanArray(deduped.size)
        keep[0] = true
        keep[deduped.lastIndex] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, deduped.lastIndex))
        while (stack.isNotEmpty()) {
            val (start, end) = stack.removeLast().let { it[0] to it[1] }
            if (end - start < 2) continue
            var maxDistance = -1f
            var index = -1
            for (i in start + 1 until end) {
                val d = distanceToSegment(deduped[i], deduped[start], deduped[end])
                if (d > maxDistance) {
                    maxDistance = d
                    index = i
                }
            }
            if (maxDistance > tolerance) {
                keep[index] = true
                stack.addLast(intArrayOf(start, index))
                stack.addLast(intArrayOf(index, end))
            }
        }
        return deduped.filterIndexed { i, _ -> keep[i] }
    }

    /**
     * Samples the smoothed curve the overlay draws for a stroke: starting at the first point,
     * a quadratic Bézier goes through the midpoints of consecutive segments using each inner
     * point as control point, and the curve ends with a straight line to the last point.
     * Used to export ink (PDF ink lists are polylines) so the copy matches what the user saw.
     */
    fun smoothPolyline(points: List<PagePoint>, segmentsPerCurve: Int = 6): List<PagePoint> {
        if (points.size < 3 || segmentsPerCurve < 1) return points
        val result = ArrayList<PagePoint>(points.size * segmentsPerCurve)
        result.add(points[0])
        var start = points[0]
        for (i in 1 until points.lastIndex) {
            val control = points[i]
            val end = midpoint(points[i], points[i + 1])
            for (s in 1..segmentsPerCurve) {
                val t = s.toFloat() / segmentsPerCurve
                val u = 1f - t
                result.add(
                    PagePoint(
                        u * u * start.x + 2f * u * t * control.x + t * t * end.x,
                        u * u * start.y + 2f * u * t * control.y + t * t * end.y,
                    ),
                )
            }
            start = end
        }
        result.add(points.last())
        return result
    }

    fun midpoint(a: PagePoint, b: PagePoint): PagePoint = PagePoint((a.x + b.x) / 2f, (a.y + b.y) / 2f)

    /** Clamps [point] into the page rectangle [0, width] x [0, height]. */
    fun clampToPage(point: PagePoint, width: Float, height: Float): PagePoint =
        PagePoint(point.x.coerceIn(0f, max(0f, width)), point.y.coerceIn(0f, max(0f, height)))

    // ---- Hit testing ---------------------------------------------------------------------

    fun distanceToSegment(p: PagePoint, a: PagePoint, b: PagePoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0f) return hypot(p.x - a.x, p.y - a.y)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / lengthSquared).coerceIn(0f, 1f)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    /**
     * True when [point] touches [annotation] within [tolerance] points. Notes are hit within
     * [noteRadius] (+ tolerance) of their anchor, since their icon has a fixed on-screen size.
     */
    fun hitTest(annotation: PageAnnotation, point: PagePoint, tolerance: Float, noteRadius: Float): Boolean =
        when (val content = annotation.content) {
            is AnnotationContent.Markup -> content.rects.any { it.inset(-tolerance).contains(point) }
            is AnnotationContent.Ink -> content.strokes.any { strokeHit(it, point, tolerance) }
            is AnnotationContent.Note ->
                hypot(point.x - content.anchor.x, point.y - content.anchor.y) <= noteRadius + tolerance
        }

    private fun strokeHit(stroke: InkStroke, point: PagePoint, tolerance: Float): Boolean {
        val reach = tolerance + stroke.width / 2f
        val pts = stroke.points
        if (pts.isEmpty()) return false
        if (pts.size == 1) return hypot(point.x - pts[0].x, point.y - pts[0].y) <= reach
        for (i in 0 until pts.lastIndex) {
            if (distanceToSegment(point, pts[i], pts[i + 1]) <= reach) return true
        }
        return false
    }

    /**
     * Returns the top-most annotation (the last one drawn) under [point], or null.
     * Notes are preferred because their icon is drawn above everything else.
     */
    fun findHit(
        annotations: List<PageAnnotation>,
        point: PagePoint,
        tolerance: Float,
        noteRadius: Float,
    ): PageAnnotation? =
        annotations.lastOrNull { it.type == AnnotationType.NOTE && hitTest(it, point, tolerance, noteRadius) }
            ?: annotations.lastOrNull { it.type != AnnotationType.NOTE && hitTest(it, point, tolerance, noteRadius) }

    // ---- Text markup lines ---------------------------------------------------------------

    /** Thickness of an underline/strikeout line for a text line of [rect], in points. */
    fun markupLineWidth(rect: PageRect): Float = (rect.height * 0.08f).coerceIn(0.75f, 3f)

    /** Line width used for a whole markup annotation (PDF allows only one per annotation). */
    fun markupLineWidth(rects: List<PageRect>): Float =
        if (rects.isEmpty()) 1f else rects.map { markupLineWidth(it) }.average().toFloat()

    /** Vertical position of an underline: slightly above the bottom, as Acrobat and PdfBox draw it. */
    fun underlineY(rect: PageRect): Float = rect.bottom - rect.height / 7f

    /** Vertical position of a strikeout line. */
    fun strikeoutY(rect: PageRect): Float = rect.centerY
}
