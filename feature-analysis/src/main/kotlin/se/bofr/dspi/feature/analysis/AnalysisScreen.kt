package se.bofr.dspi.feature.analysis

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.ui.DspiRoutes
import se.bofr.dspi.ui.PlaceholderScreen

/**
 * Entry point of :feature-analysis (route [DspiRoutes.ANALYSIS]).
 * The signature is the contract with :app — keep it.
 * WAVE 0 placeholder; package ANALYSIS implements it.
 */
@Composable
fun AnalysisScreen(bridge: DspiBridge, onNavigate: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    PlaceholderScreen(title = "Analysis", owner = "ANALYSIS", ports = "TestSignalsView.swift", modifier = modifier) {
    }
}
