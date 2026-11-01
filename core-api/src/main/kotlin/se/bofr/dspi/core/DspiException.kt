package se.bofr.dspi.core

/**
 * Domain mirror of dspi-ffi's `DspiError` (Rust) / the UniFFI `DspiException`.
 * Replaces the core C API's bool / 0xFF / -1 / status-byte conventions
 * (`core_spec.md` §26).
 */
sealed class DspiException(message: String) : Exception(message) {
    /** No device open, or it went away. */
    class Disconnected : DspiException("device not connected")

    /** A USB transfer failed (timeout, stall, short read, flash blackout). */
    class Transfer(val msg: String) : DspiException("USB transfer failed: $msg")

    /**
     * The device answered but refused. [status] is the raw code: `PRESET_*`,
     * `PIN_CONFIG_*`, `FLASH_*` (see [DspiConstants], [PresetStatus],
     * [PinConfigStatus], [FlashStatus]) or command-specific
     * (`testDacMute`: 3 = feature disabled).
     */
    class Refused(val status: Int) : DspiException("device refused the request (status $status)")

    class InvalidArgument(val msg: String) : DspiException("invalid argument: $msg")

    /** The connected firmware lacks the feature. */
    class Unsupported : DspiException("not supported by the connected firmware")

    /** Not implemented in this build (Wave 0 stubs). */
    class Unimplemented(val what: String) : DspiException("not implemented: $what")
}
