package io.github.tffy1.pdfviewer.pdf

import io.github.tffy1.pdfviewer.core.model.PagePoint
import io.github.tffy1.pdfviewer.core.model.PageRect
import io.github.tffy1.pdfviewer.core.model.PageSize
import kotlin.math.max
import kotlin.math.min

/**
 * Affine map from PDF user space (y up, before the page's /Rotate, possibly offset by the
 * crop box origin) to page space (points, origin at the top-left of the page as displayed,
 * y down):
 *
 * ```
 * x' = a·x + c·y + e
 * y' = b·x + d·y + f
 * ```
 *
 * Pure Kotlin so it can be unit tested. The engine builds it from Pdfium's own page→device
 * mapping ([fromDeviceProbes]) so text and link geometry always line up with rendering.
 */
data class PageTransform(
    val a: Double,
    val b: Double,
    val c: Double,
    val d: Double,
    val e: Double,
    val f: Double,
) {
    fun mapPoint(x: Float, y: Float): PagePoint = PagePoint(
        (a * x + c * y + e).toFloat(),
        (b * x + d * y + f).toFloat(),
    )

    /**
     * Maps the axis-aligned user-space rectangle spanned by the opposite corners
     * ([x1], [y1]) and ([x2], [y2]) and returns the normalized page-space bounds.
     */
    fun mapRect(x1: Float, y1: Float, x2: Float, y2: Float): PageRect {
        val p1 = mapPoint(x1, y1)
        val p2 = mapPoint(x2, y1)
        val p3 = mapPoint(x1, y2)
        val p4 = mapPoint(x2, y2)
        return PageRect(
            left = min(min(p1.x, p2.x), min(p3.x, p4.x)),
            top = min(min(p1.y, p2.y), min(p3.y, p4.y)),
            right = max(max(p1.x, p2.x), max(p3.x, p4.x)),
            bottom = max(max(p1.y, p2.y), max(p3.y, p4.y)),
        )
    }

    companion object {
        /** Distance (user-space units) of the axis probes from the origin probe. */
        const val PROBE_LENGTH: Double = 1000.0

        private const val MAX_PROBE_SCALE = 100.0

        /** Keeps probe device coordinates far away from Int overflow even for huge pages. */
        private const val MAX_DEVICE_EXTENT = 1.0e7

        /**
         * Device pixels per point to use for probing: Pdfium rounds device coordinates to
         * integers, so a large scale keeps the derived transform precise (1/100 pt).
         */
        fun probeScale(pageSize: PageSize): Double {
            val largest = max(1.0, max(pageSize.width.toDouble(), pageSize.height.toDouble()))
            return min(MAX_PROBE_SCALE, MAX_DEVICE_EXTENT / largest)
        }

        /** Device size (pixels) to request from Pdfium for [pageSize] at [scale]. */
        fun probeDeviceSize(pageSize: PageSize, scale: Double): Pair<Int, Int> = Pair(
            max(1, Math.round(pageSize.width * scale).toInt()),
            max(1, Math.round(pageSize.height * scale).toInt()),
        )

        /**
         * Builds the transform from where Pdfium's page→device mapping (FPDF_PageToDevice with
         * a device rect of `(0, 0) – (pageWidth·scale, pageHeight·scale)` and no extra rotation)
         * puts the user-space points (0, 0), ([PROBE_LENGTH], 0) and (0, [PROBE_LENGTH]).
         * [scale] is the number of device pixels per point.
         */
        fun fromDeviceProbes(
            originX: Int,
            originY: Int,
            xAxisX: Int,
            xAxisY: Int,
            yAxisX: Int,
            yAxisY: Int,
            scale: Double,
        ): PageTransform {
            val unit = PROBE_LENGTH * scale
            return PageTransform(
                a = (xAxisX - originX) / unit,
                b = (xAxisY - originY) / unit,
                c = (yAxisX - originX) / unit,
                d = (yAxisY - originY) / unit,
                e = originX / scale,
                f = originY / scale,
            )
        }
    }
}
