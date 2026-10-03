package io.github.tffy1.pdfviewer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import io.github.tffy1.pdfviewer.data.settings.AppSettings
import io.github.tffy1.pdfviewer.integration.IncomingIntents
import io.github.tffy1.pdfviewer.navigation.AppNavHost
import io.github.tffy1.pdfviewer.navigation.openDocument
import io.github.tffy1.pdfviewer.ui.theme.PdfViewerTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /**
     * Documents handed to us via "Open with" / share. Buffered because intents can arrive
     * (onNewIntent) before composition has started.
     */
    private val incomingDocuments = Channel<Uri>(Channel.UNLIMITED)

    /** PDFs are queued as they are; Word files are converted to PDF first (off the main thread). */
    private fun importAndQueue(uri: Uri) {
        lifecycleScope.launch {
            try {
                incomingDocuments.trySend(appContainer.documentImporter.prepareForViewing(uri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, R.string.word_error_convert, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Cold start. Skip on recreation and when relaunched from Recents: the original
        // VIEW intent would be replayed and its temporary URI grant may be gone.
        val launchedFromHistory = (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (savedInstanceState == null && !launchedFromHistory) {
            IncomingIntents.extractDocumentUri(this, intent)?.let(::importAndQueue)
        }
        // Warm start (activity is singleTask).
        addOnNewIntentListener { newIntent ->
            IncomingIntents.extractDocumentUri(this, newIntent)?.let(::importAndQueue)
        }

        val container = appContainer
        setContent {
            val settings by container.settingsRepository.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings())
            PdfViewerTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
                val navController = rememberNavController()
                LaunchedEffect(navController) {
                    for (uri in incomingDocuments) navController.openDocument(uri)
                }
                AppNavHost(navController = navController)
            }
        }
    }
}
