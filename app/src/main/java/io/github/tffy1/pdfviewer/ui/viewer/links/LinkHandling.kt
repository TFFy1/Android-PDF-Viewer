package io.github.tffy1.pdfviewer.ui.viewer.links

import androidx.compose.runtime.Composable
import io.github.tffy1.pdfviewer.pdf.PdfDocument
import io.github.tffy1.pdfviewer.pdf.PdfLink
import io.github.tffy1.pdfviewer.ui.viewer.document.PageTap

/* CONTRACT (scaffold). Owner: search/selection/links agent. Keep these signatures. */

/** Returns the link under [tap], if any (links are cached per page by the implementation). */
suspend fun findLinkAt(document: PdfDocument, tap: PageTap): PdfLink? = TODO("links agent")

/**
 * Asks the user before leaving the app for an external link, then opens it with ACTION_VIEW.
 * Shows the full URL so users can spot phishing links.
 */
@Composable
fun ExternalLinkDialog(uri: String, onDismiss: () -> Unit) {
    TODO("links agent")
}
