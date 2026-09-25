package io.github.tffy1.pdfviewer.integration

/**
 * Pure decision logic for incoming "open this file" requests. No Android dependencies, so it is
 * unit-tested directly. Lambdas are evaluated lazily and only when needed, so callers can pass
 * IPC-backed lookups (ContentResolver.getType, display-name queries) without paying for them in
 * the common case.
 */
internal object PdfIntentRules {
    const val PDF_MIME = "application/pdf"

    /** Real-world aliases some apps still use for PDF. */
    private val PDF_ALIASES = setOf(
        PDF_MIME,
        "application/x-pdf",
        "application/acrobat",
        "applications/vnd.pdf",
        "application/vnd.pdf",
        "text/pdf",
        "text/x-pdf",
    )

    /** Types that say nothing about the content; the file name decides. */
    private val GENERIC_TYPES = setOf(
        "application/octet-stream",
        "binary/octet-stream",
        "application/binary",
        "application/unknown",
        "application/force-download",
        "application/download",
        "*/*",
        "application/*",
    )

    enum class MimeKind { PDF, GENERIC, OTHER }

    fun isSupportedScheme(scheme: String?): Boolean =
        scheme.equals("content", ignoreCase = true) || scheme.equals("file", ignoreCase = true)

    /** Classifies a MIME type, ignoring case and parameters such as "; charset=binary". */
    fun classifyMime(mime: String?): MimeKind {
        val base = mime?.substringBefore(';')?.trim()?.lowercase()
        return when {
            base.isNullOrEmpty() -> MimeKind.GENERIC
            base in PDF_ALIASES -> MimeKind.PDF
            base in GENERIC_TYPES -> MimeKind.GENERIC
            else -> MimeKind.OTHER
        }
    }

    fun hasPdfExtension(name: String?): Boolean =
        name != null && name.trim().endsWith(".pdf", ignoreCase = true)

    /**
     * Accept when the declared or resolved type is PDF. When both are generic/unknown, accept
     * only if the display name ends with ".pdf". A specific non-PDF type (image/png, …) rejects.
     */
    fun isAcceptablePdf(
        declaredMime: String?,
        resolvedMime: () -> String?,
        displayName: () -> String?,
    ): Boolean {
        val declared = classifyMime(declaredMime)
        if (declared == MimeKind.PDF) return true
        if (declared == MimeKind.OTHER) return false
        return when (classifyMime(resolvedMime())) {
            MimeKind.PDF -> true
            MimeKind.OTHER -> false
            MimeKind.GENERIC -> hasPdfExtension(displayName())
        }
    }

    /**
     * True when canonical [path] equals or lies below one of the canonical [directories].
     * Both sides must already be canonical (no "..", symlinks resolved).
     */
    fun isInsideAnyDirectory(path: String, directories: List<String>): Boolean =
        directories.any { dir ->
            val normalizedDir = dir.trimEnd('/')
            normalizedDir.isNotEmpty() && (path == normalizedDir || path.startsWith("$normalizedDir/"))
        }
}
