package io.github.tffy1.pdfviewer.annotations

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.SecureRandom
import java.util.Calendar

/** Why an annotated export failed. The UI maps [reason] to a message. */
class AnnotationExportException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Reason {
        /** The PDF needs a password to open; exporting protected files is not supported. */
        PASSWORD_PROTECTED,

        /** The PDF's permissions don't allow adding annotations. */
        NOT_PERMITTED,

        /** The source couldn't be read (missing permission, deleted, corrupt). */
        READ_FAILED,

        /** The destination couldn't be written. */
        WRITE_FAILED,

        /** Not enough memory to process the document. */
        OUT_OF_MEMORY,
    }
}

/**
 * Writes a copy of a PDF with the app's annotations added as real PDF annotations
 * (Highlight/Underline/StrikeOut with QuadPoints, Ink, Text notes), each with an appearance
 * stream so every viewer — including Pdfium-based ones — renders them.
 */
object AnnotatedPdfExporter {
    private const val TAG = "AnnotatedPdfExporter"

    /** Size of the note icon PdfBox draws for /Name /Note (width x height, points). */
    private const val NOTE_ICON_WIDTH = 18f
    private const val NOTE_ICON_HEIGHT = 20f

    suspend fun export(
        context: Context,
        sourceUri: Uri,
        destinationUri: Uri,
        annotations: List<PageAnnotation>,
        deleteDestinationOnFailure: Boolean = true,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        try {
            val input = resolver.openInputStream(sourceUri)
                ?: throw AnnotationExportException(AnnotationExportException.Reason.READ_FAILED, "Cannot open source")
            val memory = MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)
            val document = try {
                input.use { PDDocument.load(it, memory) }
            } catch (e: InvalidPasswordException) {
                throw AnnotationExportException(
                    AnnotationExportException.Reason.PASSWORD_PROTECTED,
                    "The document is password protected",
                    e,
                )
            } catch (e: Exception) {
                // IOException for unreadable/corrupt files; PdfBox also throws runtime exceptions.
                throw AnnotationExportException(AnnotationExportException.Reason.READ_FAILED, "Cannot read source", e)
            }
            document.use { doc ->
                if (doc.isEncrypted) keepProtection(doc)
                ensureActive()
                addAnnotations(doc, annotations)
                ensureActive()
                val output = resolver.openOutputStream(destinationUri, "wt")
                    ?: throw AnnotationExportException(AnnotationExportException.Reason.WRITE_FAILED, "Cannot open destination")
                try {
                    output.buffered().use { doc.save(it) }
                } catch (e: IOException) {
                    throw AnnotationExportException(AnnotationExportException.Reason.WRITE_FAILED, "Cannot write copy", e)
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            if (deleteDestinationOnFailure) deleteQuietly(context, destinationUri)
            throw e
        } catch (e: AnnotationExportException) {
            Log.w(TAG, "Export failed: ${e.reason}", e)
            if (deleteDestinationOnFailure) deleteQuietly(context, destinationUri)
            Result.failure(e)
        } catch (e: Exception) {
            Log.w(TAG, "Export failed", e)
            if (deleteDestinationOnFailure) deleteQuietly(context, destinationUri)
            val reason = if (e is SecurityException) {
                AnnotationExportException.Reason.READ_FAILED
            } else {
                AnnotationExportException.Reason.WRITE_FAILED
            }
            Result.failure(AnnotationExportException(reason, e.message ?: "Export failed", e))
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Export ran out of memory", e)
            if (deleteDestinationOnFailure) deleteQuietly(context, destinationUri)
            Result.failure(AnnotationExportException(AnnotationExportException.Reason.OUT_OF_MEMORY, "Out of memory", e))
        }
    }

    /**
     * The PDF opened without a password but is encrypted (owner restrictions). PdfBox can only
     * save it encrypted again with a new policy: keep the same user permissions and an empty user
     * password, with a random owner password so the restrictions can't be lifted from our copy.
     */
    private fun keepProtection(doc: PDDocument) {
        val permission = doc.currentAccessPermission
        if (!permission.canModifyAnnotations()) {
            throw AnnotationExportException(
                AnnotationExportException.Reason.NOT_PERMITTED,
                "The document's permissions don't allow annotations",
            )
        }
        val policy = StandardProtectionPolicy(randomPassword(), "", AccessPermission(permission.permissionBytes))
        policy.setEncryptionKeyLength(256)
        doc.protect(policy)
    }

    private fun randomPassword(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun addAnnotations(doc: PDDocument, annotations: List<PageAnnotation>) {
        val pageCount = doc.numberOfPages
        for ((pageIndex, pageAnnotations) in annotations.groupBy { it.pageIndex }) {
            if (pageIndex !in 0 until pageCount) continue
            val page = doc.getPage(pageIndex)
            val transform = transformFor(page)
            val target = page.annotations
            for (annotation in pageAnnotations) {
                val pdAnnotation = try {
                    createAnnotation(annotation, transform) ?: continue
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Skipping annotation ${annotation.id}", e)
                    continue
                }
                pdAnnotation.setPage(page)
                try {
                    pdAnnotation.constructAppearances(doc)
                } catch (e: RuntimeException) {
                    // Keep the annotation: most viewers can still draw it without an appearance.
                    Log.w(TAG, "No appearance for annotation ${annotation.id}", e)
                }
                target.add(pdAnnotation)
            }
        }
    }

    private fun transformFor(page: PDPage): PageTransform {
        val crop = page.cropBox
        return PageTransform(
            UserSpaceRect.of(crop.lowerLeftX, crop.lowerLeftY, crop.upperRightX, crop.upperRightY),
            page.rotation,
        )
    }

    private fun createAnnotation(annotation: PageAnnotation, transform: PageTransform): PDAnnotation? {
        val created = when (val content = annotation.content) {
            is AnnotationContent.Markup -> createMarkup(annotation.type, content, transform)
            is AnnotationContent.Ink -> createInk(content, transform)
            is AnnotationContent.Note -> createNote(content, annotation.note, transform)
        } ?: return null
        created.setColor(pdColor(annotation.color))
        created.setPrinted(true)
        val modified = Calendar.getInstance().apply {
            timeInMillis = annotation.updatedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        }
        created.setModifiedDate(modified)
        created.setCreationDate(
            Calendar.getInstance().apply {
                timeInMillis = annotation.createdAt.takeIf { it > 0L } ?: modified.timeInMillis
            },
        )
        return created
    }

    private fun createMarkup(
        type: AnnotationType,
        content: AnnotationContent.Markup,
        transform: PageTransform,
    ): PDAnnotationMarkup? {
        val subtype = when (type) {
            AnnotationType.HIGHLIGHT -> PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT
            AnnotationType.UNDERLINE -> PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE
            AnnotationType.STRIKEOUT -> PDAnnotationTextMarkup.SUB_TYPE_STRIKEOUT
            else -> return null
        }
        val rects = content.rects.filter { it.width > 0f && it.height > 0f }
        if (rects.isEmpty()) return null
        val quads = FloatArray(rects.size * 8)
        rects.forEachIndexed { i, rect -> transform.quadPoints(rect).copyInto(quads, destinationOffset = i * 8) }
        val bounds = rects.map { transform.toUserSpace(it) }.reduce { a, b ->
            UserSpaceRect.of(
                minOf(a.lowerLeftX, b.lowerLeftX),
                minOf(a.lowerLeftY, b.lowerLeftY),
                maxOf(a.upperRightX, b.upperRightX),
                maxOf(a.upperRightY, b.upperRightY),
            )
        }
        return PDAnnotationTextMarkup(subtype).apply {
            setQuadPoints(quads)
            setRectangle(bounds.toPdRectangle(padding = 1f))
            if (type != AnnotationType.HIGHLIGHT) {
                setBorderStyle(PDBorderStyleDictionary().apply { setWidth(AnnotationGeometry.markupLineWidth(rects)) })
            }
        }
    }

    private fun createInk(content: AnnotationContent.Ink, transform: PageTransform): PDAnnotationMarkup? {
        val strokes = content.strokes.filter { it.points.size >= 2 }
        if (strokes.isEmpty()) return null
        // Ink lists are polylines: export the same smoothed curve the overlay draws.
        val paths = strokes.map { stroke ->
            AnnotationGeometry.smoothPolyline(stroke.points).map { transform.toUserSpace(it) }
        }
        val lineWidth = strokes.maxOf { it.width }
        val bounds = UserSpaceRect.bounding(paths.flatten()) ?: return null
        return PDAnnotationMarkup().apply {
            getCOSObject().setName(COSName.SUBTYPE, PDAnnotationMarkup.SUB_TYPE_INK)
            setInkList(
                paths.map { path ->
                    FloatArray(path.size * 2).also { array ->
                        path.forEachIndexed { i, p ->
                            array[2 * i] = p.x
                            array[2 * i + 1] = p.y
                        }
                    }
                }.toTypedArray(),
            )
            setBorderStyle(PDBorderStyleDictionary().apply { setWidth(lineWidth) })
            setRectangle(bounds.toPdRectangle(padding = lineWidth))
        }
    }

    private fun createNote(content: AnnotationContent.Note, text: String?, transform: PageTransform): PDAnnotationText {
        val anchor = transform.toUserSpace(content.anchor)
        return PDAnnotationText().apply {
            setName(PDAnnotationText.NAME_NOTE)
            setContents(text.orEmpty())
            setOpen(false)
            // Center the icon on the anchor, like the in-app icon.
            setRectangle(
                PDRectangle(
                    anchor.x - NOTE_ICON_WIDTH / 2f,
                    anchor.y - NOTE_ICON_HEIGHT / 2f,
                    NOTE_ICON_WIDTH,
                    NOTE_ICON_HEIGHT,
                ),
            )
        }
    }

    private fun UserSpaceRect.toPdRectangle(padding: Float): PDRectangle =
        PDRectangle(lowerLeftX - padding, lowerLeftY - padding, width + 2 * padding, height + 2 * padding)

    private fun pdColor(argb: Int): PDColor = PDColor(
        floatArrayOf(
            ((argb shr 16) and 0xFF) / 255f,
            ((argb shr 8) and 0xFF) / 255f,
            (argb and 0xFF) / 255f,
        ),
        PDDeviceRGB.INSTANCE,
    )

    private fun deleteQuietly(context: Context, uri: Uri) {
        try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (e: Exception) {
            // Not a document URI or the provider doesn't support deleting: leave it.
            Log.i(TAG, "Could not remove incomplete copy", e)
        }
    }
}
