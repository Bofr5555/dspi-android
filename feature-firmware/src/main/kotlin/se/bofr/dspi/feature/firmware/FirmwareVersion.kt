// Port of DSPi-Console (macOS) `DSPi Console/FirmwareVersion.swift` @ v1.1.6-beta5
// (b5fdeac), plus `FirmwareMatch` and `DSPViewModel.firmwareMatch`.
// Deviation: `expected` is the Console version this port tracks
// ([CONSOLE_VERSION]) instead of the app bundle's marketing version, because
// the Android app has its own version scheme.
package se.bofr.dspi.feature.firmware

import se.bofr.dspi.core.DspState
import se.bofr.dspi.core.DspiConstants

/**
 * A firmware version as three plain numbers plus a pre-release ordinal.
 * 0 = final release, 1..255 = beta N, or [EARLY_BETA] for a beta built
 * before the ordinal existed.
 */
data class FirmwareVersion(val major: Int, val minor: Int, val patch: Int, val beta: Int = 0) : Comparable<FirmwareVersion> {

    /** "1.1.7", "1.1.6 beta 2", "1.1.6 early beta". */
    val description: String
        get() = when (beta) {
            0 -> "$major.$minor.$patch"
            EARLY_BETA -> "$major.$minor.$patch early beta"
            else -> "$major.$minor.$patch beta $beta"
        }

    /** Tag spelling ("1.1.6-beta5"); null for an early beta, which names no single tag. */
    val tagSuffix: String?
        get() = when (beta) {
            0 -> "$major.$minor.$patch"
            EARLY_BETA -> null
            else -> "$major.$minor.$patch-beta$beta"
        }

    /** Final is encoded as 0 but outranks every beta of its patch. */
    private val betaRank: Int get() = if (beta == 0) 256 else beta

    override fun compareTo(other: FirmwareVersion): Int = compareValuesBy(this, other, {
        it.major
    }, { it.minor }, { it.patch }, { it.betaRank })

    override fun toString(): String = description

    companion object {
        /** A beta that could not say which one it is (1.1.6 beta 1 and 2). */
        const val EARLY_BETA = -1

        /** The first release whose every build reports the beta ordinal. */
        private val FIRST_WITH_ORDINAL = Triple(1, 1, 6)

        /** The DSPi Console release this port follows; its firmware is bundled. */
        const val CONSOLE_VERSION = "1.1.6-beta5"

        /** The firmware version this Console expects a device to be running. */
        val expected: FirmwareVersion? = parse(CONSOLE_VERSION)

        /**
         * Parses "1.1.7" and "1.1.6-beta2". A missing patch reads as 0 and an
         * unrecognised suffix as a final release. Null when there is no leading number.
         */
        fun parse(string: String): FirmwareVersion? {
            val numeric = string.takeWhile { it.isDigit() || it == '.' }
            val fields = numeric.split('.').filter { it.isNotEmpty() }.map { it.toIntOrNull() }
            val major = fields.firstOrNull() ?: return null
            val minor = fields.getOrNull(1) ?: 0
            val patch = fields.getOrNull(2) ?: 0
            val suffix = string.drop(numeric.length).lowercase().dropWhile { it == '-' || it == '.' || it == ' ' }
            val beta = if (suffix.startsWith("beta")) suffix.drop(4).takeWhile { it.isDigit() }.toIntOrNull() ?: 0 else 0
            return FirmwareVersion(major, minor, patch, beta)
        }

        /**
         * Decodes a REQ_GET_PLATFORM reply into its platform byte and version
         * (7 bytes; older firmware answers with 6 or 4).
         */
        fun fromPlatformReply(bytes: ByteArray): Pair<Int, FirmwareVersion>? {
            if (bytes.size < 4) return null
            fun u(i: Int) = bytes[i].toInt() and 0xFF
            val major = u(1)
            val minor = if (bytes.size >= 6) u(4) else u(2) shr 4
            val patch = if (bytes.size >= 6) u(5) else u(2) and 0x0F
            val beta = when {
                bytes.size >= 7 -> u(6)
                compareValuesBy(Triple(major, minor, patch), FIRST_WITH_ORDINAL, {
                    it.first
                }, { it.second }, { it.third }) >= 0 -> EARLY_BETA
                else -> 0
            }
            return u(0) to FirmwareVersion(major, minor, patch, beta)
        }

        /** What the core decoded from the platform reply; null when nothing is known (Linux `deviceFw`). */
        fun of(state: DspState): FirmwareVersion? {
            if (state.fwMajor == 0 && state.fwMinor == 0 && state.fwPatch == 0) return null
            val beta = if (state.fwBeta == DspiConstants.FW_BETA_EARLY) EARLY_BETA else state.fwBeta
            return FirmwareVersion(state.fwMajor, state.fwMinor, state.fwPatch, beta)
        }
    }
}

/** How a connected device's firmware compares with what this Console expects. */
enum class FirmwareMatch {
    MATCH,
    DEVICE_OLDER,
    DEVICE_NEWER;

    companion object {
        /** `DSPViewModel.firmwareMatch`: null when not connected or unknown. */
        fun of(connected: Boolean, device: FirmwareVersion?, expected: FirmwareVersion? = FirmwareVersion.expected): FirmwareMatch? {
            if (!connected || device == null || expected == null) return null
            return when {
                device == expected -> MATCH
                device < expected -> DEVICE_OLDER
                else -> DEVICE_NEWER
            }
        }
    }
}
