package io.github.tffy1.pdfviewer.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CacheFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun sizeOfSumsNestedFiles() {
        val dir = tmp.newFolder("thumbnails")
        File(dir, "a.png").writeBytes(ByteArray(100))
        File(dir, "nested").mkdirs()
        File(dir, "nested/b.png").writeBytes(ByteArray(23))

        assertEquals(123L, CacheFiles.sizeOf(dir))
    }

    @Test
    fun sizeOfMissingDirectoryIsZero() {
        assertEquals(0L, CacheFiles.sizeOf(File(tmp.root, "missing")))
    }

    @Test
    fun clearContentsDeletesChildrenButKeepsDirectory() {
        val dir = tmp.newFolder("thumbnails")
        File(dir, "a.png").writeBytes(ByteArray(10))
        File(dir, "nested").mkdirs()
        File(dir, "nested/b.png").writeBytes(ByteArray(10))

        assertTrue(CacheFiles.clearContents(dir))
        assertTrue(dir.isDirectory)
        assertEquals(0, dir.listFiles()?.size)
        assertEquals(0L, CacheFiles.sizeOf(dir))
    }

    @Test
    fun clearContentsOfMissingDirectorySucceeds() {
        assertTrue(CacheFiles.clearContents(File(tmp.root, "missing")))
    }
}
