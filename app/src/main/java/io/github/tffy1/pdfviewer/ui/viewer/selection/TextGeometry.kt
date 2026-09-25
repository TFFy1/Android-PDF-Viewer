package io.github.tffy1.pdfviewer.ui.viewer.selection

import io.github.tffy1.pdfviewer.core.model.PagePoint
import io.github.tffy1.pdfviewer.core.model.PageRect
import io.github.tffy1.pdfviewer.pdf.PageText
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Pure text-geometry helpers for text selection (no Android dependencies, unit-tested).
 * All coordinates are page space (points, top-left origin).
 */

/** A range of char indices, [end] exclusive. */
internal data class CharSpan(val start: Int, val end: Int)

/**
 * Vertical distance counts this much more than horizontal, so a point inside a line's band picks
 * a char of that line (e.g. dragging past the end of a short line) rather than the line below.
 */
private const val VERTICAL_WEIGHT = 10f

/** Chars created by text extraction (line breaks) have zero-size boxes and no real position. */
internal fun PageRect.hasArea(): Boolean = right > left && bottom > top

/** Euclidean distance from [point] to [rect]; 0 when inside. */
internal fun distanceToRect(rect: PageRect, point: PagePoint): Float {
    val dx = max(max(rect.left - point.x, 0f), point.x - rect.right)
    val dy = max(max(rect.top - point.y, 0f), point.y - rect.bottom)
    return sqrt(dx * dx + dy * dy)
}

/**
 * Index of the visible (non-whitespace, positioned) char nearest to [point], or null when none
 * lies within [maxDistance]. Chars on the same line as the point win over closer-looking chars
 * on neighbouring lines.
 */
internal fun nearestCharIndex(
    text: String,
    boxes: List<PageRect>,
    point: PagePoint,
    maxDistance: Float = Float.POSITIVE_INFINITY,
): Int? {
    var best: Int? = null
    var bestScore = Float.POSITIVE_INFINITY
    val count = min(text.length, boxes.size)
    for (i in 0 until count) {
        if (text[i].isWhitespace()) continue
        val box = boxes[i]
        if (!box.hasArea()) continue
        val dx = max(max(box.left - point.x, 0f), point.x - box.right)
        val dy = max(max(box.top - point.y, 0f), point.y - box.bottom)
        if (sqrt(dx * dx + dy * dy) > maxDistance) continue
        val weightedDy = dy * VERTICAL_WEIGHT
        val score = dx * dx + weightedDy * weightedDy
        if (score < bestScore) {
            bestScore = score
            best = i
        }
    }
    return best
}

/**
 * Caret position (0..text.length) closest to [point]: before the nearest char when the point is
 * on its left half, after it otherwise.
 */
internal fun caretIndexAt(text: String, boxes: List<PageRect>, point: PagePoint): Int? {
    val index = nearestCharIndex(text, boxes, point) ?: return null
    return if (point.x > boxes[index].centerX) index + 1 else index
}

/**
 * Selection spanned between a fixed [anchor] caret and a moving [caret]; the two may cross, the
 * result is always ordered and never empty (at least one char) as long as the text isn't empty.
 */
internal fun spanBetweenCarets(anchor: Int, caret: Int, length: Int): CharSpan? {
    if (length <= 0) return null
    var start = min(anchor, caret).coerceIn(0, length)
    var end = max(anchor, caret).coerceIn(0, length)
    if (start == end) {
        if (end < length) end++ else start--
    }
    return CharSpan(start, end)
}

/**
 * Chars that stand alone as a "word": CJK ideographs and kana are written without spaces, so
 * expanding over letters would select a whole sentence.
 */
private fun isStandaloneChar(codePoint: Int): Boolean {
    if (Character.isIdeographic(codePoint)) return true
    val script = Character.UnicodeScript.of(codePoint)
    return script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA
}

private fun isWordChar(codePoint: Int): Boolean {
    if (Character.isLetterOrDigit(codePoint)) return true
    return when (Character.getType(codePoint)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        Character.CONNECTOR_PUNCTUATION.toInt(),
        -> true
        else -> false
    }
}

private fun isExpandable(codePoint: Int): Boolean = isWordChar(codePoint) && !isStandaloneChar(codePoint)

/** Apostrophes inside a word ("don't", "l’été") belong to it. */
private fun isJoiner(codePoint: Int): Boolean = codePoint == '\''.code || codePoint == '’'.code

