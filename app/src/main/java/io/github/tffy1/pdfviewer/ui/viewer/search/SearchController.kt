package io.github.tffy1.pdfviewer.ui.viewer.search

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.tffy1.pdfviewer.pdf.PdfDocument
import io.github.tffy1.pdfviewer.pdf.SearchMatch
import io.github.tffy1.pdfviewer.ui.viewer.document.PageLayoutInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/* CONTRACT (scaffold). Owner: search/selection agent. Keep these signatures. */

data class SearchState(
    val query: String = "",
    val results: List<SearchMatch> = emptyList(),
    /** Index into [results] of the focused match, -1 when none. */
    val currentIndex: Int = -1,
    val isSearching: Boolean = false,
    /** 0..1 fraction of pages scanned. */
    val progress: Float = 0f,
) {
    val currentMatch: SearchMatch? get() = results.getOrNull(currentIndex)
}

class SearchController(
    private val document: PdfDocument,
    private val scope: CoroutineScope,
) {
    val state: StateFlow<SearchState> get() = TODO("search agent")
    fun search(query: String) { TODO("search agent") }
    fun next() { TODO("search agent") }
    fun previous() { TODO("search agent") }
    fun clear() { TODO("search agent") }
}

/** Replaces the viewer's top bar while searching. */
@Composable
fun SearchTopBar(controller: SearchController, onClose: () -> Unit, modifier: Modifier = Modifier) {
    TODO("search agent")
}

/** Draws match highlights for one page (current match emphasized). Use inside DocumentView.pageOverlay. */
@Composable
fun SearchHighlights(controller: SearchController, page: PageLayoutInfo) {
    TODO("search agent")
}
