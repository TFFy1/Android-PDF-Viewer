package io.github.tffy1.pdfviewer.ui.tools

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Rotate90DegreesCcw
import androidx.compose.material.icons.outlined.Rotate90DegreesCw
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tffy1.pdfviewer.AppContainer
import io.github.tffy1.pdfviewer.R
import io.github.tffy1.pdfviewer.appContainer
import io.github.tffy1.pdfviewer.pdf.PdfDocument
import io.github.tffy1.pdfviewer.pdf.PdfPasswordException
import io.github.tffy1.pdfviewer.tools.FileNames
import io.github.tffy1.pdfviewer.tools.PageEdit
import io.github.tffy1.pdfviewer.tools.PageEdits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Keeps the document open with the rendering engine while the grid is shown (for thumbnails);
 * saving re-reads the file with PdfBox.
 */
class OrganizeViewModel(container: AppContainer) : ToolViewModel(container) {
    var file by mutableStateOf<SelectedPdf?>(null)
        private set
    var pages by mutableStateOf<List<PageEdit>>(emptyList())
        private set

    /** Selected pages, by source index. */
    var selection by mutableStateOf<Set<Int>>(emptySet())
        private set

    private var document: PdfDocument? = null
    private var documentGeneration = 0
    private var openJob: Job? = null
    private val thumbnails = object : LruCache<Int, Bitmap>(THUMBNAIL_CACHE_BYTES) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

    val hasChanges: Boolean
        get() {
            val pdf = file ?: return false
            return pdf.isReady && !PageEdits.isUnchanged(pages, pdf.pageCount)
        }

    fun selectFile(uri: Uri) {
        if (isRunning) return
        dismissResult()
        closeDocument()
        val placeholder = SelectedPdf(id = newId(), uri = uri, displayName = "")
        file = placeholder
        pages = emptyList()
        selection = emptySet()
        open(placeholder, password = null)
    }

    fun unlock(password: String) {
        val current = file ?: return
        if (isRunning) return
        file = current.copy(status = PdfFileStatus.LOADING)
        open(current, password)
    }

    private fun open(pdf: SelectedPdf, password: String?) {
        openJob?.cancel()
        openJob = viewModelScope.launch {
            val info = container.documentAccess.queryInfo(pdf.uri)
            val base = pdf.copy(displayName = info.displayName, sizeBytes = info.sizeBytes, password = password)
            try {
                val opened = container.pdfEngine.open(pdf.uri, password)
                if (file?.id != pdf.id) {
                    opened.close()
                    return@launch
                }
                closeDocument()
                document = opened
                file = base.copy(pageCount = opened.pageCount, status = PdfFileStatus.READY)
                pages = PageEdits.initial(opened.pageCount)
                selection = emptySet()
            } catch (e: CancellationException) {
                throw e
            } catch (e: PdfPasswordException) {
                file = base.copy(status = if (e.wrongPassword) PdfFileStatus.WRONG_PASSWORD else PdfFileStatus.LOCKED)
            } catch (e: Exception) {
                file = base.copy(status = PdfFileStatus.UNREADABLE)
            }
        }
    }

    /** Thumbnail of a source page (rotation not applied; the UI rotates it). */
    suspend fun thumbnail(sourceIndex: Int): ImageBitmap? {
        thumbnails.get(sourceIndex)?.let { return it.asImageBitmap() }
        val doc = document ?: return null
        val generation = documentGeneration
        val bitmap = try {
            doc.renderThumbnail(sourceIndex, THUMBNAIL_WIDTH_PX)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Thumbnail failed", e)
            return null
        }
        if (generation != documentGeneration) return null
        thumbnails.put(sourceIndex, bitmap)
        return bitmap.asImageBitmap()
    }

    fun toggle(sourceIndex: Int) {
        selection = if (sourceIndex in selection) selection - sourceIndex else selection + sourceIndex
    }

    fun selectAll() {
        selection = pages.map { it.sourceIndex }.toSet()
    }

    fun clearSelection() {
        selection = emptySet()
    }

