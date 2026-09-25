package io.github.tffy1.pdfviewer.ui.viewer

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tffy1.pdfviewer.R
import io.github.tffy1.pdfviewer.data.db.BookmarkEntity
import io.github.tffy1.pdfviewer.data.settings.AppSettings
import io.github.tffy1.pdfviewer.data.settings.PageColorMode
import io.github.tffy1.pdfviewer.data.settings.ScrollMode
import io.github.tffy1.pdfviewer.integration.DocumentActions
import io.github.tffy1.pdfviewer.integration.DocumentPropertiesDialog
import io.github.tffy1.pdfviewer.pdf.PdfDocument
import io.github.tffy1.pdfviewer.pdf.PdfLink
import io.github.tffy1.pdfviewer.ui.viewer.annotations.AnnotationOverlay
import io.github.tffy1.pdfviewer.ui.viewer.annotations.AnnotationToolbar
import io.github.tffy1.pdfviewer.ui.viewer.chrome.ChoiceDialog
import io.github.tffy1.pdfviewer.ui.viewer.chrome.GoToPageDialog
import io.github.tffy1.pdfviewer.ui.viewer.chrome.ImmersiveModeEffect
import io.github.tffy1.pdfviewer.ui.viewer.chrome.KeepScreenOnEffect
import io.github.tffy1.pdfviewer.ui.viewer.chrome.PageBubble
import io.github.tffy1.pdfviewer.ui.viewer.chrome.RenameBookmarkDialog
import io.github.tffy1.pdfviewer.ui.viewer.chrome.ViewerBottomBar
import io.github.tffy1.pdfviewer.ui.viewer.chrome.ViewerMenuAction
import io.github.tffy1.pdfviewer.ui.viewer.chrome.ViewerTopBar
import io.github.tffy1.pdfviewer.ui.viewer.chrome.viewerBottomBarInsets
import io.github.tffy1.pdfviewer.ui.viewer.chrome.viewerTopBarInsets
import io.github.tffy1.pdfviewer.ui.viewer.document.DocumentView
import io.github.tffy1.pdfviewer.ui.viewer.document.PageTap
import io.github.tffy1.pdfviewer.ui.viewer.document.rememberDocumentViewState
import io.github.tffy1.pdfviewer.ui.viewer.links.ExternalLinkDialog
import io.github.tffy1.pdfviewer.ui.viewer.links.findLinkAt
import io.github.tffy1.pdfviewer.ui.viewer.search.SearchHighlights
import io.github.tffy1.pdfviewer.ui.viewer.search.SearchTopBar
import io.github.tffy1.pdfviewer.ui.viewer.selection.TextSelectionActionBar
import io.github.tffy1.pdfviewer.ui.viewer.selection.TextSelectionOverlay
import io.github.tffy1.pdfviewer.ui.viewer.sheets.BookmarksSheet
import io.github.tffy1.pdfviewer.ui.viewer.sheets.ThumbnailsSheet
import io.github.tffy1.pdfviewer.ui.viewer.sheets.TocSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class ViewerSheet { CONTENTS, PAGES, BOOKMARKS }

private enum class ViewerDialog { GO_TO_PAGE, READING_MODE, SCROLL_DIRECTION, PROPERTIES }

