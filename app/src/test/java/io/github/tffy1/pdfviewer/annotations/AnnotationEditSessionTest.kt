package io.github.tffy1.pdfviewer.annotations

import io.github.tffy1.pdfviewer.core.model.PagePoint
import io.github.tffy1.pdfviewer.core.model.PageRect
import io.github.tffy1.pdfviewer.data.db.AnnotationDao
import io.github.tffy1.pdfviewer.data.db.AnnotationEntity
import io.github.tffy1.pdfviewer.data.repository.AnnotationsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AnnotationEditSessionTest {

    /** In-memory stand-in for the Room DAO (same ordering, explicit ids on insert allowed). */
    private class FakeAnnotationDao : AnnotationDao {
        val rows = MutableStateFlow<List<AnnotationEntity>>(emptyList())
        private var nextId = 1L
        var insertGate: CompletableDeferred<Unit>? = null
        var failInserts = false
        var failDeletes = false

        override fun observeForDocument(uri: String): Flow<List<AnnotationEntity>> = rows.map { list ->
            list.filter { it.documentUri == uri }.sortedWith(compareBy({ it.pageIndex }, { it.createdAt }))
        }

        override suspend fun getForDocument(uri: String): List<AnnotationEntity> = observeForDocument(uri).first()

        override suspend fun insert(annotation: AnnotationEntity): Long {
            insertGate?.await()
            if (failInserts) throw IOException("disk full")
            val id = if (annotation.id != 0L) annotation.id else nextId
            check(rows.value.none { it.id == id }) { "UNIQUE constraint failed" }
            nextId = maxOf(nextId, id + 1)
            rows.update { it + annotation.copy(id = id) }
            return id
        }

        override suspend fun upsert(annotation: AnnotationEntity) {
            rows.update { list -> list.filterNot { it.id == annotation.id } + annotation }
        }

        override suspend fun delete(id: Long) {
            if (failDeletes) throw IOException("read-only")
            rows.update { list -> list.filterNot { it.id == id } }
        }

        override suspend fun deleteForDocument(uri: String) {
            rows.update { list -> list.filterNot { it.documentUri == uri } }
        }
    }

    private val dao = FakeAnnotationDao()

    private fun TestScope.newSession(): AnnotationEditSession = AnnotationEditSession(
        documentKey = DOC,
        repository = AnnotationsRepository(dao),
        scope = backgroundScope,
        clock = { 1_000L },
        workDispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun TestScope.collectErrors(session: AnnotationEditSession): MutableList<AnnotationError> {
        val errors = mutableListOf<AnnotationError>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.errors.collect { errors += it } }
        return errors
    }

    private val stroke = AnnotationContent.Ink(listOf(InkStroke(listOf(PagePoint(1f, 1f), PagePoint(20f, 20f)), 2f)))
    private val highlight = AnnotationContent.Markup(listOf(PageRect(10f, 10f, 100f, 22f)))

    private fun AnnotationEditSession.all(): List<PageAnnotation> = annotationsByPage.value.values.flatten()

    @Test
    fun addShowsImmediatelyThenPersists() = runTest {
        val session = newSession()
        val gate = CompletableDeferred<Unit>()
        dao.insertGate = gate

        val tempId = session.add(2, AnnotationType.INK, AnnotationColors.BLUE, stroke)
        runCurrent()
        assertTrue(tempId < 0)
        assertEquals(listOf(tempId), session.annotationsByPage.value[2]?.map { it.id })
        assertTrue(dao.rows.value.isEmpty())

        gate.complete(Unit)
        runCurrent()
        val row = dao.rows.value.single()
        assertEquals("INK", row.type)
        assertEquals(listOf(row.id), session.annotationsByPage.value[2]?.map { it.id })
        assertEquals(1, session.count.value)
        assertTrue(session.canUndo.value)
    }

    @Test
    fun deleteHidesImmediatelyAndUndoRestoresTheSameRow() = runTest {
        val session = newSession()
        session.add(0, AnnotationType.HIGHLIGHT, AnnotationColors.YELLOW, highlight)
        runCurrent()
        val id = session.all().single().id

        session.delete(id)
        runCurrent()
        assertTrue(session.all().isEmpty())
        assertTrue(dao.rows.value.isEmpty())

        session.undo()
        runCurrent()
        assertEquals(listOf(id), dao.rows.value.map { it.id })
        assertEquals(listOf(id), session.all().map { it.id })
        assertEquals(highlight, session.all().single().content)
    }

    @Test
    fun undoRemovesTheLastAddition() = runTest {
        val session = newSession()
        session.add(0, AnnotationType.INK, AnnotationColors.RED, stroke)
        session.add(0, AnnotationType.HIGHLIGHT, AnnotationColors.YELLOW, highlight)
        runCurrent()
        assertEquals(2, session.count.value)

        session.undo()
        runCurrent()
        assertEquals(listOf("INK"), dao.rows.value.map { it.type })
        assertEquals(1, session.count.value)

        session.undo()
        runCurrent()
        assertTrue(dao.rows.value.isEmpty())
        assertEquals(0, session.count.value)
        assertFalse(session.canUndo.value)

        // Nothing left to undo: no-op.
        session.undo()
        runCurrent()
        assertEquals(0, session.count.value)
    }

    @Test
    fun erasingBeforeTheInsertFinishesDeletesTheRow() = runTest {
        val session = newSession()
        val gate = CompletableDeferred<Unit>()
        dao.insertGate = gate
        val tempId = session.add(0, AnnotationType.INK, AnnotationColors.RED, stroke)
        runCurrent()

        session.delete(tempId)
        runCurrent()
        assertTrue(session.all().isEmpty())

        gate.complete(Unit)
        runCurrent()
        assertTrue(dao.rows.value.isEmpty())
        assertTrue(session.all().isEmpty())

        // Undo brings the erased stroke back.
        session.undo()
        runCurrent()
        assertEquals(1, dao.rows.value.size)
        assertEquals(1, session.count.value)
    }

    @Test
    fun failedInsertRollsBackAndReportsError() = runTest {
        val session = newSession()
        val errors = collectErrors(session)
        dao.failInserts = true
        session.add(0, AnnotationType.INK, AnnotationColors.RED, stroke)
        runCurrent()
        assertTrue(session.all().isEmpty())
        assertEquals(listOf(AnnotationError.SAVE_FAILED), errors)
        assertFalse(session.canUndo.value)
    }

    @Test
    fun failedDeleteShowsTheAnnotationAgain() = runTest {
        val session = newSession()
        val errors = collectErrors(session)
        session.add(0, AnnotationType.INK, AnnotationColors.RED, stroke)
        runCurrent()
        dao.failDeletes = true
        session.delete(session.all().single().id)
        runCurrent()
        assertEquals(1, session.count.value)
        assertEquals(listOf(AnnotationError.DELETE_FAILED), errors)
    }

    @Test
    fun corruptRowsAreSkipped() = runTest {
        dao.rows.value = listOf(
            AnnotationEntity(id = 1, documentUri = DOC, pageIndex = 0, type = "INK", color = 0, payload = "{broken"),
            AnnotationEntity(id = 2, documentUri = DOC, pageIndex = 0, type = "WAT", color = 0, payload = "{}"),
            AnnotationEntity(
                id = 3, documentUri = DOC, pageIndex = 1, type = "NOTE", color = 0,
                payload = """{"v":1,"x":5,"y":6}""", note = "hi",
            ),
            AnnotationEntity(
                id = 4, documentUri = "other", pageIndex = 0, type = "NOTE", color = 0,
                payload = """{"v":1,"x":5,"y":6}""",
            ),
        )
        val session = newSession()
        runCurrent()
        assertEquals(setOf(1), session.annotationsByPage.value.keys)
        assertEquals("hi", session.all().single().note)
    }

    @Test
    fun noteEditsAreSavedAndUndoable() = runTest {
        val session = newSession()
        session.add(0, AnnotationType.NOTE, AnnotationColors.YELLOW, AnnotationContent.Note(PagePoint(5f, 5f)), note = "first")
        runCurrent()
        val id = session.all().single().id

        session.updateNote(id, "second")
        runCurrent()
        assertEquals("second", dao.rows.value.single().note)
        assertEquals("second", session.find(id)?.note)

        session.undo()
        runCurrent()
        assertEquals("first", dao.rows.value.single().note)
    }

    @Test
    fun annotationsAreGroupedByPage() = runTest {
        val session = newSession()
        session.add(0, AnnotationType.INK, AnnotationColors.RED, stroke)
        session.add(3, AnnotationType.HIGHLIGHT, AnnotationColors.YELLOW, highlight)
        session.add(3, AnnotationType.INK, AnnotationColors.RED, stroke)
        runCurrent()
        assertEquals(1, session.annotationsByPage.value[0]?.size)
        assertEquals(listOf(AnnotationType.HIGHLIGHT, AnnotationType.INK), session.annotationsByPage.value[3]?.map { it.type })
        assertEquals(3, session.count.value)
    }

    private companion object {
        const val DOC = "content://test/doc.pdf"
    }
}
