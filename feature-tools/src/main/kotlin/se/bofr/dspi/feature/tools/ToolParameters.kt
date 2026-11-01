// Port of DSPi-Console:DSPi Console/ToolParameters.swift @ v1.1.6-beta5 (b5fdeac),
// plus the tool constants of Constants.swift (ranges, defaults, tube rows,
// rectifier rows) and the starting-point tables of the tool views.
//
// The Swift file splits each module's sweepable parameters into its own
// ObservableObject so a drag re-renders one window. Here the core owns the
// state (DspState) and Compose recomposes per reader, so each store is a plain
// immutable projection of DspState (`from(state)`), with the ranges and
// defaults of the Swift constants next to it. Ranges are the firmware's (the
// core clamps the same way, commands.rs), so a typed or dragged value is
// clamped here exactly as on the device.
package se.bofr.dspi.feature.tools

import se.bofr.dspi.core.DspState
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.ui.ValueFormat

// ═══ Shared mask helpers ════════════════════════════════════════════════

/** Mask helpers shared by the per-output modules (the views' computed masks). */
object ToolMasks {
    /** Mask with every valid output bit set (`allOutputsMask`). */
    fun allOutputs(outputCount: Int): Int = if (outputCount >= 16) 0xFFFF else (1 shl outputCount.coerceAtLeast(0)) - 1

    /** Index of the PDM sub: the last output (`pdmOutputIndex`; 8 on RP2350, 4 on RP2040). */
    fun pdmOutputIndex(outputCount: Int): Int = outputCount - 1

    /** All outputs but the PDM sub (`excludeSubMask`). */
    fun excludeSub(outputCount: Int): Int = allOutputs(outputCount) and (1 shl pdmOutputIndex(outputCount)).inv()

    /** The PDM sub alone (`subOnlyMask`). */
    fun subOnly(outputCount: Int): Int = 1 shl pdmOutputIndex(outputCount)

    /** Stereo output pairs (S/PDIF instances): 2 on RP2040, 4 on RP2350 (`numOutputSlots`). */
    fun pairCount(outputCount: Int): Int = maxOf(1, outputCount / 2)

    /** Mask with every valid pair bit set (`allPairsMask`). */
    fun allPairs(pairCount: Int): Int = if (pairCount >= 8) 0xFF else (1 shl pairCount) - 1

    fun isOn(mask: Int, bit: Int): Boolean = (mask shr bit) and 1 != 0

    /** `set<Module>OutputChannel(k, enabled:)`: one bit set or cleared. */
    fun with(mask: Int, bit: Int, on: Boolean): Int = if (on) mask or (1 shl bit) else mask and (1 shl bit).inv()

    /** A chip click: the bit flipped. */
    fun toggled(mask: Int, bit: Int): Int = mask xor (1 shl bit)
}

/** The firmware wire-format gates of DSPViewModel.swift (`firmwareSupports*`). */
object ToolFirmware {
    fun supportsLevellerMasks(v: Int) = v >= 18

    fun supportsLoudnessMask(v: Int) = v >= 19

    fun supportsCrossfeedMask(v: Int) = v >= 20

    fun supportsPsybass(v: Int) = v >= 23

    fun supportsSubharm(v: Int) = v >= 29

    fun supportsSubharmExtended(v: Int) = v >= 30

    fun supportsTube(v: Int) = v >= 31

    fun supportsLimiter(v: Int) = v >= 32

    /** V26 and RP2350 (platformId 1) only. */
    fun supportsUpmixer(v: Int, platformId: Int) = v >= 26 && platformId == 1
}

// ═══ Loudness ═══════════════════════════════════════════════════════════

/** `LoudnessParameters`. `loudnessEnabled` stays on the state. */
data class LoudnessParameters(
    val refSPL: Float = 83f,
    val intensity: Float = 100f,
    /** Bit k compensates output k (V19+). */
    val outputMask: Int = LOUDNESS_DEFAULT_OUTPUT_MASK
) {
    companion object {
        const val LOUDNESS_DEFAULT_OUTPUT_MASK = 0xFFFF

        /** "Slot 1 only (Headphones)". */
        const val HEADPHONES_MASK = 0x0003
        val REF_SPL_RANGE = 40f..100f
        val INTENSITY_RANGE = 0f..200f

        fun from(s: DspState) = LoudnessParameters(s.loudnessRefSpl, s.loudnessIntensity, s.loudnessOutputMask)
    }
}

// ═══ Crossfeed ══════════════════════════════════════════════════════════

/** CrossfeedView.swift `CrossfeedPreset`. */
data class CrossfeedPreset(val name: String, val description: String, val freq: Float, val feed: Float)

