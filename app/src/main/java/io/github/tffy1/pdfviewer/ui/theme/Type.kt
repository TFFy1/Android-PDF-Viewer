package io.github.tffy1.pdfviewer.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Default = Typography()

/**
 * The default Material 3 type scale (system font, no bundled font files) with slightly firmer
 * headings and titles, which reads better next to dense page thumbnails and document names.
 */
internal val PdfViewerTypography: Typography = Default.copy(
    headlineLarge = Default.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
    headlineMedium = Default.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = Default.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Default.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Default.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Default.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = Default.bodyLarge.copy(letterSpacing = 0.15.sp),
    labelLarge = Default.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)
