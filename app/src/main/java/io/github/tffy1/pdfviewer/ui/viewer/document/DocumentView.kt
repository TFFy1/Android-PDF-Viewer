package io.github.tffy1.pdfviewer.ui.viewer.document

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import io.github.tffy1.pdfviewer.core.model.PagePoint
import io.github.tffy1.pdfviewer.core.model.PageRect
import io.github.tffy1.pdfviewer.core.model.PageSize
import io.github.tffy1.pdfviewer.data.settings.PageColorMode
import io.github.tffy1.pdfviewer.data.settings.ScrollMode
import io.github.tffy1.pdfviewer.pdf.PdfDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/*
 * CONTRACT (scaffold). Public signatures in this file are relied on by other features
 * (viewer screen, search, selection, annotations). The owner (document-view agent) may add
 * members and change internals but must keep every declaration below source-compatible.
 */

/** Tap position on a page, in page space (points, top-left origin). */
data class PageTap(val pageIndex: Int, val point: PagePoint)

/**
 * Describes one page as currently laid out, given to [DocumentView]'s pageOverlay slot.
 * The overlay content is placed in a Box exactly covering the page on screen; within
 * that Box, page-space coordinates map to local pixels by multiplying by [scale].
 *
 * A new instance is provided whenever the scale changes (while zooming); plain scrolling keeps
 * the same instance, so overlays don't recompose while the document scrolls.
 */
@Stable
class PageLayoutInfo(
    val pageIndex: Int,
    val pageSize: PageSize,
    /** Local pixels per PDF point inside the overlay Box. */
    val scale: Float,
) {
    fun toLocalX(x: Float): Float = x * scale
    fun toLocalY(y: Float): Float = y * scale
    fun toPagePoint(localX: Float, localY: Float): PagePoint = PagePoint(localX / scale, localY / scale)
}

/** Space between pages (and around them in vertical mode). */
private val PageGap = 8.dp

/** Behind pages in night mode, slightly darker than the inverted paper. */
private val NightBackground = Color(0xFF0B0B0B)

/**
 * Scroll/zoom state of a [DocumentView].
 *
 * Model: in [ScrollMode.VERTICAL] all pages are stacked at fit-width and the whole document is
 * zoomed ([zoom] 1 = fit width, pinch below 1 springs back). In [ScrollMode.HORIZONTAL] every
 * page occupies one viewport-wide slot at fit-page size; [zoom] applies to the current page only
 * and resets when another page is shown.
 *
 * All members must be used from the main thread. Suspend functions animate; a newer call or a
 * touch on the document interrupts a running one (which then throws CancellationException, as
 * Compose scroll states do).
 */
