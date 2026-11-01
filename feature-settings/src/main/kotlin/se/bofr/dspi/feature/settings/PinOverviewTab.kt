// Port of DSPi-Console-b5:DSPi Console/DSPi_ConsoleApp.swift `PinOverviewTab`,
// with DSPi-Console-Windows Settings/PinNavigation.cs click-through (a pin
// opens the page that sets it).
package se.bofr.dspi.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.ui.MacColors

/**
 * A read-only map of every GPIO the device is holding. Only held pins are
 * listed: a feature configured but off reserves nothing. A pin claimed twice
 * (a device-side inconsistency) is listed above the map.
 */
@Composable
fun PinOverviewTab(bridge: DspiBridge, coordinator: SettingsSaveCoordinator, onOpen: (SettingsCategory) -> Unit) {
    val state by bridge.state.collectAsState()
    bridge.cs.model.collectAsState().value // recompose on CS changes (pin uses)
    val connected = bridge.status.collectAsState().value.connected
    val device = PinContext.from(coordinator.deviceSnapshot(state), state, if (connected) bridge.pinOwners() else emptyList())
    val assignments = device.activePinAssignments()
    val claims = assignments.associateBy { it.pin }
    val free = VALID_PINS.filter { it !in claims }
    // Two features on one GPIO (should never survive the firmware's checks)
    val conflicts = device.pinConflicts()

    SettingsForm {
        if (!connected) {
            item {
                SettingsSection(null) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No Device Connected", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Pin assignments live on the device. Connect a DSPi to see which GPIOs are in use.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            return@SettingsForm
        }
        if (conflicts.isNotEmpty()) {
            item {
                SettingsSection(
                    "Conflicts",
                    footer = "These pins are claimed by more than one feature.",
                    footerColor = MacColors.orange
                ) {
                    conflicts.forEach { c ->
                        SettingRow(
                            "GPIO ${c.pin}",
                            c.claimants.joinToString(" · ") {
                                it.label
                            },
                            detailColor = MacColors.orange,
                            onClick = { onOpen(c.claimants.last().page) }
                        )
                    }
                }
            }
        }
        if (assignments.isEmpty()) {
            item { SettingsSection(null) { FormCaption("No GPIOs are currently claimed.") } }
            return@SettingsForm
        }
        item {
            SettingsSection(null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${assignments.size} of ${VALID_PINS.size} GPIOs in use",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (free.isEmpty()) "none free" else "${free.size} free",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // The header in pin order: adjacency matters to the clock pairs (pin, pin + 1)
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    VALID_PINS.chunked(7).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { pin ->
                                val claim = claims[pin]
                                GpioChip(
                                    "$pin",
                                    claim?.let { Color(it.role.tint) },
                                    Modifier.weight(1f).clickable(enabled = claim != null) { claim?.let { onOpen(it.page) } }
                                )
                            }
                            repeat(7 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
        PinRole.entries.forEach { role ->
            val rows = assignments.filter { it.role == role }.sortedBy { it.pin }
            if (rows.isEmpty()) return@forEach
            item(key = role.name) {
                SettingsSection(role.title) {
                    rows.forEach { a ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpen(a.page) }.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GpioChip("GP${a.pin}", Color(role.tint), Modifier.width(48.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(a.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
