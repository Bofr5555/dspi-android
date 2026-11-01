package se.bofr.dspi.bridge

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.bofr.dspi.core.AdatOutStatus
import se.bofr.dspi.core.BufferStats
import se.bofr.dspi.core.DspState
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.core.DspiCoreApi
import se.bofr.dspi.core.DspiException
import se.bofr.dspi.core.FilterParams
import se.bofr.dspi.core.FlashStatus
import se.bofr.dspi.core.InputLockStatus
import se.bofr.dspi.core.LgStatus
import se.bofr.dspi.core.PinConfigStatus
import se.bofr.dspi.core.PresetStatus
import se.bofr.dspi.core.RtaCapsInfo
import se.bofr.dspi.core.RtaSnapshot
import se.bofr.dspi.core.SiggenCaps
import se.bofr.dspi.core.SiggenConfig
import se.bofr.dspi.core.SiggenStatus
import se.bofr.dspi.core.UpmixStatus
import se.bofr.dspi.core.statusOf

/**
 * WAVE 0 skeleton of [DspiBridge] so UI packages can build and run against
 * [se.bofr.dspi.fake.FakeDspiCore] before the real port exists.
 *
 * What works: state/status flows seeded from the core, every read accessor
 * (pure projections of [DspState] through [ChannelMap]), and setters that
 * forward to the core on one serial dispatcher and re-emit the state.
 * What does not: notifications, the 60 ms status poll, curves (flat),
 * links, clipboard, dirty tracking, sendOnly semantics, controllers.
 *
 * Package BRIDGE replaces this with `DspiBridgeImpl` (port of
 * DSPiBridge.cpp) and deletes it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
open class StubDspiBridge(
    protected val core: DspiCoreApi,
    protected val scope: CoroutineScope,
    protected val ffi: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
) : DspiBridge {
    private val _state = MutableStateFlow(core.getState())
    private val _stateRevision = MutableStateFlow(0L)
    private val _status = MutableStateFlow(BridgeStatus(connected = runCatching { core.isConnected() }.getOrDefault(false)))
    private val _devices = MutableStateFlow(DeviceListState())
    private val _presetDirty = MutableStateFlow(false)
    private val _hardwareUnsaved = MutableStateFlow(false)
    private val _magnitudesRevision = MutableStateFlow(0L)
    private val _events = MutableSharedFlow<BridgeEvent>(extraBufferCapacity = 64)

    override val state: StateFlow<DspState> = _state.asStateFlow()
    override val stateRevision: StateFlow<Long> = _stateRevision.asStateFlow()
    override val status: StateFlow<BridgeStatus> = _status.asStateFlow()
    override val devices: StateFlow<DeviceListState> = _devices.asStateFlow()
    override val presetDirty: StateFlow<Boolean> = _presetDirty.asStateFlow()
    override val hardwareUnsaved: StateFlow<Boolean> = _hardwareUnsaved.asStateFlow()
    override val magnitudesRevision: StateFlow<Long> = _magnitudesRevision.asStateFlow()
    override val events: SharedFlow<BridgeEvent> = _events

    protected val s: DspState get() = _state.value
    protected val map: ChannelMap get() = ChannelMap(s.numInputChannels, s.numOutputChannels)

    /** Re-read the core's state and emit stateChanged. */
    protected fun emitState() {
        _state.value = core.getState()
        _stateRevision.value += 1
    }

    /** Fire-and-forget setter: run on the FFI dispatcher, then emit. */
    protected fun fire(name: String, block: DspiCoreApi.() -> Unit) {
        scope.launch {
            val err =
                withContext(ffi) {
                    try {
                        core.block()
                        emitState()
                        null
                    } catch (e: DspiException) {
                        e
                    } catch (e: NotImplementedError) {
                        e
                    }
                }
            if (err != null) _events.tryEmit(BridgeEvent.CommandFailed(name, err))
        }
    }

    protected suspend fun <T> onFfi(block: DspiCoreApi.() -> T): T = withContext(ffi) { core.block() }

    protected suspend fun presetStatus(block: DspiCoreApi.() -> Unit): PresetStatus =
        PresetStatus.of(onFfi { statusOf(-1) { block() }.also { emitState() } })

    protected suspend fun pinStatus(block: DspiCoreApi.() -> Unit): PinConfigStatus =
        PinConfigStatus.of(onFfi { statusOf(255) { block() }.also { emitState() } })

    protected suspend fun flashStatus(block: DspiCoreApi.() -> Unit): FlashStatus =
        FlashStatus.of(onFfi { statusOf(DspiConstants.FLASH_ERR_WRITE) { block() }.also { emitState() } })

    private fun wireOr(ch: Int, f: (Int) -> Float): Float = map.wire(ch).let { if (it >= 0) f(it) else 0f }

    // ═══ Q_PROPERTY getters ═══════════════════════════════════════════

    override val connected get() = _status.value.connected
    override val connectionError get() = _status.value.connectionError
    override val selectedSerial get() = _devices.value.selectedSerial
    override val availableSerials get() = _devices.value.availableSerials
    override val inaccessibleDevices get() = _devices.value.inaccessibleDevices
    override val platformName get() = if (s.platformId == 1) "RP2350" else "RP2040"
    override val preampMaxDB get() = if (s.platformId == 1) 24f else 18f
    override val numChannels get() = s.numChannels
    override val numInputChannels get() = s.numInputChannels
    override val numOutputChannels get() = s.numOutputChannels
    override val firmwareVersion: String
        get() {
            if (!connected || (s.fwMajor == 0 && s.fwMinor == 0 && s.fwPatch == 0)) return ""
            val v = "${s.fwMajor}.${s.fwMinor}.${s.fwPatch}"
            return when (s.fwBeta) {
                0 -> v
                DspiConstants.FW_BETA_EARLY -> "$v early beta"
                else -> "$v-beta${s.fwBeta}"
            }
        }
    override val compat get() = if (connected) s.compat else DspiConstants.COMPAT_UNKNOWN
    override val compatMessage: String
        get() =
            when (compat) {
                DspiConstants.COMPAT_FIRMWARE_TOO_OLD ->
                    "This DSPi runs firmware ${firmwareVersion.ifEmpty { "older than 1.1.6" }}, which is too old for this Console. " +
                        "Update it to firmware 1.1.6."
                DspiConstants.COMPAT_FIRMWARE_TOO_NEW ->
                    "This DSPi runs firmware $firmwareVersion, which is newer than this Console understands. Update the Console."
                else -> ""
            }
    override val maxDelayMs get() = core.maxDelayMs()
    override val preampDB get() = s.inputPreampDb[0]
    override val bypass get() = s.bypass
    override val masterVolumeDB get() = s.masterVolumeDb
    override val masterVolumeMode get() = s.masterVolumeMode
    override val userVolumeDB get() = s.userVolumeDb
    override val userMute get() = s.userMute
    override val loudnessEnabled get() = s.loudnessEnabled
    override val loudnessRefSPL get() = s.loudnessRefSpl
    override val loudnessIntensity get() = s.loudnessIntensity
    override val loudnessOutputMask get() = s.loudnessOutputMask
    override val crossfeedEnabled get() = s.crossfeedEnabled
    override val crossfeedPreset get() = s.crossfeedPreset
    override val crossfeedFreq get() = s.crossfeedFreq
    override val crossfeedFeed get() = s.crossfeedFeed
    override val crossfeedITD get() = s.crossfeedItd
    override val crossfeedOutputPairMask get() = s.crossfeedOutputPairMask
    override val cpu0 get() = _status.value.cpu0
    override val cpu1 get() = _status.value.cpu1
    override val clipFlags get() = _status.value.clipFlags
    override val activeInputChannels get() = _status.value.activeInputChannels
    override val activePresetSlot get() = s.activePresetSlot
    override val presetOccupied get() = s.presetOccupied
    override val presetStartupMode get() = s.presetStartupMode
    override val presetDefaultSlot get() = s.presetDefaultSlot
    override val outputConfigMode get() = s.outputConfigMode
    override val inputSource get() = s.inputSource
    override val inputSources: List<InputSourceOption>
        get() {
            val extra = (s.spdifInputsEnabled and 1.inv()) != 0
            val list = mutableListOf(InputSourceOption(0, "USB"), InputSourceOption(1, if (extra) "S/PDIF 1" else "S/PDIF"))
            for (k in 1 until 4) if (s.spdifInputsEnabled and (1 shl k) != 0) list += InputSourceOption(3 + k, "S/PDIF ${k + 1}")
            list += InputSourceOption(2, "I2S")
            if (s.platformId == 1 && s.adatInputEnabled && s.adatInputPin != 0xFF) list += InputSourceOption(3, "ADAT")
            return list
        }
    override val core1Mode get() = s.core1Mode
    override val levellerEnabled get() = s.levellerEnabled
    override val levellerAmount get() = s.levellerAmount
    override val levellerSpeed get() = s.levellerSpeed
    override val levellerMaxGain get() = s.levellerMaxGainDb
    override val levellerGate get() = s.levellerGateDb
    override val levellerLookahead get() = s.levellerLookahead
    override val levellerDetectorMask get() = s.levellerDetectorMask
    override val levellerApplyMask get() = s.levellerApplyMask
    override val psybassEnabled get() = s.psybassEnabled
    override val psybassCutoff get() = s.psybassCutoffHz
    override val psybassHarmonics get() = s.psybassHarmonicsDb
    override val psybassDrive get() = s.psybassDriveDb
    override val psybassCharacter get() = s.psybassCharacterPct
    override val psybassOriginal get() = s.psybassOriginalDb
    override val psybassOutputMask get() = s.psybassOutputMask
    override val upmixSupported get() = connected && compat == DspiConstants.COMPAT_OK && s.platformId == 1
    override val upmixParams get() = s.upmix
    override val subharmParams get() = s.subharm
    override val subharmSolo get() = s.subharmSolo
    override val tubeParams get() = s.tube
    override val validPins: List<Int> = (0..28).filter { it < 23 || it > 25 }

    override fun pinOwners(): List<PinOwner> = emptyList() // TODO(BRIDGE): DSPiBridge.cpp pinOwners()

    // ═══ Channel identity / reads ═════════════════════════════════════

    override fun channelExists(ch: Int) = map.channelExists(ch)

    override fun isInputChannel(ch: Int) = map.isInputChannel(ch)

    override fun inputAppId(input: Int) = map.inputAppId(input)

    override fun peakLevel(ch: Int) = _status.value.peaks.getOrElse(ch) { 0f }

    override fun isClipping(ch: Int) = map.wire(ch).let { it >= 0 && (_status.value.clipFlags shr it) and 1L == 1L }

    override fun channelName(ch: Int) = map.wire(ch).let { if (it >= 0) s.channelNames[it] else "" }

    override fun channelColor(ch: Int) = "#888888" // TODO(BRIDGE/HOME): ChannelPalette

    override fun channelDescriptor(ch: Int): String {
        val i = map.inputOf(ch)
        if (i >= 0) return "IN ${i + 1}"
        val o = map.outputOf(ch)
        return if (o >= 0) "OUT ${o + 1}" else ""
    }

    private fun band(ch: Int, band: Int): FilterParams? = map.wire(ch).let { if (it >= 0) s.filters[it].getOrNull(band) else null }

    private fun xband(ch: Int, band: Int): FilterParams? = map.wire(ch).let { if (it >= 0) s.xover[it].getOrNull(band) else null }

    override fun filterType(ch: Int, band: Int) = band(ch, band)?.filterType ?: 0

    override fun filterFreq(ch: Int, band: Int) = band(ch, band)?.freq ?: 0f

    override fun filterGain(ch: Int, band: Int) = band(ch, band)?.gain ?: 0f

    override fun filterQ(ch: Int, band: Int) = band(ch, band)?.q ?: 0f

    override fun filterQp(ch: Int, band: Int) = band(ch, band)?.qp ?: 0f

    override fun filterBypass(ch: Int, band: Int) = band(ch, band)?.bypass ?: false

    override fun crossoverType(ch: Int, band: Int) = xband(ch, band)?.filterType ?: 0

    override fun crossoverFreq(ch: Int, band: Int) = xband(ch, band)?.freq ?: 0f

    override fun crossoverBypass(ch: Int, band: Int) = xband(ch, band)?.bypass ?: false

    override fun inputPreampDB(input: Int) = s.inputPreampDb.getOrElse(input) { 0f }

    override fun channelDelayMS(ch: Int) = wireOr(ch) { s.channelDelays[it] }

    override fun outputEnabled(idx: Int) = s.outputEnabled.getOrElse(idx) { false }

    override fun outputMuted(idx: Int) = s.outputMuted.getOrElse(idx) { false }

    override fun outputGainDB(idx: Int) = s.outputGainDb.getOrElse(idx) { 0f }

    override fun outputDelayMS(idx: Int) = s.outputDelayMs.getOrElse(idx) { 0f }

    override fun isPdmOutput(idx: Int) = idx == s.numOutputChannels - 1

    override fun matrixRouting(input: Int, output: Int) = s.matrixRouting.getOrNull(input)?.getOrNull(output) ?: false

    override fun matrixGain(input: Int, output: Int) = s.matrixGain.getOrNull(input)?.getOrNull(output) ?: 0f

    override fun matrixInvert(input: Int, output: Int) = s.matrixInvert.getOrNull(input)?.getOrNull(output) ?: false

    override fun outputPin(physOut: Int) = s.outputPins.getOrElse(physOut) { 0 }

    override fun numPinOutputs() = s.numPinOutputs

    override fun presetName(slot: Int) = s.presetNames.getOrElse(slot) { "" }

    override fun isPresetOccupied(slot: Int) = (s.presetOccupied shr slot) and 1 == 1

    override val curveFrequencies: List<Double> by lazy { List(201) { core.curveFreq(it.toLong()) } }

    override fun magnitudeCurve(ch: Int): List<Double> = List(201) { 0.0 }

    override fun phaseCurve(ch: Int, unwrap: Boolean): List<Double> = List(201) { 0.0 }

    // ═══ Setters ══════════════════════════════════════════════════════

    override fun setPreamp(db: Float) = fire("setPreamp") { setPreamp(db) }

    override fun sendPreampToDevice(db: Float) = fire("sendPreampToDevice") { setPreamp(db) }

    override fun setInputPreamp(input: Int, db: Float, sendOnly: Boolean) = fire("setInputPreamp") { setInputPreamp(input, db) }

    override fun setBypass(en: Boolean) = fire("setBypass") { setBypass(en) }

    override fun setMasterVolume(db: Float, sendOnly: Boolean) = fire("setMasterVolume") { setMasterVolume(db) }

    override fun setMasterVolumeMode(mode: Int) = fire("setMasterVolumeMode") { setMasterVolumeMode(mode) }

    override suspend fun saveMasterVolume() = presetStatus { saveMasterVolume() }

    override fun setUserVolume(db: Float, sendOnly: Boolean) = fire("setUserVolume") { setUserVolume(db) }

    override fun setUserMute(muted: Boolean) = fire("setUserMute") { setUserMute(muted) }

    override fun setFilter(ch: Int, band: Int, type: Int, freq: Float, gain: Float, q: Float) {
        val w = map.wire(ch).takeIf { it >= 0 } ?: return
        val old = s.filters[w][band]
        fire("setFilter") { setFilter(w, band, old.copy(filterType = type, freq = freq, gain = gain, q = q)) }
    }

    override fun setLinkwitzTransform(ch: Int, band: Int, f0: Float, q0: Float, fp: Float, qp: Float) {
        val w = map.wire(ch).takeIf { it >= 0 } ?: return
        val old = s.filters[w][band]
        fire("setLinkwitzTransform") {
            setFilter(w, band, old.copy(filterType = DspiConstants.FILTER_LINKWITZ_TRANSFORM, freq = f0, q = q0, gain = fp, qp = qp))
        }
    }

    override fun setBandBypass(ch: Int, band: Int, bypass: Boolean) {
        val w = map.wire(ch).takeIf { it >= 0 } ?: return
        fire("setBandBypass") { setBandBypass(w, band, bypass) }
    }

    override fun setCrossover(ch: Int, band: Int, type: Int, freq: Float) {
        val w = map.wire(ch).takeIf { it >= 0 } ?: return
        val old = s.xover[w][band]
        fire("setCrossover") { setCrossover(w, band, old.copy(filterType = type, freq = freq)) }
    }

    override fun setCrossoverBypass(ch: Int, band: Int, bypass: Boolean) {
        val w = map.wire(ch).takeIf { it >= 0 } ?: return
        fire("setCrossoverBypass") { setBandBypass(w, DspiConstants.XOVER_BAND_BASE + band, bypass) }
    }

    override fun setChannelDelay(ch: Int, ms: Float) {
        val w = map.wire(ch).takeIf { it >= 0 } ?: return
        fire("setChannelDelay") { setDelay(w, ms) }
    }

    override fun clearPeq(ch: Int) = Unit // TODO(BRIDGE)

    override fun clearAllBands(ch: Int, crossover: Boolean) = Unit // TODO(BRIDGE)

    override fun setAllBandsBypass(ch: Int, bypass: Boolean, crossover: Boolean) = Unit // TODO(BRIDGE)

    override fun canSetAllBypass(ch: Int, bypass: Boolean, crossover: Boolean) = false

    override fun setLoudness(en: Boolean) = fire("setLoudness") { setLoudness(en) }

    override fun setLoudnessRef(spl: Float, sendOnly: Boolean) = fire("setLoudnessRef") { setLoudnessRef(spl) }

    override fun setLoudnessIntensity(pct: Float, sendOnly: Boolean) = fire("setLoudnessIntensity") { setLoudnessIntensity(pct) }

    override fun setLoudnessOutputMask(mask: Int) = fire("setLoudnessOutputMask") { setLoudnessMask(mask) }

    override fun setCrossfeed(en: Boolean) = fire("setCrossfeed") { setCrossfeed(en) }

    override fun setCrossfeedPreset(p: Int) = fire("setCrossfeedPreset") { setCrossfeedPreset(p) }

    override fun setCrossfeedFreq(freq: Float, sendOnly: Boolean) = fire("setCrossfeedFreq") { setCrossfeedFreq(freq) }

    override fun setCrossfeedFeed(feed: Float, sendOnly: Boolean) = fire("setCrossfeedFeed") { setCrossfeedFeed(feed) }

    override fun setCrossfeedITD(en: Boolean) = fire("setCrossfeedITD") { setCrossfeedItd(en) }

    override fun setCrossfeedOutputPairMask(mask: Int) = fire("setCrossfeedOutputPairMask") { setCrossfeedOutputs(mask) }

    override fun setMatrixRoute(input: Int, output: Int, enabled: Boolean, gain: Float, invert: Boolean) =
        fire("setMatrixRoute") { setMatrixRoute(input, output, enabled, gain, invert) }

    override suspend fun setOutputEnable(output: Int, enabled: Boolean): Boolean =
        onFfi { runCatching { setOutputEnable(output, enabled) }.getOrDefault(false).also { emitState() } }

    override fun setOutputGain(output: Int, db: Float) = fire("setOutputGain") { setOutputGain(output, db) }

    override fun sendOutputGainToDevice(output: Int, db: Float) = fire("sendOutputGainToDevice") { setOutputGain(output, db) }

    override fun setOutputMute(output: Int, muted: Boolean) = fire("setOutputMute") { setOutputMute(output, muted) }

    override fun setOutputDelay(output: Int, ms: Float) = fire("setOutputDelay") { setOutputDelay(output, ms) }

    override fun sendOutputDelayToDevice(output: Int, ms: Float) = fire("sendOutputDelayToDevice") { setOutputDelay(output, ms) }

    override fun directRouting() = Unit // TODO(BRIDGE)

    override fun clearRouting() = Unit // TODO(BRIDGE)

    override fun core1ConflictOutputs(output: Int): List<Int> = emptyList() // TODO(BRIDGE)

    override fun enableOutputResolvingConflict(output: Int) = Unit // TODO(BRIDGE)

    override suspend fun checkCore1Conflict(output: Int): Boolean = onFfi { runCatching { checkCore1Conflict(output) }.getOrDefault(false) }

    override suspend fun setOutputPin(output: Int, pin: Int) = pinStatus { setOutputPin(output, pin) }

    override fun setOutputConfigMode(mode: Int) = fire("setOutputConfigMode") { setOutputConfigMode(mode) }

    override suspend fun saveOutputConfig() = presetStatus { saveOutputConfig() }

    override fun setChannelName(ch: Int, name: String) {
        val w = map.wire(ch).takeIf { it >= 0 } ?: return
        fire("setChannelName") { setChannelName(w, name) }
    }

    override fun resetChannelNames() = Unit // TODO(BRIDGE)

    override suspend fun savePreset(slot: Int) = presetStatus { savePreset(slot) }

    override suspend fun loadPreset(slot: Int) = presetStatus { loadPreset(slot) }

    override suspend fun deletePreset(slot: Int) = presetStatus { deletePreset(slot) }

    override suspend fun copyPreset(fromSlot: Int, toSlot: Int) = presetStatus { copyPreset(fromSlot, toSlot) }

    override fun setPresetName(slot: Int, name: String) = fire("setPresetName") { setPresetName(slot, name) }

    override fun setPresetStartup(mode: Int, slot: Int) = fire("setPresetStartup") { setPresetStartup(mode, slot) }

    override suspend fun presetChanges(): List<String> = onFfi { presetChanges() }

    override suspend fun saveParams() = flashStatus { saveParams() }

    override suspend fun loadParams() = flashStatus { loadParams() }

    override suspend fun factoryReset() = flashStatus { factoryReset() }

    override fun enterBootloader() = fire("enterBootloader") { enterBootloader() }

    override fun clearClips() = fire("clearClips") { clearClips() }

    override fun scanDevices() = Unit

    override fun selectDevice(serial: String) = fire("selectDevice") { selectDevice(serial) }

    override fun disconnectDevice() = fire("disconnectDevice") { disconnect() }

    override fun reconnect() = Unit

    override fun rescan() = Unit

    override suspend fun attachDevice(fd: Int, serial: String, vid: Int): Boolean = onFfi {
        try {
            openFd(fd, serial, vid)
            fetchAll()
            captureBaseline()
            emitState()
            _status.value = _status.value.copy(connected = true, connectionError = "")
            _devices.value = _devices.value.copy(selectedSerial = serial)
            true
        } catch (e: DspiException) {
            _status.value = _status.value.copy(connected = false, connectionError = e.message ?: "")
            false
        }
    }

    override suspend fun detachDevice(serial: String) = onFfi {
        closeFd()
        _status.value = _status.value.copy(connected = false)
    }

    override fun setInputSource(source: Int) = fire("setInputSource") { setInputSource(source) }

    override fun setLevellerEnabled(en: Boolean) = fire("setLevellerEnabled") { setLevellerEnabled(en) }

    override fun setLevellerAmount(pct: Float, sendOnly: Boolean) = fire("setLevellerAmount") { setLevellerAmount(pct) }

    override fun setLevellerSpeed(speed: Int) = fire("setLevellerSpeed") { setLevellerSpeed(speed) }

    override fun setLevellerMaxGain(db: Float, sendOnly: Boolean) = fire("setLevellerMaxGain") { setLevellerMaxGain(db) }

    override fun setLevellerGate(db: Float, sendOnly: Boolean) = fire("setLevellerGate") { setLevellerGate(db) }

    override fun setLevellerLookahead(en: Boolean) = fire("setLevellerLookahead") { setLevellerLookahead(en) }

    override fun setLevellerMasks(detector: Int, apply: Int) = fire("setLevellerMasks") { setLevellerMasks(detector, apply) }

    override fun setPsybassEnabled(en: Boolean) = fire("setPsybassEnabled") { setPsybassEnabled(en) }

    override fun setPsybassParam(param: Int, value: Float, sendOnly: Boolean) = fire("setPsybassParam") { setPsybassParam(param, value) }

    override fun setPsybassOutputMask(mask: Int) = fire("setPsybassOutputMask") { setPsybassMask(mask) }

    override fun setUpmixParam(id: Int, value: Float, sendOnly: Boolean) = fire("setUpmixParam") { setUpmixParam(id, value) }

    override fun setSubharmParam(id: Int, value: Float, sendOnly: Boolean) = fire("setSubharmParam") { setSubharmParam(id, value) }

    override fun setSubharmSolo(solo: Boolean) = fire("setSubharmSolo") { setSubharmSolo(solo) }

    override fun setTubeParam(idx: Int, value: Float, sendOnly: Boolean) = fire("setTubeParam") { setTubeParam(idx, value) }

    override suspend fun fetchUpmixStatus(): UpmixStatus? = onFfi { runCatching { fetchUpmixStatus() }.getOrNull() }

    override suspend fun fetchSubharmHeadroom(): Float? = onFfi { runCatching { fetchSubharmHeadroom() }.getOrNull() }

    override suspend fun fetchSubharmMeter(): List<Float> = onFfi { runCatching { fetchSubharmMeter() }.getOrDefault(emptyList()) }

    override fun limiterEnabled(out: Int) = s.limiterEnabled.getOrElse(out) { false }

    override fun limiterThreshold(out: Int) = s.limiterThresholdDb.getOrElse(out) { 0f }

    override fun limiterRelease(out: Int) = s.limiterReleaseMs.getOrElse(out) { 0f }

    override fun limiterLinkGroup(out: Int) = s.limiterLinkGroup.getOrElse(out) { 0 }

    override fun limiterReduction(out: Int) = 0f

    override fun setLimiterEnabled(out: Int, en: Boolean) =
        fire("setLimiterEnabled") { setLimiterParam(out, DspiConstants.LIMITER_PARAM_ENABLED, if (en) 1f else 0f) }

    override fun setLimiterThreshold(out: Int, db: Float, sendOnly: Boolean) =
        fire("setLimiterThreshold") { setLimiterParam(out, DspiConstants.LIMITER_PARAM_THRESHOLD_DB, db) }

    override fun setLimiterRelease(out: Int, ms: Float, sendOnly: Boolean) =
        fire("setLimiterRelease") { setLimiterParam(out, DspiConstants.LIMITER_PARAM_RELEASE_MS, ms) }

    override fun setLimiterLinkGroup(out: Int, group: Int) =
        fire("setLimiterLinkGroup") { setLimiterParam(out, DspiConstants.LIMITER_PARAM_LINK_GROUP, group.toFloat()) }

    override fun copyLimiterToAll(out: Int) = Unit // TODO(BRIDGE)

    override fun linkAllLimiterPairs() = Unit // TODO(BRIDGE)

    override fun unlinkAllLimiters() = Unit // TODO(BRIDGE)

    override fun disableAllLimiters() = Unit // TODO(BRIDGE)

    override suspend fun setOutputType(slot: Int, kind: Int) = pinStatus { setOutputType(slot, kind) }

    override suspend fun setI2sBckPin(role: Int, pin: Int) = pinStatus { setI2sBckPin(role, pin) }

    override suspend fun setI2sClockPinMode(mode: Int) = pinStatus { setI2sClockPinMode(mode) }

    override suspend fun setMckEnabled(enabled: Boolean) = pinStatus { setMckEnabled(enabled) }

    override suspend fun setMckPin(pin: Int) = pinStatus { setMckPin(pin) }

    override suspend fun setMckMultiplier(mult: Int) = pinStatus { setMckMultiplier(mult) }

    override suspend fun setAdatOutEnabled(enabled: Boolean) = pinStatus { setAdatOutEnabled(enabled) }

    override suspend fun setAdatOutPin(pin: Int) = pinStatus { setAdatOutPin(pin) }

    override suspend fun setSpdifRxPin(index: Int, pin: Int) = pinStatus { setSpdifRxPin(index, pin) }

    override suspend fun setSpdifInputEnabled(index: Int, enabled: Boolean) = pinStatus { setSpdifInputEnabled(index, enabled) }

    override suspend fun setI2sRxPin(pair: Int, pin: Int) = pinStatus { setI2sRxPin(pair, pin) }

    override suspend fun setI2sInputChannels(channels: Int) = pinStatus { setI2sInputChannels(channels) }

    override suspend fun setAdatInputEnabled(enabled: Boolean) = pinStatus { setAdatInputEnabled(enabled) }

    override suspend fun setAdatInputPin(pin: Int) = pinStatus { setAdatInputPin(pin) }

    override suspend fun setAdatInputClockMode(mode: Int) = pinStatus { setAdatInputClockMode(mode) }

    override fun setInputRate(index: Int) = fire("setInputRate") { setInputRate(index) }

    override fun setI2sClockMode(mode: Int) = fire("setI2sClockMode") { setI2sClockMode(mode) }

    override fun setLgSoundSync(enabled: Boolean) = fire("setLgSoundSync") { setLgSoundSync(enabled) }

    override fun setDacMute(enabled: Boolean, activeLow: Boolean, pin: Int, holdMs: Int, releaseMs: Int) =
        fire("setDacMute") { setDacMute(enabled, activeLow, pin, holdMs, releaseMs) }

    override suspend fun testDacMute(): Int = onFfi { statusOf(255) { testDacMute() } }

    override fun setUart(enabled: Boolean, txPin: Int, rxPin: Int, notify: Boolean, baud: Int) =
        fire("setUart") { setUart(se.bofr.dspi.core.UartConfig(enabled, txPin, rxPin, notify, baud.toLong())) }

    override fun setI2c(enabled: Boolean, sdaPin: Int, sclPin: Int, address: Int) =
        fire("setI2c") { setI2c(se.bofr.dspi.core.I2cConfig(enabled, sdaPin, sclPin, address)) }

    override fun refreshCtrlIfaces() = fire("refreshCtrlIfaces") { fetchCtrlIfaces() }

    override suspend fun fetchInputLock(which: Int): InputLockStatus? = onFfi { runCatching { fetchInputLock(which) }.getOrNull() }

    override suspend fun fetchAdatOutStatus(): AdatOutStatus? = onFfi { runCatching { fetchAdatOutStatus() }.getOrNull() }

    override suspend fun fetchLgStatus(): LgStatus? = onFfi { runCatching { fetchLgStatus() }.getOrNull() }

    override suspend fun fetchInputRate(): Int = onFfi { runCatching { fetchInputRate().toInt() }.getOrDefault(0) }

    // ═══ Links / clipboard / editor / visibility — TODO(BRIDGE) ═══════

    override fun liveInputCount() = _status.value.activeInputChannels

    override fun pairPartner(ch: Int) = -1

    override fun pairAvailable(ch: Int) = false

    override fun isInputLinked(ch: Int) = false

    override fun linkedPartner(ch: Int) = -1

    override fun inputPairMatches(ch: Int) = false

    override fun setInputLinked(ch: Int, linked: Boolean, keepCh: Int) = Unit

    override val inputLinks: Int get() = 0

    override fun restoreInputLinks(links: Int) = Unit

    override fun copyChannel(ch: Int) = Unit

    override fun canPaste() = false

    override fun pasteChannel(ch: Int) = Unit

    override fun channelBands(ch: Int): ChannelBands? {
        val w = map.wire(ch).takeIf { it >= 0 } ?: return null
        return ChannelBands(s.filters[w], if (map.outputOf(ch) >= 0) s.xover[w] else emptyList())
    }

    override fun channelGainOffset(ch: Int): Float {
        val o = map.outputOf(ch)
        if (o >= 0) return outputGainDB(o)
        val i = map.inputOf(ch)
        return if (i >= 0) inputPreampDB(i) else 0f
    }

    override fun sendBandLive(ch: Int, band: Int, params: FilterParams) = Unit

    override fun commitBands(ch: Int, bands: List<Int>, params: List<FilterParams>) = Unit

    private val visible = BooleanArray(ChannelMap.APP_CHANNEL_COUNT) { true }

    override fun channelVisible(ch: Int) = visible.getOrElse(ch) { false }

    override fun setChannelVisible(ch: Int, visible: Boolean) {
        if (ch in this.visible.indices) this.visible[ch] = visible
    }

    // ═══ Controllers (inert stubs) ════════════════════════════════════

    override val rta: RtaController = StubRtaController()
    override val siggen: SiggenController = StubSiggenController()
    override val stats: StatsController = StubStatsController()
    override val cs: CsController = StubCsController()
    override val monitor: MonitorModel = StubMonitorModel()
    override val configFiles: ConfigFiles = StubConfigFiles()

    override fun setUiVisible(visible: Boolean) = Unit
}

