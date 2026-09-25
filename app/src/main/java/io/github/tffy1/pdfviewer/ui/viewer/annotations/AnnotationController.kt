package io.github.tffy1.pdfviewer.ui.viewer.annotations

import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.tffy1.pdfviewer.R
import io.github.tffy1.pdfviewer.annotations.AnnotatedPdfExporter
import io.github.tffy1.pdfviewer.annotations.AnnotationCodec
import io.github.tffy1.pdfviewer.annotations.AnnotationColors
import io.github.tffy1.pdfviewer.annotations.AnnotationContent
import io.github.tffy1.pdfviewer.annotations.AnnotationEditSession
import io.github.tffy1.pdfviewer.annotations.AnnotationError
import io.github.tffy1.pdfviewer.annotations.AnnotationExportException
import io.github.tffy1.pdfviewer.annotations.AnnotationGeometry
import io.github.tffy1.pdfviewer.annotations.AnnotationType
import io.github.tffy1.pdfviewer.annotations.InkStroke
import io.github.tffy1.pdfviewer.annotations.PageAnnotation
import io.github.tffy1.pdfviewer.annotations.StrokeWidth
import io.github.tffy1.pdfviewer.core.model.PagePoint
import io.github.tffy1.pdfviewer.core.model.PageSize
import io.github.tffy1.pdfviewer.data.repository.AnnotationsRepository
import io.github.tffy1.pdfviewer.ui.viewer.document.PageLayoutInfo
import io.github.tffy1.pdfviewer.ui.viewer.selection.MarkupKind
import io.github.tffy1.pdfviewer.ui.viewer.selection.TextSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/* CONTRACT (scaffold). Owner: annotations agent. Keep these signatures. */

enum class AnnotationTool { INK, NOTE, ERASER }

/** The note dialog currently open: a new note at [anchor] ([annotationId] null) or an existing one. */
data class NoteEditorState(
    val pageIndex: Int,
    val anchor: PagePoint,
    val annotationId: Long?,
    val initialText: String,
) {
    val isNew: Boolean get() = annotationId == null
}

/**
 * Annotation state of one open document: stored annotations per page, the active tool and its
 * settings, the note dialog, and session undo. All database writes run in [scope], in order,
 * with their own error handling (see [AnnotationEditSession]); failures are reported on [errors].
 * Call from the main thread.
 */
