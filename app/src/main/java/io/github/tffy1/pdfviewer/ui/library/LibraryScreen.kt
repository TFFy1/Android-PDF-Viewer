@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.tffy1.pdfviewer.ui.library

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tffy1.pdfviewer.R
import io.github.tffy1.pdfviewer.appContainer
import io.github.tffy1.pdfviewer.data.settings.LibrarySort
import io.github.tffy1.pdfviewer.integration.DocumentActions
import io.github.tffy1.pdfviewer.library.DocumentItem
import io.github.tffy1.pdfviewer.library.FolderItem
import io.github.tffy1.pdfviewer.library.FolderScanState
import io.github.tffy1.pdfviewer.library.FolderScanner
import io.github.tffy1.pdfviewer.library.PDF_MIME_TYPE
import io.github.tffy1.pdfviewer.library.catchingNonCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class LibraryTab(@StringRes val titleRes: Int) {
    RECENT(R.string.library_tab_recent),
    FAVORITES(R.string.library_tab_favorites),
    FOLDERS(R.string.library_tab_folders),
}

/** Lists hold one column on phones and several on tablets / unfolded foldables. */
private val MinColumnWidth = 360.dp

/** Keeps the last rows reachable above the floating action button. */
private val ListContentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 96.dp)

/* CONTRACT (scaffold). Owner: library agent. */
@Composable
fun LibraryScreen(onOpenDocument: (Uri) -> Unit, modifier: Modifier = Modifier) {
    val container = appContainer()
    val viewModel: LibraryViewModel = viewModel {
        LibraryViewModel(
            contentResolver = container.appContext.contentResolver,
            recentDocuments = container.recentDocumentsRepository,
            library = container.libraryRepository,
            settings = container.settingsRepository,
            documentAccess = container.documentAccess,
            thumbnailStore = container.thumbnailStore,
            scanner = FolderScanner(container.appContext),
            savedStateHandle = createSavedStateHandle(),
        )
    }
    LibraryContentScreen(viewModel = viewModel, onOpenDocument = onOpenDocument, modifier = modifier)
}

