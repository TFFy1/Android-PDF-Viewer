package io.github.tffy1.pdfviewer.ui.viewer.document

import io.github.tffy1.pdfviewer.core.model.PageSize
import kotlin.math.max
import kotlin.math.min

/**
 * Geometry of the continuous vertical layout at zoom 1, in px. Pages are scaled to fit the
 * viewport width (very tall pages are narrowed so they stay below [DocumentViewDefaults.MAX_PAGE_PX]),
 * centered horizontally and stacked with [gap] px between them and around them.
 *
 * Everything scales linearly with zoom: at zoom z a page's top is `tops[i] * z`.
 * Pure Kotlin and O(pageCount) to build, so 1000+ page documents are cheap.
 */
internal class VerticalPageLayout(
    pageSizes: List<PageSize>,
    val viewportWidth: Float,
    val gap: Float,
) {
    val pageCount: Int = pageSizes.size

    /** Px per point at zoom 1. */
    val scales = FloatArray(pageCount)
    val widths = FloatArray(pageCount)
    val heights = FloatArray(pageCount)
    val lefts = FloatArray(pageCount)
    val tops = DoubleArray(pageCount)

    /** Content height including the leading and trailing gap. */
    val totalHeight: Double

    /** Largest page dimension (px) at zoom 1, used to bound the zoom. */
    val maxPageDimension: Float

    init {
        var y = gap.toDouble()
        var maxDim = 0f
        for (i in 0 until pageCount) {
            val size = pageSizes[i].sanitized()
            val scale = min(viewportWidth / size.width, DocumentViewDefaults.MAX_PAGE_PX / size.height)
            val w = size.width * scale
            val h = size.height * scale
            scales[i] = scale
            widths[i] = w
            heights[i] = h
            lefts[i] = (viewportWidth - w) / 2f
            tops[i] = y
            y += h + gap
            maxDim = max(maxDim, max(w, h))
        }
        totalHeight = if (pageCount == 0) 0.0 else y
        maxPageDimension = maxDim
    }

    fun bottom(page: Int): Double = tops[page] + heights[page]

    /** Index of the last page whose top is at or above [y] (clamped to valid pages). */
    fun pageAt(y: Double): Int {
        if (pageCount == 0) return 0
        var low = 0
        var high = pageCount - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (tops[mid] <= y) low = mid else high = mid - 1
        }
        return low
    }

    /** Pages intersecting the span [top, bottom) (unzoomed px). Empty when none. */
    fun visibleRange(top: Double, bottom: Double): IntRange {
        if (pageCount == 0 || bottom <= top) return IntRange.EMPTY
        var first = pageAt(top)
        if (bottom(first) <= top) first++
        var last = pageAt(bottom)
        if (tops[last] >= bottom) last--
        return if (first > last || first >= pageCount || last < 0) IntRange.EMPTY else first..last
    }

    /**
     * Page with the largest visible height in [top, bottom); pages share the same visible width,
     * so this is the page with the largest visible area. Ties go to the lower index, except when
     * [atEnd] (scrolled to the bottom) and the last page is fully visible: then the last page wins,
     * so reaching the end of a document always reports its last page.
     */
    fun mostVisiblePage(top: Double, bottom: Double, atEnd: Boolean): Int {
        if (pageCount == 0) return 0
        val range = visibleRange(top, bottom)
        if (range.isEmpty()) return pageAt(top)
        val last = pageCount - 1
        if (atEnd && last in range && tops[last] >= top && bottom(last) <= bottom) return last
        var best = range.first
        var bestVisible = -1.0
        for (page in range) {
            val visible = min(bottom, bottom(page)) - max(top, tops[page])
            if (visible > bestVisible) {
                bestVisible = visible
                best = page
            }
        }
        return best
    }
}