    fun rotateSelected(degrees: Int) = edit { PageEdits.rotate(it, selection, degrees) }

    fun moveSelectedEarlier() = edit { PageEdits.moveEarlier(it, selection) }

    fun moveSelectedLater() = edit { PageEdits.moveLater(it, selection) }

    fun deleteSelected() {
        edit { PageEdits.delete(it, selection) }
        selection = emptySet()
    }

    fun undoAll() {
        val pdf = file ?: return
        if (isRunning) return
        dismissResult()
        pages = PageEdits.initial(pdf.pageCount)
        selection = emptySet()
    }

    private inline fun edit(transform: (List<PageEdit>) -> List<PageEdit>) {
        if (isRunning || selection.isEmpty()) return
        dismissResult()
        pages = transform(pages)
    }

    fun suggestedName(): String = FileNames.withSuffix(file?.displayName.orEmpty(), "edited")

    fun save(destination: Uri) {
        val input = file?.takeIf { it.isReady }?.toInput() ?: return
        val edits = pages
        if (edits.isEmpty()) return
        runOperation(listOf(destination)) { progress -> toolkit.organize(input, edits, destination, progress) }
    }

    private fun closeDocument() {
        documentGeneration++
        thumbnails.evictAll()
        val doc = document ?: return
        document = null
        try {
            doc.close()
        } catch (e: Exception) {
            Log.w(TAG, "Closing document failed", e)
        }
    }

    override fun reset() {
        super.reset()
        openJob?.cancel()
        closeDocument()
        file = null
        pages = emptyList()
        selection = emptySet()
    }

    override fun onCleared() {
        closeDocument()
        super.onCleared()
    }

    private companion object {
        const val TAG = "OrganizeViewModel"
        const val THUMBNAIL_WIDTH_PX = 240
        const val THUMBNAIL_CACHE_BYTES = 24 * 1024 * 1024
    }
}

