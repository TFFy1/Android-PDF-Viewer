package io.github.tffy1.pdfviewer.ui.tools

import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tffy1.pdfviewer.AppContainer
import io.github.tffy1.pdfviewer.R
import io.github.tffy1.pdfviewer.appContainer
import io.github.tffy1.pdfviewer.tools.FileNames
import io.github.tffy1.pdfviewer.tools.ImageDecoding
import io.github.tffy1.pdfviewer.tools.ImageInput
import io.github.tffy1.pdfviewer.tools.ImageMargin
import io.github.tffy1.pdfviewer.tools.ImagePageSize
import io.github.tffy1.pdfviewer.tools.ImageQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SelectedImage(val id: Long, val uri: Uri, val displayName: String)

class ImagesToPdfViewModel(container: AppContainer) : ToolViewModel(container) {
    var images by mutableStateOf<List<SelectedImage>>(emptyList())
        private set
    var pageSize by mutableStateOf(ImagePageSize.A4)
        private set
    var margin by mutableStateOf(ImageMargin.SMALL)
        private set
    var quality by mutableStateOf(ImageQuality.MEDIUM)
        private set

    private val thumbnails = object : LruCache<Uri, Bitmap>(THUMBNAIL_CACHE_BYTES) {
        override fun sizeOf(key: Uri, value: Bitmap): Int = value.byteCount
    }

    fun addImages(uris: List<Uri>) {
        if (isRunning) return
        dismissResult()
        val added = uris.map { SelectedImage(id = newId(), uri = it, displayName = "") }
        images = images + added
        added.forEach { image ->
            viewModelScope.launch {
                val name = container.documentAccess.queryInfo(image.uri).displayName
                images = images.map { if (it.id == image.id) it.copy(displayName = name) else it }
            }
        }
    }

    fun remove(id: Long) {
        if (isRunning) return
        dismissResult()
        images = images.filterNot { it.id == id }
    }

    fun move(id: Long, delta: Int) {
        if (isRunning) return
        val from = images.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in images.indices) return
        dismissResult()
        images = images.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun updatePageSize(value: ImagePageSize) {
        dismissResult()
        pageSize = value
    }

    fun updateMargin(value: ImageMargin) {
        dismissResult()
        margin = value
    }

    fun updateQuality(value: ImageQuality) {
        dismissResult()
        quality = value
    }