/** `CrossfeedParameters`. `crossfeedEnabled` stays on the state. */
data class CrossfeedParameters(
    val preset: Int = 0,
    val freq: Float = 700f,
    val feed: Float = 4.5f,
    val itd: Boolean = true,
    /** Bit p crossfeeds output pair p (outputs 2p / 2p+1), V20+. */
    val outputMask: Int = CROSSFEED_DEFAULT_OUTPUT_MASK
) {
    val isCustom: Boolean get() = preset == PRESET_CUSTOM

    companion object {
        const val CROSSFEED_DEFAULT_OUTPUT_MASK = 0x01
        const val PRESET_CUSTOM = 3
        val FREQ_RANGE = 500f..2000f
        val FEED_RANGE = 0f..15f

        val presets =
            listOf(
                CrossfeedPreset("Default", "700 Hz / 4.5 dB - Balanced, most popular", 700f, 4.5f),
                CrossfeedPreset("Chu Moy", "700 Hz / 6.0 dB - Stronger spatial effect", 700f, 6.0f),
                CrossfeedPreset("Jan Meier", "650 Hz / 9.5 dB - Natural speaker-like", 650f, 9.5f),
                CrossfeedPreset("Custom", "User-defined parameters", 700f, 4.5f)
            )

        fun from(s: DspState) =
            CrossfeedParameters(s.crossfeedPreset, s.crossfeedFreq, s.crossfeedFeed, s.crossfeedItd, s.crossfeedOutputPairMask)
    }
}

// ═══ Volume leveller ════════════════════════════════════════════════════

/** `LevellerParameters`. `levellerEnabled` stays on the state. */
data class LevellerParameters(
    val amount: Float = 50f,
    /** 0 = Slow, 1 = Medium, 2 = Fast. */
    val speed: Int = 0,
    val maxGainDB: Float = 15f,
    val lookahead: Boolean = true,
    val gateDB: Float = -96f,
    /** V18: bit k = input channel k. */
    val detectorMask: Int = 0xFF,
    val applyMask: Int = 0xFF
) {
    companion object {
        val AMOUNT_RANGE = 0f..100f
        val MAX_GAIN_RANGE = 0f..35f
        val GATE_RANGE = -96f..0f

        /** The CHANNELS presets menu: (title, detector, apply). */
        val maskPresets =
            listOf(
                Triple("All channels (Night mode)", 0xFF, 0xFF),
                Triple("Center only (Dialog boost)", 0x04, 0x04),
                Triple("Front L / R only", 0x03, 0x03)
            )

        fun from(s: DspState) = LevellerParameters(
            s.levellerAmount,
            s.levellerSpeed,
            s.levellerMaxGainDb,
            s.levellerLookahead,
            s.levellerGateDb,
            s.levellerDetectorMask,
            s.levellerApplyMask
        )

        /** VolumeLevellerView `speedDescription`. */
        fun speedDescription(speed: Int): String = when (speed) {
            0 -> "Slow — Gentle response for music and wide dynamic range content."
            2 -> "Fast — Tight response for speech, dialogue, and podcasts."
            else -> "Medium — Balanced response for general purpose use."
        }

        /**
         * Inputs shown in the mask grid: the live active count, 2..8 (Qt
         * `inputCount`; macOS `effectiveInputChannels`).
         */
        fun channelCount(numInputChannels: Int, activeInputChannels: Int): Int =
            minOf(numInputChannels, maxOf(2, activeInputChannels)).coerceIn(2, 8)

        /** The CHANNELS section only makes sense with more than two inputs. */
        fun showMasks(numInputChannels: Int, channelCount: Int): Boolean = numInputChannels > 2 && channelCount > 2
    }
}

// ═══ Psychoacoustic bass ════════════════════════════════════════════════

/** PsychoacousticBassView.swift `PsybassStartingPoint`. */
data class PsybassStartingPoint(
    val name: String,
    val detail: String,
    val cutoff: Float,
    val harmonics: Float,
    val drive: Float,
    val character: Float,
    val original: Float
)

/** `PsybassParameters`. `psybassEnabled` stays on the state. */
data class PsybassParameters(
    val cutoffHz: Float = 80f,
    val harmonicsDB: Float = 0f,
    val driveDB: Float = 6f,
    val characterPct: Float = 50f,
    val originalDB: Float = 0f,
    val outputMask: Int = PSYBASS_DEFAULT_OUTPUT_MASK
) {
    companion object {
        const val PSYBASS_DEFAULT_OUTPUT_MASK = 0xFFFF
        val CUTOFF_RANGE = 30f..300f
        val HARMONICS_RANGE = -24f..12f
        val DRIVE_RANGE = 0f..18f
        val CHARACTER_RANGE = 0f..100f
        val ORIGINAL_RANGE = -60f..0f

        val startingPoints =
            listOf(
                PsybassStartingPoint("Bookshelf speakers", "Gentle low-end help", 60f, 0f, 6f, 50f, 0f),
                PsybassStartingPoint("Small Bluetooth", "Portable speaker", 100f, 3f, 9f, 40f, -12f),
                PsybassStartingPoint("Laptop / tablet", "Tiny drivers, protect them", 180f, 6f, 12f, 50f, -24f),
                PsybassStartingPoint("Headphone bass feel", "Extra sub sensation", 45f, -3f, 6f, 30f, 0f)
            )

        fun from(s: DspState) = PsybassParameters(
            s.psybassCutoffHz,
            s.psybassHarmonicsDb,
            s.psybassDriveDb,
            s.psybassCharacterPct,
            s.psybassOriginalDb,
            s.psybassOutputMask
        )
    }
}

