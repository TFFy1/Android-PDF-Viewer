package io.github.tffy1.pdfviewer.ui.library

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.LockReset
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tffy1.pdfviewer.R
import io.github.tffy1.pdfviewer.library.DocumentItem
import io.github.tffy1.pdfviewer.library.FolderItem
import io.github.tffy1.pdfviewer.library.FolderScanState

/** What a document row can do; one instance is shared by every row of the screen. */
class DocumentRowActions(
    val onOpen: (DocumentItem) -> Unit,
    val onToggleFavorite: (DocumentItem) -> Unit,
    val onRemoveFromRecents: (DocumentItem) -> Unit,
    val onShare: (DocumentItem) -> Unit,
)

private const val META_SEPARATOR = " · "

@Composable
internal fun DocumentRow(
    item: DocumentItem,
    coverLoader: CoverLoader,
    now: Long,
    actions: DocumentRowActions,
    modifier: Modifier = Modifier,
) {
    ListItem(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .clickable(onClickLabel = stringResource(R.string.action_open)) { actions.onOpen(item) },
        leadingContent = { DocumentCover(item, coverLoader) },
        headlineContent = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { DocumentDetails(item, now) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.isRecent) {
                    IconToggleButton(
                        checked = item.isFavorite,
                        onCheckedChange = { actions.onToggleFavorite(item) },
                    ) {
                        Icon(
                            imageVector = if (item.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = stringResource(R.string.library_favorite),
                            tint = if (item.isFavorite) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                        )
                    }
                }
                DocumentMenu(item, actions)
            }
        },
    )
}

@Composable
private fun DocumentMenu(item: DocumentItem, actions: DocumentRowActions) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.library_more_options_for, item.name))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_share)) },
                leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                onClick = {
                    expanded = false
                    actions.onShare(item)
                },
            )
            if (item.isRecent) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_remove_from_recents)) },
                    leadingIcon = { Icon(Icons.Outlined.RemoveCircleOutline, contentDescription = null) },
                    onClick = {
                        expanded = false
                        actions.onRemoveFromRecents(item)
                    },
                )
            }
        }
    }
}

@Composable
private fun DocumentDetails(item: DocumentItem, now: Long) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item.progress?.let { progress ->
            Text(stringResource(R.string.library_page_progress, progress.page, progress.pageCount))
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
            )
        }
        val timeText = when {
            item.lastOpenedAt != null ->
                if (now - item.lastOpenedAt < DateUtils.MINUTE_IN_MILLIS) {
                    stringResource(R.string.library_opened_just_now)
                } else {
                    stringResource(
                        R.string.library_opened_ago,
                        DateUtils.getRelativeTimeSpanString(
                            item.lastOpenedAt,
                            now,
                            DateUtils.MINUTE_IN_MILLIS,
                            DateUtils.FORMAT_ABBREV_RELATIVE,
                        ),
                    )
                }
            item.lastModified != null -> stringResource(
                R.string.library_modified_on,
                DateUtils.formatDateTime(
                    context,
                    item.lastModified,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH,
                ),
            )
            else -> null
        }
        val sizeText = item.sizeBytes?.let { Formatter.formatShortFileSize(context, it) }
        val meta = listOfNotNull(timeText, sizeText).joinToString(META_SEPARATOR)
        if (meta.isNotEmpty()) Text(meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (item.needsReopen) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.LinkOff,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    stringResource(R.string.library_reopen_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

/** First-page cover, or a PDF glyph while it loads or when none can be made. */
@Composable
private fun DocumentCover(item: DocumentItem, coverLoader: CoverLoader) {
    val cover by produceState(coverLoader.cached(item.uri), item.uri, item.thumbnailPath) {
        if (value == null) value = coverLoader.load(item.uri, item.thumbnailPath)
    }
    Surface(
        modifier = Modifier.size(width = 44.dp, height = 60.dp),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        val image = cover
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                alignment = Alignment.TopCenter,
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.PictureAsPdf,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
internal fun FolderRow(
    folder: FolderItem,
    onOpen: (FolderItem) -> Unit,
    onRegrant: (FolderItem) -> Unit,
    onRescan: (FolderItem) -> Unit,
    onRemove: (FolderItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accessLost = folder.scanState == FolderScanState.ACCESS_LOST
    var menuExpanded by remember { mutableStateOf(false) }
    ListItem(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .clickable { if (accessLost) onRegrant(folder) else onOpen(folder) },
        leadingContent = {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(12.dp),
                color = if (accessLost) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        if (accessLost) Icons.Outlined.FolderOff else Icons.Outlined.Folder,
                        contentDescription = null,
                    )
                }
            }
        },
        headlineContent = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { FolderStatus(folder) },
        trailingContent = {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.library_more_options_for, folder.name),
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    if (accessLost) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_folder_regrant)) },
                            leadingIcon = { Icon(Icons.Outlined.LockReset, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onRegrant(folder)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_folder_rescan)) },
                        leadingIcon = { Icon(Icons.Outlined.Sync, contentDescription = null) },
                        enabled = folder.scanState != FolderScanState.SCANNING,
                        onClick = {
                            menuExpanded = false
                            onRescan(folder)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_folder_remove)) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onRemove(folder)
                        },
                    )
                }
            }
        },
    )
}

@Composable
internal fun FolderStatus(folder: FolderItem) {
    val count = pluralStringResource(R.plurals.library_folder_pdf_count, folder.pdfCount, folder.pdfCount)
    when (folder.scanState) {
        FolderScanState.SCANNING -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.library_folder_scanning))
        }
        FolderScanState.ACCESS_LOST -> Text(
            stringResource(R.string.library_folder_access_lost),
            color = MaterialTheme.colorScheme.error,
        )
        FolderScanState.UNAVAILABLE -> Text(
            stringResource(R.string.library_folder_unavailable) + META_SEPARATOR + count,
            color = MaterialTheme.colorScheme.error,
        )
        FolderScanState.LIMIT_REACHED -> Text(stringResource(R.string.library_folder_limit_reached, folder.pdfCount))
        FolderScanState.IDLE -> Text(count)
    }
}

/**
 * Icon + title + body (+ action) placeholder. Scrollable and at least as tall as its container so
 * it stays centered and still works inside pull-to-refresh.
 */
@Composable
internal fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .fillMaxWidth()
                .heightIn(min = maxHeight)
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            EmptyStateBody(icon, title, body, action)
        }
    }
}

/** The non-scrolling part of [EmptyState], for use inside lists. */
@Composable
internal fun EmptyStateBody(
    icon: ImageVector,
    title: String,
    body: String,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier.widthIn(max = 420.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.size(96.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(44.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(24.dp))
            action()
        }
    }
}
