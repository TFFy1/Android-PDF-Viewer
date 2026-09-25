package io.github.tffy1.pdfviewer.pdf

import io.github.tffy1.pdfviewer.core.model.PageRect
import kotlin.math.max
import kotlin.math.min

/*
 * Pure geometry helpers for text (page space). Shared by search highlighting and text selection.
 */

/** True for the placeholder boxes of chars without geometry (see [PageText.charBoxes]). */
fun PageRect.isZeroSize(): Boolean = width <= 0f && height <= 0f

/**
 * Fraction (0..1) of the smaller box's height that [a] and [b] share vertically.
 * Boxes without height never overlap.
 */
fun verticalOverlapRatio(a: PageRect, b: PageRect): Float {
    val smallerHeight = min(a.height, b.height)
    if (smallerHeight <= 0f) return 0f
    val overlap = min(a.bottom, b.bottom) - max(a.top, b.top)
    return (overlap / smallerHeight).coerceIn(0f, 1f)
}

/**
 * Merges consecutive char boxes (in text order) into one rectangle per text line.
 * Two boxes are on the same line when they overlap vertically by more than [minOverlap]
 * of the smaller height. Boxes without area (placeholders, blank glyphs) are ignored.
 */
fun mergeCharBoxesIntoLines(boxes: List<PageRect>, minOverlap: Float = 0.5f): List<PageRect> {
    val lines = ArrayList<PageRect>()
    var line: PageRect? = null
    var last: PageRect? = null
    for (box in boxes) {
        if (box.width <= 0f || box.height <= 0f) continue
        val current = line
        line = if (current != null && last != null && sameLine(current, last, box, minOverlap)) {
            current.union(box)
        } else {
            current?.let(lines::add)
            box
        }
        last = box
    }
    line?.let(lines::add)
    return lines
}

/**
 * The overlap test runs against the line so far, so short glyphs (quotes, dots) join tall ones.
 * The box must also not start below the previous box, so a tall first glyph (e.g. a drop cap)
 * does not swallow the next line.
 */
private fun sameLine(line: PageRect, last: PageRect, box: PageRect, minOverlap: Float): Boolean =
    verticalOverlapRatio(line, box) > minOverlap && box.top < last.bottom
