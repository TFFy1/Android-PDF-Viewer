package io.github.tffy1.pdfviewer.ui.tools

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tffy1.pdfviewer.AppContainer
import io.github.tffy1.pdfviewer.R
import io.github.tffy1.pdfviewer.appContainer
import io.github.tffy1.pdfviewer.tools.FileNames
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MergeViewModel(container: AppContainer) : ToolViewModel(container) {
    var files by mutableStateOf<List<SelectedPdf>>(emptyList())
        private set

    private val inspectJobs = mutableMapOf<Long, Job>()

    val canMerge: Boolean get() = files.size >= 2 && files.all { it.isReady }
    val totalPages: Int get() = files.sumOf { it.pageCount }

    fun addFiles(uris: List<Uri>) {
        if (isRunning) return
        dismissResult()
        val added = uris.map { SelectedPdf(id = newId(), uri = it, displayName = "") }
        files = files + added
        added.forEach { inspect(it, password = null) }
    }

    fun unlock(id: Long, password: String) {
        val file = files.firstOrNull { it.id == id } ?: return
        if (isRunning) return
        update(id) { it.copy(status = PdfFileStatus.LOADING) }
        inspect(file, password)
    }

    fun remove(id: Long) {
        if (isRunning) return
        dismissResult()
        inspectJobs.remove(id)?.cancel()
        files = files.filterNot { it.id == id }
    }

    /** Moves a file one place up ([delta] = -1) or down (+1). */
    fun move(id: Long, delta: Int) {
        if (isRunning) return
        val from = files.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in files.indices) return
        dismissResult()
        files = files.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun suggestedName(): String =
        files.firstOrNull()?.displayName?.takeIf { it.isNotEmpty() }
            ?.let { FileNames.withSuffix(it, "merged") } ?: "merged.pdf"

    fun merge(destination: Uri) {
        if (!canMerge) return
        val inputs = files.map { it.toInput() }
        runOperation(listOf(destination)) { progress -> toolkit.merge(inputs, destination, progress) }
    }

    private fun inspect(pdf: SelectedPdf, password: String?) {
        inspectJobs[pdf.id]?.cancel()
        inspectJobs[pdf.id] = viewModelScope.launch {
            val inspected = inspectPdf(pdf, password)
            update(pdf.id) { inspected }
            inspectJobs.remove(pdf.id)
        }
    }

    private fun update(id: Long, transform: (SelectedPdf) -> SelectedPdf) {
        files = files.map { if (it.id == id) transform(it) else it }
    }

    override fun reset() {
        super.reset()
        inspectJobs.values.forEach { it.cancel() }
        inspectJobs.clear()
        files = emptyList()
    }
}

@Composable
internal fun MergeToolScreen(onBack: () -> Unit, onOpenDocument: (Uri) -> Unit) {
    val container = appContainer()
    val viewModel: MergeViewModel = viewModel { MergeViewModel(container) }
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    val busy = operation is OperationState.Running
    val context = LocalContext.current
    var unlockId by remember { mutableStateOf<Long?>(null) }

    val pickPdfs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.addFiles(uris)
    }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PDF_MIME_TYPE)) { uri ->
        if (uri != null) viewModel.merge(uri)
    }

    ToolScaffold(
        title = stringResource(PdfTool.MERGE.titleRes),
        onBack = {
            viewModel.leave()
            onBack()
        },
    ) { padding ->
        ToolFormColumn(padding) {
            HelpText(stringResource(R.string.tools_merge_hint))
            val files = viewModel.files
            if (files.isNotEmpty()) SectionTitle(stringResource(R.string.tools_section_files))
            files.forEachIndexed { index, file ->
                val name = file.displayName
                PdfFileCard(file = file, onUnlock = if (busy) null else ({ unlockId = file.id })) {
                    IconButton(onClick = { viewModel.move(file.id, -1) }, enabled = !busy && index > 0) {
                        Icon(Icons.Outlined.ArrowUpward, contentDescription = stringResource(R.string.tools_move_up, name))
                    }
                    IconButton(onClick = { viewModel.move(file.id, 1) }, enabled = !busy && index < files.lastIndex) {
                        Icon(
                            Icons.Outlined.ArrowDownward,
                            contentDescription = stringResource(R.string.tools_move_down, name),
                        )
                    }
                    IconButton(onClick = { viewModel.remove(file.id) }, enabled = !busy) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.tools_remove_item, name))
                    }
                }
            }
            OutlinedButton(
                onClick = { pickPdfs.launchSafely(arrayOf(PDF_MIME_TYPE), context) },
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.tools_add_pdfs))
            }
            if (files.any { it.isReady }) {
                HelpText(stringResource(R.string.tools_merge_total, pagesText(viewModel.totalPages)))
            }
            PrimaryActionButton(
                text = stringResource(R.string.tools_merge_action),
                enabled = viewModel.canMerge && !busy,
                onClick = { createDocument.launchSafely(viewModel.suggestedName(), context) },
            )
            OperationStatus(
                state = operation,
                onCancel = viewModel::cancel,
                onDismiss = viewModel::dismissResult,
                onOpen = onOpenDocument,
                onStartOver = viewModel::reset,
            )
        }
    }

    val unlockTarget = viewModel.files.firstOrNull { it.id == unlockId }
    if (unlockTarget != null) {
        PasswordDialog(
            fileName = unlockTarget.displayName,
            wrongPassword = unlockTarget.status == PdfFileStatus.WRONG_PASSWORD,
            onSubmit = { password ->
                unlockId = null
                viewModel.unlock(unlockTarget.id, password)
            },
            onDismiss = { unlockId = null },
        )
    }
}