@Composable
private fun LibraryContentScreen(
    viewModel: LibraryViewModel,
    onOpenDocument: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val content = state.content
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val currentOnOpenDocument by rememberUpdatedState(onOpenDocument)

    var selectedTab by rememberSaveable { mutableStateOf(LibraryTab.RECENT) }
    var searchActive by rememberSaveable { mutableStateOf(viewModel.savedQuery.isNotEmpty()) }
    var searchText by rememberSaveable { mutableStateOf(viewModel.savedQuery) }
    var confirmClearHistory by rememberSaveable { mutableStateOf(false) }

    fun showSnackbar(@StringRes message: Int) {
        scope.launch { snackbarHostState.showSnackbar(context.getString(message)) }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onDocumentPicked(uri)
    }
    val openFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.onFolderPicked(uri)
    }
    val pickDocument: () -> Unit = {
        try {
            openDocumentLauncher.launch(arrayOf(PDF_MIME_TYPE))
        } catch (e: ActivityNotFoundException) {
            showSnackbar(R.string.library_msg_no_picker)
        }
    }
    /** Opens the folder picker, starting at [initial] (used to re-grant a folder whose access was lost). */
    val pickFolder: (Uri?) -> Unit = { initial ->
        try {
            openFolderLauncher.launch(initial)
        } catch (e: ActivityNotFoundException) {
            showSnackbar(R.string.library_msg_no_picker)
        }
    }
    val closeSearch: () -> Unit = {
        searchActive = false
        searchText = ""
        viewModel.setQuery("")
    }

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is LibraryEvent.OpenDocument -> currentOnOpenDocument(event.uri)
                is LibraryEvent.ShowMessage -> launch {
                    val message = event.message
                    if (message is LibraryMessage.FolderAccessLost) {
                        val result = snackbarHostState.showSnackbar(
                            message = message.text(context),
                            actionLabel = context.getString(R.string.library_folder_regrant_action),
                            duration = SnackbarDuration.Long,
                        )
                        if (result == SnackbarResult.ActionPerformed) pickFolder(Uri.parse(message.treeUri))
                    } else {
                        snackbarHostState.showSnackbar(message.text(context))
                    }
                }
            }
        }
    }

    // Registered in this order so that back first closes the search, then the open folder.
    BackHandler(enabled = selectedTab == LibraryTab.FOLDERS && content.openedFolder != null) {
        viewModel.closeFolder()
    }
    BackHandler(enabled = searchActive, onBack = closeSearch)

    val rowActions = remember(viewModel) {
        DocumentRowActions(
            onOpen = { item -> currentOnOpenDocument(Uri.parse(item.uri)) },
            onToggleFavorite = viewModel::toggleFavorite,
            onRemoveFromRecents = viewModel::removeFromRecents,
            onShare = { item ->
                val shared = catchingNonCancellation {
                    DocumentActions.share(context, Uri.parse(item.uri), item.name)
                }.isSuccess
                if (!shared) showSnackbar(R.string.library_msg_share_failed)
            },
        )
    }

    // Relative times ("Opened 5 min ago") are refreshed every minute while the screen is visible.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis()
        }
    }

    val recentGridState = rememberLazyGridState()
    val favoritesGridState = rememberLazyGridState()
    val foldersGridState = rememberLazyGridState()
    val folderFilesGridState = rememberSaveable(content.openedFolder?.treeUri, saver = LazyGridState.Saver) {
        LazyGridState()
    }
    val activeGridState = when (selectedTab) {
        LibraryTab.RECENT -> recentGridState
        LibraryTab.FAVORITES -> favoritesGridState
        LibraryTab.FOLDERS -> if (content.openedFolder != null) folderFilesGridState else foldersGridState
    }
    val fabExpanded by remember(activeGridState) {
        derivedStateOf { activeGridState.firstVisibleItemIndex == 0 }
    }

    Scaffold(
        modifier = modifier,
        // The caller's modifier already carries the app scaffold's system-bar padding.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                LibraryTopBar(
                    searchActive = searchActive,
                    searchText = searchText,
                    onSearchTextChange = { text ->
                        searchText = text
                        viewModel.setQuery(text)
                    },
                    onOpenSearch = { searchActive = true },
                    onCloseSearch = closeSearch,
                    sort = state.sort,
                    onSortChange = viewModel::setSort,
                    canClearHistory = content.totalRecents > content.totalFavorites,
                    onClearHistory = { confirmClearHistory = true },
                )
                PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                    LibraryTab.entries.forEach { tab ->
                        Tab(
                            selected = tab == selectedTab,
                            onClick = { selectedTab = tab },
                            text = { Text(stringResource(tab.titleRes), maxLines = 1) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.library_open_pdf)) },
                icon = { Icon(Icons.Outlined.FileOpen, contentDescription = null) },
                onClick = pickDocument,
                expanded = fabExpanded,
            )
        },
    ) { innerPadding ->
        Box(
            Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            if (state.isLoading) {
                val loadingLabel = stringResource(R.string.library_loading)
                CircularProgressIndicator(
                    Modifier
                        .align(Alignment.Center)
                        .semantics { contentDescription = loadingLabel },
                )
            } else {
                when (selectedTab) {
                    LibraryTab.RECENT -> DocumentsTab(
                        items = content.recents,
                        totalCount = content.totalRecents,
                        query = state.query,
                        gridState = recentGridState,
                        coverLoader = viewModel.coverLoader,
                        now = now,
                        actions = rowActions,
                        emptyState = {
                            EmptyState(
                                icon = Icons.Outlined.History,
                                title = stringResource(R.string.library_empty_recent_title),
                                body = stringResource(R.string.library_empty_recent_body),
                                action = {
                                    Button(onClick = pickDocument) {
                                        Icon(
                                            Icons.Outlined.FileOpen,
                                            contentDescription = null,
                                            modifier = Modifier.size(ButtonDefaults.IconSize),
                                        )
                                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                        Text(stringResource(R.string.library_open_pdf))
                                    }
                                },
                            )
                        },
                    )
                    LibraryTab.FAVORITES -> DocumentsTab(
                        items = content.favorites,
                        totalCount = content.totalFavorites,
                        query = state.query,
                        gridState = favoritesGridState,
                        coverLoader = viewModel.coverLoader,
                        now = now,
                        actions = rowActions,
                        emptyState = {
                            EmptyState(
                                icon = Icons.Outlined.StarBorder,
                                title = stringResource(R.string.library_empty_favorites_title),
                                body = stringResource(R.string.library_empty_favorites_body),
                                action = if (content.totalRecents > 0) {
                                    {
                                        OutlinedButton(onClick = { selectedTab = LibraryTab.RECENT }) {
                                            Text(stringResource(R.string.library_empty_favorites_action))
                                        }
                                    }
                                } else {
                                    null
                                },
                            )
                        },
                    )
                    LibraryTab.FOLDERS -> FoldersTab(
                        state = state,
                        foldersGridState = foldersGridState,
                        folderFilesGridState = folderFilesGridState,
                        coverLoader = viewModel.coverLoader,
                        now = now,
                        actions = rowActions,
                        onRefresh = viewModel::refresh,
                        onAddFolder = { pickFolder(null) },
                        onOpenFolder = viewModel::openFolder,
                        onCloseFolder = viewModel::closeFolder,
                        onRegrantFolder = { folder -> pickFolder(Uri.parse(folder.treeUri)) },
                        onRescanFolder = viewModel::rescan,
                        onRemoveFolder = viewModel::removeFolder,
                    )
                }
            }
        }
    }

    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            icon = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null) },
            title = { Text(stringResource(R.string.library_clear_history_title)) },
            text = { Text(stringResource(R.string.library_clear_history_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearHistory = false
                        viewModel.clearHistory()
                    },
                ) { Text(stringResource(R.string.library_clear_history_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearHistory = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun LibraryTopBar(
    searchActive: Boolean,
    searchText: String,
    onSearchTextChange: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    sort: LibrarySort,
    onSortChange: (LibrarySort) -> Unit,
    canClearHistory: Boolean,
    onClearHistory: () -> Unit,
) {
    TopAppBar(
        title = {
            if (searchActive) {
                SearchField(value = searchText, onValueChange = onSearchTextChange)
            } else {
                Text(stringResource(R.string.app_name))
            }
        },
        navigationIcon = {
            if (searchActive) {
                IconButton(onClick = onCloseSearch) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.library_action_close_search),
                    )
                }
            }
        },
        actions = {
            if (!searchActive) {
                IconButton(onClick = onOpenSearch) {
                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.library_action_search))
                }
            } else if (searchText.isNotEmpty()) {
                IconButton(onClick = { onSearchTextChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.library_action_clear_search))
                }
            }
            SortMenu(sort = sort, onSortChange = onSortChange)
            if (!searchActive) {
                OverflowMenu(canClearHistory = canClearHistory, onClearHistory = onClearHistory)
            }
        },
        windowInsets = WindowInsets(0, 0, 0, 0),
    )
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        placeholder = { Text(stringResource(R.string.library_search_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }
}

@Composable
private fun SortMenu(sort: LibrarySort, onSortChange: (LibrarySort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.library_action_sort))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LibrarySort.entries.forEach { option ->
                val isSelected = option == sort
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes())) },
                    onClick = {
                        expanded = false
                        onSortChange(option)
                    },
                    modifier = Modifier.semantics { selected = isSelected },
                    trailingIcon = {
                        if (isSelected) Icon(Icons.Filled.Check, contentDescription = null)
                    },
                )
            }
        }
    }
}

