package io.github.tffy1.pdfviewer

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class PdfViewerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Required once before any PdfBox usage (tools + annotation export).
        PDFBoxResourceLoader.init(applicationContext)
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as PdfViewerApp).container

/** Access the container from Compose, e.g. inside a viewModel { } initializer. */
@Composable
fun appContainer(): AppContainer = LocalContext.current.appContainer
