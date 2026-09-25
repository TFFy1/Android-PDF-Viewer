package io.github.tffy1.pdfviewer.ui.settings

import io.github.tffy1.pdfviewer.data.settings.PageColorMode
import io.github.tffy1.pdfviewer.data.settings.ScrollMode
import io.github.tffy1.pdfviewer.data.settings.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsChoicesTest {

    private fun assertDistinctLabels(labels: List<Int>) {
        assertTrue("every option needs a label", labels.all { it != 0 })
        assertEquals("labels must not be shared between options", labels.size, labels.toSet().size)
    }

    @Test
    fun themeModesHaveDistinctLabels() = assertDistinctLabels(ThemeMode.entries.map { it.labelRes() })

    @Test
    fun scrollModesHaveDistinctLabels() = assertDistinctLabels(ScrollMode.entries.map { it.labelRes() })

    @Test
    fun pageColorModesHaveDistinctLabels() =
        assertDistinctLabels(PageColorMode.entries.map { it.labelRes() })
}
