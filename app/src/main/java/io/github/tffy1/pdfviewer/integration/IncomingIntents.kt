package io.github.tffy1.pdfviewer.integration

import android.content.Context
import android.content.Intent
import android.net.Uri

/* CONTRACT (scaffold). Owner: Android-integration agent. */
object IncomingIntents {
    /** Returns the PDF to open for a VIEW/SEND intent, or null if the intent isn't one. */
    fun extractPdfUri(context: Context, intent: Intent?): Uri? {
        TODO("integration agent")
    }
}
