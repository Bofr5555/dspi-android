package se.bofr.dspi.fake

import se.bofr.dspi.core.CtrlIfaceStatus
import se.bofr.dspi.core.DspState
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
import se.bofr.dspi.core.DspiConstants.SUBHARM_PARAM_COUNT
import se.bofr.dspi.core.DspiConstants.TUBE_PARAM_COUNT
import se.bofr.dspi.core.DspiConstants.UPMIX_PARAM_COUNT
import se.bofr.dspi.core.FilterParams
import se.bofr.dspi.core.I2cConfig
import se.bofr.dspi.core.UartConfig

/** `FilterParams::default()` (types.rs). */
fun defaultFilterParams() = FilterParams(filterType = FILTER_FLAT, bypass = false, freq = 1000f, q = DEFAULT_Q, gain = 0f, qp = DEFAULT_Q)

/**
 * `DspState::default()` (state.rs) — what the core holds before the first
 * fetch. TODO(BRIDGE): upmix/subharm/tube use zeros here, not the core's
 * `*_DEFAULTS` arrays; seed the fake from a real V32 dump instead.
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
    upmix = List(UPMIX_PARAM_COUNT) { 0f },
    subharm = List(SUBHARM_PARAM_COUNT) { 0f },
    subharmSolo = false,
    tube = List(TUBE_PARAM_COUNT) { 0f },
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