@Composable
private fun OverflowMenu(canClearHistory: Boolean, onClearHistory: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_action_clear_history)) },
                leadingIcon = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null) },
                enabled = canClearHistory,
                onClick = {
                    expanded = false
                    onClearHistory()
                },
            )
        }
    }
}

/** Recent or Favorites: a list of documents, its empty state, or "no matches" while searching. */
@Composable
private fun DocumentsTab(
    items: List<DocumentItem>,
    totalCount: Int,
    query: String,
    gridState: LazyGridState,
    coverLoader: CoverLoader,
    now: Long,
    actions: DocumentRowActions,
    emptyState: @Composable () -> Unit,
) {
    when {
        items.isNotEmpty() -> DocumentGrid(gridState) {
            documentItems(items, coverLoader, now, actions)
        }
        totalCount == 0 -> emptyState()
        else -> NoResults(query)
    }
}

@Composable
private fun FoldersTab(
    state: LibraryUiState,
    foldersGridState: LazyGridState,
    folderFilesGridState: LazyGridState,
    coverLoader: CoverLoader,
    now: Long,
    actions: DocumentRowActions,
    onRefresh: () -> Unit,
    onAddFolder: () -> Unit,
    onOpenFolder: (FolderItem) -> Unit,
    onCloseFolder: () -> Unit,
    onRegrantFolder: (FolderItem) -> Unit,
    onRescanFolder: (FolderItem) -> Unit,
    onRemoveFolder: (FolderItem) -> Unit,
) {
    val content = state.content
    val openedFolder = content.openedFolder
    PullToRefreshBox(
        isRefreshing = state.isScanning,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            openedFolder != null -> DocumentGrid(folderFilesGridState) {
                fullWidthItem(key = "folder-header") {
                    OpenedFolderHeader(
                        folder = openedFolder,
                        onBack = onCloseFolder,
                        onRescan = { onRescanFolder(openedFolder) },
                    )
                }
                if (content.folderFiles.isEmpty()) {
                    fullWidthItem(key = "folder-empty") {
                        Box(Modifier.padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                            if (state.query.isNotBlank()) {
                                EmptyStateBody(
                                    icon = Icons.Outlined.SearchOff,
                                    title = stringResource(R.string.library_no_results_title),
                                    body = stringResource(R.string.library_no_results_body, state.query.trim()),
                                )
                            } else {
                                EmptyStateBody(
                                    icon = Icons.Outlined.FolderOpen,
                                    title = stringResource(R.string.library_empty_folder_title),
                                    body = stringResource(R.string.library_empty_folder_body),
                                )
                            }
                        }
                    }
                } else {
                    documentItems(content.folderFiles, coverLoader, now, actions)
                }
            }
            content.folders.isEmpty() -> EmptyState(
                icon = Icons.Outlined.CreateNewFolder,
                title = stringResource(R.string.library_empty_folders_title),
                body = stringResource(R.string.library_empty_folders_body),
                action = {
                    Button(onClick = onAddFolder) {
                        Icon(
                            Icons.Outlined.CreateNewFolder,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.library_add_folder))
                    }
                },
            )
            state.query.isNotBlank() ->
                if (content.folderFiles.isEmpty()) {
                    NoResults(state.query)
                } else {
                    DocumentGrid(foldersGridState) {
                        fullWidthItem(key = "search-header") {
                            SectionHeader(stringResource(R.string.library_results_in_folders))
                        }
                        documentItems(content.folderFiles, coverLoader, now, actions)
                    }
                }
            else -> DocumentGrid(foldersGridState) {
                fullWidthItem(key = "folders-header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SectionHeader(
                            text = pluralStringResource(
                                R.plurals.library_folder_count,
                                content.folders.size,
                                content.folders.size,
                            ),
                            modifier = Modifier.weight(1f),
                            horizontalPadding = 0.dp,
                        )
                        FilledTonalButton(onClick = onAddFolder) {
                            Icon(
                                Icons.Outlined.CreateNewFolder,
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.IconSize),
                            )
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.library_add_folder))
                        }
                    }
                }
                items(content.folders, key = { it.treeUri }, contentType = { "folder" }) { folder ->
                    FolderRow(
                        folder = folder,
                        onOpen = onOpenFolder,
                        onRegrant = onRegrantFolder,
                        onRescan = onRescanFolder,
                        onRemove = onRemoveFolder,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

@Composable
private fun OpenedFolderHeader(folder: FolderItem, onBack: () -> Unit, onRescan: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.library_back_to_folders),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        ) {
            Text(
                folder.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                FolderStatus(folder)
            }
        }
        IconButton(onClick = onRescan, enabled = folder.scanState != FolderScanState.SCANNING) {
            Icon(Icons.Outlined.Sync, contentDescription = stringResource(R.string.library_folder_rescan))
        }
    }
}

