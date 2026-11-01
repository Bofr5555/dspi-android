package se.bofr.dspi.fake

import se.bofr.dspi.core.CtrlIfaceStatus
import se.bofr.dspi.core.DspState
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.core.DspiConstants.BANDS_PER_CHANNEL
import se.bofr.dspi.core.DspiConstants.BULK_PARAMS_SIZE
import se.bofr.dspi.core.DspiConstants.COMPAT_UNKNOWN
import se.bofr.dspi.core.DspiConstants.DEFAULT_Q
import se.bofr.dspi.core.DspiConstants.FILTER_FLAT
import se.bofr.dspi.core.DspiConstants.MAX_CHANNELS
import se.bofr.dspi.core.DspiConstants.MAX_INPUTS
import se.bofr.dspi.core.DspiConstants.MAX_OUTPUTS
import se.bofr.dspi.core.DspiConstants.MAX_PRESETS
import se.bofr.dspi.core.DspiConstants.MAX_XOVER_BANDS
import se.bofr.dspi.core.FilterParams
import se.bofr.dspi.core.I2cConfig
import se.bofr.dspi.core.UartConfig

/** `FilterParams::default()` (types.rs). */
fun defaultFilterParams() = FilterParams(filterType = FILTER_FLAT, bypass = false, freq = 1000f, q = DEFAULT_Q, gain = 0f, qp = DEFAULT_Q)

/**
 * `DspState::default()` (state.rs @ 4683a2c) — what the core holds before the
 * first fetch, including the `UPMIX_/SUBHARM_/TUBE_DEFAULTS` tables of
 * protocol.rs ([FakeTables]).
 */
fun defaultDspState(): DspState = DspState(
    platformId = 0,
    numChannels = 7,
    numInputChannels = 2,
    numOutputChannels = 5,
    formatVersion = 0,
    fwMajor = 0,
    fwMinor = 0,
    fwPatch = 0,
    fwBeta = 0,
    compat = COMPAT_UNKNOWN,
    preampDb = 0f,
    bypass = false,
    inputPreampDb = List(MAX_INPUTS) { 0f },
    masterVolumeDb = -20f,
    masterVolumeMode = 0,
    userVolumeDb = 0f,
    userMute = false,
    loudnessEnabled = false,
    loudnessRefSpl = 83f,
    loudnessIntensity = 100f,
    loudnessOutputMask = 0,
    crossfeedEnabled = false,
    crossfeedPreset = 0,
    crossfeedFreq = 700f,
    crossfeedFeed = 4.5f,
    crossfeedItd = true,
    crossfeedOutputPairMask = 0,
    channelDelays = List(MAX_CHANNELS) { 0f },
    matrixRouting = List(MAX_INPUTS) { List(MAX_OUTPUTS) { false } },
    matrixGain = List(MAX_INPUTS) { List(MAX_OUTPUTS) { 0f } },
    matrixInvert = List(MAX_INPUTS) { List(MAX_OUTPUTS) { false } },
    outputEnabled = List(MAX_OUTPUTS) { false },
    outputMuted = List(MAX_OUTPUTS) { false },
    outputGainDb = List(MAX_OUTPUTS) { 0f },
    outputDelayMs = List(MAX_OUTPUTS) { 0f },
    limiterEnabled = List(MAX_OUTPUTS) { false },
    limiterLinkGroup = List(MAX_OUTPUTS) { 0 },
    limiterThresholdDb = List(MAX_OUTPUTS) { 0f },
    limiterReleaseMs = List(MAX_OUTPUTS) { 100f },
    numPinOutputs = 3,
    outputPins = listOf(6, 7, 10, 0, 0),
    outputConfigMode = 1,
    filters = List(MAX_CHANNELS) { List(BANDS_PER_CHANNEL) { defaultFilterParams() } },
    xover = List(MAX_CHANNELS) { List(MAX_XOVER_BANDS) { defaultFilterParams() } },
    channelNames = List(MAX_CHANNELS) { "" },
    levellerEnabled = false,
    levellerSpeed = 1,
    levellerLookahead = false,
    levellerAmount = 50f,
    levellerMaxGainDb = 12f,
    levellerGateDb = -60f,
    levellerDetectorMask = 0x03,
    levellerApplyMask = 0x03,
    inputSource = 0,
    lgSoundSyncEnabled = false,
    spdifInputsEnabled = 0x01,
    adatInputEnabled = false,
    spdifRxPins = listOf(5, 20, 21, 22),
    i2sRxPins = listOf(1, 2, 3, 4),
    i2sInputChannels = 2,
    i2sInputRate = 1,
    i2sClockMode = 0,
    adatInputPin = 0xFF,
    adatInputClockMode = 0,
    psybassEnabled = false,
    psybassOutputMask = 0,
    psybassCutoffHz = 80f,
    psybassHarmonicsDb = 0f,
    psybassDriveDb = 6f,
    psybassCharacterPct = 50f,
    psybassOriginalDb = 0f,
    upmix = FakeTables.UPMIX_DEFAULTS,
    subharm = FakeTables.SUBHARM_DEFAULTS,
    subharmSolo = false,
    tube = FakeTables.TUBE_DEFAULTS,
    outputTypes = List(4) { 0 },
    i2sBckPin = 14,
    i2sBckPinSlave = 26,
    i2sClockPinMode = 0,
    mckEnabled = false,
    mckPin = 13,
    mckMultiplier = 0,
    adatOutEnabled = false,
    adatOutPin = 12,
    dacMuteSupported = false,
    dacMuteEnabled = false,
    dacMuteActiveLow = true,
    dacMutePin = 11,
    dacMuteHoldMs = 5,
    dacMuteReleaseMs = 0,
    ctrlIfaceSupported = false,
    uart = UartConfig(enabled = false, txPin = 16, rxPin = 17, notify = false, baud = 115_200),
    i2c = I2cConfig(enabled = false, sdaPin = 18, sclPin = 19, address = 0x42),
    ctrlStatus = CtrlIfaceStatus(uartLastStatus = 0, uartLive = false, i2cLastStatus = 0, i2cLive = false, protocolVersion = 0),
    core1Mode = 0,
    presetOccupied = 0,
    presetNames = List(MAX_PRESETS) { "" },
    activePresetSlot = 0,
    presetStartupMode = 0,
    presetDefaultSlot = 0,
    bulkValid = false,
    bulkRaw = ByteArray(BULK_PARAMS_SIZE)
)

