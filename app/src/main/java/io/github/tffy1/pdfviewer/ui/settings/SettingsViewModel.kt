package io.github.tffy1.pdfviewer.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tffy1.pdfviewer.data.repository.RecentDocumentsRepository
import io.github.tffy1.pdfviewer.data.settings.AppSettings
import io.github.tffy1.pdfviewer.data.settings.PageColorMode
import io.github.tffy1.pdfviewer.data.settings.ScrollMode
import io.github.tffy1.pdfviewer.data.settings.SettingsRepository
import io.github.tffy1.pdfviewer.data.settings.ThemeMode
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One-off results shown as a snackbar. */
enum class SettingsMessage { HISTORY_CLEARED, THUMBNAILS_CLEARED, FAILED }

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val recentDocumentsRepository: RecentDocumentsRepository,
    /** Directory holding cached document thumbnails (filesDir/thumbnails). */
    private val thumbnailDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    /** Current settings; null until the first value has been read from disk. */
    val settings: StateFlow<AppSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _thumbnailCacheBytes = MutableStateFlow<Long?>(null)

    /** Size of the thumbnail cache in bytes; null while it is being measured. */
    val thumbnailCacheBytes: StateFlow<Long?> = _thumbnailCacheBytes.asStateFlow()

    private val _messages = Channel<SettingsMessage>(Channel.BUFFERED)
    val messages: Flow<SettingsMessage> = _messages.receiveAsFlow()

    init {
        refreshThumbnailCacheSize()
    }

    fun setThemeMode(value: ThemeMode) {
        save { it.setThemeMode(value) }
    }

    fun setDynamicColor(value: Boolean) {
        save { it.setDynamicColor(value) }
    }

    fun setScrollMode(value: ScrollMode) {
        save { it.setScrollMode(value) }
    }

    fun setPageColorMode(value: PageColorMode) {
        save { it.setPageColorMode(value) }
    }

    fun setRememberLastPage(value: Boolean) {
        save { it.setRememberLastPage(value) }
    }

    fun setKeepScreenOn(value: Boolean) {
        save { it.setKeepScreenOn(value) }
    }

    fun setVolumeKeysTurnPages(value: Boolean) {
        save { it.setVolumeKeysTurnPages(value) }
    }

    fun setShowPageNumberOverlay(value: Boolean) {
        save { it.setShowPageNumberOverlay(value) }
    }

    /** Empties Recents; favorites are kept by the repository. */
    fun clearRecentHistory() {
        viewModelScope.launch {
            val ok = attempt { recentDocumentsRepository.clearHistory() }
            _messages.send(if (ok) SettingsMessage.HISTORY_CLEARED else SettingsMessage.FAILED)
        }
    }

    /** Deletes cached thumbnails and forgets the stale paths so covers get regenerated. */
    fun clearThumbnailCache() {
        viewModelScope.launch {
            val ok = attempt {
                val deleted = withContext(ioDispatcher) { CacheFiles.clearContents(thumbnailDir) }
                val prefix = thumbnailDir.absolutePath + File.separator
                recentDocumentsRepository.recents.first()
                    .filter { it.thumbnailPath?.startsWith(prefix) == true }
                    .forEach { recentDocumentsRepository.setThumbnail(it.uri, null) }
                check(deleted) { "Some thumbnails could not be deleted" }
            }
            refreshThumbnailCacheSize()
            _messages.send(if (ok) SettingsMessage.THUMBNAILS_CLEARED else SettingsMessage.FAILED)
        }
    }

    private fun refreshThumbnailCacheSize() {
        viewModelScope.launch {
            _thumbnailCacheBytes.value = withContext(ioDispatcher) {
                runCatching { CacheFiles.sizeOf(thumbnailDir) }.getOrDefault(0L)
            }
        }
    }

    private fun save(block: suspend (SettingsRepository) -> Unit) {
        viewModelScope.launch {
            if (!attempt { block(settingsRepository) }) _messages.send(SettingsMessage.FAILED)
        }
    }

    /** Runs [block], turning any failure (except cancellation) into `false`. */
    private suspend fun attempt(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Settings action failed", e)
        false
    }

    private companion object {
        const val TAG = "SettingsViewModel"
    }
}
