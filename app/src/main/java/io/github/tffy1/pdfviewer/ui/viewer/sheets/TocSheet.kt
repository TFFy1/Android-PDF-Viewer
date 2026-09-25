package io.github.tffy1.pdfviewer.ui.viewer.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tffy1.pdfviewer.R
import io.github.tffy1.pdfviewer.pdf.TocEntry
import io.github.tffy1.pdfviewer.ui.viewer.LazyResource

/** Document outline as an expandable tree; the section being read is highlighted and revealed. */
@Composable
fun TocSheet(
    toc: LazyResource<List<TocEntry>>,
    currentPage: Int,
    onSelectPage: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ViewerBottomSheet(title = stringResource(R.string.viewer_menu_contents), onDismissRequest = onDismiss) { hide ->
        when (toc) {
            LazyResource.Idle, LazyResource.Loading -> SheetMessage { CircularProgressIndicator() }
            LazyResource.Failed -> SheetMessage { SheetMessageText(stringResource(R.string.viewer_contents_error)) }
            is LazyResource.Loaded -> {
                if (toc.value.isEmpty()) {
                    SheetMessage { SheetMessageText(stringResource(R.string.viewer_contents_empty)) }
                } else {
                    TocTree(
                        entries = toc.value,
                        currentPage = currentPage,
                        onSelectPage = { page ->
                            onSelectPage(page)
                            hide()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TocTree(entries: List<TocEntry>, currentPage: Int, onSelectPage: (Int) -> Unit) {
    val currentKey = remember(entries) { currentTocKey(entries, currentPage) }
    var expanded by remember(entries) { mutableStateOf(currentKey?.let(::ancestorKeys) ?: emptySet()) }
    val rows = remember(entries, expanded) { flattenToc(entries, expanded) }
    val listState = rememberLazyListState()

    LaunchedEffect(entries) {
        val index = rows.indexOfFirst { it.key == currentKey }
        if (index > 0) listState.scrollToItem(index)
    }

    LazyColumn(state = listState) {
        items(rows, key = { it.key }) { row ->
            TocRowItem(
                row = row,
                isCurrent = row.key == currentKey,
                onClick = {
                    if (row.entry.pageIndex >= 0) {
                        onSelectPage(row.entry.pageIndex)
                    } else if (row.hasChildren) {
                        expanded = if (row.expanded) expanded - row.key else expanded + row.key
                    }
                },
                onToggle = { expanded = if (row.expanded) expanded - row.key else expanded + row.key },
            )
        }
    }
}

@Composable
private fun TocRowItem(row: TocRow, isCurrent: Boolean, onClick: () -> Unit, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(start = 24.dp + INDENT * row.depth, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.entry.title,
            style = if (row.depth == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
            fontWeight = if (isCurrent) FontWeight.SemiBold else null,
            color = if (isCurrent) colors.primary else Color.Unspecified,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp),
        )
        if (row.entry.pageIndex >= 0) {
            Text(
                text = (row.entry.pageIndex + 1).toString(),
                style = MaterialTheme.typography.labelLarge,
                color = if (isCurrent) colors.primary else colors.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        if (row.hasChildren) {
            IconButton(onClick = onToggle) {
                Icon(
                    if (row.expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = stringResource(
                        if (row.expanded) R.string.viewer_contents_collapse else R.string.viewer_contents_expand,
                        row.entry.title,
                    ),
                )
            }
        } else {
            // Keeps page numbers aligned with rows that have an expand button.
            Spacer(Modifier.width(48.dp))
        }
    }
}

@Composable
internal fun SheetMessage(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 160.dp)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
internal fun SheetMessageText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

private val INDENT = 16.dp
