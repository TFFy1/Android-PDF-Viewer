package io.github.tffy1.pdfviewer.ui.viewer.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tffy1.pdfviewer.R
import io.github.tffy1.pdfviewer.core.model.PageRect
import io.github.tffy1.pdfviewer.pdf.PdfDocument
import io.github.tffy1.pdfviewer.pdf.SearchMatch
import io.github.tffy1.pdfviewer.ui.viewer.document.PageLayoutInfo
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/* CONTRACT (scaffold). Owner: search/selection agent. Keep these signatures. */

data class SearchState(
    val query: String = "",
    val results: List<SearchMatch> = emptyList(),
    /** Index into [results] of the focused match, -1 when none. */
    val currentIndex: Int = -1,
    val isSearching: Boolean = false,
    /** 0..1 fraction of pages scanned. */
    val progress: Float = 0f,
    /** True when the search stopped early because it hit the result cap. */
    val isTruncated: Boolean = false,
) {
    val currentMatch: SearchMatch? get() = results.getOrNull(currentIndex)

    /** Indices of [results] per page, computed once per state (used by [SearchHighlights]). */
    internal val rangesByPage: Map<Int, IntRange> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        groupRangesByPage(results)
    }
}

/**
 * Full-text search over a document. Results stream in page by page (ordered by page) while
 * [SearchState.progress] advances; the focused match stays put once found.
 *
 * @param startPage supplies the page scanning starts from (typically the current page), so
 *   the first focused match is the first one at or after it. Defaults to the first page.
 */
class SearchController(
    private val document: PdfDocument,
    private val scope: CoroutineScope,
    private val startPage: () -> Int = { 0 },
) {
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var activeQuery: String? = null

    @Volatile
    private var scanStarted = false

    /** Bumped for every new search/clear so a cancelled scan can never publish stale results. */
    private val generation = AtomicInteger()

    /** Debounced search, suitable for calling on every keystroke. Blank queries clear. */
    fun search(query: String) {
        launchSearch(query, immediate = false)
    }

    /** Searches right away (IME search action), skipping the debounce. */
    internal fun searchNow(query: String) {
        launchSearch(query, immediate = true)
    }

    fun next() {
        _state.update { it.copy(currentIndex = stepIndex(it.currentIndex, 1, it.results.size)) }
    }

    fun previous() {
        _state.update { it.copy(currentIndex = stepIndex(it.currentIndex, -1, it.results.size)) }
    }

    fun clear() {
        generation.incrementAndGet()
        searchJob?.cancel()
        searchJob = null
        activeQuery = null
        scanStarted = false
        _state.value = SearchState()
    }

    private fun launchSearch(query: String, immediate: Boolean) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            clear()
            return
        }
        // Same query again: keep the running/finished search unless the user wants to skip
        // a still-pending debounce.
        if (trimmed == activeQuery && (scanStarted || !immediate)) return

        searchJob?.cancel()
        val token = generation.incrementAndGet()
        activeQuery = trimmed
        scanStarted = false
        val firstPage = startPage()
        // Keep the previous results on screen during the debounce to avoid flicker while typing.
        _state.update { it.copy(isSearching = true) }
        searchJob = scope.launch {
            if (!immediate) delay(DEBOUNCE_MILLIS)
            scanStarted = true
            runSearch(trimmed, firstPage, token)
        }
    }

    private suspend fun runSearch(query: String, startAt: Int, token: Int) {
        val pageCount = document.pageCount
        val order = scanOrder(pageCount, startAt)
        val firstPage = order.firstOrNull() ?: 0
        publishIfCurrent(token) { SearchState(query = query, isSearching = pageCount > 0) }
        if (pageCount <= 0) {
            publishIfCurrent(token) { SearchState(query = query, progress = 1f) }
            return
        }

        val perPage = arrayOfNulls<List<SearchMatch>>(pageCount)
        var total = 0
        var truncated = false
        var pendingMatches = false
        var lastPublish = 0L

        for ((scanned, page) in order.withIndex()) {
            val matches = try {
                document.searchPage(page, query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList() // An unreadable page must not abort the whole search.
            }
            if (matches.isNotEmpty()) {
                val room = MAX_RESULTS - total
                val kept = if (matches.size > room) {
                    truncated = true
                    matches.subList(0, room).toList()
                } else {
                    matches
                }
                perPage[page] = kept
                total += kept.size
                pendingMatches = true
            }

            val now = System.nanoTime()
            val firstHit = pendingMatches && _state.value.results.isEmpty()
            if (truncated || firstHit || now - lastPublish >= PUBLISH_INTERVAL_NANOS) {
                val progress = (scanned + 1).toFloat() / pageCount
                publishResults(token, query, perPage, total, progress, firstPage, searching = !truncated, truncated)
                lastPublish = now
                pendingMatches = false
            }
            if (truncated) return
            yield()
        }
        publishResults(token, query, perPage, total, 1f, firstPage, searching = false, truncated = false)
    }

    private fun publishResults(
        token: Int,
        query: String,
        perPage: Array<List<SearchMatch>?>,
        total: Int,
        progress: Float,
        firstPage: Int,
        searching: Boolean,
        truncated: Boolean,
    ) {
        val results = ArrayList<SearchMatch>(total)
        for (matches in perPage) if (matches != null) results.addAll(matches)
        publishIfCurrent(token) { old ->
            SearchState(
                query = query,
                results = results,
                currentIndex = reconcileIndex(results, old.currentMatch, firstPage),
                isSearching = searching,
                progress = progress,
                isTruncated = truncated,
            )
        }
    }

    private inline fun publishIfCurrent(token: Int, crossinline transform: (SearchState) -> SearchState) {
        _state.update { old -> if (generation.get() == token) transform(old) else old }
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 300L
        const val PUBLISH_INTERVAL_NANOS = 120_000_000L
        const val MAX_RESULTS = 10_000
    }
}

