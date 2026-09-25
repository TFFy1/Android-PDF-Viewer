package io.github.tffy1.pdfviewer.ui.tools

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tffy1.pdfviewer.AppContainer
import io.github.tffy1.pdfviewer.R
import io.github.tffy1.pdfviewer.appContainer
import io.github.tffy1.pdfviewer.tools.FileNames
import io.github.tffy1.pdfviewer.tools.PageRangeError
import io.github.tffy1.pdfviewer.tools.PageRangeParseResult
import io.github.tffy1.pdfviewer.tools.PageRangeParser

sealed interface PageSelection {
    data object NotReady : PageSelection
    data class Invalid(val error: PageRangeError) : PageSelection
    data class Ready(val pageIndices: List<Int>) : PageSelection
}

class ExtractViewModel(container: AppContainer) : SinglePdfToolViewModel(container) {
    var rangesText by mutableStateOf("")
        private set

    val selection: PageSelection
        get() {
            val pdf = file?.takeIf { it.isReady && it.pageCount > 0 } ?: return PageSelection.NotReady
            if (rangesText.isBlank()) return PageSelection.NotReady
            return when (val parsed = PageRangeParser.parse(rangesText, pdf.pageCount)) {
                is PageRangeParseResult.Valid -> PageSelection.Ready(parsed.distinctPageIndices)
                is PageRangeParseResult.Invalid -> PageSelection.Invalid(parsed.error)
            }
        }

    fun updateRanges(value: String) {
        dismissResult()
        rangesText = value
    }

    fun suggestedName(): String = FileNames.withSuffix(file?.displayName.orEmpty(), "pages")

    fun extract(destination: Uri) {
        val input = file?.toInput() ?: return
        val pages = (selection as? PageSelection.Ready)?.pageIndices ?: return
        runOperation(listOf(destination)) { progress -> toolkit.extractPages(input, pages, destination, progress) }
    }

    override fun onFileChanged() {
        rangesText = ""
    }

    override fun reset() {
        super.reset()
        rangesText = ""
    }
}

@Composable
internal fun ExtractToolScreen(onBack: () -> Unit, onOpenDocument: (Uri) -> Unit) {
    val container = appContainer()
    val viewModel: ExtractViewModel = viewModel { ExtractViewModel(container) }
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    val busy = operation is OperationState.Running
    val context = LocalContext.current

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.selectFile(uri)
    }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PDF_MIME_TYPE)) { uri ->
        if (uri != null) viewModel.extract(uri)
    }

    ToolScaffold(
        title = stringResource(PdfTool.EXTRACT.titleRes),
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
                SectionTitle(stringResource(R.string.tools_section_options))
                val selection = viewModel.selection
                PageRangeField(
                    value = viewModel.rangesText,
                    onValueChange = viewModel::updateRanges,
                    error = (selection as? PageSelection.Invalid)?.error,
                    helpText = if (selection is PageSelection.Ready) {
                        pluralStringResource(
                            R.plurals.tools_extract_preview,
                            selection.pageIndices.size,
                            selection.pageIndices.size,
                        )
                    } else {
                        stringResource(R.string.tools_extract_help)
                    },
                    enabled = !busy,
                )
                PrimaryActionButton(
                    text = stringResource(R.string.tools_extract_action),
                    enabled = selection is PageSelection.Ready && !busy,
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
