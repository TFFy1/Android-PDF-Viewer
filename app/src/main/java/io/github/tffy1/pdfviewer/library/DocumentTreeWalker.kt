package io.github.tffy1.pdfviewer.library

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** MIME type the Storage Access Framework uses for directories (DocumentsContract.Document.MIME_TYPE_DIR). */
const val DOCUMENT_MIME_DIRECTORY = "vnd.android.document/directory"

const val PDF_MIME_TYPE = "application/pdf"

/** One row of a SAF child-documents query. Plain Kotlin so the traversal can be unit tested. */
data class TreeEntry(
    val documentId: String,
    val name: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val lastModified: Long,
) {
    val isDirectory: Boolean get() = mimeType == DOCUMENT_MIME_DIRECTORY
}

/** Bounds that keep a scan of a huge or cyclic tree from running away. */
data class ScanLimits(
    /** Deepest directory level that is listed; the picked folder itself is level 0. */
    val maxDepth: Int = 12,
    /** Maximum number of PDFs collected before the scan stops. */
    val maxFiles: Int = 10_000,
    /** Maximum number of directories listed before the scan stops. */
    val maxDirectories: Int = 20_000,
)

data class TreeWalkResult(
    val pdfs: List<TreeEntry>,
    /** True when a limit stopped the scan before the whole tree was visited. */
    val truncated: Boolean,
)

/** Hidden entries (".thumbnails", ".trashed-…pdf" and friends) are never part of the library. */
fun isHiddenName(name: String): Boolean = name.startsWith('.')

/** Providers are not consistent about MIME types, so a ".pdf" name is accepted as well. */
fun isPdfEntry(entry: TreeEntry): Boolean =
    !entry.isDirectory &&
        (entry.mimeType.equals(PDF_MIME_TYPE, ignoreCase = true) || entry.name.endsWith(".pdf", ignoreCase = true))

/**
 * Walks a document tree depth-first and collects every visible PDF.
 *
 * [listChildren] performs the (blocking) child query for a document id and returns null when that
 * directory can't be listed. Returns null if the root itself can't be listed. Directory ids are
 * tracked so a provider that reports cycles can't trap the walk. Cancellation is checked before
 * every directory listing.
 */
suspend fun walkDocumentTree(
    rootDocumentId: String,
    limits: ScanLimits = ScanLimits(),
    listChildren: (documentId: String) -> List<TreeEntry>?,
): TreeWalkResult? {
    currentCoroutineContext().ensureActive()
    val rootChildren = listChildren(rootDocumentId) ?: return null

    val pdfs = ArrayList<TreeEntry>()
    val visited = hashSetOf(rootDocumentId)
    val pending = ArrayDeque<Pair<String, Int>>()
    var listedDirectories = 1
    var truncated = false

    /** Handles one directory's children at [depth]; returns false once the file limit is hit. */
    fun collect(children: List<TreeEntry>, depth: Int): Boolean {
        for (child in children) {
            if (isHiddenName(child.name)) continue
            if (child.isDirectory) {
                val childDepth = depth + 1
                if (childDepth <= limits.maxDepth && visited.add(child.documentId)) {
                    pending.addLast(child.documentId to childDepth)
                }
            } else if (isPdfEntry(child)) {
                if (pdfs.size >= limits.maxFiles) {
                    truncated = true
                    return false
                }
                pdfs += child
            }
        }
        return true
    }

    var keepGoing = collect(rootChildren, depth = 0)
    while (keepGoing && pending.isNotEmpty()) {
        if (listedDirectories >= limits.maxDirectories) {
            truncated = true
            break
        }
        currentCoroutineContext().ensureActive()
        val (documentId, depth) = pending.removeLast()
        val children = listChildren(documentId) ?: continue
        listedDirectories++
        keepGoing = collect(children, depth)
    }
    return TreeWalkResult(pdfs, truncated)
}

/**
 * Readable fallback name for a tree whose provider doesn't report a display name.
 * External-storage ids look like "primary:Documents/Books" → "Books".
 */
fun fallbackFolderName(treeDocumentId: String): String {
    val path = treeDocumentId.substringAfter(':', treeDocumentId).trimEnd('/')
    val last = path.substringAfterLast('/')
    return last.ifEmpty { treeDocumentId.substringBefore(':').ifEmpty { treeDocumentId } }
}
