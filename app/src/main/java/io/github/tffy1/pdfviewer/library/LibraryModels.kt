package io.github.tffy1.pdfviewer.library

/** Reading position as shown in the library ("Page 12 of 240"). [page] is 1-based. */
data class ReadingProgress(val page: Int, val pageCount: Int) {
    val fraction: Float
        get() = if (pageCount <= 0) 0f else (page.toFloat() / pageCount).coerceIn(0f, 1f)
}

/** A PDF as listed by the library, whether it comes from recents or from a scanned folder. */
data class DocumentItem(
    val uri: String,
    val name: String,
    val sizeBytes: Long?,
    /** When the document was last opened in the app, or null if it never was. */
    val lastOpenedAt: Long?,
    /** Modification time reported by the folder scan, or null for documents outside library folders. */
    val lastModified: Long?,
    /** Timestamp the "Recent" sort uses: opened time for recents, modified time for folder files. */
    val recencyTimestamp: Long,
    val progress: ReadingProgress?,
    val isFavorite: Boolean,
    /** True when the document has a history entry, so it can be starred or removed from recents. */
    val isRecent: Boolean,
    val thumbnailPath: String?,
    /** The app has no lasting access to this URI; the user will likely need to pick it again. */
    val needsReopen: Boolean,
)

enum class FolderScanState { IDLE, SCANNING, ACCESS_LOST, UNAVAILABLE, LIMIT_REACHED }

data class FolderItem(
    val treeUri: String,
    val name: String,
    val pdfCount: Int,
    val scanState: FolderScanState,
)

/** Everything the library lists, already filtered by the search query and sorted. */
data class LibraryContent(
    val recents: List<DocumentItem> = emptyList(),
    val favorites: List<DocumentItem> = emptyList(),
    val folders: List<FolderItem> = emptyList(),
    /** The folder whose PDFs are being browsed in the Folders tab, if any. */
    val openedFolder: FolderItem? = null,
    /**
     * PDFs of [openedFolder]; when no folder is open but a search is active, the matches across
     * all library folders; otherwise empty.
     */
    val folderFiles: List<DocumentItem> = emptyList(),
    /** Unfiltered counts, to tell "nothing here yet" apart from "nothing matches the search". */
    val totalRecents: Int = 0,
    val totalFavorites: Int = 0,
)
