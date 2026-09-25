package io.github.tffy1.pdfviewer.ui.settings

import java.io.File

/** Blocking helpers for app-private cache directories. Call them off the main thread. */
internal object CacheFiles {
    /** Total size in bytes of all regular files below [dir] (0 if it does not exist). */
    fun sizeOf(dir: File): Long =
        if (!dir.isDirectory) 0L else dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /** Deletes everything inside [dir] but keeps [dir] itself. Returns true if nothing is left. */
    fun clearContents(dir: File): Boolean {
        if (!dir.isDirectory) return true
        var allDeleted = true
        dir.listFiles()?.forEach { child -> if (!child.deleteRecursively()) allDeleted = false }
        return allDeleted
    }
}
