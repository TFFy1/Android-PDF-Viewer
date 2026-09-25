package io.github.tffy1.pdfviewer.ui.viewer.annotations

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Eraser icon (Material Icons has none): a tilted eraser with a filled head, an outlined tip and
 * a base line. Colors are placeholders; `Icon` tints the whole vector.
 */
internal val EraserIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Eraser",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(14.5f, 3.5f)
            lineTo(21f, 10f)
            lineTo(15.25f, 15.75f)
            lineTo(8.75f, 9.25f)
            close()
        }
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(8.75f, 9.25f)
            lineTo(15.25f, 15.75f)
            lineTo(11.5f, 19.5f)
            lineTo(5f, 13f)
            close()
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(14f, 19.75f)
            lineTo(21f, 19.75f)
            lineTo(21f, 21.5f)
            lineTo(14f, 21.5f)
            close()
        }
    }.build()
}
