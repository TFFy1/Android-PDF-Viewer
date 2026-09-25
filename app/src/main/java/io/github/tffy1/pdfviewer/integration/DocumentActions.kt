package io.github.tffy1.pdfviewer.integration

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.print.PrintManager
import android.provider.DocumentsContract
import android.text.format.Formatter
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tffy1.pdfviewer.MainActivity
import io.github.tffy1.pdfviewer.R
import io.github.tffy1.pdfviewer.appContainer
import io.github.tffy1.pdfviewer.io.DocumentAccess
import io.github.tffy1.pdfviewer.io.DocumentFileInfo
import io.github.tffy1.pdfviewer.io.FileNameSanitizer
import io.github.tffy1.pdfviewer.pdf.DocumentMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream

/* CONTRACT (scaffold). Owner: Android-integration agent. Keep these signatures. */
object DocumentActions {
    private const val TAG = "DocumentActions"

    /**
     * Opens the system share sheet for a PDF (content:// or app-private file).
     *
     * content:// URIs are shared directly with a read grant. file:// URIs, and content URIs whose
     * grant we cannot pass on (e.g. an expired temporary grant), are first copied to
     * cacheDir/shared and shared through our FileProvider.
     */
    fun share(context: Context, uri: Uri, displayName: String) {
        val name = FileNameSanitizer.sanitize(displayName, requiredExtension = "pdf")
        launchWithShareableUri(
            context = context,
            uri = uri,
            displayName = name,
            errorMessage = R.string.integration_error_share,
        ) { shareable ->
            val send = Intent(Intent.ACTION_SEND).apply {
                type = PdfIntentRules.PDF_MIME
                putExtra(Intent.EXTRA_STREAM, shareable)
                putExtra(Intent.EXTRA_TITLE, name)
                clipData = ClipData.newRawUri(name, shareable)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Intent.createChooser(send, context.getString(R.string.integration_share_chooser_title))
        }
    }

    /** Prints the PDF through the Android print framework. */
    fun print(context: Context, uri: Uri, displayName: String) {
        val activity = context.findActivity()
        if (activity == null || activity.isFinishing) {
            toast(context, R.string.integration_error_print)
            return
        }
        val printManager = activity.getSystemService(PrintManager::class.java)
        if (printManager == null ||
            !activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PRINTING)
        ) {
            toast(context, R.string.integration_error_print_unavailable)
            return
        }
        val name = FileNameSanitizer.sanitize(displayName, requiredExtension = "pdf")
        try {
            // PrintManager requires an Activity context; it shows its own UI from here on.
            printManager.print(name, PdfPrintAdapter(activity, uri, name), null)
        } catch (e: RuntimeException) {
            Log.w(TAG, "print failed", e)
            toast(context, R.string.integration_error_print)
        }
    }

    /** Lets the user open the PDF in another app. */
    fun openWith(context: Context, uri: Uri) {
        launchWithShareableUri(
            context = context,
            uri = uri,
            displayName = null,
            errorMessage = R.string.integration_error_open_with,
        ) { shareable ->
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(shareable, PdfIntentRules.PDF_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Intent.createChooser(view, context.getString(R.string.integration_open_with_chooser_title))
        }
    }

    /** Copies bytes of [source] into [destination] (from ACTION_CREATE_DOCUMENT). */
    suspend fun saveCopy(context: Context, source: Uri, destination: Uri): Result<Unit> =
        withContext(Dispatchers.IO) {
            val documentAccess = context.appContainer.documentAccess
            val resolver = context.contentResolver
            try {
                documentAccess.openInputStream(source).use { input ->
                    openTruncatingOutputStream(resolver, destination).use { output ->
                        DocumentAccess.copyStream(input, output)
                    }
                }
                Result.success(Unit)
            } catch (e: CancellationException) {
                deletePartialDocument(context, destination)
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "saveCopy failed", e)
                deletePartialDocument(context, destination)
                Result.failure(e)
            }
        }

    /** "wt" truncates existing content; some providers (e.g. cloud drives) only accept "w". */
    private fun openTruncatingOutputStream(resolver: ContentResolver, uri: Uri): OutputStream {
        val truncating = try {
            resolver.openOutputStream(uri, "wt")
        } catch (e: FileNotFoundException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: UnsupportedOperationException) {
            null
        }
        return truncating
            ?: resolver.openOutputStream(uri, "w")
            ?: throw FileNotFoundException("Unable to write $uri")
    }

