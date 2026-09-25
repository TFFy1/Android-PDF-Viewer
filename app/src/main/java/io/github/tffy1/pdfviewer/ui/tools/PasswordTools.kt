package io.github.tffy1.pdfviewer.ui.tools

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tffy1.pdfviewer.AppContainer
import io.github.tffy1.pdfviewer.R
import io.github.tffy1.pdfviewer.appContainer
import io.github.tffy1.pdfviewer.tools.FileNames
import io.github.tffy1.pdfviewer.tools.PdfPermissions

// ---------------------------------------------------------------------------------------------
// Remove password
// ---------------------------------------------------------------------------------------------

class RemovePasswordViewModel(container: AppContainer) : SinglePdfToolViewModel(container) {
    var password by mutableStateOf("")
        private set

    /** Protected files need a password; files that open without one may still carry an owner password. */
    val canRun: Boolean
        get() {
            val pdf = file ?: return false
            return pdf.isReady || (pdf.needsPassword && password.isNotEmpty())
        }

    fun updatePassword(value: String) {
        dismissResult()
        password = value
    }

    fun suggestedName(): String = FileNames.withSuffix(file?.displayName.orEmpty(), "unlocked")

    fun removePassword(destination: Uri) {
        if (!canRun) return
        val input = file?.toInput(passwordOverride = password.ifEmpty { null }) ?: return
        runOperation(listOf(destination)) { progress -> toolkit.removePassword(input, destination, progress) }
    }

    override fun onFileChanged() {
        password = ""
    }

    override fun reset() {
        super.reset()
        password = ""
    }
}

