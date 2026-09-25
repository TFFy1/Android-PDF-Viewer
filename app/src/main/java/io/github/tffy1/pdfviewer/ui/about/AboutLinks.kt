package io.github.tffy1.pdfviewer.ui.about

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Public repository of this app. */
const val SOURCE_CODE_URL = "https://github.com/TFFy1/Android-PDF-Viewer"

/**
 * Opens [url] in the user's browser. The app itself has no internet access; this only hands the
 * link to another app. Returns false when no app can handle it.
 */
fun Context.openExternalUrl(url: String): Boolean = try {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    true
} catch (e: ActivityNotFoundException) {
    false
}
