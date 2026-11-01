package se.bofr.dspi.feature.tools

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.ui.DspiRoutes
import se.bofr.dspi.ui.PlaceholderScreen

/**
 * Entry point for [DspiRoutes.LIMITER] (macOS OutputLimiterView.swift; opened
 * from an output channel page). [out] is an output index.
 * WAVE 0 placeholder; package TOOLS implements it.
 */
@Composable
fun OutputLimiterView(bridge: DspiBridge, out: Int, onNavigate: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    PlaceholderScreen(title = "Output Limiter", owner = "TOOLS", ports = "OutputLimiterView.swift", modifier = modifier) {
        Text("Output ${out + 1}: ${if (bridge.limiterEnabled(out)) "on" else "off"}")
    }
}
