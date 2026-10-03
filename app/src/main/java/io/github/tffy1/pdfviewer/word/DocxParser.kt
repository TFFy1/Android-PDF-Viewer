package io.github.tffy1.pdfviewer.word

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

class InvalidDocxException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Reads the text, basic formatting, lists and tables out of a .docx (Office Open XML) package.
 * Images, headers/footers, footnotes and other advanced features are intentionally skipped.
 */
object DocxParser {
    private const val MAIN_PART = "word/document.xml"
    private const val STYLES_PART = "word/styles.xml"

    /** Guards against zip bombs: the XML parts we read are never legitimately this large. */
    private const val MAX_PART_BYTES = 64L * 1024 * 1024

    @Throws(IOException::class)
    fun parse(input: InputStream): DocxDocument {
        var main: ByteArray? = null
        var styles: ByteArray? = null
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when (entry.name) {
                        MAIN_PART -> main = zip.readBounded()
                        STYLES_PART -> styles = zip.readBounded()
                    }
                }
            }
        } catch (e: java.util.zip.ZipException) {
            throw InvalidDocxException("Not a valid .docx file", e)
        }
        val mainBytes = main ?: throw InvalidDocxException("Missing $MAIN_PART")
        val headingStyles = styles?.let { runCatching { parseHeadingStyles(it) }.getOrNull() }.orEmpty()
        return try {
            parseDocument(mainBytes, headingStyles)
        } catch (e: org.xml.sax.SAXException) {
            throw InvalidDocxException("Corrupt document body", e)
        }
    }

    private fun ZipInputStream.readBounded(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_PART_BYTES) throw InvalidDocxException("Document is too large")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun newParser() = SAXParserFactory.newInstance().apply {
        isNamespaceAware = false
        // Never resolve external entities from untrusted documents.
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    }.newSAXParser()

    /** styleId -> heading level, for styles named "heading N" / "Title". */
    internal fun parseHeadingStyles(bytes: ByteArray): Map<String, Int> {
        val result = HashMap<String, Int>()
        newParser().parse(InputSource(bytes.inputStream()), object : DefaultHandler() {
            var currentId: String? = null
            override fun startElement(uri: String?, local: String?, qName: String, a: Attributes) {
                when (qName) {
                    "w:style" -> currentId = a.getValue("w:styleId")
                    "w:name" -> {
                        val id = currentId ?: return
                        val name = a.getValue("w:val")?.lowercase().orEmpty()
                        val level = when {
                            name == "title" -> 1
                            name.startsWith("heading ") -> name.removePrefix("heading ").trim().toIntOrNull()
                            else -> null
                        }
                        if (level != null) result[id] = level.coerceIn(1, 6)
                    }
                }
            }
            override fun endElement(uri: String?, local: String?, qName: String) {
                if (qName == "w:style") currentId = null
            }
        })
        return result
    }

    internal fun parseDocument(bytes: ByteArray, headingStyles: Map<String, Int>): DocxDocument {
        val handler = BodyHandler(headingStyles)
        newParser().parse(InputSource(bytes.inputStream()), handler)
        return DocxDocument(handler.blocks)
    }

    private class BodyHandler(private val headingStyles: Map<String, Int>) : DefaultHandler() {
        val blocks = ArrayList<DocxBlock>()

        // Table state; tables can nest, only the outermost structure is kept (inner text is flattened).
        private var tableDepth = 0
        private var rows = ArrayList<List<List<DocxParagraph>>>()
        private var cells = ArrayList<List<DocxParagraph>>()
        private var cellParagraphs = ArrayList<DocxParagraph>()

        // Paragraph state
        private var runs = ArrayList<DocxRun>()
        private var heading = 0
        private var align = DocxAlign.START
        private var listLevel = -1
        private var numbered = false
        private var pageBreakBefore = false
        private var inParagraph = false

        // Run state
        private var text = StringBuilder()
        private var bold = false
        private var italic = false
        private var underline = false
        private var strike = false
        private var size: Float? = null
        private var color: Int? = null
        private var inRun = false
        private var inText = false
        private var inRunProps = false
        private var inParaProps = false
        private var inNumPr = false

        override fun startElement(uri: String?, local: String?, qName: String, a: Attributes) {
            when (qName) {
                "w:tbl" -> {
                    if (tableDepth == 0) rows = ArrayList()
                    tableDepth++
                }
                "w:tr" -> if (tableDepth == 1) cells = ArrayList()
                "w:tc" -> if (tableDepth == 1) cellParagraphs = ArrayList()
                "w:p" -> {
                    inParagraph = true
                    runs = ArrayList()
                    heading = 0
                    align = DocxAlign.START
                    listLevel = -1
                    numbered = false
                    pageBreakBefore = false
                }
                "w:pPr" -> inParaProps = true
                "w:rPr" -> inRunProps = true
                "w:numPr" -> if (inParaProps) {
                    inNumPr = true
                    if (listLevel < 0) listLevel = 0
                }
                "w:ilvl" -> if (inNumPr) listLevel = a.getValue("w:val")?.toIntOrNull()?.coerceIn(0, 8) ?: 0
                "w:numId" -> if (inNumPr && a.getValue("w:val") == "0") listLevel = -1
                "w:pStyle" -> if (inParaProps) {
                    val id = a.getValue("w:val").orEmpty()
                    heading = headingStyles[id] ?: legacyHeadingLevel(id)
                    if (id.startsWith("ListBullet") || id.startsWith("ListParagraph")) {
                        if (listLevel < 0) listLevel = 0
                    }
                    if (id.startsWith("ListNumber")) {
                        if (listLevel < 0) listLevel = 0
                        numbered = true
                    }
                }
                "w:jc" -> if (inParaProps) align = when (a.getValue("w:val")) {
                    "center" -> DocxAlign.CENTER
                    "right", "end" -> DocxAlign.END
                    "both", "distribute" -> DocxAlign.JUSTIFY
                    else -> DocxAlign.START
                }
                "w:pageBreakBefore" -> if (inParaProps) pageBreakBefore = a.isOn()
                "w:r" -> {
                    inRun = true
                    text = StringBuilder()
                    bold = false; italic = false; underline = false; strike = false
                    size = null; color = null
                }
                "w:b" -> if (inRunProps) bold = a.isOn()
                "w:i" -> if (inRunProps) italic = a.isOn()
                "w:strike" -> if (inRunProps) strike = a.isOn()
                "w:u" -> if (inRunProps) underline = a.getValue("w:val").let { it != null && it != "none" }
                "w:sz" -> if (inRunProps) size = a.getValue("w:val")?.toFloatOrNull()?.div(2f)
                "w:color" -> if (inRunProps) color = a.getValue("w:val")?.takeIf { it.length == 6 }
                    ?.toIntOrNull(16)
                "w:t" -> inText = true
                "w:tab" -> if (inRun && !inRunProps) text.append('\t')
                "w:br", "w:cr" -> if (inRun) {
                    if (a.getValue("w:type") == "page") {
                        flushRun()
                        runs.add(PAGE_BREAK_RUN)
                    } else {
                        text.append('\n')
                    }
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inText) text.append(ch, start, length)
        }

        override fun endElement(uri: String?, local: String?, qName: String) {
            when (qName) {
                "w:t" -> inText = false
                "w:rPr" -> inRunProps = false
                "w:pPr" -> inParaProps = false
                "w:numPr" -> inNumPr = false
                "w:r" -> {
                    flushRun()
                    inRun = false
                }
                "w:p" -> endParagraph()
                "w:tc" -> if (tableDepth == 1) {
                    cells.add(cellParagraphs.ifEmpty { listOf(DocxParagraph(emptyList())) })
                }
                "w:tr" -> if (tableDepth == 1) rows.add(cells)
                "w:tbl" -> {
                    tableDepth--
                    if (tableDepth == 0 && rows.isNotEmpty()) blocks.add(DocxTable(rows))
                }
            }
        }

        private fun flushRun() {
            if (text.isNotEmpty()) {
                runs.add(DocxRun(text.toString(), bold, italic, underline, strike, size, color))
                text = StringBuilder()
            }
        }

        private fun endParagraph() {
            inParagraph = false
            val breakIndex = runs.indexOf(PAGE_BREAK_RUN)
            if (breakIndex < 0) {
                emit(DocxParagraph(runs.toList(), heading, align, listLevel, numbered, pageBreakBefore))
                return
            }
            // A manual page break splits the paragraph in two, the tail starting a new page.
            val head = runs.subList(0, breakIndex).toList()
            val tail = runs.subList(breakIndex + 1, runs.size).filter { it != PAGE_BREAK_RUN }
            if (head.any { it.text.isNotBlank() }) emit(DocxParagraph(head, heading, align, listLevel, numbered, pageBreakBefore))
            emit(DocxParagraph(tail, heading, align, listLevel, numbered, pageBreakBefore = true))
        }

        private fun emit(paragraph: DocxParagraph) {
            when {
                tableDepth == 0 -> blocks.add(paragraph)
                tableDepth == 1 -> cellParagraphs.add(paragraph)
                // Nested table: fold its text into the enclosing cell.
                else -> if (!paragraph.isBlank) cellParagraphs.add(paragraph)
            }
        }

        private fun Attributes.isOn(): Boolean = getValue("w:val").let { it == null || it !in OFF_VALUES }

        private fun legacyHeadingLevel(styleId: String): Int =
            if (styleId.startsWith("Heading", ignoreCase = true)) {
                styleId.drop("Heading".length).toIntOrNull()?.coerceIn(1, 6) ?: 0
            } else if (styleId.equals("Title", ignoreCase = true)) 1 else 0
    }

    private val OFF_VALUES = setOf("0", "false", "off", "none")
    private val PAGE_BREAK_RUN = DocxRun("\u000C")
}
