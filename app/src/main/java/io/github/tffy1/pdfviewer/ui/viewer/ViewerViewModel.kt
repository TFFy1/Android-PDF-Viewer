package io.github.tffy1.pdfviewer.ui.viewer

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tffy1.pdfviewer.AppContainer
import io.github.tffy1.pdfviewer.data.db.BookmarkEntity
import io.github.tffy1.pdfviewer.data.settings.AppSettings
import io.github.tffy1.pdfviewer.data.settings.PageColorMode
import io.github.tffy1.pdfviewer.data.settings.ScrollMode
import io.github.tffy1.pdfviewer.integration.DocumentActions
import io.github.tffy1.pdfviewer.io.DocumentFileInfo
import io.github.tffy1.pdfviewer.pdf.DocumentMetadata
import io.github.tffy1.pdfviewer.pdf.PdfDocument
import io.github.tffy1.pdfviewer.pdf.PdfPasswordException
import io.github.tffy1.pdfviewer.pdf.TocEntry
import io.github.tffy1.pdfviewer.ui.viewer.annotations.AnnotationController
import io.github.tffy1.pdfviewer.ui.viewer.annotations.AnnotationTool
import io.github.tffy1.pdfviewer.ui.viewer.annotations.exportAnnotatedPdf
import io.github.tffy1.pdfviewer.ui.viewer.search.SearchController
import io.github.tffy1.pdfviewer.ui.viewer.selection.TextSelectionController
import io.github.tffy1.pdfviewer.ui.viewer.sheets.PageThumbnailCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the viewer needs once a document is open. Lives as long as the ViewModel. */
class ViewerSession(
    val document: PdfDocument,
    val info: DocumentFileInfo,
    val search: SearchController,
    val selection: TextSelectionController,
    val annotations: AnnotationController,
    val thumbnails: PageThumbnailCache,
)

sealed interface ViewerLoadState {
    data object Loading : ViewerLoadState
    data class NeedsPassword(val wrongPassword: Boolean) : ViewerLoadState
    data class Error(val kind: ViewerErrorKind, val message: String?) : ViewerLoadState
    class Ready(val session: ViewerSession) : ViewerLoadState {
        val document: PdfDocument get() = session.document
        val info: DocumentFileInfo get() = session.info
    }
}

/** Data loaded on demand (TOC, metadata) when its sheet or dialog is first opened. */
sealed interface LazyResource<out T> {
    data object Idle : LazyResource<Nothing>
    data object Loading : LazyResource<Nothing>
    data class Loaded<T>(val value: T) : LazyResource<T>
    data object Failed : LazyResource<Nothing>
}

/** One-off results shown as snackbars. */
sealed interface ViewerEvent {
    data class CopySaved(val destination: Uri, val annotated: Boolean) : ViewerEvent
    data object SaveFailed : ViewerEvent
}

/**
 * Owns the open [PdfDocument] (closed only in [onCleared], after the screen stopped showing it),
 * the per-document controllers, reading position, bookmarks and viewer-level settings.
 */
