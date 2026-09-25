package io.github.tffy1.pdfviewer.annotations

import io.github.tffy1.pdfviewer.core.model.PagePoint
import io.github.tffy1.pdfviewer.core.model.PageRect
import io.github.tffy1.pdfviewer.core.model.PageSize
import kotlin.math.max
import kotlin.math.min

/** A point in PDF user space (y grows upwards). */
data class UserSpacePoint(val x: Float, val y: Float)

/** An axis-aligned rectangle in PDF user space. */
data class UserSpaceRect(val lowerLeftX: Float, val lowerLeftY: Float, val upperRightX: Float, val upperRightY: Float) {
    val width: Float get() = upperRightX - lowerLeftX
    val height: Float get() = upperRightY - lowerLeftY

    companion object {
        /** Normalizes two arbitrary corners into a rectangle. */
        fun of(x1: Float, y1: Float, x2: Float, y2: Float): UserSpaceRect =
            UserSpaceRect(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))

        fun bounding(points: List<UserSpacePoint>): UserSpaceRect? {
            if (points.isEmpty()) return null
            return UserSpaceRect(
                points.minOf { it.x },
                points.minOf { it.y },
                points.maxOf { it.x },
                points.maxOf { it.y },
            )
        }
    }
}

/**
 * Converts between page space (points, origin at the top-left of the page *as displayed*,
 * y down, see core/model/Geometry.kt) and PDF user space (origin at the bottom-left of the
 * media box, y up) for a page whose visible area is [cropBox] and whose /Rotate is [rotation]
 * (clockwise, any multiple of 90; other values are treated as 0 like PDF readers do).
 *
 * This is the same convention Pdfium uses to report page sizes and text boxes: the displayed
 * page is the crop box rotated clockwise by /Rotate.
 */
class PageTransform(cropBox: UserSpaceRect, rotation: Int) {
    private val box = UserSpaceRect.of(cropBox.lowerLeftX, cropBox.lowerLeftY, cropBox.upperRightX, cropBox.upperRightY)
    val rotation: Int = normalizeRotation(rotation)

    /** Size of the page as displayed (rotation applied). */
    val pageSize: PageSize =
        if (this.rotation == 90 || this.rotation == 270) PageSize(box.height, box.width) else PageSize(box.width, box.height)

    fun toUserSpace(point: PagePoint): UserSpacePoint {
        val w = box.width
        val h = box.height
        // (a, b): offset from the crop box's top-left corner in the unrotated page, y down.
        val a: Float
        val b: Float
        when (rotation) {
            90 -> { a = point.y; b = h - point.x }
            180 -> { a = w - point.x; b = h - point.y }
            270 -> { a = w - point.y; b = point.x }
            else -> { a = point.x; b = point.y }
        }
        return UserSpacePoint(box.lowerLeftX + a, box.upperRightY - b)
    }

    fun toPageSpace(point: UserSpacePoint): PagePoint {
        val w = box.width
        val h = box.height
        val a = point.x - box.lowerLeftX
        val b = box.upperRightY - point.y
        return when (rotation) {
            90 -> PagePoint(h - b, a)
            180 -> PagePoint(w - a, h - b)
            270 -> PagePoint(b, w - a)
            else -> PagePoint(a, b)
        }
    }

    /** Axis-aligned user-space bounds of a page-space rectangle. */
    fun toUserSpace(rect: PageRect): UserSpaceRect {
        val p1 = toUserSpace(PagePoint(rect.left, rect.top))
        val p2 = toUserSpace(PagePoint(rect.right, rect.bottom))
        return UserSpaceRect.of(p1.x, p1.y, p2.x, p2.y)
    }

    /**
     * QuadPoints (8 numbers) for a text line [rect], in the order readers actually use:
     * upper-left, upper-right, lower-left, lower-right — "upper" and "left" as the user sees
     * the page, so the markup follows the text orientation on rotated pages.
     */
    fun quadPoints(rect: PageRect): FloatArray {
        val ul = toUserSpace(PagePoint(rect.left, rect.top))
        val ur = toUserSpace(PagePoint(rect.right, rect.top))
        val ll = toUserSpace(PagePoint(rect.left, rect.bottom))
        val lr = toUserSpace(PagePoint(rect.right, rect.bottom))
        return floatArrayOf(ul.x, ul.y, ur.x, ur.y, ll.x, ll.y, lr.x, lr.y)
    }

    companion object {
        fun normalizeRotation(rotation: Int): Int {
            if (rotation % 90 != 0) return 0
            return ((rotation % 360) + 360) % 360
        }
    }
}
