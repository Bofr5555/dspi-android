package se.bofr.dspi.fake

import java.util.Base64
import se.bofr.dspi.core.CtrlIfaceStatus
import se.bofr.dspi.core.DspState
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.core.FilterParams
import se.bofr.dspi.core.I2cConfig
import se.bofr.dspi.core.UartConfig

/**
 * `DspState` <-> JSON, the format of FFI's `fixtures/dspstate_v32.json`
 * (the `DspState` record serialised with Kotlin camelCase field names).
 *
 * Reading is lenient so the loader survives the fixture's exact encoding:
 * a missing field keeps the [defaultDspState] value; `bulkRaw` may be an
 * array of byte values, a base64 string, or the whole image as hex; booleans may be
 * 0/1. The field lists below were generated from `CoreTypes.kt`'s `DspState`
 * (keep them in step when the contract changes).
 */
object DspStateJson {
    fun parse(text: String, base: DspState = defaultDspState()): DspState {
        val m = MiniJson.parseObject(text) ?: throw IllegalArgumentException("not a JSON object")
        // Accept both a bare DspState and {"state": {...}} wrappers.
        return fromMap(m.obj("state") ?: m, base)
    }

    fun write(s: DspState): String = MiniJson.write(toMap(s))

    fun fromMap(m: Map<String, Any?>, base: DspState = defaultDspState()): DspState = DspState(
        platformId = m.int("platformId") ?: base.platformId,
        numChannels = m.int("numChannels") ?: base.numChannels,
        numInputChannels = m.int("numInputChannels") ?: base.numInputChannels,
        numOutputChannels = m.int("numOutputChannels") ?: base.numOutputChannels,
        formatVersion = m.int("formatVersion") ?: base.formatVersion,
        fwMajor = m.int("fwMajor") ?: base.fwMajor,
        fwMinor = m.int("fwMinor") ?: base.fwMinor,
        fwPatch = m.int("fwPatch") ?: base.fwPatch,
        fwBeta = m.int("fwBeta") ?: base.fwBeta,
        compat = m.int("compat") ?: base.compat,
        wireRead = m.int("wireRead") ?: base.wireRead,
        wireFeatures = m.long("wireFeatures") ?: base.wireFeatures,
        preampDb = m.float("preampDb") ?: base.preampDb,
        bypass = m.bool("bypass") ?: base.bypass,
        inputPreampDb = m.floats("inputPreampDb") ?: base.inputPreampDb,
        masterVolumeDb = m.float("masterVolumeDb") ?: base.masterVolumeDb,
        masterVolumeMode = m.int("masterVolumeMode") ?: base.masterVolumeMode,
        userVolumeDb = m.float("userVolumeDb") ?: base.userVolumeDb,
        userMute = m.bool("userMute") ?: base.userMute,
        loudnessEnabled = m.bool("loudnessEnabled") ?: base.loudnessEnabled,
        loudnessRefSpl = m.float("loudnessRefSpl") ?: base.loudnessRefSpl,
        loudnessIntensity = m.float("loudnessIntensity") ?: base.loudnessIntensity,
        loudnessOutputMask = m.int("loudnessOutputMask") ?: base.loudnessOutputMask,
        crossfeedEnabled = m.bool("crossfeedEnabled") ?: base.crossfeedEnabled,
        crossfeedPreset = m.int("crossfeedPreset") ?: base.crossfeedPreset,
        crossfeedFreq = m.float("crossfeedFreq") ?: base.crossfeedFreq,
        crossfeedFeed = m.float("crossfeedFeed") ?: base.crossfeedFeed,
        crossfeedItd = m.bool("crossfeedItd") ?: base.crossfeedItd,
        crossfeedOutputPairMask = m.int("crossfeedOutputPairMask") ?: base.crossfeedOutputPairMask,
        crossfeedPairMap = m.ints("crossfeedPairMap") ?: base.crossfeedPairMap,
        channelDelays = m.floats("channelDelays") ?: base.channelDelays,
        matrixRouting = m.bools2("matrixRouting") ?: base.matrixRouting,
        matrixGain = m.floats2("matrixGain") ?: base.matrixGain,
        matrixInvert = m.bools2("matrixInvert") ?: base.matrixInvert,
        outputEnabled = m.bools("outputEnabled") ?: base.outputEnabled,
        outputMuted = m.bools("outputMuted") ?: base.outputMuted,
        outputGainDb = m.floats("outputGainDb") ?: base.outputGainDb,
        outputDelayMs = m.floats("outputDelayMs") ?: base.outputDelayMs,
        limiterEnabled = m.bools("limiterEnabled") ?: base.limiterEnabled,
        limiterLinkGroup = m.ints("limiterLinkGroup") ?: base.limiterLinkGroup,
        limiterThresholdDb = m.floats("limiterThresholdDb") ?: base.limiterThresholdDb,
        limiterReleaseMs = m.floats("limiterReleaseMs") ?: base.limiterReleaseMs,
        numPinOutputs = m.int("numPinOutputs") ?: base.numPinOutputs,
        outputPins = m.ints("outputPins") ?: base.outputPins,
        outputConfigMode = m.int("outputConfigMode") ?: base.outputConfigMode,
        filters = m.filters2("filters") ?: base.filters,
        xover = m.filters2("xover") ?: base.xover,
        channelNames = m.strs("channelNames") ?: base.channelNames,
        levellerEnabled = m.bool("levellerEnabled") ?: base.levellerEnabled,
        levellerSpeed = m.int("levellerSpeed") ?: base.levellerSpeed,
        levellerLookahead = m.bool("levellerLookahead") ?: base.levellerLookahead,
        levellerAmount = m.float("levellerAmount") ?: base.levellerAmount,
        levellerMaxGainDb = m.float("levellerMaxGainDb") ?: base.levellerMaxGainDb,
        levellerGateDb = m.float("levellerGateDb") ?: base.levellerGateDb,
        levellerDetectorMask = m.int("levellerDetectorMask") ?: base.levellerDetectorMask,
        levellerApplyMask = m.int("levellerApplyMask") ?: base.levellerApplyMask,
        inputSource = m.int("inputSource") ?: base.inputSource,
        lgSoundSyncEnabled = m.bool("lgSoundSyncEnabled") ?: base.lgSoundSyncEnabled,
        spdifInputsEnabled = m.int("spdifInputsEnabled") ?: base.spdifInputsEnabled,
        adatInputEnabled = m.bool("adatInputEnabled") ?: base.adatInputEnabled,
        spdifRxPins = m.ints("spdifRxPins") ?: base.spdifRxPins,
        i2sRxPins = m.ints("i2sRxPins") ?: base.i2sRxPins,
        i2sInputChannels = m.int("i2sInputChannels") ?: base.i2sInputChannels,
        i2sInputRate = m.int("i2sInputRate") ?: base.i2sInputRate,
        i2sClockMode = m.int("i2sClockMode") ?: base.i2sClockMode,
        adatInputPin = m.int("adatInputPin") ?: base.adatInputPin,
        adatInputClockMode = m.int("adatInputClockMode") ?: base.adatInputClockMode,
        psybassEnabled = m.bool("psybassEnabled") ?: base.psybassEnabled,
        psybassOutputMask = m.int("psybassOutputMask") ?: base.psybassOutputMask,
        psybassCutoffHz = m.float("psybassCutoffHz") ?: base.psybassCutoffHz,
        psybassHarmonicsDb = m.float("psybassHarmonicsDb") ?: base.psybassHarmonicsDb,
        psybassDriveDb = m.float("psybassDriveDb") ?: base.psybassDriveDb,
        psybassCharacterPct = m.float("psybassCharacterPct") ?: base.psybassCharacterPct,
        psybassOriginalDb = m.float("psybassOriginalDb") ?: base.psybassOriginalDb,
        upmix = m.floats("upmix") ?: base.upmix,
        subharm = m.floats("subharm") ?: base.subharm,
        subharmSolo = m.bool("subharmSolo") ?: base.subharmSolo,
        tube = m.floats("tube") ?: base.tube,
        outputTypes = m.ints("outputTypes") ?: base.outputTypes,
        i2sBckPin = m.int("i2sBckPin") ?: base.i2sBckPin,
        i2sBckPinSlave = m.int("i2sBckPinSlave") ?: base.i2sBckPinSlave,
        i2sClockPinMode = m.int("i2sClockPinMode") ?: base.i2sClockPinMode,
        mckEnabled = m.bool("mckEnabled") ?: base.mckEnabled,
        mckPin = m.int("mckPin") ?: base.mckPin,
        mckMultiplier = m.int("mckMultiplier") ?: base.mckMultiplier,
        adatOutEnabled = m.bool("adatOutEnabled") ?: base.adatOutEnabled,
        adatOutPin = m.int("adatOutPin") ?: base.adatOutPin,
        dacMuteSupported = m.bool("dacMuteSupported") ?: base.dacMuteSupported,
        dacMuteEnabled = m.bool("dacMuteEnabled") ?: base.dacMuteEnabled,
        dacMuteActiveLow = m.bool("dacMuteActiveLow") ?: base.dacMuteActiveLow,
        dacMutePin = m.int("dacMutePin") ?: base.dacMutePin,
        dacMuteHoldMs = m.int("dacMuteHoldMs") ?: base.dacMuteHoldMs,
        dacMuteReleaseMs = m.int("dacMuteReleaseMs") ?: base.dacMuteReleaseMs,
        ctrlIfaceSupported = m.bool("ctrlIfaceSupported") ?: base.ctrlIfaceSupported,
        uart = m.uart("uart") ?: base.uart,
        i2c = m.i2c("i2c") ?: base.i2c,
        ctrlStatus = m.ctrl("ctrlStatus") ?: base.ctrlStatus,
        core1Mode = m.int("core1Mode") ?: base.core1Mode,
        presetOccupied = m.int("presetOccupied") ?: base.presetOccupied,
        presetNames = m.strs("presetNames") ?: base.presetNames,
        activePresetSlot = m.int("activePresetSlot") ?: base.activePresetSlot,
        presetStartupMode = m.int("presetStartupMode") ?: base.presetStartupMode,
        presetDefaultSlot = m.int("presetDefaultSlot") ?: base.presetDefaultSlot,
        bulkValid = m.bool("bulkValid") ?: base.bulkValid,
        bulkRaw = m.bytes("bulkRaw") ?: base.bulkRaw
    )

