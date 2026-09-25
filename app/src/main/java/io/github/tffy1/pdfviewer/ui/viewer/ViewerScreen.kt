package io.github.tffy1.pdfviewer.ui.viewer

import android.net.Uri
import androidx.compose.runtime.Composable

/* CONTRACT (scaffold). Owner: viewer-screen agent. */
@Composable
fun ViewerScreen(
    uri: Uri,
    initialPage: Int,
    onBack: () -> Unit,
    onOpenDocument: (Uri) -> Unit,
) {
    TODO("viewer-screen agent")
}