@Composable
private fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 16.dp,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .padding(horizontal = horizontalPadding, vertical = 8.dp)
            .semantics { heading() },
    )
}

@Composable
private fun NoResults(query: String) {
    EmptyState(
        icon = Icons.Outlined.SearchOff,
        title = stringResource(R.string.library_no_results_title),
        body = stringResource(R.string.library_no_results_body, query.trim()),
    )
}

@Composable
private fun DocumentGrid(state: LazyGridState, content: LazyGridScope.() -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = MinColumnWidth),
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = ListContentPadding,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

private fun LazyGridScope.fullWidthItem(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = key) { content() }
}

private fun LazyGridScope.documentItems(
    items: List<DocumentItem>,
    coverLoader: CoverLoader,
    now: Long,
    actions: DocumentRowActions,
) {
    items(items, key = { it.uri }, contentType = { "document" }) { item ->
        DocumentRow(
            item = item,
            coverLoader = coverLoader,
            now = now,
            actions = actions,
            modifier = Modifier.animateItem(),
        )
    }
}

@StringRes
private fun LibrarySort.labelRes(): Int = when (this) {
    LibrarySort.RECENT -> R.string.library_sort_recent
    LibrarySort.NAME -> R.string.library_sort_name
    LibrarySort.SIZE -> R.string.library_sort_size
}

private fun LibraryMessage.text(context: Context): String = when (this) {
    is LibraryMessage.FolderAdded -> context.getString(R.string.library_msg_folder_added, name)
    is LibraryMessage.FolderAlreadyAdded -> context.getString(R.string.library_msg_folder_already_added, name)
    is LibraryMessage.FolderAccessRestored -> context.getString(R.string.library_msg_folder_access_restored, name)
    is LibraryMessage.FolderRemoved -> context.getString(R.string.library_msg_folder_removed, name)
    is LibraryMessage.FolderAccessLost -> context.getString(R.string.library_msg_folder_access_lost, name)
    is LibraryMessage.FolderUnavailable -> context.getString(R.string.library_msg_folder_unavailable, name)
    is LibraryMessage.FolderLimitReached -> context.getString(R.string.library_msg_folder_limit, name, count)
    LibraryMessage.FolderPermissionFailed -> context.getString(R.string.library_msg_folder_permission_failed)
    is LibraryMessage.RemovedFromRecents -> context.getString(R.string.library_msg_removed_from_recents, name)
    LibraryMessage.HistoryCleared -> context.getString(R.string.library_msg_history_cleared)
    LibraryMessage.GenericError -> context.getString(R.string.error_generic)
}
