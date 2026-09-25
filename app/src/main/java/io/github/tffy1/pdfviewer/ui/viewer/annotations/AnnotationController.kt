package io.github.tffy1.pdfviewer.ui.viewer.annotations

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.tffy1.pdfviewer.data.repository.AnnotationsRepository
import io.github.tffy1.pdfviewer.ui.viewer.document.PageLayoutInfo
import io.github.tffy1.pdfviewer.ui.viewer.selection.TextSelection
import io.github.tffy1.pdfviewer.ui.viewer.selection.MarkupKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/* CONTRACT (scaffold). Owner: annotations agent. Keep these signatures. */

enum class AnnotationTool { INK, NOTE, ERASER }

class AnnotationController(
    val documentUri: Uri,
    private val repository: AnnotationsRepository,
    private val scope: CoroutineScope,
) {
    /** Active drawing tool; null when not in annotate mode. */
    val activeTool: StateFlow<AnnotationTool?> get() = TODO("annotations agent")

    /** True while a tool that needs raw touch (ink/eraser/note placement) is active. */
    val capturesTouch: StateFlow<Boolean> get() = TODO("annotations agent")

    val annotationCount: StateFlow<Int> get() = TODO("annotations agent")

    fun setTool(tool: AnnotationTool?) { TODO("annotations agent") }

    /** Adds a text-markup annotation from a text selection. */
    fun addMarkup(selection: TextSelection, kind: MarkupKind) { TODO("annotations agent") }

    fun undo() { TODO("annotations agent") }
}

/** Renders saved annotations for a page and handles drawing input when a tool is active. */
@Composable
fun AnnotationOverlay(controller: AnnotationController, page: PageLayoutInfo) {
    TODO("annotations agent")
}

/** Bottom toolbar shown in annotate mode (tools, color, stroke width, undo, done). */
@Composable
fun AnnotationToolbar(controller: AnnotationController, onDone: () -> Unit, modifier: Modifier = Modifier) {
    TODO("annotations agent")
}

/**
 * Screen-level entry for "Save annotated copy": the viewer calls this with a destination URI
 * obtained from ACTION_CREATE_DOCUMENT. Writes the source PDF plus all stored annotations
 * as real PDF annotations using PdfBox. Returns failure instead of throwing.
 */
suspend fun exportAnnotatedPdf(
    context: android.content.Context,
    sourceUri: Uri,
    destinationUri: Uri,
    repository: AnnotationsRepository,
): Result<Unit> = TODO("annotations agent")
