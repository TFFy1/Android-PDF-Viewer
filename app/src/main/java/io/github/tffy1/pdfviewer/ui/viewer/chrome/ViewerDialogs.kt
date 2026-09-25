package io.github.tffy1.pdfviewer.ui.viewer.chrome

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.tffy1.pdfviewer.R
import io.github.tffy1.pdfviewer.ui.viewer.PageInput
import io.github.tffy1.pdfviewer.ui.viewer.parsePageInput

/** Asks for a 1-based page number and calls [onGoToPage] with the 0-based index. */
@Composable
fun GoToPageDialog(
    pageCount: Int,
    onGoToPage: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val input = parsePageInput(text, pageCount)
    val showError = input is PageInput.Invalid || input is PageInput.OutOfRange
    val focusRequester = remember { FocusRequester() }
    val submit: () -> Unit = { if (input is PageInput.Valid) onGoToPage(input.pageIndex) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.viewer_go_to_page_title)) },
        text = {
            OutlinedTextField(
                value = text,
                // Digits only (any script); page numbers never need more than a few of them.
                onValueChange = { value -> text = value.filter { it.isDigit() }.take(MAX_PAGE_DIGITS) },
                label = { Text(stringResource(R.string.viewer_go_to_page_label)) },
                supportingText = {
                    Text(
                        if (showError) {
                            stringResource(R.string.viewer_go_to_page_invalid, pageCount)
                        } else {
                            stringResource(R.string.viewer_go_to_page_range, pageCount)
                        },
                    )
                },
                isError = showError,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
            LaunchedEffect(focusRequester) { focusRequester.requestFocus() }
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = input is PageInput.Valid) {
                Text(stringResource(R.string.viewer_go))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Password prompt for protected documents. Cancelling leaves the viewer. */
@Composable
fun PasswordDialog(
    wrongPassword: Boolean,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    // Deliberately not saveable: a password must not end up in the saved instance state.
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val submit: () -> Unit = { if (password.isNotEmpty()) onSubmit(password) }

    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
        title = { Text(stringResource(R.string.viewer_password_title)) },
        text = {
            Column {
                Text(stringResource(R.string.viewer_password_message))
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.viewer_password_label)) },
                    supportingText = if (wrongPassword) {
                        { Text(stringResource(R.string.viewer_password_wrong)) }
                    } else {
                        null
                    },
                    isError = wrongPassword,
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = stringResource(
                                    if (visible) R.string.viewer_password_hide else R.string.viewer_password_show,
                                ),
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                        autoCorrectEnabled = false,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
                LaunchedEffect(focusRequester) { focusRequester.requestFocus() }
            }
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = password.isNotEmpty()) {
                Text(stringResource(R.string.action_open))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Edits a bookmark title; the confirm button is disabled for blank names. */
@Composable
fun RenameBookmarkDialog(
    initialTitle: String,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    val focusRequester = remember { FocusRequester() }
    val submit: () -> Unit = { if (title.isNotBlank()) onRename(title.trim()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.viewer_bookmark_rename_title)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(MAX_BOOKMARK_TITLE_LENGTH) },
                label = { Text(stringResource(R.string.viewer_bookmark_name_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
            LaunchedEffect(focusRequester) { focusRequester.requestFocus() }
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = title.isNotBlank()) {
                Text(stringResource(R.string.action_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Single-choice list (radio buttons); selecting an option applies it and closes the dialog. */
@Composable
fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                options.forEach { (value, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = value == selected,
                                role = Role.RadioButton,
                                onClick = { onSelect(value) },
                            )
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The whole row handles the click, so the radio itself stays non-interactive.
                        RadioButton(selected = value == selected, onClick = null)
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

private const val MAX_PAGE_DIGITS = 6
private const val MAX_BOOKMARK_TITLE_LENGTH = 200
