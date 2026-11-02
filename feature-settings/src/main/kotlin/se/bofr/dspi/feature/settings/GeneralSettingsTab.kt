// Port of DSPi-Console-b5:DSPi Console/DSPi_ConsoleApp.swift `GeneralSettingsTab` ("About").
// Android additions: the port credit and the Licence section (LicensesView, NOTICE.md).
package se.bofr.dspi.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The support links, as on macOS (title, URL, brand colour). */
internal val SUPPORT_LINKS = listOf(
    Triple("YouTube", "https://youtube.com/weeblabs", Color.Red),
    Triple("GitHub", "https://github.com/weeblabs", Color(0xFFB8B8B8)),
    Triple("Discord", "https://discord.gg/RCyqxAQ5xS", Color(0xFF5865F2)),
    Triple("Patreon", "https://patreon.com/weeblabs", Color(0xFFFF9500)),
    Triple("Ko-fi", "https://ko-fi.com/weeblabs", Color(0xFF719EBC))
)

/** The About page's own sub-pages: null = About, [LICENSES] = overview, else a bundled text's asset name. */
private const val LICENSES = "licenses"

@Composable
fun GeneralSettingsTab() {
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = page != null) { page = if (page == LICENSES) null else LICENSES }
    when (val p = page) {
        null -> AboutPage(onOpenLicenses = { page = LICENSES })
        LICENSES -> LicensesView(onOpenText = { page = it.asset })
        else -> {
            val text = (THIRD_PARTY.mapNotNull { it.text } + GPL3).firstOrNull { it.asset == p }
            if (text == null) page = LICENSES else LicenseTextView(text, onBack = { page = LICENSES })
        }
    }
}

@Composable
private fun AboutPage(onOpenLicenses: () -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "—"
    }
    SettingsForm {
        item {
            SettingsSection(null) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("🔊", fontSize = 32.sp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("DSPi Console", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "USB Audio DSP Controller",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Version $version",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Made with love by Weeb Labs",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Android port by Bofr5555",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        item {
            SettingsSection("Links & Support") {
                FormCaption(
                    "DSPi Firmware and Console are free, open-source software that I develop in my spare time. " +
                        "Contributions of any kind - code, feedback, funding, or otherwise - are always immensely appreciated."
                )
                SUPPORT_LINKS.forEach { (title, url, color) ->
                    SettingRow(title, null, onClick = { runCatching { uri.openUri(url) } }) {
                        Text("●", color = color)
                    }
                }
            }
        }
        item {
            SettingsSection("Licence") {
                SettingRow("Licences", "GPL-3.0 with additional terms · third-party notices", onClick = onOpenLicenses)
                SettingRow("Source code", APP_SOURCE_URL, onClick = { runCatching { uri.openUri(APP_SOURCE_URL) } })
            }
        }
    }
}