@Stable
class DocumentViewState(
    val pageSizes: List<PageSize>,
    initialPage: Int = 0,
) {
    val pageCount: Int get() = pageSizes.size

    private val sizes: List<PageSize> = pageSizes.map { it.sanitized() }

    // ---- Layout inputs (set by DocumentView) ----
    private var mode by mutableStateOf(ScrollMode.VERTICAL)
    private var viewportWidth by mutableIntStateOf(0)
    private var viewportHeight by mutableIntStateOf(0)
    private var gapPx by mutableIntStateOf(0)
    private var verticalLayout by mutableStateOf(VerticalPageLayout(emptyList(), 0f, 0f), referentialEqualityPolicy())

    /** Page to show once the viewport is known (initial page, or a jump requested before layout). */
    private var pendingPage by mutableStateOf<Int?>(clampPage(initialPage))

    // ---- Position ----
    private var zoomValue by mutableFloatStateOf(1f)

    // Vertical mode: scroll in zoomed px (Double: 1000 pages at 8x exceed Float precision).
    private var scrollX by mutableDoubleStateOf(0.0)
    private var scrollY by mutableDoubleStateOf(0.0)

    // Horizontal mode: pager position in px (page i rests at i * stride), the page the zoom
    // applies to, and that page's scroll inside its slot.
    private var pagerScroll by mutableDoubleStateOf(0.0)
    private var zoomPage by mutableIntStateOf(0)
    private var panX by mutableDoubleStateOf(0.0)
    private var panY by mutableDoubleStateOf(0.0)

    private val mutatorMutex = MutatorMutex()

    private val currentPageState = derivedStateOf(structuralEqualityPolicy()) { computeCurrentPage() }
    private val composedPagesState = derivedStateOf(structuralEqualityPolicy()) { computeComposedPages() }

    /** Index of the page occupying most of the viewport. */
    val currentPage: Int
        get() = currentPageState.value

    /** 1f = fit (fit width in vertical mode, whole page in horizontal mode). */
    val zoom: Float
        get() = zoomValue

    /** When false, pan/scroll/zoom gestures are ignored (e.g. while drawing ink). */
    var gesturesEnabled: Boolean by mutableStateOf(true)

    /** Visible pages plus the prefetched neighbours; the pages DocumentView composes. */
    internal val composedPages: IntRange
        get() = composedPagesState.value

    suspend fun scrollToPage(pageIndex: Int, animate: Boolean = true) {
        if (pageCount == 0) return
        val page = clampPage(pageIndex)
        if (!hasViewport) {
            pendingPage = page
            return
        }
        mutate {
            when {
                !animate -> showPage(page, resetZoom = false)
                mode == ScrollMode.VERTICAL -> animateScrollTo(scrollX, verticalPageScroll(page))
                else -> animatePagerToPage(page)
            }
        }
    }

    /**
     * Scrolls so that [rect] on [pageIndex] is visible (used for search hits and links): nothing
     * moves when it is already fully visible, otherwise it is centered when it fits. The zoom is
     * kept (in horizontal mode, going to another page shows that page at fit).
     */
    suspend fun scrollToRect(pageIndex: Int, rect: PageRect, animate: Boolean = true) {
        if (pageCount == 0) return
        val page = clampPage(pageIndex)
        if (!hasViewport) {
            pendingPage = page
            return
        }
        mutate {
            val margin = 2.0 * gapPx
            val w = viewportWidth.toDouble()
            val h = viewportHeight.toDouble()
            if (mode == ScrollMode.VERTICAL) {
                val layout = verticalLayout
                val z = zoomValue
                val s = layout.scales[page] * z
                val left = layout.lefts[page] * z + rect.left * s
                val top = layout.tops[page] * z + rect.top * s
                val targetX = scrollToReveal(left - margin, left + rect.width * s + margin, scrollX, w)
                val targetY = scrollToReveal(top - margin, top + rect.height * s + margin, scrollY, h)
                if (animate) {
                    animateScrollTo(targetX, targetY)
                } else {
                    scrollX = clampScrollX(targetX)
                    scrollY = clampScrollY(targetY)
                }
            } else if (page != zoomPage || abs(pagerDisplacement()) > 0.5) {
                if (animate) animatePagerToPage(page) else showPage(page, resetZoom = true)
            } else if (zoomValue > 1f) {
                val s = pageScale(page).toDouble()
                val targetX = clampPanX(scrollToReveal(rect.left * s - margin, rect.right * s + margin, panX, w))
                val targetY = clampPanY(scrollToReveal(rect.top * s - margin, rect.bottom * s + margin, panY, h))
                if (animate) {
                    val startX = panX
                    val startY = panY
                    animate(0f, 1f, animationSpec = scrollSpec()) { p, _ ->
                        panX = startX + (targetX - startX) * p
                        panY = startY + (targetY - startY) * p
                    }
                } else {
                    panX = targetX
                    panY = targetY
                }
            }
        }
    }

    suspend fun resetZoom() {
        if (!hasViewport || pageCount == 0) return
        mutate { animateZoomTo(1f, Offset(viewportWidth / 2f, viewportHeight / 2f)) }
    }

    // ---------------------------------------------------------------------------------------
    // Internal API used by DocumentView, its pages and gestures.
    // ---------------------------------------------------------------------------------------

    internal fun setScrollMode(newMode: ScrollMode) {
        if (newMode == mode) return
        val page = currentPage
        mode = newMode
        if (hasViewport && pendingPage == null) showPage(page, resetZoom = true) else pendingPage = page
    }

    /** Called during measurement with the viewport size; keeps the reading position on resize. */
    internal fun onViewportMeasured(width: Int, height: Int, gap: Int) {
        if (width <= 0 || height <= 0) return
        if (width == viewportWidth && height == viewportHeight && gap == gapPx) return
        val anchor = if (hasViewport && pendingPage == null) captureAnchor() else null
        viewportWidth = width
        viewportHeight = height
        gapPx = gap
        verticalLayout = VerticalPageLayout(sizes, width.toFloat(), gap.toFloat())
        if (anchor != null) {
            restoreAnchor(anchor)
        } else {
            val page = pendingPage ?: 0
            pendingPage = null
            showPage(page, resetZoom = true)
        }
    }

    internal fun pageSize(page: Int): PageSize = sizes[page]

    /** On-screen px per point of [page]. */
    internal fun pageScale(page: Int): Float = when (mode) {
        ScrollMode.VERTICAL -> verticalLayout.scales[page] * zoomValue
        ScrollMode.HORIZONTAL -> fitScale(page) * (if (page == zoomPage) zoomValue else 1f)
    }

    internal fun pageConstraints(page: Int): Constraints {
        val scale = pageScale(page)
        val maxPx = DocumentViewDefaults.MAX_PAGE_PX.toInt()
        return Constraints.fixed(
            (sizes[page].width * scale).roundToInt().coerceIn(1, maxPx),
            (sizes[page].height * scale).roundToInt().coerceIn(1, maxPx),
        )
    }

    /** Top-left of [page] on screen, rounded like its placement. */
    internal fun pageOffset(page: Int): IntOffset = IntOffset(pageLeft(page).roundToInt(), pageTop(page).roundToInt())

    internal fun isPageVisible(page: Int): Boolean {
        if (!hasViewport || page !in 0 until pageCount) return false
        val offset = pageOffset(page)
        val scale = pageScale(page)
        return offset.x < viewportWidth && offset.y < viewportHeight &&
            offset.x + sizes[page].width * scale > 0 && offset.y + sizes[page].height * scale > 0
    }

    internal fun hitTest(position: Offset): PageTap? {
        for (page in composedPages) {
            val offset = pageOffset(page)
            val scale = pageScale(page)
            val x = position.x - offset.x
            val y = position.y - offset.y
            if (x >= 0f && y >= 0f && x <= sizes[page].width * scale && y <= sizes[page].height * scale) {
                return PageTap(page, PagePoint(x / scale, y / scale))
            }
        }
        return null
    }

    /** Whole-page bitmap for [page] at fit size (independent of zoom); null before layout. */
    internal fun baseRenderKey(page: Int): RenderKey? {
        if (!hasViewport) return null
        val size = when (mode) {
            ScrollMode.VERTICAL -> baseRenderSize(verticalLayout.widths[page], verticalLayout.heights[page])
            ScrollMode.HORIZONTAL -> {
                val fit = fitScale(page)
                baseRenderSize(sizes[page].width * fit, sizes[page].height * fit)
            }
        }
        return RenderKey.base(page, size)
    }

    /** Tiles needed for [page]: null when the base bitmap is sharp enough or the page is off screen. */
    internal fun tileRequest(page: Int): TileRequest? {
        if (!hasViewport || page !in 0 until pageCount) return null
        val base = baseRenderKey(page) ?: return null
        val scale = pageScale(page)
        val width = (sizes[page].width * scale).roundToInt()
        val height = (sizes[page].height * scale).roundToInt()
        if (width <= base.width * 1.05f) return null
        val offset = pageOffset(page)
        val visible = TileRect(
            left = max(0, -offset.x),
            top = max(0, -offset.y),
            right = min(width, viewportWidth - offset.x),
            bottom = min(height, viewportHeight - offset.y),
        )
        if (visible.width <= 0 || visible.height <= 0) return null
        return TileRequest(width, height, visible)
    }

    /** Applies one step of a drag/pinch: zoom by [zoomChange] around [focus], then move by [pan]. */
    internal fun transform(focus: Offset, pan: Offset, zoomChange: Float) {
        if (!hasViewport || pageCount == 0) return
        if (zoomChange != 1f) {
            if (mode == ScrollMode.HORIZONTAL && zoomValue == 1f) {
                // Not zoomed yet: zoom the page that is mostly on screen.
                val nearest = nearestPagerPage()
                if (nearest != zoomPage) resetPageZoom(nearest)
            }
            setZoomAround(zoomValue * zoomChange, focus)
        }
        panBy(pan, allowPaging = true)
    }

    /** Called when all fingers are up: snaps the pager, springs back from < 1x or flings. */
    internal fun settle(
        velocity: Velocity,
        focus: Offset,
        flingThreshold: Float,
        decay: DecayAnimationSpec<Float>,
        scope: CoroutineScope,
    ) {
        if (!hasViewport || pageCount == 0) return
        val flinging = velocity != Velocity.Zero
        val needed = when (mode) {
            ScrollMode.VERTICAL -> zoomValue < 1f || flinging
            ScrollMode.HORIZONTAL -> abs(pagerDisplacement()) > 0.5 || zoomValue < 1f || (zoomValue > 1f && flinging)
        }
        if (!needed) return
        scope.launch {
            mutate {
                when {
                    mode == ScrollMode.HORIZONTAL && abs(pagerDisplacement()) > 0.5 -> {
                        snapPager(-velocity.x, flingThreshold)
                        if (zoomValue < 1f) animateZoomTo(1f, Offset(viewportWidth / 2f, viewportHeight / 2f))
                    }
                    zoomValue < 1f -> animateZoomTo(1f, focus)
                    else -> fling(velocity, decay)
                }
            }
        }
    }

    /** Double tap: zoom in to [DocumentViewDefaults.DOUBLE_TAP_ZOOM] at [focus], or back to fit. */
    internal suspend fun toggleZoom(focus: Offset) {
        if (!hasViewport || pageCount == 0) return
        if (mode == ScrollMode.HORIZONTAL && abs(pagerDisplacement()) > 0.5) return
        mutate {
            val target = if (zoomValue > 1.05f) 1f else min(DocumentViewDefaults.DOUBLE_TAP_ZOOM, maxZoom())
            animateZoomTo(target, focus)
        }
    }

    internal suspend fun stopAnimation() {
        mutatorMutex.mutate(MutatePriority.UserInput) {}
    }

    /** Mouse wheel: scrolls in vertical mode; turns pages in horizontal mode unless zoomed. */
    internal fun onMouseWheel(delta: Offset, scope: CoroutineScope): Boolean {
        if (!hasViewport || pageCount == 0) return false
        if (mode == ScrollMode.HORIZONTAL && zoomValue <= 1f) {
            val direction = sign(if (delta.y != 0f) delta.y else delta.x).toInt()
            if (direction != 0) scope.launch { scrollToPage(currentPage + direction) }
        } else {
            panBy(-delta, allowPaging = false)
        }
        return true
    }

    internal fun accessibilityScrollValue(): Float = when (mode) {
        ScrollMode.VERTICAL -> scrollY.coerceAtLeast(0.0).toFloat()
        ScrollMode.HORIZONTAL -> pagerScroll.toFloat()
    }

    internal fun accessibilityScrollMax(): Float = when (mode) {
        ScrollMode.VERTICAL -> max(0.0, contentHeight() - viewportHeight).toFloat()
        ScrollMode.HORIZONTAL -> ((pageCount - 1).coerceAtLeast(0) * stride).toFloat()
    }

    /** Accessibility scroll action: moves a screen in vertical mode, one page in horizontal mode. */
    internal suspend fun accessibilityScrollBy(x: Float, y: Float) {
        if (!hasViewport || pageCount == 0) return
        if (mode == ScrollMode.VERTICAL) {
            mutate { animateScrollTo(scrollX + x, scrollY + y) }
        } else {
            val direction = sign(if (x != 0f) x else y).toInt()
            if (direction != 0) scrollToPage(currentPage + direction)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Geometry
    // ---------------------------------------------------------------------------------------

    private val hasViewport: Boolean get() = viewportWidth > 0 && viewportHeight > 0
    private val stride: Double get() = (viewportWidth + gapPx).toDouble()

    private fun clampPage(page: Int): Int = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))

    private fun maxZoom(): Float = when (mode) {
        ScrollMode.VERTICAL -> maxZoomFor(verticalLayout.maxPageDimension)
        ScrollMode.HORIZONTAL -> {
            val fit = fitScale(zoomPage)
            maxZoomFor(fit * max(sizes[zoomPage].width, sizes[zoomPage].height))
        }
    }

    // Vertical
    private fun contentWidth(): Double = viewportWidth.toDouble() * zoomValue
    private fun contentHeight(): Double = verticalLayout.totalHeight * zoomValue
    private fun clampScrollX(x: Double) = clampScroll(x, contentWidth(), viewportWidth.toDouble())
    private fun clampScrollY(y: Double) = clampScroll(y, contentHeight(), viewportHeight.toDouble())

    /** Scroll that puts [page] at the top, showing the gap above it. */
    private fun verticalPageScroll(page: Int): Double = (verticalLayout.tops[page] - gapPx) * zoomValue

    // Horizontal
    private fun fitScale(page: Int): Float = fitInsideScale(sizes[page], viewportWidth.toFloat(), viewportHeight.toFloat())
    private fun zoomedPageWidth(): Double = sizes[zoomPage].width.toDouble() * fitScale(zoomPage) * zoomValue
    private fun zoomedPageHeight(): Double = sizes[zoomPage].height.toDouble() * fitScale(zoomPage) * zoomValue
    private fun clampPanX(x: Double) = clampScroll(x, zoomedPageWidth(), viewportWidth.toDouble())
    private fun clampPanY(y: Double) = clampScroll(y, zoomedPageHeight(), viewportHeight.toDouble())
    private fun pagerDisplacement(): Double = pagerScroll - zoomPage * stride
    private fun nearestPagerPage(): Int = clampPage((pagerScroll / stride).roundToInt())

    private fun resetPageZoom(page: Int) {
        zoomPage = page
        zoomValue = 1f
        panX = clampPanX(0.0)
        panY = clampPanY(0.0)
    }

    private fun pageLeft(page: Int): Double = when (mode) {
        ScrollMode.VERTICAL -> verticalLayout.lefts[page].toDouble() * zoomValue - scrollX
        ScrollMode.HORIZONTAL -> {
            val slot = page * stride - pagerScroll
            if (page == zoomPage) {
                slot - panX
            } else {
                slot + (viewportWidth - sizes[page].width * fitScale(page)) / 2.0
            }
        }
    }

    private fun pageTop(page: Int): Double = when (mode) {
        ScrollMode.VERTICAL -> verticalLayout.tops[page] * zoomValue - scrollY
        ScrollMode.HORIZONTAL -> if (page == zoomPage) {
            -panY
        } else {
            (viewportHeight - sizes[page].height * fitScale(page)) / 2.0
        }
    }

    private fun computeCurrentPage(): Int {
        if (pageCount == 0) return 0
        pendingPage?.let { return it }
        if (!hasViewport) return 0
        return when (mode) {
            ScrollMode.VERTICAL -> {
                val z = zoomValue.toDouble()
                val contentHeight = contentHeight()
                val atEnd = contentHeight > viewportHeight && scrollY >= contentHeight - viewportHeight - 1.0
                verticalLayout.mostVisiblePage(scrollY / z, (scrollY + viewportHeight) / z, atEnd)
            }
            ScrollMode.HORIZONTAL -> nearestPagerPage()
        }
    }

    private fun computeComposedPages(): IntRange {
        if (!hasViewport || pageCount == 0) return IntRange.EMPTY
        val visible = when (mode) {
            ScrollMode.VERTICAL -> {
                val z = zoomValue.toDouble()
                verticalLayout.visibleRange(scrollY / z, (scrollY + viewportHeight) / z)
            }
            ScrollMode.HORIZONTAL -> {
                val first = floor(pagerScroll / stride).toInt()
                val last = if (pagerScroll - first * stride > 0.5) first + 1 else first
                first..last
            }
        }
        if (visible.isEmpty()) return IntRange.EMPTY
        val prefetch = DocumentViewDefaults.PREFETCH_PAGES
        val first = max(0, visible.first - prefetch)
        val last = min(pageCount - 1, visible.last + prefetch)
        return if (first > last) IntRange.EMPTY else first..last
    }

    // ---------------------------------------------------------------------------------------
    // Movement
    // ---------------------------------------------------------------------------------------

    private suspend fun mutate(block: suspend () -> Unit) {
        mutatorMutex.mutate(MutatePriority.UserInput, block)
    }

    /** Jumps to [page] without animation. */
    private fun showPage(page: Int, resetZoom: Boolean) {
        val target = clampPage(page)
        when (mode) {
            ScrollMode.VERTICAL -> {
                if (resetZoom) zoomValue = 1f
                zoomValue = zoomValue.coerceIn(DocumentViewDefaults.MIN_ZOOM, maxZoom())
                scrollX = clampScrollX(if (resetZoom) 0.0 else scrollX)
                scrollY = clampScrollY(verticalPageScroll(target))
            }
            ScrollMode.HORIZONTAL -> {
                resetPageZoom(target)
                pagerScroll = target * stride
            }
        }
    }

    /** Sets the zoom (clamped to the gesture range) keeping the content under [focus] in place. */
    private fun setZoomAround(target: Float, focus: Offset) {
        val oldZoom = zoomValue
        val newZoom = target.coerceIn(DocumentViewDefaults.MIN_GESTURE_ZOOM, maxZoom())
        if (newZoom == oldZoom) return
        when (mode) {
            ScrollMode.VERTICAL -> {
                val x = zoomAround(scrollX, oldZoom, newZoom, focus.x)
                val y = zoomAround(scrollY, oldZoom, newZoom, focus.y)
                zoomValue = newZoom
                scrollX = clampScrollX(x)
                scrollY = clampScrollY(y)
            }
            ScrollMode.HORIZONTAL -> {
                val slotLeft = (zoomPage * stride - pagerScroll).toFloat()
                val x = zoomAround(panX, oldZoom, newZoom, focus.x - slotLeft)
                val y = zoomAround(panY, oldZoom, newZoom, focus.y)
                zoomValue = newZoom
                panX = clampPanX(x)
                panY = clampPanY(y)
            }
        }
    }

    /** Moves the content by [pan] (finger movement); returns the movement actually applied. */
    private fun panBy(pan: Offset, allowPaging: Boolean): Offset {
        if (mode == ScrollMode.VERTICAL) {
            val x = clampScrollX(scrollX - pan.x)
            val y = clampScrollY(scrollY - pan.y)
            val consumed = Offset((scrollX - x).toFloat(), (scrollY - y).toFloat())
            scrollX = x
            scrollY = y
            return consumed
        }
        var dx = pan.x.toDouble()
        var consumedX = 0.0
        // 1. A pager dragged away from the zoomed page first moves back to it.
        val displacement = pagerDisplacement()
        if ((displacement > 0 && dx > 0) || (displacement < 0 && dx < 0)) {
            val take = if (dx > 0) min(dx, displacement) else max(dx, displacement)
            pagerScroll = if (take == displacement) zoomPage * stride else pagerScroll - take
            dx -= take
            consumedX += take
        }
        // 2. Then the zoomed page pans inside its slot.
        if (abs(pagerDisplacement()) < 0.5) {
            val x = clampPanX(panX - dx)
            val moved = panX - x
            panX = x
            dx -= moved
            consumedX += moved
        }
        // 3. Whatever is left (page edge reached, or not zoomed) drags the pager, one page at most.
        if (allowPaging && dx != 0.0) {
            val minScroll = max(0, zoomPage - 1) * stride
            val maxScroll = min(pageCount - 1, zoomPage + 1) * stride
            val newScroll = (pagerScroll - dx).coerceIn(minScroll, max(minScroll, maxScroll))
            consumedX += pagerScroll - newScroll
            pagerScroll = newScroll
        }
        val y = clampPanY(panY - pan.y)
        val consumedY = panY - y
        panY = y
        return Offset(consumedX.toFloat(), consumedY.toFloat())
    }

    private suspend fun fling(velocity: Velocity, decay: DecayAnimationSpec<Float>) {
        val speed = hypot(velocity.x, velocity.y)
        if (speed < 1f) return
        val directionX = velocity.x / speed
        val directionY = velocity.y / speed
        var last = 0f
        AnimationState(initialValue = 0f, initialVelocity = speed).animateDecay(decay) {
            val delta = value - last
            last = value
            val consumed = panBy(Offset(directionX * delta, directionY * delta), allowPaging = false)
            // Stop at the edges instead of spinning invisibly until the decay ends.
            if (abs(delta) >= 1f && abs(consumed.x) < 0.5f && abs(consumed.y) < 0.5f) cancelAnimation()
        }
    }

    private suspend fun animateZoomTo(target: Float, focus: Offset) {
        val start = zoomValue
        if (abs(start - target) < 0.001f) return
        animate(start, target, animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)) { value, _ ->
            setZoomAround(value, focus)
        }
    }

    /** Vertical mode: animated scroll; far jumps land one screen before the target first. */
    private suspend fun animateScrollTo(targetX: Double, targetY: Double) {
        val endX = clampScrollX(targetX)
        val endY = clampScrollY(targetY)
        val distance = endY - scrollY
        if (abs(distance) > 2.0 * viewportHeight) scrollY = endY - sign(distance) * viewportHeight
        val startX = scrollX
        val startY = scrollY
        animate(0f, 1f, animationSpec = scrollSpec()) { p, _ ->
            scrollX = startX + (endX - startX) * p
            scrollY = startY + (endY - startY) * p
        }
    }

    /** Horizontal mode: slides to [page] (at fit); far jumps start from the adjacent page. */
    private suspend fun animatePagerToPage(page: Int) {
        if (page == zoomPage && abs(pagerDisplacement()) <= 0.5) return
        val target = page * stride
        if (abs(pagerScroll - target) > 1.5 * stride) {
            pagerScroll = target - sign(target - pagerScroll) * stride
        }
        resetPageZoom(page)
        animatePager(target, velocity = 0f, spec = scrollSpec())
    }

    /** Horizontal mode: settles a dragged pager on the page chosen from offset and velocity. */
    private suspend fun snapPager(velocity: Float, flingThreshold: Float) {
        val fraction = (pagerDisplacement() / stride).toFloat()
        val targetPage = pagerTargetPage(zoomPage, fraction, velocity, flingThreshold, pageCount)
        animatePager(targetPage * stride, velocity, spring(stiffness = Spring.StiffnessMediumLow))
        if (targetPage != zoomPage) resetPageZoom(targetPage)
    }

    private suspend fun animatePager(target: Double, velocity: Float, spec: AnimationSpec<Float>) {
        val start = pagerScroll
        animate(0f, (target - start).toFloat(), initialVelocity = velocity, animationSpec = spec) { value, _ ->
            pagerScroll = start + value
        }
        pagerScroll = target
    }

    private fun scrollSpec(): AnimationSpec<Float> = tween(durationMillis = 350, easing = FastOutSlowInEasing)

    // ---------------------------------------------------------------------------------------
    // Resize anchoring
    // ---------------------------------------------------------------------------------------

    private class ViewAnchor(val page: Int, val fractionY: Double, val centerFractionX: Double)

    private fun captureAnchor(): ViewAnchor = when (mode) {
        ScrollMode.VERTICAL -> {
            val layout = verticalLayout
            val top = scrollY / zoomValue
            val page = layout.pageAt(top)
            val height = layout.heights[page].toDouble()
            ViewAnchor(
                page = page,
                fractionY = if (height > 0) (top - layout.tops[page]) / height else 0.0,
                centerFractionX = (scrollX + viewportWidth / 2.0) / contentWidth(),
            )
        }
        ScrollMode.HORIZONTAL -> ViewAnchor(currentPage, 0.0, 0.5)
    }

    private fun restoreAnchor(anchor: ViewAnchor) {
        when (mode) {
            ScrollMode.VERTICAL -> {
                val layout = verticalLayout
                zoomValue = zoomValue.coerceIn(DocumentViewDefaults.MIN_ZOOM, maxZoom())
                val page = anchor.page
                scrollY = clampScrollY((layout.tops[page] + anchor.fractionY * layout.heights[page]) * zoomValue)
                scrollX = clampScrollX(anchor.centerFractionX * contentWidth() - viewportWidth / 2.0)
            }
            ScrollMode.HORIZONTAL -> showPage(anchor.page, resetZoom = true)
        }
    }

    internal companion object {
        /** Saves the current page so it survives process death. */
        fun saver(pageSizes: List<PageSize>): Saver<DocumentViewState, Int> = Saver(
            save = { it.currentPage },
            restore = { DocumentViewState(pageSizes, it) },
        )
    }
}

