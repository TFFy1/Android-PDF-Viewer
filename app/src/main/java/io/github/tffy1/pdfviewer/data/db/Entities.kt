package io.github.tffy1.pdfviewer.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A document the user opened. Keyed by the URI string used to open it. */
@Entity(tableName = "recent_documents")
data class RecentDocumentEntity(
    @PrimaryKey val uri: String,
    val displayName: String,
    val sizeBytes: Long? = null,
    val pageCount: Int = 0,
    val lastPage: Int = 0,
    val lastOpenedAt: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
    /** Absolute path of a cached thumbnail PNG in app storage, if generated. */
    val thumbnailPath: String? = null,
    /** False when we only had a temporary grant and the URI can't be reopened later. */
    val hasPersistentAccess: Boolean = true,
)

@Entity(
    tableName = "bookmarks",
    indices = [Index(value = ["documentUri", "pageIndex"], unique = true)],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentUri: String,
    val pageIndex: Int,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * User annotations kept in-app (non-destructive) until exported into a PDF copy.
 * [type] is an AnnotationType name. [payload] is JSON owned by the annotations
 * feature (rects for markup, stroke points for ink, anchor for notes), in page space.
 */
@Entity(
    tableName = "annotations",
    indices = [Index(value = ["documentUri", "pageIndex"])],
)
data class AnnotationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentUri: String,
    val pageIndex: Int,
    val type: String,
    /** ARGB color. */
    val color: Int,
    val payload: String,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** A folder (SAF tree URI) the user added to the library for scanning. */
@Entity(tableName = "library_folders")
data class LibraryFolderEntity(
    @PrimaryKey val treeUri: String,
    val displayName: String,
    val addedAt: Long = System.currentTimeMillis(),
)

/** Result of scanning library folders for PDFs. */
@Entity(
    tableName = "library_files",
    indices = [Index(value = ["folderUri"])],
)
data class LibraryFileEntity(
    @PrimaryKey val uri: String,
    val folderUri: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
)