/**
 * Replaces the viewer's top bar while searching. Window insets are left to the caller
 * (pass e.g. `Modifier.windowInsetsPadding(...)`), like the viewer's own top bar.
 */
@Composable
fun SearchTopBar(controller: SearchController, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    var text by rememberSaveable { mutableStateOf(controller.state.value.query) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val close: () -> Unit = {
        controller.clear()
        onClose()
    }

    BackHandler(onBack = close)
    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }

    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = close) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.search_close),
                    )
                }
                TextField(
                    value = text,
                    onValueChange = {
                        text = it
                        controller.search(it)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    placeholder = {
                        Text(
                            stringResource(R.string.search_hint),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingIcon = if (text.isNotEmpty()) {
                        {
                            IconButton(
                                onClick = {
                                    text = ""
                                    controller.clear()
                                    focusRequester.requestFocus()
                                },
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_clear))
                            }
                        }
                    } else {
                        null
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            controller.searchNow(text)
                            keyboard?.hide()
                        },
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                )
                if (text.isNotBlank()) SearchCounter(state)
                val hasResults = state.results.isNotEmpty()
                IconButton(onClick = controller::previous, enabled = hasResults) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.search_previous))
                }
                IconButton(onClick = controller::next, enabled = hasResults) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.search_next))
                }
            }
            if (state.isSearching) {
                val searchingLabel = stringResource(R.string.search_searching)
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .semantics { contentDescription = searchingLabel },
                )
            }
        }
    }
}

/** "3 / 57", or "No results" once a finished search found nothing. */
@Composable
private fun SearchCounter(state: SearchState) {
    val total = state.results.size
    val label: String
    val description: String
    if (total > 0) {
        val position = state.currentIndex + 1
        label = if (state.isTruncated) {
            stringResource(R.string.search_counter_truncated, position, total)
        } else {
            stringResource(R.string.search_counter, position, total)
        }
        description = stringResource(R.string.search_counter_description, position, total)
    } else if (!state.isSearching && state.query.isNotEmpty()) {
        label = stringResource(R.string.search_no_results)
        description = label
    } else {
        return
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .semantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            },
    )
}

/** Draws match highlights for one page (current match emphasized). Use inside DocumentView.pageOverlay. */
@Composable
fun SearchHighlights(controller: SearchController, page: PageLayoutInfo) {
    val state by controller.state.collectAsState()
    val range = state.rangesByPage[page.pageIndex] ?: return
    val results = state.results
    val currentIndex = state.currentIndex
    // No pointer input here: the overlay never consumes touches.
    Canvas(Modifier.fillMaxSize()) {
        val scale = page.scale
        for (i in range) {
            if (i == currentIndex) continue
            results[i].rects.forEach { drawMatchRect(it, scale, MatchFill, null) }
        }
        if (currentIndex in range) {
            val outline = Stroke(width = 2.dp.toPx())
            results[currentIndex].rects.forEach { drawMatchRect(it, scale, CurrentMatchFill, outline) }
        }
    }
}

private fun DrawScope.drawMatchRect(rect: PageRect, scale: Float, fill: Color, outline: Stroke?) {
    val pad = MATCH_PADDING_PT * scale
    val topLeft = Offset(rect.left * scale - pad, rect.top * scale - pad)
    val size = Size(rect.width * scale + 2 * pad, rect.height * scale + 2 * pad)
    drawRect(color = fill, topLeft = topLeft, size = size)
    if (outline != null) drawRect(color = CurrentMatchOutline, topLeft = topLeft, size = size, style = outline)
}

private const val MATCH_PADDING_PT = 0.75f
private val MatchFill = Color(0x66FFD600)
private val CurrentMatchFill = Color(0x80FF9100)
private val CurrentMatchOutline = Color(0xFFE65100)