@Composable
fun rememberDocumentViewState(pageSizes: List<PageSize>, initialPage: Int = 0): DocumentViewState =
    rememberSaveable(pageSizes, saver = DocumentViewState.saver(pageSizes)) {
        DocumentViewState(pageSizes, initialPage)
    }

/**
 * Renders a document with continuous vertical scrolling or horizontal paging,
 * pinch/double-tap zoom and progressive high-resolution tiles.
 *
 * Only visible pages (plus one on each side, prefetched) are composed, so documents with
 * thousands of pages scroll smoothly. Page bitmaps are cached per document; [pageColorMode] is
 * applied as a color filter at draw time.
 *
 * Touch handling: the pan/zoom/tap detectors sit on this view, above the pages in the hierarchy,
 * so [pageOverlay] content (a descendant) receives each pointer event first. An overlay takes
 * over a gesture by consuming its events: consumed moves stop panning/zooming and consumed
 * downs/ups cancel taps. Unconsumed events fall through to the document, so overlays should only
 * consume what they handle. While [DocumentViewState.gesturesEnabled] is false the document
 * handles no gestures at all (not even taps).
 *
 * @param onTap single tap; null when the tap is outside any page. Delayed by the double-tap
 *   timeout; a double tap zooms and never produces a single tap.
 * @param onLongPress long press on a page (used to start text selection). Not reported during
 *   a multi-finger gesture.
 * @param pageOverlay drawn above each visible page, see [PageLayoutInfo].
 */
