package io.github.tffy1.pdfviewer.ui.viewer.sheets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.tffy1.pdfviewer.R
import io.github.tffy1.pdfviewer.core.model.PageSize
import io.github.tffy1.pdfviewer.data.settings.PageColorMode

/** Grid of page thumbnails; the current page is highlighted and scrolled into view. */
@Composable
fun ThumbnailsSheet(
    pageSizes: List<PageSize>,
    currentPage: Int,
    cache: PageThumbnailCache,
    pageColorMode: PageColorMode,
    onSelectPage: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colorFilter = pageColorFilter(pageColorMode)
    ViewerBottomSheet(title = stringResource(R.string.viewer_menu_pages), onDismissRequest = onDismiss) { hide ->
        val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = currentPage)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 96.dp),
            state = gridState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(count = pageSizes.size, key = { it }) { page ->
                PageThumbnail(
                    pageIndex = page,
                    pageSize = pageSizes[page],
                    isCurrent = page == currentPage,
                    cache = cache,
                    colorFilter = colorFilter,
                    onClick = {
                        onSelectPage(page)
                        hide()
                    },
                )
            }
        }
    }
}

@Composable
private fun PageThumbnail(
    pageIndex: Int,
    pageSize: PageSize,
    isCurrent: Boolean,
    cache: PageThumbnailCache,
    colorFilter: ColorFilter?,
    onClick: () -> Unit,
) {
    val bitmap: ImageBitmap? by produceState(cache.peek(pageIndex)?.asImageBitmap(), cache, pageIndex) {
        if (value == null) value = cache.load(pageIndex)?.asImageBitmap()
    }
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(4.dp)
    val label = stringResource(R.string.viewer_page_number, pageIndex + 1)

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                selected = isCurrent
            }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = shape,
            color = colors.surfaceContainerHighest,
            border = if (isCurrent) {
                BorderStroke(3.dp, colors.primary)
            } else {
                BorderStroke(1.dp, colors.outlineVariant)
            },
            modifier = Modifier
                .fillMaxWidth()
                // Guard against degenerate page sizes: aspectRatio() requires a positive ratio.
                .aspectRatio(pageSize.aspectRatio.takeIf { it.isFinite() && it > 0f }?.coerceIn(0.2f, 5f) ?: DEFAULT_RATIO),
        ) {
            Box(contentAlignment = Alignment.Center) {
                bitmap?.let {
                    Image(
                        bitmap = it,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        colorFilter = colorFilter,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        Text(
            text = (pageIndex + 1).toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isCurrent) FontWeight.Bold else null,
            color = if (isCurrent) colors.primary else colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * Approximates the document view's reading modes on thumbnails: night inverts the page,
 * sepia tints it warm while keeping text dark.
 */
internal fun pageColorFilter(mode: PageColorMode): ColorFilter? = when (mode) {
    PageColorMode.NORMAL -> null
    PageColorMode.NIGHT -> ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
    PageColorMode.SEPIA -> ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, 0f,
                0f, 0.94f, 0f, 0f, 0f,
                0f, 0f, 0.82f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
}

private const val DEFAULT_RATIO = 0.7071f
