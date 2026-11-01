// Port of DSPi-Console-b5:DSPi Console/DSPi_ConsoleApp.swift `SettingsSaveBar`,
// with a tap-to-expand list of what is pending
// (as the Windows Settings InfoBar lists its changes).
package se.bofr.dspi.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import se.bofr.dspi.ui.MacColors

/**
 * Shown at the bottom of every Settings page while anything is pending
 * (global draft, live output config not yet flashed, CS preview). Save mints
 * SAVE_OUTPUT_CONFIG / CS_SAVE, Revert HARDWARE_APPLY (E1b).
 */
@Composable
internal fun SettingsSaveBar(coordinator: SettingsSaveCoordinator) {
    val bridge = coordinator.bridge
    val state by bridge.state.collectAsState()
    val outputDirty by bridge.outputConfigDirty.collectAsState()
    val edited by coordinator.globalUserEdited.collectAsState()
    val csUnsaved by bridge.cs.unsaved.collectAsState()
    val csBusy by coordinator.csBusy.collectAsState()
    val saving by coordinator.saving.collectAsState()
    val result by coordinator.lastResult.collectAsState()
    val connected = bridge.status.collectAsState().value.connected
    @Suppress("UNUSED_EXPRESSION")
    listOf(outputDirty, edited, csUnsaved) // recompose on any of them

    val hasPending = coordinator.hasPendingChanges(state)
    val failures = result?.failures.orEmpty()
    if (!hasPending && failures.isEmpty()) return

    var expanded by rememberSaveable { mutableStateOf(false) }
    val csOnly = coordinator.csOnly(state)
    val subtitle = if (csOnly) {
        "Your controls are live now; saving keeps them across a reboot."
    } else {
        "Saving writes these settings to " +
            "the device's flash."
    }
    val lines = coordinator.pendingSummary(state)

    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        HorizontalDivider()
        if (failures.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                failures.forEach { StatusLine(it, true) }
            }
        }
        if (hasPending) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("●", color = MacColors.orange)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Unsaved changes" + if (lines.size > 1) " (${lines.size})" else "", style = MaterialTheme.typography.bodyMedium)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (csBusy || saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            if (expanded) {
                Column(Modifier.padding(start = 32.dp, end = 16.dp, bottom = 4.dp)) {
                    lines.forEach {
                        Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.weight(1f))
                // Revert stays available offline (the global draft is app-side);
                // a CS revert is a device operation.
                OutlinedButton(
                    onClick = { coordinator.onRevert() },
                    enabled = !csBusy && !saving && !(csOnly && !connected)
                ) { Text("Revert") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { coordinator.onSave() }, enabled = !csBusy && !saving && connected) { Text("Save") }
            }
        }
    }
}