    fun toMap(s: DspState): Map<String, Any?> = linkedMapOf(
        "platformId" to s.platformId,
        "numChannels" to s.numChannels,
        "numInputChannels" to s.numInputChannels,
        "numOutputChannels" to s.numOutputChannels,
        "formatVersion" to s.formatVersion,
        "fwMajor" to s.fwMajor,
        "fwMinor" to s.fwMinor,
        "fwPatch" to s.fwPatch,
        "fwBeta" to s.fwBeta,
        "compat" to s.compat,
        "wireRead" to s.wireRead,
        "wireFeatures" to s.wireFeatures,
        "preampDb" to s.preampDb,
        "bypass" to s.bypass,
        "inputPreampDb" to s.inputPreampDb,
        "masterVolumeDb" to s.masterVolumeDb,
        "masterVolumeMode" to s.masterVolumeMode,
        "userVolumeDb" to s.userVolumeDb,
        "userMute" to s.userMute,
        "loudnessEnabled" to s.loudnessEnabled,
        "loudnessRefSpl" to s.loudnessRefSpl,
        "loudnessIntensity" to s.loudnessIntensity,
        "loudnessOutputMask" to s.loudnessOutputMask,
        "crossfeedEnabled" to s.crossfeedEnabled,
        "crossfeedPreset" to s.crossfeedPreset,
        "crossfeedFreq" to s.crossfeedFreq,
        "crossfeedFeed" to s.crossfeedFeed,
        "crossfeedItd" to s.crossfeedItd,
        "crossfeedOutputPairMask" to s.crossfeedOutputPairMask,
        "crossfeedPairMap" to s.crossfeedPairMap,
        "channelDelays" to s.channelDelays,
        "matrixRouting" to s.matrixRouting,
        "matrixGain" to s.matrixGain,
        "matrixInvert" to s.matrixInvert,
        "outputEnabled" to s.outputEnabled,
        "outputMuted" to s.outputMuted,
        "outputGainDb" to s.outputGainDb,
        "outputDelayMs" to s.outputDelayMs,
        "limiterEnabled" to s.limiterEnabled,
        "limiterLinkGroup" to s.limiterLinkGroup,
        "limiterThresholdDb" to s.limiterThresholdDb,
        "limiterReleaseMs" to s.limiterReleaseMs,
        "numPinOutputs" to s.numPinOutputs,
        "outputPins" to s.outputPins,
        "outputConfigMode" to s.outputConfigMode,
        "filters" to s.filters.map { ch -> ch.map(::filterMap) },
        "xover" to s.xover.map { ch -> ch.map(::filterMap) },
        "channelNames" to s.channelNames,
        "levellerEnabled" to s.levellerEnabled,
        "levellerSpeed" to s.levellerSpeed,
        "levellerLookahead" to s.levellerLookahead,
        "levellerAmount" to s.levellerAmount,
        "levellerMaxGainDb" to s.levellerMaxGainDb,
        "levellerGateDb" to s.levellerGateDb,
        "levellerDetectorMask" to s.levellerDetectorMask,
        "levellerApplyMask" to s.levellerApplyMask,
        "inputSource" to s.inputSource,
        "lgSoundSyncEnabled" to s.lgSoundSyncEnabled,
        "spdifInputsEnabled" to s.spdifInputsEnabled,
        "adatInputEnabled" to s.adatInputEnabled,
        "spdifRxPins" to s.spdifRxPins,
        "i2sRxPins" to s.i2sRxPins,
        "i2sInputChannels" to s.i2sInputChannels,
        "i2sInputRate" to s.i2sInputRate,
        "i2sClockMode" to s.i2sClockMode,
        "adatInputPin" to s.adatInputPin,
        "adatInputClockMode" to s.adatInputClockMode,
        "psybassEnabled" to s.psybassEnabled,
        "psybassOutputMask" to s.psybassOutputMask,
        "psybassCutoffHz" to s.psybassCutoffHz,
        "psybassHarmonicsDb" to s.psybassHarmonicsDb,
        "psybassDriveDb" to s.psybassDriveDb,
        "psybassCharacterPct" to s.psybassCharacterPct,
        "psybassOriginalDb" to s.psybassOriginalDb,
        "upmix" to s.upmix,
        "subharm" to s.subharm,
        "subharmSolo" to s.subharmSolo,
        "tube" to s.tube,
        "outputTypes" to s.outputTypes,
        "i2sBckPin" to s.i2sBckPin,
        "i2sBckPinSlave" to s.i2sBckPinSlave,
        "i2sClockPinMode" to s.i2sClockPinMode,
        "mckEnabled" to s.mckEnabled,
        "mckPin" to s.mckPin,
        "mckMultiplier" to s.mckMultiplier,
        "adatOutEnabled" to s.adatOutEnabled,
        "adatOutPin" to s.adatOutPin,
        "dacMuteSupported" to s.dacMuteSupported,
        "dacMuteEnabled" to s.dacMuteEnabled,
        "dacMuteActiveLow" to s.dacMuteActiveLow,
        "dacMutePin" to s.dacMutePin,
        "dacMuteHoldMs" to s.dacMuteHoldMs,
        "dacMuteReleaseMs" to s.dacMuteReleaseMs,
        "ctrlIfaceSupported" to s.ctrlIfaceSupported,
        "uart" to uartMap(s.uart),
        "i2c" to i2cMap(s.i2c),
        "ctrlStatus" to ctrlMap(s.ctrlStatus),
        "core1Mode" to s.core1Mode,
        "presetOccupied" to s.presetOccupied,
        "presetNames" to s.presetNames,
        "activePresetSlot" to s.activePresetSlot,
        "presetStartupMode" to s.presetStartupMode,
        "presetDefaultSlot" to s.presetDefaultSlot,
        "bulkValid" to s.bulkValid,
        "bulkRaw" to s.bulkRaw.map { it.toInt() and 0xFF }
    )