private fun isSelectable(codePoint: Int): Boolean =
    !Character.isWhitespace(codePoint) &&
        !Character.isISOControl(codePoint) &&
        Character.getType(codePoint) != Character.UNASSIGNED.toInt() &&
        !Character.isSpaceChar(codePoint)

/**
 * The word containing the char at [index]: a run of letters/digits (with inner apostrophes).
 * A CJK char or a punctuation mark is selected on its own. Returns null for whitespace.
 */
internal fun wordSpanAt(text: String, index: Int): CharSpan? {
    if (index !in text.indices) return null
    val cpStart = if (Character.isLowSurrogate(text[index]) && index > 0 && Character.isHighSurrogate(text[index - 1])) {
        index - 1
    } else {
        index
    }
    val codePoint = text.codePointAt(cpStart)
    if (!isSelectable(codePoint)) return null
    val cpEnd = cpStart + Character.charCount(codePoint)
    if (!isExpandable(codePoint)) return CharSpan(cpStart, cpEnd)

    var start = cpStart
    while (start > 0) {
        val prev = Character.codePointBefore(text, start)
        val prevStart = start - Character.charCount(prev)
        start = when {
            isExpandable(prev) -> prevStart
            isJoiner(prev) && prevStart > 0 && isExpandable(Character.codePointBefore(text, prevStart)) -> prevStart
            else -> break
        }
    }
    var end = cpEnd
    while (end < text.length) {
        val next = text.codePointAt(end)
        val nextEnd = end + Character.charCount(next)
        end = when {
            isExpandable(next) -> nextEnd
            isJoiner(next) && nextEnd < text.length && isExpandable(text.codePointAt(nextEnd)) -> nextEnd
            else -> break
        }
    }
    return CharSpan(start, end)
}

/** Span from the first to the last visible char of [text], or null if it has none. */
internal fun visibleSpan(text: String): CharSpan? {
    val first = text.indexOfFirst { !it.isWhitespace() && !it.isISOControl() }
    if (first < 0) return null
    val last = text.indexOfLast { !it.isWhitespace() && !it.isISOControl() }
    return CharSpan(first, last + 1)
}

/**
 * Merges the boxes of chars in [span] into one rect per text line. Whitespace and zero-size
 * boxes are skipped (the gaps between words are covered by the union anyway). A char joins the
 * current line when it overlaps it vertically by at least half of the smaller height, which
 * works for left-to-right and right-to-left text alike.
 */
internal fun mergeLineRects(text: String, boxes: List<PageRect>, span: CharSpan): List<PageRect> {
    val lines = ArrayList<PageRect>()
    var current: PageRect? = null
    val end = min(span.end, min(text.length, boxes.size))
    for (i in max(span.start, 0) until end) {
        if (text[i].isWhitespace()) continue
        val box = boxes[i]
        if (!box.hasArea()) continue
        val line = current
        current = when {
            line == null -> box
            isSameLine(line, box) -> line.union(box)
            else -> {
                lines += line
                box
            }
        }
    }
    current?.let { lines += it }
    return lines
}

private fun isSameLine(line: PageRect, box: PageRect): Boolean {
    val overlap = min(line.bottom, box.bottom) - max(line.top, box.top)
    return overlap >= min(line.height, box.height) * 0.5f
}

/**
 * Text suitable for the clipboard: extraction line breaks ("\r\n") become "\n", other control
 * chars and non-characters (e.g. U+FFFE markers some extractors emit) are dropped.
 */
internal fun cleanSelectedText(raw: String): String {
    val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
    val out = StringBuilder(normalized.length)
    for (c in normalized) {
        val keep = c == '\n' || c == '\t' || (!c.isISOControl() && c != '￾' && c != '￿')
        if (keep) out.append(c)
    }
    return out.toString()
}

/** Builds the selection for [span] of [pageText], or null when the span has no visible char. */
internal fun buildSelection(pageText: PageText, span: CharSpan): TextSelection? {
    val length = min(pageText.text.length, pageText.charBoxes.size)
    val start = span.start.coerceIn(0, length)
    val end = span.end.coerceIn(start, length)
    val clamped = CharSpan(start, end)
    val rects = mergeLineRects(pageText.text, pageText.charBoxes, clamped)
    if (rects.isEmpty()) return null
    return TextSelection(
        pageIndex = pageText.pageIndex,
        startIndex = start,
        endIndex = end,
        text = cleanSelectedText(pageText.text.substring(start, end)),
        rects = rects,
    )
}