// ═══ Subharmonic synthesizer ════════════════════════════════════════════

/** SubharmonicSynthView.swift `SubharmStartingPoint`. */
data class SubharmStartingPoint(val name: String, val detail: String, val low: Float, val high: Float, val boost: Float)

/** `SubharmParameters` (V29, extended V30), by `SUBHARM_PARAM_*` id of the core. */
data class SubharmParameters(
    val enabled: Boolean = false,
    val lowDB: Float = 0f,
    val highDB: Float = 0f,
    val topDB: Float = SUBHARM_LEVEL_MIN,
    val boostDB: Float = 0f,
    val outputMask: Int = SUBHARM_DEFAULT_OUTPUT_MASK,
    val selectMode: Int = SUBHARM_SELECT_ALL,
    val selectDepthPct: Float = 100f,
    val selectHoldMs: Float = 150f,
    /** dBFS; 0 = off. */
    val ceilingDB: Float = 0f,
    val linkPairs: Boolean = true,
    /** Runtime-only monitor, never persisted. */
    val solo: Boolean = false
) {
    val ceilingOff: Boolean get() = ceilingDB >= SUBHARM_CEILING_MAX
    val selectivityActive: Boolean get() = selectMode != SUBHARM_SELECT_ALL

    companion object {
        const val SUBHARM_DEFAULT_OUTPUT_MASK = 0xFFFF
        const val SUBHARM_LEVEL_MIN = -30f
        const val SUBHARM_LEVEL_MAX = 12f
        const val SUBHARM_BOOST_MIN = 0f
        const val SUBHARM_BOOST_MAX = 6f
        const val SUBHARM_SELECT_ALL = 0
        const val SUBHARM_SELECT_PERCUSSIVE = 1
        const val SUBHARM_SELECT_SUSTAINED = 2
        const val SUBHARM_DEPTH_MIN = 0f
        const val SUBHARM_DEPTH_MAX = 100f
        const val SUBHARM_HOLD_MIN_MS = 50f
        const val SUBHARM_HOLD_MAX_MS = 400f
        const val SUBHARM_CEILING_MIN = -40f
        const val SUBHARM_CEILING_MAX = 0f
        const val SUBHARM_BOOST_HZ = 70f
        const val SUBHARM_BOOST_Q = 0.9f

        /** The divider's natural gain: a band at 0 dB is 1.4 dB below its program. */
        const val SUBHARM_DIVIDER_GAIN = 0.849f

        val LEVEL_RANGE = SUBHARM_LEVEL_MIN..SUBHARM_LEVEL_MAX
        val BOOST_RANGE = SUBHARM_BOOST_MIN..SUBHARM_BOOST_MAX
        val DEPTH_RANGE = SUBHARM_DEPTH_MIN..SUBHARM_DEPTH_MAX
        val HOLD_RANGE = SUBHARM_HOLD_MIN_MS..SUBHARM_HOLD_MAX_MS
        val CEILING_RANGE = SUBHARM_CEILING_MIN..SUBHARM_CEILING_MAX

        /** None of them uses the top band, so applying one sets it back to its floor. */
        val startingPoints =
            listOf(
                SubharmStartingPoint("Subwoofer feed", "Subtle added weight", -6f, -6f, 0f),
                SubharmStartingPoint("Club / large PA", "Lower two bands, full", 0f, 0f, 3f),
                SubharmStartingPoint("Thin recordings", "Add a missing bottom", 0f, -6f, 3f),
                SubharmStartingPoint("Cinema LFE", "Lowest octave only", 3f, -12f, 0f)
            )

        fun from(s: DspState): SubharmParameters {
            val p = s.subharm
            fun at(id: Int, d: Float) = p.getOrElse(id) { d }
            return SubharmParameters(
                enabled = at(DspiConstants.SUBHARM_PARAM_ENABLED, 0f) != 0f,
                lowDB = at(DspiConstants.SUBHARM_PARAM_LOW, 0f),
                highDB = at(DspiConstants.SUBHARM_PARAM_HIGH, 0f),
                topDB = at(DspiConstants.SUBHARM_PARAM_TOP, SUBHARM_LEVEL_MIN),
                boostDB = at(DspiConstants.SUBHARM_PARAM_BOOST, 0f),
                outputMask = at(DspiConstants.SUBHARM_PARAM_MASK, SUBHARM_DEFAULT_OUTPUT_MASK.toFloat()).toInt(),
                selectMode = at(DspiConstants.SUBHARM_PARAM_SELECT, 0f).toInt(),
                selectDepthPct = at(DspiConstants.SUBHARM_PARAM_DEPTH, 100f),
                selectHoldMs = at(DspiConstants.SUBHARM_PARAM_HOLD, 150f),
                ceilingDB = at(DspiConstants.SUBHARM_PARAM_CEILING, 0f),
                linkPairs = at(DspiConstants.SUBHARM_PARAM_LINK, 1f) != 0f,
                solo = s.subharmSolo
            )
        }

        /** SubharmonicSynthView `selectivitySummary`. */
        fun selectivitySummary(mode: Int): String = when (mode) {
            SUBHARM_SELECT_PERCUSSIVE -> "A short sub burst after each attack - extends kicks, not the bass line."
            SUBHARM_SELECT_SUSTAINED -> "The sub opens once a band has been ringing - extends bass notes, not kicks."
            else -> "Every band signal is treated alike."
        }

        /** SubharmonicSynthView `selectivityHelp`. */
        fun selectivityHelp(mode: Int): String = when (mode) {
            SUBHARM_SELECT_PERCUSSIVE ->
                "A short sub burst after each attack, so a kick can be extended without extending the bass line under it. " +
                    "The most robust of the three: a held note gets nothing between kicks."
            SUBHARM_SELECT_SUSTAINED ->
                "The sub opens only after a band has been ringing, so bass notes are extended and kicks are not. " +
                    "A kick landing in the same band ducks the held note's sub, which pumps on a four-on-the-floor line - " +
                    "shorten the hold or lower the depth to soften it."
            else ->
                "Every band signal is treated alike. Choose percussive or sustained to weight the synthesized sub toward one " +
                    "kind of bass material; the gate decides per band from time behaviour, so it cannot separate two sources " +
                    "sounding at once in the same band."
        }

        /** The HEADROOM COST readout: "+4.2 dB", or "none". */
        fun headroomText(db: Float): String = if (db > 0f) ValueFormat.signed(db, 1) + " dB" else "none"
    }
}

