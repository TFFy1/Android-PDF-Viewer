package io.github.tffy1.pdfviewer.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import io.github.tffy1.pdfviewer.data.settings.ThemeMode

/* CONTRACT (scaffold). Owner: settings/theme agent — replace the body, keep the signature. */
@Composable
fun PdfViewerTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colorScheme: ColorScheme =
        if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            if (darkTheme) DarkColors else LightColors
        }

    val view = LocalView.current
    if (!view.isInEditMode) {
        val windowBackground = colorScheme.background.toArgb()
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            // Edge-to-edge is enabled by MainActivity; make the system bar icons follow the app
            // theme (which may differ from the system theme) rather than the system setting.
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
            // Match the window behind Compose to the theme so resizes/transitions never flash
            // the static XML window background (which only knows the system night mode).
            window.setBackgroundDrawable(ColorDrawable(windowBackground))
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PdfViewerTypography,
        content = content,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
