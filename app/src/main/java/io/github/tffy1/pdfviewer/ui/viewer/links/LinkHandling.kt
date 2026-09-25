package io.github.tffy1.pdfviewer.ui.viewer.links

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.tffy1.pdfviewer.R
import io.github.tffy1.pdfviewer.pdf.PdfDocument
import io.github.tffy1.pdfviewer.pdf.PdfLink
import io.github.tffy1.pdfviewer.ui.viewer.document.PageTap
import java.util.WeakHashMap
import kotlinx.coroutines.CancellationException

/* CONTRACT (scaffold). Owner: search/selection/links agent. Keep these signatures. */

/** Returns the link under [tap], if any (links are cached per page by the implementation). */
suspend fun findLinkAt(document: PdfDocument, tap: PageTap): PdfLink? {
    if (tap.pageIndex !in 0 until document.pageCount) return null
    val links = LinkCache.linksFor(document, tap.pageIndex) ?: return null
    if (links.isEmpty()) return null
    return hitTestLinks(links, tap.point)
}

/**
 * Per-document LRU of page links. Weakly keyed by the document object so closed documents are
 * dropped with it; all access is synchronized because taps may be resolved concurrently.
 */
private object LinkCache {
    private const val PAGES_PER_DOCUMENT = 8
    private val documents = WeakHashMap<PdfDocument, LinkedHashMap<Int, List<PdfLink>>>()

    suspend fun linksFor(document: PdfDocument, pageIndex: Int): List<PdfLink>? {
        synchronized(documents) { documents[document]?.get(pageIndex) }?.let { return it }
        val links = try {
            document.links(pageIndex)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null // Treat an unreadable page as having no links; retry on the next tap.
        }
        synchronized(documents) {
            documents.getOrPut(document) { newPageLru() }[pageIndex] = links
        }
        return links
    }

    private fun newPageLru() = object : LinkedHashMap<Int, List<PdfLink>>(PAGES_PER_DOCUMENT + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<PdfLink>>?): Boolean =
            size > PAGES_PER_DOCUMENT
    }
}

/**
 * Asks the user before leaving the app for an external link, then opens it with ACTION_VIEW.
 * Shows the full URL so users can spot phishing links.
 *
 * Only http(s), mailto and tel links can be opened; anything else (intent:, file:, javascript:…)
 * is shown with a copy option only, so a PDF can never launch arbitrary components.
 */
@Composable
fun ExternalLinkDialog(uri: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val target = remember(uri) { normalizeExternalUri(uri) ?: uri }
    val canOpen = remember(target) { isAllowedExternalUri(target) }
    val host = remember(target) { externalUriHost(target) }
    val clipLabel = stringResource(R.string.links_clip_label)
    val copy: () -> Unit = {
        context.copyPlainText(clipLabel, target, R.string.links_copied)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Link, contentDescription = null) },
        title = {
            Text(stringResource(if (canOpen) R.string.links_open_title else R.string.links_blocked_title))
        },
        text = {
            Column {
                Text(
                    stringResource(if (canOpen) R.string.links_open_message else R.string.links_blocked_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (canOpen && host != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.links_host, host),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(12.dp))
                SelectionContainer(
                    Modifier
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        target,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            if (canOpen) {
                TextButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, target.toUri())
                            .addCategory(Intent.CATEGORY_BROWSABLE)
                        if (!context.startActivitySafely(intent)) {
                            Toast.makeText(context, R.string.links_no_app, Toast.LENGTH_SHORT).show()
                        }
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.action_open))
                }
            } else {
                TextButton(onClick = copy) { Text(stringResource(R.string.links_copy)) }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                if (canOpen) {
                    TextButton(onClick = copy) { Text(stringResource(R.string.links_copy)) }
                }
            }
        },
    )
}
