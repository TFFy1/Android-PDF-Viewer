package io.github.tffy1.pdfviewer.ui.viewer.document

import io.github.tffy1.pdfviewer.core.model.PageSize
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

/*
 * Pure viewport math used by DocumentViewState. No Android or Compose types so it is unit-tested.
 *
 * Scrolling is expressed per axis as a "scroll" value S: the content coordinate (in zoomed pixels)
 * shown at the viewport's start edge. A content point p is drawn at p - S. Content smaller than the
 * viewport is centered, which is represented by a negative S.
 */

internal object DocumentViewDefaults {
    /** Zoom is relative to "fit": fit-width in vertical mode, fit-page in horizontal mode. */
    const val MIN_ZOOM = 1f
    const val MAX_ZOOM = 8f

    /** Pinching below 1x is allowed while the fingers are down, then it springs back. */
    const val MIN_GESTURE_ZOOM = 0.5f
    const val DOUBLE_TAP_ZOOM = 2.5f

    /**
     * Largest size (px) of a page on screen. Keeps page nodes representable by Compose
     * Constraints (both dimensions must stay below 32767) and bounds tile coordinates.
     */
    const val MAX_PAGE_PX = 32_000f

    /** Base (whole page) bitmaps are at most this wide and this many pixels (16 MB ARGB). */
    const val MAX_BASE_WIDTH = 2048
    const val MAX_BASE_PIXELS = 2048 * 2048

    const val TILE_SIZE = 512
    const val TILE_DEBOUNCE_MS = 120L

    /** Pages composed (and prefetched) before and after the visible ones. */
    const val PREFETCH_PAGES = 1

    /** Render queue priorities, lower runs first. */
    const val PRIORITY_VISIBLE_BASE = 0
    const val PRIORITY_VISIBLE_TILE = 1
    const val PRIORITY_PREFETCH = 2
    const val PRIORITY_HIDDEN_TILE = 3

    /** US Letter, used for pages reporting a broken size. */
    val FALLBACK_PAGE_SIZE = PageSize(612f, 792f)
}

/** Page size with non-finite or non-positive dimensions replaced by a sane default. */
internal fun PageSize.sanitized(): PageSize =
    if (width.isFinite() && height.isFinite() && width > 0f && height > 0f) this
    else DocumentViewDefaults.FALLBACK_PAGE_SIZE

/**
 * Clamps a scroll value to the content. Content smaller than (or equal to) the viewport is
 * centered, which yields a negative (or zero) scroll.
 */
internal fun clampScroll(scroll: Double, contentSize: Double, viewportSize: Double): Double =
    if (contentSize <= viewportSize) {
        (contentSize - viewportSize) / 2.0
    } else {
        scroll.coerceIn(0.0, contentSize - viewportSize)
    }

/**
 * Scroll after the content is rescaled from [oldZoom] to [newZoom] such that the content point
 * under [focus] (viewport px on this axis) stays under it.
 */
internal fun zoomAround(scroll: Double, oldZoom: Float, newZoom: Float, focus: Float): Double =
    (scroll + focus) * (newZoom.toDouble() / oldZoom.toDouble()) - focus

/**
 * Scroll that reveals the content span [start, end]: unchanged when the span is already fully
 * visible, centered when it fits in the viewport, otherwise aligned to its start.
 */
internal fun scrollToReveal(start: Double, end: Double, scroll: Double, viewportSize: Double): Double =
    when {
        start >= scroll && end <= scroll + viewportSize -> scroll
        end - start <= viewportSize -> (start + end) / 2.0 - viewportSize / 2.0
        else -> start
    }

/** Scale (px per point) that fits a page entirely inside a box. */
internal fun fitInsideScale(page: PageSize, boxWidth: Float, boxHeight: Float): Float =
    min(boxWidth / page.width, boxHeight / page.height)

/** Maximum zoom such that a page whose largest dimension at zoom 1 is [maxDimensionPx] stays representable. */
internal fun maxZoomFor(maxDimensionPx: Float): Float =
    if (maxDimensionPx <= 0f) {
        DocumentViewDefaults.MAX_ZOOM
    } else {
        (DocumentViewDefaults.MAX_PAGE_PX / maxDimensionPx)
            .coerceIn(DocumentViewDefaults.MIN_ZOOM, DocumentViewDefaults.MAX_ZOOM)
    }

internal data class PixelSize(val width: Int, val height: Int)

/**
 * Size of the whole-page base bitmap for a page displayed at [width] x [height] px, capped to
 * [maxWidth] and [maxPixels] while keeping the aspect ratio.
 */
internal fun baseRenderSize(
    width: Float,
    height: Float,
    maxWidth: Int = DocumentViewDefaults.MAX_BASE_WIDTH,
    maxPixels: Int = DocumentViewDefaults.MAX_BASE_PIXELS,
): PixelSize {
    val w = width.coerceAtLeast(1f)
    val h = height.coerceAtLeast(1f)
    val factor = minOf(1f, maxWidth / w, sqrt(maxPixels / (w * h)))
    return PixelSize(
        (w * factor).roundToInt().coerceAtLeast(1),
        (h * factor).roundToInt().coerceAtLeast(1),
    )
}

/**
 * Page a horizontal pager settles on after a drag.
 *
 * @param currentPage the page the pager rests on (the page being dragged away from).
 * @param displacementFraction pager offset from [currentPage] in pages; positive shows the next page.
 * @param velocity pager scroll velocity in px/s; positive moves towards the next pages.
 * @param velocityThreshold minimum speed (px/s) that counts as a fling.
 */
internal fun pagerTargetPage(
    currentPage: Int,
    displacementFraction: Float,
    velocity: Float,
    velocityThreshold: Float,
    pageCount: Int,
): Int {
    if (pageCount <= 0) return 0
    val direction = sign(displacementFraction).toInt()
    val target = if (abs(velocity) >= velocityThreshold && direction != 0) {
        // A fling in the drag direction turns the page, a fling back cancels the turn.
        if (sign(velocity).toInt() == direction) currentPage + direction else currentPage
    } else {
        currentPage + displacementFraction.coerceIn(-1f, 1f).roundToInt()
    }
    return target.coerceIn(0, pageCount - 1)
}
