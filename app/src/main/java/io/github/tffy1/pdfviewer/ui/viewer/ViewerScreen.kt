package io.github.tffy1.pdfviewer.ui.viewer

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tffy1.pdfviewer.appContainer
import io.github.tffy1.pdfviewer.ui.viewer.chrome.PasswordDialog
import io.github.tffy1.pdfviewer.ui.viewer.chrome.ViewerErrorScreen
import io.github.tffy1.pdfviewer.ui.viewer.chrome.ViewerLoadingScreen

/* CONTRACT (scaffold). Owner: viewer-screen agent. */
@Composable
fun ViewerScreen(
    uri: Uri,
    initialPage: Int,
    onBack: () -> Unit,
    onOpenDocument: (Uri) -> Unit,
) {
    val container = appContainer()
    // Keyed by URI: a single-top navigation to another document may reuse this back stack entry.
    val viewModel: ViewerViewModel = viewModel(key = "viewer:$uri") {
        ViewerViewModel(uri, initialPage, container, createSavedStateHandle())
    }
    val loadState by viewModel.loadState.collectAsStateWithLifecycle()

    when (val state = loadState) {
        ViewerLoadState.Loading -> ViewerLoadingScreen(onBack = onBack)
        is ViewerLoadState.NeedsPassword -> {
            ViewerLoadingScreen(onBack = onBack, locked = true)
            PasswordDialog(
                wrongPassword = state.wrongPassword,
                onSubmit = viewModel::submitPassword,
                onCancel = onBack,
            )
        }
        is ViewerLoadState.Error -> ViewerErrorScreen(
            kind = state.kind,
            message = state.message,
            onRetry = viewModel::retry,
            onBack = onBack,
        )
        is ViewerLoadState.Ready -> ViewerContent(
            viewModel = viewModel,
            session = state.session,
            onBack = onBack,
            onOpenDocument = onOpenDocument,
        )
    }
}
