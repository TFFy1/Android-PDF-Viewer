package io.github.tffy1.pdfviewer.ui.viewer.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tffy1.pdfviewer.R
import io.github.tffy1.pdfviewer.data.db.BookmarkEntity

/** Bookmarks of the open document: tap to jump, rename or delete; friendly empty state. */
@Composable
fun BookmarksSheet(
    bookmarks: List<BookmarkEntity>,
    currentPage: Int,
    onSelectPage: (Int) -> Unit,
    onRename: (BookmarkEntity) -> Unit,
    onDelete: (BookmarkEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    ViewerBottomSheet(title = stringResource(R.string.viewer_menu_bookmarks), onDismissRequest = onDismiss) { hide ->
        if (bookmarks.isEmpty()) {
            SheetMessage {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.Bookmarks,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.viewer_bookmarks_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    SheetMessageText(stringResource(R.string.viewer_bookmarks_empty_message))
                }
            }
        } else {
            LazyColumn {
                items(bookmarks, key = { it.id }) { bookmark ->
                    BookmarkRow(
                        bookmark = bookmark,
                        isCurrent = bookmark.pageIndex == currentPage,
                        onClick = {
                            onSelectPage(bookmark.pageIndex)
                            hide()
                        },
                        onRename = { onRename(bookmark) },
                        onDelete = { onDelete(bookmark) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BookmarkRow(
    bookmark: BookmarkEntity,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    ListItem(
        headlineContent = { Text(bookmark.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(stringResource(R.string.viewer_page_number, bookmark.pageIndex + 1)) },
        leadingContent = {
            Icon(
                Icons.Filled.Bookmark,
                contentDescription = null,
                tint = if (isCurrent) colors.primary else colors.onSurfaceVariant,
            )
        },
        trailingContent = {
            Row {
                IconButton(onClick = onRename) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = stringResource(R.string.viewer_bookmark_rename_description, bookmark.title),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.viewer_bookmark_delete_description, bookmark.title),
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = colors.surfaceContainerLow),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
