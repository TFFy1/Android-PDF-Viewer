package io.github.tffy1.pdfviewer.ui.viewer.document

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.tffy1.pdfviewer.core.model.PagePoint
import io.github.tffy1.pdfviewer.core.model.PageRect
import io.github.tffy1.pdfviewer.core.model.PageSize
import io.github.tffy1.pdfviewer.data.settings.PageColorMode
import io.github.tffy1.pdfviewer.data.settings.ScrollMode
import io.github.tffy1.pdfviewer.pdf.PdfDocument

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

@Stable
class DocumentViewState(
    val pageSizes: List<PageSize>,
    initialPage: Int = 0,
) {
    /** Index of the page occupying most of the viewport. */
    val currentPage: Int
        get() = TODO("document-view agent")

    /** 1f = fit width. */
    val zoom: Float
        get() = TODO("document-view agent")

    /** When false, pan/scroll/zoom gestures are ignored (e.g. while drawing ink). */
    var gesturesEnabled: Boolean = true

    suspend fun scrollToPage(pageIndex: Int, animate: Boolean = true) {
        TODO("document-view agent")
    }

    /** Scrolls so that [rect] on [pageIndex] is visible (used for search hits and links). */
    suspend fun scrollToRect(pageIndex: Int, rect: PageRect, animate: Boolean = true) {
        TODO("document-view agent")
    }

    suspend fun resetZoom() {
        TODO("document-view agent")
    }
}

@Composable
fun rememberDocumentViewState(pageSizes: List<PageSize>, initialPage: Int = 0): DocumentViewState =
    remember(pageSizes) { DocumentViewState(pageSizes, initialPage) }

/**
 * Renders a document with continuous vertical scrolling or horizontal paging,
 * pinch/double-tap zoom and progressive high-resolution tiles.
 *
 * @param onTap single tap; null when the tap is outside any page.
 * @param onLongPress long press on a page (used to start text selection).
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
    TODO("document-view agent")
}
