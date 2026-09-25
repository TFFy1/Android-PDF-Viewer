package io.github.tffy1.pdfviewer.tools

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Page size choice for "Images to PDF". */
enum class ImagePageSize {
    /** A4, portrait or landscape to match the image. */
    A4,

    /** US Letter, portrait or landscape to match the image. */
    LETTER,

    /** A page with the image's own aspect ratio. */
    FIT_IMAGE,
}

/** White space around each image, in PDF points (1/72"). */
enum class ImageMargin(val points: Float) {
    NONE(0f),
    SMALL(18f),
    LARGE(36f),
}

/** JPEG quality and pixel budget used when embedding pictures. */
enum class ImageQuality(val jpegQuality: Int, val maxPixels: Long) {
    LOW(jpegQuality = 60, maxPixels = 2_000_000),
    MEDIUM(jpegQuality = 80, maxPixels = 4_000_000),
    HIGH(jpegQuality = 92, maxPixels = 6_000_000),
}

/** How hard the compress tool re-encodes embedded images. */
enum class CompressionLevel(val jpegQuality: Int, val maxPixels: Long) {
    /** Smallest files: roughly 110 dpi for a full A4 page image. */
    STRONG(jpegQuality = 50, maxPixels = 1_200_000),

    /** Roughly 150 dpi for a full A4 page image. */
    BALANCED(jpegQuality = 70, maxPixels = 2_500_000),

    /** Roughly 200 dpi for a full A4 page image. */
    LIGHT(jpegQuality = 85, maxPixels = 4_000_000),
}

/** PDF affine matrix `[a b c d e f]` (x' = a·x + c·y + e, y' = b·x + d·y + f). */
data class PdfMatrix(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float) {
    /** Maps a point of the image's unit square (u right, v up) to page space. */
    fun map(u: Float, v: Float): Pair<Float, Float> = Pair(a * u + c * v + e, b * u + d * v + f)
}

/** Where one image goes: the page size (points) and the image transform on that page. */
data class ImagePlacement(val pageWidth: Float, val pageHeight: Float, val matrix: PdfMatrix)

/** Pure geometry for placing an image on a PDF page, honouring the EXIF orientation. */
object ImageLayout {
    const val A4_WIDTH = 595.27559f
    const val A4_HEIGHT = 841.88976f
    const val LETTER_WIDTH = 612f
    const val LETTER_HEIGHT = 792f

    /** Long side of the image area for [ImagePageSize.FIT_IMAGE] pages (A4 height). */
    const val FIT_LONG_SIDE = A4_HEIGHT

    // EXIF orientation values (same numbers as androidx.exifinterface's constants).
    const val ORIENTATION_NORMAL = 1
    const val ORIENTATION_FLIP_HORIZONTAL = 2
    const val ORIENTATION_ROTATE_180 = 3
    const val ORIENTATION_FLIP_VERTICAL = 4
    const val ORIENTATION_TRANSPOSE = 5
    const val ORIENTATION_ROTATE_90 = 6
    const val ORIENTATION_TRANSVERSE = 7
    const val ORIENTATION_ROTATE_270 = 8

    /** True when the orientation swaps width and height. */
    fun swapsDimensions(orientation: Int): Boolean = orientation in ORIENTATION_TRANSPOSE..ORIENTATION_ROTATE_270

    fun place(
        pixelWidth: Int,
        pixelHeight: Int,
        orientation: Int,
        pageSize: ImagePageSize,
        margin: ImageMargin,
    ): ImagePlacement {
        require(pixelWidth > 0 && pixelHeight > 0) { "Empty image" }
        val swap = swapsDimensions(orientation)
        val shownWidth = (if (swap) pixelHeight else pixelWidth).toFloat()
        val shownHeight = (if (swap) pixelWidth else pixelHeight).toFloat()
        val m = margin.points

        val pageWidth: Float
        val pageHeight: Float
        when (pageSize) {
            ImagePageSize.FIT_IMAGE -> {
                val scale = FIT_LONG_SIDE / max(shownWidth, shownHeight)
                pageWidth = shownWidth * scale + 2 * m
                pageHeight = shownHeight * scale + 2 * m
            }
            ImagePageSize.A4, ImagePageSize.LETTER -> {
                val (shortSide, longSide) = if (pageSize == ImagePageSize.A4) {
                    A4_WIDTH to A4_HEIGHT
                } else {
                    LETTER_WIDTH to LETTER_HEIGHT
                }
                val landscape = shownWidth > shownHeight
                pageWidth = if (landscape) longSide else shortSide
                pageHeight = if (landscape) shortSide else longSide
            }
        }

        val areaWidth = pageWidth - 2 * m
        val areaHeight = pageHeight - 2 * m
        val scale = min(areaWidth / shownWidth, areaHeight / shownHeight)
        val drawWidth = shownWidth * scale
        val drawHeight = shownHeight * scale
        val x = m + (areaWidth - drawWidth) / 2f
        val y = m + (areaHeight - drawHeight) / 2f
        return ImagePlacement(pageWidth, pageHeight, orientationMatrix(orientation, x, y, drawWidth, drawHeight))
    }

