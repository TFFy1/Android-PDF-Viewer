package io.github.tffy1.pdfviewer.tools

/** Pure planning for the split tool: which pages go into which output file, and its name. */
object SplitPlanner {
    /** Consecutive chunks of [pagesPerFile] pages; the last chunk may be shorter. */
    fun everyNPages(pageCount: Int, pagesPerFile: Int): List<PageRange> {
        require(pageCount >= 1) { "pageCount must be >= 1" }
        require(pagesPerFile >= 1) { "pagesPerFile must be >= 1" }
        return (1..pageCount step pagesPerFile).map { first ->
            PageRange(first, minOf(first + pagesPerFile - 1, pageCount))
        }
    }

    /**
     * Parses the "pages per file" field. Returns null unless it is a whole number in
     * 1..[pageCount].
     */
    fun parsePagesPerFile(text: String, pageCount: Int): Int? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > 9 || !trimmed.all { it in '0'..'9' }) return null
        return trimmed.toInt().takeIf { it in 1..pageCount }
    }

    /** `report.pdf` + pages 1-3 → `report_p1-3.pdf`. */
    fun partFileName(sourceDisplayName: String, range: PageRange): String =
        "${FileNames.baseName(sourceDisplayName)}_p$range.pdf"
}

/** File-name helpers for tool outputs. */
object FileNames {
    private const val MAX_BASE_LENGTH = 80
    private const val FALLBACK = "document"
    private val ILLEGAL = Regex("""[\\/:*?"<>|\u0000-\u001F]""")

    /** Display name without its extension, made safe for use as a file name. */
    fun baseName(displayName: String): String {
        val trimmed = displayName.trim()
        val dot = trimmed.lastIndexOf('.')
        val withoutExtension = if (dot > 0 && trimmed.length - dot <= 6) trimmed.substring(0, dot) else trimmed
        return sanitize(withoutExtension)
    }

    /** `report.pdf` + `merged` → `report_merged.pdf`. */
    fun withSuffix(displayName: String, suffix: String): String = "${baseName(displayName)}_$suffix.pdf"

    fun sanitize(name: String): String {
        val cleaned = name.replace(ILLEGAL, "_").trim().trim('.').trim()
        return cleaned.take(MAX_BASE_LENGTH).trim().ifEmpty { FALLBACK }
    }
}