// ═══ Tube modeller ══════════════════════════════════════════════════════

/** Constants.swift `TubeTypeRow`. */
data class TubeTypeRow(
    val name: String,
    val style: String,
    val biasPct: Float,
    val asymDB: Float,
    val hardnessPct: Float,
    val sagPct: Float,
    val pushPull: Boolean = false
) {
    /** The first of the equivalent names, short enough for a chip. */
    val shortName: String get() = name.split(" / ").first()
}

/** Constants.swift `TubeRectifierRow`. */
data class TubeRectifierRow(val name: String, val depthScale: Float, val attackMs: Float, val releaseMs: Float)

/** TubeModellerView.swift `TubeStartingPoint`. */
data class TubeStartingPoint(
    val name: String,
    val detail: String,
    val tubeType: Int,
    val driveDB: Float,
    val rectifier: Int,
    val xfmr: Boolean,
    val damping: Float = TubeParameters.TUBE_DEFAULT_XFMR_DAMPING,
    val resHz: Float = TubeParameters.TUBE_DEFAULT_XFMR_RES_HZ
)

/** `TubeParameters` (V31), by `TUBE_PARAM_*` index. */
data class TubeParameters(
    val enabled: Boolean = false,
    val outputMask: Int = TUBE_DEFAULT_OUTPUT_MASK,
    /** 0 = Custom, 1..16 = a row of [TUBE_TYPE_ROWS]. */
    val type: Int = TUBE_DEFAULT_TUBE_TYPE,
    val driveDB: Float = TUBE_DEFAULT_DRIVE_DB,
    val biasPct: Float = TUBE_DEFAULT_BIAS_PCT,
    val asymDB: Float = TUBE_DEFAULT_ASYM_DB,
    val hardnessPct: Float = TUBE_DEFAULT_HARDNESS_PCT,
    val sagPct: Float = TUBE_DEFAULT_SAG_PCT,
    val rectifier: Int = TUBE_DEFAULT_RECTIFIER,
    val xfmrEnabled: Boolean = TUBE_DEFAULT_XFMR_ENABLED,
    val xfmrDamping: Float = TUBE_DEFAULT_XFMR_DAMPING,
    val xfmrResHz: Float = TUBE_DEFAULT_XFMR_RES_HZ,
    val mixPct: Float = TUBE_DEFAULT_MIX_PCT,
    val trimDB: Float = TUBE_DEFAULT_TRIM_DB
) {
    val selectedRow: TubeTypeRow? get() = tubeTypeRow(type)

    /**
     * TubeModellerView `xfmrLift`: the spec's formula for a source impedance of
     * Zn/df against a speaker rising to 4x nominal at resonance and 2x at the top.
     */
    val xfmrLift: Pair<Float, Float>
        get() {
            val df = maxOf(xfmrDamping, TUBE_XFMR_DAMPING_MIN)
            val bump = 4 * (df + 1) / (4 * df + 1)
            val top = 2 * (df + 1) / (2 * df + 1)
            return Pair(20 * kotlin.math.log10(bump), 20 * kotlin.math.log10(top))
        }

    fun shaper() = TubeShaper(driveDB, biasPct, asymDB, hardnessPct, mixPct, trimDB)

    /** Basic mode showcase caption. */
    val showcaseCaption: String
        get() {
            val row = selectedRow ?: return "Character set by hand. Pick a tube to load one, or fine-tune it in Advanced."
            if (row.pushPull && !xfmrEnabled) return "${row.style}. Meant for use with the output stage, in Advanced."
            return "${row.style}."
        }

    /** Advanced mode caption under the Tube picker. */
    val tubeTypeCaption: String
        get() {
            val row = selectedRow ?: return "Character controls as set, no tube row applied."
            if (row.pushPull && !xfmrEnabled) return "${row.style}. Meant for use with the output stage on."
            return "${row.style}."
        }

    val rectifierSummary: String
        get() {
            val r = rectifier
            if (r <= TUBE_RECT_SOLID_STATE || r >= TUBE_RECTIFIER_ROWS.size) {
                return "No sag: the supply holds up however hard the stage is driven."
            }
            val row = TUBE_RECTIFIER_ROWS[r]
            return "Sag depth x${ToolFormat.fixed(row.depthScale, 1)}, ${ToolFormat.fixed(row.attackMs, 0)} ms attack, " +
                "${ToolFormat.fixed(row.releaseMs, 0)} ms release."
        }

    companion object {
        const val TUBE_DRIVE_MIN = -30f
        const val TUBE_DRIVE_MAX = 24f
        const val TUBE_BIAS_MIN = -100f
        const val TUBE_BIAS_MAX = 100f
        const val TUBE_ASYM_MIN = -12f
        const val TUBE_ASYM_MAX = 12f
        const val TUBE_HARDNESS_MIN = 0f
        const val TUBE_HARDNESS_MAX = 100f
        const val TUBE_SAG_MIN = 0f
        const val TUBE_SAG_MAX = 100f
        const val TUBE_XFMR_DAMPING_MIN = 1f
        const val TUBE_XFMR_DAMPING_MAX = 20f
        const val TUBE_XFMR_RES_MIN = 30f
        const val TUBE_XFMR_RES_MAX = 150f
        const val TUBE_MIX_MIN = 0f
        const val TUBE_MIX_MAX = 100f
        const val TUBE_TRIM_MIN = -12f
        const val TUBE_TRIM_MAX = 12f

        const val TUBE_DEFAULT_TUBE_TYPE = 1
        const val TUBE_DEFAULT_RECTIFIER = 1
        const val TUBE_DEFAULT_OUTPUT_MASK = 0xFFFF
        const val TUBE_DEFAULT_DRIVE_DB = -12f
        const val TUBE_DEFAULT_BIAS_PCT = 10f
        const val TUBE_DEFAULT_ASYM_DB = 3f
        const val TUBE_DEFAULT_HARDNESS_PCT = 40f
        const val TUBE_DEFAULT_SAG_PCT = 15f
        const val TUBE_DEFAULT_XFMR_ENABLED = true
        const val TUBE_DEFAULT_XFMR_DAMPING = 2f
        const val TUBE_DEFAULT_XFMR_RES_HZ = 95f
        const val TUBE_DEFAULT_MIX_PCT = 100f
        const val TUBE_DEFAULT_TRIM_DB = 0f

        const val TUBE_TYPE_CUSTOM = 0
        const val TUBE_TYPE_MAX = 16
        const val TUBE_RECT_SOLID_STATE = 0
        const val TUBE_RECT_MAX = 3

        /** Indexed by tube_type; index 0 is Custom and carries no row. */
        val TUBE_TYPE_ROWS: List<TubeTypeRow?> =
            listOf(
                null,
                TubeTypeRow("12AX7 / ECC83", "High-gain preamp triode", 10f, 3f, 40f, 15f),
                TubeTypeRow("5751", "Cooler 12AX7", 8f, 3f, 35f, 12f),
                TubeTypeRow("12AT7 / ECC81", "Medium-gain driver, more odd-order", 5f, 2f, 55f, 10f),
                TubeTypeRow("12AY7", "Tweed front end, gentle", 7f, 4f, 25f, 15f),
                TubeTypeRow("12AU7 / ECC82", "Clean line stage", 5f, 5f, 20f, 8f),
                TubeTypeRow("6SN7", "Octal hi-fi line stage, sweet", 7f, 6f, 15f, 10f),
                TubeTypeRow("6SL7", "Octal high-mu, rounder knee", 10f, 3f, 30f, 15f),
                TubeTypeRow("6DJ8 / ECC88 / 6922", "Clean, hard when pushed", 3f, 2f, 60f, 5f),
                TubeTypeRow("EF86 / 6267", "Pentode preamp, symmetric bite", 2f, 0f, 75f, 12f),
                TubeTypeRow("6SJ7", "Octal pentode, softer than EF86", 3f, 1f, 65f, 15f),
                TubeTypeRow("EL84 / 6BQ5", "Push-pull power, chimey", 0f, 0f, 50f, 25f, pushPull = true),
                TubeTypeRow("EL34", "Push-pull power, mid crunch, deep sag", 0f, 0f, 60f, 30f, pushPull = true),
                TubeTypeRow("6L6 / 5881", "Push-pull power, tight", 0f, 0f, 55f, 18f, pushPull = true),
                TubeTypeRow("6V6", "Push-pull power, early breakup, heavy sag", 0f, 0f, 35f, 30f, pushPull = true),
                TubeTypeRow("KT88 / 6550", "Push-pull hi-fi power, near linear", 0f, 0f, 45f, 10f, pushPull = true),
                TubeTypeRow("300B / 2A3", "Single-ended DHT, pure even harmonics", 12f, 6f, 10f, 12f)
            )

        val TUBE_RECTIFIER_ROWS =
            listOf(
                TubeRectifierRow("Solid state", 0f, 0f, 0f),
                TubeRectifierRow("GZ34 / 5AR4", 0.6f, 5f, 120f),
                TubeRectifierRow("5U4", 1.0f, 8f, 200f),
                TubeRectifierRow("5Y3", 1.3f, 10f, 300f)
            )

        /** The rectifier segments (short labels). */
        val RECTIFIER_SEGMENTS = listOf("Solid state", "GZ34", "5U4", "5Y3")

        /** The three shelf groups (Basic) / picker sections (Advanced). */
        val TUBE_GROUPS = listOf("Preamp triodes" to (1..8), "Preamp pentodes" to (9..10), "Power stages" to (11..16))

        /** Each sets every non-character control, mix and trim included. */
        val startingPoints =
            listOf(
                TubeStartingPoint(
                    "Clean default",
                    "12AX7, level-neutral, output stage on",
                    1,
                    TUBE_DEFAULT_DRIVE_DB,
                    1,
                    TUBE_DEFAULT_XFMR_ENABLED
                ),
                TubeStartingPoint("Warm hi-fi", "12AU7 line stage, tightly damped", 5, -3f, 1, true, damping = 10f),
                TubeStartingPoint("Single-ended sweetness", "300B, loose damping", 16, 3f, 1, true, damping = 2f),
                TubeStartingPoint("Guitar-amp style", "12AX7 pushed, 5U4, loose damping", 1, 15f, 2, true, damping = 2f, resHz = 100f),
                TubeStartingPoint("Push-pull power", "EL34 with the output stage on", 12, 0f, 1, true, damping = 6f)
            )

        fun tubeTypeRow(type: Int): TubeTypeRow? = if (type > 0 && type < TUBE_TYPE_ROWS.size) TUBE_TYPE_ROWS[type] else null

        /** Constants.swift `tubeTypeName`. */
        fun tubeTypeName(type: Int): String = when {
            type == TUBE_TYPE_CUSTOM -> "Custom"
            tubeTypeRow(type) != null -> tubeTypeRow(type)!!.name
            else -> "Type $type"
        }

        fun tubeRectifierName(rect: Int): String = TUBE_RECTIFIER_ROWS.getOrNull(rect)?.name ?: "Rectifier $rect"

        fun from(s: DspState): TubeParameters {
            val p = s.tube
            fun at(i: Int, d: Float) = p.getOrElse(i) { d }
            return TubeParameters(
                enabled = at(DspiConstants.TUBE_PARAM_ENABLED, 0f) != 0f,
                outputMask = at(DspiConstants.TUBE_PARAM_MASK, TUBE_DEFAULT_OUTPUT_MASK.toFloat()).toInt(),
                type = at(DspiConstants.TUBE_PARAM_TYPE, TUBE_DEFAULT_TUBE_TYPE.toFloat()).toInt(),
                driveDB = at(DspiConstants.TUBE_PARAM_DRIVE, TUBE_DEFAULT_DRIVE_DB),
                biasPct = at(DspiConstants.TUBE_PARAM_BIAS, TUBE_DEFAULT_BIAS_PCT),
                asymDB = at(DspiConstants.TUBE_PARAM_ASYM, TUBE_DEFAULT_ASYM_DB),
                hardnessPct = at(DspiConstants.TUBE_PARAM_HARDNESS, TUBE_DEFAULT_HARDNESS_PCT),
                sagPct = at(DspiConstants.TUBE_PARAM_SAG, TUBE_DEFAULT_SAG_PCT),
                rectifier = at(DspiConstants.TUBE_PARAM_RECTIFIER, TUBE_DEFAULT_RECTIFIER.toFloat()).toInt(),
                xfmrEnabled = at(DspiConstants.TUBE_PARAM_XFMR, 1f) != 0f,
                xfmrDamping = at(DspiConstants.TUBE_PARAM_DAMPING, TUBE_DEFAULT_XFMR_DAMPING),
                xfmrResHz = at(DspiConstants.TUBE_PARAM_RESONANCE, TUBE_DEFAULT_XFMR_RES_HZ),
                mixPct = at(DspiConstants.TUBE_PARAM_MIX, TUBE_DEFAULT_MIX_PCT),
                trimDB = at(DspiConstants.TUBE_PARAM_TRIM, TUBE_DEFAULT_TRIM_DB)
            )
        }
    }
}

