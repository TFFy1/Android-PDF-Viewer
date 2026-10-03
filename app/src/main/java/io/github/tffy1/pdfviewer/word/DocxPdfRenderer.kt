package io.github.tffy1.pdfviewer.word

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import java.io.OutputStream

/**
 * Lays out a [DocxDocument] onto A4 pages with the platform text stack and writes a PDF.
 * Everything is measured in PDF points (1/72 inch).
 */
class DocxPdfRenderer(
    private val pageWidth: Int = 595,
    private val pageHeight: Int = 842,
    private val margin: Int = 56,
) {
    private val contentWidth = pageWidth - 2 * margin
    private val bottom = pageHeight - margin

    private lateinit var pdf: PdfDocument
    private var page: PdfDocument.Page? = null
    private var canvas: Canvas? = null
    private var pageNumber = 0
    private var y = 0f

    /** Writes [document] to [out]. Does not close the stream. Returns the number of pages. */
    fun render(document: DocxDocument, out: OutputStream): Int {
        pdf = PdfDocument()
        pageNumber = 0
        page = null
        try {
            newPage()
            var numberedCounters = IntArray(MAX_LIST_LEVELS)
            for (block in document.blocks) {
                when (block) {
                    is DocxParagraph -> {
                        if (block.pageBreakBefore && y > margin) newPage()
                        if (block.listLevel < 0 || !block.numbered) numberedCounters = IntArray(MAX_LIST_LEVELS)
                        drawParagraph(block, contentWidth, x = margin.toFloat(), counters = numberedCounters)
                    }
                    is DocxTable -> drawTable(block)
                }
            }
            finishPage()
            pdf.writeTo(out)
            return pageNumber
        } finally {
            page?.let { runCatching { pdf.finishPage(it) } }
            page = null
            pdf.close()
        }
    }

    private fun newPage() {
        finishPage()
        pageNumber++
        val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        page = pdf.startPage(info)
        canvas = page!!.canvas
        y = margin.toFloat()
    }

    private fun finishPage() {
        page?.let { pdf.finishPage(it) }
        page = null
        canvas = null
    }

    private fun drawParagraph(
        p: DocxParagraph,
        width: Int,
        x: Float,
        counters: IntArray,
    ) {
        val indent = if (p.listLevel >= 0) LIST_INDENT * (p.listLevel + 1) else 0
        val marker = when {
            p.listLevel < 0 -> null
            p.numbered -> "${++counters[p.listLevel.coerceAtMost(MAX_LIST_LEVELS - 1)]}."
            else -> "•"
        }
        val layout = layoutOf(p, width - indent)
        val spaceAfter = if (p.headingLevel > 0) 6f else 4f
        val spaceBefore = if (p.headingLevel > 0) 8f else 0f
        if (y + spaceBefore + layout.getLineBottom(0) > bottom && y > margin) newPage()
        y += if (y > margin) spaceBefore else 0f

        var line = 0
        while (line < layout.lineCount) {
            val lineTop = layout.getLineTop(line)
            // Take as many lines as fit on the rest of the page (at least one, to guarantee progress).
            var last = line
            while (last + 1 < layout.lineCount &&
                y + (layout.getLineBottom(last + 1) - lineTop) <= bottom
            ) last++
            if (y + (layout.getLineBottom(last) - lineTop) > bottom && y > margin) {
                newPage()
                continue
            }
            val c = canvas!!
            c.save()
            c.translate(x + indent, y - lineTop)
            c.clipRect(0f, lineTop.toFloat(), width.toFloat(), layout.getLineBottom(last).toFloat())
            layout.draw(c)
            c.restore()
            if (marker != null && line == 0) drawMarker(c, marker, x + indent - LIST_INDENT * 0.8f, y, layout)
            y += layout.getLineBottom(last) - lineTop
            line = last + 1
            if (line < layout.lineCount) newPage()
        }
        y += spaceAfter
    }

    private fun drawMarker(c: Canvas, marker: String, x: Float, top: Float, layout: StaticLayout) {
        val paint = TextPaint(BASE_PAINT).apply { color = DEFAULT_COLOR }
        c.drawText(marker, x, top + layout.getLineBaseline(0), paint)
    }

    private fun drawTable(table: DocxTable) {
        val columns = table.rows.maxOfOrNull { it.size } ?: return
        if (columns == 0) return
        val cellWidth = contentWidth / columns
        val border = Paint().apply { color = TABLE_BORDER; style = Paint.Style.STROKE; strokeWidth = 0.5f }
        for (row in table.rows) {
            val layouts = row.map { cell -> cell.map { layoutOf(it, cellWidth - 2 * CELL_PADDING) } }
            val rowHeight = (layouts.maxOfOrNull { cell -> cell.sumOf { it.height } + CELL_PADDING * 2 } ?: 0)
                .coerceAtLeast(CELL_PADDING * 2 + 12)
            // Rows are never split; a row taller than a page is clipped to one page.
            val height = rowHeight.coerceAtMost(bottom - margin)
            if (y + height > bottom && y > margin) newPage()
            val c = canvas!!
            row.forEachIndexed { index, _ ->
                val left = margin + index * cellWidth
                c.drawRect(left.toFloat(), y, (left + cellWidth).toFloat(), y + height, border)
                c.save()
                c.clipRect(left.toFloat(), y, (left + cellWidth).toFloat(), y + height)
                var cy = y + CELL_PADDING
                for (layout in layouts[index]) {
                    c.save()
                    c.translate((left + CELL_PADDING).toFloat(), cy)
                    layout.draw(c)
                    c.restore()
                    cy += layout.height
                }
                c.restore()
            }
            y += height
        }
        y += 8f
    }

    private fun layoutOf(p: DocxParagraph, width: Int): StaticLayout {
        val text = SpannableStringBuilder()
        val headingScale = HEADING_SCALE.getOrElse(p.headingLevel) { 1f }
        for (run in p.runs) {
            val start = text.length
            text.append(run.text)
            val end = text.length
            if (start == end) continue
            val bold = run.bold || p.headingLevel > 0
            val style = when {
                bold && run.italic -> Typeface.BOLD_ITALIC
                bold -> Typeface.BOLD
                run.italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            if (style != Typeface.NORMAL) text.setSpan(StyleSpan(style), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (run.underline) text.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (run.strike) text.setSpan(StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val size = (run.sizePt ?: BASE_SIZE) * headingScale
            if (size != BASE_SIZE) {
                text.setSpan(AbsoluteSizeSpan(size.toInt().coerceIn(MIN_SIZE, MAX_SIZE), false), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            run.color?.let {
                text.setSpan(ForegroundColorSpan(0xFF000000.toInt() or it), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        // Empty paragraphs still occupy a line, as in Word.
        if (text.isEmpty()) text.append(' ')
        val alignment = when (p.align) {
            DocxAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
            DocxAlign.END -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, TextPaint(BASE_PAINT), width.coerceAtLeast(1))
            .setAlignment(alignment)
            .setLineSpacing(0f, 1.15f)
            .setIncludePad(false)
        // Justified text needs the newer line breaker constants; older devices fall back to left-aligned.
        if (p.align == DocxAlign.JUSTIFY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setJustificationMode(LineBreaker.JUSTIFICATION_MODE_INTER_WORD)
        }
        return builder.build()
    }

    private companion object {
        const val BASE_SIZE = 11f
        const val MIN_SIZE = 4
        const val MAX_SIZE = 96
        const val LIST_INDENT = 18
        const val MAX_LIST_LEVELS = 9
        const val CELL_PADDING = 4
        const val DEFAULT_COLOR = 0xFF000000.toInt()
        const val TABLE_BORDER = 0xFF808080.toInt()
        val HEADING_SCALE = floatArrayOf(1f, 1.8f, 1.5f, 1.3f, 1.15f, 1.05f, 1f)
        val BASE_PAINT = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = BASE_SIZE
            color = DEFAULT_COLOR
            typeface = Typeface.SERIF
        }
    }
}
