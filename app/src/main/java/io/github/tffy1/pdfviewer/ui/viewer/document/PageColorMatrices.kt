package io.github.tffy1.pdfviewer.ui.viewer.document

/**
 * 4x5 color matrices (row-major, offsets in 0..255, same layout as android.graphics.ColorMatrix
 * and Compose's ColorMatrix) applied when drawing pages. Pure Kotlin for unit tests.
 */
internal object PageColorMatrices {
    private const val LUMA_R = 0.299f
    private const val LUMA_G = 0.587f
    private const val LUMA_B = 0.114f

    // Night: white paper becomes this dark gray, black text this light gray (softer than pure
    // black/white for reading in the dark).
    private const val NIGHT_DARK = 24f
    private const val NIGHT_LIGHT = 224f

    /**
     * Inverts luminance while keeping hue: c' = c - 2Y + 255 (Y = Rec.601 luma), then compresses
     * the result into [NIGHT_DARK]..[NIGHT_LIGHT].
     */
    val night: FloatArray = run {
        val k = (NIGHT_LIGHT - NIGHT_DARK) / 255f
        val offset = 255f * k + NIGHT_DARK
        fun row(r: Float, g: Float, b: Float) = floatArrayOf(r * k, g * k, b * k, 0f, offset)
        floatArrayOf(
            *row(1f - 2f * LUMA_R, -2f * LUMA_G, -2f * LUMA_B),
            *row(-2f * LUMA_R, 1f - 2f * LUMA_G, -2f * LUMA_B),
            *row(-2f * LUMA_R, -2f * LUMA_G, 1f - 2f * LUMA_B),
            0f, 0f, 0f, 1f, 0f,
        )
    }

    // Sepia: white maps to warm paper, black to dark brown ink; colors keep a warm cast.
    private val sepiaPaper = floatArrayOf(244f, 236f, 216f)
    private val sepiaInk = floatArrayOf(59f, 46f, 34f)

    val sepia: FloatArray = FloatArray(20).also { m ->
        for (channel in 0..2) {
            m[channel * 5 + channel] = (sepiaPaper[channel] - sepiaInk[channel]) / 255f
            m[channel * 5 + 4] = sepiaInk[channel]
        }
        m[18] = 1f
    }

    /** Applies [matrix] to an opaque RGB color (0..255 channels), clamping like the platform does. */
    fun apply(matrix: FloatArray, r: Float, g: Float, b: Float): FloatArray = FloatArray(3) { row ->
        val base = row * 5
        (matrix[base] * r + matrix[base + 1] * g + matrix[base + 2] * b + matrix[base + 3] * 255f + matrix[base + 4])
            .coerceIn(0f, 255f)
    }
}
