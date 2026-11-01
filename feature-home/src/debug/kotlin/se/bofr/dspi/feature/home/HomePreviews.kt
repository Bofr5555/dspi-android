// @Previews on FakeDspiCore (debug source set only; coordinator decision E2).
package se.bofr.dspi.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.Dispatchers
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.bridge.DspiBridgeImpl
import se.bofr.dspi.fake.FakeDspiCore
import se.bofr.dspi.ui.DspiTheme

/** A bridge over the seeded fake core, connected to its demo device. */
@Composable
internal fun rememberPreviewBridge(): DspiBridge {
    val scope = rememberCoroutineScope()
    return remember {
        DspiBridgeImpl(FakeDspiCore.seeded(), scope, ffi = Dispatchers.Unconfined, main = Dispatchers.Unconfined).also { it.scanDevices() }
    }
}

@Preview(name = "Home (phone)", widthDp = 380, heightDp = 1600, showBackground = true)
@Composable
private fun HomePhonePreview() {
    DspiTheme(darkTheme = true) { HomeScreen(rememberPreviewBridge(), onNavigate = {}, onBack = {}) }
}

@Preview(name = "Home (tablet)", widthDp = 1200, heightDp = 900, showBackground = true)
@Composable
private fun HomeTabletPreview() {
    DspiTheme(darkTheme = true) { HomeScreen(rememberPreviewBridge(), onNavigate = {}, onBack = {}) }
}

@Preview(name = "Input channel", widthDp = 380, heightDp = 1200, showBackground = true)
@Composable
private fun InputChannelPreview() {
    DspiTheme(darkTheme = true) { ChannelScreen(rememberPreviewBridge(), ch = 0, onNavigate = {}, onBack = {}) }
}

@Preview(name = "Output channel", widthDp = 380, heightDp = 1400, showBackground = true)
@Composable
private fun OutputChannelPreview() {
    DspiTheme(darkTheme = true) { ChannelScreen(rememberPreviewBridge(), ch = 2, onNavigate = {}, onBack = {}) }
}

@Preview(name = "Dashboard", widthDp = 900, heightDp = 900, showBackground = true)
@Composable
private fun DashboardPreview() {
    DspiTheme(darkTheme = true) { DashboardOverview(rememberPreviewBridge(), HomePreferences.inMemory()) }
}
