package io.github.tffy1.pdfviewer.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsDestinationTest {

    private val all = listOf(
        SettingsDestination.Main,
        SettingsDestination.About,
        SettingsDestination.Licenses,
        SettingsDestination.LicenseDetail("pdfium"),
        SettingsDestination.LicenseDetail("pdfbox-android"),
    )

    @Test
    fun keyRoundTripsForEveryDestination() {
        all.forEach { destination ->
            assertEquals(destination, SettingsDestination.fromKey(destination.key))
        }
    }

    @Test
    fun keysAreUnique() {
        assertEquals(all.size, all.map { it.key }.toSet().size)
    }

    @Test
    fun unknownOrMalformedKeysFallBackToMain() {
        listOf(null, "", "nope", "license/", "LICENSES").forEach { key ->
            assertEquals(SettingsDestination.Main, SettingsDestination.fromKey(key))
        }
    }

    @Test
    fun backWalksUpToMain() {
        var destination: SettingsDestination = SettingsDestination.LicenseDetail("kotlin")
        val visited = mutableListOf(destination)
        while (true) {
            destination = destination.parent ?: break
            visited += destination
        }
        assertEquals(
            listOf(
                SettingsDestination.LicenseDetail("kotlin"),
                SettingsDestination.Licenses,
                SettingsDestination.About,
                SettingsDestination.Main,
            ),
            visited,
        )
        assertNull(SettingsDestination.Main.parent)
    }
}
