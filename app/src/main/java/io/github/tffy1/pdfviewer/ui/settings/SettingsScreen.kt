package io.github.tffy1.pdfviewer.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tffy1.pdfviewer.R
import io.github.tffy1.pdfviewer.appContainer
import io.github.tffy1.pdfviewer.data.settings.AppSettings
import io.github.tffy1.pdfviewer.data.settings.PageColorMode
import io.github.tffy1.pdfviewer.data.settings.ScrollMode
import io.github.tffy1.pdfviewer.data.settings.ThemeMode
import io.github.tffy1.pdfviewer.ui.about.AboutContent
import io.github.tffy1.pdfviewer.ui.about.LicenseDetailContent
import io.github.tffy1.pdfviewer.ui.about.LicensesContent
import io.github.tffy1.pdfviewer.ui.about.OpenSourceLibraries
import java.io.File
import kotlinx.coroutines.launch

/** Sub-directory of filesDir holding cached document thumbnails. */
private const val THUMBNAIL_DIR_NAME = "thumbnails"

private val DestinationSaver = Saver<SettingsDestination, String>(
    save = { it.key },
    restore = { SettingsDestination.fromKey(it) },
)

/* CONTRACT (scaffold). Owner: settings/theme agent. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val container = appContainer()
    val viewModel: SettingsViewModel = viewModel {
        SettingsViewModel(
            settingsRepository = container.settingsRepository,
            recentDocumentsRepository = container.recentDocumentsRepository,
            thumbnailDir = File(container.appContext.filesDir, THUMBNAIL_DIR_NAME),
        )
    }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val thumbnailBytes by viewModel.thumbnailCacheBytes.collectAsStateWithLifecycle()

    var destination by rememberSaveable(stateSaver = DestinationSaver) {
        mutableStateOf<SettingsDestination>(SettingsDestination.Main)
    }
    val goBack = { destination = destination.parent ?: SettingsDestination.Main }
    BackHandler(enabled = destination != SettingsDestination.Main, onBack = goBack)

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { message ->
        scope.launch { snackbarHostState.showSnackbar(message) }
    }
    val historyCleared = stringResource(R.string.settings_history_cleared)
    val thumbnailsCleared = stringResource(R.string.settings_thumbnails_cleared)
    val failed = stringResource(R.string.error_generic)
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(
                when (message) {
                    SettingsMessage.HISTORY_CLEARED -> historyCleared
                    SettingsMessage.THUMBNAILS_CLEARED -> thumbnailsCleared
                    SettingsMessage.FAILED -> failed
                },
            )
        }
    }

    val current = destination
    val title = when (current) {
        SettingsDestination.Main -> stringResource(R.string.settings_title)
        SettingsDestination.About -> stringResource(R.string.about_title)
        SettingsDestination.Licenses -> stringResource(R.string.about_licenses_title)
        is SettingsDestination.LicenseDetail ->
            OpenSourceLibraries.byId(current.libraryId)?.name ?: stringResource(R.string.about_licenses_title)
    }

    // The caller's modifier already carries the system-bar and bottom-navigation padding, so this
    // Scaffold and its top bar must not add window insets a second time.
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (current != SettingsDestination.Main) {
                        IconButton(onClick = goBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
    ) { innerPadding ->
        // Keeps each page's scroll position when navigating back to it.
        val stateHolder = rememberSaveableStateHolder()
        Crossfade(
            targetState = current,
            label = "settingsDestination",
            modifier = Modifier.padding(innerPadding),
        ) { page ->
            stateHolder.SaveableStateProvider(page.key) {
                when (page) {
                    SettingsDestination.Main -> {
                        val loaded = settings
                        if (loaded != null) {
                            MainSettings(
                                settings = loaded,
                                thumbnailBytes = thumbnailBytes,
                                viewModel = viewModel,
                                onOpenAbout = { destination = SettingsDestination.About },
                                onShowMessage = showMessage,
                            )
                        }
                    }
                    SettingsDestination.About -> AboutContent(
                        onOpenLicenses = { destination = SettingsDestination.Licenses },
                        onShowMessage = showMessage,
                    )
                    SettingsDestination.Licenses -> LicensesContent(
                        onLibraryClick = { destination = SettingsDestination.LicenseDetail(it.id) },
                    )
                    is SettingsDestination.LicenseDetail -> {
                        val library = OpenSourceLibraries.byId(page.libraryId)
                        if (library != null) {
                            LicenseDetailContent(library = library, onShowMessage = showMessage)
                        } else {
                            LicensesContent(
                                onLibraryClick = { destination = SettingsDestination.LicenseDetail(it.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainSettings(
    settings: AppSettings,
    thumbnailBytes: Long?,
    viewModel: SettingsViewModel,
    onOpenAbout: () -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val openFailed = stringResource(R.string.settings_open_failed)
    var confirmClearHistory by rememberSaveable { mutableStateOf(false) }

    PreferenceList(modifier = modifier) {
        // Appearance
        item(key = "h_appearance") { PreferenceSectionHeader(stringResource(R.string.settings_section_appearance)) }
        item(key = "theme") {
            ChoicePreference(
                title = stringResource(R.string.settings_theme),
                options = ThemeMode.entries,
                selected = settings.themeMode,
                optionLabel = { stringResource(it.labelRes()) },
                onSelect = viewModel::setThemeMode,
                icon = Icons.Outlined.Palette,
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            item(key = "dynamic_color") {
                SwitchPreference(
                    title = stringResource(R.string.settings_dynamic_color),
                    summary = stringResource(R.string.settings_dynamic_color_summary),
                    checked = settings.dynamicColor,
                    onCheckedChange = viewModel::setDynamicColor,
                    icon = Icons.Outlined.ColorLens,
                )
            }
        }

        // Reading
        item(key = "h_reading") { PreferenceSectionHeader(stringResource(R.string.settings_section_reading)) }
        item(key = "scroll_mode") {
            ChoicePreference(
                title = stringResource(R.string.settings_scroll_direction),
                options = ScrollMode.entries,
                selected = settings.scrollMode,
                optionLabel = { stringResource(it.labelRes()) },
                onSelect = viewModel::setScrollMode,
                icon = Icons.Outlined.SwapVert,
            )
        }
        item(key = "page_color") {
            ChoicePreference(
                title = stringResource(R.string.settings_page_color),
                options = PageColorMode.entries,
                selected = settings.pageColorMode,
                optionLabel = { stringResource(it.labelRes()) },
                onSelect = viewModel::setPageColorMode,
                icon = Icons.Outlined.InvertColors,
            )
        }
        item(key = "remember_page") {
            SwitchPreference(
                title = stringResource(R.string.settings_remember_last_page),
                summary = stringResource(R.string.settings_remember_last_page_summary),
                checked = settings.rememberLastPage,
                onCheckedChange = viewModel::setRememberLastPage,
                icon = Icons.Outlined.AutoStories,
            )
        }
        item(key = "keep_screen_on") {
            SwitchPreference(
                title = stringResource(R.string.settings_keep_screen_on),
                summary = stringResource(R.string.settings_keep_screen_on_summary),
                checked = settings.keepScreenOn,
                onCheckedChange = viewModel::setKeepScreenOn,
                icon = Icons.Outlined.Lightbulb,
            )
        }
        item(key = "volume_keys") {
            SwitchPreference(
                title = stringResource(R.string.settings_volume_keys),
                summary = stringResource(R.string.settings_volume_keys_summary),
                checked = settings.volumeKeysTurnPages,
                onCheckedChange = viewModel::setVolumeKeysTurnPages,
                icon = Icons.AutoMirrored.Outlined.VolumeUp,
            )
        }
        item(key = "page_number") {
            SwitchPreference(
                title = stringResource(R.string.settings_show_page_number),
                summary = stringResource(R.string.settings_show_page_number_summary),
                checked = settings.showPageNumberOverlay,
                onCheckedChange = viewModel::setShowPageNumberOverlay,
                icon = Icons.Outlined.Numbers,
            )
        }

        // Library & data
        item(key = "h_data") { PreferenceSectionHeader(stringResource(R.string.settings_section_data)) }
        item(key = "clear_history") {
            ClickablePreference(
                title = stringResource(R.string.settings_clear_history),
                summary = stringResource(R.string.settings_clear_history_summary),
                icon = Icons.Outlined.DeleteSweep,
                onClick = { confirmClearHistory = true },
            )
        }
        item(key = "clear_thumbnails") {
            val bytes = thumbnailBytes
            ClickablePreference(
                title = stringResource(R.string.settings_clear_thumbnails),
                summary = if (bytes != null && bytes > 0L) {
                    stringResource(
                        R.string.settings_clear_thumbnails_summary,
                        Formatter.formatShortFileSize(context, bytes),
                    )
                } else {
                    stringResource(R.string.settings_clear_thumbnails_summary_empty)
                },
                icon = Icons.Outlined.HideImage,
                onClick = viewModel::clearThumbnailCache,
            )
        }

        // Language: Android 13+ has a per-app language page in system settings.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            item(key = "h_language") { PreferenceSectionHeader(stringResource(R.string.settings_section_language)) }
            item(key = "language") {
                ClickablePreference(
                    title = stringResource(R.string.settings_language),
                    summary = stringResource(R.string.settings_language_summary),
                    icon = Icons.Outlined.Translate,
                    onClick = { if (!context.openAppLanguageSettings()) onShowMessage(openFailed) },
                )
            }
        }

        // About
        item(key = "h_about") { PreferenceSectionHeader(stringResource(R.string.settings_section_about)) }
        item(key = "about") {
            ClickablePreference(
                title = stringResource(R.string.settings_about),
                summary = stringResource(R.string.settings_about_summary),
                icon = Icons.Outlined.Info,
                onClick = onOpenAbout,
            )
        }
    }

    if (confirmClearHistory) {
        ConfirmDialog(
            title = stringResource(R.string.settings_clear_history_dialog_title),
            message = stringResource(R.string.settings_clear_history_dialog_message),
            confirmLabel = stringResource(R.string.settings_clear_history_confirm),
            icon = Icons.Outlined.DeleteSweep,
            onConfirm = viewModel::clearRecentHistory,
            onDismiss = { confirmClearHistory = false },
        )
    }
}

/** Opens the system per-app language page (Android 13+). Returns false if it could not. */
private fun Context.openAppLanguageSettings(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    return try {
        startActivity(
            Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", packageName, null)),
        )
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
