package io.github.tffy1.pdfviewer.ui.tools

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import io.github.tffy1.pdfviewer.tools.CompressionLevel
import io.github.tffy1.pdfviewer.tools.FileNames
import io.github.tffy1.pdfviewer.tools.ImageSampling

class CompressViewModel(container: AppContainer) : SinglePdfToolViewModel(container) {
    var level by mutableStateOf(CompressionLevel.BALANCED)
        private set

    fun updateLevel(value: CompressionLevel) {
        dismissResult()
        level = value
    }

    fun suggestedName(): String = FileNames.withSuffix(file?.displayName.orEmpty(), "compressed")

    fun compress(destination: Uri) {
        val pdf = file?.takeIf { it.isReady } ?: return
        val input = pdf.toInput()
        val selectedLevel = level
        runOperation(listOf(destination)) { progress -> toolkit.compress(input, selectedLevel, destination, progress) }
    }

    override fun reset() {
        super.reset()
        level = CompressionLevel.BALANCED
    }
}

@Composable
internal fun CompressToolScreen(onBack: () -> Unit, onOpenDocument: (Uri) -> Unit) {
    val container = appContainer()
    val viewModel: CompressViewModel = viewModel { CompressViewModel(container) }
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    val busy = operation is OperationState.Running
    val context = LocalContext.current

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.selectFile(uri)
    }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PDF_MIME_TYPE)) { uri ->
        if (uri != null) viewModel.compress(uri)
    }

    ToolScaffold(
        title = stringResource(PdfTool.COMPRESS.titleRes),
        onBack = {
            viewModel.leave()
            onBack()
        },
    ) { padding ->
        ToolFormColumn(padding) {
            HelpText(stringResource(R.string.tools_compress_explainer))
            val file = viewModel.file
            SinglePdfSection(
                file = file,
                enabled = !busy,
                onPick = { pickPdf.launchSafely(arrayOf(PDF_MIME_TYPE), context) },
                onUnlock = viewModel::unlock,
            )
            if (file != null && file.isReady) {
                SectionTitle(stringResource(R.string.tools_compress_level))
                ChoiceRow(
                    options = CompressionLevel.entries,
                    selected = viewModel.level,
                    label = { level ->
                        when (level) {
                            CompressionLevel.STRONG -> stringResource(R.string.tools_compress_strong)
                            CompressionLevel.BALANCED -> stringResource(R.string.tools_compress_balanced)
                            CompressionLevel.LIGHT -> stringResource(R.string.tools_compress_light)
                        }
                    },
                    onSelect = viewModel::updateLevel,
                    enabled = !busy,
                )
                HelpText(
                    stringResource(
                        when (viewModel.level) {
                            CompressionLevel.STRONG -> R.string.tools_compress_strong_help
                            CompressionLevel.BALANCED -> R.string.tools_compress_balanced_help
                            CompressionLevel.LIGHT -> R.string.tools_compress_light_help
                        },
                    ),
                )
                PrimaryActionButton(
                    text = stringResource(R.string.tools_compress_action),
                    enabled = !busy,
                    onClick = { createDocument.launchSafely(viewModel.suggestedName(), context) },
                )
            }
            OperationStatus(
                state = operation,
                onCancel = viewModel::cancel,
                onDismiss = viewModel::dismissResult,
                onOpen = onOpenDocument,
                onStartOver = viewModel::reset,
                details = { result ->
                    val stats = result.compression
                    if (stats != null) {
                        val after = formatSize(context, stats.compressedBytes)
                        val sizes = stats.originalBytes?.let { before ->
                            stringResource(
                                R.string.tools_compress_result,
                                formatSize(context, before),
                                after,
                                ImageSampling.percentSaved(before, stats.compressedBytes),
                            )
                        } ?: stringResource(R.string.tools_compress_result_no_original, after)
                        Text(sizes, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            pluralStringResource(
                                R.plurals.tools_compress_images,
                                stats.imagesCompressed,
                                stats.imagesCompressed,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                },
            )
        }
    }
}
