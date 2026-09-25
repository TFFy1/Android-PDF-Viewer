package io.github.tffy1.pdfviewer.ui.viewer.selection

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.StrikethroughS
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.tffy1.pdfviewer.R
import io.github.tffy1.pdfviewer.core.model.PagePoint
import io.github.tffy1.pdfviewer.core.model.PageRect
import io.github.tffy1.pdfviewer.pdf.PageText
import io.github.tffy1.pdfviewer.pdf.PdfDocument
import io.github.tffy1.pdfviewer.ui.viewer.document.PageLayoutInfo
import io.github.tffy1.pdfviewer.ui.viewer.links.copyPlainText
import io.github.tffy1.pdfviewer.ui.viewer.links.sharePlainText
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/* CONTRACT (scaffold). Owner: search/selection agent. Keep these signatures. */

/** Selection within a single page (selections do not span pages). */
data class TextSelection(
    val pageIndex: Int,
    val startIndex: Int,
    /** Exclusive. */
    val endIndex: Int,
    val text: String,
    /** One rect per line, page space. Used for highlight/underline/strikeout annotations. */
    val rects: List<PageRect>,
)

class TextSelectionController(
    private val document: PdfDocument,
    private val scope: CoroutineScope,
) {
    private val _selection = MutableStateFlow<TextSelection?>(null)
    val selection: StateFlow<TextSelection?> = _selection.asStateFlow()

    private val textCache = PageTextCache(capacity = 3)
    private var job: Job? = null

    /** Selects the word at [point] (called on long press). No-op if there is no text there. */
    fun selectWordAt(pageIndex: Int, point: PagePoint) {
        launchSelection(pageIndex) { pageText ->
            val index = nearestCharIndex(pageText.text, pageText.charBoxes, point, WORD_HIT_TOLERANCE_PT)
            if (index == null) null else wordSpanAt(pageText.text, index)
        }
    }

    fun selectAllOnPage(pageIndex: Int) {
        launchSelection(pageIndex) { pageText -> visibleSpan(pageText.text) }
    }

    fun clear() {
        job?.cancel()
        job = null
        _selection.value = null
    }

    /**
     * Moves one end of the selection while a handle is dragged: the other end stays at
     * [anchor] (a caret index) and the moving end follows [point]; the ends may cross.
     */
    internal fun dragSelectionTo(pageIndex: Int, anchor: Int, point: PagePoint) {
        val current = _selection.value ?: return
        if (current.pageIndex != pageIndex) return
        val pageText = textCache.get(pageIndex) ?: return
        val caret = caretIndexAt(pageText.text, pageText.charBoxes, point) ?: return
        val span = spanBetweenCarets(anchor, caret, pageText.text.length) ?: return
        if (span.start == current.startIndex && span.end == current.endIndex) return
        buildSelection(pageText, span)?.let { _selection.value = it }
    }

    private fun launchSelection(pageIndex: Int, findSpan: (PageText) -> CharSpan?) {
        job?.cancel()
        job = scope.launch {
            val pageText = loadPageText(pageIndex) ?: return@launch
            val span = findSpan(pageText) ?: return@launch
            buildSelection(pageText, span)?.let { _selection.value = it }
        }
    }

    private suspend fun loadPageText(pageIndex: Int): PageText? {
        if (pageIndex !in 0 until document.pageCount) return null
        textCache.get(pageIndex)?.let { return it }
        val loaded = try {
            document.pageText(pageIndex)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null // Broken text layer: selection simply isn't available on this page.
        }
        // Defensive: the geometry below indexes text and boxes in parallel.
        val count = min(loaded.text.length, loaded.charBoxes.size)
        val pageText = PageText(pageIndex, loaded.text.substring(0, count), loaded.charBoxes.subList(0, count))
        textCache.put(pageIndex, pageText)
        return pageText
    }

    private companion object {
        /** How far (in points) a long press may land from a glyph and still select it. */
        const val WORD_HIT_TOLERANCE_PT = 8f
    }
}

/** Tiny thread-safe LRU of extracted page text (selections usually stay on one or two pages). */
private class PageTextCache(private val capacity: Int) {
    private val map = object : LinkedHashMap<Int, PageText>(capacity + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PageText>?): Boolean = size > capacity
    }

    fun get(pageIndex: Int): PageText? = synchronized(map) { map[pageIndex] }

    fun put(pageIndex: Int, text: PageText) {
        synchronized(map) { map[pageIndex] = text }
    }
}

