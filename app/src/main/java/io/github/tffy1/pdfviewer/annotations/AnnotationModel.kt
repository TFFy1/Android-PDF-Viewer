package io.github.tffy1.pdfviewer.annotations

import io.github.tffy1.pdfviewer.core.model.PagePoint
import io.github.tffy1.pdfviewer.core.model.PageRect

/**
 * Kinds of annotations the app can create. The enum name is what is stored in
 * `AnnotationEntity.type`, so entries must never be renamed.
 */
enum class AnnotationType {
    HIGHLIGHT,
    UNDERLINE,
    STRIKEOUT,
    INK,
    NOTE,
    ;

    val isTextMarkup: Boolean
        get() = this == HIGHLIGHT || this == UNDERLINE || this == STRIKEOUT

    companion object {
        /** Returns the type stored under [name], or null for unknown/corrupt values. */
        fun fromStoredName(name: String): AnnotationType? = entries.firstOrNull { it.name == name }
    }
}

/** One freehand stroke. [width] is the line width in points. */
data class InkStroke(val points: List<PagePoint>, val width: Float)

/** Geometry of an annotation, in page space (points, top-left origin, rotation applied). */
sealed interface AnnotationContent {
    /** Highlight/underline/strikeout: one rect per text line. */
    data class Markup(val rects: List<PageRect>) : AnnotationContent

    data class Ink(val strokes: List<InkStroke>) : AnnotationContent

    /** Sticky note; [anchor] is the center of the note icon. */
    data class Note(val anchor: PagePoint) : AnnotationContent
}

/**
 * A decoded annotation of one page.
 *
 * [id] is the database row id, or a negative temporary id while the row is being inserted.
 * [color] is ARGB. [note] is the free text of a sticky note (unused by other types).
 */
data class PageAnnotation(
    val id: Long,
    val pageIndex: Int,
    val type: AnnotationType,
    val color: Int,
    val content: AnnotationContent,
    val note: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = createdAt,
) {
    /** Bounding box of the geometry (for ink: including half the stroke width). */
    val bounds: PageRect by lazy(LazyThreadSafetyMode.PUBLICATION) { AnnotationGeometry.bounds(content) }
}

/** Ink stroke widths offered in the UI, in points. */
enum class StrokeWidth(val points: Float) {
    THIN(1.5f),
    MEDIUM(3f),
    THICK(6f),
}

/** Default colors (ARGB) and the small palettes the user can choose from. */
object AnnotationColors {
    const val YELLOW = 0xFFFFEB3B.toInt()
    const val GREEN = 0xFF9CCC65.toInt()
    const val CYAN = 0xFF4DD0E1.toInt()
    const val PINK = 0xFFF48FB1.toInt()
    const val ORANGE = 0xFFFFB74D.toInt()
    const val LAVENDER = 0xFFCE93D8.toInt()

    const val BLACK = 0xFF212121.toInt()
    const val RED = 0xFFE53935.toInt()
    const val BLUE = 0xFF1E88E5.toInt()
    const val DARK_GREEN = 0xFF43A047.toInt()
    const val DEEP_ORANGE = 0xFFF4511E.toInt()
    const val PURPLE = 0xFF8E24AA.toInt()

    /** Light colors that work with multiply blending (highlights, note icons). */
    val lightPalette: List<Int> = listOf(YELLOW, GREEN, CYAN, PINK, ORANGE, LAVENDER)

    /** Saturated colors for lines (ink, underline, strikeout). */
    val strongPalette: List<Int> = listOf(BLACK, RED, BLUE, DARK_GREEN, DEEP_ORANGE, PURPLE)

    fun paletteFor(type: AnnotationType): List<Int> = when (type) {
        AnnotationType.HIGHLIGHT, AnnotationType.NOTE -> lightPalette
        AnnotationType.UNDERLINE, AnnotationType.STRIKEOUT, AnnotationType.INK -> strongPalette
    }

    fun defaultFor(type: AnnotationType): Int = when (type) {
        AnnotationType.HIGHLIGHT -> YELLOW
        AnnotationType.UNDERLINE -> BLUE
        AnnotationType.STRIKEOUT -> RED
        AnnotationType.INK -> BLUE
        AnnotationType.NOTE -> YELLOW
    }

    /** Default color per type, for every type. */
    fun defaults(): Map<AnnotationType, Int> = AnnotationType.entries.associateWith(::defaultFor)
}