class AnnotationController(
    val documentUri: Uri,
    private val repository: AnnotationsRepository,
    private val scope: CoroutineScope,
) {
    private val session = AnnotationEditSession(documentUri.toString(), repository, scope)

    private val _activeTool = MutableStateFlow<AnnotationTool?>(null)

    /** Active drawing tool; null when not in annotate mode. */
    val activeTool: StateFlow<AnnotationTool?> = _activeTool.asStateFlow()

    private val _capturesTouch = MutableStateFlow(false)

    /** True while a tool that needs raw touch (ink/eraser/note placement) is active. */
    val capturesTouch: StateFlow<Boolean> = _capturesTouch.asStateFlow()

    val annotationCount: StateFlow<Int> = session.count

    /** Visible annotations by page index, in drawing order (last = top-most). */
    val annotations: StateFlow<Map<Int, List<PageAnnotation>>> = session.annotationsByPage

    val canUndo: StateFlow<Boolean> = session.canUndo

    /** Failed database operations; map with [messageRes] and show e.g. in a snackbar. */
    val errors: SharedFlow<AnnotationError> = session.errors

    private val _colors = MutableStateFlow(AnnotationColors.defaults())

    /** Current color (ARGB) for each annotation type. */
    val colors: StateFlow<Map<AnnotationType, Int>> = _colors.asStateFlow()

    private val _strokeWidth = MutableStateFlow(StrokeWidth.MEDIUM)
    val strokeWidth: StateFlow<StrokeWidth> = _strokeWidth.asStateFlow()

    private val _noteEditor = MutableStateFlow<NoteEditorState?>(null)

    /** The open note dialog, if any. Shown by the [AnnotationOverlay] of its page. */
    val noteEditor: StateFlow<NoteEditorState?> = _noteEditor.asStateFlow()

    init {
        scope.launch {
            session.errors.collect { Log.w(TAG, "Annotation operation failed: $it") }
        }
    }

    fun setTool(tool: AnnotationTool?) {
        _activeTool.value = tool
        _capturesTouch.value = tool != null
    }

    fun colorFor(type: AnnotationType): Int = _colors.value[type] ?: AnnotationColors.defaultFor(type)

    fun setColor(type: AnnotationType, color: Int) {
        _colors.update { it + (type to color) }
    }

    fun setStrokeWidth(width: StrokeWidth) {
        _strokeWidth.value = width
    }

    /** Adds a text-markup annotation from a text selection. */
    fun addMarkup(selection: TextSelection, kind: MarkupKind) {
        val rects = selection.rects.filter {
            it.left.isFinite() && it.top.isFinite() && it.right.isFinite() && it.bottom.isFinite() &&
                it.width > 0f && it.height > 0f
        }
        if (rects.isEmpty() || selection.pageIndex < 0) return
        val type = when (kind) {
            MarkupKind.HIGHLIGHT -> AnnotationType.HIGHLIGHT
            MarkupKind.UNDERLINE -> AnnotationType.UNDERLINE
            MarkupKind.STRIKEOUT -> AnnotationType.STRIKEOUT
        }
        session.add(selection.pageIndex, type, colorFor(type), AnnotationContent.Markup(rects))
    }

    /**
     * Commits a freehand stroke drawn with the current color and width. Points are clamped to
     * the page and simplified with [tolerance] (points). Strokes with fewer than 2 distinct
     * points are ignored. Returns the new annotation's temporary id, or null if ignored.
     */
    fun addInkStroke(
        pageIndex: Int,
        points: List<PagePoint>,
        pageSize: PageSize? = null,
        tolerance: Float = DEFAULT_SIMPLIFY_TOLERANCE,
    ): Long? {
        val clamped = if (pageSize != null) {
            points.map { AnnotationGeometry.clampToPage(it, pageSize.width, pageSize.height) }
        } else {
            points
        }
        val simplified = AnnotationGeometry.simplify(clamped, tolerance)
        if (simplified.size < 2 || pageIndex < 0) return null
        val stroke = InkStroke(simplified, _strokeWidth.value.points)
        return session.add(pageIndex, AnnotationType.INK, colorFor(AnnotationType.INK), AnnotationContent.Ink(listOf(stroke)))
    }

    /**
     * Deletes the top-most annotation of any type under [point] (eraser). [tolerance] and
     * [noteRadius] are in points. Returns true if something was erased.
     */
    fun eraseAt(pageIndex: Int, point: PagePoint, tolerance: Float, noteRadius: Float): Boolean {
        val candidates = annotations.value[pageIndex] ?: return false
        val hit = AnnotationGeometry.findHit(candidates, point, tolerance, noteRadius) ?: return false
        session.delete(hit.id)
        return true
    }

    fun delete(annotationId: Long) = session.delete(annotationId)

    /** Opens the dialog for a new note at [anchor] (the note is created when saved). */
    fun startNewNote(pageIndex: Int, anchor: PagePoint) {
        _noteEditor.value = NoteEditorState(pageIndex, anchor, annotationId = null, initialText = "")
    }

    /** Opens an existing note to view, edit or delete it. */
    fun openNote(annotationId: Long) {
        val note = session.find(annotationId) ?: return
        val content = note.content as? AnnotationContent.Note ?: return
        _noteEditor.value = NoteEditorState(note.pageIndex, content.anchor, annotationId, note.note.orEmpty())
    }

    fun saveNote(text: String) {
        val editor = _noteEditor.value ?: return
        _noteEditor.value = null
        val id = editor.annotationId
        if (id == null) {
            if (text.isBlank()) return
            session.add(
                editor.pageIndex,
                AnnotationType.NOTE,
                colorFor(AnnotationType.NOTE),
                AnnotationContent.Note(editor.anchor),
                note = text,
            )
        } else if (text != editor.initialText) {
            session.updateNote(id, text)
        }
    }

    fun deleteOpenNote() {
        val editor = _noteEditor.value ?: return
        _noteEditor.value = null
        editor.annotationId?.let(session::delete)
    }

    fun dismissNoteEditor() {
        _noteEditor.value = null
    }

    fun undo() {
        session.undo()
    }

    companion object {
        private const val TAG = "AnnotationController"

        /** Stroke simplification tolerance in points when the caller doesn't know the zoom. */
        const val DEFAULT_SIMPLIFY_TOLERANCE = 0.4f
    }
}

