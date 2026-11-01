package se.bofr.dspi.feature.home

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.ui.DspiRoutes
import se.bofr.dspi.ui.PlaceholderScreen

/**
 * Entry point of :feature-home (route [DspiRoutes.HOME]).
 * The signature is the contract with :app — keep it.
 * WAVE 0 placeholder; package HOME implements it.
 */
@Composable
fun HomeScreen(bridge: DspiBridge, onNavigate: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    PlaceholderScreen(title = "DSPi", owner = "HOME", ports = "ContentView.swift (HomeScreen/ChannelScreen)", modifier = modifier) {
        val state by bridge.state.collectAsStateWithLifecycle()
        val status by bridge.status.collectAsStateWithLifecycle()
        Text(if (status.connected) "Connected: ${bridge.platformName} fw ${bridge.firmwareVersion}" else "No device")
        Text("Master volume: ${state.masterVolumeDb} dB")
        listOf(
            "Channel" to DspiRoutes.channel(2),
            "Graph" to DspiRoutes.GRAPH,
            "Matrix" to DspiRoutes.MATRIX,
            "Tools" to DspiRoutes.TOOLS,
            "Presets" to DspiRoutes.PRESETS,
            "Analysis" to DspiRoutes.ANALYSIS,
            "Settings" to DspiRoutes.SETTINGS,
            "Firmware" to DspiRoutes.FIRMWARE
        ).forEach { (label, route) ->
            Button(onClick = { onNavigate(route) }) { Text(label) }
        }
    }
}
