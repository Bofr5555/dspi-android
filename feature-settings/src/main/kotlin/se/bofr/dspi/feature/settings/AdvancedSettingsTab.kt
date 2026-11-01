// Port of DSPi-Console-b5:DSPi Console/DSPi_ConsoleApp.swift `AdvancedSettingsTab`.
// The "Onboarding (Developer)" section is not ported (onboarding is Phase 6).
package se.bofr.dspi.feature.settings

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import se.bofr.dspi.bridge.DspiBridge

@Composable
fun AdvancedSettingsTab(bridge: DspiBridge, appSettings: AppSettingsStore) {
    val s by appSettings.settings.collectAsState()
    val connected = bridge.status.collectAsState().value.connected
    SettingsForm {
        item {
            SettingsSection("Channel Names") {
                SettingRow("Reset all channel names to factory defaults.", null, enabled = connected) {
                    OutlinedButton(onClick = { bridge.resetChannelNames() }, enabled = connected) { Text("Reset") }
                }
            }
        }
        item {
            SettingsSection("Diagnostics") {
                SwitchRow("Show Debug Information", "Display additional diagnostic data in the UI", s.showDebugInfo) { v ->
                    appSettings.update { it.copy(showDebugInfo = v) }
                }
            }
        }
    }
}
