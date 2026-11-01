package se.bofr.dspi.feature.matrix

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.ui.DspiRoutes
import se.bofr.dspi.ui.PlaceholderScreen

/**
 * Entry point of :feature-matrix (route [DspiRoutes.MATRIX]).
 * The signature is the contract with :app — keep it.
 * WAVE 0 placeholder; package MATRIX implements it.
 */
@Composable
fun MatrixMixerView(bridge: DspiBridge, onNavigate: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    PlaceholderScreen(title = "Matrix Mixer", owner = "MATRIX", ports = "MatrixMixerView.swift", modifier = modifier) {
    }
}