private class StubRtaController : RtaController {
    override val caps = MutableStateFlow<RtaCapsInfo?>(null)
    override val snapshot = MutableStateFlow<RtaSnapshot?>(null)
    override var fftOrder = 0
    override var avgMs = 0
    override var peakDecay = 0

    override fun update(id: Int, tap: Int, mask: Int, wantsBins: Boolean) = Unit

    override fun release(id: Int) = Unit

    override fun appChannel(tap: Int, index: Int) = -1

    override fun tapOf(appChannel: Int) = -1

    override fun tapIndex(appChannel: Int) = -1

    override fun availableChannels(tap: Int): List<Int> = emptyList()
}

private class StubSiggenController : SiggenController {
    override val caps = MutableStateFlow<SiggenCaps?>(null)
    override val draft = MutableStateFlow<SiggenConfig?>(null)
    override val status = MutableStateFlow<SiggenStatus?>(null)
    override var watching = false

    override fun selectType(type: Int) = Unit

    override fun setParam(index: Int, value: Double) = Unit

    override fun setFlag(flag: Int, on: Boolean) = Unit

    override fun cycleOutput(output: Int) = Unit

    override fun selectAllOutputs() = Unit

    override fun clearOutputs() = Unit

    override fun setLevelDb(db: Float) = Unit

