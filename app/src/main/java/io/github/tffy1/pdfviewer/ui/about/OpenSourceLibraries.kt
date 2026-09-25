package io.github.tffy1.pdfviewer.ui.about

/** Licenses whose full text ships in res/raw. */
enum class OpenSourceLicense(val spdxId: String) {
    APACHE_2_0("Apache-2.0"),
    BSD_3_CLAUSE("BSD-3-Clause"),
}

/** A third-party component credited on the "Open-source licenses" screen. */
data class OpenSourceLibrary(
    val id: String,
    val name: String,
    val author: String,
    val licenses: List<OpenSourceLicense>,
    val website: String,
)

object OpenSourceLibraries {
    val all: List<OpenSourceLibrary> = listOf(
        OpenSourceLibrary(
            id = "pdfium",
            name = "PDFium",
            author = "The PDFium Authors (Google, Foxit Software)",
            licenses = listOf(OpenSourceLicense.BSD_3_CLAUSE, OpenSourceLicense.APACHE_2_0),
            website = "https://pdfium.googlesource.com/pdfium/",
        ),
        OpenSourceLibrary(
            id = "pdfiumandroid",
            name = "PdfiumAndroid (io.legere)",
            author = "John Gray, based on PdfiumAndroid by Bartosz Schiller",
            licenses = listOf(OpenSourceLicense.APACHE_2_0),
            website = "https://github.com/johngray1965/PdfiumAndroidKt",
        ),
        OpenSourceLibrary(
            id = "pdfbox-android",
            name = "PdfBox-Android",
            author = "Tom Roush",
            licenses = listOf(OpenSourceLicense.APACHE_2_0),
            website = "https://github.com/TomRoush/PdfBox-Android",
        ),
        OpenSourceLibrary(
            id = "apache-pdfbox",
            name = "Apache PDFBox",
            author = "The Apache Software Foundation",
            licenses = listOf(OpenSourceLicense.APACHE_2_0),
            website = "https://pdfbox.apache.org/",
        ),
        OpenSourceLibrary(
            id = "androidx",
            name = "AndroidX & Jetpack Compose",
            author = "The Android Open Source Project",
            licenses = listOf(OpenSourceLicense.APACHE_2_0),
            website = "https://developer.android.com/jetpack/androidx",
        ),
        OpenSourceLibrary(
            id = "kotlin",
            name = "Kotlin & kotlinx",
            author = "JetBrains s.r.o. and Kotlin Programming Language contributors",
            licenses = listOf(OpenSourceLicense.APACHE_2_0),
            website = "https://kotlinlang.org/",
        ),
        OpenSourceLibrary(
            id = "material-icons",
            name = "Material Icons",
            author = "Google",
            licenses = listOf(OpenSourceLicense.APACHE_2_0),
            website = "https://fonts.google.com/icons",
        ),
    )

    fun byId(id: String): OpenSourceLibrary? = all.firstOrNull { it.id == id }
}