/** Renders saved annotations for a page and handles drawing input when a tool is active. */
@Composable
fun AnnotationOverlay(controller: AnnotationController, page: PageLayoutInfo) {
    AnnotationOverlayContent(controller, page)
}

/**
 * Bottom toolbar shown in annotate mode (tools, color, stroke width, undo, done).
 * [windowInsets] are applied inside the toolbar surface (navigation bar by default).
 */
@Composable
fun AnnotationToolbar(
    controller: AnnotationController,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = BottomAppBarDefaults.windowInsets,
) {
    AnnotationToolbarContent(controller, onDone, modifier, windowInsets)
}

/**
 * Screen-level entry for "Save annotated copy": the viewer calls this with a destination URI
 * obtained from ACTION_CREATE_DOCUMENT. Writes the source PDF plus all stored annotations
 * as real PDF annotations using PdfBox. Returns failure instead of throwing
 * (failures are [AnnotationExportException]; see [exportErrorMessageRes]).
 * With [deleteDestinationOnFailure] an incomplete destination document is removed.
 */
suspend fun exportAnnotatedPdf(
    context: android.content.Context,
    sourceUri: Uri,
    destinationUri: Uri,
    repository: AnnotationsRepository,
    deleteDestinationOnFailure: Boolean = true,
): Result<Unit> {
    val annotations = try {
        repository.getAll(sourceUri.toString()).mapNotNull(AnnotationCodec::fromEntity)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return Result.failure(
            AnnotationExportException(AnnotationExportException.Reason.READ_FAILED, "Cannot load annotations", e),
        )
    }
    return AnnotatedPdfExporter.export(context, sourceUri, destinationUri, annotations, deleteDestinationOnFailure)
}

/** User-facing message for a failure returned by [exportAnnotatedPdf]. */
@StringRes
fun exportErrorMessageRes(error: Throwable?): Int =
    when ((error as? AnnotationExportException)?.reason) {
        AnnotationExportException.Reason.PASSWORD_PROTECTED -> R.string.annotations_export_error_password
        AnnotationExportException.Reason.NOT_PERMITTED -> R.string.annotations_export_error_not_permitted
        AnnotationExportException.Reason.READ_FAILED -> R.string.annotations_export_error_read
        AnnotationExportException.Reason.OUT_OF_MEMORY -> R.string.annotations_export_error_memory
        AnnotationExportException.Reason.WRITE_FAILED, null -> R.string.annotations_export_error_write
    }

/** User-facing message for an [AnnotationController.errors] event. */
@StringRes
fun AnnotationError.messageRes(): Int = when (this) {
    AnnotationError.LOAD_FAILED -> R.string.annotations_error_load
    AnnotationError.SAVE_FAILED -> R.string.annotations_error_save
    AnnotationError.DELETE_FAILED -> R.string.annotations_error_delete
    AnnotationError.UNDO_FAILED -> R.string.annotations_error_undo
}
