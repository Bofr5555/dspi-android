package se.bofr.dspi.feature.home

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.ui.DspiRoutes
import se.bofr.dspi.ui.PlaceholderScreen

/**
 * Entry point for [DspiRoutes.CHANNEL]: one channel's page (macOS
 * ContentView `OutputChannelDetail` / input header + FilterList).
 * [ch] is an APP channel id. WAVE 0 placeholder; package HOME implements it.
 */
@Composable
fun ChannelScreen(bridge: DspiBridge, ch: Int, onNavigate: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = bridge.channelDescriptor(ch).ifEmpty {
            "Channel $ch"
        },
        owner = "HOME",
        ports = "ContentView.swift",
        modifier = modifier
    ) {
        Text(bridge.channelName(ch))
    }
}