    /**
     * Matrix drawing the stored (un-rotated) image so that, after applying the EXIF
     * [orientation], it exactly fills the rectangle ([x], [y], [width], [height]) with
     * PDF's bottom-left origin. [width]/[height] are the size as displayed.
     */
    fun orientationMatrix(orientation: Int, x: Float, y: Float, width: Float, height: Float): PdfMatrix =
        when (orientation) {
            ORIENTATION_FLIP_HORIZONTAL -> PdfMatrix(-width, 0f, 0f, height, x + width, y)
            ORIENTATION_ROTATE_180 -> PdfMatrix(-width, 0f, 0f, -height, x + width, y + height)
            ORIENTATION_FLIP_VERTICAL -> PdfMatrix(width, 0f, 0f, -height, x, y + height)
            ORIENTATION_TRANSPOSE -> PdfMatrix(0f, -height, -width, 0f, x + width, y + height)
            ORIENTATION_ROTATE_90 -> PdfMatrix(0f, -height, width, 0f, x, y + height)
            ORIENTATION_TRANSVERSE -> PdfMatrix(0f, height, width, 0f, x, y)
            ORIENTATION_ROTATE_270 -> PdfMatrix(0f, height, -width, 0f, x + width, y)
            else -> PdfMatrix(width, 0f, 0f, height, x, y)
        }
}

/** Pure sizing decisions for decoding and re-encoding images within a memory budget. */
object ImageSampling {
    /** Images smaller than this are not worth re-encoding when compressing. */
    const val MIN_COMPRESSIBLE_PIXELS = 150L * 150L

    /**
     * Power-of-two `BitmapFactory.Options.inSampleSize` so the decoded bitmap has at most
     * twice [maxPixels] pixels (it is then scaled down exactly with [targetSize]).
     */
    fun sampleSize(width: Int, height: Int, maxPixels: Long): Int {
        require(maxPixels > 0)
        var sample = 1
        while ((width.toLong() / sample) * (height.toLong() / sample) > 2 * maxPixels) {
            sample *= 2
        }
        return sample
    }

    /** Size ≤ [maxPixels] pixels keeping the aspect ratio; unchanged when already small enough. */
    fun targetSize(width: Int, height: Int, maxPixels: Long): Pair<Int, Int> {
        val pixels = width.toLong() * height
        if (pixels <= maxPixels) return width to height
        val scale = sqrt(maxPixels.toDouble() / pixels)
        return max(1, floor(width * scale).toInt()) to max(1, floor(height * scale).toInt())
    }

    /** PdfBox subsampling step (keep every n-th pixel) so decoding stays near [maxPixels]. */
    fun subsampling(width: Int, height: Int, maxPixels: Long): Int {
        val pixels = width.toLong() * height
        if (pixels <= maxPixels) return 1
        return max(1, floor(sqrt(pixels.toDouble() / maxPixels)).toInt())
    }

    /** Only swap an image when it saves at least 10 %; otherwise keep the original bytes. */
    fun isWorthReplacing(originalBytes: Long, newBytes: Long): Boolean =
        newBytes > 0 && newBytes < originalBytes * 0.9

    /** Percentage saved going from [before] to [after] bytes, 0 when nothing was saved. */
    fun percentSaved(before: Long, after: Long): Int =
        if (before <= 0 || after >= before) 0 else ((before - after) * 100.0 / before).roundToInt()
}