    private fun filterMap(f: FilterParams) =
        linkedMapOf("filterType" to f.filterType, "bypass" to f.bypass, "freq" to f.freq, "q" to f.q, "gain" to f.gain, "qp" to f.qp)

    private fun uartMap(u: UartConfig) = linkedMapOf(
        "enabled" to u.enabled,
        "txPin" to u.txPin,
        "rxPin" to u.rxPin,
        "notify" to u.notify,
        "baud" to u.baud
    )

    private fun i2cMap(c: I2cConfig) = linkedMapOf(
        "enabled" to c.enabled,
        "sdaPin" to c.sdaPin,
        "sclPin" to c.sclPin,
        "address" to c.address
    )

    private fun ctrlMap(c: CtrlIfaceStatus) = linkedMapOf(
        "uartLastStatus" to c.uartLastStatus,
        "uartLive" to c.uartLive,
        "i2cLastStatus" to c.i2cLastStatus,
        "i2cLive" to c.i2cLive,
        "protocolVersion" to c.protocolVersion
    )

    private fun Map<String, Any?>.floats(k: String): List<Float>? = list(k)?.map { (it as? Number)?.toFloat() ?: 0f }

    private fun Map<String, Any?>.ints(k: String): List<Int>? = list(k)?.map { (it as? Number)?.toInt() ?: 0 }

