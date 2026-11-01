package se.bofr.dspi.core

/** Named views of the raw status bytes carried by [DspiException.Refused]. */
enum class PresetStatus(val code: Int) {
    OK(DspiConstants.PRESET_OK),
    INVALID_SLOT(DspiConstants.PRESET_ERR_INVALID_SLOT),
    SLOT_EMPTY(DspiConstants.PRESET_ERR_SLOT_EMPTY),
    CRC(DspiConstants.PRESET_ERR_CRC),
    FLASH_WRITE(DspiConstants.PRESET_ERR_FLASH_WRITE),

    /** The device did not answer (transfer error / disconnected). */
    NO_REPLY(-1)
    ;

    companion object {
        fun of(code: Int): PresetStatus = entries.firstOrNull { it.code == code } ?: NO_REPLY
    }
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
