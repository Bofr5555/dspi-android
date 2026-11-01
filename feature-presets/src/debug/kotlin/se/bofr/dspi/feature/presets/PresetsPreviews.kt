// @Previews of :feature-presets against FakeDspiCore (PACKAGES.md Wave B DoD).
// Debug source set only: :fake-core is a debugImplementation dependency, never
// part of a release build.
package se.bofr.dspi.feature.presets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.Dispatchers
import se.bofr.dspi.bridge.DspiBridgeImpl
import se.bofr.dspi.fake.FakeDspiCore
import se.bofr.dspi.feature.presets.autoeq.AutoEQBrowser
import se.bofr.dspi.feature.presets.autoeq.AutoEQManager
import se.bofr.dspi.feature.presets.autoeq.FavoriteProfile
import se.bofr.dspi.feature.presets.autoeq.InMemoryFavoritesStore
import se.bofr.dspi.ui.DspiTheme
import se.bofr.dspi.ui.alert.AlertButton
import se.bofr.dspi.ui.alert.AlertButtonRole
import se.bofr.dspi.ui.alert.AlertCheckbox
import se.bofr.dspi.ui.alert.AlertStyle
import se.bofr.dspi.ui.alert.ConsoleAlert
import se.bofr.dspi.ui.alert.ConsoleAlertDialog

private const val PREVIEW_DB = """{"version":1,"generatedAt":"2026-01-18T05:41:39.649194+00:00","entryCount":3,"entries":[
{"id":"oratory1990/Sennheiser HD 650","manufacturer":"Sennheiser","model":"HD 650","source":"oratory1990","formFactor":"over-ear","preamp":-6.4,
 "filters":[{"type":"lowShelf","freq":105.0,"q":0.7,"gain":6.5},{"type":"peaking","freq":2000.0,"q":1.2,"gain":-2.0}]},
{"id":"crinacle/Moondrop Blessing 2","manufacturer":"Moondrop","model":"Blessing 2","source":"crinacle","formFactor":"in-ear","preamp":-3.1,
 "filters":[{"type":"peaking","freq":4500.0,"q":2.0,"gain":-3.0}]},
{"id":"rtings/Apple AirPods Pro","manufacturer":"Apple","model":"AirPods Pro","source":"rtings","formFactor":"earbud","preamp":-2.0,"filters":[]}]}"""

@Composable
private fun rememberPreviewController(dirty: Boolean = false): PresetsController {
    val scope = rememberCoroutineScope()
    return remember {
        val core = FakeDspiCore.seeded()
        val bridge = DspiBridgeImpl(core, scope).also { it.scanDevices() }
        if (dirty) bridge.setBypass(true)
        val manager =
            AutoEQManager({
                PREVIEW_DB
            }, null, InMemoryFavoritesStore(listOf(FavoriteProfile("crinacle/Moondrop Blessing 2", "Moondrop Blessing 2")))).also {
                it.loadDatabase()
            }
        PresetsController(bridge, scope, manager, Dispatchers.Default)
    }
}

@Preview(name = "Presets — phone", widthDp = 400, heightDp = 1600, showBackground = true)
@Composable
private fun PresetsPhonePreview() {
    DspiTheme { PresetsView(rememberPreviewController(), PresetFileRequests(), onNavigate = {}, onBack = {}) }
}

@Preview(name = "Presets — tablet", widthDp = 1000, heightDp = 900, showBackground = true)
@Composable
private fun PresetsTabletPreview() {
    DspiTheme { PresetsView(rememberPreviewController(dirty = true), PresetFileRequests(), onNavigate = {}, onBack = {}) }
}

@Preview(name = "AutoEQ browser", widthDp = 400, heightDp = 700, showBackground = true)
@Composable
private fun AutoEQBrowserPreview() {
    val c = rememberPreviewController()
    DspiTheme { AutoEQBrowser(c.autoEQ, onApply = { true }, onDismiss = {}) }
}

@Preview(name = "Unsaved Changes alert", widthDp = 400, heightDp = 600)
@Composable
private fun UnsavedChangesPreview() {
    val diff = PresetDiff(listOf("Preamp L: -3.0 dB → 0.0 dB", "3 bands changed on USB L", "SPDIF 1 L gain: 0.0 dB → -6.0 dB"))
    DspiTheme { ConsoleAlertDialog(PresetAlerts.unsavedChangesAlert(diff) {}, onButton = { _, _ -> }, onDismiss = {}) }
}

@Preview(name = "Import Device Configuration alert", widthDp = 400, heightDp = 700)
@Composable
private fun ImportConfigurationPreview() {
    val alert =
        ConsoleAlert(
            "Import Device Configuration",
            PresetDocumentFiles.importOptionsText("RP2040", "1.1.5", "2026-10-01T12:00:00Z", "RP2350"),
            AlertStyle.INFORMATIONAL,
            listOf(AlertButton("Import", AlertButtonRole.DEFAULT), AlertButton("Cancel", AlertButtonRole.CANCEL)),
            checkboxes =
            listOf(
                AlertCheckbox(FileMenuActions.VOLUMES, "Volume levels (master and listening volume)"),
                AlertCheckbox(FileMenuActions.HARDWARE, "Hardware I/O (GPIO pins, clocks, ADAT, inputs, output limiters)")
            )
        )
    DspiTheme { ConsoleAlertDialog(alert, onButton = { _, _ -> }, onDismiss = {}) }
}