    private fun Map<String, Any?>.bools(k: String): List<Boolean>? = list(k)?.map { boolOf(it) }

    private fun Map<String, Any?>.strs(k: String): List<String>? = list(k)?.map { it as? String ?: "" }

    private fun Map<String, Any?>.bools2(k: String): List<List<Boolean>>? = list(k)?.map { row ->
        (row as? List<*>)?.map { boolOf(it) }
            ?: emptyList()
    }

    private fun Map<String, Any?>.floats2(k: String): List<List<Float>>? =
        list(k)?.map { row -> (row as? List<*>)?.map { (it as? Number)?.toFloat() ?: 0f } ?: emptyList() }

    private fun Map<String, Any?>.filters2(k: String): List<List<FilterParams>>? = list(k)?.map { row ->
        (row as? List<*>)?.map { f ->
            @Suppress("UNCHECKED_CAST")
            val o = f as? Map<String, Any?> ?: emptyMap()
            val d = defaultFilterParams()
            FilterParams(
                filterType = o.int("filterType") ?: d.filterType,
                bypass = o.bool("bypass") ?: d.bypass,
                freq = o.float("freq") ?: d.freq,
                q = o.float("q") ?: d.q,
                gain = o.float("gain") ?: d.gain,
                qp = o.float("qp") ?: d.qp
            )
        } ?: emptyList()
    }