/** Tables of protocol.rs @ 4683a2c the core uses to clamp and default tool parameters. */
object FakeTables {
    /** (min, max) per `UPMIX_PARAM_*`. */
    val UPMIX_LIMITS: List<Pair<Float, Float>> =
        listOf(
            0f to 1f, 0f to 2f, 0f to 2f, 0f to 100f, 0f to 100f, 0f to 95f, 1f to 500f,
            5f to 2000f, 20f to 1000f, 0f to 20f, 20f to 2000f, 1000f to 20000f, 0f to 100f, -12f to 12f
        )
    val UPMIX_DEFAULTS: List<Float> = listOf(0f, 1f, 2f, 100f, 25f, 30f, 10f, 100f, 200f, 12f, 300f, 7000f, 90f, 0f)

    val SUBHARM_LIMITS: List<Pair<Float, Float>> =
        listOf(
            0f to 1f, 0f to 65535f, -30f to 12f, -30f to 12f, -30f to 12f, 0f to 6f, 0f to 2f, 0f to 100f, 50f to 400f, -40f to 0f,
            0f to 1f
        )
    val SUBHARM_DEFAULTS: List<Float> = listOf(0f, 65535f, 0f, 0f, -30f, 0f, 0f, 100f, 150f, 0f, 1f)

    val TUBE_LIMITS: List<Pair<Float, Float>> =
        listOf(
            0f to 1f, 0f to 65535f, 0f to 16f, -30f to 24f, -100f to 100f, -12f to 12f,
            0f to 100f, 0f to 100f, 0f to 3f, 0f to 1f, 1f to 20f, 30f to 150f, 0f to 100f, -12f to 12f
        )
    val TUBE_DEFAULTS: List<Float> = listOf(0f, 65535f, 1f, -12f, 10f, 3f, 40f, 15f, 1f, 1f, 2f, 95f, 100f, 0f)

    /** Bias, asymmetry, knee hardness, sag of tube types 1..16. */
    val TUBE_ROWS: List<List<Float>> =
        listOf(
            listOf(10f, 3f, 40f, 15f), listOf(8f, 3f, 35f, 12f), listOf(5f, 2f, 55f, 10f), listOf(7f, 4f, 25f, 15f),
            listOf(5f, 5f, 20f, 8f), listOf(7f, 6f, 15f, 10f), listOf(10f, 3f, 30f, 15f), listOf(3f, 2f, 60f, 5f),
            listOf(2f, 0f, 75f, 12f), listOf(3f, 1f, 65f, 15f), listOf(0f, 0f, 50f, 25f), listOf(0f, 0f, 60f, 30f),
            listOf(0f, 0f, 55f, 18f), listOf(0f, 0f, 35f, 30f), listOf(0f, 0f, 45f, 10f), listOf(12f, 6f, 10f, 12f)
        )

