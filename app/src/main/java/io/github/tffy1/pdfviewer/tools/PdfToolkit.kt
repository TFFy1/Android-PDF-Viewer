package io.github.tffy1.pdfviewer.tools

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.util.Matrix
import io.github.tffy1.pdfviewer.io.DocumentAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.math.max
import kotlin.math.min

/** Reports progress of a running tool as a fraction in 0..1. Called on a background thread. */
typealias ProgressListener = (Float) -> Unit

/** What the owner of a protected PDF allows people who only know the open password to do. */
data class PdfPermissions(
    val print: Boolean = true,
    val copy: Boolean = true,
    val modify: Boolean = true,
    val annotate: Boolean = true,
)

/**
 * PDF tools implemented with PdfBox-Android. Every operation:
 * - runs on [Dispatchers.IO] and buffers PDF data in temp files (no big heap allocations),
 * - checks for cancellation between pages and while writing, and rethrows
 *   [CancellationException] after deleting partial output,
 * - never throws otherwise: failures are returned as [ToolResult.Failure],
 * - never modifies its inputs; outputs go to destinations picked through the Storage Access
 *   Framework.
 *
 * Outputs of tools other than [addPassword] are not encrypted (PdfBox can only re-encrypt with a
 * known owner password); [ToolResult.Success.protectionRemoved] tells the UI when that happened.
 */
