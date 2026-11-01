// Port of DSPi-Console-b5:DSPi Console/DSPi_ConsoleApp.swift `ControlInterfacesSettingsTab`.
// UART / I2C config persist is an ungated flash write (D3): one Apply per
// interface, as on macOS; the outcome arrives as BridgeEvent.CtrlIfaceApplied.
package se.bofr.dspi.feature.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import se.bofr.dspi.bridge.BridgeEvent
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.core.I2cConfig
import se.bofr.dspi.core.UartConfig

/** macOS `UART_CTRL_BAUD_CHOICES`. */
internal val UART_CTRL_BAUD_CHOICES = listOf(9600L, 19200L, 38400L, 57600L, 115200L, 230400L, 460800L, 921600L, 1000000L)
internal const val I2C_CTRL_ADDR_MIN = 0x08
internal const val I2C_CTRL_ADDR_MAX = 0x77

internal fun baudLabel(b: Long) = if (b >= 1000 && b % 1000 == 0L) "${b / 1000}k" else "$b"

/** A `PIN_CONFIG_*` apply outcome as inline feedback (macOS `statusMessage`). */
internal fun ctrlIfaceStatusMessage(status: Int, iface: String): Pair<String, Boolean> = when (status) {
    DspiConstants.PIN_CONFIG_SUCCESS -> "$iface configuration applied and saved" to false
    DspiConstants.PIN_CONFIG_INVALID_PIN -> "A pin is out of range or lacks the required $iface mux function" to true
    DspiConstants.PIN_CONFIG_PIN_IN_USE -> "A pin is already claimed by another output or interface" to true
    DspiConstants.PIN_CONFIG_INVALID_PARAM ->
        (if (iface == "UART") "Baud rate is out of range (9600 - 1000000)" else "Address is out of range (0x08 - 0x77)") to true
    else -> "Failed to apply $iface configuration" to true
}

/** Pins carrying the needed mux function and free (macOS `uartTxPins` …). */
internal fun ctrlCandidates(pins: PinContext, consumer: PinConsumer, current: Int, other: Int, mux: (Int) -> Boolean): List<Int> =
    VALID_PINS.filter { p -> mux(p) && p != other && (p == current || pins.pinInUseBy(p, consumer) == null) }