// ═══ Output limiter ═════════════════════════════════════════════════════

/** One output's limiter settings (`LimiterOutputSettings`). */
data class LimiterOutputSettings(
    val enabled: Boolean = false,
    val thresholdDB: Float = LimiterParameters.LIMITER_DEFAULT_THRESHOLD_DB,
    val releaseMs: Float = LimiterParameters.LIMITER_DEFAULT_RELEASE_MS,
    /** 0 = unlinked, 1..4 = link group. */
    val linkGroup: Int = 0
)

/**
 * `LimiterGang`: the firmware's link-group ganging (limiter.c). The bridge's
 * published state now mirrors the ganging (contract TOOLS C), so the views do
 * not use this; it stays as the tested oracle of the macOS semantics
 * (ToolParametersTest, ToolBridgeMappingTest). Outputs
 * sharing a non-zero group share enable, threshold and release. Each function
 * works on the first `count` entries.
 */
object LimiterGang {
    /** Lowest-index output other than [out] in [group], or null. */
    fun peer(out: Int, group: Int, outs: List<LimiterOutputSettings>, count: Int): Int? {
        if (group == 0) return null
        return (0 until minOf(count, outs.size)).firstOrNull { it != out && outs[it].linkGroup == group }
    }

    /** An enable, threshold or release edit on [out] reaches every member of its group. */
    fun edit(
        out: Int,
        outs: List<LimiterOutputSettings>,
        count: Int,
        change: (LimiterOutputSettings) -> LimiterOutputSettings
    ): List<LimiterOutputSettings> {
        val group = outs[out].linkGroup
        return outs.mapIndexed { k, s -> if (k < count && (k == out || (group != 0 && s.linkGroup == group))) change(s) else s }
    }

