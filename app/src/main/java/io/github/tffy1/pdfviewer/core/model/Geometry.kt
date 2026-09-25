package io.github.tffy1.pdfviewer.core.model

/**
 * Geometry shared by every feature. All page-space values are in PDF points
 * (1/72 inch) with the origin at the TOP-LEFT corner of the page as displayed
 * (i.e. after the page's /Rotate has been applied) and y growing downwards.
 *
 * Pure Kotlin on purpose (no android.graphics) so logic using it is unit-testable.
 */
data class PageSize(val width: Float, val height: Float) {
    val aspectRatio: Float get() = if (height == 0f) 1f else width / height
}

data class PagePoint(val x: Float, val y: Float)

data class PageRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun contains(point: PagePoint): Boolean =
        point.x in left..right && point.y in top..bottom

    fun union(other: PageRect): PageRect = PageRect(
        minOf(left, other.left),
        minOf(top, other.top),
        maxOf(right, other.right),
        maxOf(bottom, other.bottom),
    )

    fun inset(dx: Float, dy: Float = dx): PageRect = PageRect(left + dx, top + dy, right - dx, bottom - dy)
}
