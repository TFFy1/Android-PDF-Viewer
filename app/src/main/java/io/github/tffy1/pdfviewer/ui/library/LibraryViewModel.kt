package io.github.tffy1.pdfviewer.ui.library

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tffy1.pdfviewer.data.db.LibraryFolderEntity
import io.github.tffy1.pdfviewer.data.db.RecentDocumentEntity
import io.github.tffy1.pdfviewer.data.repository.LibraryRepository
import io.github.tffy1.pdfviewer.data.repository.RecentDocumentsRepository
import io.github.tffy1.pdfviewer.data.settings.LibrarySort
import io.github.tffy1.pdfviewer.data.settings.SettingsRepository
import io.github.tffy1.pdfviewer.io.DocumentAccess
import io.github.tffy1.pdfviewer.library.DocumentItem
import io.github.tffy1.pdfviewer.library.FolderItem
import io.github.tffy1.pdfviewer.library.FolderScanResult
import io.github.tffy1.pdfviewer.library.FolderScanState
import io.github.tffy1.pdfviewer.library.FolderScanner
import io.github.tffy1.pdfviewer.library.LibraryContent
import io.github.tffy1.pdfviewer.library.assembleLibraryContent
import io.github.tffy1.pdfviewer.library.catchingNonCancellation
import io.github.tffy1.pdfviewer.pdf.ThumbnailStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

data class LibraryUiState(
    val isLoading: Boolean = true,
    val sort: LibrarySort = LibrarySort.RECENT,
    val query: String = "",
    val content: LibraryContent = LibraryContent(),
    /** At least one library folder is being scanned. */
    val isScanning: Boolean = false,
)

sealed interface LibraryEvent {
    data class OpenDocument(val uri: Uri) : LibraryEvent
    data class ShowMessage(val message: LibraryMessage) : LibraryEvent
}

/** Snackbar messages; turned into localized text by the screen. */
sealed interface LibraryMessage {
    data class FolderAdded(val name: String) : LibraryMessage
    data class FolderAlreadyAdded(val name: String) : LibraryMessage
    data class FolderAccessRestored(val name: String) : LibraryMessage
    data class FolderRemoved(val name: String) : LibraryMessage
    data class FolderAccessLost(val name: String, val treeUri: String) : LibraryMessage
    data class FolderUnavailable(val name: String) : LibraryMessage
    data class FolderLimitReached(val name: String, val count: Int) : LibraryMessage
    data object FolderPermissionFailed : LibraryMessage
    data class RemovedFromRecents(val name: String) : LibraryMessage
    data object HistoryCleared : LibraryMessage
    data object GenericError : LibraryMessage
}

