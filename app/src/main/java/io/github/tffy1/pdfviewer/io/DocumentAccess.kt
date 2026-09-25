package io.github.tffy1.pdfviewer.io

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

data class DocumentFileInfo(
    val displayName: String,
    val sizeBytes: Long?,
    val mimeType: String?,
)

/**
 * CONTRACT (scaffold) + implementation owned by the Android-integration agent.
 * Central place for turning URIs into readable data. Never requires broad storage permissions.
 */
class DocumentAccess(private val context: Context) {
    private val resolver: ContentResolver get() = context.contentResolver

    /** Opens a read-only descriptor. Caller owns (and must close) the result. */
    @Throws(FileNotFoundException::class, SecurityException::class)
    fun openFileDescriptor(uri: Uri): ParcelFileDescriptor = when (uri.scheme) {
        ContentResolver.SCHEME_FILE -> ParcelFileDescriptor.open(
            File(requireNotNull(uri.path) { "file uri without path" }),
            ParcelFileDescriptor.MODE_READ_ONLY,
        )
        else -> resolver.openFileDescriptor(uri, "r")
            ?: throw FileNotFoundException("Unable to open $uri")
    }

    suspend fun queryInfo(uri: Uri): DocumentFileInfo = withContext(Dispatchers.IO) {
        var name: String? = null
        var size: Long? = null
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val file = File(uri.path.orEmpty())
            name = file.name
            size = file.length().takeIf { file.exists() }
        } else {
            runCatching {
                resolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0 && !cursor.isNull(nameIdx)) name = cursor.getString(nameIdx)
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                    }
                }
            }
        }
        DocumentFileInfo(
            displayName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf",
            sizeBytes = size,
            mimeType = runCatching { resolver.getType(uri) }.getOrNull(),
        )
    }

    /**
     * Tries to keep read access across restarts. Returns true when access is persistent
     * (persisted grant, or a file:// / app-private URI that needs no grant).
     */
    fun takePersistableReadPermission(uri: Uri): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return true
        if (resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }) return true
        return runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        }.getOrDefault(false)
    }
}
