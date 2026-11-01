// @Previews of :feature-graph on FakeDspiCore (debug source set only, so the
// release build never links the fake). The fake is seeded from the V32
// fixture and starts connected (demo mode), with its animated RTA.
package se.bofr.dspi.feature.graph

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.bridge.DspiBridgeImpl
import se.bofr.dspi.fake.FakeDspiCore
import se.bofr.dspi.ui.DspiTheme
import se.bofr.dspi.ui.settings.AppSettings
import se.bofr.dspi.ui.settings.InMemoryAppSettingsStore
import se.bofr.dspi.ui.settings.LocalAppSettings

@Composable
private fun rememberFakeBridge(): DspiBridge {
    val scope = rememberCoroutineScope()
    return remember { DspiBridgeImpl(FakeDspiCore.seeded(animate = true), scope).also { it.scanDevices() } }
}

@Composable
private fun PreviewFrame(content: @Composable () -> Unit) {
    DspiTheme(darkTheme = true) {
        Column(Modifier.fillMaxSize().background(Color(0xFF1A1A1C)).padding(8.dp)) { content() }
    }
}

@Preview(name = "Dashboard graph", widthDp = 392, heightDp = 320)
@Composable
private fun GraphViewDashboardPreview() {
    PreviewFrame { GraphView(rememberFakeBridge()) }
}

@Preview(name = "Channel page: OUT1 editable", widthDp = 392, heightDp = 320)
@Composable
private fun GraphViewChannelPreview() {
    PreviewFrame {
        // App id 2 = output 1.
        GraphView(rememberFakeBridge(), channels = setOf(2), editable = true)
        GraphResizeHandle()
    }
}

@Preview(name = "Phase and spectrum", widthDp = 392, heightDp = 320)
@Composable
private fun GraphViewPhasePreview() {
    val store = remember { InMemoryAppSettingsStore(AppSettings(showPhase = true, rtaDashboardSelectionKey = "in:0,1")) }
    CompositionLocalProvider(LocalAppSettings provides store) {
        PreviewFrame { GraphView(rememberFakeBridge(), channels = setOf(0), editable = true) }
    }
}

@Preview(name = "GraphScreen (landscape)", widthDp = 800, heightDp = 392)
@Composable
private fun GraphScreenPreview() {
    DspiTheme(darkTheme = true) { GraphScreen(rememberFakeBridge(), onNavigate = {}, onBack = {}) }
}

@Preview(name = "Band chip and shape page", widthDp = 392, heightDp = 300)
@Composable
private fun PeqBandHudPreview() {
    val selection = remember { PeqGraphSelection() }
    CompositionLocalProvider(LocalPeqGraphSelection provides selection) {
        PreviewFrame {
            val bridge = rememberFakeBridge()
            GraphView(bridge, channels = setOf(2), editable = true)
            // Select the first band so its chip shows.
            remember { selection.setSelected(listOf(0)) }
        }
    }
}