class LibraryViewModel(
    private val contentResolver: ContentResolver,
    private val recentDocuments: RecentDocumentsRepository,
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
    private val documentAccess: DocumentAccess,
    private val thumbnailStore: ThumbnailStore,
    private val scanner: FolderScanner,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val coverLoader = CoverLoader(thumbnailStore) { uri, path -> recentDocuments.setThumbnail(uri, path) }

    /** The committed search query (the text field keeps its own state to stay responsive). */
    private val query: StateFlow<String> = savedStateHandle.getStateFlow(KEY_QUERY, "")
    /** Query to restore into the search field when the screen is recreated. */
    val savedQuery: String get() = query.value
    private val openedFolderUri: StateFlow<String?> = savedStateHandle.getStateFlow<String?>(KEY_OPENED_FOLDER, null)

    private val scanStates = MutableStateFlow<Map<String, FolderScanState>>(emptyMap())
    /** Running scans per folder; only touched from the main thread (viewModelScope). */
    private val scanJobs = HashMap<String, Job>()
    private val scanPermits = Semaphore(MAX_PARALLEL_SCANS)

    private val events = Channel<LibraryEvent>(Channel.BUFFERED)
    val eventFlow: Flow<LibraryEvent> = events.receiveAsFlow()

    private val inputs = combine(
        query,
        openedFolderUri,
        settings.settings.map { it.librarySort }.distinctUntilChanged(),
        scanStates,
    ) { text, openedFolder, sort, states -> Inputs(text, openedFolder, sort, states) }

    val uiState: StateFlow<LibraryUiState> = combine(
        recentDocuments.recents,
        library.folders,
        library.files,
        inputs,
    ) { recents, folders, files, input ->
        LibraryUiState(
            isLoading = false,
            sort = input.sort,
            query = input.query,
            content = assembleLibraryContent(
                recents = recents,
                folders = folders,
                files = files,
                sort = input.sort,
                query = input.query,
                openedFolderUri = input.openedFolderUri,
                scanStates = input.scanStates,
            ),
            isScanning = input.scanStates.containsValue(FolderScanState.SCANNING),
        )
    }
        .flowOn(Dispatchers.Default)
        .catch {
            showMessage(LibraryMessage.GenericError)
            emit(LibraryUiState(isLoading = false))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    init {
        // Refresh the folders once per process so files added elsewhere show up.
        viewModelScope.launch {
            val folders = catchingNonCancellation { library.getFolders() }.getOrDefault(emptyList())
            if (folders.isNotEmpty() && initialScanDone.compareAndSet(false, true)) {
                folders.forEach { startScan(it.treeUri, it.displayName, userInitiated = false) }
            }
        }
    }

    fun setQuery(value: String) {
        savedStateHandle[KEY_QUERY] = value
    }

    fun setSort(sort: LibrarySort) {
        viewModelScope.launch {
            if (catchingNonCancellation { settings.setLibrarySort(sort) }.isFailure) {
                showMessage(LibraryMessage.GenericError)
            }
        }
    }

    fun openFolder(folder: FolderItem) {
        savedStateHandle[KEY_OPENED_FOLDER] = folder.treeUri
    }

    fun closeFolder() {
        savedStateHandle[KEY_OPENED_FOLDER] = null
    }

    /** A PDF picked with the system file picker: keep access across restarts, then open it. */
    fun onDocumentPicked(uri: Uri) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                catchingNonCancellation { documentAccess.takePersistableReadPermission(uri) }
            }
            events.send(LibraryEvent.OpenDocument(uri))
        }
    }

    fun toggleFavorite(item: DocumentItem) {
        if (!item.isRecent) return
        viewModelScope.launch {
            if (catchingNonCancellation { recentDocuments.setFavorite(item.uri, !item.isFavorite) }.isFailure) {
                showMessage(LibraryMessage.GenericError)
            }
        }
    }

    fun removeFromRecents(item: DocumentItem) {
        viewModelScope.launch {
            val removed = catchingNonCancellation {
                recentDocuments.get(item.uri)?.also { recentDocuments.remove(item.uri) }
            }.getOrElse {
                showMessage(LibraryMessage.GenericError)
                return@launch
            } ?: return@launch
            showMessage(LibraryMessage.RemovedFromRecents(item.name))
            forgetDocuments(listOf(removed))
        }
    }

    /** Clears history but keeps favorites. */
    fun clearHistory() {
        viewModelScope.launch {
            val removed = catchingNonCancellation {
                recentDocuments.recents.first().filterNot { it.isFavorite }.also { recentDocuments.clearHistory() }
            }.getOrElse {
                showMessage(LibraryMessage.GenericError)
                return@launch
            }
            showMessage(LibraryMessage.HistoryCleared)
            forgetDocuments(removed)
        }
    }

    /** A folder picked with the system folder picker: new folder, or a re-grant of a known one. */
    fun onFolderPicked(treeUri: Uri) {
        viewModelScope.launch {
            val granted = withContext(Dispatchers.IO) {
                catchingNonCancellation {
                    contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }.isSuccess
            }
            if (!granted) {
                showMessage(LibraryMessage.FolderPermissionFailed)
                return@launch
            }
            val key = treeUri.toString()
            val existing = catchingNonCancellation { library.getFolders() }
                .getOrDefault(emptyList())
                .firstOrNull { it.treeUri == key }
            val name = existing?.displayName ?: scanner.folderDisplayName(treeUri)
            if (existing == null) {
                if (catchingNonCancellation { library.addFolder(LibraryFolderEntity(treeUri = key, displayName = name)) }.isFailure) {
                    showMessage(LibraryMessage.GenericError)
                    return@launch
                }
            }
            showMessage(
                when {
                    existing == null -> LibraryMessage.FolderAdded(name)
                    scanStates.value[key] == FolderScanState.ACCESS_LOST -> LibraryMessage.FolderAccessRestored(name)
                    else -> LibraryMessage.FolderAlreadyAdded(name)
                },
            )
            startScan(key, name, userInitiated = true)
        }
    }

    /** Pull-to-refresh: rescans the open folder, or every folder. */
    fun refresh() {
        val opened = uiState.value.content.openedFolder
        if (opened != null) {
            rescan(opened)
            return
        }
        viewModelScope.launch {
            catchingNonCancellation { library.getFolders() }
                .getOrDefault(emptyList())
                .forEach { startScan(it.treeUri, it.displayName, userInitiated = true) }
        }
    }

    fun rescan(folder: FolderItem) = startScan(folder.treeUri, folder.name, userInitiated = true)

    fun removeFolder(folder: FolderItem) {
        viewModelScope.launch {
            // Wait for a running scan so it can't write results for a folder that no longer exists.
            scanJobs.remove(folder.treeUri)?.cancelAndJoin()
            scanStates.update { it - folder.treeUri }
            if (openedFolderUri.value == folder.treeUri) closeFolder()

            val folderFiles = catchingNonCancellation { library.files.first() }
                .getOrDefault(emptyList())
                .filter { it.folderUri == folder.treeUri }
                .map { it.uri }
            if (catchingNonCancellation { library.removeFolder(folder.treeUri) }.isFailure) {
                showMessage(LibraryMessage.GenericError)
                return@launch
            }
            showMessage(LibraryMessage.FolderRemoved(folder.name))

            val recentUris = catchingNonCancellation { recentDocuments.recents.first() }
                .getOrDefault(emptyList())
                .mapTo(HashSet()) { it.uri }
            withContext(Dispatchers.IO) {
                catchingNonCancellation {
                    contentResolver.releasePersistableUriPermission(
                        Uri.parse(folder.treeUri),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                folderFiles.filterNot { it in recentUris }.forEach { uri ->
                    coverLoader.evict(uri)
                    catchingNonCancellation { thumbnailStore.remove(Uri.parse(uri)) }
                }
            }
        }
    }

    private fun startScan(treeUri: String, name: String, userInitiated: Boolean) {
        scanJobs.remove(treeUri)?.cancel()
        scanStates.update { it + (treeUri to FolderScanState.SCANNING) }
        scanJobs[treeUri] = viewModelScope.launch {
            val result = scanPermits.withPermit { scanner.scan(Uri.parse(treeUri)) }
            val state = when (result) {
                is FolderScanResult.Success -> {
                    val stored = catchingNonCancellation { library.replaceScanResults(treeUri, result.files) }.isSuccess
                    when {
                        !stored -> FolderScanState.UNAVAILABLE
                        result.truncated -> FolderScanState.LIMIT_REACHED
                        else -> FolderScanState.IDLE
                    }
                }
                FolderScanResult.AccessLost -> FolderScanState.ACCESS_LOST
                FolderScanResult.Unavailable -> FolderScanState.UNAVAILABLE
            }
            scanStates.update { it + (treeUri to state) }

            val message = when (state) {
                FolderScanState.ACCESS_LOST -> LibraryMessage.FolderAccessLost(name, treeUri)
                FolderScanState.UNAVAILABLE -> LibraryMessage.FolderUnavailable(name)
                FolderScanState.LIMIT_REACHED ->
                    LibraryMessage.FolderLimitReached(name, (result as FolderScanResult.Success).files.size)
                FolderScanState.IDLE, FolderScanState.SCANNING -> null
            }
            // Lost access always matters; other problems are reported when the user asked for the scan.
            if (message != null && (userInitiated || state == FolderScanState.ACCESS_LOST)) showMessage(message)
        }
    }

    /**
     * Housekeeping after documents leave the history: drop their covers and give back their
     * persisted URI grants (Android caps how many an app may hold).
     */
    private suspend fun forgetDocuments(entities: List<RecentDocumentEntity>) {
        if (entities.isEmpty()) return
        val libraryFileUris = catchingNonCancellation { library.files.first() }
            .getOrDefault(emptyList())
            .mapTo(HashSet()) { it.uri }
        withContext(Dispatchers.IO) {
            val persisted = catchingNonCancellation {
                contentResolver.persistedUriPermissions.mapTo(HashSet()) { it.uri.toString() }
            }.getOrDefault(emptySet())
            entities.forEach { entity ->
                val uri = Uri.parse(entity.uri)
                if (entity.uri !in libraryFileUris) {
                    coverLoader.evict(entity.uri)
                    catchingNonCancellation { thumbnailStore.remove(uri) }
                }
                if (entity.uri in persisted) {
                    catchingNonCancellation {
                        contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
            }
        }
    }

    private fun showMessage(message: LibraryMessage) {
        events.trySend(LibraryEvent.ShowMessage(message))
    }

    private data class Inputs(
        val query: String,
        val openedFolderUri: String?,
        val sort: LibrarySort,
        val scanStates: Map<String, FolderScanState>,
    )

    private companion object {
        const val KEY_QUERY = "library_query"
        const val KEY_OPENED_FOLDER = "library_opened_folder"
        const val MAX_PARALLEL_SCANS = 2

        /** Folders are rescanned automatically only on the first library visit of a process. */
        val initialScanDone = AtomicBoolean(false)
    }
}