    override fun setDurationMs(ms: Long) = Unit

    override fun setRepeat(count: Int) = Unit

    override fun setGapMs(ms: Int) = Unit

    override suspend fun start() = false

    override fun stop() = Unit

    override fun stopNow() = Unit

    override fun identify(output: Int) = Unit
}

private class StubStatsController : StatsController {
    override var watching = false
    override val info = MutableStateFlow<Map<Int, Long>>(emptyMap())
    override val buffers = MutableStateFlow<BufferStats?>(null)
    override val reconnects = MutableStateFlow(0)

    override fun resetWatermarks() = Unit
}

private class StubCsController : CsController {
    override val model = MutableStateFlow("")
    override val supported = MutableStateFlow(false)
    override val loaded = MutableStateFlow(false)
    override val unsaved = MutableStateFlow(false)

    override suspend fun apply(opJson: String) = """{"ok":false,"error":"not implemented"}"""

    override fun sendOnly(opJson: String) = Unit

    override fun refresh() = Unit

    override suspend fun save() = 255

    override suspend fun revert() = 255

    override fun statusMessage(code: Int) = ""
}

private class StubMonitorModel : MonitorModel {
    override var watching = false
    override var paused = false
    override val lines = MutableStateFlow<List<MonitorLine>>(emptyList())

    override fun clear() = Unit

    override fun allText() = ""
}

private class StubConfigFiles : ConfigFiles {
    override suspend fun exportConfiguration(name: String) = ""

    override fun inspectConfiguration(text: String) = """{"ok":false,"error":"not implemented"}"""

    override suspend fun importConfiguration(text: String, volumes: Boolean, hardware: Boolean) =
        """{"ok":false,"error":"not implemented"}"""

    override suspend fun exportFilters() = ""

    override suspend fun inspectFilters(text: String) = """{"ok":false,"error":"not implemented"}"""

    override suspend fun importFilters(text: String, wires: List<Int>) = """{"ok":false,"error":"not implemented"}"""
}
