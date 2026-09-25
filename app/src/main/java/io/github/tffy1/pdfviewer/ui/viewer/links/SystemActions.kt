package io.github.tffy1.pdfviewer.ui.viewer.links

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.annotation.StringRes

/*
 * Small Android helpers shared by text selection and link handling
 * (clipboard, share sheet, launching other apps without crashing).
 */

/**
 * Copies [text] to the clipboard. Android 13+ shows its own confirmation, so
 * [confirmation] is only toasted on older versions. Returns false if the copy failed.
 */
internal fun Context.copyPlainText(label: String, text: String, @StringRes confirmation: Int): Boolean {
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return false
    val copied = try {
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        true
    } catch (e: RuntimeException) {
        // e.g. TransactionTooLargeException wrapped in a RuntimeException for huge clips.
        false
    }
    if (copied && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(this, confirmation, Toast.LENGTH_SHORT).show()
    }
    return copied
}

/** Opens the system share sheet for plain [text]. */
internal fun Context.sharePlainText(text: String): Boolean {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    return startActivitySafely(Intent.createChooser(send, null))
}

/** Starts [intent], returning false instead of crashing when no app can handle it. */
internal fun Context.startActivitySafely(intent: Intent): Boolean {
    if (findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}

private fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context != null) {
        if (context is Activity) return context
        context = (context as? ContextWrapper)?.baseContext
    }
    return null
}
