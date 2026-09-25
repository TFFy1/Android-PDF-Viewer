package io.github.tffy1.pdfviewer.ui.about

import android.content.res.Resources
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.tffy1.pdfviewer.BuildConfig
import io.github.tffy1.pdfviewer.R
import io.github.tffy1.pdfviewer.ui.settings.ClickablePreference
import io.github.tffy1.pdfviewer.ui.settings.PreferenceList
import io.github.tffy1.pdfviewer.ui.theme.BrandGradientBottom
import io.github.tffy1.pdfviewer.ui.theme.BrandGradientTop
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * About page: identity, version, privacy promise and links.
 * [onShowMessage] reports problems (e.g. no browser installed) to the host's snackbar.
 */
@Composable
fun AboutContent(
    onOpenLicenses: () -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val linkFailed = stringResource(R.string.about_link_open_failed)
    PreferenceList(modifier = modifier) {
        item(key = "header") { AboutHeader() }
        item(key = "privacy") { PrivacyCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        item(key = "source") {
            ClickablePreference(
                title = stringResource(R.string.about_source_code),
                summary = stringResource(R.string.about_source_code_summary),
                icon = Icons.Outlined.Code,
                trailing = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                onClick = { if (!context.openExternalUrl(SOURCE_CODE_URL)) onShowMessage(linkFailed) },
            )
        }
        item(key = "licenses") {
            ClickablePreference(
                title = stringResource(R.string.about_licenses),
                summary = stringResource(R.string.about_licenses_summary),
                icon = Icons.Outlined.Gavel,
                onClick = onOpenLicenses,
            )
        }
    }
}

@Composable
private fun AboutHeader(modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 24.dp),
    ) {
        AppIconBadge(size = 96.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.about_tagline),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
    }
}

/** The launcher icon drawn in Compose (adaptive-icon XML cannot be loaded by painterResource). */
@Composable
private fun AppIconBadge(size: Dp, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(percent = 30))
            .background(Brush.verticalGradient(listOf(BrandGradientTop, BrandGradientBottom))),
    ) {
        // The adaptive foreground is 108 units wide but only the middle 72 are meant to be seen.
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.requiredSize(size * 1.5f),
        )
    }
}

@Composable
private fun PrivacyCard(modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp)) {
            Icon(Icons.Outlined.Shield, contentDescription = null)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = stringResource(R.string.about_privacy_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.about_privacy_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** List of bundled open-source components; tapping one shows its license text. */
@Composable
fun LicensesContent(
    onLibraryClick: (OpenSourceLibrary) -> Unit,
    modifier: Modifier = Modifier,
) {
    PreferenceList(modifier = modifier) {
        items(OpenSourceLibraries.all, key = { it.id }) { library ->
            val licenseNames = library.licenses.joinToString(" / ") { it.spdxId }
            ClickablePreference(
                title = library.name,
                summary = "${library.author}\n$licenseNames",
                onClick = { onLibraryClick(library) },
            )
        }
    }
}

/** Full license text(s) for one library, read from res/raw off the main thread. */
@Composable
fun LicenseDetailContent(
    library: OpenSourceLibrary,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val linkFailed = stringResource(R.string.about_link_open_failed)
    val textsState by produceState<LicenseTexts>(LicenseTexts.Loading, library.id) {
        value = withContext(Dispatchers.IO) {
            try {
                LicenseTexts.Loaded(
                    library.licenses.map { license ->
                        LicenseTextFormatter.reflow(context.resources.readRawText(license.textRes()))
                    },
                )
            } catch (e: IOException) {
                LicenseTexts.Failed
            } catch (e: Resources.NotFoundException) {
                LicenseTexts.Failed
            }
        }
    }

    val texts = textsState
    PreferenceList(modifier = modifier) {
        item(key = "header") {
            Column {
                Text(
                    text = library.author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
                ClickablePreference(
                    title = stringResource(R.string.about_license_website),
                    summary = library.website,
                    icon = Icons.Outlined.Language,
                    trailing = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                    onClick = { if (!context.openExternalUrl(library.website)) onShowMessage(linkFailed) },
                )
            }
        }
        when (val state = texts) {
            LicenseTexts.Loading -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            LicenseTexts.Failed -> item(key = "error") {
                Text(
                    text = stringResource(R.string.about_license_load_failed),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }
            is LicenseTexts.Loaded -> {
                library.licenses.forEachIndexed { index, license ->
                    item(key = license.spdxId) {
                        LicenseTextBlock(
                            title = stringResource(license.nameRes()),
                            text = state.texts[index],
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LicenseTextBlock(title: String, text: String, modifier: Modifier = Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        HorizontalDivider()
        Text(title, style = MaterialTheme.typography.titleMedium)
        SelectionContainer {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private sealed interface LicenseTexts {
    data object Loading : LicenseTexts
    data object Failed : LicenseTexts
    data class Loaded(val texts: List<String>) : LicenseTexts
}

@StringRes
private fun OpenSourceLicense.nameRes(): Int = when (this) {
    OpenSourceLicense.APACHE_2_0 -> R.string.about_license_apache_2_0
    OpenSourceLicense.BSD_3_CLAUSE -> R.string.about_license_bsd_3_clause
}

@RawRes
private fun OpenSourceLicense.textRes(): Int = when (this) {
    OpenSourceLicense.APACHE_2_0 -> R.raw.license_apache_2_0
    OpenSourceLicense.BSD_3_CLAUSE -> R.raw.license_bsd_3_clause
}

private fun Resources.readRawText(@RawRes id: Int): String =
    openRawResource(id).bufferedReader(Charsets.UTF_8).use { it.readText() }