    /** Joining a group adopts its settings; the first member keeps its own and leaving keeps the current ones. */
    fun setGroup(out: Int, group: Int, outs: List<LimiterOutputSettings>, count: Int): List<LimiterOutputSettings> {
        val joined = outs.toMutableList()
        joined[out] = joined[out].copy(linkGroup = group)
        val p = peer(out, group, joined, count) ?: return joined
        joined[out] = joined[out].copy(enabled = joined[p].enabled, thresholdDB = joined[p].thresholdDB, releaseMs = joined[p].releaseMs)
        return joined
    }

    /** What the firmware does to a raw bulk or preset restore: every member copies its group's lowest member. */
    fun gangAll(outs: List<LimiterOutputSettings>, count: Int): List<LimiterOutputSettings> {
        val r = outs.toMutableList()
        for (k in 0 until minOf(count, r.size)) {
            val lead = peer(k, r[k].linkGroup, r, count) ?: continue
            if (lead >= k) continue
            r[k] = r[k].copy(enabled = r[lead].enabled, thresholdDB = r[lead].thresholdDB, releaseMs = r[lead].releaseMs)
        }
        return r
    }

    /** A stored group above the maximum falls back to unlinked. */
    fun storedGroup(raw: Int): Int = if (raw > LimiterParameters.LIMITER_LINK_GROUP_MAX) 0 else raw
}

