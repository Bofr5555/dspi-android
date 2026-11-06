// Port of DSPi-Console release/v1.1.6 `NetworkingSettingsView.swift`, the
// client half only ("Other Hubs": Find hubs automatically, the hub rows with
// their Open/Private badge, Add by Address…). The hub half (This Hub, Paired
// Clients, sharing this phone's DSPi) is not ported: the Android app is a
// Link client only (owner decisions D1/D5). Android additions: the opt-in
// switch (D2), the client name (D7), paired hubs with Forget, and the
// trusted-network notice.
package se.bofr.dspi.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import se.bofr.dspi.ui.MacColors
import se.bofr.dspi.ui.WritesExempt
import se.bofr.dspi.ui.link.NetworkHubRow
import se.bofr.dspi.ui.link.NetworkingController

/** macOS LinkDiscovery.defaultPort. */
private const val DEFAULT_PORT = 11915

/** Settings › Networking (the DSPi Link client). App preferences only: usable without a device. */
@Composable
fun NetworkingSettingsTab(controller: NetworkingController) = WritesExempt {
    val s by controller.state.collectAsState()
    DisposableEffect(controller) {
        controller.setVisible(true)
        onDispose { controller.setVisible(false) }
    }
    var adding by remember { mutableStateOf(false) }
    SettingsForm {
        item {
            SettingsSection(
                "DSPi Link",
                footer = "Control a DSPi plugged into a Mac running DSPi Console, or wired to a DSPi bridge, from this phone. " +
                    "DSPi Link traffic is not encrypted: use it only on networks you trust. To reach a hub from elsewhere, use a VPN " +
                    "such as Tailscale."
            ) {
                FormSwitchRow("Control DSPi devices over the network", null, s.enabled) { controller.setEnabled(it) }
                if (s.status.isNotEmpty()) {
                    SettingRow("Connection", s.status) {
                        if (s.connected) TextButton(onClick = { controller.disconnect() }) { Text("Disconnect") }
                    }
                }
            }
        }
        if (!s.enabled) return@SettingsForm
        item {
            SettingsSection("This Phone", footer = "Hubs list this name among their clients.") {
                var draft by remember(s.clientName) { mutableStateOf(s.clientName) }
                OutlinedTextField(
                    value = draft,
                    onValueChange = {
                        draft = it.take(64)
                        controller.setClientName(draft.trim())
                    },
                    label = { Text("Name") },
                    placeholder = { Text(s.defaultClientName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
        item {
            SettingsSection("Other Hubs", footer = "Tap a hub to control its DSPi from this phone.") {
                FormSwitchRow("Find hubs automatically", null, s.findAutomatically) { controller.setFindAutomatically(it) }
                if (s.findAutomatically && s.browseError != null) {
                    FormCaption("⚠ ${s.browseError}", MacColors.orange)
                }
                s.hubs.forEach { hub -> HubRow(hub, controller) }
                if (s.hubs.isEmpty()) {
                    if (s.findAutomatically) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.width(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Looking for hubs…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        FormCaption("No hubs added.")
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { adding = true }) { Text("Add by Address…") }
                }
            }
        }
        if (s.paired.isNotEmpty()) {
            item {
                SettingsSection("Paired Hubs", footer = "Forgetting a hub deletes its pairing; it asks for a PIN again next time.") {
                    s.paired.forEach { p ->
                        SettingRow(p.name.ifEmpty { p.id }) {
                            TextButton(onClick = { controller.forget(p.id) }) { Text("Forget") }
                        }
                    }
                }
            }
        }
    }
    if (adding) AddHubDialog(controller, onDismiss = { adding = false })
}

@Composable
private fun HubRow(hub: NetworkHubRow, controller: NetworkingController) {
    SettingRow(
        title = hub.name,
        detail = hub.detail + if (hub.active) " · connected" else "",
        onClick = { if (!hub.active) controller.connect(hub.id) }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (hub.open) Pill("Open", MacColors.green) else Pill("Private", MacColors.blue)
            if (hub.manual) {
                TextButton(onClick = { controller.removeManual(hub.id) }) { Text("Remove") }
            }
        }
    }
}

/** macOS `addHubPopover`. */
@Composable
private fun AddHubDialog(controller: NetworkingController, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf("") }
    val valid = controller.isValidAddress(draft)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a Hub by Address") },
        text = {
            Column {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("192.168.1.20 or studio.local") },
                    singleLine = true
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "Hostname or IP address. Add :port if the hub doesn't use $DEFAULT_PORT.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                if (controller.addManual(draft)) onDismiss()
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
