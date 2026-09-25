package io.github.tffy1.pdfviewer.annotations

import io.github.tffy1.pdfviewer.core.model.PagePoint
import io.github.tffy1.pdfviewer.core.model.PageRect
import io.github.tffy1.pdfviewer.data.db.AnnotationEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/*
 * Stored payload formats (all numbers in page-space points, rounded to 0.01):
 *   markup: {"v":1,"rects":[[left,top,right,bottom],...]}
 *   ink:    {"v":1,"strokes":[{"w":width,"p":[x0,y0,x1,y1,...]},...]}
 *   note:   {"v":1,"x":x,"y":y}
 * Unknown keys are ignored so newer minor additions stay readable; a newer "v" is rejected.
 */

@Serializable
internal data class MarkupPayload(
    @SerialName("v") val version: Int = AnnotationCodec.PAYLOAD_VERSION,
    val rects: List<List<Float>>,
)

@Serializable
internal data class InkStrokePayload(
    @SerialName("w") val width: Float,
    @SerialName("p") val points: List<Float>,
)

@Serializable
internal data class InkPayload(
    @SerialName("v") val version: Int = AnnotationCodec.PAYLOAD_VERSION,
    val strokes: List<InkStrokePayload>,
)

@Serializable
internal data class NotePayload(
    @SerialName("v") val version: Int = AnnotationCodec.PAYLOAD_VERSION,
    val x: Float,
    val y: Float,
)

/**
 * Converts annotations to and from database rows. Decoding never throws: corrupt, unknown
 * or future-version rows decode to null so callers can skip them.
 */
object AnnotationCodec {
    const val PAYLOAD_VERSION = 1

    /** Largest stroke width accepted when decoding, in points. */
    private const val MAX_STROKE_WIDTH = 100f

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(content: AnnotationContent): String = when (content) {
        is AnnotationContent.Markup -> json.encodeToString(
            MarkupPayload.serializer(),
            MarkupPayload(
                rects = content.rects.filter { it.isFinite() }.map {
                    listOf(round2(it.left), round2(it.top), round2(it.right), round2(it.bottom))
                },
            ),
        )
        is AnnotationContent.Ink -> json.encodeToString(
            InkPayload.serializer(),
            InkPayload(
                strokes = content.strokes.filter { it.width.isFinite() }.map { stroke ->
                    InkStrokePayload(
                        width = round2(stroke.width),
                        points = stroke.points
                            .filter { it.x.isFinite() && it.y.isFinite() }
                            .flatMap { listOf(round2(it.x), round2(it.y)) },
                    )
                },
            ),
        )
        is AnnotationContent.Note -> json.encodeToString(
            NotePayload.serializer(),
            NotePayload(
                x = round2(content.anchor.x.finiteOrZero()),
                y = round2(content.anchor.y.finiteOrZero()),
            ),
        )
    }

    /** Decodes [payload] for [type]; returns null if it is corrupt or doesn't match the type. */
    fun decode(type: AnnotationType, payload: String): AnnotationContent? = try {
        when (type) {
            AnnotationType.HIGHLIGHT, AnnotationType.UNDERLINE, AnnotationType.STRIKEOUT ->
                decodeMarkup(json.decodeFromString(MarkupPayload.serializer(), payload))
            AnnotationType.INK -> decodeInk(json.decodeFromString(InkPayload.serializer(), payload))
            AnnotationType.NOTE -> decodeNote(json.decodeFromString(NotePayload.serializer(), payload))
        }
    } catch (e: Exception) {
        // Malformed JSON, missing fields, wrong shapes: the row is skipped, never a crash.
        null
    }

    private fun decodeMarkup(payload: MarkupPayload): AnnotationContent.Markup? {
        if (!isSupported(payload.version)) return null
        val rects = payload.rects.mapNotNull { values ->
            if (values.size != 4 || values.any { !it.isFinite() }) return@mapNotNull null
            PageRect(
                min(values[0], values[2]),
                min(values[1], values[3]),
                max(values[0], values[2]),
                max(values[1], values[3]),
            )
        }
        return if (rects.isEmpty()) null else AnnotationContent.Markup(rects)
    }

    private fun decodeInk(payload: InkPayload): AnnotationContent.Ink? {
        if (!isSupported(payload.version)) return null
        val strokes = payload.strokes.mapNotNull { stroke ->
            val values = stroke.points
            if (!stroke.width.isFinite() || stroke.width <= 0f) return@mapNotNull null
            if (values.size < 4 || values.size % 2 != 0 || values.any { !it.isFinite() }) return@mapNotNull null
            InkStroke(
                points = List(values.size / 2) { i -> PagePoint(values[2 * i], values[2 * i + 1]) },
                width = min(stroke.width, MAX_STROKE_WIDTH),
            )
        }
        return if (strokes.isEmpty()) null else AnnotationContent.Ink(strokes)
    }

    private fun decodeNote(payload: NotePayload): AnnotationContent.Note? {
        if (!isSupported(payload.version)) return null
        if (!payload.x.isFinite() || !payload.y.isFinite()) return null
        return AnnotationContent.Note(PagePoint(payload.x, payload.y))
    }

    private fun isSupported(version: Int) = version in 1..PAYLOAD_VERSION

    /** Decodes a database row, or returns null for rows that can't be understood. */
    fun fromEntity(entity: AnnotationEntity): PageAnnotation? {
        val type = AnnotationType.fromStoredName(entity.type) ?: return null
        if (entity.pageIndex < 0) return null
        val content = decode(type, entity.payload) ?: return null
        return PageAnnotation(
            id = entity.id,
            pageIndex = entity.pageIndex,
            type = type,
            color = entity.color,
            content = content,
            note = entity.note,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
        )
    }

    /** Builds a database row. Negative (temporary) ids become 0 so Room generates one. */
    fun toEntity(annotation: PageAnnotation, documentUri: String): AnnotationEntity = AnnotationEntity(
        id = annotation.id.coerceAtLeast(0L),
        documentUri = documentUri,
        pageIndex = annotation.pageIndex,
        type = annotation.type.name,
        color = annotation.color,
        payload = encode(annotation.content),
        note = annotation.note,
        createdAt = annotation.createdAt,
        updatedAt = annotation.updatedAt,
    )

    /** Rounds to 0.01 pt: far below what anyone can see, and keeps payloads short. */
    internal fun round2(value: Float): Float = (round(value * 100.0) / 100.0).toFloat()

    private fun Float.finiteOrZero(): Float = if (isFinite()) this else 0f

    private fun PageRect.isFinite(): Boolean =
        left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()
}