/** The reader itself, once the document is open: document, chrome, modes, sheets and dialogs. */
@Composable
internal fun ViewerContent(
    viewModel: ViewerViewModel,
    session: ViewerSession,
    onBack: () -> Unit,
    onOpenDocument: (Uri) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val document = session.document
    val pageCount = document.pageCount
    val displayName = session.info.displayName

    val settings = viewModel.settings.collectAsStateWithLifecycle().value ?: AppSettings()
    val currentPage by viewModel.currentPage.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val isBookmarked by viewModel.isCurrentPageBookmarked.collectAsStateWithLifecycle()
    val annotateMode by viewModel.annotateMode.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val searchState by session.search.state.collectAsStateWithLifecycle()
    val selection by session.selection.selection.collectAsStateWithLifecycle()
    val capturesTouch by session.annotations.capturesTouch.collectAsStateWithLifecycle()
    val annotationCount by session.annotations.annotationCount.collectAsStateWithLifecycle()

    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf<ViewerSheet?>(null) }
    var dialog by rememberSaveable { mutableStateOf<ViewerDialog?>(null) }
    var externalLink by rememberSaveable { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<BookmarkEntity?>(null) }

    val viewState = rememberDocumentViewState(document.pageSizes, initialPage = viewModel.currentPage.value)
    val snackbarHostState = remember { SnackbarHostState() }
    val focusRequester = remember { FocusRequester() }

    val topChromeVisible = chromeVisible || searchActive || annotateMode
    val bottomBarVisible = chromeVisible && !annotateMode
    val immersive = !topChromeVisible

    // Jumps (TOC, bookmarks, thumbnails, scrubber…) are instant; stepping one page animates.
    fun goToPage(page: Int, animate: Boolean = false) {
        scope.launch { viewState.scrollToPage(clampPage(page, pageCount), animate) }
    }

    fun closeSearch() {
        session.search.clear()
        searchActive = false
    }

    // ---- State wiring ---------------------------------------------------------------------

    LaunchedEffect(viewState) {
        snapshotFlow { viewState.currentPage }.collect { viewModel.onPageChanged(it) }
    }
    SideEffect { viewState.gesturesEnabled = !capturesTouch }

    val currentMatch = searchState.currentMatch
    LaunchedEffect(currentMatch) {
        val match = currentMatch ?: return@LaunchedEffect
        val rect = match.rects.firstOrNull()
        if (rect != null) viewState.scrollToRect(match.pageIndex, rect) else viewState.scrollToPage(match.pageIndex)
    }

    ImmersiveModeEffect(immersive = immersive)
    KeepScreenOnEffect(enabled = settings.keepScreenOn)

    // Volume keys are read from the focused root; give focus back whenever nothing else needs it,
    // but never take it from the search field.
    LaunchedEffect(searchActive, sheet, dialog, annotateMode) {
        if (!searchActive) focusRequester.requestFocus()
    }

    BackHandler(enabled = selection != null || searchActive || annotateMode) {
        when {
            selection != null -> session.selection.clear()
            searchActive -> closeSearch()
            annotateMode -> viewModel.exitAnnotateMode()
        }
    }

    // ---- One-off events -------------------------------------------------------------------

    val copySavedMessage = stringResource(R.string.viewer_copy_saved)
    val annotatedSavedMessage = stringResource(R.string.viewer_annotated_copy_saved)
    val saveFailedMessage = stringResource(R.string.viewer_save_failed)
    val openLabel = stringResource(R.string.action_open)
    val noAppMessage = stringResource(R.string.viewer_no_app_for_action)
    val currentOnOpenDocument by rememberUpdatedState(onOpenDocument)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ViewerEvent.CopySaved -> {
                    val result = snackbarHostState.showSnackbar(
                        message = if (event.annotated) annotatedSavedMessage else copySavedMessage,
                        actionLabel = openLabel,
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) currentOnOpenDocument(event.destination)
                }
                ViewerEvent.SaveFailed -> snackbarHostState.showSnackbar(saveFailedMessage)
            }
        }
    }

    val saveCopyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(PDF_MIME_TYPE),
    ) { destination -> destination?.let(viewModel::saveCopy) }
    val saveAnnotatedLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(PDF_MIME_TYPE),
    ) { destination -> destination?.let(viewModel::saveAnnotatedCopy) }
    val annotatedSuffix = stringResource(R.string.viewer_annotated_file_suffix)
    val bookmarkTitle = stringResource(R.string.viewer_bookmark_default_title, currentPage + 1)

    fun launchSafely(block: () -> Unit) {
        try {
            block()
        } catch (e: ActivityNotFoundException) {
            scope.launch { snackbarHostState.showSnackbar(noAppMessage) }
        }
    }

    fun onMenuAction(action: ViewerMenuAction) {
        when (action) {
            ViewerMenuAction.GO_TO_PAGE -> dialog = ViewerDialog.GO_TO_PAGE
            ViewerMenuAction.CONTENTS -> sheet = ViewerSheet.CONTENTS
            ViewerMenuAction.PAGES -> sheet = ViewerSheet.PAGES
            ViewerMenuAction.BOOKMARKS -> sheet = ViewerSheet.BOOKMARKS
            ViewerMenuAction.READING_MODE -> dialog = ViewerDialog.READING_MODE
            ViewerMenuAction.SCROLL_DIRECTION -> dialog = ViewerDialog.SCROLL_DIRECTION
            ViewerMenuAction.ANNOTATE -> {
                searchActive = false
                viewModel.enterAnnotateMode()
            }
            ViewerMenuAction.SAVE_ANNOTATED_COPY -> launchSafely {
                saveAnnotatedLauncher.launch(suggestedCopyFileName(displayName, annotatedSuffix))
            }
            ViewerMenuAction.SHARE -> launchSafely { DocumentActions.share(context, document.uri, displayName) }
            ViewerMenuAction.PRINT -> launchSafely { DocumentActions.print(context, document.uri, displayName) }
            ViewerMenuAction.OPEN_WITH -> launchSafely { DocumentActions.openWith(context, document.uri) }
            ViewerMenuAction.SAVE_COPY -> launchSafely { saveCopyLauncher.launch(suggestedCopyFileName(displayName)) }
            ViewerMenuAction.PROPERTIES -> dialog = ViewerDialog.PROPERTIES
        }
    }

    // ---- Gestures on pages ----------------------------------------------------------------

    val onTap: (PageTap?) -> Unit = { tap ->
        when {
            selection != null -> session.selection.clear()
            tap == null -> if (!annotateMode) chromeVisible = !chromeVisible
            else -> scope.launch {
                when (val link = findLinkSafely(document, tap)) {
                    is PdfLink.Internal -> viewState.scrollToPage(clampPage(link.targetPageIndex, pageCount), animate = false)
                    is PdfLink.External -> externalLink = link.uri
                    null -> if (!annotateMode) chromeVisible = !chromeVisible
                }
            }
        }
    }
    val onLongPress: (PageTap) -> Unit = { tap ->
        if (!capturesTouch) session.selection.selectWordAt(tap.pageIndex, tap.point)
    }

    // ---- Layout ---------------------------------------------------------------------------

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .onPreviewKeyEvent { event ->
                if (!settings.volumeKeysTurnPages || searchActive) return@onPreviewKeyEvent false
                val delta = when (event.key) {
                    Key.VolumeUp -> -1
                    Key.VolumeDown -> 1
                    else -> return@onPreviewKeyEvent false
                }
                // Consume both down and up so the system volume doesn't change.
                if (event.type == KeyEventType.KeyDown) goToPage(viewState.currentPage + delta, animate = true)
                true
            }
            .focusRequester(focusRequester)
            .focusable(),
    ) {
        DocumentView(
            document = document,
            state = viewState,
            scrollMode = settings.scrollMode,
            pageColorMode = settings.pageColorMode,
            modifier = Modifier.fillMaxSize(),
            onTap = onTap,
            onLongPress = onLongPress,
            pageOverlay = { page ->
                SearchHighlights(session.search, page)
                AnnotationOverlay(session.annotations, page)
                TextSelectionOverlay(session.selection, page)
            },
        )

        // Top chrome: app bar or search bar, plus save progress.
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(),
        ) {
            AnimatedVisibility(
                visible = topChromeVisible,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
            ) {
                if (searchActive) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                        SearchTopBar(
                            controller = session.search,
                            onClose = { closeSearch() },
                            modifier = Modifier.windowInsetsPadding(viewerTopBarInsets),
                        )
                    }
                } else {
                    ViewerTopBar(
                        title = displayName,
                        isBookmarked = isBookmarked,
                        canSaveAnnotatedCopy = annotationCount > 0,
                        onBack = onBack,
                        onSearch = {
                            session.selection.clear()
                            if (annotateMode) viewModel.exitAnnotateMode()
                            searchActive = true
                        },
                        onToggleBookmark = { viewModel.toggleBookmark(currentPage, bookmarkTitle) },
                        onMenuAction = { onMenuAction(it) },
                    )
                }
            }
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // Floating page number while reading full screen.
        var pillVisible by remember { mutableStateOf(false) }
        LaunchedEffect(currentPage) {
            pillVisible = true
            delay(PAGE_PILL_VISIBLE_MS)
            pillVisible = false
        }
        AnimatedVisibility(
            visible = settings.showPageNumberOverlay && !bottomBarVisible && !annotateMode && pillVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(viewerBottomBarInsets)
                .padding(bottom = 16.dp),
        ) {
            PageBubble(text = stringResource(R.string.viewer_page_indicator, currentPage + 1, pageCount))
        }

        // Bottom chrome: snackbars, selection actions, then the page bar or annotation toolbar.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SnackbarHost(snackbarHostState)
            AnimatedVisibility(visible = selection != null, enter = fadeIn(), exit = fadeOut()) {
                TextSelectionActionBar(
                    controller = session.selection,
                    onAnnotate = { selected, kind ->
                        session.annotations.addMarkup(selected, kind)
                        session.selection.clear()
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (annotateMode) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    AnnotationToolbar(
                        controller = session.annotations,
                        onDone = viewModel::exitAnnotateMode,
                        modifier = Modifier.windowInsetsPadding(viewerBottomBarInsets),
                    )
                }
            } else {
                AnimatedVisibility(
                    visible = bottomBarVisible,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                ) {
                    ViewerBottomBar(
                        currentPage = currentPage,
                        pageCount = pageCount,
                        onJumpToPage = { page -> goToPage(page) },
                    )
                }
                // Keeps snackbars above the navigation bar while the page bar is hidden.
                if (!bottomBarVisible) Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }

    // ---- Sheets ---------------------------------------------------------------------------

    when (sheet) {
        ViewerSheet.CONTENTS -> {
            val toc by viewModel.toc.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { viewModel.loadToc() }
            TocSheet(
                toc = toc,
                currentPage = currentPage,
                onSelectPage = { page -> goToPage(page) },
                onDismiss = { sheet = null },
            )
        }
        ViewerSheet.PAGES -> ThumbnailsSheet(
            pageSizes = document.pageSizes,
            currentPage = currentPage,
            cache = session.thumbnails,
            pageColorMode = settings.pageColorMode,
            onSelectPage = { page -> goToPage(page) },
            onDismiss = { sheet = null },
        )
        ViewerSheet.BOOKMARKS -> BookmarksSheet(
            bookmarks = bookmarks,
            currentPage = currentPage,
            onSelectPage = { page -> goToPage(page) },
            onRename = { renaming = it },
            onDelete = { viewModel.deleteBookmark(it.pageIndex) },
            onDismiss = { sheet = null },
        )
        null -> Unit
    }

    // ---- Dialogs --------------------------------------------------------------------------

    when (dialog) {
        ViewerDialog.GO_TO_PAGE -> GoToPageDialog(
            pageCount = pageCount,
            onGoToPage = { page ->
                dialog = null
                goToPage(page)
            },
            onDismiss = { dialog = null },
        )
        ViewerDialog.READING_MODE -> ChoiceDialog(
            title = stringResource(R.string.viewer_menu_reading_mode),
            options = listOf(
                PageColorMode.NORMAL to stringResource(R.string.viewer_reading_mode_normal),
                PageColorMode.NIGHT to stringResource(R.string.viewer_reading_mode_night),
                PageColorMode.SEPIA to stringResource(R.string.viewer_reading_mode_sepia),
            ),
            selected = settings.pageColorMode,
            onSelect = { mode ->
                viewModel.setPageColorMode(mode)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        ViewerDialog.SCROLL_DIRECTION -> ChoiceDialog(
            title = stringResource(R.string.viewer_menu_scroll_direction),
            options = listOf(
                ScrollMode.VERTICAL to stringResource(R.string.viewer_scroll_vertical),
                ScrollMode.HORIZONTAL to stringResource(R.string.viewer_scroll_horizontal),
            ),
            selected = settings.scrollMode,
            onSelect = { mode ->
                viewModel.setScrollMode(mode)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        ViewerDialog.PROPERTIES -> {
            val metadata by viewModel.metadata.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { viewModel.loadMetadata() }
            val loaded = metadata
            DocumentPropertiesDialog(
                metadata = if (loaded is LazyResource.Loaded) loaded.value else null,
                fileInfo = session.info,
                pageCount = pageCount,
                onDismiss = { dialog = null },
            )
        }
        null -> Unit
    }

    renaming?.let { bookmark ->
        RenameBookmarkDialog(
            initialTitle = bookmark.title,
            onRename = { title ->
                viewModel.renameBookmark(bookmark.id, title)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }

    externalLink?.let { uri ->
        ExternalLinkDialog(uri = uri, onDismiss = { externalLink = null })
    }
}

/** Link lookup must never break tapping: failures simply mean "no link here". */
private suspend fun findLinkSafely(document: PdfDocument, tap: PageTap): PdfLink? = try {
    findLinkAt(document, tap)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

private const val PDF_MIME_TYPE = "application/pdf"
private const val PAGE_PILL_VISIBLE_MS = 1_500L
