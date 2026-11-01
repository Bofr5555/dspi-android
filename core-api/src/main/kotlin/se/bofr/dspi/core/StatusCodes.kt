package se.bofr.dspi.core

/** Named views of the raw status bytes carried by [DspiException.Refused]. */
enum class PresetStatus(val code: Int) {
    OK(DspiConstants.PRESET_OK),
    INVALID_SLOT(DspiConstants.PRESET_ERR_INVALID_SLOT),
    SLOT_EMPTY(DspiConstants.PRESET_ERR_SLOT_EMPTY),
    CRC(DspiConstants.PRESET_ERR_CRC),
    FLASH_WRITE(DspiConstants.PRESET_ERR_FLASH_WRITE),

    /**
     * BRIDGE-3 / HOME H12: macOS `PRESET_STATUS_LOCKED` (0xFE, `Constants.swift`).
     * App-side only, never on the wire: the firmware (`config.h` PRESET_* =
     * 0x00..0x04) and the core define no such status. macOS returns it when
     * another DSPi Link session holds the hub's device lock, so the
     * save/copy was not sent. Android talks to the unit over USB only (no
     * DSPi Link hub, no device lock), so the bridge never produces it today;
     * it exists so the wording and the mapping match macOS
     * ([failureText], [DeviceLock.LOCKED_MESSAGE]).
     */
    LOCKED(PRESET_STATUS_LOCKED),

    /** The device did not answer (transfer error / disconnected). */
    NO_REPLY(-1)
    ;

    /**
     * macOS `presetFailureText(_:)`: [LOCKED] gets the shared lock wording
     * ("Another console is applying changes"), anything else "error N" with
     * the status byte (macOS shows 255 for no reply, the byte its transport
     * returns then).
     */
    val failureText: String
        get() = if (this == LOCKED) DeviceLock.LOCKED_MESSAGE else "error ${if (this == NO_REPLY) 255 else code}"

    companion object {
        fun of(code: Int): PresetStatus = entries.firstOrNull { it.code == code } ?: NO_REPLY
    }
}

/** macOS `PRESET_STATUS_LOCKED`: app-side preset status, never on the wire. */
const val PRESET_STATUS_LOCKED: Int = 0xFE

/** macOS `DeviceLock` (`Link/DeviceTransport.swift`): the shared device-lock wording. */
object DeviceLock {
    /** macOS `DeviceLock.lockedMessage`. */
    const val LOCKED_MESSAGE: String = "Another console is applying changes"
}

enum class PinConfigStatus(val code: Int) {
    SUCCESS(DspiConstants.PIN_CONFIG_SUCCESS),
    INVALID_PIN(DspiConstants.PIN_CONFIG_INVALID_PIN),
    PIN_IN_USE(DspiConstants.PIN_CONFIG_PIN_IN_USE),
    INVALID_OUTPUT(DspiConstants.PIN_CONFIG_INVALID_OUTPUT),
    OUTPUT_ACTIVE(DspiConstants.PIN_CONFIG_OUTPUT_ACTIVE),
    INVALID_PARAM(DspiConstants.PIN_CONFIG_INVALID_PARAM),

    /** 255 in the C API: no reply. */
    NO_REPLY(255)
    ;

    companion object {
        fun of(code: Int): PinConfigStatus = entries.firstOrNull { it.code == code } ?: NO_REPLY
    }
}

enum class FlashStatus(val code: Int) {
    OK(DspiConstants.FLASH_OK),
    ERR_WRITE(DspiConstants.FLASH_ERR_WRITE),
    ERR_NO_DATA(DspiConstants.FLASH_ERR_NO_DATA),
    ERR_CRC(DspiConstants.FLASH_ERR_CRC)
    ;

    companion object {
        fun of(code: Int): FlashStatus = entries.firstOrNull { it.code == code } ?: ERR_WRITE
    }
}

/** `DspState.compat`. */
enum class Compat(val code: Int) {
    UNKNOWN(DspiConstants.COMPAT_UNKNOWN),
    OK(DspiConstants.COMPAT_OK),
    FIRMWARE_TOO_OLD(DspiConstants.COMPAT_FIRMWARE_TOO_OLD),
    FIRMWARE_TOO_NEW(DspiConstants.COMPAT_FIRMWARE_TOO_NEW)
    ;

    companion object {
        fun of(code: Int): Compat = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** Run [block], mapping a [DspiException.Refused] to its code and other failures to [noReply]. */
inline fun statusOf(noReply: Int, block: () -> Unit): Int = try {
    block()
    0
} catch (e: DspiException.Refused) {
    e.status
} catch (e: DspiException) {
    noReply
}