    /** `set_crossfeed_preset`'s locally applied (freq, feed). */
    val CROSSFEED_PRESETS: List<Pair<Float, Float>> = listOf(700f to 4.5f, 700f to 6f, 650f to 9.5f)
}

private fun band(type: Int, freq: Float, q: Float = DEFAULT_Q, gain: Float = 0f) =
    FilterParams(filterType = type, bypass = false, freq = freq, q = q, gain = gain, qp = DEFAULT_Q)

/**
 * The built-in representative device for demo mode and tests when no fixture
 * is available: compatible V32 firmware 1.1.6-beta5 on an RP2350 (8 inputs,
 * 9 outputs: four S/PDIF pairs + PDM sub), USB 1/2 routed to a 2-way
 * crossover on outputs 1-4 and the PDM sub, a little input EQ, two presets.
 */
fun demoDspState(): DspState {
    val base = defaultDspState()
    val nIn = 8
    val nOut = 9
    val flat = defaultFilterParams()
    val filters =
        List(MAX_CHANNELS) { w ->
            val bands = MutableList(BANDS_PER_CHANNEL) { flat }
            if (w < 2) {
                bands[0] = band(DspiConstants.FILTER_LOWSHELF, 80f, 0.707f, 3f)
                bands[1] = band(DspiConstants.FILTER_PEAKING, 2500f, 1.4f, -2.5f)
                bands[2] = band(DspiConstants.FILTER_HIGHSHELF, 9000f, 0.707f, 1.5f)
            }
            if (w == nIn + 1) bands[0] = band(DspiConstants.FILTER_PEAKING, 4200f, 2f, -1.5f)
            bands.toList()
        }
    val lr4Lp = DspiConstants.FILTER_XOVER_FIRST + 2 // LR4 low-pass
    val lr4Hp = DspiConstants.FILTER_XOVER_FIRST + 3 // LR4 high-pass
    val xover =
        List(MAX_CHANNELS) { w ->
            val out = w - nIn
            val bands = MutableList(MAX_XOVER_BANDS) { flat }
            when (out) {
                0, 1 -> bands[0] = band(lr4Lp, 2200f) // woofer L/R
                2, 3 -> bands[0] = band(lr4Hp, 2200f) // tweeter L/R
                8 -> bands[0] = band(lr4Lp, 80f) // PDM sub
            }
            if (out in 0..3) bands[1] = band(lr4Hp, 40f)
            bands.toList()
        }
    val routing = List(MAX_INPUTS) { i -> List(MAX_OUTPUTS) { o -> (i < 2 && o < 4 && o % 2 == i) } }
    val names =
        List(MAX_CHANNELS) { w ->
            when {
                w < nIn -> "USB ${w + 1}"
                w - nIn == nOut - 1 -> "PDM"
                w < nIn + nOut -> listOf(
                    "Woofer L",
                    "Woofer R",
                    "Tweeter L",
                    "Tweeter R",
                    "SPDIF 3 L",
                    "SPDIF 3 R",
                    "SPDIF 4 L",
                    "SPDIF 4 R"
                )[
                    w -
                        nIn
                ]
                else -> ""
            }
        }
    return base.copy(
        platformId = 1,
        numChannels = nIn + nOut,
        numInputChannels = nIn,
        numOutputChannels = nOut,
        formatVersion = DspiConstants.WIRE_FORMAT_VERSION,
        fwMajor = 1,
        fwMinor = 1,
        fwPatch = 6,
        fwBeta = 5,
        compat = DspiConstants.COMPAT_OK,
        masterVolumeDb = -12f,
        filters = filters,
        xover = xover,
        matrixRouting = routing,
        outputEnabled = List(MAX_OUTPUTS) { it < 4 },
        outputGainDb = List(MAX_OUTPUTS) { if (it == 2 || it == 3) -3f else 0f },
        outputDelayMs = List(MAX_OUTPUTS) { if (it < 2) 0.25f else 0f },
        channelDelays = List(MAX_CHANNELS) { if (it == nIn || it == nIn + 1) 0.25f else 0f },
        numPinOutputs = 5,
        outputPins = listOf(6, 7, 8, 9, 10),
        channelNames = names,
        presetNames = listOf("Living room", "Late night") + List(MAX_PRESETS - 2) { "" },
        presetOccupied = 0x0003,
        activePresetSlot = 0,
        dacMuteSupported = true,
        ctrlIfaceSupported = true,
        ctrlStatus = base.ctrlStatus.copy(protocolVersion = 1),
        bulkValid = true
    )
}
