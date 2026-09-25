package io.github.tffy1.pdfviewer.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * Brand palette generated from the seed #D93A2B ("document red") with the Material color
 * utilities (HCT tonal palettes): primary/secondary keep the seed's fidelity, tertiary is a warm
 * amber (seed hue + 60°) and neutrals are low-chroma warm greys so pages stay calm to read.
 * Every on-color pair meets WCAG AA (≥ 4.5:1) against its container.
 *
 * Keep [BrandRed], window_background (res/values/colors.xml and res/values-night/colors.xml) and the launcher colors in sync.
 */

/** The seed color. Used for brand surfaces such as the About header and the launcher icon. */
val BrandRed = Color(0xFFD93A2B)

/** Gradient stops used behind the app icon (matches drawable/ic_launcher_background). */
val BrandGradientTop = Color(0xFFE5503F)
val BrandGradientBottom = Color(0xFFC12A1C)

internal val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFFB51F15),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD93A2B),
    onPrimaryContainer = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFFFFB4A8),
    secondary = Color(0xFF9D4135),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDAD5),
    onSecondaryContainer = Color(0xFF7E2A20),
    tertiary = Color(0xFF775A0B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDF9B),
    onTertiaryContainer = Color(0xFF5B4300),
    background = Color(0xFFFFF8F6),
    onBackground = Color(0xFF231918),
    surface = Color(0xFFFFF8F6),
    onSurface = Color(0xFF231918),
    surfaceVariant = Color(0xFFF5DDDA),
    onSurfaceVariant = Color(0xFF534341),
    surfaceTint = Color(0xFFB51F15),
    inverseSurface = Color(0xFF392E2C),
    inverseOnSurface = Color(0xFFFFEDEA),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    outline = Color(0xFF857370),
    outlineVariant = Color(0xFFD8C2BE),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFF8F6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF0EE),
    surfaceContainer = Color(0xFFFCEAE7),
    surfaceContainerHigh = Color(0xFFF7E4E1),
    surfaceContainerHighest = Color(0xFFF1DEDC),
    surfaceDim = Color(0xFFE8D6D3),
)

internal val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFFFB4A8),
    onPrimary = Color(0xFF690001),
    primaryContainer = Color(0xFFD93A2B),
    onPrimaryContainer = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFFB51F15),
    secondary = Color(0xFFFFB4A8),
    onSecondary = Color(0xFF60140D),
    secondaryContainer = Color(0xFF7E2A20),
    onSecondaryContainer = Color(0xFFFFDAD5),
    tertiary = Color(0xFFE8C26C),
    onTertiary = Color(0xFF3F2E00),
    tertiaryContainer = Color(0xFF5B4300),
    onTertiaryContainer = Color(0xFFFFDF9B),
    background = Color(0xFF1A1110),
    onBackground = Color(0xFFF1DEDC),
    surface = Color(0xFF1A1110),
    onSurface = Color(0xFFF1DEDC),
    surfaceVariant = Color(0xFF534341),
    onSurfaceVariant = Color(0xFFD8C2BE),
    surfaceTint = Color(0xFFFFB4A8),
    inverseSurface = Color(0xFFF1DEDC),
    inverseOnSurface = Color(0xFF392E2C),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFFA08C89),
    outlineVariant = Color(0xFF534341),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF423735),
    surfaceContainerLowest = Color(0xFF140C0B),
    surfaceContainerLow = Color(0xFF231918),
    surfaceContainer = Color(0xFF271D1C),
    surfaceContainerHigh = Color(0xFF322826),
    surfaceContainerHighest = Color(0xFF3D3231),
    surfaceDim = Color(0xFF1A1110),
)
