package io.github.tffy1.pdfviewer.io

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

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

    /** Authority of our FileProvider. Uses packageName so the ".debug" build variant works too. */
    val fileProviderAuthority: String get() = "${context.packageName}.fileprovider"

    /** True for content:// URIs served by this app's own FileProvider. */
    fun isOwnFileProviderUri(uri: Uri): Boolean =
        uri.scheme == ContentResolver.SCHEME_CONTENT && uri.authority == fileProviderAuthority

    /**
     * Copies [uri] into `cacheDir/shared/<sanitized name>` so it can be handed to other apps
     * through the FileProvider. The copy is written to a temp file first and then renamed, so
     * copying a file onto itself (or a failed copy) never destroys an existing file.
     * Old copies (> 1 day) are cleaned up opportunistically.
     */
    @Throws(IOException::class, SecurityException::class)
    suspend fun copyToCache(uri: Uri, displayName: String? = null): File = withContext(Dispatchers.IO) {
        val name = FileNameSanitizer.sanitize(displayName ?: queryInfo(uri).displayName, requiredExtension = "pdf")
        val dir = File(context.cacheDir, SHARED_CACHE_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
        deleteStaleSharedFiles(dir)

        val target = File(dir, name)
        val temp = File(dir, ".$name.${UUID.randomUUID()}.tmp")
        try {
            openInputStream(uri).use { input ->
                temp.outputStream().use { output -> copyStream(input, output) }
            }
            if (target.exists() && !target.delete()) throw IOException("Cannot replace $target")
            if (!temp.renameTo(target)) throw IOException("Cannot rename to $target")
        } finally {
            if (temp.exists()) temp.delete()
        }
        target
    }

    /**
     * content:// URI for a file inside one of the FileProvider roots (see res/xml/file_paths.xml).
     * @throws IllegalArgumentException when [file] is outside those roots.
     */
    fun shareableUri(file: File): Uri = FileProvider.getUriForFile(context, fileProviderAuthority, file)

    /** Opens [uri] (content:// or file://) for reading. Caller closes the stream. */
    @Throws(FileNotFoundException::class, SecurityException::class)
    fun openInputStream(uri: Uri): InputStream =
        resolver.openInputStream(uri) ?: throw FileNotFoundException("Unable to open $uri")

    private fun deleteStaleSharedFiles(dir: File) {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) file.delete()
        }
    }

    companion object {
        /** Sub-directory of cacheDir exposed by the FileProvider as "shared". */
        const val SHARED_CACHE_DIR = "shared"

        private const val COPY_BUFFER_SIZE = 64 * 1024

        /** Buffered copy that stops promptly when the calling coroutine is cancelled. */
        suspend fun copyStream(input: InputStream, output: OutputStream): Long {
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                total += read
            }
            output.flush()
            return total
        }
    }
}

/**
 * Turns arbitrary display names (from other apps, PDF titles, …) into safe single-segment file
 * names. Pure Kotlin so it can be unit-tested.
 */
object FileNameSanitizer {
    private const val DEFAULT_BASE_NAME = "document"

    /** Keeps names comfortably under the 255-byte limit of common file systems. */
    private const val MAX_BYTES = 200

    private val FORBIDDEN_CHARS = Regex("[\\\\/:*?\"<>|\\x00-\\x1F\\x7F]")
    private val WHITESPACE_RUNS = Regex("[\\s\\p{Z}]+")

    /**
     * Removes path separators, control and reserved characters, trims leading/trailing dots and
     * spaces, and bounds the length (in UTF-8 bytes) without splitting characters.
     * When [requiredExtension] is given (without dot) the result is guaranteed to end with it.
     */
    fun sanitize(name: String?, requiredExtension: String? = null): String {
        val cleaned = name.orEmpty()
            .replace(FORBIDDEN_CHARS, "_")
            .replace(WHITESPACE_RUNS, " ")
            .trim()

        val ext = requiredExtension?.trim('.', ' ')?.takeIf { it.isNotEmpty() }
        var base = cleaned
        var suffix = ""
        if (ext != null) {
            suffix = ".$ext"
            if (cleaned.endsWith(suffix, ignoreCase = true)) {
                // Keep the original casing of the extension, e.g. "Report.PDF".
                suffix = cleaned.takeLast(suffix.length)
                base = cleaned.dropLast(suffix.length)
            }
        } else {
            val dot = cleaned.lastIndexOf('.')
            if (dot > 0 && cleaned.length - dot in 2..10) {
                suffix = cleaned.substring(dot)
                base = cleaned.substring(0, dot)
            }
        }

        // Leading dots would make hidden files; trailing dots/spaces are invalid on some FSs.
        base = base.trim(' ', '.')
        base = truncateUtf8(base, MAX_BYTES - suffix.utf8Size()).trimEnd(' ', '.')
        return base.ifEmpty { DEFAULT_BASE_NAME } + suffix
    }

    private fun truncateUtf8(text: String, maxBytes: Int): String {
        if (text.utf8Size() <= maxBytes) return text
        val out = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            val chars = Character.charCount(codePoint)
            val size = text.substring(i, i + chars).utf8Size()
            if (bytes + size > maxBytes) break
            out.append(text, i, i + chars)
            bytes += size
            i += chars
        }
        return out.toString()
    }

    private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size
}
