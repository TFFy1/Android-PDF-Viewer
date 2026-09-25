package io.github.tffy1.pdfviewer.library

import io.github.tffy1.pdfviewer.data.db.LibraryFileEntity
import io.github.tffy1.pdfviewer.data.db.LibraryFolderEntity
import io.github.tffy1.pdfviewer.data.db.RecentDocumentEntity
import io.github.tffy1.pdfviewer.data.settings.LibrarySort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryListLogicTest {

    private val tree = "content://com.android.externalstorage.documents/tree/primary%3ABooks"
    private fun treeDoc(name: String) = "$tree/document/primary%3ABooks%2F$name"

    private fun recent(
        uri: String,
        name: String = uri,
        openedAt: Long = 0,
        size: Long? = null,
        favorite: Boolean = false,
        persistent: Boolean = true,
        lastPage: Int = 0,
        pageCount: Int = 0,
    ) = RecentDocumentEntity(
        uri = uri,
        displayName = name,
        sizeBytes = size,
        pageCount = pageCount,
        lastPage = lastPage,
        lastOpenedAt = openedAt,
        isFavorite = favorite,
        hasPersistentAccess = persistent,
    )

    private fun libraryFile(name: String, folder: String = tree, size: Long = 10, modified: Long = 0) =
        LibraryFileEntity(uri = treeDoc(name), folderUri = folder, displayName = name, sizeBytes = size, lastModified = modified)

    private fun names(items: List<DocumentItem>) = items.map { it.name }

    @Test
    fun naturalOrderComparesNumbersByValueAndIgnoresCase() {
        val sorted = listOf("chapter 10.pdf", "Chapter 2.pdf", "chapter 1.pdf", "appendix.pdf", "Chapter 002b.pdf")
            .sortedWith(NaturalOrderComparator)
        assertEquals(
            listOf("appendix.pdf", "chapter 1.pdf", "Chapter 2.pdf", "Chapter 002b.pdf", "chapter 10.pdf"),
            sorted,
        )
        assertTrue(NaturalOrderComparator.compare("a", "ab") < 0)
        assertTrue(NaturalOrderComparator.compare("file99999999999999999999", "file100000000000000000000") < 0)
        assertEquals(0, NaturalOrderComparator.compare("same", "same"))
    }

    @Test
    fun readingProgressIsOneBasedAndClamped() {
        assertNull(readingProgress(lastPageIndex = 3, pageCount = 0))
        assertEquals(ReadingProgress(12, 240), readingProgress(11, 240))
        assertEquals(ReadingProgress(1, 5), readingProgress(-4, 5))
        assertEquals(ReadingProgress(5, 5), readingProgress(99, 5))
        assertEquals(1f, ReadingProgress(5, 5).fraction)
    }

    @Test
    fun sortsByRecencyNameAndSize() {
        val items = listOf(
            recent("u1", "b.pdf", openedAt = 100, size = 5),
            recent("u2", "a.pdf", openedAt = 300, size = null),
            recent("u3", "c.pdf", openedAt = 200, size = 50),
        ).map { it.toDocumentItem(emptyList()) }

        assertEquals(listOf("a.pdf", "c.pdf", "b.pdf"), names(sortDocuments(items, LibrarySort.RECENT)))
        assertEquals(listOf("a.pdf", "b.pdf", "c.pdf"), names(sortDocuments(items, LibrarySort.NAME)))
        assertEquals(listOf("c.pdf", "b.pdf", "a.pdf"), names(sortDocuments(items, LibrarySort.SIZE)))
    }

    @Test
    fun searchMatchesAllTermsIgnoringCaseAndAccents() {
        val items = listOf("Résumé 2024.pdf", "Tax return 2024.pdf", "Notes.pdf")
            .mapIndexed { i, name -> recent("u$i", name).toDocumentItem(emptyList()) }

        assertEquals(listOf("Résumé 2024.pdf"), names(filterDocuments(items, "resume")))
        assertEquals(listOf("Tax return 2024.pdf"), names(filterDocuments(items, "  2024   TAX ")))
        assertEquals(items, filterDocuments(items, "   "))
        assertTrue(filterDocuments(items, "invoice").isEmpty())
    }

    @Test
    fun reopenHintOnlyWithoutAnyLastingAccess() {
        val temporary = recent("content://mail/attachment/1", persistent = false).toDocumentItem(listOf(tree))
        assertTrue(temporary.needsReopen)

        val underFolder = recent(treeDoc("a.pdf"), persistent = false).toDocumentItem(listOf(tree))
        assertFalse(underFolder.needsReopen)

        val persisted = recent("content://x/document/1", persistent = true).toDocumentItem(emptyList())
        assertFalse(persisted.needsReopen)

        assertFalse(isCoveredByTreeGrant("$tree-other/document/x", listOf(tree)))
    }

    @Test
    fun folderFileIsEnrichedWithItsHistoryEntry() {
        val file = libraryFile("a.pdf", size = 0, modified = 42)
        val history = recent(file.uri, size = 99, favorite = true, lastPage = 1, pageCount = 10)

        val plain = file.toDocumentItem(null)
        assertFalse(plain.isRecent)
        assertNull(plain.sizeBytes)
        assertEquals(42L, plain.lastModified)

        val enriched = file.toDocumentItem(history)
        assertTrue(enriched.isRecent)
        assertTrue(enriched.isFavorite)
        assertEquals(99L, enriched.sizeBytes)
        assertEquals(ReadingProgress(2, 10), enriched.progress)
    }

    @Test
    fun assemblesTabsFoldersAndSearchResults() {
        val otherTree = "content://com.android.externalstorage.documents/tree/primary%3AWork"
        val recents = listOf(
            recent("r1", "Manual.pdf", openedAt = 2, favorite = true),
            recent("r2", "Invoice.pdf", openedAt = 1),
        )
        val folders = listOf(
            LibraryFolderEntity(treeUri = tree, displayName = "Books"),
            LibraryFolderEntity(treeUri = otherTree, displayName = "Work"),
        )
        val files = listOf(
            libraryFile("Novel.pdf"),
            libraryFile("Manual copy.pdf"),
            LibraryFileEntity(uri = "$otherTree/document/x", folderUri = otherTree, displayName = "Report.pdf", sizeBytes = 1, lastModified = 1),
        )
        val states = mapOf(otherTree to FolderScanState.ACCESS_LOST)

        val idle = assembleLibraryContent(recents, folders, files, LibrarySort.RECENT, "", null, states)
        assertEquals(listOf("Manual.pdf", "Invoice.pdf"), names(idle.recents))
        assertEquals(listOf("Manual.pdf"), names(idle.favorites))
        assertEquals(listOf("Books" to 2, "Work" to 1), idle.folders.map { it.name to it.pdfCount })
        assertEquals(FolderScanState.ACCESS_LOST, idle.folders[1].scanState)
        assertNull(idle.openedFolder)
        assertTrue(idle.folderFiles.isEmpty())
        assertEquals(2, idle.totalRecents)
        assertEquals(1, idle.totalFavorites)

        val opened = assembleLibraryContent(recents, folders, files, LibrarySort.NAME, "", tree, states)
        assertEquals("Books", opened.openedFolder?.name)
        assertEquals(listOf("Manual copy.pdf", "Novel.pdf"), names(opened.folderFiles))

        val searching = assembleLibraryContent(recents, folders, files, LibrarySort.NAME, "man", null, states)
        assertEquals(listOf("Manual.pdf"), names(searching.recents))
        assertEquals(listOf("Manual copy.pdf"), names(searching.folderFiles))
        assertEquals(2, searching.totalRecents)

        val removedFolder = assembleLibraryContent(recents, folders, files, LibrarySort.NAME, "", "content://gone", states)
        assertNull(removedFolder.openedFolder)
        assertTrue(removedFolder.folderFiles.isEmpty())
    }
}
