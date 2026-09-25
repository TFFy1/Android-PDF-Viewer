package io.github.tffy1.pdfviewer.ui.viewer.chrome

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.github.tffy1.pdfviewer.R

/** Actions offered by the viewer's overflow menu (some are promoted to icons on wide screens). */
enum class ViewerMenuAction {
    GO_TO_PAGE,
    CONTENTS,
    PAGES,
    BOOKMARKS,
    READING_MODE,
    SCROLL_DIRECTION,
    ANNOTATE,
    SAVE_ANNOTATED_COPY,
    SHARE,
    PRINT,
    OPEN_WITH,
    SAVE_COPY,
    PROPERTIES,
}

/** Insets for bars that overlay the document: stable even while the system bars are hidden. */
@OptIn(ExperimentalLayoutApi::class)
internal val viewerTopBarInsets: WindowInsets
    @Composable get() = WindowInsets.systemBarsIgnoringVisibility
        .union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerTopBar(
    title: String,
    isBookmarked: Boolean,
    canSaveAnnotatedCopy: Boolean,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onToggleBookmark: () -> Unit,
    onMenuAction: (ViewerMenuAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    // On tablets / landscape there is room to promote navigation actions out of the overflow menu.
    val wide = LocalConfiguration.current.screenWidthDp >= WIDE_SCREEN_DP
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
        },
        actions = {
            IconButton(onClick = onSearch) {
                Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.viewer_search))
            }
            IconButton(onClick = onToggleBookmark) {
                if (isBookmarked) {
                    Icon(
                        Icons.Filled.Bookmark,
                        contentDescription = stringResource(R.string.viewer_bookmark_remove),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(Icons.Outlined.BookmarkBorder, contentDescription = stringResource(R.string.viewer_bookmark_add))
                }
            }
            if (wide) {
                IconButton(onClick = { onMenuAction(ViewerMenuAction.CONTENTS) }) {
                    Icon(Icons.AutoMirrored.Outlined.List, contentDescription = stringResource(R.string.viewer_menu_contents))
                }
                IconButton(onClick = { onMenuAction(ViewerMenuAction.PAGES) }) {
                    Icon(Icons.Outlined.GridView, contentDescription = stringResource(R.string.viewer_menu_pages))
                }
            }
            ViewerOverflowMenu(
                showNavigationItems = !wide,
                canSaveAnnotatedCopy = canSaveAnnotatedCopy,
                onMenuAction = onMenuAction,
            )
        },
        windowInsets = viewerTopBarInsets,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
}

@Composable
private fun ViewerOverflowMenu(
    showNavigationItems: Boolean,
    canSaveAnnotatedCopy: Boolean,
    onMenuAction: (ViewerMenuAction) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val select: (ViewerMenuAction) -> Unit = { action ->
                expanded = false
                onMenuAction(action)
            }
            MenuItem(R.string.viewer_menu_go_to_page) { select(ViewerMenuAction.GO_TO_PAGE) }
            if (showNavigationItems) {
                MenuItem(R.string.viewer_menu_contents) { select(ViewerMenuAction.CONTENTS) }
                MenuItem(R.string.viewer_menu_pages) { select(ViewerMenuAction.PAGES) }
            }
            MenuItem(R.string.viewer_menu_bookmarks) { select(ViewerMenuAction.BOOKMARKS) }
            HorizontalDivider()
            MenuItem(R.string.viewer_menu_reading_mode) { select(ViewerMenuAction.READING_MODE) }
            MenuItem(R.string.viewer_menu_scroll_direction) { select(ViewerMenuAction.SCROLL_DIRECTION) }
            HorizontalDivider()
            MenuItem(R.string.viewer_menu_annotate) { select(ViewerMenuAction.ANNOTATE) }
            MenuItem(R.string.viewer_menu_save_annotated_copy, enabled = canSaveAnnotatedCopy) {
                select(ViewerMenuAction.SAVE_ANNOTATED_COPY)
            }
            HorizontalDivider()
            MenuItem(R.string.action_share) { select(ViewerMenuAction.SHARE) }
            MenuItem(R.string.viewer_menu_print) { select(ViewerMenuAction.PRINT) }
            MenuItem(R.string.viewer_menu_open_with) { select(ViewerMenuAction.OPEN_WITH) }
            MenuItem(R.string.viewer_menu_save_copy) { select(ViewerMenuAction.SAVE_COPY) }
            MenuItem(R.string.viewer_menu_properties) { select(ViewerMenuAction.PROPERTIES) }
        }
    }
}

@Composable
private fun MenuItem(@StringRes textRes: Int, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(textRes)) },
        onClick = onClick,
        enabled = enabled,
    )
}

private const val WIDE_SCREEN_DP = 600
