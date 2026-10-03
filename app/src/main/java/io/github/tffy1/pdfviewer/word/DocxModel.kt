package io.github.tffy1.pdfviewer.word

/** Minimal, render-oriented model of a Word document. Pure Kotlin so the parser is unit-testable. */
data class DocxDocument(val blocks: List<DocxBlock>)

sealed interface DocxBlock

enum class DocxAlign { START, CENTER, END, JUSTIFY }

data class DocxRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    /** Font size in points, or null for the document default. */
    val sizePt: Float? = null,
    /** RGB without alpha, or null for the default text colour. */
    val color: Int? = null,
)

data class DocxParagraph(
    val runs: List<DocxRun>,
    /** 1..6 for headings, 0 for body text. */
    val headingLevel: Int = 0,
    val align: DocxAlign = DocxAlign.START,
    /** Nesting level of a list item, or -1 when the paragraph is not a list item. */
    val listLevel: Int = -1,
    val numbered: Boolean = false,
    val pageBreakBefore: Boolean = false,
) : DocxBlock {
    val isBlank: Boolean get() = runs.all { it.text.isBlank() }
}

data class DocxTable(val rows: List<List<List<DocxParagraph>>>) : DocxBlock
