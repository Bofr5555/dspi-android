package se.bofr.dspi.core

/**
 * Hand mirror of core patch 0003 (`third_party/patches/0003-wire-compat-read.patch`,
 * `core/src/wirecompat.rs`): [DspState.wireRead] and [DspState.wireFeatures].
 * The contract generator reads only the unpatched submodule, so these are not
 * in [DspiConstants]; `rust/dspi-ffi/tests/wire_compat.rs` checks every value
 * against the core.
 *
 * With [WIRE_READ_OLDER] / [WIRE_READ_NEWER] the state holds the device's
 * settings mapped from its own wire format, for DISPLAY only: `compat` stays
 * TOO_OLD / TOO_NEW, so decision G1 keeps the app read-only.
 */
object WireCompat {
    const val WIRE_READ_NONE: Int = 0
    const val WIRE_READ_EXACT: Int = 1
    const val WIRE_READ_OLDER: Int = 2
    const val WIRE_READ_NEWER: Int = 3

    // WIRE_FEAT_* bits, with the wire format that introduced each
    const val ADAT_OUT: Long = 1L shl 0 // V17
    const val LEVELLER_MASKS: Long = 1L shl 1 // V18
    const val LOUDNESS_MASK: Long = 1L shl 2 // V19
    const val CROSSFEED_MASK: Long = 1L shl 3 // V20
    const val I2S_CLOCK_MODE: Long = 1L shl 4 // V21 (also S/PDIF 2-3, I2S clock-pin mode)
    const val LINKWITZ: Long = 1L shl 5 // V22
    const val PSYBASS: Long = 1L shl 6 // V23
    const val ADAT_IN: Long = 1L shl 7 // V24
    const val UPMIX: Long = 1L shl 8 // V25
    const val UPMIX_PRESENCE: Long = 1L shl 9 // V26
    const val SPDIF_IN4: Long = 1L shl 10 // V28 (also first-order LP/HP)
    const val SUBHARM: Long = 1L shl 11 // V29
    const val SUBHARM_EXT: Long = 1L shl 12 // V30
    const val TUBE: Long = 1L shl 13 // V31
    const val LIMITER: Long = 1L shl 14 // V32

    /** Every feature of the V32 layout (core `WIRE_FEATURES_ALL`). */
    const val ALL: Long = 32767L // (1 shl 15) - 1

    /** The state holds a decoded image of another wire format (display only). */
    fun isDisplayOnly(s: DspState): Boolean = s.wireRead == WIRE_READ_OLDER || s.wireRead == WIRE_READ_NEWER

    /**
     * Whether the connected firmware has [feature]. True whenever the state
     * is this core's own format or nothing was decoded yet (no gating then:
     * the existing `usable` checks apply), so only an older image hides anything.
     */
    fun has(s: DspState, feature: Long): Boolean = s.wireRead != WIRE_READ_OLDER || (s.wireFeatures and feature) != 0L
}
