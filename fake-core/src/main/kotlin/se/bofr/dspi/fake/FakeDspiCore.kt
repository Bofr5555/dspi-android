package se.bofr.dspi.fake

import java.util.Collections
import java.util.Timer
import kotlin.concurrent.fixedRateTimer
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import se.bofr.dspi.core.AdatOutStatus
import se.bofr.dspi.core.BootloaderCounts
import se.bofr.dspi.core.BufferStats
import se.bofr.dspi.core.CoreInfo
import se.bofr.dspi.core.DeviceEvents
import se.bofr.dspi.core.DeviceInfo
import se.bofr.dspi.core.DspState
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.core.DspiConstants.BANDS_PER_CHANNEL
import se.bofr.dspi.core.DspiConstants.FILTER_FLAT
import se.bofr.dspi.core.DspiConstants.FILTER_LINKWITZ_TRANSFORM
import se.bofr.dspi.core.DspiConstants.MAX_CHANNELS
import se.bofr.dspi.core.DspiConstants.MAX_INPUTS
import se.bofr.dspi.core.DspiConstants.MAX_OUTPUTS
import se.bofr.dspi.core.DspiConstants.MAX_PRESETS
import se.bofr.dspi.core.DspiConstants.MAX_XOVER_BANDS
import se.bofr.dspi.core.DspiConstants.XOVER_BAND_BASE
import se.bofr.dspi.core.DspiException
import se.bofr.dspi.core.FilterParams
import se.bofr.dspi.core.I2cConfig
import se.bofr.dspi.core.InputLockStatus
import se.bofr.dspi.core.LgStatus
import se.bofr.dspi.core.MonitorBatch
import se.bofr.dspi.core.MonitorEntry
import se.bofr.dspi.core.NotifyListener
import se.bofr.dspi.core.NotifyResult
import se.bofr.dspi.core.PdmBufferStats
import se.bofr.dspi.core.PinUse
import se.bofr.dspi.core.PresetDirectory
import se.bofr.dspi.core.RtaCapsInfo
import se.bofr.dspi.core.RtaChannelFrame
import se.bofr.dspi.core.RtaListener
import se.bofr.dspi.core.RtaRequest
import se.bofr.dspi.core.RtaSnapshot
import se.bofr.dspi.core.SiggenCaps
import se.bofr.dspi.core.SiggenConfig
import se.bofr.dspi.core.SiggenParam
import se.bofr.dspi.core.SiggenStatus
import se.bofr.dspi.core.SiggenTypeInfo
import se.bofr.dspi.core.SpdifBufferStats
import se.bofr.dspi.core.SpdifRxStatus
import se.bofr.dspi.core.SystemStatus
import se.bofr.dspi.core.UartConfig
import se.bofr.dspi.core.UnimplementedDspiCore
import se.bofr.dspi.core.UpmixStatus

/**
 * Hardware-free, stateful [se.bofr.dspi.core.DspiCoreApi] for demo mode and
 * tests. It models what the real core does to its `DspState` for every
 * setter (`DSPi-Console-Linux:core/src/commands.rs`, `preset.rs` @ 4683a2c:
 * clamping, quantising, the crossfeed preset table, tube type rows,
 * Core 1 conflicts, pin refusals), keeps presets and an unsaved-changes
 * baseline, computes curves with [DspMath], and simulates notifications,
 * the spectrum analyser, the signal generator, statistics, the interrupt
 * monitor, control surfaces and configuration files plausibly.
 *
 * Seed it from FFI's V32 fixture with [fromFixture] / [seeded], or use the
 * built-in [demoDspState] (RP2350, 8 in / 9 out).
 *
 * Test hooks: [calls] (every call, `name(args)`), [failNext] (inject
 * transfer errors), [simulateRemote] (a change made on the device, announced
 * like a notification), [simulateClip], [emitRtaFrame].
 *
 * Thread safety: every core method is synchronized; listeners are invoked
 * outside the lock.
 */
