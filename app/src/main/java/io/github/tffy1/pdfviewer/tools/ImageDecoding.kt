package io.github.tffy1.pdfviewer.tools

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

/** A decoded picture, still in its stored orientation, plus the EXIF orientation to apply. */
class DecodedImage(val bitmap: Bitmap, val orientation: Int)

/** Memory-conscious image decoding for the images tool. Call off the main thread. */
object ImageDecoding {
    /** EXIF orientation (1..8), [ImageLayout.ORIENTATION_NORMAL] when unknown. */
    fun readOrientation(resolver: ContentResolver, uri: Uri): Int {
        val orientation = try {
            resolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        } catch (e: Exception) {
            null
        }
        return if (orientation != null && orientation in 1..8) orientation else ImageLayout.ORIENTATION_NORMAL
    }

    /** Pixel size as stored, or null when the image can't be read. */
    fun readBounds(resolver: ContentResolver, uri: Uri): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val stream = resolver.openInputStream(uri) ?: return null
        // With inJustDecodeBounds the result is always null; only the options are filled in.
        stream.use { BitmapFactory.decodeStream(it, null, options) }
        return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
    }

    /**
     * Decodes [uri] with at most [maxPixels] pixels, opaque (transparent areas become white),
     * or returns null when it isn't a decodable image. May throw [OutOfMemoryError].
     */
    fun decode(resolver: ContentResolver, uri: Uri, maxPixels: Long): DecodedImage? {
        val (width, height) = readBounds(resolver, uri) ?: return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageSampling.sampleSize(width, height, maxPixels)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null
        val (targetWidth, targetHeight) = ImageSampling.targetSize(decoded.width, decoded.height, maxPixels)
        var bitmap = decoded
        if (targetWidth != decoded.width || targetHeight != decoded.height) {
            bitmap = Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
            if (bitmap !== decoded) decoded.recycle()
        }
        if (bitmap.hasAlpha()) bitmap = flattenOnWhite(bitmap)
        return DecodedImage(bitmap, readOrientation(resolver, uri))
    }

    /** Small preview with the EXIF orientation applied, or null. */
    fun decodeThumbnail(resolver: ContentResolver, uri: Uri, maxSidePx: Int): Bitmap? {
        val (width, height) = readBounds(resolver, uri) ?: return null
        var sample = 1
        while (width / (sample * 2) >= maxSidePx && height / (sample * 2) >= maxSidePx) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null
        val matrix = orientationMatrix(readOrientation(resolver, uri)) ?: return bitmap
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    /** Opaque copy of [source] over a white background; recycles [source]. */
    fun flattenOnWhite(source: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(result).apply {
            drawColor(Color.WHITE)
            drawBitmap(source, 0f, 0f, null)
        }
        source.recycle()
        // Opaque bitmaps become plain JPEGs; with alpha PdfBox would add a soft mask.
        result.setHasAlpha(false)
        return result
    }

    private fun orientationMatrix(orientation: Int): Matrix? = when (orientation) {
        ImageLayout.ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply { setScale(-1f, 1f) }
        ImageLayout.ORIENTATION_ROTATE_180 -> Matrix().apply { setRotate(180f) }
        ImageLayout.ORIENTATION_FLIP_VERTICAL -> Matrix().apply { setRotate(180f); postScale(-1f, 1f) }
        ImageLayout.ORIENTATION_TRANSPOSE -> Matrix().apply { setRotate(90f); postScale(-1f, 1f) }
        ImageLayout.ORIENTATION_ROTATE_90 -> Matrix().apply { setRotate(90f) }
        ImageLayout.ORIENTATION_TRANSVERSE -> Matrix().apply { setRotate(-90f); postScale(-1f, 1f) }
        ImageLayout.ORIENTATION_ROTATE_270 -> Matrix().apply { setRotate(-90f) }
        else -> null
    }
}
