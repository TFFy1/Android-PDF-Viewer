package io.github.tffy1.pdfviewer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.util.Consumer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import io.github.tffy1.pdfviewer.data.settings.AppSettings
import io.github.tffy1.pdfviewer.integration.IncomingIntents
import io.github.tffy1.pdfviewer.navigation.AppNavHost
import io.github.tffy1.pdfviewer.navigation.openDocument
import io.github.tffy1.pdfviewer.ui.theme.PdfViewerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = appContainer
        setContent {
            val settings by container.settingsRepository.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings())
            PdfViewerTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
                val navController = rememberNavController()

                // Cold start via "Open with" / share: only handle it on first creation, not on rotation.
                LaunchedEffect(Unit) {
                    if (savedInstanceState == null) {
                        IncomingIntents.extractPdfUri(this@MainActivity, intent)
                            ?.let { navController.openDocument(it) }
                    }
                }
                // Warm start (activity is singleTask).
                DisposableEffect(navController) {
                    val listener = Consumer<Intent> { newIntent ->
                        IncomingIntents.extractPdfUri(this@MainActivity, newIntent)
                            ?.let { navController.openDocument(it) }
                    }
                    addOnNewIntentListener(listener)
                    onDispose { removeOnNewIntentListener(listener) }
                }

                AppNavHost(navController = navController)
            }
        }
    }
}
