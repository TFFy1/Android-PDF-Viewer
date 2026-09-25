package io.github.tffy1.pdfviewer.integration

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.content.IntentCompat
import io.github.tffy1.pdfviewer.R
import java.io.File

/* CONTRACT (scaffold). Owner: Android-integration agent. */
object IncomingIntents {
    /**
     * Returns the PDF to open for a VIEW/SEND intent, or null if the intent isn't one.
     *
     * Only content:// and file:// URIs that look like PDFs are accepted (see [PdfIntentRules]).
     * file:// URIs pointing into this app's private data directory are always rejected so another
     * app cannot trick us into exposing our own files (confused deputy).
     *
     * When the intent *is* a VIEW/SEND request but the file is rejected, a short toast tells the
     * user why nothing opened. Never throws.
     */
    fun extractPdfUri(context: Context, intent: Intent?): Uri? {
        if (intent == null) return null
        val action = intent.action
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_SEND) return null

        val uri = runCatching { candidateUri(intent) }.getOrNull()
        val accepted = uri != null && runCatching { isAcceptable(context, intent, uri) }.getOrDefault(false)
        if (accepted) return uri

        runCatching {
            Toast.makeText(context, R.string.integration_error_unsupported_file, Toast.LENGTH_LONG).show()
        }
        return null
    }

    private fun candidateUri(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_VIEW -> intent.data ?: firstClipUri(intent)
        Intent.ACTION_SEND ->
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?: firstClipUri(intent)
        else -> null
    }

    private fun firstClipUri(intent: Intent): Uri? {
        val clip = intent.clipData ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0)?.uri
    }

    private fun isAcceptable(context: Context, intent: Intent, uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        if (!PdfIntentRules.isSupportedScheme(scheme)) return false

        if (scheme == ContentResolver.SCHEME_FILE) {
            val path = uri.path ?: return false
            val canonical = canonicalPath(File(path)) ?: return false
            val privateDirs = privateDataDirs(context)
            if (PdfIntentRules.isInsideAnyDirectory(canonical, privateDirs)) return false
            return PdfIntentRules.isAcceptablePdf(
                declaredMime = intent.type,
                resolvedMime = { null },
                displayName = { File(canonical).name },
            )
        }

        val resolver = context.contentResolver
        return PdfIntentRules.isAcceptablePdf(
            declaredMime = intent.type,
            resolvedMime = { runCatching { resolver.getType(uri) }.getOrNull() },
            displayName = { queryDisplayName(resolver, uri) ?: uri.lastPathSegment },
        )
    }

    /** Single-row, single-column query: cheap enough for the rare generic-mime case. */
    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getString(index) else null
        }
    }.getOrNull()

    /** Every directory that holds this app's private data, canonicalized (resolves /data/data symlinks). */
    private fun privateDataDirs(context: Context): List<String> {
        val info = context.applicationInfo
        return listOfNotNull(
            info.dataDir?.let(::File),
            info.deviceProtectedDataDir?.let(::File),
            context.filesDir?.parentFile,
            context.cacheDir?.parentFile,
            context.dataDir,
        ).mapNotNull { canonicalPath(it) }.distinct()
    }

    private fun canonicalPath(file: File): String? = runCatching { file.canonicalPath }.getOrNull()
}
