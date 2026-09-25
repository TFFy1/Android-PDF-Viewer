package io.github.tffy1.pdfviewer.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentDocumentDao {
    @Query("SELECT * FROM recent_documents ORDER BY lastOpenedAt DESC")
    fun observeAll(): Flow<List<RecentDocumentEntity>>

    @Query("SELECT * FROM recent_documents WHERE isFavorite = 1 ORDER BY displayName COLLATE NOCASE")
    fun observeFavorites(): Flow<List<RecentDocumentEntity>>

    @Query("SELECT * FROM recent_documents WHERE uri = :uri")
    suspend fun get(uri: String): RecentDocumentEntity?

    @Query("SELECT * FROM recent_documents WHERE uri = :uri")
    fun observe(uri: String): Flow<RecentDocumentEntity?>

    @Upsert
    suspend fun upsert(entity: RecentDocumentEntity)

    @Query("UPDATE recent_documents SET lastPage = :page WHERE uri = :uri")
    suspend fun updateLastPage(uri: String, page: Int)

    @Query("UPDATE recent_documents SET isFavorite = :favorite WHERE uri = :uri")
    suspend fun setFavorite(uri: String, favorite: Boolean)

    @Query("UPDATE recent_documents SET thumbnailPath = :path WHERE uri = :uri")
    suspend fun setThumbnail(uri: String, path: String?)

    @Query("DELETE FROM recent_documents WHERE uri = :uri")
    suspend fun delete(uri: String)

    @Query("DELETE FROM recent_documents WHERE isFavorite = 0")
    suspend fun clearNonFavorites()
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE documentUri = :uri ORDER BY pageIndex")
    fun observeForDocument(uri: String): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE documentUri = :uri AND pageIndex = :pageIndex")
    suspend fun delete(uri: String, pageIndex: Int)

    @Query("UPDATE bookmarks SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)
}

@Dao
interface AnnotationDao {
    @Query("SELECT * FROM annotations WHERE documentUri = :uri ORDER BY pageIndex, createdAt")
    fun observeForDocument(uri: String): Flow<List<AnnotationEntity>>

    @Query("SELECT * FROM annotations WHERE documentUri = :uri ORDER BY pageIndex, createdAt")
    suspend fun getForDocument(uri: String): List<AnnotationEntity>

    @Insert
    suspend fun insert(annotation: AnnotationEntity): Long

    @Upsert
    suspend fun upsert(annotation: AnnotationEntity)

    @Query("DELETE FROM annotations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM annotations WHERE documentUri = :uri")
    suspend fun deleteForDocument(uri: String)
}

@Dao
abstract class LibraryDao {
    @Query("SELECT * FROM library_folders ORDER BY displayName COLLATE NOCASE")
    abstract fun observeFolders(): Flow<List<LibraryFolderEntity>>

    @Query("SELECT * FROM library_folders")
    abstract suspend fun getFolders(): List<LibraryFolderEntity>

    @Upsert
    abstract suspend fun upsertFolder(folder: LibraryFolderEntity)

    @Query("DELETE FROM library_folders WHERE treeUri = :treeUri")
    abstract suspend fun deleteFolder(treeUri: String)

    @Query("SELECT * FROM library_files ORDER BY displayName COLLATE NOCASE")
    abstract fun observeFiles(): Flow<List<LibraryFileEntity>>

    @Query("DELETE FROM library_files WHERE folderUri = :folderUri")
    abstract suspend fun deleteFilesInFolder(folderUri: String)

    @Upsert
    abstract suspend fun upsertFiles(files: List<LibraryFileEntity>)

    /** Atomically replaces the scan results for one folder. */
    @Transaction
    open suspend fun replaceFilesInFolder(folderUri: String, files: List<LibraryFileEntity>) {
        deleteFilesInFolder(folderUri)
        upsertFiles(files)
    }
}
