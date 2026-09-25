package io.github.tffy1.pdfviewer.ui.settings

/**
 * Pages shown inside the Settings tab. About and licenses are navigated locally (not through the
 * app NavHost) so the bottom bar stays visible; system back walks up via [parent].
 */
sealed interface SettingsDestination {
    /** The page system back returns to, or null for the root. */
    val parent: SettingsDestination?

    /** Stable string form, used to survive process death and configuration changes. */
    val key: String

    data object Main : SettingsDestination {
        override val parent: SettingsDestination? = null
        override val key = "main"
    }

    data object About : SettingsDestination {
        override val parent: SettingsDestination = Main
        override val key = "about"
    }

    data object Licenses : SettingsDestination {
        override val parent: SettingsDestination = About
        override val key = "licenses"
    }

    data class LicenseDetail(val libraryId: String) : SettingsDestination {
        override val parent: SettingsDestination = Licenses
        override val key = LICENSE_PREFIX + libraryId
    }

    companion object {
        private const val LICENSE_PREFIX = "license/"

        /** Inverse of [key]; unknown or malformed keys fall back to [Main]. */
        fun fromKey(key: String?): SettingsDestination = when {
            key == null -> Main
            key == About.key -> About
            key == Licenses.key -> Licenses
            key.startsWith(LICENSE_PREFIX) && key.length > LICENSE_PREFIX.length ->
                LicenseDetail(key.removePrefix(LICENSE_PREFIX))
            else -> Main
        }
    }
}
