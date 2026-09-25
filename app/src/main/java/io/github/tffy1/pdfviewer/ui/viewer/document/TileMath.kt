package io.github.tffy1.pdfviewer.ui.viewer.document

import kotlin.math.max
import kotlin.math.min

/** A tile of a page rendered at a given scaled size; tiles form a grid of [tileSize] px squares. */
internal data class TileCoord(val column: Int, val row: Int)

/** Pixel rect inside the scaled page; right/bottom exclusive. */
internal data class TileRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** Pixel rect of [coord] clipped to a page of [pageWidth] x [pageHeight] px. */
internal fun tileRect(coord: TileCoord, tileSize: Int, pageWidth: Int, pageHeight: Int): TileRect {
    val left = coord.column * tileSize
    val top = coord.row * tileSize
    return TileRect(left, top, min(left + tileSize, pageWidth), min(top + tileSize, pageHeight))
}

/**
 * Tiles of a [pageWidth] x [pageHeight] px page that intersect the visible part of the page
 * ([visible], page px, right/bottom exclusive). Sorted so tiles closest to the center of the
 * visible area come first (they are rendered first).
 */
internal fun visibleTiles(pageWidth: Int, pageHeight: Int, visible: TileRect, tileSize: Int): List<TileCoord> {
    val left = max(0, visible.left)
    val top = max(0, visible.top)
    val right = min(pageWidth, visible.right)
    val bottom = min(pageHeight, visible.bottom)
    if (right <= left || bottom <= top || tileSize <= 0) return emptyList()
    val firstColumn = left / tileSize
    val lastColumn = (right - 1) / tileSize
    val firstRow = top / tileSize
    val lastRow = (bottom - 1) / tileSize
    val centerX = (left + right) / 2.0
    val centerY = (top + bottom) / 2.0
    val tiles = ArrayList<TileCoord>((lastColumn - firstColumn + 1) * (lastRow - firstRow + 1))
    for (row in firstRow..lastRow) {
        for (column in firstColumn..lastColumn) tiles += TileCoord(column, row)
    }
    tiles.sortBy { tile ->
        val dx = (tile.column + 0.5) * tileSize - centerX
        val dy = (tile.row + 0.5) * tileSize - centerY
        dx * dx + dy * dy
    }
    return tiles
}
