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

/** A claimed GPIO. role: "output", "clock", "input", "control" or "other". */
data class PinOwner(val pin: Int, val owner: String, val role: String)

/** A channel's bands for the on-graph editor. */
data class ChannelBands(
    val peq: List<FilterParams>,
    /** Empty for inputs. */
    val xover: List<FilterParams>
)

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