@Composable
fun ControlInterfacesSettingsTab(bridge: DspiBridge, coordinator: SettingsSaveCoordinator) {
    val state by bridge.state.collectAsState()
    val connected = bridge.status.collectAsState().value.connected
    val pins = coordinator.pinContext(state)
    val live = state.ctrlStatus

    var uartDraft by remember { mutableStateOf(state.uart) }
    var i2cDraft by remember { mutableStateOf(state.i2c) }
    var uartBase by remember { mutableStateOf(state.uart) }
    var i2cBase by remember { mutableStateOf(state.i2c) }
    var uartStatus by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var i2cStatus by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var uartApplying by remember { mutableStateOf(false) }
    var i2cApplying by remember { mutableStateOf(false) }

    // Refresh live status/config in case another host changed it; a drop
    // mid-apply never answers, so release the Apply buttons
    LaunchedEffect(connected) {
        if (connected) {
            bridge.refreshCtrlIfaces()
        } else {
            uartApplying = false
            i2cApplying = false
        }
    }
    // Drafts belong to the selected device: discard them on a switch (macOS onChange(selectedDevice))
    val serial = bridge.devices.collectAsState().value.selectedSerial
    var lastSerial by remember { mutableStateOf(serial) }
    LaunchedEffect(serial) {
        if (serial != lastSerial) {
            lastSerial = serial
            uartDraft = state.uart
            i2cDraft = state.i2c
            uartStatus = null
            i2cStatus = null
            uartApplying = false
            i2cApplying = false
        }
    }
    // Re-seed clean drafts when the device's config changes
    LaunchedEffect(state.uart, state.i2c) {
        if (!uartApplying && uartDraft == uartBase) uartDraft = state.uart
        if (!i2cApplying && i2cDraft == i2cBase) i2cDraft = state.i2c
        uartBase = state.uart
        i2cBase = state.i2c
    }
    LaunchedEffect(bridge) {
        bridge.events.collect { e ->
            if (e is BridgeEvent.CtrlIfaceApplied) {
                if (e.which == 0) {
                    uartApplying = false
                    uartStatus = ctrlIfaceStatusMessage(e.status, "UART")
                } else {
                    i2cApplying = false
                    i2cStatus = ctrlIfaceStatusMessage(e.status, "I2C")
                }
            }
        }
    }
    val uartDirty = uartDraft != state.uart
    val i2cDirty = i2cDraft != state.i2c

    SettingsForm {
        item {
            SettingsSection("UART") {
                InterfaceHeader(
                    "Enable UART",
                    "Asynchronous 3.3V serial link, fixed 8N1 framing.",
                    live.uartLive,
                    state.uart.enabled,
                    uartDraft.enabled,
                    connected
                ) {
                    uartDraft = uartDraft.copy(enabled = it)
                }
                if (uartDraft.enabled) {
                    PinRow(
                        "TX Pin",
                        "GPIO transmitting to the controller's RX.",
                        uartDraft.txPin,
                        ctrlCandidates(pins, PinConsumer.UartCtrl, uartDraft.txPin, uartDraft.rxPin) { it % 4 == 0 },
                        enabled = connected
                    ) { uartDraft = uartDraft.copy(txPin = it) }
                    PinRow(
                        "RX Pin",
                        "GPIO receiving from the controller's TX.",
                        uartDraft.rxPin,
                        ctrlCandidates(pins, PinConsumer.UartCtrl, uartDraft.rxPin, uartDraft.txPin) { it % 4 == 1 },
                        enabled = connected
                    ) { uartDraft = uartDraft.copy(rxPin = it) }
                    ChoiceRow(
                        "Baud Rate",
                        "9600 - 1000000. Must match the controller.",
                        UART_CTRL_BAUD_CHOICES.map {
                            it to baudLabel(it)
                        },
                        uartDraft.baud,
                        enabled = connected
                    ) {
                        uartDraft = uartDraft.copy(baud = it)
                    }
                    SwitchRow(
                        "Push Notifications",
                        "Stream live parameter/preset/format changes to the controller (type-0x40 frames) instead of polling.",
                        uartDraft.notify,
                        enabled = connected
                    ) { uartDraft = uartDraft.copy(notify = it) }
                }
                ApplyRow(uartApplying, uartDirty, uartStatus, connected, onRevert = {
                    uartDraft = state.uart
                    uartStatus = null
                }) {
                    val c: UartConfig = uartDraft
                    uartApplying = true
                    uartStatus = null
                    bridge.setUart(c.enabled, c.txPin, c.rxPin, c.notify, c.baud.toInt())
                }
            }
        }
        item {
            SettingsSection("I2C") {
                InterfaceHeader(
                    "Enable I2C Target",
                    "Device acts as an I2C slave; the controller is bus master. Poll-only (no async notifications).",
                    live.i2cLive,
                    state.i2c.enabled,
                    i2cDraft.enabled,
                    connected
                ) { i2cDraft = i2cDraft.copy(enabled = it) }
                if (i2cDraft.enabled) {
                    PinRow(
                        "SDA Pin",
                        "Serial data line (even GPIO).",
                        i2cDraft.sdaPin,
                        ctrlCandidates(pins, PinConsumer.I2cCtrl, i2cDraft.sdaPin, i2cDraft.sclPin) { it % 2 == 0 },
                        enabled = connected
                    ) { i2cDraft = i2cDraft.copy(sdaPin = it) }
                    PinRow(
                        "SCL Pin",
                        "Serial clock line (next odd GPIO, same instance).",
                        i2cDraft.sclPin,
                        ctrlCandidates(pins, PinConsumer.I2cCtrl, i2cDraft.sclPin, i2cDraft.sdaPin) { it % 2 == 1 },
                        enabled = connected
                    ) { i2cDraft = i2cDraft.copy(sclPin = it) }
                    SettingRow("Target Address", "7-bit address, 0x08 - 0x77.", enabled = connected) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                i2cDraft =
                                    i2cDraft.copy(address = (i2cDraft.address - 1).coerceAtLeast(I2C_CTRL_ADDR_MIN))
                            }, enabled = connected) { Text("−") }
                            Text("0x%02X".format(i2cDraft.address), style = MaterialTheme.typography.bodyLarge)
                            TextButton(onClick = {
                                i2cDraft =
                                    i2cDraft.copy(address = (i2cDraft.address + 1).coerceAtMost(I2C_CTRL_ADDR_MAX))
                            }, enabled = connected) { Text("+") }
                        }
                    }
                }
                ApplyRow(i2cApplying, i2cDirty, i2cStatus, connected, onRevert = {
                    i2cDraft = state.i2c
                    i2cStatus = null
                }) {
                    val c: I2cConfig = i2cDraft
                    i2cApplying = true
                    i2cStatus = null
                    bridge.setI2c(c.enabled, c.sdaPin, c.sclPin, c.address)
                }
            }
        }
        item {
            SettingsSection(
                null,
                footer = if (live.protocolVersion != 0) "External control protocol version ${live.protocolVersion}." else null
            ) {
                Caption(
                    "Both control interfaces are configured over USB only - an external controller can read the configuration but can " +
                        "never " +
                        "reconfigure or disable the transport it is talking on, so it cannot lock itself out. Settings persist across " +
                        "reboots and " +
                        "survive a factory reset.\n\nUART TX/RX and I2C SDA/SCL must land on GPIOs that carry the matching peripheral " +
                        "mux function; " +
                        "the device validates this when you apply. Fit external pull-ups (2.2k - 4.7k) on the I2C bus."
                )
            }
        }
    }
}

/** Title, description, live pill (Active / Inactive / Disabled) and the enable switch. */
@Composable
private fun InterfaceHeader(
    title: String,
    detail: String,
    live: Boolean,
    configEnabled: Boolean,
    on: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit
) {
    SettingRow(
        title,
        detail + if (configEnabled && !live) {
            "\nEnabled in flash but not running - its pins likely collide with the current output wiring. Reassign the conflicting pin " +
                "or move this interface, then apply."
        } else {
            ""
        },
        enabled = enabled
    ) {
        val (text, color) = when {
            live -> "Active" to OkGreen
            configEnabled -> "Inactive" to WarningOrange
            else -> "Disabled" to Color.Gray
        }
        Pill(text, color)
        Spacer(Modifier.width(8.dp))
        Switch(checked = on, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Apply / Revert with inline outcome (macOS `applyRow`). */
@Composable
private fun ApplyRow(
    applying: Boolean,
    dirty: Boolean,
    status: Pair<String, Boolean>?,
    connected: Boolean,
    onRevert: () -> Unit,
    onApply: () -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (status != null && !dirty) {
                Text(if (status.second) "⚠ " else "✓ ", color = if (status.second) WarningOrange else OkGreen)
                Text(
                    status.first,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.second) WarningOrange else MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (dirty) {
                Text("Unapplied changes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (applying) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        TextButton(onClick = onRevert, enabled = dirty && !applying) { Text("Revert") }
        OutlinedButton(onClick = onApply, enabled = dirty && !applying && connected) { Text("Apply") }
    }
}
