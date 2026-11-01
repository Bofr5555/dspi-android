package se.bofr.dspi.feature.firmware

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.ui.DspiRoutes
import se.bofr.dspi.ui.PlaceholderScreen

/**
 * Entry point of :feature-firmware (route [DspiRoutes.FIRMWARE]).
 * The signature is the contract with :app — keep it.
 * WAVE 0 placeholder; package FIRMWARE implements it.
 */
@Composable
fun FirmwareUpdateView(bridge: DspiBridge, onNavigate: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    PlaceholderScreen(title = "Firmware Update", owner = "FIRMWARE", ports = "FirmwareUpdateView.swift", modifier = modifier) {
    }
}
