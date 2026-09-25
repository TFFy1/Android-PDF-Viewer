package io.github.tffy1.pdfviewer.ui.viewer.document

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.tffy1.pdfviewer.R
import io.github.tffy1.pdfviewer.data.settings.PageColorMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Color filter applied when drawing page bitmaps (never re-rendered for a mode change). */
internal fun pageColorFilter(mode: PageColorMode): ColorFilter? = when (mode) {
    PageColorMode.NORMAL -> null
    PageColorMode.NIGHT -> ColorFilter.colorMatrix(ColorMatrix(PageColorMatrices.night.copyOf()))
    PageColorMode.SEPIA -> ColorFilter.colorMatrix(ColorMatrix(PageColorMatrices.sepia.copyOf()))
}

private class LoadedBase(val key: RenderKey, val image: ImageBitmap)

/**
 * One page on screen, measured by the parent to exactly its on-screen size. Draws a white
 * placeholder, the whole-page base bitmap scaled to the page, then high-resolution tiles, all
 * through [colorFilter]. The overlay is composed above in a Box covering the page.
 *
 * Only composed while the page is visible or prefetched, and holds no state beyond its bitmaps.
 */
@Composable
internal fun DocumentPage(
    pageIndex: Int,
    state: DocumentViewState,
    renderer: PageRenderer,
    colorFilter: ColorFilter?,
    overlay: @Composable (PageLayoutInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.document_page_description, pageIndex + 1, state.pageCount)

    // Base bitmap: depends on viewport and mode, not on zoom, so pinching never re-renders it.
    val baseKey = state.baseRenderKey(pageIndex)
    // A synchronous cache hit avoids a white frame when a page scrolls back into view.
    val cachedBase = remember(renderer, baseKey) { baseKey?.let(renderer::cached) }
    var loadedBase by remember(renderer) { mutableStateOf<LoadedBase?>(null) }
    LaunchedEffect(renderer, baseKey) {
        if (baseKey == null) return@LaunchedEffect
        val image = cachedBase ?: renderer.render(baseKey) {
            if (state.isPageVisible(pageIndex)) {
                DocumentViewDefaults.PRIORITY_VISIBLE_BASE
            } else {
                DocumentViewDefaults.PRIORITY_PREFETCH
            }
        }
        if (image != null) loadedBase = LoadedBase(baseKey, image)
    }

    // High-resolution tiles while zoomed. A scale change is debounced until the zoom settles;
    // panning at a fixed scale updates the tile set immediately.
    val scope = rememberCoroutineScope()
    val tileLoader = remember(renderer) {
        TileLoader(pageIndex, renderer, scope) {
            if (state.isPageVisible(pageIndex)) {
                DocumentViewDefaults.PRIORITY_VISIBLE_TILE
            } else {
                DocumentViewDefaults.PRIORITY_HIDDEN_TILE
            }
        }
    }
    LaunchedEffect(tileLoader) {
        snapshotFlow { state.tileRequest(pageIndex) }.collectLatest { request ->
            if (request != null && tileLoader.isNewScale(request)) delay(DocumentViewDefaults.TILE_DEBOUNCE_MS)
            tileLoader.update(request)
        }
    }

    Box(
        modifier
            .semantics { contentDescription = description }
            .drawBehind {
                drawRect(color = Color.White, colorFilter = colorFilter)
                val loaded = loadedBase
                val image = if (loaded != null && loaded.key == baseKey) loaded.image else cachedBase ?: loaded?.image
                if (image != null) {
                    drawImage(
                        image = image,
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                        colorFilter = colorFilter,
                    )
                }
                drawTiles(tileLoader.staleTiles, colorFilter)
                drawTiles(tileLoader.tiles, colorFilter)
            },
    ) {
        PageOverlayHost(pageIndex, state, overlay)
    }
}

/** Separate restart scope: only the overlay recomposes when the page scale changes. */
@Composable
private fun PageOverlayHost(
    pageIndex: Int,
    state: DocumentViewState,
    overlay: @Composable (PageLayoutInfo) -> Unit,
) {
    val scale = state.pageScale(pageIndex)
    val info = remember(pageIndex, scale) { PageLayoutInfo(pageIndex, state.pageSize(pageIndex), scale) }
    Box(Modifier.fillMaxSize()) {
        overlay(info)
    }
}

/** Draws tiles scaled from the size they were rendered at to the page's current size. */
private fun DrawScope.drawTiles(tiles: List<LoadedTile>, colorFilter: ColorFilter?) {
    for (tile in tiles) {
        val ratioX = size.width / tile.scaledPageWidth
        val ratioY = size.height / tile.scaledPageHeight
        // Outward rounding makes neighbouring tiles overlap by at most a pixel instead of leaving seams.
        val left = floor(tile.rect.left * ratioX).toInt()
        val top = floor(tile.rect.top * ratioY).toInt()
        val right = ceil(tile.rect.right * ratioX).toInt()
        val bottom = ceil(tile.rect.bottom * ratioY).toInt()
        if (right <= left || bottom <= top) continue
        drawImage(
            image = tile.image,
            dstOffset = IntOffset(left, top),
            dstSize = IntSize(right - left, bottom - top),
            colorFilter = colorFilter,
        )
    }
}
