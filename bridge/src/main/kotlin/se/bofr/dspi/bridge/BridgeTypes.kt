package se.bofr.dspi.bridge

import se.bofr.dspi.core.FilterParams

/** statusChanged payload. Peaks are per APP id (index = app id, 0 if absent). */
data class BridgeStatus(
    val connected: Boolean = false,
    /** Why the last connection attempt failed, "" if it didn't. */
    val connectionError: String = "",
    val cpu0: Int = 0,
    val cpu1: Int = 0,
    /**
     * Sticky clip flags (10 s latch), bit per WIRE channel as the core reports
     * them. [DspiBridge.clipFlags] is the same re-keyed by app id.
     */
    val clipFlags: Long = 0,
    val activeInputChannels: Int = 0,
    /** 0..1 per app id, size [ChannelMap.APP_CHANNEL_COUNT]. */
    val peaks: List<Float> = List(ChannelMap.APP_CHANNEL_COUNT) { 0f },
    /**
     * D4: limiter gain reduction in dB (> 0 while limiting) per OUTPUT index
     * (Qt `m_limiterGR`, refreshed every 4th poll while a limiter is enabled).
     * Size [se.bofr.dspi.core.DspiConstants.MAX_OUTPUTS] while connected with a
     * limiter enabled; empty otherwise (read a missing index as 0). Any change
     * of a value is a status change, as Qt's `changed = true`.
     */
    val limiterReduction: List<Float> = emptyList()
)

data class DeviceListState(
    val selectedSerial: String = "",
    val availableSerials: List<String> = emptyList(),
    /** DSPis seen that could not be opened (Android: permission denied). */
    val inaccessibleDevices: Int = 0
)

/** Firmware InputSource id + display name ("USB", "S/PDIF 1", "I2S", "ADAT"). */
data class InputSourceOption(val id: Int, val name: String)

/**
 * A claimed GPIO. [owner] and [role] are the Qt `pinOwners()` label and role
 * ("output", "clock", "input", "control" or "other"), unchanged.
 *
 * BRIDGE-3 / SETTINGS S15: [kind] + [index] say structurally who holds the
 * pin, so no caller has to parse [owner] ("Control Surface 3" ->
 * `CONTROL_SURFACE`, index 2). They map 1:1 onto macOS
 * `DSPViewModel.swift` `PinConsumer` (see [PinOwnerKind]); [index] is the
 * consumer's argument (output-pin index, S/PDIF input, I2S pair, control
 * surface binding slot, all 0-based) or the pin's position inside a
 * two-pin consumer (UART 0 TX / 1 RX, I2C 0 SDA / 1 SCL), -1 where the
 * consumer has none.
 */
data class PinOwner(val pin: Int, val owner: String, val role: String, val kind: PinOwnerKind = PinOwnerKind.OTHER, val index: Int = -1)

/**
 * What holds a GPIO (macOS `PinConsumer`, split where macOS shows a
 * different label for the same consumer). [macRole] is the macOS `PinRole`
 * the Overview groups it under ("output", "clock", "input", "control",
 * "utility").
 */
enum class PinOwnerKind(val macRole: String) {
    /** `.output(slot)`: an S/PDIF / I2S output slot; index = slot (outputPins index). */
    OUTPUT_SLOT("output"),

    /** `.output(pdmIndex)`: the PDM subwoofer; index = its outputPins index. */
    SUBWOOFER("output"),

    /** `.adatOut`: ADAT bulk output data pin (held only while enabled). */
    ADAT_OUT("output"),

    /** `.i2sBck`: the master bit clock. */
    I2S_BCK("clock"),

    /** `.i2sBck` (BCK + 1): the master word clock. */
    I2S_LRCLK("clock"),

    /** `.i2sBckSlave`: slave bit clock (held in SPLIT clock-pin mode only). */
    I2S_SLAVE_BCK("clock"),

    /** `.i2sBckSlave` (BCK + 1): slave word clock (SPLIT clock-pin mode only). */
    I2S_SLAVE_LRCLK("clock"),

    /** `.mck`: the I2S master clock. */
    MCK("clock"),

    /** `.spdifRx(n)`: S/PDIF receiver data pin; index = input 0..3. */
    SPDIF_RX("input"),

    /** `.i2sRx(pair)`: I2S input data pin; index = stereo pair 0..3. */
    I2S_RX("input"),

    /** `.adatIn`: ADAT input data pin (held only while enabled). */
    ADAT_IN("input"),

    /** `.dacMute`: the DAC hardware-mute GPIO. */
    DAC_MUTE("utility"),

    /** `.uartCtrl`: UART control interface; index 0 TX, 1 RX. */
    UART("control"),

    /** `.i2cCtrl`: I2C control interface; index 0 SDA, 1 SCL. */
    I2C("control"),

    /** `.controlSurface(slot)`: a live control-surface binding; index = binding slot (0-based). */
    CONTROL_SURFACE("control"),

    /** Not produced by [DspiBridge.pinOwners]; the default for hand-built values. */
    OTHER("utility")
}

/** A channel's bands for the on-graph editor. */
data class ChannelBands(
    val peq: List<FilterParams>,
    /** Empty for inputs. */
    val xover: List<FilterParams>
)

/**
 * G1: a write/command refused because [DspiBridge.writesAllowed] is false
 * (carried by [BridgeEvent.CommandFailed]). Nothing was sent to the device.
 */
class WritesNotAllowedException(message: String) : IllegalStateException(message) {
    companion object {
        /** Connected, but the firmware's wire format is not this app's (compat != OK). */
        const val FIRMWARE_MISMATCH = "firmware mismatch: read-only"

        /** No device connected. */
        const val NOT_CONNECTED = "device not connected: read-only"
    }
}