class PdfToolkit(context: Context, private val documentAccess: DocumentAccess) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver get() = appContext.contentResolver
    private val tempDir: File get() = File(appContext.cacheDir, TEMP_DIR_NAME)

    /** Appends all pages of [inputs], in order, into one new PDF at [destination]. */
    suspend fun merge(inputs: List<PdfInput>, destination: Uri, onProgress: ProgressListener): ToolResult =
        runTool(listOf(destination)) {
            require(inputs.isNotEmpty()) { "Nothing to merge" }
            // Sources must stay open until the merged document is saved: it references their data.
            val sources = ArrayList<PDDocument>(inputs.size)
            try {
                PDDocument(memorySetting()).use { target ->
                    val merger = PDFMergerUtility()
                    var protectedInput = false
                    var estimatedBytes = 0L
                    inputs.forEachIndexed { index, input ->
                        ensureActive()
                        val source = load(input)
                        sources += source
                        protectedInput = protectedInput || source.isEncrypted
                        estimatedBytes += sizeOf(input.uri)
                        merger.appendDocument(target, source)
                        onProgress(PREPARE_SHARE * (index + 1) / inputs.size)
                    }
                    val pageCount = target.numberOfPages
                    val size = save(target, destination, estimatedBytes, onProgress.scaled(PREPARE_SHARE, 1f))
                    ToolResult.Success(listOf(output(destination, pageCount, size)), protectionRemoved = protectedInput)
                }
            } finally {
                sources.forEach { it.closeQuietly() }
            }
        }

    /** Copies the pages at [pageIndices] (0-based, in that order) into a new PDF. */
    suspend fun extractPages(
        input: PdfInput,
        pageIndices: List<Int>,
        destination: Uri,
        onProgress: ProgressListener,
    ): ToolResult = runTool(listOf(destination)) {
        load(input).use { source ->
            val total = source.numberOfPages
            if (pageIndices.isEmpty() || pageIndices.any { it !in 0 until total }) {
                throw ToolException(ToolError.CannotRead(input.displayName))
            }
            PDDocument(memorySetting()).use { target ->
                pageIndices.forEachIndexed { i, index ->
                    ensureActive()
                    importPage(target, source.getPage(index))
                    onProgress(PREPARE_SHARE * (i + 1) / pageIndices.size)
                }
                val estimate = sizeOf(input.uri) * pageIndices.size / max(1, total)
                val size = save(target, destination, estimate, onProgress.scaled(PREPARE_SHARE, 1f))
                ToolResult.Success(
                    listOf(output(destination, pageIndices.size, size)),
                    protectionRemoved = source.isEncrypted,
                )
            }
        }
    }

    /**
     * Writes one PDF per entry of [parts] into the folder [folderTreeUri] (from
     * `ACTION_OPEN_DOCUMENT_TREE`). On failure or cancellation, files already written are deleted.
     */
    suspend fun split(
        input: PdfInput,
        parts: List<PageRange>,
        folderTreeUri: Uri,
        onProgress: ProgressListener,
    ): ToolResult = runTool(emptyList()) { created ->
        load(input).use { source ->
            val total = source.numberOfPages
            if (parts.isEmpty() || parts.any { it.last > total }) {
                throw ToolException(ToolError.CannotRead(input.displayName))
            }
            val parent = folderDocumentUri(folderTreeUri)
            val sourceSize = sizeOf(input.uri)
            val outputs = ArrayList<ToolOutput>(parts.size)
            parts.forEachIndexed { partIndex, range ->
                val partProgress = onProgress.scaled(
                    partIndex.toFloat() / parts.size,
                    (partIndex + 1f) / parts.size,
                )
                PDDocument(memorySetting()).use { target ->
                    for (index in range.indices) {
                        ensureActive()
                        importPage(target, source.getPage(index))
                    }
                    val name = SplitPlanner.partFileName(input.displayName, range)
                    val uri = createDocument(parent, name)
                    created += uri
                    val size = save(target, uri, sourceSize * range.size / max(1, total), partProgress)
                    outputs += ToolOutput(uri, nameOf(uri, name), range.size, size)
                }
            }
            ToolResult.Success(outputs, protectionRemoved = source.isEncrypted)
        }
    }

    /**
     * Saves a copy containing [pages] in the given order and with the extra rotation applied.
     * Works on the loaded document itself so outlines, forms and links between kept pages survive.
     */
    suspend fun organize(
        input: PdfInput,
        pages: List<PageEdit>,
        destination: Uri,
        onProgress: ProgressListener,
    ): ToolResult = runTool(listOf(destination)) {
        load(input).use { doc ->
            val total = doc.numberOfPages
            if (pages.isEmpty() || pages.any { it.sourceIndex !in 0 until total }) {
                throw ToolException(ToolError.CannotRead(input.displayName))
            }
            val original = ArrayList<PDPage>(total)
            for (page in doc.pages) original += page

            pages.forEachIndexed { i, edit ->
                ensureActive()
                val page = original[edit.sourceIndex]
                // Pin inherited attributes: the page is re-parented to the root of the tree below.
                page.getResources()?.let { page.setResources(it) }
                page.setMediaBox(page.getMediaBox())
                page.setCropBox(page.getCropBox())
                page.rotation = PageEdits.normalizeRotation(page.rotation + edit.rotationDelta)
                onProgress(PREPARE_SHARE * (i + 1) / pages.size)
            }

            // Rebuild the page tree as a flat list in the new order. Deleted pages are simply
            // no longer referenced by the tree.
            val tree = doc.pages
            val root = tree.cosObject
            root.setItem(COSName.KIDS, COSArray())
            root.setInt(COSName.COUNT, 0)
            pages.forEach { tree.add(original[it.sourceIndex]) }

            val wasProtected = doc.isEncrypted
            if (wasProtected) doc.setAllSecurityToBeRemoved(true)
            val size = save(doc, destination, sizeOf(input.uri), onProgress.scaled(PREPARE_SHARE, 1f))
            ToolResult.Success(listOf(output(destination, pages.size, size)), protectionRemoved = wasProtected)
        }
    }

    /** Builds a PDF with one page per image. */
    suspend fun imagesToPdf(
        images: List<ImageInput>,
        pageSize: ImagePageSize,
        margin: ImageMargin,
        quality: ImageQuality,
        destination: Uri,
        onProgress: ProgressListener,
    ): ToolResult = runTool(listOf(destination)) {
        require(images.isNotEmpty()) { "No images" }
        PDDocument(memorySetting()).use { doc ->
            var estimatedBytes = 0L
            images.forEachIndexed { index, image ->
                ensureActive()
                val decoded: DecodedImage? = try {
                    ImageDecoding.decode(resolver, image.uri, quality.maxPixels)
                } catch (e: IOException) {
                    null
                } catch (e: SecurityException) {
                    null
                } catch (e: IllegalArgumentException) {
                    null
                }
                if (decoded == null) throw ToolException(ToolError.CannotRead(image.displayName))
                try {
                    val bitmap = decoded.bitmap
                    val pdImage = JPEGFactory.createFromImage(doc, bitmap, quality.jpegQuality / 100f)
                    estimatedBytes += pdImage.cosObject.length
                    val placement = ImageLayout.place(bitmap.width, bitmap.height, decoded.orientation, pageSize, margin)
                    val page = PDPage(PDRectangle(placement.pageWidth, placement.pageHeight))
                    doc.addPage(page)
                    PDPageContentStream(doc, page).use { content ->
                        val m = placement.matrix
                        content.drawImage(pdImage, Matrix(m.a, m.b, m.c, m.d, m.e, m.f))
                    }
                } finally {
                    decoded.bitmap.recycle()
                }
                onProgress(IMAGES_SHARE * (index + 1) / images.size)
            }
            val size = save(doc, destination, estimatedBytes, onProgress.scaled(IMAGES_SHARE, 1f))
            ToolResult.Success(listOf(output(destination, images.size, size)))
        }
    }

    /**
     * Re-encodes embedded images as smaller JPEGs (best effort; images with transparency,
     * 1-bit images and formats Android can't decode are kept). Fails with
     * [ToolError.NothingToCompress] (and writes nothing) when no image got smaller.
     */
    suspend fun compress(
        input: PdfInput,
        level: CompressionLevel,
        destination: Uri,
        onProgress: ProgressListener,
    ): ToolResult = runTool(listOf(destination)) {
        load(input).use { doc ->
            val recompressor = ImageRecompressor(doc, level, coroutineContext)
            val total = max(1, doc.numberOfPages)
            var done = 0
            for (page in doc.pages) {
                ensureActive()
                recompressor.process(page.resources)
                done++
                onProgress(COMPRESS_SHARE * done / total)
            }
            if (recompressor.replaced == 0) throw ToolException(ToolError.NothingToCompress)

            val wasProtected = doc.isEncrypted
            if (wasProtected) doc.setAllSecurityToBeRemoved(true)
            val originalSize = documentAccess.queryInfo(input.uri).sizeBytes
            val size = save(doc, destination, (originalSize ?: 0L) / 2, onProgress.scaled(COMPRESS_SHARE, 1f))
            ToolResult.Success(
                listOf(output(destination, doc.numberOfPages, size)),
                protectionRemoved = wasProtected,
                compression = CompressionStats(originalSize, size, recompressor.replaced),
            )
        }
    }

    /** Saves an unprotected copy. [PdfInput.password] must open the file with edit rights. */
    suspend fun removePassword(input: PdfInput, destination: Uri, onProgress: ProgressListener): ToolResult =
        runTool(listOf(destination)) {
            load(input).use { doc ->
                if (!doc.isEncrypted) throw ToolException(ToolError.NotEncrypted)
                val access = doc.currentAccessPermission
                // Respect the author's restrictions: without the owner password, only files whose
                // permissions allow changes may be unlocked.
                if (!access.isOwnerPermission && !access.canModify()) {
                    throw ToolException(ToolError.OwnerPasswordRequired)
                }
                doc.setAllSecurityToBeRemoved(true)
                onProgress(PREPARE_SHARE)
                val size = save(doc, destination, sizeOf(input.uri), onProgress.scaled(PREPARE_SHARE, 1f))
                ToolResult.Success(listOf(output(destination, doc.numberOfPages, size)))
            }
        }

    /**
     * Saves a copy encrypted with AES-256. When [ownerPassword] is empty a random one is used,
     * so the permissions can't be lifted with the open password.
     */
    suspend fun addPassword(
        input: PdfInput,
        userPassword: String,
        ownerPassword: String?,
        permissions: PdfPermissions,
        destination: Uri,
        onProgress: ProgressListener,
    ): ToolResult = runTool(listOf(destination)) {
        require(userPassword.isNotEmpty()) { "Empty password" }
        load(input).use { doc ->
            val access = AccessPermission().apply {
                setCanPrint(permissions.print)
                setCanPrintFaithful(permissions.print)
                setCanExtractContent(permissions.copy)
                setCanModify(permissions.modify)
                setCanAssembleDocument(permissions.modify)
                setCanModifyAnnotations(permissions.annotate)
                setCanFillInForm(permissions.annotate)
                setCanExtractForAccessibility(true)
            }
            val owner = ownerPassword?.takeIf { it.isNotEmpty() } ?: randomPassword()
            val policy = StandardProtectionPolicy(owner, userPassword, access)
            policy.setEncryptionKeyLength(256)
            doc.protect(policy)
            onProgress(PREPARE_SHARE)
            val pageCount = doc.numberOfPages
            val size = save(doc, destination, sizeOf(input.uri), onProgress.scaled(PREPARE_SHARE, 1f))
            ToolResult.Success(listOf(output(destination, pageCount, size)))
        }
    }

    // ---------------------------------------------------------------------------------------
    // Shared plumbing
    // ---------------------------------------------------------------------------------------

    /**
     * Runs [block] on the IO dispatcher and converts every failure into a [ToolResult.Failure].
     * Files listed in [outputs] (plus the ones the block adds to the list it receives) are deleted
     * when the tool fails or is cancelled, so no half-written PDF is left behind.
     */
    private suspend fun runTool(
        outputs: List<Uri>,
        block: suspend CoroutineScope.(created: MutableList<Uri>) -> ToolResult,
    ): ToolResult = withContext(Dispatchers.IO) {
        val created = outputs.toMutableList()
        cleanStaleTempFiles()
        try {
            block(this, created)
        } catch (e: CancellationException) {
            deleteQuietly(created)
            throw e
        } catch (e: ToolException) {
            deleteQuietly(created)
            ToolResult.Failure(e.error)
        } catch (e: OutOfMemoryError) {
            deleteQuietly(created)
            ToolResult.Failure(ToolError.OutOfMemory)
        } catch (e: Exception) {
            Log.w(TAG, "PDF tool failed", e)
            deleteQuietly(created)
            ToolResult.Failure(ToolError.Unexpected(e.message))
        }
    }

    private fun memorySetting(): MemoryUsageSetting {
        val dir = tempDir
        dir.mkdirs()
        return MemoryUsageSetting.setupTempFileOnly().setTempDir(dir)
    }

    /** Removes temp files left behind by a crash or process kill. */
    private fun cleanStaleTempFiles() {
        val now = System.currentTimeMillis()
        tempDir.listFiles()?.forEach { file ->
            if (now - file.lastModified() > STALE_TEMP_FILE_MS) file.delete()
        }
    }

    private fun load(input: PdfInput): PDDocument {
        val stream: InputStream? = try {
            resolver.openInputStream(input.uri)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open input", e)
            null
        }
        if (stream == null) throw ToolException(ToolError.CannotRead(input.displayName))
        return stream.use {
            try {
                PDDocument.load(it, input.password.orEmpty(), memorySetting())
            } catch (e: InvalidPasswordException) {
                val error = if (input.password.isNullOrEmpty()) {
                    ToolError.PasswordRequired(input.displayName)
                } else {
                    ToolError.WrongPassword(input.displayName)
                }
                throw ToolException(error, e)
            } catch (e: IOException) {
                throw ToolException(ToolError.CannotRead(input.displayName), e)
            }
        }
    }

    /**
     * Writes [doc] to [destination], reporting progress against [estimatedBytes] and honouring
     * cancellation while writing. Returns the number of bytes written.
     */
    private fun CoroutineScope.save(
        doc: PDDocument,
        destination: Uri,
        estimatedBytes: Long,
        onProgress: ProgressListener,
    ): Long {
        val stream = ProgressOutputStream(BufferedOutputStream(openOutput(destination), BUFFER_SIZE), coroutineContext) { written ->
            if (estimatedBytes > 0) onProgress(min(0.99f, written.toFloat() / estimatedBytes))
        }
        try {
            // PDDocument.save closes the stream when done; ProgressOutputStream.close is idempotent.
            stream.use { doc.save(it) }
        } catch (e: IOException) {
            if (stream.writeFailed) throw ToolException(ToolError.CannotWrite, e)
            throw e
        }
        onProgress(1f)
        return stream.count
    }

    private fun openOutput(uri: Uri): OutputStream {
        // "wt" truncates; a few providers only support "w", which is fine for the fresh
        // documents created by the system file picker.
        val truncating: OutputStream? = try {
            resolver.openOutputStream(uri, "wt")
        } catch (e: Exception) {
            null
        }
        if (truncating != null) return truncating
        val plain: OutputStream? = try {
            resolver.openOutputStream(uri, "w")
        } catch (e: Exception) {
            throw ToolException(ToolError.CannotWrite, e)
        }
        return plain ?: throw ToolException(ToolError.CannotWrite)
    }

    private fun folderDocumentUri(treeUri: Uri): Uri = try {
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
    } catch (e: IllegalArgumentException) {
        throw ToolException(ToolError.CannotWrite, e)
    }

    private fun createDocument(parentDocumentUri: Uri, displayName: String): Uri {
        val uri: Uri? = try {
            DocumentsContract.createDocument(resolver, parentDocumentUri, PDF_MIME_TYPE, displayName)
        } catch (e: Exception) {
            throw ToolException(ToolError.CannotWrite, e)
        }
        return uri ?: throw ToolException(ToolError.CannotWrite)
    }

    private fun deleteQuietly(uris: List<Uri>) {
        uris.forEach { uri ->
            try {
                if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                    DocumentsContract.deleteDocument(resolver, uri)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not delete partial output", e)
            }
        }
    }

    private suspend fun sizeOf(uri: Uri): Long = documentAccess.queryInfo(uri).sizeBytes ?: 0L

    private suspend fun nameOf(uri: Uri, fallback: String): String =
        try {
            documentAccess.queryInfo(uri).displayName
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fallback
        }

    private suspend fun output(uri: Uri, pageCount: Int, sizeBytes: Long): ToolOutput =
        ToolOutput(uri, nameOf(uri, uri.lastPathSegment ?: PDF_FALLBACK_NAME), pageCount, sizeBytes)

    /**
     * Imports [page] into [target] like PdfBox's Splitter: keeps inherited resources and drops
     * links to other pages so the whole source document isn't pulled into the output.
     */
    private fun importPage(target: PDDocument, page: PDPage): PDPage {
        val imported = target.importPage(page)
        val resources = page.resources
        if (resources != null && !page.cosObject.containsKey(COSName.RESOURCES)) {
            imported.resources = resources
        }
        for (annotation in imported.annotations) {
            if (annotation is PDAnnotationLink) {
                var linkDestination = annotation.destination
                val action = annotation.action
                if (linkDestination == null && action is PDActionGoTo) linkDestination = action.destination
                if (linkDestination is PDPageDestination) linkDestination.setPage(null)
            }
            annotation.setPage(null)
        }
        return imported
    }

    private fun randomPassword(): String {
        val bytes = ByteArray(RANDOM_PASSWORD_BYTES)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun PDDocument.closeQuietly() {
        try {
            close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing document", e)
        }
    }

    private companion object {
        const val TAG = "PdfToolkit"
        const val PDF_MIME_TYPE = "application/pdf"
        const val PDF_FALLBACK_NAME = "document.pdf"
        const val TEMP_DIR_NAME = "pdfbox-tmp"
        const val STALE_TEMP_FILE_MS = 6L * 60 * 60 * 1000
        const val BUFFER_SIZE = 64 * 1024
        const val RANDOM_PASSWORD_BYTES = 24

        /** Share of the progress bar used before writing starts (the rest tracks writing). */
        const val PREPARE_SHARE = 0.3f
        const val IMAGES_SHARE = 0.8f
        const val COMPRESS_SHARE = 0.6f
    }
}