/** Selection highlight + draggable handles for one page. Use inside DocumentView.pageOverlay. */
@Composable
fun TextSelectionOverlay(controller: TextSelectionController, page: PageLayoutInfo) {
    val current by controller.selection.collectAsState()
    val selection = current?.takeIf { it.pageIndex == page.pageIndex && it.rects.isNotEmpty() } ?: return
    val handleColor = MaterialTheme.colorScheme.primary
    val fillColor = handleColor.copy(alpha = 0.3f)

    // Page coordinates are absolute (not mirrored in RTL layouts), hence the absolute alignment.
    Box(Modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
        // Drawing only: touches outside the handles fall through to the document.
        Canvas(Modifier.fillMaxSize()) {
            val scale = page.scale
            selection.rects.forEach { rect ->
                drawRect(
                    color = fillColor,
                    topLeft = Offset(rect.left * scale, rect.top * scale),
                    size = Size(rect.width * scale, rect.height * scale),
                )
            }
        }
        SelectionHandle(controller, page, selection, isStart = true, color = handleColor)
        SelectionHandle(controller, page, selection, isStart = false, color = handleColor)
    }
}

private val HandleTouchSize = 48.dp
private val HandleVisualSize = 20.dp

/**
 * A teardrop handle hanging below the start or end of the selection. The 48dp touch box
 * consumes its whole gesture (so the document neither scrolls nor long-presses underneath)
 * and moves the selection end by the finger's movement.
 */
@Composable
private fun SelectionHandle(
    controller: TextSelectionController,
    page: PageLayoutInfo,
    selection: TextSelection,
    isStart: Boolean,
    color: Color,
) {
    val line = if (isStart) selection.rects.first() else selection.rects.last()
    val caretX = page.toLocalX(if (isStart) line.left else line.right)
    val caretBottom = page.toLocalY(line.bottom)
    val latestSelection by rememberUpdatedState(selection)
    val latestScale by rememberUpdatedState(page.scale)

    Box(
        Modifier
            .absoluteOffset {
                val half = HandleTouchSize.toPx() / 2f
                val radius = (HandleVisualSize / 2).toPx()
                val centerX = if (isStart) caretX - radius else caretX + radius
                val centerY = caretBottom + radius
                IntOffset((centerX - half).roundToInt(), (centerY - half).roundToInt())
            }
            .size(HandleTouchSize)
            .pointerInput(controller, isStart) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val start = latestSelection
                    val anchor = if (isStart) start.endIndex else start.startIndex
                    val startLine = if (isStart) start.rects.first() else start.rects.last()
                    // Track the caret (not the finger) so the selection doesn't jump on touch-down:
                    // it moves by exactly the finger's displacement.
                    val scaleAtDown = latestScale
                    var x = (if (isStart) startLine.left else startLine.right) * scaleAtDown
                    var y = startLine.centerY * scaleAtDown
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        val delta = change.position - change.previousPosition
                        change.consume()
                        if (delta != Offset.Zero) {
                            x += delta.x
                            y += delta.y
                            controller.dragSelectionTo(
                                start.pageIndex,
                                anchor,
                                PagePoint(x / scaleAtDown, y / scaleAtDown),
                            )
                        }
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = (HandleVisualSize / 2).toPx()
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(color = color, radius = radius, center = center)
            // Square off the corner that points at the caret to form the teardrop.
            drawRect(
                color = color,
                topLeft = Offset(if (isStart) center.x else center.x - radius, center.y - radius),
                size = Size(radius, radius),
            )
        }
    }
}

/**
 * Floating action bar shown while text is selected: Copy, Share, Select all, and — when
 * [onAnnotate] is non-null — Highlight / Underline / Strikethrough (the viewer wires this
 * to the annotation controller).
 */
@Composable
fun TextSelectionActionBar(
    controller: TextSelectionController,
    onAnnotate: ((selection: TextSelection, kind: MarkupKind) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val current by controller.selection.collectAsState()
    val selection = current ?: return
    val context = LocalContext.current
    val clipLabel = stringResource(R.string.selection_clip_label)

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectionAction(Icons.Filled.ContentCopy, R.string.selection_copy) {
                if (context.copyPlainText(clipLabel, selection.text, R.string.selection_copied)) {
                    controller.clear()
                }
            }
            SelectionAction(Icons.Filled.Share, R.string.selection_share) {
                context.sharePlainText(selection.text)
                controller.clear()
            }
            SelectionAction(Icons.Filled.SelectAll, R.string.selection_select_all) {
                controller.selectAllOnPage(selection.pageIndex)
            }
            if (onAnnotate != null) {
                VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))
                SelectionAction(Icons.Filled.BorderColor, R.string.selection_highlight) {
                    onAnnotate(selection, MarkupKind.HIGHLIGHT)
                    controller.clear()
                }
                SelectionAction(Icons.Filled.FormatUnderlined, R.string.selection_underline) {
                    onAnnotate(selection, MarkupKind.UNDERLINE)
                    controller.clear()
                }
                SelectionAction(Icons.Filled.StrikethroughS, R.string.selection_strikethrough) {
                    onAnnotate(selection, MarkupKind.STRIKEOUT)
                    controller.clear()
                }
            }
        }
    }
}

@Composable
private fun SelectionAction(icon: ImageVector, @StringRes label: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = stringResource(label))
    }
}

enum class MarkupKind { HIGHLIGHT, UNDERLINE, STRIKEOUT }
