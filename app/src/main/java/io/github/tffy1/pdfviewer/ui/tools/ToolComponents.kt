package io.github.tffy1.pdfviewer.ui.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tffy1.pdfviewer.R
import io.github.tffy1.pdfviewer.integration.DocumentActions
import io.github.tffy1.pdfviewer.tools.PageRangeError
import io.github.tffy1.pdfviewer.tools.ToolError
import io.github.tffy1.pdfviewer.tools.ToolOutput
import io.github.tffy1.pdfviewer.tools.ToolResult
import kotlin.math.roundToInt

internal const val PDF_MIME_TYPE = "application/pdf"

/** Top bar with a back arrow; handles system back. Insets are handled by the app scaffold. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = actions,
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        bottomBar = bottomBar,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        content = content,
    )
}

/** Scrollable form column used by most tools. */
@Composable
internal fun ToolFormColumn(padding: PaddingValues, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
internal fun HelpText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun PrimaryActionButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        Text(text)
    }
}

/** Full-width single-choice segmented control. */
@Composable
internal fun <T> ChoiceRow(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                enabled = enabled,
                label = { Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
internal fun pagesText(count: Int): String = pluralStringResource(R.plurals.tools_page_count, count, count)

internal fun formatSize(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

/** Launches a system picker, telling the user when no app can handle it. */
internal fun <I> ActivityResultLauncher<I>.launchSafely(input: I, context: Context) {
    try {
        launch(input)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.tools_no_picker, Toast.LENGTH_LONG).show()
    }
}

/** Row describing a picked PDF: name, pages/size or status, and optional trailing actions. */
@Composable
internal fun PdfFileCard(
    file: SelectedPdf,
    onUnlock: (() -> Unit)?,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val context = LocalContext.current
    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (file.needsPassword) Icons.Outlined.Lock else Icons.Outlined.PictureAsPdf,
                contentDescription = null,
                tint = if (file.status == PdfFileStatus.UNREADABLE) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.displayName.ifEmpty { stringResource(R.string.tools_reading_file) },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val detail: String? = when (file.status) {
                    // The title already says "Reading…" until the name is known.
                    PdfFileStatus.LOADING ->
                        if (file.displayName.isEmpty()) null else stringResource(R.string.tools_reading_file)
                    PdfFileStatus.READY -> {
                        val pages = pagesText(file.pageCount)
                        file.sizeBytes?.let {
                            stringResource(R.string.tools_file_details, pages, formatSize(context, it))
                        } ?: pages
                    }
                    PdfFileStatus.LOCKED -> stringResource(R.string.tools_file_locked)
                    PdfFileStatus.WRONG_PASSWORD -> stringResource(R.string.tools_file_wrong_password)
                    PdfFileStatus.UNREADABLE -> stringResource(R.string.tools_file_unreadable)
                }
                if (detail != null) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (file.status) {
                            PdfFileStatus.UNREADABLE, PdfFileStatus.WRONG_PASSWORD -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            if (file.status == PdfFileStatus.LOADING) {
                CircularProgressIndicator(modifier = Modifier.padding(horizontal = 12.dp).size(20.dp), strokeWidth = 2.dp)
            }
            if (file.needsPassword && onUnlock != null) {
                TextButton(onClick = onUnlock) { Text(stringResource(R.string.tools_unlock)) }
            }
            trailing()
        }
    }
}

/**
 * Picker + summary for tools with one PDF input. When [promptForPassword] is true the password
 * dialog opens automatically for protected files.
 */
@Composable
internal fun SinglePdfSection(
    file: SelectedPdf?,
    enabled: Boolean,
    onPick: () -> Unit,
    onUnlock: (String) -> Unit,
    promptForPassword: Boolean = true,
) {
    SectionTitle(stringResource(R.string.tools_section_file))
    if (file == null) {
        OutlinedButton(
            onClick = onPick,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
        ) {
            Icon(Icons.Outlined.PictureAsPdf, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.tools_choose_pdf))
        }
        return
    }

    var showPasswordDialog by remember { mutableStateOf(false) }
    LaunchedEffect(file.id, file.status) {
        if (promptForPassword && file.needsPassword) showPasswordDialog = true
    }
    PdfFileCard(
        file = file,
        onUnlock = if (promptForPassword && enabled) ({ showPasswordDialog = true }) else null,
    ) {
        TextButton(onClick = onPick, enabled = enabled) { Text(stringResource(R.string.tools_change_file)) }
    }
    if (showPasswordDialog && file.needsPassword) {
        PasswordDialog(
            fileName = file.displayName,
            wrongPassword = file.status == PdfFileStatus.WRONG_PASSWORD,
            onSubmit = { password ->
                showPasswordDialog = false
                onUnlock(password)
            },
            onDismiss = { showPasswordDialog = false },
        )
    }
}