@Composable
internal fun RemovePasswordToolScreen(onBack: () -> Unit, onOpenDocument: (Uri) -> Unit) {
    val container = appContainer()
    val viewModel: RemovePasswordViewModel = viewModel { RemovePasswordViewModel(container) }
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    val busy = operation is OperationState.Running
    val context = LocalContext.current

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.selectFile(uri)
    }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PDF_MIME_TYPE)) { uri ->
        if (uri != null) viewModel.removePassword(uri)
    }
    val start = { createDocument.launchSafely(viewModel.suggestedName(), context) }

    ToolScaffold(
        title = stringResource(PdfTool.REMOVE_PASSWORD.titleRes),
        onBack = {
            viewModel.leave()
            onBack()
        },
    ) { padding ->
        ToolFormColumn(padding) {
            val file = viewModel.file
            // The password is typed below instead of in a dialog: it may be the owner password.
            SinglePdfSection(
                file = file,
                enabled = !busy,
                onPick = { pickPdf.launchSafely(arrayOf(PDF_MIME_TYPE), context) },
                onUnlock = viewModel::unlock,
                promptForPassword = false,
            )
            if (file != null && (file.isReady || file.needsPassword)) {
                PasswordField(
                    value = viewModel.password,
                    onValueChange = viewModel::updatePassword,
                    label = stringResource(R.string.tools_remove_password_field),
                    enabled = !busy,
                    supportingText = stringResource(R.string.tools_remove_password_help),
                    onDone = { if (viewModel.canRun && !busy) start() },
                )
                PrimaryActionButton(
                    text = stringResource(R.string.tools_remove_password_action),
                    enabled = viewModel.canRun && !busy,
                    onClick = start,
                )
            }
            OperationStatus(
                state = operation,
                onCancel = viewModel::cancel,
                onDismiss = viewModel::dismissResult,
                onOpen = onOpenDocument,
                onStartOver = viewModel::reset,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Add password
// ---------------------------------------------------------------------------------------------

class AddPasswordViewModel(container: AppContainer) : SinglePdfToolViewModel(container) {
    var password by mutableStateOf("")
        private set
    var confirmation by mutableStateOf("")
        private set
    var ownerPassword by mutableStateOf("")
        private set
    var permissions by mutableStateOf(PdfPermissions())
        private set

    val passwordTooShort: Boolean get() = password.isNotEmpty() && password.length < MIN_PASSWORD_LENGTH
    val mismatch: Boolean get() = confirmation.isNotEmpty() && confirmation != password
    val ownerSameAsPassword: Boolean get() = ownerPassword.isNotEmpty() && ownerPassword == password

    val canRun: Boolean
        get() = file?.isReady == true &&
            password.length >= MIN_PASSWORD_LENGTH &&
            confirmation == password &&
            !ownerSameAsPassword

    fun updatePassword(value: String) {
        dismissResult()
        password = value
    }

    fun updateConfirmation(value: String) {
        dismissResult()
        confirmation = value
    }

    fun updateOwnerPassword(value: String) {
        dismissResult()
        ownerPassword = value
    }

    fun updatePermissions(value: PdfPermissions) {
        dismissResult()
        permissions = value
    }

    fun suggestedName(): String = FileNames.withSuffix(file?.displayName.orEmpty(), "protected")

    fun protect(destination: Uri) {
        if (!canRun) return
        val input = file?.toInput() ?: return
        val user = password
        val owner = ownerPassword.ifEmpty { null }
        val selected = permissions
        runOperation(listOf(destination)) { progress ->
            toolkit.addPassword(input, user, owner, selected, destination, progress)
        }
    }

    override fun reset() {
        super.reset()
        password = ""
        confirmation = ""
        ownerPassword = ""
        permissions = PdfPermissions()
    }

    companion object {
        const val MIN_PASSWORD_LENGTH = 4
    }
}

@Composable
internal fun AddPasswordToolScreen(onBack: () -> Unit, onOpenDocument: (Uri) -> Unit) {
    val container = appContainer()
    val viewModel: AddPasswordViewModel = viewModel { AddPasswordViewModel(container) }
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    val busy = operation is OperationState.Running
    val context = LocalContext.current

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.selectFile(uri)
    }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PDF_MIME_TYPE)) { uri ->
        if (uri != null) viewModel.protect(uri)
    }

    ToolScaffold(
        title = stringResource(PdfTool.ADD_PASSWORD.titleRes),
        onBack = {
            viewModel.leave()
            onBack()
        },
    ) { padding ->
        ToolFormColumn(padding) {
            val file = viewModel.file
            SinglePdfSection(
                file = file,
                enabled = !busy,
                onPick = { pickPdf.launchSafely(arrayOf(PDF_MIME_TYPE), context) },
                onUnlock = viewModel::unlock,
            )
            if (file != null && file.isReady) {
                SectionTitle(stringResource(R.string.tools_add_password_field))
                PasswordField(
                    value = viewModel.password,
                    onValueChange = viewModel::updatePassword,
                    label = stringResource(R.string.tools_add_password_field),
                    enabled = !busy,
                    isError = viewModel.passwordTooShort,
                    supportingText = if (viewModel.passwordTooShort) {
                        stringResource(R.string.tools_add_password_too_short, AddPasswordViewModel.MIN_PASSWORD_LENGTH)
                    } else {
                        stringResource(R.string.tools_add_password_warning)
                    },
                    imeAction = ImeAction.Next,
                )
                PasswordField(
                    value = viewModel.confirmation,
                    onValueChange = viewModel::updateConfirmation,
                    label = stringResource(R.string.tools_add_password_confirm),
                    enabled = !busy,
                    isError = viewModel.mismatch,
                    supportingText = if (viewModel.mismatch) stringResource(R.string.tools_add_password_mismatch) else null,
                )

                SectionTitle(stringResource(R.string.tools_add_password_permissions))
                val permissions = viewModel.permissions
                PermissionCheckbox(R.string.tools_permission_print, permissions.print, !busy) {
                    viewModel.updatePermissions(permissions.copy(print = it))
                }
                PermissionCheckbox(R.string.tools_permission_copy, permissions.copy, !busy) {
                    viewModel.updatePermissions(permissions.copy(copy = it))
                }
                PermissionCheckbox(R.string.tools_permission_modify, permissions.modify, !busy) {
                    viewModel.updatePermissions(permissions.copy(modify = it))
                }
                PermissionCheckbox(R.string.tools_permission_annotate, permissions.annotate, !busy) {
                    viewModel.updatePermissions(permissions.copy(annotate = it))
                }
                PasswordField(
                    value = viewModel.ownerPassword,
                    onValueChange = viewModel::updateOwnerPassword,
                    label = stringResource(R.string.tools_add_password_owner),
                    enabled = !busy,
                    isError = viewModel.ownerSameAsPassword,
                    supportingText = if (viewModel.ownerSameAsPassword) {
                        stringResource(R.string.tools_add_password_same_owner)
                    } else {
                        stringResource(R.string.tools_add_password_owner_help)
                    },
                )
                HelpText(stringResource(R.string.tools_add_password_encryption))
                PrimaryActionButton(
                    text = stringResource(R.string.tools_add_password_action),
                    enabled = viewModel.canRun && !busy,
                    onClick = { createDocument.launchSafely(viewModel.suggestedName(), context) },
                )
            }
            OperationStatus(
                state = operation,
                onCancel = viewModel::cancel,
                onDismiss = viewModel::dismissResult,
                onOpen = onOpenDocument,
                onStartOver = viewModel::reset,
            )
        }
    }
}

@Composable
private fun PermissionCheckbox(labelRes: Int, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(labelRes), style = MaterialTheme.typography.bodyLarge)
    }
}