    /** The destination came from ACTION_CREATE_DOCUMENT, so a half-written file is ours to remove. */
    private fun deletePartialDocument(context: Context, uri: Uri) {
        runCatching {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            }
        }
    }

    /**
     * Resolves a URI other apps can read, builds the chooser with [buildChooser] and starts it.
     * Falls back to a FileProvider copy (off the main thread) when a direct grant is impossible.
     */
    private fun launchWithShareableUri(
        context: Context,
        uri: Uri,
        displayName: String?,
        @StringRes errorMessage: Int,
        buildChooser: (Uri) -> Intent,
    ) {
        val documentAccess = context.appContainer.documentAccess
        when (uri.scheme) {
            ContentResolver.SCHEME_CONTENT -> {
                try {
                    startChooser(context, buildChooser(uri))
                    return
                } catch (e: ActivityNotFoundException) {
                    toast(context, R.string.integration_error_no_app)
                    return
                } catch (e: SecurityException) {
                    // We can read but not re-grant (or lost the grant): try a private copy below.
                    if (documentAccess.isOwnFileProviderUri(uri)) {
                        toast(context, errorMessage)
                        return
                    }
                }
            }
            ContentResolver.SCHEME_FILE -> {
                // Already inside a FileProvider root (tool outputs, exports)? Share it in place.
                val direct = uri.path?.let { path ->
                    runCatching { documentAccess.shareableUri(File(path)) }.getOrNull()
                }
                if (direct != null) {
                    launchChooser(context, buildChooser(direct), errorMessage)
                    return
                }
            }
            else -> {
                toast(context, errorMessage)
                return
            }
        }

        context.appContainer.applicationScope.launch(Dispatchers.Main) {
            val shareable = try {
                val copy = documentAccess.copyToCache(uri, displayName)
                documentAccess.shareableUri(copy)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                Log.w(TAG, "no access to $uri", e)
                toast(context, R.string.integration_error_no_access)
                return@launch
            } catch (e: Exception) {
                Log.w(TAG, "copy for sharing failed", e)
                toast(context, errorMessage)
                return@launch
            }
            val activity = context.findActivity()
            if (activity != null && (activity.isFinishing || activity.isDestroyed)) return@launch
            launchChooser(context, buildChooser(shareable), errorMessage)
        }
    }

    private fun launchChooser(context: Context, chooser: Intent, @StringRes errorMessage: Int) {
        try {
            startChooser(context, chooser)
        } catch (e: ActivityNotFoundException) {
            toast(context, R.string.integration_error_no_app)
        } catch (e: SecurityException) {
            Log.w(TAG, "chooser failed", e)
            toast(context, errorMessage)
        }
    }

    private fun startChooser(context: Context, chooser: Intent) {
        // Our own viewer is pointless as a target when sharing / opening elsewhere.
        chooser.putExtra(
            Intent.EXTRA_EXCLUDE_COMPONENTS,
            arrayOf(ComponentName(context, MainActivity::class.java)),
        )
        if (context.findActivity() == null) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    private fun toast(context: Context, @StringRes message: Int) {
        runCatching { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }
}

private tailrec fun Context?.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun DocumentPropertiesDialog(
    metadata: DocumentMetadata?,
    fileInfo: DocumentFileInfo?,
    pageCount: Int,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val fileName = stringResource(R.string.integration_properties_file_name)
    val size = stringResource(R.string.integration_properties_size)
    val pages = stringResource(R.string.integration_properties_pages)
    val title = stringResource(R.string.integration_properties_document_title)
    val author = stringResource(R.string.integration_properties_author)
    val subject = stringResource(R.string.integration_properties_subject)
    val keywords = stringResource(R.string.integration_properties_keywords)
    val creator = stringResource(R.string.integration_properties_creator)
    val producer = stringResource(R.string.integration_properties_producer)
    val created = stringResource(R.string.integration_properties_created)
    val modified = stringResource(R.string.integration_properties_modified)

    val rows: List<Pair<String, String>> = remember(metadata, fileInfo, pageCount, locale) {
        listOf(
            fileName to fileInfo?.displayName,
            size to fileInfo?.sizeBytes?.takeIf { it >= 0 }?.let { Formatter.formatShortFileSize(context, it) },
            pages to pageCount.takeIf { it > 0 }?.let { String.format(locale, "%d", it) },
            title to metadata?.title,
            author to metadata?.author,
            subject to metadata?.subject,
            keywords to metadata?.keywords,
            creator to metadata?.creator,
            producer to metadata?.producer,
            created to PdfDates.format(metadata?.creationDate, locale),
            modified to PdfDates.format(metadata?.modificationDate, locale),
        ).mapNotNull { (label, value) ->
            value?.trim()?.takeIf { it.isNotEmpty() }?.let { label to it }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
        title = { Text(stringResource(R.string.integration_properties_title)) },
        text = {
            if (rows.isEmpty()) {
                Text(stringResource(R.string.integration_properties_empty))
            } else {
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        rows.forEach { (label, value) -> PropertyRow(label, value) }
                    }
                }
            }
        },
    )
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