    /** Small preview for the list, with EXIF orientation applied; null when not decodable. */
    suspend fun thumbnail(uri: Uri): ImageBitmap? {
        thumbnails.get(uri)?.let { return it.asImageBitmap() }
        val bitmap = withContext(Dispatchers.IO) {
            try {
                ImageDecoding.decodeThumbnail(container.appContext.contentResolver, uri, THUMBNAIL_SIZE_PX)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
        } ?: return null
        thumbnails.put(uri, bitmap)
        return bitmap.asImageBitmap()
    }

    fun suggestedName(): String =
        images.firstOrNull()?.displayName?.takeIf { it.isNotEmpty() }
            ?.let { "${FileNames.baseName(it)}.pdf" } ?: "images.pdf"

    fun create(destination: Uri) {
        if (images.isEmpty()) return
        val inputs = images.map { ImageInput(it.uri, it.displayName.ifEmpty { it.uri.lastPathSegment.orEmpty() }) }
        val size = pageSize
        val selectedMargin = margin
        val selectedQuality = quality
        runOperation(listOf(destination)) { progress ->
            toolkit.imagesToPdf(inputs, size, selectedMargin, selectedQuality, destination, progress)
        }
    }

    override fun reset() {
        super.reset()
        images = emptyList()
        thumbnails.evictAll()
    }

    private companion object {
        const val THUMBNAIL_SIZE_PX = 160
        const val THUMBNAIL_CACHE_BYTES = 8 * 1024 * 1024
    }
}

@Composable
internal fun ImagesToPdfToolScreen(onBack: () -> Unit, onOpenDocument: (Uri) -> Unit) {
    val container = appContainer()
    val viewModel: ImagesToPdfViewModel = viewModel { ImagesToPdfViewModel(container) }
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    val busy = operation is OperationState.Running
    val context = LocalContext.current

    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) viewModel.addImages(uris)
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.addImages(uris)
    }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PDF_MIME_TYPE)) { uri ->
        if (uri != null) viewModel.create(uri)
    }

    ToolScaffold(
        title = stringResource(PdfTool.IMAGES_TO_PDF.titleRes),
        onBack = {
            viewModel.leave()
            onBack()
        },
    ) { padding ->
        ToolFormColumn(padding) {
            HelpText(stringResource(R.string.tools_images_hint))
            val images = viewModel.images
            if (images.isNotEmpty()) {
                SectionTitle(pluralStringResource(R.plurals.tools_images_count, images.size, images.size))
            }
            images.forEachIndexed { index, image ->
                ImageRow(
                    image = image,
                    loadThumbnail = viewModel::thumbnail,
                    canMoveUp = !busy && index > 0,
                    canMoveDown = !busy && index < images.lastIndex,
                    enabled = !busy,
                    onMoveUp = { viewModel.move(image.id, -1) },
                    onMoveDown = { viewModel.move(image.id, 1) },
                    onRemove = { viewModel.remove(image.id) },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        pickPhotos.launchSafely(
                            PickVisualMediaRequest(mediaType = ActivityResultContracts.PickVisualMedia.ImageOnly),
                            context,
                        )
                    },
                    enabled = !busy,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.tools_images_add_photos))
                }
                OutlinedButton(
                    onClick = { pickFiles.launchSafely(arrayOf("image/*"), context) },
                    enabled = !busy,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Outlined.Folder, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.tools_images_add_files))
                }
            }

            if (images.isNotEmpty()) {
                SectionTitle(stringResource(R.string.tools_images_page_size))
                ChoiceRow(
                    options = ImagePageSize.entries,
                    selected = viewModel.pageSize,
                    label = { size ->
                        when (size) {
                            ImagePageSize.A4 -> stringResource(R.string.tools_images_size_a4)
                            ImagePageSize.LETTER -> stringResource(R.string.tools_images_size_letter)
                            ImagePageSize.FIT_IMAGE -> stringResource(R.string.tools_images_size_fit)
                        }
                    },
                    onSelect = viewModel::updatePageSize,
                    enabled = !busy,
                )
                SectionTitle(stringResource(R.string.tools_images_margins))
                ChoiceRow(
                    options = ImageMargin.entries,
                    selected = viewModel.margin,
                    label = { margin ->
                        when (margin) {
                            ImageMargin.NONE -> stringResource(R.string.tools_margin_none)
                            ImageMargin.SMALL -> stringResource(R.string.tools_margin_small)
                            ImageMargin.LARGE -> stringResource(R.string.tools_margin_large)
                        }
                    },
                    onSelect = viewModel::updateMargin,
                    enabled = !busy,
                )
                SectionTitle(stringResource(R.string.tools_images_quality))
                ChoiceRow(
                    options = ImageQuality.entries,
                    selected = viewModel.quality,
                    label = { quality ->
                        when (quality) {
                            ImageQuality.LOW -> stringResource(R.string.tools_quality_low)
                            ImageQuality.MEDIUM -> stringResource(R.string.tools_quality_medium)
                            ImageQuality.HIGH -> stringResource(R.string.tools_quality_high)
                        }
                    },
                    onSelect = viewModel::updateQuality,
                    enabled = !busy,
                )
                HelpText(
                    stringResource(
                        when (viewModel.quality) {
                            ImageQuality.LOW -> R.string.tools_quality_low_help
                            ImageQuality.MEDIUM -> R.string.tools_quality_medium_help
                            ImageQuality.HIGH -> R.string.tools_quality_high_help
                        },
                    ),
                )
            }
            PrimaryActionButton(
                text = stringResource(R.string.tools_images_action),
                enabled = images.isNotEmpty() && !busy,
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
}

@Composable
private fun ImageRow(
    image: SelectedImage,
    loadThumbnail: suspend (Uri) -> ImageBitmap?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    enabled: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, image.uri) {
        value = loadThumbnail(image.uri)
    }
    val name = image.displayName
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = thumbnail
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp),
                    )
                } else {
                    Icon(Icons.Outlined.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                Icon(Icons.Outlined.ArrowUpward, contentDescription = stringResource(R.string.tools_move_up, name))
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                Icon(Icons.Outlined.ArrowDownward, contentDescription = stringResource(R.string.tools_move_down, name))
            }
            IconButton(onClick = onRemove, enabled = enabled) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.tools_remove_item, name))
            }
        }
    }
}