private fun ProgressListener.scaled(from: Float, to: Float): ProgressListener =
    { fraction -> this(from + (to - from) * fraction.coerceIn(0f, 1f)) }

/**
 * Counts bytes, reports progress every [REPORT_STEP] bytes and aborts with a
 * [CancellationException] once [context] is cancelled. Remembers whether the underlying stream
 * failed so write errors can be told apart from errors reading the source PDF.
 */
private class ProgressOutputStream(
    private val out: OutputStream,
    private val context: CoroutineContext,
    private val onWritten: (Long) -> Unit,
) : OutputStream() {
    var count = 0L
        private set
    var writeFailed = false
        private set
    private var lastReported = 0L
    private var closed = false

    override fun write(b: Int) {
        context.ensureActive()
        guard { out.write(b) }
        advance(1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        context.ensureActive()
        guard { out.write(b, off, len) }
        advance(len)
    }

    override fun flush() = guard { out.flush() }

    override fun close() {
        if (closed) return
        closed = true
        guard { out.close() }
    }

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (e: IOException) {
            writeFailed = true
            throw e
        }
    }

    private fun advance(bytes: Int) {
        count += bytes
        if (count - lastReported >= REPORT_STEP) {
            lastReported = count
            onWritten(count)
        }
    }

    private companion object {
        const val REPORT_STEP = 256L * 1024
    }
}