/** `LimiterParameters` (V32): one entry per output. */
data class LimiterParameters(val outputs: List<LimiterOutputSettings>) {
    val anyEnabled: Boolean get() = outputs.any { it.enabled }

    companion object {
        const val LIMITER_THRESHOLD_MIN = -30f
        const val LIMITER_THRESHOLD_MAX = 0f
        const val LIMITER_RELEASE_MIN = 10f
        const val LIMITER_RELEASE_MAX = 1000f
        const val LIMITER_LINK_GROUP_MAX = 4
        const val LIMITER_DEFAULT_THRESHOLD_DB = -1f
        const val LIMITER_DEFAULT_RELEASE_MS = 100f
        val THRESHOLD_RANGE = LIMITER_THRESHOLD_MIN..LIMITER_THRESHOLD_MAX
        val RELEASE_RANGE = LIMITER_RELEASE_MIN..LIMITER_RELEASE_MAX

        fun from(s: DspState): LimiterParameters = LimiterParameters(
            (0 until s.limiterEnabled.size).map { o ->
                LimiterOutputSettings(
                    enabled = s.limiterEnabled[o],
                    thresholdDB = s.limiterThresholdDb.getOrElse(o) { LIMITER_DEFAULT_THRESHOLD_DB },
                    releaseMs = s.limiterReleaseMs.getOrElse(o) { LIMITER_DEFAULT_RELEASE_MS },
                    linkGroup = LimiterGang.storedGroup(s.limiterLinkGroup.getOrElse(o) { 0 })
                )
            }
        )

        /**
         * `stereoPairGroups`: outputs 1+2 in group 1, 3+4 in group 2 and so on, as
         * far as the four groups go; the PDM sub stays unlinked.
         */
        fun stereoPairGroups(outputCount: Int): List<Int> = (0 until outputCount).map { out ->
            val pair = out / 2 + 1
            if (out < ToolMasks.pdmOutputIndex(outputCount) && pair <= LIMITER_LINK_GROUP_MAX) pair else 0
        }

        /** OutputLimiterSettings `linkSummary`. */
        fun linkSummary(output: Int, group: Int, outs: List<LimiterOutputSettings>, outputCount: Int, name: (Int) -> String): String {
            if (group == 0) return "Not linked."
            val others = (0 until minOf(outputCount, outs.size)).filter { it != output && outs[it].linkGroup == group }.map(name)
            val last = others.lastOrNull() ?: return "No other outputs in group $group."
            val list = if (others.size == 1) last else others.dropLast(1).joinToString(", ") + " and " + last
            return "Linked with $list."
        }

        /** Limiting when the gain reduction reaches 0.05 dB (OutputLimiterCell). */
        fun isLimiting(enabled: Boolean, reductionDB: Float): Boolean = enabled && reductionDB >= 0.05f
    }
}

