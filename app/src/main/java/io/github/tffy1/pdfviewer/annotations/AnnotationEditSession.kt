package io.github.tffy1.pdfviewer.annotations

import io.github.tffy1.pdfviewer.data.repository.AnnotationsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Failures reported by [AnnotationEditSession.errors]. */
enum class AnnotationError { LOAD_FAILED, SAVE_FAILED, DELETE_FAILED, UNDO_FAILED }

/**
 * The annotations of one document plus the edits made in this session.
 *
 * Edits show up immediately (optimistically) and are written to the database one at a time,
 * in order, by a single consumer coroutine, so an undo always applies to the latest finished
 * edit. Every database write handles its own errors; failures roll back the optimistic state
 * and are reported through [errors]. Corrupt rows are skipped when reading.
 */
class AnnotationEditSession(
    private val documentKey: String,
    private val repository: AnnotationsRepository,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** An inserted-but-maybe-not-yet-observed annotation. [rowId] is set once the insert finished. */
    private data class PendingAdd(val annotation: PageAnnotation, val rowId: Long?)

    private sealed interface Op {
        data class Add(val annotation: PageAnnotation) : Op
        data class Delete(val id: Long) : Op
        data class UpdateNote(val id: Long, val text: String) : Op
        data class StoredChanged(val rows: List<PageAnnotation>) : Op
        data object Undo : Op
    }

    private sealed interface UndoRecord {
        data class Added(val rowId: Long) : UndoRecord
        data class Deleted(val annotation: PageAnnotation) : UndoRecord
        data class NoteEdited(val previous: PageAnnotation) : UndoRecord
    }

    private val _errors = MutableSharedFlow<AnnotationError>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val errors: SharedFlow<AnnotationError> = _errors.asSharedFlow()

    // ---- Consumer-only state (declared before the flows below, which use [ops]) ----------
    private val ops = Channel<Op>(Channel.UNLIMITED)
    private val undoStack = ArrayDeque<UndoRecord>()
    private val rowIdsByTempId = HashMap<Long, Long>()
    private val finishedDeletes = HashSet<Long>()
    private var nextTempId = -1L

    private val stored: StateFlow<List<PageAnnotation>> = repository.observe(documentKey)
        .map { rows -> rows.mapNotNull(AnnotationCodec::fromEntity) }
        .flowOn(workDispatcher)
        .catch { _errors.tryEmit(AnnotationError.LOAD_FAILED) }
        .onEach { rows -> ops.trySend(Op.StoredChanged(rows)) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val pendingAdds = MutableStateFlow<List<PendingAdd>>(emptyList())

    /** Ids (row or temporary) that were deleted in the UI and must not be shown. */
    private val hiddenIds = MutableStateFlow<Set<Long>>(emptySet())

    /** Visible annotations grouped by page, in drawing order (last = top-most). */
    val annotationsByPage: StateFlow<Map<Int, List<PageAnnotation>>> =
        combine(stored, pendingAdds, hiddenIds) { rows, adds, hidden -> visible(rows, adds, hidden) }
            .flowOn(workDispatcher)
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val count: StateFlow<Int> = annotationsByPage
        .map { byPage -> byPage.values.sumOf { it.size } }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    init {
        scope.launch {
            for (op in ops) process(op)
        }
    }

    // ---- Public API (call from the main thread) ------------------------------------------

    /** Adds an annotation; returns its temporary id (valid for [delete]/[updateNote]). */
    fun add(
        pageIndex: Int,
        type: AnnotationType,
        color: Int,
        content: AnnotationContent,
        note: String? = null,
    ): Long {
        val now = clock()
        val annotation = PageAnnotation(
            id = nextTempId--,
            pageIndex = pageIndex,
            type = type,
            color = color,
            content = content,
            note = note,
            createdAt = now,
            updatedAt = now,
        )
        pendingAdds.update { it + PendingAdd(annotation, rowId = null) }
        ops.trySend(Op.Add(annotation))
        return annotation.id
    }

    fun delete(id: Long) {
        if (id in hiddenIds.value) return
        hiddenIds.update { it + id }
        ops.trySend(Op.Delete(id))
    }

    fun updateNote(id: Long, text: String) {
        ops.trySend(Op.UpdateNote(id, text))
    }

    fun undo() {
        ops.trySend(Op.Undo)
    }

    /** The visible annotation with [id], if any. */
    fun find(id: Long): PageAnnotation? =
        annotationsByPage.value.values.firstNotNullOfOrNull { list -> list.firstOrNull { it.id == id } }

    // ---- Consumer ------------------------------------------------------------------------

    private suspend fun process(op: Op) {
        try {
            when (op) {
                is Op.Add -> processAdd(op.annotation)
                is Op.Delete -> processDelete(op.id)
                is Op.UpdateNote -> processUpdateNote(op.id, op.text)
                is Op.StoredChanged -> cleanUp(op.rows)
                Op.Undo -> processUndo()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Each operation rolls back its own state; this only guards the consumer loop.
            _errors.tryEmit(AnnotationError.SAVE_FAILED)
        }
    }

    private suspend fun processAdd(annotation: PageAnnotation) {
        val tempId = annotation.id
        val rowId = try {
            withContext(NonCancellable) { repository.add(AnnotationCodec.toEntity(annotation, documentKey)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            pendingAdds.update { list -> list.filterNot { it.annotation.id == tempId } }
            _errors.tryEmit(AnnotationError.SAVE_FAILED)
            return
        }
        rowIdsByTempId[tempId] = rowId
        pendingAdds.update { list -> list.map { if (it.annotation.id == tempId) it.copy(rowId = rowId) else it } }
        pushUndo(UndoRecord.Added(rowId))
        cleanUp(stored.value)
    }

    private suspend fun processDelete(id: Long) {
        val rowId = if (id < 0) rowIdsByTempId[id] else id
        if (rowId == null) {
            // The insert failed (or never ran): nothing is stored, just drop the pending entry.
            pendingAdds.update { list -> list.filterNot { it.annotation.id == id } }
            hiddenIds.update { it - id }
            return
        }
        val snapshot = findSnapshot(id, rowId)
        // Hide the row id before dropping the pending entry so it never flashes back.
        hiddenIds.update { if (id < 0) it - id + rowId else it + rowId }
        pendingAdds.update { list -> list.filterNot { it.annotation.id == id || it.rowId == rowId } }
        try {
            withContext(NonCancellable) { repository.delete(rowId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            hiddenIds.update { it - rowId }
            _errors.tryEmit(AnnotationError.DELETE_FAILED)
            return
        }
        finishedDeletes += rowId
        if (snapshot != null) pushUndo(UndoRecord.Deleted(snapshot))
        cleanUp(stored.value)
    }

    private suspend fun processUpdateNote(id: Long, text: String) {
        val rowId = (if (id < 0) rowIdsByTempId[id] else id) ?: return
        val current = findSnapshot(id, rowId) ?: return
        if (current.note == text) return
        val updated = current.copy(note = text, updatedAt = clock())
        try {
            withContext(NonCancellable) { repository.update(AnnotationCodec.toEntity(updated, documentKey)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _errors.tryEmit(AnnotationError.SAVE_FAILED)
            return
        }
        pendingAdds.update { list -> list.map { if (it.rowId == rowId) it.copy(annotation = updated) else it } }
        pushUndo(UndoRecord.NoteEdited(current))
    }

    private suspend fun processUndo() {
        val record = undoStack.removeLastOrNull() ?: return
        _canUndo.value = undoStack.isNotEmpty()
        try {
            when (record) {
                is UndoRecord.Added -> {
                    hiddenIds.update { it + record.rowId }
                    finishedDeletes -= record.rowId
                    try {
                        withContext(NonCancellable) { repository.delete(record.rowId) }
                    } catch (e: Exception) {
                        hiddenIds.update { it - record.rowId }
                        throw e
                    }
                    finishedDeletes += record.rowId
                }
                is UndoRecord.Deleted -> {
                    val annotation = record.annotation
                    finishedDeletes -= annotation.id
                    hiddenIds.update { it - annotation.id }
                    // Show it again right away; the entry is dropped once the row is observed.
                    pendingAdds.update { it + PendingAdd(annotation, rowId = annotation.id) }
                    try {
                        // Re-inserting with the original id keeps its place in the drawing order.
                        withContext(NonCancellable) { repository.add(AnnotationCodec.toEntity(annotation, documentKey)) }
                    } catch (e: Exception) {
                        pendingAdds.update { list -> list.filterNot { it.rowId == annotation.id } }
                        throw e
                    }
                }
                is UndoRecord.NoteEdited -> withContext(NonCancellable) {
                    repository.update(AnnotationCodec.toEntity(record.previous, documentKey))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _errors.tryEmit(AnnotationError.UNDO_FAILED)
        }
        cleanUp(stored.value)
    }

    private fun pushUndo(record: UndoRecord) {
        undoStack.addLast(record)
        while (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        _canUndo.value = true
    }

    /** Current state of an annotation, from the database or a pending insert, with its row id. */
    private fun findSnapshot(id: Long, rowId: Long): PageAnnotation? =
        stored.value.firstOrNull { it.id == rowId }
            ?: pendingAdds.value.firstOrNull { it.annotation.id == id || it.rowId == rowId }
                ?.annotation?.copy(id = rowId)

    /** Drops optimistic state that the database has caught up with. */
    private fun cleanUp(rows: List<PageAnnotation>) {
        val storedIds = rows.mapTo(HashSet(rows.size)) { it.id }
        pendingAdds.update { list -> list.filterNot { it.rowId != null && it.rowId in storedIds } }
        val gone = finishedDeletes.filterTo(HashSet()) { it !in storedIds }
        if (gone.isNotEmpty()) {
            finishedDeletes -= gone
            hiddenIds.update { it - gone }
        }
    }

    companion object {
        const val MAX_UNDO = 100

        private fun visible(
            rows: List<PageAnnotation>,
            adds: List<PendingAdd>,
            hidden: Set<Long>,
        ): Map<Int, List<PageAnnotation>> {
            val storedIds = rows.mapTo(HashSet(rows.size)) { it.id }
            val result = ArrayList<PageAnnotation>(rows.size + adds.size)
            rows.filterTo(result) { it.id !in hidden }
            for (pending in adds) {
                val rowId = pending.rowId
                if (pending.annotation.id in hidden) continue
                if (rowId != null && (rowId in storedIds || rowId in hidden)) continue
                result += if (rowId != null) pending.annotation.copy(id = rowId) else pending.annotation
            }
            return result.groupBy { it.pageIndex }
        }
    }
}