@Composable
internal fun OrganizeToolScreen(onBack: () -> Unit, onOpenDocument: (Uri) -> Unit) {
    val container = appContainer()
    val viewModel: OrganizeViewModel = viewModel { OrganizeViewModel(container) }
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    val busy = operation is OperationState.Running
    val context = LocalContext.current

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.selectFile(uri)
    }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PDF_MIME_TYPE)) { uri ->
        if (uri != null) viewModel.save(uri)
    }

    val file = viewModel.file
    val ready = file != null && file.isReady
    val pages = viewModel.pages
    val selection = viewModel.selection

    ToolScaffold(
        title = stringResource(PdfTool.ORGANIZE.titleRes),
        onBack = {
            viewModel.leave()
            onBack()
        },
        actions = {
            if (ready) {
                IconButton(onClick = viewModel::undoAll, enabled = viewModel.hasChanges && !busy) {
                    Icon(Icons.Outlined.RestartAlt, contentDescription = stringResource(R.string.tools_organize_reset))
                }
                TextButton(
                    onClick = { createDocument.launchSafely(viewModel.suggestedName(), context) },
                    enabled = pages.isNotEmpty() && !busy,
                ) {
                    Text(stringResource(R.string.tools_save))
                }
            }
        },
        bottomBar = {
            if (ready && pages.isNotEmpty()) {
                OrganizeActionBar(
                    enabled = !busy,
                    hasSelection = selection.isNotEmpty(),
                    allSelected = selection.size == pages.size,
                    onSelectAll = viewModel::selectAll,
                    onClearSelection = viewModel::clearSelection,
                    onRotateLeft = { viewModel.rotateSelected(-90) },
                    onRotateRight = { viewModel.rotateSelected(90) },
                    onMoveEarlier = viewModel::moveSelectedEarlier,
                    onMoveLater = viewModel::moveSelectedLater,
                    onDelete = viewModel::deleteSelected,
                )
            }
        },
    ) { padding ->
        if (file == null || !file.isReady) {
            ToolFormColumn(padding) {
                HelpText(stringResource(R.string.tools_organize_hint))
                SinglePdfSection(
                    file = file,
                    enabled = !busy,
                    onPick = { pickPdf.launchSafely(arrayOf(PDF_MIME_TYPE), context) },
                    onUnlock = viewModel::unlock,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PdfFileCard(file = file, onUnlock = null) {
                        TextButton(
                            onClick = { pickPdf.launchSafely(arrayOf(PDF_MIME_TYPE), context) },
                            enabled = !busy,
                        ) {
                            Text(stringResource(R.string.tools_change_file))
                        }
                    }
                    OperationStatus(
                        state = operation,
                        onCancel = viewModel::cancel,
                        onDismiss = viewModel::dismissResult,
                        onOpen = onOpenDocument,
                        onStartOver = viewModel::reset,
                    )
                    HelpText(
                        if (selection.isEmpty()) {
                            stringResource(R.string.tools_organize_hint)
                        } else {
                            pluralStringResource(R.plurals.tools_organize_selected, selection.size, selection.size)
                        },
                    )
                }
                if (pages.isEmpty()) {
                    HelpText(
                        text = stringResource(R.string.tools_organize_all_deleted),
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 104.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(pages, key = { _, page -> page.sourceIndex }) { _, page ->
                            PageCell(
                                page = page,
                                selected = page.sourceIndex in selection,
                                enabled = !busy,
                                loadThumbnail = viewModel::thumbnail,
                                onToggle = { viewModel.toggle(page.sourceIndex) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageCell(
    page: PageEdit,
    selected: Boolean,
    enabled: Boolean,
    loadThumbnail: suspend (Int) -> ImageBitmap?,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, page.sourceIndex) {
        value = loadThumbnail(page.sourceIndex)
    }
    val rotation by animateFloatAsState(targetValue = page.rotationDelta.toFloat(), label = "pageRotation")
    val pageNumber = page.sourceIndex + 1
    val normalized = PageEdits.normalizeRotation(page.rotationDelta)
    val description = if (normalized == 0) {
        stringResource(R.string.tools_organize_page, pageNumber)
    } else {
        stringResource(R.string.tools_organize_page_rotated, pageNumber, normalized)
    }
    val shape = RoundedCornerShape(8.dp)
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .clip(shape)
            .toggleable(value = selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = description }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .background(if (selected) colors.primaryContainer else colors.surfaceVariant)
                .border(
                    width = if (selected) 2.dp else 0.dp,
                    color = if (selected) colors.primary else Color.Transparent,
                    shape = shape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = thumbnail
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                        .graphicsLayer { rotationZ = rotation },
                )
            } else {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                )
            }
        }
        Text(
            text = pageNumber.toString(),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun OrganizeActionBar(
    enabled: Boolean,
    hasSelection: Boolean,
    allSelected: Boolean,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
    onDelete: () -> Unit,
) {
    val canEdit = enabled && hasSelection
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            if (allSelected) {
                IconButton(onClick = onClearSelection, enabled = enabled) {
                    Icon(Icons.Outlined.Deselect, contentDescription = stringResource(R.string.tools_organize_clear_selection))
                }
            } else {
                IconButton(onClick = onSelectAll, enabled = enabled) {
                    Icon(Icons.Outlined.SelectAll, contentDescription = stringResource(R.string.tools_organize_select_all))
                }
            }
            IconButton(onClick = onRotateLeft, enabled = canEdit) {
                Icon(Icons.Outlined.Rotate90DegreesCcw, contentDescription = stringResource(R.string.tools_organize_rotate_left))
            }
            IconButton(onClick = onRotateRight, enabled = canEdit) {
                Icon(Icons.Outlined.Rotate90DegreesCw, contentDescription = stringResource(R.string.tools_organize_rotate_right))
            }
            IconButton(onClick = onMoveEarlier, enabled = canEdit) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.tools_organize_move_earlier),
                )
            }
            IconButton(onClick = onMoveLater, enabled = canEdit) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.tools_organize_move_later),
                )
            }
            IconButton(onClick = onDelete, enabled = canEdit) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.tools_organize_delete))
            }
        }
    }
}
