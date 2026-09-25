package io.github.tffy1.pdfviewer.ui.viewer.sheets

import io.github.tffy1.pdfviewer.pdf.TocEntry

/** One visible line of the table-of-contents tree. */
data class TocRow(
    val entry: TocEntry,
    /** Stable key made of the child indices along the path, e.g. "0/3/1". */
    val key: String,
    val depth: Int,
    val hasChildren: Boolean,
    val expanded: Boolean,
)

/**
 * Flattens the outline into the rows currently visible: children are listed right after their
 * parent, and only when the parent's key is in [expandedKeys].
 */
fun flattenToc(entries: List<TocEntry>, expandedKeys: Set<String>): List<TocRow> {
    val rows = ArrayList<TocRow>()
    fun visit(level: List<TocEntry>, parentKey: String?, depth: Int) {
        level.forEachIndexed { index, entry ->
            val key = if (parentKey == null) "$index" else "$parentKey/$index"
            val hasChildren = entry.children.isNotEmpty()
            val expanded = hasChildren && key in expandedKeys
            rows += TocRow(entry, key, depth, hasChildren, expanded)
            if (expanded) visit(entry.children, key, depth + 1)
        }
    }
    visit(entries, parentKey = null, depth = 0)
    return rows
}

/**
 * Key of the section being read: the last entry (in reading order) whose target page is at or
 * before [pageIndex]. Null when no entry qualifies.
 */
fun currentTocKey(entries: List<TocEntry>, pageIndex: Int): String? {
    var bestKey: String? = null
    var bestPage = -1
    fun visit(level: List<TocEntry>, parentKey: String?) {
        level.forEachIndexed { index, entry ->
            val key = if (parentKey == null) "$index" else "$parentKey/$index"
            if (entry.pageIndex in 0..pageIndex && entry.pageIndex >= bestPage) {
                bestPage = entry.pageIndex
                bestKey = key
            }
            visit(entry.children, key)
        }
    }
    visit(entries, parentKey = null)
    return bestKey
}

/** Keys of all ancestors of [key] ("0/3/1" -> {"0", "0/3"}), i.e. what must be expanded to show it. */
fun ancestorKeys(key: String): Set<String> {
    val parts = key.split('/')
    return (1 until parts.size).mapTo(LinkedHashSet()) { parts.take(it).joinToString("/") }
}
