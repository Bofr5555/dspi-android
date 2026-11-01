// Port of DSPi-Console (macOS) `DSPi Console/DSPi_ConsoleApp.swift` @ v1.1.6-beta5:
// `ToolsMenuActions` (Commit Parameters…, Revert to Saved…, Factory Reset…)
// and `FileMenuActions.saveMasterVolume` / `saveOutputConfig`, with their
// alert wording. Linux `qt/qml/main.qml` commitDialog / revertDialog /
// factoryResetDialog drive the same core.
package se.bofr.dspi.feature.presets

import kotlinx.coroutines.CoroutineScope
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
                        scope.launchDeviceSequence {
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
                        scope.launchDeviceSequence {
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
                        scope.launchDeviceSequence {
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
     * File > "Save Master Volume": as macOS, the menu tap saves (decision E1: the
     * tap mints SAVE_MASTER_VOLUME); the result alert follows.
     */
    fun saveMasterVolume() {
        if (!bridge.connected) {
            host.showError("No device connected.")
            return
        }
        val display = PresetDocumentFiles.masterVolumeDisplay(bridge.masterVolumeDB)
        val token = bridge.dangerousOps.confirm(DangerousOp.SAVE_MASTER_VOLUME)
        scope.launchDeviceSequence {
            if (bridge.saveMasterVolume(token) == PresetStatus.OK) {
                host.showSuccess("Master volume saved ($display). It will be applied on next boot.")
            } else {
                host.showError("Failed to save master volume — the device did not acknowledge the request.")
            }
        }
    }

    /** File > "Save Output Configuration": the tap saves (E1, SAVE_OUTPUT_CONFIG); the result alert follows. */
    fun saveOutputConfig() {
        if (!bridge.connected) {
            host.showError("No device connected.")
            return
        }
        val token = bridge.dangerousOps.confirm(DangerousOp.SAVE_OUTPUT_CONFIG)
        scope.launchDeviceSequence {
            if (bridge.saveOutputConfig(token) == PresetStatus.OK) {
                host.showSuccess("Output configuration saved. It will be applied on next boot.")
            } else {
                host.showError("Failed to save output configuration. The device did not acknowledge the request.")
            }
        }
    }
}
