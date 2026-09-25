package io.github.tffy1.pdfviewer.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.settingsDataStore

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        val defaults = AppSettings()
        AppSettings(
            themeMode = prefs[Keys.THEME_MODE].toEnum(defaults.themeMode),
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: defaults.dynamicColor,
            scrollMode = prefs[Keys.SCROLL_MODE].toEnum(defaults.scrollMode),
            pageColorMode = prefs[Keys.PAGE_COLOR_MODE].toEnum(defaults.pageColorMode),
            keepScreenOn = prefs[Keys.KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
            rememberLastPage = prefs[Keys.REMEMBER_LAST_PAGE] ?: defaults.rememberLastPage,
            volumeKeysTurnPages = prefs[Keys.VOLUME_KEYS] ?: defaults.volumeKeysTurnPages,
            showPageNumberOverlay = prefs[Keys.PAGE_NUMBER_OVERLAY] ?: defaults.showPageNumberOverlay,
            librarySort = prefs[Keys.LIBRARY_SORT].toEnum(defaults.librarySort),
        )
    }

    suspend fun setThemeMode(value: ThemeMode) = put(Keys.THEME_MODE, value.name)
    suspend fun setDynamicColor(value: Boolean) = put(Keys.DYNAMIC_COLOR, value)
    suspend fun setScrollMode(value: ScrollMode) = put(Keys.SCROLL_MODE, value.name)
    suspend fun setPageColorMode(value: PageColorMode) = put(Keys.PAGE_COLOR_MODE, value.name)
    suspend fun setKeepScreenOn(value: Boolean) = put(Keys.KEEP_SCREEN_ON, value)
    suspend fun setRememberLastPage(value: Boolean) = put(Keys.REMEMBER_LAST_PAGE, value)
    suspend fun setVolumeKeysTurnPages(value: Boolean) = put(Keys.VOLUME_KEYS, value)
    suspend fun setShowPageNumberOverlay(value: Boolean) = put(Keys.PAGE_NUMBER_OVERLAY, value)
    suspend fun setLibrarySort(value: LibrarySort) = put(Keys.LIBRARY_SORT, value.name)

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        dataStore.edit { it[key] = value }
    }

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val SCROLL_MODE = stringPreferencesKey("scroll_mode")
        val PAGE_COLOR_MODE = stringPreferencesKey("page_color_mode")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val REMEMBER_LAST_PAGE = booleanPreferencesKey("remember_last_page")
        val VOLUME_KEYS = booleanPreferencesKey("volume_keys_turn_pages")
        val PAGE_NUMBER_OVERLAY = booleanPreferencesKey("page_number_overlay")
        val LIBRARY_SORT = stringPreferencesKey("library_sort")
    }
}

private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
    this?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default