open class FakeDspiCore(
    initial: DspState = demoDspState(),
    /** Demo mode: a timer feeds the spectrum analyser while it is requested. */
    private val animate: Boolean = false,
    /** The serial the fake device reports. */
    val serial: String = DEMO_SERIAL
) : UnimplementedDspiCore() {
    @Volatile private var state: DspState = initial

    @Volatile private var connected = true

    private var baseline: DspState = initial
    private val presets: Array<DspState?> = arrayOfNulls(MAX_PRESETS)
    private val factory: DspState = factoryOf(initial)

    @Volatile var notifyListener: NotifyListener? = null
        private set

    @Volatile var rtaListener: RtaListener? = null
        private set

    @Volatile var deviceEvents: DeviceEvents? = null
        private set

    /** Calls made, in order, as `name(args)` (or `name`) — for tests. */
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private class Fault(var remaining: Int, val error: () -> DspiException)

    private val faults = HashMap<String, Fault>()

    init {
        for (slot in 0 until MAX_PRESETS) if ((initial.presetOccupied shr slot) and 1 == 1) presets[slot] = initial
    }

    // ═══ Test hooks ══════════════════════════════════════════════════════

    /** The next [times] calls of [method] (Kotlin name) throw [error]. */
    @Synchronized
    fun failNext(method: String, times: Int = 1, error: () -> DspiException = { DspiException.Transfer("injected") }) {
        faults[method] = Fault(times, error)
    }

    /** How often [method] was called. */
    fun callCount(method: String): Int = synchronized(calls) { calls.count { it == method || it.startsWith("$method(") } }

    /** Replace the live state (no notification) — for tests. */
    @Synchronized
    fun setState(s: DspState) {
        state = s
    }

    /** The device goes away without a host call. */
    fun simulateUnplug() {
        synchronized(this) { connected = false }
        deviceEvents?.onDeparted(serial)
    }

    private var pendingNotify = NotifyResult(0, 0)
    private val monitor = ArrayList<MonitorEntry>()
    private var monitorId = 0L

    /**
     * Something changed on the device (a knob, the OS volume, another host):
     * apply [change], queue `flags`/`filterWires` for [processNotifications]
     * and wake the listener, as the core's notify thread does.
     */
    fun simulateRemote(
        flags: Int = DspiConstants.NOTIFY_STATE,
        filterWires: Long = 0,
        text: String = "PARAM_CHANGED",
        change: (DspState) -> DspState = { it }
    ) {
        synchronized(this) {
            state = change(state)
            pendingNotify = NotifyResult(pendingNotify.flags or flags.toLong(), pendingNotify.filterChannels or filterWires)
            log(text)
        }
        notifyListener?.onNotifications()
    }

    /** The user turned the volume knob / OS volume. */
    fun simulateUserVolume(db: Float) = simulateRemote(text = "USER_VOLUME $db dB") { it.copy(userVolumeDb = db.coerceIn(-60f, 0f)) }

    /** A filter band changed on the device (control surface, other host). */
    fun simulateFilterChange(wire: Int, band: Int, p: FilterParams) = simulateRemote(
        flags = DspiConstants.NOTIFY_STATE or DspiConstants.NOTIFY_CURVES,
        filterWires = 1L shl wire,
        text = "EQ ch $wire band $band"
    ) { it.copy(filters = it.filters.with(wire, it.filters[wire].with(band, p))) }

    /** A preset was loaded on the device itself. */
    fun simulatePresetLoaded(slot: Int) {
        synchronized(this) {
            presets[slot]?.let { state = restorePreset(it, slot) }
            baseline = state
        }
        simulateRemote(
            flags =
            DspiConstants.NOTIFY_STATE or DspiConstants.NOTIFY_CURVES or DspiConstants.NOTIFY_REFRESHED or DspiConstants.NOTIFY_PRESET,
            text = "PRESET_LOADED $slot"
        )
    }

    /** Latch a clip on [wire] (sticky until clearClips). */
    @Synchronized
    fun simulateClip(wire: Int) {
        stickyClips = stickyClips or (1L shl wire)
    }

    /** Peaks fetchStatus reports (per wire); null = animated. */
    @Volatile var peaksOverride: List<Float>? = null

    // ═══ Internals ═══════════════════════════════════════════════════════

    @Synchronized
    private fun <T> op(name: String, args: String = "", device: Boolean = true, block: () -> T): T {
        calls += if (args.isEmpty()) name else "$name($args)"
        faults[name]?.let {
            if (it.remaining > 0) {
                it.remaining--
                throw it.error()
            }
        }
        if (device && !connected) throw DspiException.Disconnected()
        return block()
    }

    private fun set(name: String, args: String = "", f: (DspState) -> DspState) = op(name, args) { state = f(state) }

    private fun log(text: String) {
        monitor += MonitorEntry(++monitorId, System.currentTimeMillis(), text)
        if (monitor.size > 2000) monitor.removeAt(0)
    }

    private fun refuse(status: Int): Nothing = throw DspiException.Refused(status)

    private fun bad(what: String): Nothing = throw DspiException.InvalidArgument(what)

    private val s get() = state

    private fun outputOfWire(w: Int): Int = if (w >= s.numInputChannels && w < s.numChannels) w - s.numInputChannels else -1

    private fun rp2350() = s.platformId == 1

    // ═══ Device management ═══════════════════════════════════════════════

    override fun openFd(fd: Int, serial: String, vid: Int) = op("openFd", "$fd, $serial, $vid", device = false) { connected = true }

    override fun closeFd() = op("closeFd", device = false) { connected = false }

    override fun scanDevices(): List<DeviceInfo> = op("scanDevices", device = false) {
        if (connected) listOf(DeviceInfo(serial, 1)) else emptyList()
    }

    override fun preampMaxDb(): Float = if (rp2350()) 24f else 18f

    override fun inaccessibleDevices(): Long = 0

    override fun selectDevice(serial: String) = op("selectDevice", serial, device = false) {
        if (serial != this.serial) throw DspiException.Disconnected()
        connected = true
    }

    override fun disconnect() = op("disconnect", device = false) { connected = false }

    override fun isConnected(): Boolean = connected

    override fun maxDelayMs(): Float = if (rp2350()) 42f else 21f

    // ═══ Sync / status ═══════════════════════════════════════════════════

    override fun fetchAll() = op("fetchAll") {}

    override fun refreshParams() = op("refreshParams") {}

    override fun applyAllParams() = op("applyAllParams") { if (!s.bulkValid) throw DspiException.Disconnected() }

    override fun getState(): DspState = state

    private var tick = 0
    private var stickyClips = 0L

    override fun fetchStatus(): SystemStatus = op("fetchStatus") {
        tick++
        val st = s
        val peaks =
            peaksOverride ?: List(MAX_CHANNELS) { w ->
                val out = outputOfWire(w)
                when {
                    w < st.numInputChannels -> if (w < 2) (0.35 + 0.25 * sin(tick * 0.21 + w)).toFloat() else 0f
                    out >= 0 && st.outputEnabled[out] && !st.outputMuted[out] -> (0.3 + 0.2 * sin(tick * 0.17 + w)).toFloat()
                    else -> 0f
                }
            }
        SystemStatus(
            peaks = peaks,
            cpu0 = 22 + tick % 3,
            cpu1 = if (st.core1Mode == 0) 3 else 18 + tick % 2,
            clipFlags = stickyClips,
            activeInputChannels = if (st.numInputChannels > 2) st.numInputChannels else 2,
            numChannels = st.numChannels
        )
    }

    override fun clearClips() = op("clearClips") { stickyClips = 0 }

    // ═══ EQ ══════════════════════════════════════════════════════════════

    override fun setFilter(ch: Int, band: Int, params: FilterParams) = set("setFilter", "$ch, $band, $params") {
        if (ch !in 0 until MAX_CHANNELS || band !in 0 until BANDS_PER_CHANNEL) bad("channel/band")
        val p = if (params.filterType != FILTER_LINKWITZ_TRANSFORM) params.copy(gain = DspMath.quantizeGain(params.gain)) else params
        it.copy(filters = it.filters.with(ch, it.filters[ch].with(band, p)))
    }

    override fun setCrossover(ch: Int, xband: Int, params: FilterParams) = set("setCrossover", "$ch, $xband, $params") {
        if (outputOfWire(ch) < 0 || xband !in 0 until MAX_XOVER_BANDS) bad("crossover channel/band")
        if (params.filterType != FILTER_FLAT && !DspMath.isCrossover(params.filterType)) bad("crossover type")
        it.copy(xover = it.xover.with(ch, it.xover[ch].with(xband, params)))
    }

    override fun setBandBypass(ch: Int, band: Int, bypass: Boolean) = set("setBandBypass", "$ch, $band, $bypass") {
        if (ch !in 0 until MAX_CHANNELS) bad("channel")
        when (band) {
            in 0 until BANDS_PER_CHANNEL -> it.copy(
                filters = it.filters.with(ch, it.filters[ch].with(band, it.filters[ch][band].copy(bypass = bypass)))
            )
            in XOVER_BAND_BASE until XOVER_BAND_BASE + MAX_XOVER_BANDS -> {
                val x = band - XOVER_BAND_BASE
                it.copy(xover = it.xover.with(ch, it.xover[ch].with(x, it.xover[ch][x].copy(bypass = bypass))))
            }
            else -> bad("band")
        }
    }

    // ═══ Preamp / bypass / volume ════════════════════════════════════════

    private fun preamp(db: Float) = DspMath.quantizeGain(db.coerceIn(-60f, preampMaxDb()))

    override fun setPreamp(db: Float) = set("setPreamp", "$db") {
        val v = preamp(db)
        it.copy(preampDb = v, inputPreampDb = List(MAX_INPUTS) { v })
    }

    override fun setInputPreamp(input: Int, db: Float) = set("setInputPreamp", "$input, $db") {
        if (input !in 0 until MAX_INPUTS) bad("input")
        val v = preamp(db)
        it.copy(inputPreampDb = it.inputPreampDb.with(input, v), preampDb = if (input == 0) v else it.preampDb)
    }

    override fun setBypass(enabled: Boolean) = set("setBypass", "$enabled") { it.copy(bypass = enabled) }

    override fun setMasterVolume(db: Float) = set("setMasterVolume", "$db") {
        val v = if (db <=
            DspiConstants.MASTER_VOL_MUTE_DB
        ) {
            DspiConstants.MASTER_VOL_MUTE_DB
        } else {
            (DspMath.roundHalfAway(db * 2f) / 2f).coerceIn(-127f, 0f)
        }
        it.copy(masterVolumeDb = v)
    }

    override fun setMasterVolumeMode(mode: Int) = set("setMasterVolumeMode", "$mode") { it.copy(masterVolumeMode = mode) }

    override fun saveMasterVolume() = op("saveMasterVolume") { log("SAVE_MASTER_VOLUME") }

    override fun setUserVolume(db: Float) = set("setUserVolume", "$db") { it.copy(userVolumeDb = db.coerceIn(-60f, 0f)) }

    override fun setUserMute(muted: Boolean) = set("setUserMute", "$muted") { it.copy(userMute = muted) }

    override fun setDelay(ch: Int, ms: Float) {
        val out = outputOfWire(ch)
        if (out >= 0) return setOutputDelay(out, ms)
        set("setDelay", "$ch, $ms") {
            if (ch !in 0 until MAX_CHANNELS) bad("channel")
            it.copy(channelDelays = it.channelDelays.with(ch, DspMath.quantizeDelay(ms).coerceIn(0f, maxDelayMs())))
        }
    }

    // ═══ Loudness / crossfeed / leveller / input / psybass ═══════════════

    override fun setLoudness(enabled: Boolean) = set("setLoudness", "$enabled") { it.copy(loudnessEnabled = enabled) }

    override fun setLoudnessRef(spl: Float) = set("setLoudnessRef", "$spl") { it.copy(loudnessRefSpl = spl) }

    override fun setLoudnessIntensity(pct: Float) = set("setLoudnessIntensity", "$pct") { it.copy(loudnessIntensity = pct) }

    override fun setLoudnessMask(mask: Int) = set("setLoudnessMask", "$mask") { it.copy(loudnessOutputMask = mask and 0xFFFF) }

    override fun setCrossfeed(enabled: Boolean) = set("setCrossfeed", "$enabled") { it.copy(crossfeedEnabled = enabled) }

    override fun setCrossfeedPreset(preset: Int) = set("setCrossfeedPreset", "$preset") {
        val known = FakeTables.CROSSFEED_PRESETS.getOrNull(preset)
        it.copy(
            crossfeedPreset = preset,
            crossfeedFreq = known?.first ?: it.crossfeedFreq,
            crossfeedFeed =
            known?.second ?: it.crossfeedFeed
        )
    }

    override fun setCrossfeedFreq(freq: Float) = set("setCrossfeedFreq", "$freq") { it.copy(crossfeedFreq = freq) }

    override fun setCrossfeedFeed(feed: Float) = set("setCrossfeedFeed", "$feed") { it.copy(crossfeedFeed = feed) }

    override fun setCrossfeedItd(enabled: Boolean) = set("setCrossfeedItd", "$enabled") { it.copy(crossfeedItd = enabled) }

    override fun setCrossfeedOutputs(pairMask: Int) = set("setCrossfeedOutputs", "$pairMask") {
        it.copy(
            crossfeedOutputPairMask =
            pairMask and 0xFF
        )
    }

    override fun setLevellerEnabled(enabled: Boolean) = set("setLevellerEnabled", "$enabled") { it.copy(levellerEnabled = enabled) }

    override fun setLevellerAmount(pct: Float) = set("setLevellerAmount", "$pct") { it.copy(levellerAmount = pct) }

    override fun setLevellerSpeed(speed: Int) = set("setLevellerSpeed", "$speed") { it.copy(levellerSpeed = speed) }

    override fun setLevellerMaxGain(db: Float) = set("setLevellerMaxGain", "$db") { it.copy(levellerMaxGainDb = db) }

    override fun setLevellerLookahead(enabled: Boolean) = set("setLevellerLookahead", "$enabled") { it.copy(levellerLookahead = enabled) }

    override fun setLevellerGate(db: Float) = set("setLevellerGate", "$db") { it.copy(levellerGateDb = db) }

    override fun setLevellerMasks(detector: Int, apply: Int) = set("setLevellerMasks", "$detector, $apply") {
        it.copy(levellerDetectorMask = detector, levellerApplyMask = apply)
    }

    override fun setInputSource(source: Int) = set("setInputSource", "$source") { it.copy(inputSource = source) }

    override fun setPsybassParam(param: Int, value: Float) = set("setPsybassParam", "$param, $value") {
        when (param) {
            DspiConstants.PSYBASS_PARAM_CUTOFF -> it.copy(psybassCutoffHz = value.coerceIn(30f, 300f))
            DspiConstants.PSYBASS_PARAM_HARMONICS -> it.copy(psybassHarmonicsDb = value.coerceIn(-24f, 12f))
            DspiConstants.PSYBASS_PARAM_DRIVE -> it.copy(psybassDriveDb = value.coerceIn(0f, 18f))
            DspiConstants.PSYBASS_PARAM_CHARACTER -> it.copy(psybassCharacterPct = value.coerceIn(0f, 100f))
            DspiConstants.PSYBASS_PARAM_ORIGINAL -> it.copy(psybassOriginalDb = value.coerceIn(-60f, 0f))
            else -> bad("psybass param")
        }
    }

    override fun setPsybassMask(mask: Int) = set("setPsybassMask", "$mask") { it.copy(psybassOutputMask = mask and 0xFFFF) }

    override fun setPsybassEnabled(enabled: Boolean) = set("setPsybassEnabled", "$enabled") { it.copy(psybassEnabled = enabled) }

    // ═══ Upmix / subharm / tube ══════════════════════════════════════════

    override fun setUpmixParam(id: Int, value: Float) = set("setUpmixParam", "$id, $value") {
        if (id !in 0 until DspiConstants.UPMIX_PARAM_COUNT) bad("upmix param")
        val (lo, hi) = FakeTables.UPMIX_LIMITS[id]
        var v = value.coerceIn(lo, hi)
        if (id <= DspiConstants.UPMIX_PARAM_SURROUND_MODE) {
            v = DspMath.roundHalfAway(v)
        } else if (id == DspiConstants.UPMIX_PARAM_PRESENCE) {
            v = DspMath.roundHalfAway(v * 2f) / 2f
        }
        it.copy(upmix = it.upmix.with(id, v))
    }

    override fun fetchUpmixStatus(): UpmixStatus = op("fetchUpmixStatus") {
        if (!rp2350()) throw DspiException.Unsupported()
        val on = s.upmix[DspiConstants.UPMIX_PARAM_ENABLED] != 0f
        UpmixStatus(
            active = on,
            parkedReason = if (on) 0 else 1,
            correlation = 0.62f,
            balance = 0.05f,
            centerGain = 0.7f,
            lsGain = 0.4f,
            rsGain = 0.4f
        )
    }

    override fun setSubharmParam(id: Int, value: Float) = set("setSubharmParam", "$id, $value") {
        if (id !in 0 until DspiConstants.SUBHARM_PARAM_COUNT) bad("subharm param")
        val (lo, hi) = FakeTables.SUBHARM_LIMITS[id]
        val whole =
            id == DspiConstants.SUBHARM_PARAM_ENABLED ||
                id == DspiConstants.SUBHARM_PARAM_MASK ||
                id == DspiConstants.SUBHARM_PARAM_SELECT ||
                id == DspiConstants.SUBHARM_PARAM_LINK
        val v = if (whole) DspMath.roundHalfAway(value).coerceIn(lo, hi) else value.coerceIn(lo, hi)
        it.copy(subharm = it.subharm.with(id, v))
    }

    override fun setSubharmSolo(solo: Boolean) = set("setSubharmSolo", "$solo") { it.copy(subharmSolo = solo) }

    override fun fetchSubharmHeadroom(): Float = op("fetchSubharmHeadroom") {
        val loud =
            max(
                s.subharm[DspiConstants.SUBHARM_PARAM_LOW],
                max(s.subharm[DspiConstants.SUBHARM_PARAM_HIGH], s.subharm[DspiConstants.SUBHARM_PARAM_TOP])
            )
        0f - loud.coerceAtLeast(0f)
    }

    override fun fetchSubharmMeter(): List<Float> = op("fetchSubharmMeter") {
        val on = s.subharm[DspiConstants.SUBHARM_PARAM_ENABLED] != 0f
        List(MAX_OUTPUTS) { o -> if (on && o < s.numOutputChannels) (0.2 + 0.1 * sin(tick * 0.3 + o)).toFloat() else 0f }
    }

    override fun setTubeParam(idx: Int, value: Float) = set("setTubeParam", "$idx, $value") {
        if (idx !in 0 until DspiConstants.TUBE_PARAM_COUNT) bad("tube param")
        val (lo, hi) = FakeTables.TUBE_LIMITS[idx]
        val whole =
            idx == DspiConstants.TUBE_PARAM_ENABLED ||
                idx == DspiConstants.TUBE_PARAM_MASK ||
                idx == DspiConstants.TUBE_PARAM_TYPE ||
                idx == DspiConstants.TUBE_PARAM_RECTIFIER ||
                idx == DspiConstants.TUBE_PARAM_XFMR
        val v = if (whole) DspMath.roundHalfAway(value).coerceIn(lo, hi) else value.coerceIn(lo, hi)
        val t = it.tube.toMutableList()
        val character = DspiConstants.TUBE_PARAM_BIAS..DspiConstants.TUBE_PARAM_SAG
        if (idx == DspiConstants.TUBE_PARAM_TYPE && v >= 1f) {
            FakeTables.TUBE_ROWS[v.toInt() - 1].forEachIndexed { k, x -> t[DspiConstants.TUBE_PARAM_BIAS + k] = x }
        } else if (idx in character && t[idx] != v) {
            t[DspiConstants.TUBE_PARAM_TYPE] = 0f
        }
        t[idx] = v
        it.copy(tube = t)
    }

    // ═══ Matrix / outputs / limiter ══════════════════════════════════════

    override fun setMatrixRoute(input: Int, output: Int, enabled: Boolean, gain: Float, invert: Boolean) =
        set("setMatrixRoute", "$input, $output, $enabled, $gain, $invert") {
            if (input !in 0 until MAX_INPUTS || output !in 0 until MAX_OUTPUTS) bad("matrix")
            it.copy(
                matrixRouting = it.matrixRouting.with(input, it.matrixRouting[input].with(output, enabled)),
                matrixGain = it.matrixGain.with(input, it.matrixGain[input].with(output, DspMath.quantizeGain(gain))),
                matrixInvert = it.matrixInvert.with(input, it.matrixInvert[input].with(output, invert))
            )
        }

    /** Core 1 runs the PDM output or the EQ worker for outputs 2..n-2, never both. */
    private fun conflicts(st: DspState, output: Int): Boolean {
        val n = st.numOutputChannels
        val pdm = n - 1
        return if (output == pdm) (2 until n - 1).any { st.outputEnabled[it] } else output in 2 until pdm && st.outputEnabled[pdm]
    }

    private fun core1ModeOf(st: DspState): Int {
        val n = st.numOutputChannels
        return when {
            n > 0 && st.outputEnabled[n - 1] -> 1
            (2 until n - 1).any { st.outputEnabled[it] } -> 2
            else -> 0
        }
    }

    override fun setOutputEnable(output: Int, enabled: Boolean): Boolean = op("setOutputEnable", "$output, $enabled") {
        if (output !in 0 until MAX_OUTPUTS) bad("output")
        val actual = if (enabled && conflicts(s, output)) s.outputEnabled[output] else enabled
        val ns = s.copy(outputEnabled = s.outputEnabled.with(output, actual))
        state = ns.copy(core1Mode = core1ModeOf(ns))
        actual
    }

    override fun setOutputGain(output: Int, db: Float) = set("setOutputGain", "$output, $db") {
        if (output !in 0 until MAX_OUTPUTS) bad("output")
        it.copy(outputGainDb = it.outputGainDb.with(output, DspMath.quantizeGain(db)))
    }

    override fun setOutputMute(output: Int, muted: Boolean) = set("setOutputMute", "$output, $muted") {
        if (output !in 0 until MAX_OUTPUTS) bad("output")
        it.copy(outputMuted = it.outputMuted.with(output, muted))
    }

    override fun setOutputDelay(output: Int, ms: Float) = set("setOutputDelay", "$output, $ms") {
        if (output !in 0 until MAX_OUTPUTS) bad("output")
        val v = DspMath.quantizeDelay(ms).coerceIn(0f, maxDelayMs())
        val ch = it.numInputChannels + output
        it.copy(
            outputDelayMs = it.outputDelayMs.with(output, v),
            channelDelays = if (ch <
                MAX_CHANNELS
            ) {
                it.channelDelays.with(ch, v)
            } else {
                it.channelDelays
            }
        )
    }

    override fun setLimiterParam(output: Int, param: Int, value: Float) = set("setLimiterParam", "$output, $param, $value") {
        val v =
            when (param) {
                DspiConstants.LIMITER_PARAM_ENABLED -> if (value != 0f) 1f else 0f
                DspiConstants.LIMITER_PARAM_THRESHOLD_DB -> value.coerceIn(-30f, 0f)
                DspiConstants.LIMITER_PARAM_RELEASE_MS -> value.coerceIn(10f, 1000f)
                DspiConstants.LIMITER_PARAM_LINK_GROUP -> DspMath.roundHalfAway(value).coerceIn(0f, 4f)
                else -> bad("limiter param")
            }
        val outs =
            when {
                output == DspiConstants.LIMITER_ALL_OUTPUTS -> 0 until it.numOutputChannels
                output in 0 until MAX_OUTPUTS -> output..output
                else -> bad("output")
            }
        var n = it
        for (o in outs) {
            n =
                when (param) {
                    DspiConstants.LIMITER_PARAM_ENABLED -> n.copy(limiterEnabled = n.limiterEnabled.with(o, v != 0f))
                    DspiConstants.LIMITER_PARAM_THRESHOLD_DB -> n.copy(limiterThresholdDb = n.limiterThresholdDb.with(o, v))
                    DspiConstants.LIMITER_PARAM_RELEASE_MS -> n.copy(limiterReleaseMs = n.limiterReleaseMs.with(o, v))
                    else -> n.copy(limiterLinkGroup = n.limiterLinkGroup.with(o, v.toInt()))
                }
        }
        n
    }

    override fun fetchLimiterMeter(): List<Float> = op("fetchLimiterMeter") {
        List(MAX_OUTPUTS) { o -> if (o < s.numOutputChannels && s.limiterEnabled[o]) ((tick % 20) / 10f) else 0f }
    }

    override fun fetchCore1Mode(): Int = op("fetchCore1Mode") { core1ModeOf(s).also { m -> state = s.copy(core1Mode = m) } }

    override fun checkCore1Conflict(output: Int): Boolean = op("checkCore1Conflict", "$output") { conflicts(s, output) }

    // ═══ Pins / hardware IO ══════════════════════════════════════════════

    private val validPins = (0..28).filter { it < 23 || it > 25 }

    private fun pinCheck(pin: Int, own: Int = -1) {
        if (pin != DspiConstants.PIN_RESET_TO_DEFAULT && pin !in validPins) refuse(DspiConstants.PIN_CONFIG_INVALID_PIN)
        val used = s.outputPins.take(s.numPinOutputs).withIndex().any { (i, p) -> i != own && p == pin }
        if (used) refuse(DspiConstants.PIN_CONFIG_PIN_IN_USE)
    }

    override fun setOutputPin(slot: Int, pin: Int) = set("setOutputPin", "$slot, $pin") {
        if (slot !in 0 until it.numPinOutputs) refuse(DspiConstants.PIN_CONFIG_INVALID_OUTPUT)
        pinCheck(pin, slot)
        val p = if (pin == DspiConstants.PIN_RESET_TO_DEFAULT) factory.outputPins[slot] else pin
        it.copy(outputPins = it.outputPins.with(slot, p))
    }

    override fun fetchOutputPin(slot: Int): Int = op("fetchOutputPin", "$slot") { s.outputPins.getOrElse(slot) { 0 } }

    override fun setOutputConfigMode(mode: Int) = set("setOutputConfigMode", "$mode") { it.copy(outputConfigMode = mode) }

    override fun saveOutputConfig() = op("saveOutputConfig") { log("SAVE_OUTPUT_CONFIG") }

    override fun setOutputType(slot: Int, kind: Int) = set("setOutputType", "$slot, $kind") {
        if (slot !in 0 until 4 || kind !in 0..1) refuse(DspiConstants.PIN_CONFIG_INVALID_PARAM)
        it.copy(outputTypes = it.outputTypes.with(slot, kind))
    }

    override fun setI2sBckPin(role: Int, pin: Int) = set("setI2sBckPin", "$role, $pin") {
        pinCheck(pin)
        if (role == 0) it.copy(i2sBckPin = pin) else it.copy(i2sBckPinSlave = pin)
    }

    override fun setI2sClockPinMode(mode: Int) = set("setI2sClockPinMode", "$mode") { it.copy(i2sClockPinMode = mode) }

    override fun setMckEnabled(enabled: Boolean) = set("setMckEnabled", "$enabled") { it.copy(mckEnabled = enabled) }

    override fun setMckPin(pin: Int) = set("setMckPin", "$pin") {
        pinCheck(pin)
        it.copy(mckPin = pin)
    }

    override fun setMckMultiplier(mult: Int) = set("setMckMultiplier", "$mult") { it.copy(mckMultiplier = mult) }

    override fun setAdatOutEnabled(enabled: Boolean) = set("setAdatOutEnabled", "$enabled") {
        if (!rp2350()) refuse(DspiConstants.PIN_CONFIG_INVALID_PARAM)
        it.copy(adatOutEnabled = enabled)
    }

    override fun setAdatOutPin(pin: Int) = set("setAdatOutPin", "$pin") {
        pinCheck(pin)
        it.copy(adatOutPin = pin)
    }

    override fun setSpdifRxPin(index: Int, pin: Int) = set("setSpdifRxPin", "$index, $pin") {
        if (index !in 0 until 4) refuse(DspiConstants.PIN_CONFIG_INVALID_PARAM)
        pinCheck(pin)
        it.copy(
            spdifRxPins = it.spdifRxPins.with(
                index,
                if (pin ==
                    DspiConstants.PIN_RESET_TO_DEFAULT
                ) {
                    factory.spdifRxPins[index]
                } else {
                    pin
                }
            )
        )
    }

    override fun setSpdifInputEnabled(index: Int, enabled: Boolean) = set("setSpdifInputEnabled", "$index, $enabled") {
        if (index !in 1 until 4) refuse(DspiConstants.PIN_CONFIG_INVALID_PARAM)
        val bit = 1 shl index
        it.copy(spdifInputsEnabled = if (enabled) it.spdifInputsEnabled or bit else it.spdifInputsEnabled and bit.inv())
    }

    override fun setI2sRxPin(pair: Int, pin: Int) = set("setI2sRxPin", "$pair, $pin") {
        if (pair !in 0 until 4) refuse(DspiConstants.PIN_CONFIG_INVALID_PARAM)
        pinCheck(pin)
        it.copy(i2sRxPins = it.i2sRxPins.with(pair, if (pin == DspiConstants.PIN_RESET_TO_DEFAULT) factory.i2sRxPins[pair] else pin))
    }

    override fun setI2sInputChannels(channels: Int) = set("setI2sInputChannels", "$channels") {
        if (channels !in setOf(2, 4, 6, 8)) refuse(DspiConstants.PIN_CONFIG_INVALID_PARAM)
        it.copy(i2sInputChannels = channels)
    }

    override fun setAdatInputEnabled(enabled: Boolean) = set("setAdatInputEnabled", "$enabled") {
        if (!rp2350()) refuse(DspiConstants.PIN_CONFIG_INVALID_PARAM)
        it.copy(adatInputEnabled = enabled)
    }

    override fun setAdatInputPin(pin: Int) = set("setAdatInputPin", "$pin") {
        if (pin != 0xFF) pinCheck(pin)
        it.copy(adatInputPin = pin)
    }

    override fun setAdatInputClockMode(mode: Int) = set("setAdatInputClockMode", "$mode") { it.copy(adatInputClockMode = mode) }

    override fun setInputRate(index: Int) = set("setInputRate", "$index") { it.copy(i2sInputRate = if (index > 2) 1 else index) }

    override fun fetchInputRate(): Long = op("fetchInputRate") {
        when (s.i2sInputRate) {
            0 -> 44_100L
            2 -> 96_000L
            else -> 48_000L
        }
    }

    override fun setI2sClockMode(mode: Int) = set("setI2sClockMode", "$mode") { it.copy(i2sClockMode = mode.coerceAtMost(1)) }

    override fun fetchInputLock(which: Int): InputLockStatus = op("fetchInputLock", "$which") {
        val mode = if (which == 0) s.i2sClockMode else s.adatInputClockMode
        InputLockStatus(state = 0, clockMode = mode, detectedRate = 0, measuredHz = 0, rateOk = false, lockCount = 0, lossCount = 0)
    }

    override fun fetchAdatOutStatus(): AdatOutStatus = op("fetchAdatOutStatus") {
        AdatOutStatus(
            enabled = s.adatOutEnabled,
            active = s.adatOutEnabled,
            pin = s.adatOutPin,
            rateOk = true,
            resyncCount = 0,
            slipCount = 0
        )
    }

    override fun setDacMute(enabled: Boolean, activeLow: Boolean, pin: Int, holdMs: Int, releaseMs: Int) =
        set("setDacMute", "$enabled, $activeLow, $pin, $holdMs, $releaseMs") {
            // The device keeps a valid config only (applied in its main loop).
            if (!it.dacMuteSupported || (enabled && pin !in validPins)) {
                it
            } else {
                it.copy(
                    dacMuteEnabled = enabled,
                    dacMuteActiveLow = activeLow,
                    dacMutePin = pin,
                    dacMuteHoldMs = holdMs,
                    dacMuteReleaseMs = releaseMs
                )
            }
        }

    override fun fetchDacMute() = op("fetchDacMute") { state = s.copy(dacMuteSupported = true) }

    override fun testDacMute() = op("testDacMute") { if (!s.dacMuteEnabled) refuse(3) }

    override fun setLgSoundSync(enabled: Boolean) = set("setLgSoundSync", "$enabled") { it.copy(lgSoundSyncEnabled = enabled) }

    override fun fetchLgStatus(): LgStatus = op("fetchLgStatus") {
        LgStatus(enabled = s.lgSoundSyncEnabled, present = false, volume = 0xFF, muted = false)
    }

    override fun fetchCtrlIfaces() = op("fetchCtrlIfaces") {
        if (!s.ctrlIfaceSupported) throw DspiException.Unsupported()
    }

    override fun setUart(config: UartConfig) = set("setUart", "$config") {
        val ok = !config.enabled || (config.txPin in validPins && config.rxPin in validPins && config.txPin != config.rxPin)
        if (ok) {
            it.copy(uart = config, ctrlStatus = it.ctrlStatus.copy(uartLastStatus = 0, uartLive = config.enabled))
        } else {
            it.copy(ctrlStatus = it.ctrlStatus.copy(uartLastStatus = DspiConstants.PIN_CONFIG_INVALID_PIN))
        }
    }

    override fun setI2c(config: I2cConfig) = set("setI2c", "$config") {
        val ok = !config.enabled || (config.sdaPin in validPins && config.sclPin in validPins && config.sdaPin != config.sclPin)
        if (ok) {
            it.copy(i2c = config, ctrlStatus = it.ctrlStatus.copy(i2cLastStatus = 0, i2cLive = config.enabled))
        } else {
            it.copy(ctrlStatus = it.ctrlStatus.copy(i2cLastStatus = DspiConstants.PIN_CONFIG_INVALID_PIN))
        }
    }

    // ═══ Names / presets / flash ═════════════════════════════════════════

    override fun setChannelName(ch: Int, name: String) = set("setChannelName", "$ch, $name") {
        if (ch !in 0 until MAX_CHANNELS) bad("channel")
        it.copy(channelNames = it.channelNames.with(ch, fitName(name)))
    }

    override fun getChannelName(ch: Int): String = op("getChannelName", "$ch") { s.channelNames.getOrElse(ch) { bad("channel") } }

    private fun checkSlot(slot: Int) {
        if (slot !in 0 until MAX_PRESETS) refuse(DspiConstants.PRESET_ERR_INVALID_SLOT)
    }

    /** The live state restored from a preset, keeping what presets don't hold. */
    private fun restorePreset(p: DspState, slot: Int): DspState {
        val live = s
        var n =
            p.copy(
                platformId = live.platformId, numChannels = live.numChannels, numInputChannels = live.numInputChannels,
                numOutputChannels = live.numOutputChannels, formatVersion = live.formatVersion, fwMajor = live.fwMajor,
                fwMinor = live.fwMinor, fwPatch = live.fwPatch, fwBeta = live.fwBeta, compat = live.compat,
                presetOccupied = live.presetOccupied, presetNames = live.presetNames, activePresetSlot = slot,
                presetStartupMode = live.presetStartupMode, presetDefaultSlot = live.presetDefaultSlot,
                masterVolumeMode = live.masterVolumeMode, outputConfigMode = live.outputConfigMode,
                userVolumeDb = live.userVolumeDb, userMute = live.userMute, bulkValid = live.bulkValid, bulkRaw = live.bulkRaw
            )
        if (live.masterVolumeMode == 0) n = n.copy(masterVolumeDb = live.masterVolumeDb)
        if (live.outputConfigMode == 0) {
            n =
                n.copy(
                    outputPins = live.outputPins, outputTypes = live.outputTypes, i2sBckPin = live.i2sBckPin,
                    i2sBckPinSlave = live.i2sBckPinSlave, i2sClockPinMode = live.i2sClockPinMode, mckEnabled = live.mckEnabled,
                    mckPin = live.mckPin, mckMultiplier = live.mckMultiplier, adatOutEnabled = live.adatOutEnabled,
                    adatOutPin = live.adatOutPin, spdifRxPins = live.spdifRxPins, i2sRxPins = live.i2sRxPins
                )
        }
        return n.copy(core1Mode = core1ModeOf(n))
    }

    private fun saveSlot(slot: Int) {
        checkSlot(slot)
        presets[slot] = s
        state = s.copy(activePresetSlot = slot, presetOccupied = s.presetOccupied or (1 shl slot))
        log("PRESET_SAVE $slot")
    }

    override fun savePreset(slot: Int) = op("savePreset", "$slot") { saveSlot(slot) }

    override fun loadPreset(slot: Int) = op("loadPreset", "$slot") {
        checkSlot(slot)
        val p = presets[slot] ?: refuse(DspiConstants.PRESET_ERR_SLOT_EMPTY)
        state = restorePreset(p, slot)
        log("PRESET_LOAD $slot")
    }

    override fun copyPreset(source: Int, dest: Int) = op("copyPreset", "$source, $dest") {
        checkSlot(source)
        saveSlot(dest)
        saveSlot(source)
    }

    override fun deletePreset(slot: Int) = op("deletePreset", "$slot") {
        checkSlot(slot)
        if (presets[slot] == null) refuse(DspiConstants.PRESET_ERR_SLOT_EMPTY)
        presets[slot] = null
        val live = s.copy(presetOccupied = s.presetOccupied and (1 shl slot).inv(), presetNames = s.presetNames.with(slot, ""))
        state = live
        // Deleting the active slot applies factory defaults on the device.
        if (live.activePresetSlot == slot) state = restorePreset(factory, slot)
        log("PRESET_DELETE $slot")
    }

    override fun setPresetName(slot: Int, name: String) = set("setPresetName", "$slot, $name") {
        if (slot !in 0 until MAX_PRESETS) bad("slot")
        it.copy(presetNames = it.presetNames.with(slot, fitName(name)))
    }

    override fun getPresetName(slot: Int): String = op("getPresetName", "$slot") {
        checkSlot(slot)
        if (presets[slot] == null) throw DspiException.Transfer("stall")
        s.presetNames[slot]
    }

    override fun getPresetDirectory(): PresetDirectory = op("getPresetDirectory") {
        PresetDirectory(
            s.presetOccupied,
            s.presetStartupMode,
            s.presetDefaultSlot,
            s.activePresetSlot,
            s.outputConfigMode,
            s.masterVolumeMode
        )
    }

    override fun setPresetStartup(mode: Int, defaultSlot: Int) = set("setPresetStartup", "$mode, $defaultSlot") {
        it.copy(presetStartupMode = mode, presetDefaultSlot = defaultSlot)
    }

    override fun saveParams() = op("saveParams") { saveSlot(s.activePresetSlot) }

    override fun loadParams() = op("loadParams") {
        val slot = s.activePresetSlot
        val p = presets[slot] ?: refuse(DspiConstants.FLASH_ERR_WRITE)
        state = restorePreset(p, slot)
    }

    override fun factoryReset() = op("factoryReset") {
        state = restorePreset(factory, s.activePresetSlot)
        log("FACTORY_RESET")
    }

    override fun enterBootloader() {
        op("enterBootloader") { connected = false }
        deviceEvents?.onDeparted(serial)
    }

    // ═══ Unsaved changes ═════════════════════════════════════════════════

    override fun captureBaseline() = op("captureBaseline", device = false) { baseline = s }

    override fun presetDirty(): Boolean = op("presetDirty", device = false) { FakePresetDiff.diff(baseline, s).isNotEmpty() }

    override fun presetChanges(): List<String> = op("presetChanges", device = false) { FakePresetDiff.diff(baseline, s) }

    // ═══ Hotplug / notifications / monitor ═══════════════════════════════

    override fun setHotplugCallback(listener: DeviceEvents?) {
        deviceEvents = listener
    }

    override fun pollHotplug() {}

    override fun setNotifyListener(listener: NotifyListener?) {
        notifyListener = listener
    }

    override fun processNotifications(): NotifyResult = op("processNotifications", device = false) {
        pendingNotify.also { pendingNotify = NotifyResult(0, 0) }
    }

    override fun monitorRead(afterId: Long): MonitorBatch = op("monitorRead", "$afterId", device = false) {
        val e = monitor.filter { it.id > afterId }
        MonitorBatch(lastId = e.lastOrNull()?.id ?: afterId, entries = e)
    }

    override fun monitorClear() = op("monitorClear", device = false) { monitor.clear() }

    // ═══ Spectrum analyser ═══════════════════════════════════════════════

    private var rtaStarted = false
    private var rtaRequest = RtaRequest(0, 0, false, 10, 300, 12)
    private var rtaVersion = 0L
    private var rtaTelemetry = 0L
    private var rtaTimer: Timer? = null

    override fun rtaSetListener(listener: RtaListener?) {
        rtaListener = listener
    }

    override fun rtaStart() {
        op("rtaStart", device = false) { rtaStarted = connected && s.compat == DspiConstants.COMPAT_OK }
        rtaListener?.onFrame()
    }

    override fun rtaSetRequest(request: RtaRequest) {
        val running =
            op("rtaSetRequest", "$request", device = false) {
                rtaRequest = request
                rtaTelemetry++
                request.mask != 0
            }
        if (animate) {
            synchronized(this) {
                rtaTimer?.cancel()
                rtaTimer = if (running) fixedRateTimer("fake-rta", daemon = true, period = 100L) { emitRtaFrame() } else null
            }
        }
        rtaListener?.onFrame()
    }

    /** One new analyser frame: bump the version and wake the listener. */
    fun emitRtaFrame() {
        synchronized(this) { if (rtaRequest.mask != 0) rtaVersion++ }
        rtaListener?.onFrame()
    }

    override fun rtaGetCaps(): RtaCapsInfo = op("rtaGetCaps", device = false) {
        RtaCapsInfo(
            supported = rtaStarted,
            inputChannels = if (rtaStarted) s.numInputChannels else 0,
            outputChannels = if (rtaStarted) s.numOutputChannels else 0,
            orderMin = 8, orderMax = 12, orderDefault = 10, bassBands = 6, maxBands = RTA_BANDS.size,
            dynamicRangeDb = 100, bassDynamicRangeDb = 90,
            centres = RTA_BANDS + List(DspiConstants.RTA_MAX_BANDS - RTA_BANDS.size) { 0 }
        )
    }

    override fun rtaGetSnapshot(): RtaSnapshot = op("rtaGetSnapshot", device = false) {
        val r = rtaRequest
        val frames =
            List(DspiConstants.RTA_MAX_CHANNELS) { ch ->
                val on = (r.mask shr ch) and 1 == 1
                val avg =
                    List(DspiConstants.RTA_MAX_BANDS) { b ->
                        if (!on || b >= RTA_BANDS.size) {
                            DspiConstants.RTA_FLOOR_DB
                        } else {
                            // Pink-ish: a tilt around −30 dBFS with a little motion
                            (-30.0 - 10 * log10(RTA_BANDS[b] / 1000.0) + 3 * sin(rtaVersion * 0.4 + b + ch)).toFloat()
                        }
                    }
                RtaChannelFrame(
                    channel = ch,
                    nBands = if (on) RTA_BANDS.size else 0,
                    silent = !on,
                    avgDb = avg,
                    peakDb = avg.map {
                        it + 4f
                    }
                )
            }
        val count = Integer.bitCount(r.mask)
        RtaSnapshot(
            version = rtaVersion, telemetryVersion = rtaTelemetry, tap = r.tap, count = count, frames = frames,
            populated = r.mask.toLong(), binsCount = if (r.wantsBins && count == 1) 256 else 0,
            binsChannel = Integer.numberOfTrailingZeros(r.mask).coerceAtMost(15), binsSampleRate = 48_000,
            binsDb = List(DspiConstants.RTA_MAX_BINS) { b -> (-40.0 - 15 * log10(1.0 + b)).toFloat() },
            running = r.mask != 0, liveCount = count, liveMask = r.mask, framesPerS = if (r.mask != 0) 10 else 0,
            busyUsPerS = 42_000, lastFrameUs = 900, bassBusyUsPerS = 8_000, sampleRate = 48_000, firstBand = 0,
            fftOrder = r.fftOrder, configRejected = false, refreshIntervalS = 0.1f
        )
    }

    // ═══ Signal generator ════════════════════════════════════════════════

    private var siggenConfig = SiggenConfig(0, 0x0003, 0, 0, -20f, 0, 0, 0, listOf(1000f, 0f, 0f, 0f))
    private var siggenState = 0
    private var siggenStop = 0

    override fun siggenFetchCaps(): SiggenCaps = op("siggenFetchCaps") {
        SiggenCaps(
            supported = true,
            typeCount = SIGGEN_TYPES.size,
            outputChannels = s.numOutputChannels,
            multitoneMax = 16,
            validMask = (1 shl s.numOutputChannels) - 1,
            types = SIGGEN_TYPES
        )
    }

    override fun siggenGetConfig(): SiggenConfig = op("siggenGetConfig") { siggenConfig }

    override fun siggenSetConfig(config: SiggenConfig) = op("siggenSetConfig", "$config") {
        // The device ACKs a refused config and keeps the old one.
        if (config.channelMask != 0 && config.signalType in 0 until DspiConstants.SIGGEN_TYPE_COUNT) siggenConfig = config
    }

    override fun siggenControl(action: Int) {
        op("siggenControl", "$action") {
            siggenState = if (action == DspiConstants.SIGGEN_CTL_START) 1 else 0
            if (action != DspiConstants.SIGGEN_CTL_START) siggenStop = 1
            log("SIGGEN_STATE $siggenState")
            pendingNotify = NotifyResult(pendingNotify.flags or DspiConstants.NOTIFY_SIGGEN.toLong(), pendingNotify.filterChannels)
        }
        notifyListener?.onNotifications()
    }

    override fun siggenGetStatus(): SiggenStatus = op("siggenGetStatus") {
        val c = siggenConfig
        SiggenStatus(
            state = siggenState,
            signalType = c.signalType,
            activeChannel = if (siggenState != 0) Integer.numberOfTrailingZeros(c.channelMask) else 0xFF,
            elapsedMs = if (siggenState != 0) tick * 60L else 0,
            cyclesDone = 0,
            stopReason = siggenStop,
            currentFreq = c.p.firstOrNull() ?: 0f
        )
    }

    // ═══ Statistics ══════════════════════════════════════════════════════

    override fun fetchStat(which: Int): Long = op("fetchStat", "$which") {
        when (which) {
            DspiConstants.STAT_CLOCK_HZ -> if (rp2350()) 150_000_000L else 133_000_000L
            DspiConstants.STAT_CORE_MV -> 1100L
            DspiConstants.STAT_SAMPLE_RATE -> 48_000L
            DspiConstants.STAT_TEMPERATURE -> 3125L
            else -> 0L
        }
    }

    private var bufferSeq = 0

    override fun fetchBufferStats(): BufferStats = op("fetchBufferStats") {
        bufferSeq++
        val fill = 50 + (bufferSeq % 7) - 3
        BufferStats(
            numSpdif = 4,
            pdmActive = s.outputEnabled.getOrElse(s.numOutputChannels - 1) { false },
            streaming = true,
            sequence = bufferSeq,
            spdif = List(4) { SpdifBufferStats(free = 2, prepared = 2, playing = 4, fillPct = fill, minPct = 44, maxPct = 56) },
            pdm = PdmBufferStats(50, 45, 55, 50, 40, 60)
        )
    }

    override fun resetBufferStats() = op("resetBufferStats") {}

    override fun fetchSpdifRxStatus(): SpdifRxStatus = op("fetchSpdifRxStatus") {
        SpdifRxStatus(
            state = 0, inputSource = s.inputSource, lockCount = 0, lossCount = 0, sampleRate = 0, parityErrors = 0,
            fifoFillPct = 0, libState = 0, stableCallbacks = 0, lostCallbacks = 0
        )
    }

    override fun fetchSpdifRxChannelStatus(): List<Int> = op("fetchSpdifRxChannelStatus") { List(24) { 0 } }

    override fun fetchSpdifRxPin(index: Int): Int = op("fetchSpdifRxPin", "$index") {
        s.spdifRxPins.getOrElse(index) { throw DspiException.Transfer("stall") }
    }

    // ═══ Configuration / filter files ════════════════════════════════════

    override fun configExport(name: String, appVersion: String, links: Int): String = op("configExport", name, device = false) {
        FakeFiles.exportConfig(s, name, appVersion, links)
    }

    override fun configImport(text: String, options: Long): String = op("configImport", "$options", device = false) {
        val (ns, result) = FakeFiles.importConfig(s, text, options)
        state = ns
        result
    }

    override fun filtersExport(inputs: Int): String = op("filtersExport", "$inputs", device = false) { FakeFiles.exportFilters(s, inputs) }

    override fun filtersInspect(text: String, inputs: Int): String = op("filtersInspect", "$inputs", device = false) {
        FakeFiles.inspectFilters(s, text, inputs)
    }

    override fun filtersImport(text: String, wires: List<Int>): String = op("filtersImport", "$wires", device = false) {
        val (ns, result) = FakeFiles.importFilters(s, text, wires)
        state = ns
        result
    }

    // ═══ Control surfaces ════════════════════════════════════════════════

    private var csDirty = false
    private var csFetched = false

    override fun csFetch() = op("csFetch") { csFetched = true }

    override fun csSnapshot(): String = op("csSnapshot", device = false) {
        if (!csFetched) {
            ""
        } else {
            MiniJson.write(
                linkedMapOf(
                    "supported" to true, "capsVersion" to 21, "maxBindings" to 16, "maxGroups" to 8, "maxMacros" to 8, "maxSteps" to 8,
                    "types" to
                        listOf(
                            "None", "Button", "Encoder", "Potentiometer", "LED", "IR", "Display", "Relay", "PWM", "Toggle", "Aux", "Aux PWM"
                        ),
                    "bindings" to emptyList<Any>(),
                    "status" to linkedMapOf("dirty" to csDirty, "active" to 0),
                    "ext" to linkedMapOf("macroRunning" to 0xFF),
                    "display" to linkedMapOf("status" to linkedMapOf("link" to false)),
                    "auxState" to List(16) { 0 },
                    "auxLevel" to List(16) { 0 }
                )
            )
        }
    }

    override fun csApply(op: String): String = this.op("csApply", op) {
        val o = MiniJson.parseObject(op)
        when (o?.str("op")) {
            null -> """{"ok":false,"error":"missing op"}"""
            "save", "revert" -> {
                csDirty = false
                """{"ok":true,"status":0}"""
            }
            "displayStatus", "refreshStatus", "auxRead", "learnCancel", "macroCancel", "learnArm" -> """{"ok":true}"""
            "learnRead" -> """{"ok":true,"state":1,"protocol":0,"code":0}"""
            "macroFire" -> """{"ok":true,"status":0}"""
            else -> {
                csDirty = true
                """{"ok":true,"status":0}"""
            }
        }
    }

    override fun csPinUses(): List<PinUse> = emptyList()

    // ═══ Stateless ═══════════════════════════════════════════════════════

    override fun computeResponse(filters: List<FilterParams>, freq: Float): Float = DspMath.responseAt(freq, filters)

    override fun computeMagnitudeCurve(filters: List<FilterParams>): List<Double> {
        calls += "computeMagnitudeCurve"
        return DspMath.magnitudeCurve(filters)
    }

    override fun computePhaseCurve(filters: List<FilterParams>, unwrap: Boolean): List<Double> = DspMath.phaseCurve(filters, unwrap)

    override fun curveFreq(i: Long): Double = DspMath.curveFreq(i.toInt())

    override fun configInspect(text: String): String = FakeFiles.inspectConfig(text)

    override fun bootloaderCounts(): BootloaderCounts = BootloaderCounts(0, 0)

    override fun coreInfo(): CoreInfo = CoreInfo(
        ffiVersion = "fake",
        wireFormatVersion = DspiConstants.WIRE_FORMAT_VERSION,
        minWireFormatVersion = DspiConstants.MIN_WIRE_FORMAT_VERSION,
        bulkParamsSize = DspiConstants.BULK_PARAMS_SIZE.toLong(),
        magnitudePoints = MAGNITUDE_POINTS.toLong()
    )

    override fun close() {
        synchronized(this) {
            rtaTimer?.cancel()
            rtaTimer = null
        }
    }

    companion object {
        const val MAGNITUDE_POINTS = 201
        const val DEMO_SERIAL = "DEMO0000E6614103"

        /** ISO third-octave centres 20 Hz .. 20 kHz (31 bands). */
        val RTA_BANDS: List<Int> =
            listOf(
                20, 25, 31, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800, 1000, 1250, 1600, 2000,
                2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000, 20000
            )

        private fun p(semantic: Int, min: Float, max: Float, def: Float) = SiggenParam(semantic, min, max, def)

        private val NONE = p(0, 0f, 0f, 0f)

        private fun type(id: Int, name: String, timing: Int, vararg ps: SiggenParam) = SiggenTypeInfo(
            id,
            name,
            timing,
            ps.toList() + List(4 - ps.size) { NONE }
        )

        /** The generator's types, as SiggenController.cpp's built-in table describes them. */
        val SIGGEN_TYPES: List<SiggenTypeInfo> =
            listOf(
                type(0, "Sine", 0, p(1, 1f, 30000f, 1000f)),
                type(1, "Square", 0, p(1, 1f, 30000f, 100f)),
                type(2, "White Noise", 0),
                type(3, "Pink Noise", 0),
                type(4, "Log Sweep", 1, p(1, 1f, 30000f, 20f), p(1, 1f, 30000f, 20000f)),
                type(5, "Linear Sweep", 1, p(1, 1f, 30000f, 20f), p(1, 1f, 30000f, 20000f)),
                type(6, "Stepped Sweep", 1, p(1, 1f, 30000f, 20f), p(1, 1f, 30000f, 20000f), p(4, 1f, 24f, 3f), p(2, 20f, 10000f, 250f)),
                type(7, "Impulse", 2, p(2, 10f, 60000f, 500f)),
                type(8, "Clicks", 2, p(2, 10f, 60000f, 500f)),
                type(9, "Polarity", 2, p(2, 1f, 100f, 5f), p(2, 10f, 60000f, 500f)),
                type(10, "Tone Burst", 2, p(1, 1f, 30000f, 1000f), p(3, 1f, 1000f, 8f), p(3, 0f, 1000f, 8f), p(3, 0f, 100f, 2f)),
                type(11, "Two-Tone IMD", 0, p(1, 1f, 30000f, 60f), p(1, 1f, 30000f, 7000f), p(5, 0.1f, 10f, 4f)),
                type(12, "Multitone", 0, p(4, 2f, 16f, 10f), p(1, 1f, 30000f, 20f), p(1, 1f, 30000f, 20000f)),
                type(13, "Inter-Sample Peaks", 0, p(6, 0f, 1f, 0f)),
                type(14, "Channel ID", 2, p(2, 30f, 1000f, 120f))
            )

        /** A fake seeded from FFI's `dspstate_v32.json` text. */
        fun fromFixture(json: String, animate: Boolean = false): FakeDspiCore = FakeDspiCore(DspStateJson.parse(json), animate)

        /**
         * The V32 fixture if it is on the classpath (`/dspstate_v32.json` or
         * `/fixtures/dspstate_v32.json`), else [demoDspState].
         */
        fun seeded(animate: Boolean = false): FakeDspiCore = seeded(animate, FIXTURE_PATHS)

        /** [seeded] over explicit classpath [paths] (tests). */
        fun seeded(animate: Boolean, paths: List<String>): FakeDspiCore {
            val text = fixtureText(paths)
            return if (text != null) fromFixture(text, animate) else FakeDspiCore(demoDspState(), animate)
        }

        /**
         * Where FFI's V32 capture sits on the classpath: fake-core's resources
         * include the repo-root `fixtures/` (decision D10), so it is `/dspstate_v32.json`.
         */
        val FIXTURE_PATHS = listOf("/dspstate_v32.json", "/fixtures/dspstate_v32.json")

        /** The first of [paths] on the classpath, as text; null if none is there. */
        fun fixtureText(paths: List<String> = FIXTURE_PATHS): String? = paths.firstNotNullOfOrNull { path ->
            FakeDspiCore::class.java.getResourceAsStream(path)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }

        /** Demo mode: the seeded device with an animated analyser. */
        fun demo(): FakeDspiCore = seeded(animate = true)

        /** `DspState::default()` values with the device's identity: what a factory reset leaves. */
        fun factoryOf(s: DspState): DspState = defaultDspState().copy(
            platformId = s.platformId, numChannels = s.numChannels, numInputChannels = s.numInputChannels,
            numOutputChannels = s.numOutputChannels, formatVersion = s.formatVersion, fwMajor = s.fwMajor, fwMinor = s.fwMinor,
            fwPatch = s.fwPatch, fwBeta = s.fwBeta, compat = s.compat, numPinOutputs = s.numPinOutputs, outputPins = s.outputPins,
            dacMuteSupported = s.dacMuteSupported, ctrlIfaceSupported = s.ctrlIfaceSupported, bulkValid = s.bulkValid, bulkRaw = s.bulkRaw,
            outputEnabled = List(MAX_OUTPUTS) { it < 2 },
            matrixRouting = List(MAX_INPUTS) { i -> List(MAX_OUTPUTS) { o -> i < 2 && o == i } }
        )

        /** At most 31 UTF-8 bytes (CHANNEL_NAME_LEN with its NUL), never splitting a character. */
        fun fitName(name: String): String {
            val sb = StringBuilder()
            var bytes = 0
            for (cp in name.codePoints().toArray()) {
                val n = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
                if (bytes + n > DspiConstants.CHANNEL_NAME_LEN - 1) break
                bytes += n
                sb.appendCodePoint(cp)
            }
            return sb.toString()
        }
    }
}

internal fun <T> List<T>.with(i: Int, v: T): List<T> = toMutableList().also { it[i] = v }
