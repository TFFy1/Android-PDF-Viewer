package io.github.tffy1.pdfviewer.ui.viewer.annotations

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import io.github.tffy1.pdfviewer.R

/** Dialog to write a new note, or view/edit/delete an existing one. */
@Composable
internal fun NoteEditorDialog(
    state: NoteEditorState,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable(state.pageIndex, state.annotationId, state.anchor.x, state.anchor.y) {
        mutableStateOf(state.initialText)
    }
    val focusRequester = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Filled.StickyNote2, contentDescription = null) },
        title = {
            Text(
                stringResource(
                    if (state.isNew) R.string.annotations_note_new_title else R.string.annotations_note_title,
                ),
            )
        },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                placeholder = { Text(stringResource(R.string.annotations_note_hint)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                minLines = 3,
                maxLines = 8,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(text) },
                enabled = !state.isNew || text.isNotBlank(),
            ) {
                Text(stringResource(R.string.annotations_note_save))
            }
        },
        dismissButton = {
            Row {
                if (!state.isNew) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )

    if (state.isNew) {
        LaunchedEffect(state) {
            // The dialog window composes its content a frame later; focus once it's attached.
            withFrameNanos { }
            try {
                focusRequester.requestFocus()
            } catch (e: IllegalStateException) {
                // Not attached (e.g. dialog already dismissed): the user can tap the field instead.
            }
        }
    }
}