/** Qt signals other than the property NOTIFYs. */
sealed interface BridgeEvent {
    /** The signal generator started or stopped (device or elsewhere). */
    data object SiggenNotified : BridgeEvent

    /** An aux output changed ([aux]) or IR learning finished ([learn]). */
    data class CsNotified(val aux: Boolean, val learn: Boolean) : BridgeEvent

    /** A batch of device notifications arrived (Interrupt Monitor). */
    data object NotificationsArrived : BridgeEvent

    data class DeviceArrived(val serial: String) : BridgeEvent

    data class DeviceDeparted(val serial: String) : BridgeEvent

    data class DacMuteApplied(val accepted: Boolean) : BridgeEvent

    /** which: 0 = UART, 1 = I2C; status: 0 OK, 1 invalid pin, 2 pin in use, 5 invalid parameter. */
    data class CtrlIfaceApplied(val which: Int, val status: Int) : BridgeEvent

    /** A value shown on the graph changed mid-drag: redraw now. */
    data object PreviewChanged : BridgeEvent

    /** A fire-and-forget setter failed (no Qt equivalent: Qt ignores the bool). */
    data class CommandFailed(val command: String, val error: Throwable) : BridgeEvent
}

/**
 * Decoded IEC 60958 consumer channel status (macOS StatsView "S/PDIF
 * Receiver", Qt StatsController): bytes 0, 1 and 4 of the 24 received.
 */
data class SpdifChannelStatus(
    /** Byte 0 bit 0 clear: consumer (S/PDIF) rather than professional (AES). */
    val consumer: Boolean,
    /** Byte 0 bit 1 clear: linear PCM audio. */
    val pcm: Boolean,
    /** Byte 0 bit 2: copying permitted. */
    val copyPermitted: Boolean,
    /** Byte 1 category code, named ("CD Player", "DAT", … or "0x2A"). */
    val category: String,
    /** Byte 4 word length ("16-bit", "24-bit", "Not indicated", "-"). */
    val wordLength: String,
    /** The 24 raw bytes. */
    val raw: List<Int>
)

/**
 * Feature flags SETTINGS needs, derived as macOS `DSPViewModel` does for the
 * only wire format this app writes (V32; [DspiBridge.compat] == OK):
 * - the bulk image of every V21+ firmware carries S/PDIF 2-4 (`multiSpdifSupported`,
 *   macOS: non-zero enable-mask+1 byte), the I2S input and its clock mode
 *   (`i2sInputSupported`, `i2sClockModeSupported`, macOS: bulk V21 gate) and
 *   the clock-pin mode (`i2sClockPinModeSupported`, macOS: non-zero +1 byte);
 *   V32 firmware always has them;
 * - ADAT output and input are RP2350-only (macOS `adatSupported` /
 *   `adatInputSupported` = platform == "RP2350");
 * - multi-channel I2S input (4/6/8 channels) is RP2350-only (Qt InputsPage).
 * [spdifInputCount] is the receiver count; macOS reads it from the 0xEF
 * inventory, which the core does not expose, so it is the wire maximum (4)
 * — on both platforms: firmware v1.1.6 `audio_input.h` defines
 * `SPDIF_RX_NUM_INPUTS 4` unconditionally (RP2040 included), and the V32 image
 * always carries the non-zero enable-mask+1 byte (review S18).
 */
data class HardwareCaps(
    val multiSpdifSupported: Boolean = false,
    val spdifInputCount: Int = 0,
    val i2sInputSupported: Boolean = false,
    val i2sMultichannelInput: Boolean = false,
    val i2sClockModeSupported: Boolean = false,
    val i2sClockPinModeSupported: Boolean = false,
    val adatOutSupported: Boolean = false,
    val adatInputSupported: Boolean = false,
    val limiterSupported: Boolean = false,
    /**
     * BRIDGE-3: macOS `inputSourceSupported` — the input-source menu (HOME),
     * the S/PDIF Input settings page and the S/PDIF RX pin claims. macOS sets
     * it whenever a V21+ bulk image parses (`Commands.swift` bulk apply) and
     * then re-probes REQ_GET_INPUT_SOURCE 0xE1 (false on a STALL); the core
     * has no 0xE1 probe, so here it is true exactly while the V32 image is
     * usable (every RP2040/RP2350 firmware that writes V32 answers 0xE1).
     */
    val inputSourceSupported: Boolean = false,
    /**
     * BRIDGE-3: macOS `lgSoundSyncSupported` (separate flag on macOS, set by
     * the same bulk parse — V8+ — or a successful REQ_GET_LG_SOUND_SYNC_ENABLE 0xE7 read): the LG Sound
     * Sync toggle and the LG rows of the statistics view. True while the V32
     * image is usable.
     */
    val lgSoundSyncSupported: Boolean = false,
    /**
     * K340 firmware fork (core patch 0002): the firmware answers
     * REQ_CROSSFEED_PAIR_MAP (0x02), probed once per connect; upstream
     * firmware STALLs it. Gates the crossfeed pair editor.
     */
    val crossfeedPairMapSupported: Boolean = false,
    /** Stereo pairs crossfeed can use (4 on RP2350, 2 on RP2040); 0 while not usable. */
    val crossfeedPairCount: Int = 0
)

/** The bit masks [DspiBridge.toggleMaskBit] flips one bit of (tool output chips). */
enum class MaskField {
    LOUDNESS_OUTPUTS,
    CROSSFEED_PAIRS,
    PSYBASS_OUTPUTS,
    LEVELLER_DETECTOR,
    LEVELLER_APPLY,
    SUBHARM_OUTPUTS,
    TUBE_OUTPUTS
}
