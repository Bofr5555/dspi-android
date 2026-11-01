package se.bofr.dspi.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.ui.DspiRoutes

/**
 * Entry point of :feature-home (route [DspiRoutes.HOME]): the macOS main
 * window ([ContentView]). The signature is the contract with :app — keep it.
 * [onBack] is unused: home is the start destination.
 */
@Composable
fun HomeScreen(bridge: DspiBridge, onNavigate: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    ContentView(bridge, onNavigate, modifier)
}