class ViewerViewModel(
    private val uri: Uri,
    private val routeInitialPage: Int,
    private val container: AppContainer,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val uriString = uri.toString()

    private val _loadState = MutableStateFlow<ViewerLoadState>(ViewerLoadState.Loading)
    val loadState: StateFlow<ViewerLoadState> = _loadState.asStateFlow()

    /** Null until the first value is read from DataStore. */
    private val _settings = MutableStateFlow<AppSettings?>(null)
    val settings: StateFlow<AppSettings?> = _settings.asStateFlow()

    private val _currentPage = MutableStateFlow(savedStateHandle.get<Int>(KEY_CURRENT_PAGE) ?: 0)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    val bookmarks: StateFlow<List<BookmarkEntity>> = container.bookmarksRepository.observe(uriString)
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val isCurrentPageBookmarked: StateFlow<Boolean> =
        combine(bookmarks, _currentPage) { list, page -> list.any { it.pageIndex == page } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    private val _annotateMode = MutableStateFlow(false)
    val annotateMode: StateFlow<Boolean> = _annotateMode.asStateFlow()

    private val _toc = MutableStateFlow<LazyResource<List<TocEntry>>>(LazyResource.Idle)
    val toc: StateFlow<LazyResource<List<TocEntry>>> = _toc.asStateFlow()

    private val _metadata = MutableStateFlow<LazyResource<DocumentMetadata>>(LazyResource.Idle)
    val metadata: StateFlow<LazyResource<DocumentMetadata>> = _metadata.asStateFlow()

    /** True while a copy is being written. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _events = Channel<ViewerEvent>(Channel.BUFFERED)
    val events: Flow<ViewerEvent> = _events.receiveAsFlow()

    private var loadJob: Job? = null
    private var pageSaveJob: Job? = null

    // The opened document is handed over under this lock so that a load finishing after
    // onCleared() can never leak a native document.
    private val documentLock = Any()
    private var openDocument: PdfDocument? = null
    private var cleared = false

    init {
        viewModelScope.launch {
            container.settingsRepository.settings
                .catch { emit(AppSettings()) }
                .collect { _settings.value = it }
        }
        load(password = null)
    }

    fun retry() = load(password = null)

    fun submitPassword(password: String) = load(password)

    private fun load(password: String?) {
        if (_loadState.value is ViewerLoadState.Ready) return
        loadJob?.cancel()
        _loadState.value = ViewerLoadState.Loading
        loadJob = viewModelScope.launch {
            val settings = _settings.filterNotNull().first()
            val access = container.documentAccess
            val info = attempt { access.queryInfo(uri) }.getOrElse {
                DocumentFileInfo(displayName = uri.lastPathSegment ?: DEFAULT_NAME, sizeBytes = null, mimeType = null)
            }
            val persistent = withContext(Dispatchers.IO) {
                attempt { access.takePersistableReadPermission(uri) }.getOrDefault(false)
            }

            val document = try {
                // Not cancellable: a document that finished opening must be adopted (or closed).
                withContext(NonCancellable) {
                    container.pdfEngine.open(uri, password).also { opened ->
                        if (!adopt(opened)) closeInBackground(opened)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PdfPasswordException) {
                _loadState.value = ViewerLoadState.NeedsPassword(wrongPassword = e.wrongPassword)
                return@launch
            } catch (e: Exception) {
                _loadState.value = ViewerLoadState.Error(diagnose(e), e.message)
                return@launch
            }

            if (document.pageCount <= 0) {
                _loadState.value = ViewerLoadState.Error(ViewerErrorKind.CORRUPT, null)
                return@launch
            }

            val recent = attempt { container.recentDocumentsRepository.get(uriString) }.getOrNull()
            val initialPage = resolveInitialPage(
                savedPage = savedStateHandle.get<Int>(KEY_CURRENT_PAGE),
                routePage = routeInitialPage,
                rememberLastPage = settings.rememberLastPage,
                lastPage = recent?.lastPage,
                pageCount = document.pageCount,
            )
            attempt {
                container.recentDocumentsRepository.recordOpened(
                    uri = uriString,
                    displayName = info.displayName,
                    sizeBytes = info.sizeBytes,
                    pageCount = document.pageCount,
                    hasPersistentAccess = persistent,
                )
            }

            _currentPage.value = initialPage
            savedStateHandle[KEY_CURRENT_PAGE] = initialPage
            _loadState.value = ViewerLoadState.Ready(
                ViewerSession(
                    document = document,
                    info = info,
                    search = SearchController(document, viewModelScope),
                    selection = TextSelectionController(document, viewModelScope),
                    annotations = AnnotationController(uri, container.annotationsRepository, viewModelScope),
                    thumbnails = PageThumbnailCache(document),
                ),
            )
        }
    }

    /** Refines engine errors, which may not carry the underlying I/O cause, by probing the URI directly. */
    private suspend fun diagnose(error: Exception): ViewerErrorKind {
        val kind = classifyOpenError(error)
        if (kind == ViewerErrorKind.NOT_FOUND || kind == ViewerErrorKind.NO_PERMISSION) return kind
        val probeError = withContext(Dispatchers.IO) {
            try {
                container.documentAccess.openFileDescriptor(uri).close()
                null
            } catch (e: Exception) {
                e
            }
        }
        val probeKind = probeError?.let(::classifyOpenError)
        return if (probeKind == ViewerErrorKind.NOT_FOUND || probeKind == ViewerErrorKind.NO_PERMISSION) probeKind else kind
    }

    private fun adopt(document: PdfDocument): Boolean = synchronized(documentLock) {
        if (cleared) {
            false
        } else {
            openDocument = document
            true
        }
    }

    // ---- Reading position ---------------------------------------------------------------

    /** Called whenever the page occupying most of the viewport changes. */
    fun onPageChanged(page: Int) {
        if (page == _currentPage.value) return
        _currentPage.value = page
        savedStateHandle[KEY_CURRENT_PAGE] = page
        if (_loadState.value !is ViewerLoadState.Ready) return
        pageSaveJob?.cancel()
        pageSaveJob = viewModelScope.launch {
            delay(PAGE_SAVE_DEBOUNCE_MS)
            if (_settings.value?.rememberLastPage == true) {
                attempt { container.recentDocumentsRepository.updateLastPage(uriString, page) }
            }
        }
    }

    // ---- Bookmarks ----------------------------------------------------------------------

    /** Adds a bookmark for [page] titled [title], or removes the existing one. */
    fun toggleBookmark(page: Int, title: String) {
        val exists = bookmarks.value.any { it.pageIndex == page }
        viewModelScope.launch {
            attempt {
                if (exists) {
                    container.bookmarksRepository.remove(uriString, page)
                } else {
                    container.bookmarksRepository.add(uriString, page, title)
                }
            }
        }
    }

    fun renameBookmark(id: Long, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { attempt { container.bookmarksRepository.rename(id, trimmed) } }
    }

    fun deleteBookmark(pageIndex: Int) {
        viewModelScope.launch { attempt { container.bookmarksRepository.remove(uriString, pageIndex) } }
    }

    // ---- Settings changed from the viewer (persisted globally) ------------------------------

    fun setScrollMode(mode: ScrollMode) {
        viewModelScope.launch { attempt { container.settingsRepository.setScrollMode(mode) } }
    }

    fun setPageColorMode(mode: PageColorMode) {
        viewModelScope.launch { attempt { container.settingsRepository.setPageColorMode(mode) } }
    }

    // ---- Annotate mode ------------------------------------------------------------------

    fun enterAnnotateMode() {
        val session = readySession() ?: return
        session.selection.clear()
        session.search.clear()
        if (session.annotations.activeTool.value == null) session.annotations.setTool(AnnotationTool.INK)
        _annotateMode.value = true
    }

    fun exitAnnotateMode() {
        readySession()?.annotations?.setTool(null)
        _annotateMode.value = false
    }

    // ---- Lazily loaded document data ------------------------------------------------------

    fun loadToc() {
        val document = readySession()?.document ?: return
        loadLazily(_toc) { document.tableOfContents() }
    }

    fun loadMetadata() {
        val document = readySession()?.document ?: return
        loadLazily(_metadata) { document.metadata() }
    }

    private fun <T> loadLazily(target: MutableStateFlow<LazyResource<T>>, block: suspend () -> T) {
        val current = target.value
        if (current is LazyResource.Loading || current is LazyResource.Loaded) return
        target.value = LazyResource.Loading
        viewModelScope.launch {
            val result = attempt { block() }
            target.value = if (result.isSuccess) LazyResource.Loaded(result.getOrThrow()) else LazyResource.Failed
        }
    }

    // ---- Copies -------------------------------------------------------------------------

    /** Copies the original bytes to [destination] (from ACTION_CREATE_DOCUMENT). */
    fun saveCopy(destination: Uri) = writeCopy(destination, annotated = false) {
        DocumentActions.saveCopy(container.appContext, uri, destination)
    }

    /** Writes the document plus stored annotations as real PDF annotations to [destination]. */
    fun saveAnnotatedCopy(destination: Uri) = writeCopy(destination, annotated = true) {
        exportAnnotatedPdf(container.appContext, uri, destination, container.annotationsRepository)
    }

    private fun writeCopy(destination: Uri, annotated: Boolean, write: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            _busy.value = true
            try {
                // Runs in the application scope so leaving the screen never leaves a half-written file.
                val result = container.applicationScope
                    .async(Dispatchers.IO) { attempt { write().getOrThrow() } }
                    .await()
                _events.send(
                    if (result.isSuccess) ViewerEvent.CopySaved(destination, annotated) else ViewerEvent.SaveFailed,
                )
            } finally {
                _busy.value = false
            }
        }
    }

    private fun readySession(): ViewerSession? = (_loadState.value as? ViewerLoadState.Ready)?.session

    override fun onCleared() {
        val document = synchronized(documentLock) {
            cleared = true
            openDocument.also { openDocument = null }
        }
        // The screen no longer displays the document: persist the position, then free native memory.
        val savePage = document != null && _settings.value?.rememberLastPage == true
        val page = _currentPage.value
        container.applicationScope.launch(Dispatchers.IO) {
            if (savePage) attempt { container.recentDocumentsRepository.updateLastPage(uriString, page) }
            document?.let { runCatching { it.close() } }
        }
    }

    private fun closeInBackground(document: PdfDocument) {
        container.applicationScope.launch(Dispatchers.IO) { runCatching { document.close() } }
    }

    private companion object {
        const val KEY_CURRENT_PAGE = "viewer_current_page"
        const val PAGE_SAVE_DEBOUNCE_MS = 500L
        const val STOP_TIMEOUT_MS = 5_000L
        const val DEFAULT_NAME = "document.pdf"
    }
}

/** Like [runCatching] but never swallows coroutine cancellation. */
private inline fun <T> attempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