// ═══ Upmixer ════════════════════════════════════════════════════════════

/** `UpmixParameters` (V25/V26), by `UPMIX_PARAM_*` id. */
data class UpmixParameters(
    val enabled: Boolean = false,
    val centerMode: Int = UPMIX_CENTER_MODE_ADAPTIVE,
    val surroundMode: Int = UPMIX_SURROUND_MODE_ADAPTIVE,
    val strengthPct: Float = 100f,
    val centerWidthPct: Float = 25f,
    val thresholdPct: Float = 30f,
    val attackMs: Float = 10f,
    val releaseMs: Float = 100f,
    val detectorHpfHz: Float = 200f,
    val surroundDelayMs: Float = 12f,
    val surroundHpfHz: Float = 300f,
    val surroundLpfHz: Float = 7000f,
    val decorrPct: Float = 90f,
    val presenceDB: Float = 0f
) {
    val centreOff: Boolean get() = centerMode == UPMIX_CENTER_MODE_OFF
    val centreAdaptive: Boolean get() = centerMode == UPMIX_CENTER_MODE_ADAPTIVE
    val surroundOn: Boolean get() = surroundMode != UPMIX_SURROUND_MODE_OFF

    companion object {
        const val UPMIX_CENTER_MODE_PASSIVE = 0
        const val UPMIX_CENTER_MODE_ADAPTIVE = 1
        const val UPMIX_CENTER_MODE_OFF = 2
        const val UPMIX_SURROUND_MODE_OFF = 0
        const val UPMIX_SURROUND_MODE_PASSIVE = 1
        const val UPMIX_SURROUND_MODE_ADAPTIVE = 2
        const val UPMIX_PARKED_ACTIVE = 0
        const val UPMIX_PARKED_DISABLED = 1
        const val UPMIX_PARKED_NOT_STEREO = 2
        const val UPMIX_PARKED_RATE_TOO_HIGH = 3

        /** Segment order Off | Sinner | Logician -> wire value (the enum order is not the UI order). */
        val CENTER_SEGMENTS = listOf(UPMIX_CENTER_MODE_OFF, UPMIX_CENTER_MODE_PASSIVE, UPMIX_CENTER_MODE_ADAPTIVE)
        val SURROUND_SEGMENTS = listOf(UPMIX_SURROUND_MODE_OFF, UPMIX_SURROUND_MODE_PASSIVE, UPMIX_SURROUND_MODE_ADAPTIVE)
        val ENGINE_LABELS = listOf("Off", "Sinner", "Logician")

        fun from(s: DspState): UpmixParameters {
            val p = s.upmix
            fun at(i: Int, d: Float) = p.getOrElse(i) { d }
            return UpmixParameters(
                enabled = at(DspiConstants.UPMIX_PARAM_ENABLED, 0f) != 0f,
                centerMode = at(DspiConstants.UPMIX_PARAM_CENTER_MODE, 1f).toInt(),
                surroundMode = at(DspiConstants.UPMIX_PARAM_SURROUND_MODE, 2f).toInt(),
                strengthPct = at(DspiConstants.UPMIX_PARAM_STRENGTH, 100f),
                centerWidthPct = at(DspiConstants.UPMIX_PARAM_WIDTH, 25f),
                thresholdPct = at(DspiConstants.UPMIX_PARAM_THRESHOLD, 30f),
                attackMs = at(DspiConstants.UPMIX_PARAM_ATTACK, 10f),
                releaseMs = at(DspiConstants.UPMIX_PARAM_RELEASE, 100f),
                detectorHpfHz = at(DspiConstants.UPMIX_PARAM_DETECTOR_HPF, 200f),
                surroundDelayMs = at(DspiConstants.UPMIX_PARAM_SURROUND_DELAY, 12f),
                surroundHpfHz = at(DspiConstants.UPMIX_PARAM_SURROUND_HPF, 300f),
                surroundLpfHz = at(DspiConstants.UPMIX_PARAM_SURROUND_LPF, 7000f),
                decorrPct = at(DspiConstants.UPMIX_PARAM_DECORRELATION, 90f),
                presenceDB = at(DspiConstants.UPMIX_PARAM_PRESENCE, 0f)
            )
        }

        /** UpmixStatusPane `statusText`. */
        fun statusText(connected: Boolean, active: Boolean, parkedReason: Int): String = when {
            !connected -> "No device connected"
            active -> "Active - processing audio"
            parkedReason == UPMIX_PARKED_DISABLED -> "Idle: upmixer disabled"
            parkedReason == UPMIX_PARKED_NOT_STEREO -> "Idle: input is not stereo"
            parkedReason == UPMIX_PARKED_RATE_TOO_HIGH -> "Idle: sample rate above 48 kHz"
            else -> "Idle"
        }
    }
}
