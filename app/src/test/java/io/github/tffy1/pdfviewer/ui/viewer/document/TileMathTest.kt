package io.github.tffy1.pdfviewer.ui.viewer.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileMathTest {

    @Test
    fun tileRect_clipsLastRowAndColumn() {
        assertEquals(TileRect(0, 0, 512, 512), tileRect(TileCoord(0, 0), 512, 1200, 1000))
        assertEquals(TileRect(1024, 512, 1200, 1000), tileRect(TileCoord(2, 1), 512, 1200, 1000))
    }

    @Test
    fun visibleTiles_coversVisibleRect() {
        val tiles = visibleTiles(4096, 4096, TileRect(600, 100, 1500, 700), 512)
        assertEquals(
            setOf(TileCoord(1, 0), TileCoord(2, 0), TileCoord(1, 1), TileCoord(2, 1)),
            tiles.toSet(),
        )
        assertEquals(4, tiles.size)
    }

    @Test
    fun visibleTiles_edgeExactlyOnTileBoundaryIsExclusive() {
        val tiles = visibleTiles(2048, 2048, TileRect(0, 0, 512, 512), 512)
        assertEquals(listOf(TileCoord(0, 0)), tiles)
    }

    @Test
    fun visibleTiles_clipsToPage() {
        val tiles = visibleTiles(700, 700, TileRect(-300, -300, 5000, 5000), 512)
        assertEquals(4, tiles.size)
    }

    @Test
    fun visibleTiles_emptyWhenOffPage() {
        assertTrue(visibleTiles(1000, 1000, TileRect(1000, 0, 1500, 500), 512).isEmpty())
        assertTrue(visibleTiles(1000, 1000, TileRect(10, 10, 10, 500), 512).isEmpty())
    }

    @Test
    fun visibleTiles_centerTileFirst() {
        val tiles = visibleTiles(4096, 4096, TileRect(0, 0, 1536, 1536), 512)
        assertEquals(TileCoord(1, 1), tiles.first())
        assertEquals(9, tiles.size)
    }
}
