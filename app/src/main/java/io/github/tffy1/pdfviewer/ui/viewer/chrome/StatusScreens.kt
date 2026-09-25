package io.github.tffy1.pdfviewer.ui.viewer.chrome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tffy1.pdfviewer.R
import io.github.tffy1.pdfviewer.ui.viewer.ViewerErrorKind

/** Shown while the document opens (and behind the password dialog). */
@Composable
fun ViewerLoadingScreen(onBack: () -> Unit, locked: Boolean = false) {
    StatusScaffold(onBack = onBack) {
        if (locked) {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.viewer_loading),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Explains why the document can't be shown and offers Retry / Back. */
@Composable
fun ViewerErrorScreen(
    kind: ViewerErrorKind,
    message: String?,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val icon: ImageVector
    val title: String
    val body: String
    when (kind) {
        ViewerErrorKind.NOT_FOUND -> {
            icon = Icons.Outlined.SearchOff
            title = stringResource(R.string.viewer_error_not_found_title)
            body = stringResource(R.string.viewer_error_not_found_message)
        }
        ViewerErrorKind.NO_PERMISSION -> {
            icon = Icons.Outlined.Lock
            title = stringResource(R.string.viewer_error_no_permission_title)
            body = stringResource(R.string.viewer_error_no_permission_message)
        }
        ViewerErrorKind.CORRUPT -> {
            icon = Icons.Outlined.BrokenImage
            title = stringResource(R.string.viewer_error_corrupt_title)
            body = stringResource(R.string.viewer_error_corrupt_message)
        }
        ViewerErrorKind.OTHER -> {
            icon = Icons.Outlined.ErrorOutline
            title = stringResource(R.string.error_generic)
            body = stringResource(R.string.viewer_error_other_message)
        }
    }
    // Retrying cannot restore a lost grant: the user has to pick the file again.
    val canRetry = kind != ViewerErrorKind.NO_PERMISSION

    StatusScaffold(onBack = onBack) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (!message.isNullOrBlank() && kind != ViewerErrorKind.NOT_FOUND) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.viewer_error_details, message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            if (canRetry) {
                Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusScaffold(onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            // Centered when it fits, scrollable when it doesn't (landscape phones, large fonts).
            Column(
                Modifier
                    .widthIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                content()
            }
        }
    }
}
