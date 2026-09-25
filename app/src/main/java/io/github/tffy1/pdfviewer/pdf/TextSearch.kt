package io.github.tffy1.pdfviewer.pdf

/*
 * Pure text matching used by PdfDocument.searchPage (unit tested, no Android types).
 */

/**
 * Finds non-overlapping occurrences of [query] in [text], in text order.
 *
 * - Case-insensitive unless [matchCase] (simple per-char folding, so indexes stay aligned).
 * - Any run of whitespace in the query matches any run of whitespace in the text, including
 *   line breaks, so "hello world" also finds "hello\r\nworld". Leading/trailing query
 *   whitespace is ignored.
 * - With [wholeWord], a match must not continue a word on either side (only checked at query
 *   ends that are word characters).
 *
 * Returns ranges of char indexes into [text] (inclusive), which may span whitespace runs.
 */
fun findTextMatches(
    text: String,
    query: String,
    matchCase: Boolean = false,
    wholeWord: Boolean = false,
): List<IntRange> {
    val needle = normalizeForSearch(query.trim(), matchCase, indexMap = null)
    if (needle.isEmpty() || text.isEmpty()) return emptyList()

    val indexMap = IntArray(text.length)
    val haystack = normalizeForSearch(text, matchCase, indexMap)
    val checkStart = wholeWord && isWordChar(needle.first())
    val checkEnd = wholeWord && isWordChar(needle.last())

    val matches = ArrayList<IntRange>()
    var from = 0
    while (from <= haystack.length - needle.length) {
        val found = haystack.indexOf(needle, from)
        if (found < 0) break
        val start = indexMap[found]
        val endExclusive = indexMap[found + needle.length - 1] + 1
        val boundaryOk = (!checkStart || start == 0 || !isWordChar(text[start - 1])) &&
            (!checkEnd || endExclusive == text.length || !isWordChar(text[endExclusive]))
        if (boundaryOk) {
            matches += start until endExclusive
            from = found + needle.length
        } else {
            from = found + 1
        }
    }
    return matches
}

/** Word characters for whole-word matching: letters, digits and combining marks. */
fun isWordChar(c: Char): Boolean =
    c.isLetterOrDigit() || c == '_' ||
        c.category == CharCategory.NON_SPACING_MARK ||
        c.category == CharCategory.COMBINING_SPACING_MARK

/** Case folding that keeps a 1:1 char mapping (unlike String.lowercase()). */
private fun foldCase(c: Char): Char = c.uppercaseChar().lowercaseChar()

/**
 * Collapses whitespace runs into a single space and optionally folds case. When [indexMap]
 * is given, `indexMap[i]` receives the index in [source] of normalized char `i`.
 */
private fun normalizeForSearch(source: String, matchCase: Boolean, indexMap: IntArray?): String {
    val out = StringBuilder(source.length)
    var inWhitespace = false
    for (i in source.indices) {
        val c = source[i]
        if (c.isWhitespace()) {
            if (inWhitespace) continue
            inWhitespace = true
            indexMap?.set(out.length, i)
            out.append(' ')
        } else {
            inWhitespace = false
            indexMap?.set(out.length, i)
            out.append(if (matchCase) c else foldCase(c))
        }
    }
    return out.toString()
}
