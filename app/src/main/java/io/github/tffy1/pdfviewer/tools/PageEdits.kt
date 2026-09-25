package io.github.tffy1.pdfviewer.tools

/**
 * One page of the "organize pages" result: which source page it is and how much extra
 * clockwise rotation the user applied. [rotationDelta] is cumulative (e.g. -90, 450) so the
 * UI can animate in the direction the user tapped; use [PageEdits.normalizeRotation] when saving.
 */
data class PageEdit(val sourceIndex: Int, val rotationDelta: Int = 0)

/** Pure list operations used by the organize tool. */
object PageEdits {
    /** Maps any multiple of 90 (negative or > 360) to 0, 90, 180 or 270. */
    fun normalizeRotation(degrees: Int): Int = ((degrees % 360) + 360) % 360

    fun initial(pageCount: Int): List<PageEdit> = List(pageCount) { PageEdit(it) }

    fun rotate(pages: List<PageEdit>, selected: Set<Int>, degrees: Int): List<PageEdit> =
        pages.map { if (it.sourceIndex in selected) it.copy(rotationDelta = it.rotationDelta + degrees) else it }

    fun delete(pages: List<PageEdit>, selected: Set<Int>): List<PageEdit> =
        pages.filterNot { it.sourceIndex in selected }

    /**
     * Moves every selected page one position towards the start. A selected page directly after
     * another selected page (or at the start) keeps its place relative to it, so blocks move
     * together and nothing jumps over the selection.
     */
    fun moveEarlier(pages: List<PageEdit>, selected: Set<Int>): List<PageEdit> {
        val result = pages.toMutableList()
        for (i in 1 until result.size) {
            if (result[i].sourceIndex in selected && result[i - 1].sourceIndex !in selected) {
                val tmp = result[i - 1]
                result[i - 1] = result[i]
                result[i] = tmp
            }
        }
        return result
    }

    /** Mirror of [moveEarlier]. */
    fun moveLater(pages: List<PageEdit>, selected: Set<Int>): List<PageEdit> {
        val result = pages.toMutableList()
        for (i in result.size - 2 downTo 0) {
            if (result[i].sourceIndex in selected && result[i + 1].sourceIndex !in selected) {
                val tmp = result[i + 1]
                result[i + 1] = result[i]
                result[i] = tmp
            }
        }
        return result
    }

    /** True when the edits would produce exactly the original document. */
    fun isUnchanged(pages: List<PageEdit>, pageCount: Int): Boolean =
        pages.size == pageCount &&
            pages.withIndex().all { (i, p) -> p.sourceIndex == i && normalizeRotation(p.rotationDelta) == 0 }
}
