package io.github.tffy1.pdfviewer.annotations

import io.github.tffy1.pdfviewer.core.model.PagePoint
import io.github.tffy1.pdfviewer.core.model.PageRect
import io.github.tffy1.pdfviewer.data.db.AnnotationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationCodecTest {

    @Test
    fun markupRoundTrip() {
        val content = AnnotationContent.Markup(
            listOf(PageRect(10f, 20f, 110.5f, 32.25f), PageRect(10f, 34f, 60f, 46f)),
        )
        for (type in listOf(AnnotationType.HIGHLIGHT, AnnotationType.UNDERLINE, AnnotationType.STRIKEOUT)) {
            assertEquals(content, AnnotationCodec.decode(type, AnnotationCodec.encode(content)))
        }
    }

    @Test
    fun inkRoundTrip() {
        val content = AnnotationContent.Ink(
            listOf(
                InkStroke(listOf(PagePoint(1f, 2f), PagePoint(3.5f, 4.25f), PagePoint(10f, 12f)), width = 3f),
                InkStroke(listOf(PagePoint(0f, 0f), PagePoint(100f, 200f)), width = 1.5f),
            ),
        )
        assertEquals(content, AnnotationCodec.decode(AnnotationType.INK, AnnotationCodec.encode(content)))
    }

    @Test
    fun noteRoundTrip() {
        val content = AnnotationContent.Note(PagePoint(300.5f, 420.75f))
        assertEquals(content, AnnotationCodec.decode(AnnotationType.NOTE, AnnotationCodec.encode(content)))
    }

    @Test
    fun payloadIsVersionedAndCompact() {
        val json = AnnotationCodec.encode(AnnotationContent.Note(PagePoint(1.23456f, 2f)))
        assertEquals("""{"v":1,"x":1.23,"y":2.0}""", json)
        val ink = AnnotationCodec.encode(
            AnnotationContent.Ink(listOf(InkStroke(listOf(PagePoint(1f, 2f), PagePoint(3f, 4f)), 2f))),
        )
        assertEquals("""{"v":1,"strokes":[{"w":2.0,"p":[1.0,2.0,3.0,4.0]}]}""", ink)
    }

    @Test
    fun valuesAreRoundedToHundredths() {
        val encoded = AnnotationCodec.encode(AnnotationContent.Markup(listOf(PageRect(1.004f, 2.006f, 3.3333f, 4.9999f))))
        val decoded = AnnotationCodec.decode(AnnotationType.HIGHLIGHT, encoded) as AnnotationContent.Markup
        assertEquals(PageRect(1f, 2.01f, 3.33f, 5f), decoded.rects.single())
    }

    @Test
    fun corruptPayloadsDecodeToNull() {
        val corrupt = listOf(
            "",
            "not json",
            "{",
            "null",
            "[]",
            """{"v":1}""",
            """{"v":1,"rects":"oops"}""",
            """{"v":1,"rects":[[1,2,3]]}""",
            """{"v":1,"rects":[]}""",
        )
        for (payload in corrupt) {
            assertNull(payload, AnnotationCodec.decode(AnnotationType.HIGHLIGHT, payload))
        }
        assertNull(AnnotationCodec.decode(AnnotationType.INK, """{"v":1,"strokes":[{"w":2,"p":[1,2]}]}"""))
        assertNull(AnnotationCodec.decode(AnnotationType.INK, """{"v":1,"strokes":[{"w":2,"p":[1,2,3]}]}"""))
        assertNull(AnnotationCodec.decode(AnnotationType.INK, """{"v":1,"strokes":[{"w":0,"p":[1,2,3,4]}]}"""))
        assertNull(AnnotationCodec.decode(AnnotationType.NOTE, """{"v":1,"x":1}"""))
        assertNull(AnnotationCodec.decode(AnnotationType.NOTE, """{"v":1,"x":1e39,"y":2}"""))
    }

    @Test
    fun invalidEntriesAreDroppedButValidOnesKept() {
        val decoded = AnnotationCodec.decode(
            AnnotationType.INK,
            """{"v":1,"strokes":[{"w":2,"p":[1]},{"w":2,"p":[1,2,3,4]}]}""",
        ) as AnnotationContent.Ink
        assertEquals(1, decoded.strokes.size)

        val markup = AnnotationCodec.decode(
            AnnotationType.UNDERLINE,
            """{"v":1,"rects":[[1,2],[30,40,10,20]]}""",
        ) as AnnotationContent.Markup
        // Swapped corners are normalized.
        assertEquals(listOf(PageRect(10f, 20f, 30f, 40f)), markup.rects)
    }

    @Test
    fun typeMismatchDecodesToNull() {
        val ink = AnnotationCodec.encode(
            AnnotationContent.Ink(listOf(InkStroke(listOf(PagePoint(1f, 2f), PagePoint(3f, 4f)), 2f))),
        )
        assertNull(AnnotationCodec.decode(AnnotationType.HIGHLIGHT, ink))
        assertNull(AnnotationCodec.decode(AnnotationType.NOTE, ink))
    }

    @Test
    fun futureVersionsAreRejectedAndUnknownKeysIgnored() {
        assertNull(AnnotationCodec.decode(AnnotationType.NOTE, """{"v":2,"x":1,"y":2}"""))
        assertNull(AnnotationCodec.decode(AnnotationType.NOTE, """{"v":0,"x":1,"y":2}"""))
        assertEquals(
            AnnotationContent.Note(PagePoint(1f, 2f)),
            AnnotationCodec.decode(AnnotationType.NOTE, """{"v":1,"x":1,"y":2,"extra":true}"""),
        )
        // A missing version means version 1.
        assertNotNull(AnnotationCodec.decode(AnnotationType.NOTE, """{"x":1,"y":2}"""))
    }

    @Test
    fun entityRoundTrip() {
        val annotation = PageAnnotation(
            id = 42,
            pageIndex = 3,
            type = AnnotationType.NOTE,
            color = AnnotationColors.YELLOW,
            content = AnnotationContent.Note(PagePoint(10f, 20f)),
            note = "Remember this",
            createdAt = 1_000L,
            updatedAt = 2_000L,
        )
        val entity = AnnotationCodec.toEntity(annotation, "content://doc")
        assertEquals("NOTE", entity.type)
        assertEquals("content://doc", entity.documentUri)
        assertEquals(annotation, AnnotationCodec.fromEntity(entity))
    }

    @Test
    fun temporaryIdsBecomeZeroForInsert() {
        val annotation = PageAnnotation(
            id = -5,
            pageIndex = 0,
            type = AnnotationType.NOTE,
            color = 0,
            content = AnnotationContent.Note(PagePoint(1f, 1f)),
        )
        assertEquals(0L, AnnotationCodec.toEntity(annotation, "x").id)
    }

    @Test
    fun corruptRowsAreSkipped() {
        fun entity(type: String, payload: String, page: Int = 0) =
            AnnotationEntity(documentUri = "d", pageIndex = page, type = type, color = 0, payload = payload)
        assertNull(AnnotationCodec.fromEntity(entity("SQUIGGLE", """{"v":1,"x":1,"y":2}""")))
        assertNull(AnnotationCodec.fromEntity(entity("NOTE", "garbage")))
        assertNull(AnnotationCodec.fromEntity(entity("NOTE", """{"v":1,"x":1,"y":2}""", page = -1)))
        assertTrue(AnnotationCodec.fromEntity(entity("NOTE", """{"v":1,"x":1,"y":2}""")) != null)
    }

    @Test
    fun nonFiniteValuesAreNotEncoded() {
        val encoded = AnnotationCodec.encode(
            AnnotationContent.Ink(
                listOf(InkStroke(listOf(PagePoint(1f, 2f), PagePoint(Float.NaN, 3f), PagePoint(5f, 6f)), 2f)),
            ),
        )
        val decoded = AnnotationCodec.decode(AnnotationType.INK, encoded) as AnnotationContent.Ink
        assertEquals(listOf(PagePoint(1f, 2f), PagePoint(5f, 6f)), decoded.strokes.single().points)
    }
}
