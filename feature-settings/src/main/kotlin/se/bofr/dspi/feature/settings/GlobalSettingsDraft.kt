// Port of DSPi-Console-b5:DSPi Console/DSPi_ConsoleApp.swift `GlobalSettingsDraft`.
package se.bofr.dspi.feature.settings

import se.bofr.dspi.core.DspState
import se.bofr.dspi.core.DspiConstants

/**
 * The board-level settings in the device's flash directory (not per preset),
 * staged until Save so the directory sector isn't written per picker change:
 * startup preset (mode + default slot), master-volume and output-config
 * persistence modes, DAC hardware mute config. Modes: 0 independent, 1 with
 * preset; startup 0 specified default, 1 last used.
 */
data class GlobalSettingsDraft(
    val presetStartupMode: Int = STARTUP_SPECIFIED,
    val presetDefaultSlot: Int = 0,
    val presetMasterVolumeMode: Int = MASTER_VOLUME_MODE_INDEPENDENT,
    val presetOutputConfigMode: Int = OUTPUT_CONFIG_MODE_WITH_PRESET,
    val dacHwMuteConfig: DacHwMuteConfig = DacHwMuteConfig()
) {
    companion object {
        const val STARTUP_SPECIFIED = DspiConstants.PRESET_STARTUP_SPECIFIED
        const val STARTUP_LAST_ACTIVE = DspiConstants.PRESET_STARTUP_LAST_ACTIVE
        const val MASTER_VOLUME_MODE_INDEPENDENT = 0
        const val MASTER_VOLUME_MODE_WITH_PRESET = 1
        const val OUTPUT_CONFIG_MODE_INDEPENDENT = 0
        const val OUTPUT_CONFIG_MODE_WITH_PRESET = 1

        fun from(s: DspState) = GlobalSettingsDraft(
            presetStartupMode = s.presetStartupMode,
            presetDefaultSlot = s.presetDefaultSlot,
            presetMasterVolumeMode = s.masterVolumeMode,
            presetOutputConfigMode = s.outputConfigMode,
            dacHwMuteConfig = DacHwMuteConfig.from(s)
        )
    }
}
