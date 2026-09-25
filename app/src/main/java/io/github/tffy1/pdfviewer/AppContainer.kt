package io.github.tffy1.pdfviewer

import android.content.Context
import io.github.tffy1.pdfviewer.data.db.AppDatabase
import io.github.tffy1.pdfviewer.data.repository.AnnotationsRepository
import io.github.tffy1.pdfviewer.data.repository.BookmarksRepository
import io.github.tffy1.pdfviewer.data.repository.LibraryRepository
import io.github.tffy1.pdfviewer.data.repository.RecentDocumentsRepository
import io.github.tffy1.pdfviewer.data.settings.SettingsRepository
import io.github.tffy1.pdfviewer.io.DocumentAccess
import io.github.tffy1.pdfviewer.pdf.PdfEngine
import io.github.tffy1.pdfviewer.pdf.pdfium.PdfiumEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/** Manual dependency container (no DI framework). One instance per process. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    /** Scope for work that must outlive a screen (e.g. saving last page on exit). */
    val applicationScope = CoroutineScope(SupervisorJob())

    val database: AppDatabase by lazy { AppDatabase.create(appContext) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }
    val recentDocumentsRepository by lazy { RecentDocumentsRepository(database.recentDocumentDao()) }
    val bookmarksRepository by lazy { BookmarksRepository(database.bookmarkDao()) }
    val annotationsRepository by lazy { AnnotationsRepository(database.annotationDao()) }
    val libraryRepository by lazy { LibraryRepository(database.libraryDao()) }

    val documentAccess: DocumentAccess by lazy { DocumentAccess(appContext) }
    val pdfEngine: PdfEngine by lazy { PdfiumEngine(appContext, documentAccess) }
}
