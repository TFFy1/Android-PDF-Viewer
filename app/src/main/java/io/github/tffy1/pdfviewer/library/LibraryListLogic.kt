package io.github.tffy1.pdfviewer.library

import io.github.tffy1.pdfviewer.data.db.LibraryFileEntity
import io.github.tffy1.pdfviewer.data.db.LibraryFolderEntity
import io.github.tffy1.pdfviewer.data.db.RecentDocumentEntity
import io.github.tffy1.pdfviewer.data.settings.LibrarySort
import java.text.Normalizer

/** "Page N of M" from the stored 0-based last page; null when the page count is unknown. */
fun readingProgress(lastPageIndex: Int, pageCount: Int): ReadingProgress? =
    if (pageCount <= 0) null else ReadingProgress((lastPageIndex + 1).coerceIn(1, pageCount), pageCount)

/**
 * True when [documentUri] was built from one of the library's tree grants
 * (`content://…/tree/<id>/document/<docId>`), so it stays readable even without its own grant.
 */
fun isCoveredByTreeGrant(documentUri: String, treeUris: Collection<String>): Boolean =
    treeUris.any { tree -> documentUri.startsWith("$tree/document/") }

fun RecentDocumentEntity.toDocumentItem(folderTreeUris: Collection<String>): DocumentItem = DocumentItem(
    uri = uri,
    name = displayName,
    sizeBytes = sizeBytes?.takeIf { it > 0 },
    lastOpenedAt = lastOpenedAt,
    lastModified = null,
    recencyTimestamp = lastOpenedAt,
    progress = readingProgress(lastPage, pageCount),
    isFavorite = isFavorite,
    isRecent = true,
    thumbnailPath = thumbnailPath,
    needsReopen = !hasPersistentAccess && !isCoveredByTreeGrant(uri, folderTreeUris),
)

/** A scanned folder file, enriched with its history entry when the user has opened it before. */
fun LibraryFileEntity.toDocumentItem(recent: RecentDocumentEntity?): DocumentItem = DocumentItem(
    uri = uri,
    name = displayName,
    sizeBytes = sizeBytes.takeIf { it > 0 } ?: recent?.sizeBytes?.takeIf { it > 0 },
    lastOpenedAt = recent?.lastOpenedAt,
    lastModified = lastModified.takeIf { it > 0 },
    recencyTimestamp = lastModified,
    progress = recent?.let { readingProgress(it.lastPage, it.pageCount) },
    isFavorite = recent?.isFavorite == true,
    isRecent = recent != null,
    thumbnailPath = recent?.thumbnailPath,
    needsReopen = false,
)

/**
 * Case-insensitive "natural" order: "Chapter 2" sorts before "Chapter 10".
 * Runs of ASCII digits are compared by numeric value, everything else character by character.
 */
object NaturalOrderComparator : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isAsciiDigit() && cb.isAsciiDigit()) {
                val startA = i
                while (i < a.length && a[i].isAsciiDigit()) i++
                val startB = j
                while (j < b.length && b[j].isAsciiDigit()) j++
                val numberA = a.substring(startA, i).trimStart('0')
                val numberB = b.substring(startB, j).trimStart('0')
                if (numberA.length != numberB.length) return numberA.length.compareTo(numberB.length)
                val byValue = numberA.compareTo(numberB)
                if (byValue != 0) return byValue
            } else {
                val byChar = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (byChar != 0) return byChar
                i++
                j++
            }
        }
        val byRemaining = (a.length - i).compareTo(b.length - j)
        // Fully equal ignoring case and leading zeros: fall back to a stable, deterministic order.
        return if (byRemaining != 0) byRemaining else a.compareTo(b)
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
}

private val byName: Comparator<DocumentItem> =
    Comparator<DocumentItem> { x, y -> NaturalOrderComparator.compare(x.name, y.name) }.thenBy { it.uri }

fun sortDocuments(items: List<DocumentItem>, sort: LibrarySort): List<DocumentItem> = when (sort) {
    LibrarySort.RECENT -> items.sortedWith(compareByDescending<DocumentItem> { it.recencyTimestamp }.then(byName))
    LibrarySort.NAME -> items.sortedWith(byName)
    // Largest first; unknown sizes go last.
    LibrarySort.SIZE -> items.sortedWith(
        compareBy<DocumentItem> { it.sizeBytes == null }
            .thenByDescending { it.sizeBytes ?: 0L }
            .then(byName),
    )
}

private val combiningMarks = Regex("\\p{Mn}+")
private val whitespace = Regex("\\s+")

/** Lower-cases and strips accents so "resume" finds "Résumé.pdf". */
fun normalizeForSearch(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(combiningMarks, "").lowercase()

/** A parsed search query: every whitespace-separated term must appear in the file name. */
class SearchQuery(raw: String) {
    private val terms: List<String> = normalizeForSearch(raw).split(whitespace).filter { it.isNotEmpty() }

    val isEmpty: Boolean get() = terms.isEmpty()

    fun matches(name: String): Boolean {
        if (terms.isEmpty()) return true
        val normalized = normalizeForSearch(name)
        return terms.all { normalized.contains(it) }
    }
}

fun filterDocuments(items: List<DocumentItem>, query: String): List<DocumentItem> {
    val search = SearchQuery(query)
    return if (search.isEmpty) items else items.filter { search.matches(it.name) }
}

/** Builds the lists shown by every tab from the raw database rows and the UI inputs. */
fun assembleLibraryContent(
    recents: List<RecentDocumentEntity>,
    folders: List<LibraryFolderEntity>,
    files: List<LibraryFileEntity>,
    sort: LibrarySort,
    query: String,
    openedFolderUri: String?,
    scanStates: Map<String, FolderScanState>,
): LibraryContent {
    val search = SearchQuery(query)
    val treeUris = folders.map { it.treeUri }
    val recentItems = recents.map { it.toDocumentItem(treeUris) }
    val visibleRecents = if (search.isEmpty) recentItems else recentItems.filter { search.matches(it.name) }

    val countsByFolder = files.groupingBy { it.folderUri }.eachCount()
    val folderItems = folders
        .map { folder ->
            FolderItem(
                treeUri = folder.treeUri,
                name = folder.displayName,
                pdfCount = countsByFolder[folder.treeUri] ?: 0,
                scanState = scanStates[folder.treeUri] ?: FolderScanState.IDLE,
            )
        }
        .sortedWith { x, y -> NaturalOrderComparator.compare(x.name, y.name) }
    val openedFolder = openedFolderUri?.let { uri -> folderItems.firstOrNull { it.treeUri == uri } }

    val candidateFiles = when {
        openedFolder != null -> files.filter { it.folderUri == openedFolder.treeUri }
        !search.isEmpty -> files
        else -> emptyList()
    }
    val recentByUri = if (candidateFiles.isEmpty()) emptyMap() else recents.associateBy { it.uri }
    val folderFiles = candidateFiles
        .filter { search.matches(it.displayName) }
        .map { it.toDocumentItem(recentByUri[it.uri]) }

    return LibraryContent(
        recents = sortDocuments(visibleRecents, sort),
        favorites = sortDocuments(visibleRecents.filter { it.isFavorite }, sort),
        folders = folderItems,
        openedFolder = openedFolder,
        folderFiles = sortDocuments(folderFiles, sort),
        totalRecents = recents.size,
        totalFavorites = recents.count { it.isFavorite },
    )
}
