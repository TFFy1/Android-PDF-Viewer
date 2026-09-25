package io.github.tffy1.pdfviewer.data.settings

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class ScrollMode { VERTICAL, HORIZONTAL }

/** How page content is colored in the viewer. */
enum class PageColorMode { NORMAL, NIGHT, SEPIA }

enum class LibrarySort { RECENT, NAME, SIZE }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val scrollMode: ScrollMode = ScrollMode.VERTICAL,
    val pageColorMode: PageColorMode = PageColorMode.NORMAL,
    val keepScreenOn: Boolean = false,
    val rememberLastPage: Boolean = true,
    val volumeKeysTurnPages: Boolean = false,
    val showPageNumberOverlay: Boolean = true,
    val librarySort: LibrarySort = LibrarySort.RECENT,
)
