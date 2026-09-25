package io.github.tffy1.pdfviewer.ui.viewer.chrome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tffy1.pdfviewer.R
import io.github.tffy1.pdfviewer.ui.viewer.sliderValueToPage

/** Insets for bottom chrome that overlays the document, stable while system bars are hidden. */
@OptIn(ExperimentalLayoutApi::class)
internal val viewerBottomBarInsets: WindowInsets
    @Composable get() = WindowInsets.navigationBarsIgnoringVisibility
        .union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)

/**
 * Page indicator ("12 / 240") plus a scrubber. While dragging, a bubble shows the target page;
 * the jump happens when the finger is released (rendering every page on the way would be wasteful).
 */
@Composable
fun ViewerBottomBar(
    currentPage: Int,
    pageCount: Int,
    onJumpToPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val shownPage = dragValue?.let { sliderValueToPage(it, pageCount) } ?: currentPage

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedVisibility(
            visible = dragValue != null,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
        ) {
            PageBubble(text = stringResource(R.string.viewer_page_number, shownPage + 1))
        }
        Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer) {
            Box(
                Modifier
                    .windowInsetsPadding(viewerBottomBarInsets)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    Modifier
                        .widthIn(max = MAX_BAR_CONTENT_WIDTH)
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    val indicatorDescription =
                        stringResource(R.string.viewer_page_indicator_description, shownPage + 1, pageCount)
                    Text(
                        text = stringResource(R.string.viewer_page_indicator, shownPage + 1, pageCount),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.semantics { contentDescription = indicatorDescription },
                    )
                    if (pageCount > 1) {
                        val scrubberDescription = stringResource(R.string.viewer_page_scrubber)
                        Slider(
                            value = dragValue ?: currentPage.toFloat(),
                            onValueChange = { dragValue = it },
                            onValueChangeFinished = {
                                dragValue?.let { onJumpToPage(sliderValueToPage(it, pageCount)) }
                                dragValue = null
                            },
                            valueRange = 0f..(pageCount - 1).toFloat(),
                            modifier = Modifier
                                .weight(1f)
                                .semantics { contentDescription = scrubberDescription },
                        )
                    }
                }
            }
        }
    }
}

/** Large rounded label used for the scrubber bubble and the floating page number. */
@Composable
fun PageBubble(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(bottom = 8.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 3.dp,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}

private val MAX_BAR_CONTENT_WIDTH = 840.dp
