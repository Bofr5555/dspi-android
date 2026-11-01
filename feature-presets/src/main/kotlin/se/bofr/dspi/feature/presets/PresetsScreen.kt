package se.bofr.dspi.feature.presets

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.ui.DspiRoutes
import se.bofr.dspi.ui.PlaceholderScreen

/**
 * Entry point of :feature-presets (route [DspiRoutes.PRESETS]).
 * The signature is the contract with :app — keep it.
 * WAVE 0 placeholder; package PRESETS implements it.
 */
@Composable
fun PresetsScreen(bridge: DspiBridge, onNavigate: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    PlaceholderScreen(title = "Presets & Files", owner = "PRESETS", ports = "ContentView preset menu", modifier = modifier) {
    }
}
