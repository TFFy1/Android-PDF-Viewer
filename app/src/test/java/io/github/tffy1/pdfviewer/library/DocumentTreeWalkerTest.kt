package io.github.tffy1.pdfviewer.library

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentTreeWalkerTest {

    /** In-memory tree: parent document id → children. Unknown ids can't be listed. */
    private class FakeTree(private val children: Map<String, List<TreeEntry>>) {
        val listed = mutableListOf<String>()

        fun list(documentId: String): List<TreeEntry>? {
            listed += documentId
            return children[documentId]
        }
    }

    private fun dir(id: String, name: String = id) = TreeEntry(id, name, DOCUMENT_MIME_DIRECTORY, 0, 0)
    private fun pdf(id: String, name: String = "$id.pdf", size: Long = 1) = TreeEntry(id, name, PDF_MIME_TYPE, size, 0)
    private fun file(id: String, name: String, mime: String?) = TreeEntry(id, name, mime, 1, 0)

    private fun ids(result: TreeWalkResult?) = result!!.pdfs.map { it.documentId }.toSet()

    @Test
    fun collectsPdfsRecursively() = runTest {
        val tree = FakeTree(
            mapOf(
                "root" to listOf(pdf("a"), dir("sub"), file("txt", "notes.txt", "text/plain")),
                "sub" to listOf(pdf("b"), dir("deeper")),
                "deeper" to listOf(pdf("c")),
            ),
        )
        val result = walkDocumentTree("root", ScanLimits(), tree::list)
        assertEquals(setOf("a", "b", "c"), ids(result))
        assertFalse(result!!.truncated)
    }

    @Test
    fun acceptsPdfExtensionWhenMimeTypeIsGeneric() = runTest {
        val tree = FakeTree(
            mapOf(
                "root" to listOf(
                    file("x", "Report.PDF", "application/octet-stream"),
                    file("y", "scan", "application/pdf"),
                    file("z", "image.png", "image/png"),
                    file("n", "unknown.pdf", null),
                ),
            ),
        )
        assertEquals(setOf("x", "y", "n"), ids(walkDocumentTree("root", ScanLimits(), tree::list)))
    }

    @Test
    fun skipsHiddenDirectoriesAndFiles() = runTest {
        val tree = FakeTree(
            mapOf(
                "root" to listOf(dir("hidden", ".thumbnails"), pdf("t", ".trashed-1.pdf"), pdf("v")),
                "hidden" to listOf(pdf("h")),
            ),
        )
        val result = walkDocumentTree("root", ScanLimits(), tree::list)
        assertEquals(setOf("v"), ids(result))
        assertFalse("hidden" in tree.listed)
    }

    @Test
    fun respectsDepthLimit() = runTest {
        val tree = FakeTree(
            mapOf(
                "root" to listOf(pdf("p0"), dir("d1")),
                "d1" to listOf(pdf("p1"), dir("d2")),
                "d2" to listOf(pdf("p2")),
            ),
        )
        assertEquals(setOf("p0", "p1"), ids(walkDocumentTree("root", ScanLimits(maxDepth = 1), tree::list)))
        assertEquals(setOf("p0"), ids(walkDocumentTree("root", ScanLimits(maxDepth = 0), tree::list)))
    }

    @Test
    fun stopsAtFileLimitAndReportsTruncation() = runTest {
        val tree = FakeTree(mapOf("root" to (1..5).map { pdf("p$it") }))
        val limited = walkDocumentTree("root", ScanLimits(maxFiles = 3), tree::list)!!
        assertEquals(3, limited.pdfs.size)
        assertTrue(limited.truncated)

        val exact = walkDocumentTree("root", ScanLimits(maxFiles = 5), tree::list)!!
        assertEquals(5, exact.pdfs.size)
        assertFalse(exact.truncated)
    }

    @Test
    fun stopsAtDirectoryLimit() = runTest {
        val tree = FakeTree(
            mapOf(
                "root" to listOf(dir("a"), dir("b")),
                "a" to listOf(pdf("pa")),
                "b" to listOf(pdf("pb")),
            ),
        )
        val result = walkDocumentTree("root", ScanLimits(maxDirectories = 2), tree::list)!!
        assertEquals(1, result.pdfs.size)
        assertTrue(result.truncated)
    }

    @Test
    fun unreadableRootReturnsNullAndUnreadableSubdirectoryIsSkipped() = runTest {
        assertNull(walkDocumentTree("missing", ScanLimits(), FakeTree(emptyMap())::list))

        val tree = FakeTree(mapOf("root" to listOf(dir("gone"), pdf("ok"))))
        assertEquals(setOf("ok"), ids(walkDocumentTree("root", ScanLimits(), tree::list)))
    }

    @Test
    fun survivesCycles() = runTest {
        val tree = FakeTree(
            mapOf(
                "root" to listOf(dir("a")),
                "a" to listOf(dir("root"), dir("a"), pdf("p")),
            ),
        )
        val result = walkDocumentTree("root", ScanLimits(), tree::list)
        assertEquals(setOf("p"), ids(result))
        assertEquals(listOf("root", "a"), tree.listed)
    }

    @Test
    fun stopsWhenCancelled() = runTest {
        val tree = FakeTree(
            mapOf(
                "root" to listOf(dir("a"), dir("b"), dir("c")),
                "a" to emptyList(),
                "b" to emptyList(),
                "c" to emptyList(),
            ),
        )
        lateinit var job: Job
        job = launch {
            walkDocumentTree("root", ScanLimits()) { id ->
                if (tree.listed.size == 1) job.cancel()
                tree.list(id)
            }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(2, tree.listed.size)
    }

    @Test
    fun fallbackFolderNameUsesLastPathSegment() {
        assertEquals("Books", fallbackFolderName("primary:Documents/Books"))
        assertEquals("Documents", fallbackFolderName("primary:Documents/"))
        assertEquals("primary", fallbackFolderName("primary:"))
        assertEquals("1234-ABCD", fallbackFolderName("1234-ABCD"))
    }
}
