package io.github.tffy1.pdfviewer.tools

import android.net.Uri

/** A PDF to read, with the password to open it when it is protected. */
data class PdfInput(val uri: Uri, val displayName: String, val password: String? = null)

/** An image to place on its own page. */
data class ImageInput(val uri: Uri, val displayName: String)

/** A file written by a tool. */
data class ToolOutput(val uri: Uri, val displayName: String, val pageCount: Int, val sizeBytes: Long)

data class CompressionStats(val originalBytes: Long?, val compressedBytes: Long, val imagesCompressed: Int)

/** Outcome of a tool. Tools never throw (except [kotlinx.coroutines.CancellationException]). */
sealed interface ToolResult {
    data class Success(
        val outputs: List<ToolOutput>,
        /** A protected input was used and the output is not protected. */
        val protectionRemoved: Boolean = false,
        val compression: CompressionStats? = null,
    ) : ToolResult

    data class Failure(val error: ToolError) : ToolResult
}

sealed interface ToolError {
    data class PasswordRequired(val fileName: String) : ToolError
    data class WrongPassword(val fileName: String) : ToolError
    data class CannotRead(val fileName: String) : ToolError
    data object CannotWrite : ToolError
    data object NotEncrypted : ToolError

    /** The file opened with the user password, but its permissions forbid changes. */
    data object OwnerPasswordRequired : ToolError
    data object NothingToCompress : ToolError
    data object OutOfMemory : ToolError
    data class Unexpected(val message: String?) : ToolError
}

/** Thrown inside tool implementations to end with a specific [ToolError]. */
class ToolException(val error: ToolError, cause: Throwable? = null) : Exception(error.toString(), cause)
