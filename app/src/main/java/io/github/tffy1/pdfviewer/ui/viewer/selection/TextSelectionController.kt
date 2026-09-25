package io.github.tffy1.pdfviewer.ui.viewer.selection

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.tffy1.pdfviewer.core.model.PagePoint
import io.github.tffy1.pdfviewer.core.model.PageRect
import io.github.tffy1.pdfviewer.pdf.PdfDocument
import io.github.tffy1.pdfviewer.ui.viewer.document.PageLayoutInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

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
    val selection: StateFlow<TextSelection?> get() = TODO("selection agent")

    /** Selects the word at [point] (called on long press). No-op if there is no text there. */
    fun selectWordAt(pageIndex: Int, point: PagePoint) { TODO("selection agent") }

    fun selectAllOnPage(pageIndex: Int) { TODO("selection agent") }

    fun clear() { TODO("selection agent") }
}

/** Selection highlight + draggable handles for one page. Use inside DocumentView.pageOverlay. */
@Composable
fun TextSelectionOverlay(controller: TextSelectionController, page: PageLayoutInfo) {
    TODO("selection agent")
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
    TODO("selection agent")
}

enum class MarkupKind { HIGHLIGHT, UNDERLINE, STRIKEOUT }