/** Replaces large embedded images with smaller JPEG versions, sharing replacements. */
private class ImageRecompressor(
    private val doc: PDDocument,
    private val level: CompressionLevel,
    private val context: CoroutineContext,
) {
    private val replacements = IdentityHashMap<COSStream, PDImageXObject?>()
    private val visited: MutableSet<COSDictionary> = Collections.newSetFromMap(IdentityHashMap())

    var replaced = 0
        private set

    fun process(resources: PDResources?) {
        if (resources == null || !visited.add(resources.cosObject)) return
        val names = resources.getXObjectNames().toList()
        for (name in names) {
            context.ensureActive()
            val xObject = try {
                resources.getXObject(name)
            } catch (e: IOException) {
                null
            }
            when (xObject) {
                is PDImageXObject -> replacementFor(xObject)?.let { resources.put(name, it) }
                is PDFormXObject -> process(xObject.resources)
                else -> Unit
            }
        }
    }

    private fun replacementFor(image: PDImageXObject): PDImageXObject? {
        val stream = image.cosObject
        if (replacements.containsKey(stream)) return replacements[stream]
        val result = try {
            recompress(image)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Unsupported filter (JPX, JBIG2…), exotic colour space or damaged data: keep it.
            null
        } catch (e: OutOfMemoryError) {
            null
        }
        replacements[stream] = result
        if (result != null) replaced++
        return result
    }

    private fun recompress(image: PDImageXObject): PDImageXObject? {
        val width = image.width
        val height = image.height
        if (width.toLong() * height < ImageSampling.MIN_COMPRESSIBLE_PIXELS) return null
        // Keep masks, stencils and bilevel scans: JPEG would lose transparency or bloat them.
        if (image.isStencil || image.bitsPerComponent == 1) return null
        if (image.softMask != null || image.mask != null || image.colorKeyMask != null) return null

        val originalLength = image.cosObject.length
        val decoded: Bitmap = image.getImage(null, ImageSampling.subsampling(width, height, level.maxPixels))
            ?: return null
        var bitmap = decoded
        try {
            val (targetWidth, targetHeight) = ImageSampling.targetSize(decoded.width, decoded.height, level.maxPixels)
            if (targetWidth != decoded.width || targetHeight != decoded.height) {
                bitmap = Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
            }
            // The image has no mask, so it is opaque; without this PdfBox would add a soft mask.
            bitmap.setHasAlpha(false)
            val candidate = JPEGFactory.createFromImage(doc, bitmap, level.jpegQuality / 100f)
            return if (ImageSampling.isWorthReplacing(originalLength, candidate.cosObject.length)) candidate else null
        } finally {
            if (bitmap !== decoded) bitmap.recycle()
            decoded.recycle()
        }
    }
}
