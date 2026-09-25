package io.github.tffy1.pdfviewer.ui.about

/**
 * License files are hard-wrapped at ~72 columns, which looks ragged once the phone re-wraps them.
 * [reflow] joins the lines of each paragraph so the text wraps naturally, while keeping paragraph
 * breaks and starting list items ("1.", "(a)", "*", "-") on their own line.
 */
object LicenseTextFormatter {
    private val listItemStart = Regex("""^(\*|-|\(\w{1,4}\)|\d{1,2}\.)\s""")

    fun reflow(text: String): String =
        text.replace("\r\n", "\n")
            .split(Regex("""\n[ \t]*\n"""))
            .map(::reflowParagraph)
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")

    private fun reflowParagraph(paragraph: String): String {
        val lines = mutableListOf<StringBuilder>()
        paragraph.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                val current = lines.lastOrNull()
                if (current == null || listItemStart.containsMatchIn(line)) {
                    lines += StringBuilder(line)
                } else {
                    current.append(' ').append(line)
                }
            }
        return lines.joinToString("\n")
    }
}
