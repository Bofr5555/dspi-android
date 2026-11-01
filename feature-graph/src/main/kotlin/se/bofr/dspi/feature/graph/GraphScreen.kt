package se.bofr.dspi.feature.graph

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.ui.DspiRoutes
import se.bofr.dspi.ui.PlaceholderScreen

/**
 * Entry point of :feature-graph (route [DspiRoutes.GRAPH]).
 * The signature is the contract with :app — keep it.
 * WAVE 0 placeholder; package GRAPH implements it.
 */
@Composable
fun GraphScreen(bridge: DspiBridge, onNavigate: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    PlaceholderScreen(title = "Response Graph", owner = "GRAPH", ports = "GraphView.swift", modifier = modifier) {
    }
}
