package io.github.tffy1.pdfviewer.library

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import io.github.tffy1.pdfviewer.data.db.LibraryFileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

sealed interface FolderScanResult {
    data class Success(val files: List<LibraryFileEntity>, val truncated: Boolean) : FolderScanResult

    /** The persisted grant is gone (revoked, app data restored, provider changed); the user must pick the folder again. */
    data object AccessLost : FolderScanResult

    /** The provider could not list the folder (removed SD card, deleted folder, provider crash). */
    data object Unavailable : FolderScanResult
}

/**
 * Finds PDFs inside a SAF tree the user granted to the library.
 *
 * Uses raw [DocumentsContract] child queries instead of DocumentFile: one query per directory with
 * only the columns we need, which is dramatically faster on large trees.
 */
class FolderScanner(
    context: Context,
    private val limits: ScanLimits = ScanLimits(),
) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    suspend fun scan(treeUri: Uri): FolderScanResult = withContext(Dispatchers.IO) {
        if (!hasPersistedReadGrant(treeUri)) return@withContext FolderScanResult.AccessLost
        try {
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            val walk = walkDocumentTree(rootId, limits) { documentId -> listChildren(treeUri, documentId) }
            if (walk == null) {
                FolderScanResult.Unavailable
            } else {
                val folderKey = treeUri.toString()
                FolderScanResult.Success(
                    files = walk.pdfs.map { entry ->
                        LibraryFileEntity(
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.documentId).toString(),
                            folderUri = folderKey,
                            displayName = entry.name,
                            sizeBytes = entry.sizeBytes,
                            lastModified = entry.lastModified,
                        )
                    },
                    truncated = walk.truncated,
                )
            }
        } catch (e: SecurityException) {
            FolderScanResult.AccessLost
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // IllegalArgumentException for a malformed tree URI, or a RuntimeException from the provider.
            FolderScanResult.Unavailable
        }
    }

    /** Display name of the picked folder, falling back to the last segment of its document id. */
    suspend fun folderDisplayName(treeUri: Uri): String = withContext(Dispatchers.IO) {
        val treeId = catchingNonCancellation { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        val queried = treeId?.let { id ->
            catchingNonCancellation {
                resolver.query(
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null }
            }.getOrNull()
        }
        queried?.takeIf { it.isNotBlank() }
            ?: treeId?.let(::fallbackFolderName)
            ?: treeUri.lastPathSegment.orEmpty()
    }

    private fun hasPersistedReadGrant(treeUri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }

    /** Blocking child query; null when the provider returns no cursor for this directory. */
    private fun listChildren(treeUri: Uri, parentDocumentId: String): List<TreeEntry>? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val cursor = resolver.query(childrenUri, PROJECTION, null, null, null) ?: return null
        return cursor.use { c ->
            val idColumn = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeColumn = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedColumn = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            if (idColumn < 0) return@use emptyList<TreeEntry>()

            val entries = ArrayList<TreeEntry>(c.count.coerceAtLeast(0))
            while (c.moveToNext()) {
                val documentId = c.getString(idColumn) ?: continue
                entries += TreeEntry(
                    documentId = documentId,
                    name = if (nameColumn >= 0) c.getString(nameColumn).orEmpty() else "",
                    mimeType = if (mimeColumn >= 0) c.getString(mimeColumn) else null,
                    sizeBytes = if (sizeColumn >= 0 && !c.isNull(sizeColumn)) c.getLong(sizeColumn) else 0L,
                    lastModified = if (modifiedColumn >= 0 && !c.isNull(modifiedColumn)) c.getLong(modifiedColumn) else 0L,
                )
            }
            entries
        }
    }

    private companion object {
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
