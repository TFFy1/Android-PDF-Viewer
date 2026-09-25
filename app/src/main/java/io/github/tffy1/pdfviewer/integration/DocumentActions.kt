package io.github.tffy1.pdfviewer.integration

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import io.github.tffy1.pdfviewer.io.DocumentFileInfo
import io.github.tffy1.pdfviewer.pdf.DocumentMetadata

/* CONTRACT (scaffold). Owner: Android-integration agent. Keep these signatures. */
object DocumentActions {
    /** Opens the system share sheet for a PDF (content:// or app-private file). */
    fun share(context: Context, uri: Uri, displayName: String) { TODO("integration agent") }

    /** Prints the PDF through the Android print framework. */
    fun print(context: Context, uri: Uri, displayName: String) { TODO("integration agent") }

    /** Lets the user open the PDF in another app. */
    fun openWith(context: Context, uri: Uri) { TODO("integration agent") }

    /** Copies bytes of [source] into [destination] (from ACTION_CREATE_DOCUMENT). */
    suspend fun saveCopy(context: Context, source: Uri, destination: Uri): Result<Unit> =
        TODO("integration agent")
}

@Composable
fun DocumentPropertiesDialog(
    metadata: DocumentMetadata?,
    fileInfo: DocumentFileInfo?,
    pageCount: Int,
    onDismiss: () -> Unit,
) {
    TODO("integration agent")
}
