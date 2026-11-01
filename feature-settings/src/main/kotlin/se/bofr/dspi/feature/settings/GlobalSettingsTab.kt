// Port of DSPi-Console-b5:DSPi Console/DSPi_ConsoleApp.swift `GlobalSettingsTab`.
package se.bofr.dspi.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import se.bofr.dspi.bridge.BridgeEvent
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.feature.settings.GlobalSettingsDraft.Companion.MASTER_VOLUME_MODE_INDEPENDENT
import se.bofr.dspi.feature.settings.GlobalSettingsDraft.Companion.MASTER_VOLUME_MODE_WITH_PRESET
import se.bofr.dspi.feature.settings.GlobalSettingsDraft.Companion.OUTPUT_CONFIG_MODE_INDEPENDENT
import se.bofr.dspi.feature.settings.GlobalSettingsDraft.Companion.OUTPUT_CONFIG_MODE_WITH_PRESET

/**
 * Settings that live in the device's flash directory (board-level, not in
 * presets). Edits are staged in [SettingsSaveCoordinator] and written on Save.
 */
@Composable
fun GlobalSettingsTab(bridge: DspiBridge, coordinator: SettingsSaveCoordinator) {
    val state by bridge.state.collectAsState()
    val edited by coordinator.globalUserEdited.collectAsState()
    val connected = bridge.status.collectAsState().value.connected
    @Suppress("UNUSED_EXPRESSION")
    edited
    val draft = coordinator.globalDraft(state)
    val globalDirty = coordinator.globalDirty(state)
    val pins = coordinator.pinContext(state)
    var testStatus by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(bridge) {
        bridge.events.collect { e ->
            if (e is BridgeEvent.DacMuteApplied && !e.accepted) {
                testStatus = "The device rejected the mute configuration. The pin may be in use by another function." to true
            }
        }
    }

    fun slotLabel(slot: Int): String {
        val name = if ((state.presetOccupied shr slot) and 1 ==
            1
        ) {
            state.presetNames.getOrElse(slot) { "" }.trim().ifEmpty { "Preset ${slot + 1}" }
        } else {
            "Empty"
        }
        return "${slot + 1}: $name"
    }

    SettingsForm {
        item {
            SettingsSection("Startup Preset") {
                ChoiceRow(
                    "Mode",
                    null,
                    listOf(0 to "Specified Default", 1 to "Last Used"),
                    draft.presetStartupMode,
                    enabled = connected
                ) { v ->
                    coordinator.editGlobal { it.copy(presetStartupMode = v) }
                }
                if (draft.presetStartupMode == 0) {
                    ChoiceRow(
                        "Default Preset",
                        null,
                        (0 until 10).map {
                            it to slotLabel(it)
                        },
                        draft.presetDefaultSlot,
                        enabled = connected
                    ) { v ->
                        coordinator.editGlobal { it.copy(presetDefaultSlot = v) }
                    }
                }
                Caption("Choose which preset loads when the device powers on.")
            }
        }
        if (state.dacMuteSupported) {
            item {
                val m = draft.dacHwMuteConfig
                SettingsSection("External Mute Control") {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
                        Text("⚠", color = WarningOrange)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "Adjust only with audio stopped",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Changing these settings while audio is playing can send a loud pop or full-level transient to your " +
                                    "amplifier " +
                                    "and speakers. Stop playback before making changes.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    SwitchRow(
                        "Enable Automatic Mute",
                        "Briefly mute an external DAC or amplifier to suppress loud pops during system state changes.",
                        m.enabled,
                        enabled = connected
                    ) { on ->
                        coordinator.editGlobal { d ->
                            var cfg = d.dacHwMuteConfig.copy(enabled = on)
                            // Enabling with no pin: take the first free GPIO
                            if (on && cfg.pin == DacHwMuteConfig.DAC_HW_MUTE_PIN_NONE) {
                                VALID_PINS.firstOrNull {
                                    pins.pinInUseBy(it, PinConsumer.DacMute) == null
                                }?.let { cfg = cfg.copy(pin = it) }
                            }
                            d.copy(dacHwMuteConfig = cfg)
                        }
                    }
                    if (m.enabled) {
                        ChoiceRow(
                            "Polarity",
                            null,
                            listOf(true to "Active Low", false to "Active High"),
                            m.activeLow,
                            enabled = connected
                        ) { v ->
                            coordinator.editGlobal { it.copy(dacHwMuteConfig = it.dacHwMuteConfig.copy(activeLow = v)) }
                        }
                        // No "None": the enable switch is the master switch
                        PinRow(
                            "Mute Pin",
                            null,
                            m.pin,
                            VALID_PINS.filter {
                                pins.pinInUseBy(it, PinConsumer.DacMute) == null
                            },
                            enabled = connected
                        ) { v ->
                            coordinator.editGlobal { it.copy(dacHwMuteConfig = it.dacHwMuteConfig.copy(pin = v)) }
                        }
                        ChoiceRow(
                            "Hold Time",
                            "Mute-attack wait before clock-stop",
                            msOptions(listOf(5, 10, 20, 50, 100), m.holdMs),
                            m.holdMs,
                            enabled = connected
                        ) { v ->
                            coordinator.editGlobal { it.copy(dacHwMuteConfig = it.dacHwMuteConfig.copy(holdMs = v)) }
                        }
                        ChoiceRow(
                            "Release Time",
                            "Wait after un-mute before audio resumes",
                            msOptions(listOf(0, 5, 10, 20, 50, 100), m.releaseMs),
                            m.releaseMs,
                            enabled = connected
                        ) { v ->
                            coordinator.editGlobal { it.copy(dacHwMuteConfig = it.dacHwMuteConfig.copy(releaseMs = v)) }
                        }
                        val device = DacHwMuteConfig.from(state)
                        val canTest = connected && device.pin != DacHwMuteConfig.DAC_HW_MUTE_PIN_NONE && device.enabled && !globalDirty
                        SettingRow(
                            "Test",
                            if (globalDirty) {
                                "Save your changes first to test the mute pin."
                            } else {
                                "Toggle automatic mute for one second to confirm hardware configuration."
                            },
                            enabled = canTest
                        ) {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    val code = bridge.testDacMute()
                                    testStatus = when (code) {
                                        0 -> null
                                        3 -> "Enable and save the mute first." to true
                                        else -> "The device did not respond." to true
                                    }
                                }
                            }, enabled = canTest) { Text("Start") }
                        }
                        StatusLine(testStatus?.first, testStatus?.second ?: false)
                    }
                }
            }
        }
        item {
            SettingsSection("Master Volume") {
                ChoiceRow(
                    "Mode",
                    null,
                    listOf(MASTER_VOLUME_MODE_INDEPENDENT to "Independent", MASTER_VOLUME_MODE_WITH_PRESET to "With Preset"),
                    draft.presetMasterVolumeMode,
                    enabled = connected
                ) { v -> coordinator.editGlobal { it.copy(presetMasterVolumeMode = v) } }
                Caption(
                    if (draft.presetMasterVolumeMode == MASTER_VOLUME_MODE_INDEPENDENT) {
                        "Master volume is stored on the device independently of presets and applied at boot. Loading a preset never " +
                            "changes it."
                    } else {
                        "Master volume is part of each preset. Saved with the preset, restored on preset load."
                    }
                )
            }
        }
        item {
            SettingsSection("Hardware Configuration") {
                ChoiceRow(
                    "Mode",
                    null,
                    listOf(OUTPUT_CONFIG_MODE_INDEPENDENT to "Independent", OUTPUT_CONFIG_MODE_WITH_PRESET to "With Preset"),
                    draft.presetOutputConfigMode,
                    enabled = connected
                ) { v -> coordinator.editGlobal { it.copy(presetOutputConfigMode = v) } }
                Caption(
                    if (draft.presetOutputConfigMode == OUTPUT_CONFIG_MODE_INDEPENDENT) {
                        "Input and output configuration is stored on the device independently and applied at boot. Loading a preset " +
                            "never changes your wiring."
                    } else {
                        "Input and output configuration is part of each preset. Saved with the preset and restored on preset load."
                    }
                )
            }
        }
    }
}

/** "5 ms" … plus the current value if it isn't one of [options] (macOS `globalMsPicker`). */
internal fun msOptions(options: List<Int>, current: Int): List<Pair<Int, String>> =
    (options + if (current in options) emptyList() else listOf(current)).map { it to "$it ms" }
