package se.bofr.dspi.feature.tools

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.ui.DspiRoutes
import se.bofr.dspi.ui.PlaceholderScreen

/**
 * Entry point of :feature-tools (route [DspiRoutes.TOOLS]).
 * The signature is the contract with :app — keep it.
 * WAVE 0 placeholder; package TOOLS implements it.
 */
@Composable
fun ToolsScreen(bridge: DspiBridge, onNavigate: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    PlaceholderScreen(title = "Tools", owner = "TOOLS", ports = "LoudnessView", modifier = modifier) {
    }
}
