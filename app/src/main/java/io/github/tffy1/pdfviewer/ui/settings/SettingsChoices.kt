package io.github.tffy1.pdfviewer.ui.settings

import androidx.annotation.StringRes
import io.github.tffy1.pdfviewer.R
import io.github.tffy1.pdfviewer.data.settings.PageColorMode
import io.github.tffy1.pdfviewer.data.settings.ScrollMode
import io.github.tffy1.pdfviewer.data.settings.ThemeMode

// User-visible labels for the enum-valued settings.

@StringRes
internal fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
}

@StringRes
internal fun ScrollMode.labelRes(): Int = when (this) {
    ScrollMode.VERTICAL -> R.string.settings_scroll_vertical
    ScrollMode.HORIZONTAL -> R.string.settings_scroll_horizontal
}

@StringRes
internal fun PageColorMode.labelRes(): Int = when (this) {
    PageColorMode.NORMAL -> R.string.settings_page_color_normal
    PageColorMode.NIGHT -> R.string.settings_page_color_night
    PageColorMode.SEPIA -> R.string.settings_page_color_sepia
}
