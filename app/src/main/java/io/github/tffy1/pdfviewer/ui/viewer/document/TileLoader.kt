package io.github.tffy1.pdfviewer.ui.viewer.document

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What part of a page needs high-resolution tiles: the page's current scaled size and visible rect (page px). */
internal data class TileRequest(
    val scaledPageWidth: Int,
    val scaledPageHeight: Int,
    val visible: TileRect,
)

internal class LoadedTile(
    val coord: TileCoord,
    val rect: TileRect,
    val scaledPageWidth: Int,
    val scaledPageHeight: Int,
    val image: ImageBitmap,
)

/**
 * Loads the high-resolution tiles of one page. Tiles that leave the viewport are dropped and their
 * pending renders cancelled. When the scale changes, the tiles of the previous scale stay
 * drawable ([staleTiles]) until the new ones covering the viewport are loaded, so zooming never
 * flashes back to the blurry base bitmap. Main thread only.
 */
internal class TileLoader(
    private val pageIndex: Int,
    private val renderer: PageRenderer,
    private val scope: CoroutineScope,
    private val priority: () -> Int,
) {
    var tiles: List<LoadedTile> by mutableStateOf(emptyList())
        private set
    var staleTiles: List<LoadedTile> by mutableStateOf(emptyList())
        private set

    /** Scaled page size the current [tiles] belong to (0 when none). */
    var scaledPageWidth = 0
        private set
    private var scaledPageHeight = 0
    private var needed: Set<TileCoord> = emptySet()
    private val jobs = HashMap<TileCoord, Job>()

    fun isNewScale(request: TileRequest): Boolean =
        request.scaledPageWidth != scaledPageWidth || request.scaledPageHeight != scaledPageHeight

    fun update(request: TileRequest?) {
        if (request == null) {
            clear()
            return
        }
        if (isNewScale(request)) {
            cancelJobs()
            if (tiles.isNotEmpty()) staleTiles = tiles
            tiles = emptyList()
            scaledPageWidth = request.scaledPageWidth
            scaledPageHeight = request.scaledPageHeight
        }
        val width = request.scaledPageWidth
        val height = request.scaledPageHeight
        val coords = visibleTiles(width, height, request.visible, DocumentViewDefaults.TILE_SIZE)
        needed = coords.toHashSet()

        jobs.keys.filter { it !in needed }.forEach { jobs.remove(it)?.cancel() }
        var current = tiles.filter { it.coord in needed }
        val loaded = current.mapTo(HashSet()) { it.coord }
        for (coord in coords) {
            if (coord in loaded || coord in jobs) continue
            val rect = tileRect(coord, DocumentViewDefaults.TILE_SIZE, width, height)
            val key = RenderKey.tile(pageIndex, width, height, rect)
            val cached = renderer.cached(key)
            if (cached != null) {
                current = current + LoadedTile(coord, rect, width, height, cached)
                continue
            }
            // Registered before it starts so the job always finds (and removes) its own entry.
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val self = coroutineContext[Job]
                try {
                    val image = renderer.render(key, priority)
                    if (image != null && isActive && coord in needed && width == scaledPageWidth && height == scaledPageHeight) {
                        tiles = tiles + LoadedTile(coord, rect, width, height, image)
                    }
                } finally {
                    if (jobs[coord] === self) jobs.remove(coord)
                }
                dropStaleIfCovered()
            }
            jobs[coord] = job
            job.start()
        }
        if (current.size != tiles.size || current.any { it !in tiles }) tiles = current
        dropStaleIfCovered()
    }

    private fun dropStaleIfCovered() {
        if (staleTiles.isEmpty()) return
        val loaded = tiles.mapTo(HashSet()) { it.coord }
        if (loaded.containsAll(needed)) staleTiles = emptyList()
    }

    private fun clear() {
        cancelJobs()
        needed = emptySet()
        scaledPageWidth = 0
        scaledPageHeight = 0
        if (tiles.isNotEmpty()) tiles = emptyList()
        if (staleTiles.isNotEmpty()) staleTiles = emptyList()
    }

    private fun cancelJobs() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }
}
