package io.github.tffy1.pdfviewer.ui.tools

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tffy1.pdfviewer.AppContainer
import io.github.tffy1.pdfviewer.R
import io.github.tffy1.pdfviewer.appContainer
import io.github.tffy1.pdfviewer.tools.PageRange
import io.github.tffy1.pdfviewer.tools.PageRangeError
import io.github.tffy1.pdfviewer.tools.PageRangeParseResult
import io.github.tffy1.pdfviewer.tools.PageRangeParser
import io.github.tffy1.pdfviewer.tools.SplitPlanner

enum class SplitMode { EVERY_N_PAGES, CUSTOM_RANGES }

sealed interface SplitPlan {
    /** No usable file yet, or the ranges field is still empty. */
    data object NotReady : SplitPlan
    data class InvalidCount(val maxPages: Int) : SplitPlan
    data class InvalidRanges(val error: PageRangeError) : SplitPlan
    data class Ready(val parts: List<PageRange>) : SplitPlan
}

class SplitViewModel(container: AppContainer) : SinglePdfToolViewModel(container) {
    var mode by mutableStateOf(SplitMode.EVERY_N_PAGES)
        private set
    var pagesPerFileText by mutableStateOf("1")
        private set
    var rangesText by mutableStateOf("")
        private set

    val plan: SplitPlan
        get() {
            val pdf = file?.takeIf { it.isReady && it.pageCount > 0 } ?: return SplitPlan.NotReady
            return when (mode) {
                SplitMode.EVERY_N_PAGES -> SplitPlanner.parsePagesPerFile(pagesPerFileText, pdf.pageCount)
                    ?.let { SplitPlan.Ready(SplitPlanner.everyNPages(pdf.pageCount, it)) }
                    ?: SplitPlan.InvalidCount(pdf.pageCount)
                SplitMode.CUSTOM_RANGES -> {
                    if (rangesText.isBlank()) return SplitPlan.NotReady
                    when (val parsed = PageRangeParser.parse(rangesText, pdf.pageCount)) {
                        is PageRangeParseResult.Valid -> SplitPlan.Ready(parsed.ranges)
                        is PageRangeParseResult.Invalid -> SplitPlan.InvalidRanges(parsed.error)
                    }
                }
            }
        }

    fun updateMode(value: SplitMode) {
        dismissResult()
        mode = value
    }

    fun updatePagesPerFile(value: String) {
        dismissResult()
        pagesPerFileText = value.filter { it.isDigit() }.take(6)
    }

    fun updateRanges(value: String) {
        dismissResult()
        rangesText = value
    }

    fun split(folderTreeUri: Uri) {
        val input = file?.toInput() ?: return
        val parts = (plan as? SplitPlan.Ready)?.parts ?: return
        runOperation(listOf(folderTreeUri)) { progress -> toolkit.split(input, parts, folderTreeUri, progress) }
    }

    override fun onFileChanged() {
        rangesText = ""
    }

    override fun reset() {
        super.reset()
        mode = SplitMode.EVERY_N_PAGES
        pagesPerFileText = "1"
        rangesText = ""
    }
}

@Composable
internal fun SplitToolScreen(onBack: () -> Unit, onOpenDocument: (Uri) -> Unit) {
    val container = appContainer()
    val viewModel: SplitViewModel = viewModel { SplitViewModel(container) }
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    val busy = operation is OperationState.Running
    val context = LocalContext.current

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.selectFile(uri)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.split(uri)
    }

    ToolScaffold(
        title = stringResource(PdfTool.SPLIT.titleRes),
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
                ChoiceRow(
                    options = SplitMode.entries,
                    selected = viewModel.mode,
                    label = { mode ->
                        when (mode) {
                            SplitMode.EVERY_N_PAGES -> stringResource(R.string.tools_split_mode_every)
                            SplitMode.CUSTOM_RANGES -> stringResource(R.string.tools_split_mode_ranges)
                        }
                    },
                    onSelect = viewModel::updateMode,
                    enabled = !busy,
                )
                val plan = viewModel.plan
                when (viewModel.mode) {
                    SplitMode.EVERY_N_PAGES -> OutlinedTextField(
                        value = viewModel.pagesPerFileText,
                        onValueChange = viewModel::updatePagesPerFile,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                        label = { Text(stringResource(R.string.tools_split_every_label)) },
                        singleLine = true,
                        isError = plan is SplitPlan.InvalidCount,
                        supportingText = {
                            Text(
                                when (plan) {
                                    is SplitPlan.InvalidCount ->
                                        stringResource(R.string.tools_split_every_invalid, plan.maxPages)
                                    is SplitPlan.Ready ->
                                        pluralStringResource(R.plurals.tools_split_preview, plan.parts.size, plan.parts.size)
                                    else -> ""
                                },
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    )
                    SplitMode.CUSTOM_RANGES -> PageRangeField(
                        value = viewModel.rangesText,
                        onValueChange = viewModel::updateRanges,
                        error = (plan as? SplitPlan.InvalidRanges)?.error,
                        helpText = if (plan is SplitPlan.Ready) {
                            pluralStringResource(R.plurals.tools_split_preview, plan.parts.size, plan.parts.size)
                        } else {
                            stringResource(R.string.tools_split_ranges_help)
                        },
                        enabled = !busy,
                    )
                }
                HelpText(stringResource(R.string.tools_split_folder_note))
                PrimaryActionButton(
                    text = stringResource(R.string.tools_split_action),
                    enabled = plan is SplitPlan.Ready && !busy,
                    onClick = { pickFolder.launchSafely(null, context) },
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
