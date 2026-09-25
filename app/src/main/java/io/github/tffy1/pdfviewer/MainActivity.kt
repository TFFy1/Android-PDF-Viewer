package io.github.tffy1.pdfviewer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import io.github.tffy1.pdfviewer.data.settings.AppSettings
import io.github.tffy1.pdfviewer.integration.IncomingIntents
import io.github.tffy1.pdfviewer.navigation.AppNavHost
import io.github.tffy1.pdfviewer.navigation.openDocument
import io.github.tffy1.pdfviewer.ui.theme.PdfViewerTheme
import kotlinx.coroutines.channels.Channel

class MainActivity : ComponentActivity() {
    /**
     * Documents handed to us via "Open with" / share. Buffered because intents can arrive
     * (onNewIntent) before composition has started.
     */
    private val incomingDocuments = Channel<Uri>(Channel.UNLIMITED)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Cold start. Skip on recreation and when relaunched from Recents: the original
        // VIEW intent would be replayed and its temporary URI grant may be gone.
        val launchedFromHistory = (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (savedInstanceState == null && !launchedFromHistory) {
            IncomingIntents.extractPdfUri(this, intent)?.let(incomingDocuments::trySend)
        }
        // Warm start (activity is singleTask).
        addOnNewIntentListener { newIntent ->
            IncomingIntents.extractPdfUri(this, newIntent)?.let(incomingDocuments::trySend)
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
