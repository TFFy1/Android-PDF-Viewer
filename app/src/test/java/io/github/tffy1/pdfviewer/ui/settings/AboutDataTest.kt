package io.github.tffy1.pdfviewer.ui.settings

import io.github.tffy1.pdfviewer.ui.about.LicenseTextFormatter
import io.github.tffy1.pdfviewer.ui.about.OpenSourceLibraries
import io.github.tffy1.pdfviewer.ui.about.OpenSourceLicense
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the pure parts of the About pages (reached from Settings). */
class AboutDataTest {

    @Test
    fun librariesHaveUniqueIdsAndAtLeastOneLicense() {
        val libraries = OpenSourceLibraries.all
        assertEquals(libraries.size, libraries.map { it.id }.toSet().size)
        assertTrue(libraries.all { it.licenses.isNotEmpty() && it.website.startsWith("https://") })
    }

    @Test
    fun pdfiumCreditsBothLicenses() {
        val pdfium = OpenSourceLibraries.byId("pdfium")!!
        assertEquals(
            setOf(OpenSourceLicense.BSD_3_CLAUSE, OpenSourceLicense.APACHE_2_0),
            pdfium.licenses.toSet(),
        )
        assertNull(OpenSourceLibraries.byId("does-not-exist"))
    }

    @Test
    fun licenseDetailKeysSurviveRoundTrip() {
        OpenSourceLibraries.all.forEach { library ->
            val destination = SettingsDestination.LicenseDetail(library.id)
            assertEquals(destination, SettingsDestination.fromKey(destination.key))
        }
    }

    @Test
    fun reflowJoinsWrappedLinesAndKeepsParagraphs() {
        val text = "First line of a\n   wrapped paragraph.\n\n\nSecond\nparagraph.\n"
        assertEquals(
            "First line of a wrapped paragraph.\n\nSecond paragraph.",
            LicenseTextFormatter.reflow(text),
        )
    }

    @Test
    fun reflowKeepsListItemsOnTheirOwnLines() {
        val text = """
            Conditions:
               * Redistributions of source code must
            retain the notice.
               * Redistributions in binary form
            must reproduce it.
                (a) You must give
                    a copy; and
                (b) You must cause
            2. Grant of Copyright
               License.
        """.trimIndent()
        assertEquals(
            listOf(
                "Conditions:",
                "* Redistributions of source code must retain the notice.",
                "* Redistributions in binary form must reproduce it.",
                "(a) You must give a copy; and",
                "(b) You must cause",
                "2. Grant of Copyright License.",
            ).joinToString("\n"),
            LicenseTextFormatter.reflow(text),
        )
    }

    @Test
    fun reflowHandlesWindowsLineEndings() {
        assertEquals("a b\n\nc", LicenseTextFormatter.reflow("a\r\nb\r\n\r\nc"))
    }
}
