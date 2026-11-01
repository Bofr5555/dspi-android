// @Previews of the Settings pages over FakeDspiCore (debug source set only:
// :fake-core is a debugImplementation dependency and never ships).
package se.bofr.dspi.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.bridge.DspiBridgeImpl
import se.bofr.dspi.fake.FakeDspiCore
import se.bofr.dspi.ui.DspiTheme
import se.bofr.dspi.ui.settings.InMemoryAppSettingsStore

/** A bridge over the demo FakeDspiCore (RP2350, 8 in / 9 out); it connects itself on scan. */
@Composable
private fun rememberPreviewBridge(): DspiBridge = remember {
    DspiBridgeImpl(FakeDspiCore(), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)).also { it.scanDevices() }
}

@Composable
private fun PreviewPage(page: SettingsCategory?) {
    DspiTheme {
        SettingsView(rememberPreviewBridge(), InMemoryAppSettingsStore(), onNavigate = {}, onBack = {}, initialPage = page)
    }
}

@Preview(name = "Settings list (phone)", widthDp = 380, heightDp = 780)
@Composable
private fun SettingsListPreview() = PreviewPage(null)

@Preview(name = "Settings (tablet)", widthDp = 1000, heightDp = 700)
@Composable
private fun SettingsTabletPreview() = PreviewPage(SettingsCategory.OUTPUT_ASSIGNMENT)

@Preview(name = "Overview", widthDp = 380, heightDp = 780)
@Composable
private fun OverviewPreview() = PreviewPage(SettingsCategory.OVERVIEW)

@Preview(name = "Global Parameters", widthDp = 380, heightDp = 780)
@Composable
private fun GlobalPreview() = PreviewPage(SettingsCategory.GLOBAL_PARAMS)

@Preview(name = "Outputs", widthDp = 380, heightDp = 780)
@Composable
private fun OutputsPreview() = PreviewPage(SettingsCategory.OUTPUT_ASSIGNMENT)

@Preview(name = "I2S Configuration", widthDp = 380, heightDp = 780)
@Composable
private fun I2sPreview() = PreviewPage(SettingsCategory.I2S_CONFIG)

@Preview(name = "Inputs", widthDp = 380, heightDp = 780)
@Composable
private fun InputsPreview() = PreviewPage(SettingsCategory.SPDIF_INPUT)

@Preview(name = "Control Interfaces", widthDp = 380, heightDp = 780)
@Composable
private fun ControlInterfacesPreview() = PreviewPage(SettingsCategory.CONTROL_INTERFACES)

@Preview(name = "Control Surfaces", widthDp = 380, heightDp = 780)
@Composable
private fun ControlSurfacesPreview() = PreviewPage(SettingsCategory.CONTROL_SURFACES)

@Preview(name = "Graphing", widthDp = 380, heightDp = 780)
@Composable
private fun GraphingPreview() = PreviewPage(SettingsCategory.GRAPHING)

@Preview(name = "Spectrum Analyser", widthDp = 380, heightDp = 780)
@Composable
private fun SpectrumPreview() = PreviewPage(SettingsCategory.SPECTRUM)

@Preview(name = "About", widthDp = 380, heightDp = 780)
@Composable
private fun AboutPreview() = PreviewPage(SettingsCategory.GENERAL)
