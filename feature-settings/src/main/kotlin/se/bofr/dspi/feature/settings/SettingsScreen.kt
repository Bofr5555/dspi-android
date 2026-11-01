package se.bofr.dspi.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.ui.DspiRoutes
import se.bofr.dspi.ui.PlaceholderScreen

/**
 * Entry point of :feature-settings (route [DspiRoutes.SETTINGS]).
 * The signature is the contract with :app — keep it.
 * WAVE 0 placeholder; package SETTINGS implements it.
 */
@Composable
fun SettingsScreen(bridge: DspiBridge, onNavigate: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = "Settings",
        owner = "SETTINGS",
        ports = "DSPi_ConsoleApp.swift SettingsView + *SettingsTab",
        modifier = modifier
    ) {
    }
}
