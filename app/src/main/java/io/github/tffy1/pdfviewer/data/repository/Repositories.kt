package io.github.tffy1.pdfviewer.data.repository

import io.github.tffy1.pdfviewer.data.db.AnnotationDao
import io.github.tffy1.pdfviewer.data.db.AnnotationEntity
import io.github.tffy1.pdfviewer.data.db.BookmarkDao
import io.github.tffy1.pdfviewer.data.db.BookmarkEntity
import io.github.tffy1.pdfviewer.data.db.LibraryDao
import io.github.tffy1.pdfviewer.data.db.LibraryFileEntity
import io.github.tffy1.pdfviewer.data.db.LibraryFolderEntity
import io.github.tffy1.pdfviewer.data.db.RecentDocumentDao
import io.github.tffy1.pdfviewer.data.db.RecentDocumentEntity
import kotlinx.coroutines.flow.Flow

class RecentDocumentsRepository(private val dao: RecentDocumentDao) {
    val recents: Flow<List<RecentDocumentEntity>> = dao.observeAll()
    val favorites: Flow<List<RecentDocumentEntity>> = dao.observeFavorites()

    fun observe(uri: String): Flow<RecentDocumentEntity?> = dao.observe(uri)
    suspend fun get(uri: String): RecentDocumentEntity? = dao.get(uri)

    /** Records that a document was opened, preserving favorite/lastPage/thumbnail if known. */
    suspend fun recordOpened(
        uri: String,
        displayName: String,
        sizeBytes: Long?,
        pageCount: Int,
        hasPersistentAccess: Boolean,
    ) {
        val existing = dao.get(uri)
        dao.upsert(
            (existing ?: RecentDocumentEntity(uri = uri, displayName = displayName)).copy(
                displayName = displayName,
                sizeBytes = sizeBytes ?: existing?.sizeBytes,
                pageCount = pageCount,
                lastOpenedAt = System.currentTimeMillis(),
                hasPersistentAccess = hasPersistentAccess,
            ),
        )
    }

    suspend fun updateLastPage(uri: String, page: Int) = dao.updateLastPage(uri, page)
    suspend fun setFavorite(uri: String, favorite: Boolean) = dao.setFavorite(uri, favorite)
    suspend fun setThumbnail(uri: String, path: String?) = dao.setThumbnail(uri, path)
    suspend fun remove(uri: String) = dao.delete(uri)
    suspend fun clearHistory() = dao.clearNonFavorites()
}

class BookmarksRepository(private val dao: BookmarkDao) {
    fun observe(documentUri: String): Flow<List<BookmarkEntity>> = dao.observeForDocument(documentUri)
    suspend fun add(documentUri: String, pageIndex: Int, title: String) {
        dao.insert(BookmarkEntity(documentUri = documentUri, pageIndex = pageIndex, title = title))
    }
    suspend fun remove(documentUri: String, pageIndex: Int) = dao.delete(documentUri, pageIndex)
    suspend fun rename(id: Long, title: String) = dao.rename(id, title)
}

class AnnotationsRepository(private val dao: AnnotationDao) {
    fun observe(documentUri: String): Flow<List<AnnotationEntity>> = dao.observeForDocument(documentUri)
    suspend fun getAll(documentUri: String): List<AnnotationEntity> = dao.getForDocument(documentUri)
    suspend fun add(annotation: AnnotationEntity): Long = dao.insert(annotation)
    suspend fun update(annotation: AnnotationEntity) =
        dao.upsert(annotation.copy(updatedAt = System.currentTimeMillis()))
    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun deleteAll(documentUri: String) = dao.deleteForDocument(documentUri)
}

class LibraryRepository(private val dao: LibraryDao) {
    val folders: Flow<List<LibraryFolderEntity>> = dao.observeFolders()
    val files: Flow<List<LibraryFileEntity>> = dao.observeFiles()

    suspend fun getFolders(): List<LibraryFolderEntity> = dao.getFolders()
    suspend fun addFolder(folder: LibraryFolderEntity) = dao.upsertFolder(folder)
    suspend fun removeFolder(treeUri: String) {
        dao.deleteFilesInFolder(treeUri)
        dao.deleteFolder(treeUri)
    }
    suspend fun replaceScanResults(folderUri: String, files: List<LibraryFileEntity>) =
        dao.replaceFilesInFolder(folderUri, files)
}
