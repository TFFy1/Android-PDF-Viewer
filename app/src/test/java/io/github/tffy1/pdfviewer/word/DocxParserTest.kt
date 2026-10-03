package io.github.tffy1.pdfviewer.word

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocxParserTest {
    private fun docx(body: String, styles: String? = null): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(
                """<?xml version="1.0"?><w:document xmlns:w="w"><w:body>$body</w:body></w:document>""".toByteArray(),
            )
            zip.closeEntry()
            if (styles != null) {
                zip.putNextEntry(ZipEntry("word/styles.xml"))
                zip.write("""<w:styles xmlns:w="w">$styles</w:styles>""".toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun parse(body: String, styles: String? = null) =
        DocxParser.parse(ByteArrayInputStream(docx(body, styles)))

    @Test
    fun `plain and formatted runs`() {
        val doc = parse(
            """<w:p><w:r><w:t>Hello </w:t></w:r><w:r><w:rPr><w:b/><w:i/><w:sz w:val="28"/></w:rPr><w:t>world</w:t></w:r></w:p>""",
        )
        val p = doc.blocks.single() as DocxParagraph
        assertEquals(listOf("Hello ", "world"), p.runs.map { it.text })
        assertFalse(p.runs[0].bold)
        assertTrue(p.runs[1].bold && p.runs[1].italic)
        assertEquals(14f, p.runs[1].sizePt)
    }

    @Test
    fun `bold switched off explicitly`() {
        val p = parse("""<w:p><w:r><w:rPr><w:b w:val="0"/></w:rPr><w:t>x</w:t></w:r></w:p>""").blocks.single() as DocxParagraph
        assertFalse(p.runs.single().bold)
    }

    @Test
    fun `headings come from style names`() {
        val styles = """<w:style w:styleId="Kop1"><w:name w:val="heading 1"/></w:style>"""
        val doc = parse(
            """<w:p><w:pPr><w:pStyle w:val="Kop1"/></w:pPr><w:r><w:t>Title</w:t></w:r></w:p>
               <w:p><w:pPr><w:pStyle w:val="Heading2"/></w:pPr><w:r><w:t>Sub</w:t></w:r></w:p>""",
            styles,
        )
        assertEquals(listOf(1, 2), doc.blocks.map { (it as DocxParagraph).headingLevel })
    }

    @Test
    fun `lists tabs breaks and alignment`() {
        val doc = parse(
            """<w:p><w:pPr><w:numPr><w:ilvl w:val="1"/><w:numId w:val="3"/></w:numPr><w:jc w:val="center"/></w:pPr>
               <w:r><w:t>a</w:t><w:tab/><w:t>b</w:t><w:br/><w:t>c</w:t></w:r></w:p>""",
        )
        val p = doc.blocks.single() as DocxParagraph
        assertEquals(1, p.listLevel)
        assertEquals(DocxAlign.CENTER, p.align)
        assertEquals("a\tb\nc", p.runs.joinToString("") { it.text })
    }

    @Test
    fun `manual page break splits the paragraph`() {
        val doc = parse("""<w:p><w:r><w:t>one</w:t><w:br w:type="page"/><w:t>two</w:t></w:r></w:p>""")
        val paragraphs = doc.blocks.map { it as DocxParagraph }
        assertEquals(listOf("one", "two"), paragraphs.map { it.runs.joinToString("") { r -> r.text } })
        assertEquals(listOf(false, true), paragraphs.map { it.pageBreakBefore })
    }

    @Test
    fun `tables keep rows and cells`() {
        val doc = parse(
            """<w:tbl><w:tr><w:tc><w:p><w:r><w:t>A</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>B</w:t></w:r></w:p></w:tc></w:tr>
               <w:tr><w:tc><w:p><w:r><w:t>C</w:t></w:r></w:p></w:tc><w:tc><w:p/></w:tc></w:tr></w:tbl>
               <w:p><w:r><w:t>after</w:t></w:r></w:p>""",
        )
        val table = doc.blocks[0] as DocxTable
        assertEquals(2, table.rows.size)
        assertEquals("A", table.rows[0][0].single().runs.single().text)
        assertEquals(2, table.rows[1].size)
        assertEquals("after", ((doc.blocks[1]) as DocxParagraph).runs.single().text)
    }

    @Test(expected = InvalidDocxException::class)
    fun `not a zip is rejected`() {
        DocxParser.parse(ByteArrayInputStream("hello".toByteArray()))
    }

    @Test(expected = InvalidDocxException::class)
    fun `zip without document part is rejected`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { it.putNextEntry(ZipEntry("other.txt")); it.write(1); it.closeEntry() }
        DocxParser.parse(ByteArrayInputStream(out.toByteArray()))
    }

    @Test(expected = InvalidDocxException::class)
    fun `doctype declarations are refused`() {
        val xml = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><w:document xmlns:w="w"/>"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { it.putNextEntry(ZipEntry("word/document.xml")); it.write(xml.toByteArray()); it.closeEntry() }
        DocxParser.parse(ByteArrayInputStream(out.toByteArray()))
    }
}
