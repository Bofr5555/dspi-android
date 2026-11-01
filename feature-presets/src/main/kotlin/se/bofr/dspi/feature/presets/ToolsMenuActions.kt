// Port of DSPi-Console (macOS) `DSPi Console/DSPi_ConsoleApp.swift` @ v1.1.6-beta5:
// `ToolsMenuActions` (Commit Parameters…, Revert to Saved…, Factory Reset…)
// and `FileMenuActions.saveMasterVolume` / `saveOutputConfig`, with their
// alert wording. Linux `qt/qml/main.qml` commitDialog / revertDialog /
// factoryResetDialog drive the same core.
package se.bofr.dspi.feature.presets

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import se.bofr.dspi.bridge.DangerousOp
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.core.FlashStatus
import se.bofr.dspi.core.PresetStatus

class ToolsMenuActions(private val bridge: DspiBridge, private val scope: CoroutineScope, private val host: AlertHost) {
    /** "Commit Parameters...": save the live settings into the active slot (GATED SAVE_PRESET). */
    fun commitParameters() {
        if (!bridge.connected) {
            host.showError("Not connected to device")
            return
        }
        val slot = bridge.activePresetSlot
        host.show(
            ConsoleAlert(
                "Save Preset",
                "Save current parameters to preset slot ${slot + 1}?",
                AlertStyle.WARNING,
                listOf(
                    AlertButton("Save", AlertButtonRole.DEFAULT) {
                        val token = bridge.dangerousOps.confirm(DangerousOp.SAVE_PRESET)
                        scope.launch {
                            when (val result = bridge.savePreset(token, slot)) {
                                PresetStatus.OK -> host.showSuccess("Preset saved successfully")
                                else -> host.showError("Failed to save preset (${presetFailureText(result)})")
                            }
                        }
                    },
                    AlertButton("Cancel", AlertButtonRole.CANCEL)
                )
            )
        )
    }

    /** "Revert to Saved...": reload the active preset (ungated, D1). */
    fun revertToSaved() {
        host.show(
            ConsoleAlert(
                "Revert to Saved",
                "Revert to last saved parameters?\n\nCurrent unsaved changes will be lost.",
                AlertStyle.WARNING,
                listOf(
                    AlertButton("Revert", AlertButtonRole.DEFAULT) {
                        if (!bridge.connected) {
                            host.showError("Not connected to device")
                            return@AlertButton
                        }
                        scope.launch {
                            when (bridge.loadParams()) {
                                FlashStatus.OK -> host.showSuccess("Parameters reverted successfully")
                                FlashStatus.ERR_NO_DATA -> host.showInfo(
                                    "No saved parameters found.\n\nThe device is using factory defaults."
                                )
                                FlashStatus.ERR_CRC -> host.showError("Saved data is corrupted")
                                else -> host.showError("Failed to load parameters")
                            }
                        }
                    },
                    AlertButton("Cancel", AlertButtonRole.CANCEL)
                )
            )
        )
    }

    /** "Factory Reset...": reset the live settings (GATED FACTORY_RESET); saved presets stay. */
    fun factoryReset() {
        host.show(
            ConsoleAlert(
                "Factory Reset",
                "Do you wish to clear all active parameters?\n\n" +
                    "This will not overwrite your saved parameters unless you run 'Commit Parameters'.",
                AlertStyle.CRITICAL,
                listOf(
                    AlertButton("Reset", AlertButtonRole.DESTRUCTIVE) {
                        if (!bridge.connected) {
                            host.showError("Not connected to device")
                            return@AlertButton
                        }
                        val token = bridge.dangerousOps.confirm(DangerousOp.FACTORY_RESET)
                        scope.launch {
                            when (bridge.factoryReset(token)) {
                                FlashStatus.OK -> host.showSuccess("Factory reset complete")
                                else -> host.showError("Failed to reset parameters")
                            }
                        }
                    },
                    AlertButton("Cancel", AlertButtonRole.CANCEL)
                )
            )
        )
    }

    /**
     * File > "Save Master Volume" (GATED SAVE_MASTER_VOLUME). macOS saves
     * without asking; the confirmation is deviation 3 (wording from README
     * "Saving Master Volume and Output Configuration").
     */
    fun saveMasterVolume() {
        if (!bridge.connected) {
            host.showError("No device connected.")
            return
        }
        val savedDB = bridge.masterVolumeDB
        val display = PresetDocumentFiles.masterVolumeDisplay(savedDB)
        host.show(
            ConsoleAlert(
                "Save Master Volume",
                "Store the current master volume ($display) on the device? It will be applied on next boot.",
                AlertStyle.WARNING,
                listOf(
                    AlertButton("Save", AlertButtonRole.DEFAULT) {
                        val token = bridge.dangerousOps.confirm(DangerousOp.SAVE_MASTER_VOLUME)
                        scope.launch {
                            if (bridge.saveMasterVolume(token) == PresetStatus.OK) {
                                host.showSuccess("Master volume saved ($display). It will be applied on next boot.")
                            } else {
                                host.showError("Failed to save master volume — the device did not acknowledge the request.")
                            }
                        }
                    },
                    AlertButton("Cancel", AlertButtonRole.CANCEL)
                )
            )
        )
    }

    /** File > "Save Output Configuration" (GATED SAVE_OUTPUT_CONFIG); confirmation = deviation 3. */
    fun saveOutputConfig() {
        if (!bridge.connected) {
            host.showError("No device connected.")
            return
        }
        host.show(
            ConsoleAlert(
                "Save Output Configuration",
                "Store the current hardware configuration (pins, output types, I2S clocks, S/PDIF input pins and output limiters) " +
                    "on the device? It will be applied on next boot.",
                AlertStyle.WARNING,
                listOf(
                    AlertButton("Save", AlertButtonRole.DEFAULT) {
                        val token = bridge.dangerousOps.confirm(DangerousOp.SAVE_OUTPUT_CONFIG)
                        scope.launch {
                            if (bridge.saveOutputConfig(token) == PresetStatus.OK) {
                                host.showSuccess("Output configuration saved. It will be applied on next boot.")
                            } else {
                                host.showError("Failed to save output configuration. The device did not acknowledge the request.")
                            }
                        }
                    },
                    AlertButton("Cancel", AlertButtonRole.CANCEL)
                )
            )
        )
    }
}