@Composable
fun DocumentView(
    document: PdfDocument,
    state: DocumentViewState,
    scrollMode: ScrollMode,
    pageColorMode: PageColorMode,
    modifier: Modifier = Modifier,
    onTap: (PageTap?) -> Unit = {},
    onLongPress: (PageTap) -> Unit = {},
    pageOverlay: @Composable (PageLayoutInfo) -> Unit = {},
) {
    val renderer = remember(document) { PageRenderer(document) }
    DisposableEffect(renderer) {
        onDispose { renderer.clear() }
    }
    SideEffect { state.setScrollMode(scrollMode) }

    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Float>()
    val flags = remember { GestureFlags() }
    val currentOnTap = rememberUpdatedState(onTap)
    val currentOnLongPress = rememberUpdatedState(onLongPress)
    val colorFilter = remember(pageColorMode) { pageColorFilter(pageColorMode) }
    val background = if (pageColorMode == PageColorMode.NIGHT) {
        NightBackground
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val gestures = if (state.gesturesEnabled) {
        Modifier.documentGestures(state, scope, decay, flags, currentOnTap, currentOnLongPress)
    } else {
        Modifier
    }
    val pages = state.composedPages

    Layout(
        content = {
            for (page in pages) {
                key(page) {
                    DocumentPage(
                        pageIndex = page,
                        state = state,
                        renderer = renderer,
                        colorFilter = colorFilter,
                        overlay = pageOverlay,
                        modifier = Modifier.layoutId(page),
                    )
                }
            }
        },
        modifier = modifier
            .clipToBounds()
            .background(background)
            .then(gestures)
            .semantics {
                val range = ScrollAxisRange(
                    value = { state.accessibilityScrollValue() },
                    maxValue = { state.accessibilityScrollMax() },
                )
                if (scrollMode == ScrollMode.VERTICAL) {
                    verticalScrollAxisRange = range
                } else {
                    horizontalScrollAxisRange = range
                }
                scrollBy { x, y ->
                    scope.launch { state.accessibilityScrollBy(x, y) }
                    true
                }
            },
    ) { measurables, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
        state.onViewportMeasured(width, height, PageGap.roundToPx())
        // Sizes depend on zoom only; positions (read in the placement block) on scroll, so
        // scrolling only re-places pages.
        val placeables = measurables.map { measurable ->
            val page = measurable.layoutId as Int
            page to measurable.measure(state.pageConstraints(page))
        }
        layout(width, height) {
            for ((page, placeable) in placeables) placeable.place(state.pageOffset(page))
        }
    }
}