@Composable
internal fun PasswordDialog(
    fileName: String,
    wrongPassword: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Deliberately not rememberSaveable: passwords must not end up in the saved-state bundle.
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
        title = { Text(stringResource(R.string.tools_password_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.tools_password_dialog_message, fileName))
                PasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.tools_password_label),
                    isError = wrongPassword,
                    supportingText = if (wrongPassword) stringResource(R.string.tools_password_incorrect) else null,
                    onDone = { if (password.isNotEmpty()) onSubmit(password) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(password) }, enabled = password.isNotEmpty()) {
                Text(stringResource(R.string.tools_unlock))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
internal fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    imeAction: ImeAction = ImeAction.Done,
    onDone: () -> Unit = {},
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        supportingText = if (supportingText != null) {
            { Text(supportingText) }
        } else {
            null
        },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = stringResource(
                        if (visible) R.string.tools_password_hide else R.string.tools_password_show,
                    ),
                )
            }
        },
    )
}

/** Page-range text field with validation feedback. */
@Composable
internal fun PageRangeField(
    value: String,
    onValueChange: (String) -> Unit,
    error: PageRangeError?,
    helpText: String,
    enabled: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(stringResource(R.string.tools_ranges_label)) },
        placeholder = { Text(stringResource(R.string.tools_ranges_placeholder)) },
        singleLine = true,
        isError = error != null,
        supportingText = { Text(error?.let { rangeErrorMessage(it) } ?: helpText) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
    )
}

@Composable
internal fun rangeErrorMessage(error: PageRangeError): String = when (error) {
    PageRangeError.Empty -> stringResource(R.string.tools_range_error_empty)
    is PageRangeError.InvalidToken -> stringResource(R.string.tools_range_error_invalid, error.token)
    is PageRangeError.PageOutOfRange ->
        stringResource(R.string.tools_range_error_out_of_range, error.page, error.pageCount)
    is PageRangeError.Reversed -> stringResource(R.string.tools_range_error_reversed, error.first, error.last)
}

@Composable
internal fun toolErrorMessage(error: ToolError): String = when (error) {
    is ToolError.PasswordRequired -> stringResource(R.string.tools_error_password_required, error.fileName)
    is ToolError.WrongPassword -> stringResource(R.string.tools_error_wrong_password, error.fileName)
    is ToolError.CannotRead -> stringResource(R.string.tools_error_cannot_read, error.fileName)
    ToolError.CannotWrite -> stringResource(R.string.tools_error_cannot_write)
    ToolError.NotEncrypted -> stringResource(R.string.tools_error_not_encrypted)
    ToolError.OwnerPasswordRequired -> stringResource(R.string.tools_error_owner_password)
    ToolError.NothingToCompress -> stringResource(R.string.tools_error_nothing_to_compress)
    ToolError.OutOfMemory -> stringResource(R.string.tools_error_out_of_memory)
    is ToolError.Unexpected -> error.message?.takeIf { it.isNotBlank() }
        ?.let { stringResource(R.string.tools_error_unexpected, it) }
        ?: stringResource(R.string.error_generic)
}

/**
 * Progress (with cancel), result (with Open/Share per output) or error card for the current
 * operation. Shows nothing when idle. [details] adds tool-specific lines to the result card.
 */
@Composable
internal fun OperationStatus(
    state: OperationState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onOpen: (Uri) -> Unit,
    onStartOver: () -> Unit,
    details: @Composable ColumnScope.(ToolResult.Success) -> Unit = {},
) {
    when (state) {
        OperationState.Idle -> Unit
        is OperationState.Running -> RunningCard(state.progress, onCancel)
        is OperationState.Finished -> ResultCard(state.result, onOpen, onStartOver, details)
        is OperationState.Failed -> ErrorCard(toolErrorMessage(state.error), onDismiss)
    }
}

@Composable
private fun RunningCard(progress: Float, onCancel: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.tools_working),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.tools_progress_percent, (progress * 100).roundToInt()),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

@Composable
private fun ResultCard(
    result: ToolResult.Success,
    onOpen: (Uri) -> Unit,
    onStartOver: () -> Unit,
    details: @Composable ColumnScope.(ToolResult.Success) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.tools_done_title), style = MaterialTheme.typography.titleMedium)
            }
            details(result)
            if (result.protectionRemoved) {
                Text(stringResource(R.string.tools_result_unprotected), style = MaterialTheme.typography.bodyMedium)
            }
            result.outputs.forEach { output -> OutputRow(output, onOpen) }
            TextButton(onClick = onStartOver, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.tools_start_over))
            }
        }
    }
}

@Composable
private fun OutputRow(output: ToolOutput, onOpen: (Uri) -> Unit) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(output.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            text = stringResource(
                R.string.tools_output_details,
                pagesText(output.pageCount),
                formatSize(context, output.sizeBytes),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            Button(onClick = { onOpen(output.uri) }) { Text(stringResource(R.string.action_open)) }
            OutlinedButton(onClick = { DocumentActions.share(context, output.uri, output.displayName) }) {
                Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_share))
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.tools_error_title), style = MaterialTheme.typography.titleMedium)
            }
            Text(message, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.tools_dismiss))
            }
        }
    }
}
