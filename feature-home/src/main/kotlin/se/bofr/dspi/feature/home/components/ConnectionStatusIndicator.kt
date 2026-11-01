// Port of DSPi-Console-b5:DSPi Console/Components.swift (ConnectionStatusIndicator) @ b5fdeac.
package se.bofr.dspi.feature.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.feature.home.HomeModel
import se.bofr.dspi.feature.home.observeStatus
import se.bofr.dspi.ui.DspiTheme
import se.bofr.dspi.ui.DspiType
import se.bofr.dspi.ui.PopUpButton

/**
 * Connection dot + device picker. A long-press on the picker forces a
 * reconnect (macOS: right-click). "No Devices" in red when nothing is
 * attached; the dot's description carries the connection error.
 */
@Composable
fun ConnectionStatusIndicator(bridge: DspiBridge, modifier: Modifier = Modifier) {
    val c = DspiTheme.colors
    val connected = bridge.observeStatus { it.connected }
    val error = bridge.observeStatus { it.connectionError }
    val devices by bridge.devices.collectAsStateWithLifecycle()
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(6.dp)
                .background(if (connected) c.green else c.red, CircleShape)
                .semantics {
                    contentDescription =
                        if (connected) "Connected" else error.ifEmpty { "Not connected. Long-press the device name to retry." }
                }
        )
        val serials = devices.availableSerials
        if (serials.isEmpty()) {
            Text("No Devices", style = DspiType.caption, color = c.red)
        } else {
            val selected = devices.selectedSerial.takeIf { it in serials } ?: serials.first()
            // Pickable when there is a choice, or when the shown device is not the selected one.
            val pickable = serials.size > 1 || devices.selectedSerial !in serials
            PopUpButton(
                items = serials,
                selection = selected,
                titleForItem = HomeModel::deviceDisplayName,
                onSelect = { if (it != devices.selectedSerial) bridge.selectDevice(it) },
                enabled = pickable,
                showsChevron = pickable,
                onLongClick = { bridge.reconnect() },
                textStyle = DspiType.caption,
                dimWhenDisabled = false
            )
        }
    }
}