    private fun Map<String, Any?>.uart(k: String): UartConfig? = obj(k)?.let {
        UartConfig(
            it.bool("enabled") ?: false,
            it.int("txPin") ?: 0,
            it.int("rxPin") ?: 0,
            it.bool("notify") ?: false,
            it.long("baud") ?: 0
        )
    }

    private fun Map<String, Any?>.i2c(k: String): I2cConfig? = obj(k)?.let {
        I2cConfig(it.bool("enabled") ?: false, it.int("sdaPin") ?: 0, it.int("sclPin") ?: 0, it.int("address") ?: 0)
    }

    private fun Map<String, Any?>.ctrl(k: String): CtrlIfaceStatus? = obj(k)?.let {
        CtrlIfaceStatus(
            it.int("uartLastStatus") ?: 0,
            it.bool("uartLive") ?: false,
            it.int("i2cLastStatus") ?: 0,
            it.bool("i2cLive") ?: false,
            it.int("protocolVersion") ?: 0
        )
    }

    private fun Map<String, Any?>.bytes(k: String): ByteArray? = when (val v = this[k]) {
        is List<*> -> ByteArray(v.size) { ((v[it] as? Number)?.toInt() ?: 0).toByte() }
        // Base64, unless it is exactly the bulk image in hex (2 × BULK_PARAMS_SIZE digits)
        is String ->
            if (v.length == 2 * DspiConstants.BULK_PARAMS_SIZE && v.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                ByteArray(v.length / 2) { v.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
            } else {
                runCatching { Base64.getDecoder().decode(v) }.getOrNull()
            }
        else -> null
    }

    private fun boolOf(v: Any?): Boolean = (v as? Boolean) ?: (((v as? Number)?.toInt() ?: 0) != 0)
}
