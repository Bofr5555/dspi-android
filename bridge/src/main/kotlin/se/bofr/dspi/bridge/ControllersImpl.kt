// Ports of DSPi-Console-Linux:qt/src/{RtaController,SiggenController,
// StatsController,CsController,MonitorModel,ConfigFiles}.cpp @ 4683a2c.
// Core calls run on the bridge's FFI dispatcher; flows update on main.
package se.bofr.dspi.bridge

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import se.bofr.dspi.core.BufferStats
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.core.DspiException
import se.bofr.dspi.core.RtaCapsInfo
import se.bofr.dspi.core.RtaListener
import se.bofr.dspi.core.RtaRequest
import se.bofr.dspi.core.RtaSnapshot
import se.bofr.dspi.core.SiggenCaps
import se.bofr.dspi.core.SiggenConfig
import se.bofr.dspi.core.SiggenParam
import se.bofr.dspi.core.SiggenStatus
import se.bofr.dspi.core.SiggenTypeInfo

// ═══════════════════════════════════════════════════════════════════════
// RtaController
// ═══════════════════════════════════════════════════════════════════════

/**
 * The spectrum analyser, shared by every view that shows it. The device does
 * the analysis; the core polls it on its own thread. Views subscribe ([update])
 * while on screen with the tap and channels they show; the newest
 * subscription picks the tap, and the channels of every subscription at that
 * tap are polled together. [snapshot] changes only when the picture did.
 */
class RtaControllerImpl internal constructor(private val bridge: DspiBridgeImpl) : RtaController {
    private val _caps = MutableStateFlow<RtaCapsInfo?>(null)
    private val _snapshot = MutableStateFlow<RtaSnapshot?>(null)
    override val caps: StateFlow<RtaCapsInfo?> = _caps.asStateFlow()
    override val snapshot: StateFlow<RtaSnapshot?> = _snapshot.asStateFlow()

    private data class Sub(val tap: Int, val mask: Int, val bins: Boolean, val seq: Long)

    private val subs = LinkedHashMap<Int, Sub>()
    private var seq = 0L
    private var sent: RtaRequest? = null
    private val wakePending = AtomicBoolean(false)

    override var fftOrder: Int = 10
        set(v) {
            if (v != field) {
                field = v
                pushRequest()
            }
        }
    override var avgMs: Int = 300
        set(v) {
            if (v != field) {
                field = v
                pushRequest()
            }
        }
    override var peakDecay: Int = 12
        set(v) {
            if (v != field) {
                field = v
                pushRequest()
            }
        }

    init {
        // Worker thread: hand over to the main thread once per batch
        bridge.core.rtaSetListener(
            RtaListener {
                if (wakePending.compareAndSet(false, true)) bridge.post { onWake() }
            }
        )
        // A reconnect may change which channels exist
        bridge.deviceChangedHooks += { pushRequest() }
        // The probe may already have finished and woken nobody: read what it found
        bridge.post { onWake() }
    }

    internal fun close() {
        bridge.core.rtaSetListener(null)
    }

    private fun onWake() {
        wakePending.set(false)
        bridge.scope.launch(bridge.main) {
            val (caps, snap) = bridge.onFfi { bridge.core.rtaGetCaps() to bridge.core.rtaGetSnapshot() }
            if (caps != _caps.value) {
                _caps.value = caps
                pushRequest()
            }
            val old = _snapshot.value
            if (old == null || snap.version != old.version || snap.telemetryVersion != old.telemetryVersion) _snapshot.value = snap
        }
    }

    /** A view's interest (Qt subscribe + update): an unchanged request keeps its place. */
    override fun update(id: Int, tap: Int, mask: Int, wantsBins: Boolean) {
        val old = subs[id]
        if (old != null && old.tap == tap && old.mask == mask && old.bins == wantsBins) return
        subs[id] = Sub(tap, mask, wantsBins, ++seq)
        pushRequest()
    }

    override fun release(id: Int) {
        if (subs.remove(id) != null) pushRequest()
    }

    // The newest subscription picks the tap; every subscription at that tap adds
    // its channels, except that one drawing bins gets its own channel alone (the
    // device keeps bins for the last channel it transformed).
    private fun pushRequest() {
        val c = _caps.value
        val primary = subs.values.filter { it.mask != 0 }.maxByOrNull { it.seq }
        var r = RtaRequest(tap = 0, mask = 0, wantsBins = false, fftOrder = 0, avgMs = 0, peakDecayDbS = 0)
        if (primary != null && c != null && c.supported) {
            val mask = if (primary.bins) primary.mask else subs.values.filter { it.tap == primary.tap }.fold(0) { m, s -> m or s.mask }
            val bins = Integer.bitCount(mask) == 1 && subs.values.any { it.tap == primary.tap && it.bins && it.mask == mask }
            r =
                RtaRequest(
                    tap = primary.tap,
                    mask = mask and 0xFFFF,
                    wantsBins = bins,
                    fftOrder = fftOrder.coerceIn(c.orderMin, maxOf(c.orderMin, c.orderMax)),
                    avgMs = avgMs.coerceIn(0, 10000),
                    peakDecayDbS = peakDecay.coerceIn(0, 100)
                )
        }
        if (r == sent) return
        sent = r
        bridge.scope.launch(bridge.ffi) { runCatching { bridge.core.rtaSetRequest(r) } }
    }

    override fun appChannel(tap: Int, index: Int): Int {
        if (index < 0) return -1
        return if (tap == DspiConstants.RTA_TAP_INPUT) bridge.inputAppId(index) else index + 2
    }

    override fun tapOf(appChannel: Int): Int {
        if (appChannel < 0) return -1
        return if (bridge.isInputChannel(appChannel)) DspiConstants.RTA_TAP_INPUT else DspiConstants.RTA_TAP_OUTPUT
    }

    override fun tapIndex(appChannel: Int): Int {
        for (i in 0 until 16) if (bridge.inputAppId(i) == appChannel) return i
        return if (appChannel in 2..10) appChannel - 2 else -1
    }

    /** Channels that can be analysed at a tap now (live inputs, enabled outputs). */
    override fun availableChannels(tap: Int): List<Int> {
        val c = _caps.value ?: return emptyList()
        if (!c.supported) return emptyList()
        return if (tap == DspiConstants.RTA_TAP_INPUT) {
            (0 until minOf(bridge.liveInputCount(), c.inputChannels)).toList()
        } else {
            (0 until minOf(bridge.numOutputChannels, c.outputChannels)).filter { bridge.outputEnabled(it) }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// SiggenController
// ═══════════════════════════════════════════════════════════════════════

/**
 * The device's signal generator: its caps, the draft signal being edited
 * (kept for the session, seeded from the device's applied config at
 * connect), and the generator's status. While a signal plays, edits to the
 * draft re-apply after a short pause; an unchanged draft is never re-sent,
 * since every send restarts the signal with a fade. The device ACKs a config
 * even when it refuses one, so starts are checked by reading back.
 */
class SiggenControllerImpl internal constructor(private val bridge: DspiBridgeImpl) : SiggenController {
    private val _caps = MutableStateFlow<SiggenCaps?>(null)
    private val _draft = MutableStateFlow<SiggenConfig?>(DEFAULT_DRAFT)
    private val _status = MutableStateFlow<SiggenStatus?>(null)
    override val caps: StateFlow<SiggenCaps?> = _caps.asStateFlow()
    override val draft: StateFlow<SiggenConfig?> = _draft.asStateFlow()
    override val status: StateFlow<SiggenStatus?> = _status.asStateFlow()

    private var d: SiggenConfig
        get() = _draft.value ?: DEFAULT_DRAFT
        set(v) {
            _draft.value = v
        }

    private var sent: SiggenConfig? = null
    private var serial = ""
    private var pollJob: Job? = null
    private var applyJob: Job? = null

    override var watching: Boolean = false
        set(w) {
            if (w == field) return
            field = w
            if (w) refreshStatus()
            updatePolling()
        }

    private val supported get() = _caps.value?.supported == true
    private val running get() = (_status.value?.state ?: 0) != 0

    init {
        bridge.deviceChangedHooks += { onDeviceChanged() }
        bridge.siggenHooks += { refreshStatus() }
    }

    internal fun close() {
        pollJob?.cancel()
        applyJob?.cancel()
    }

    // A new device (or none): read its generator once, adopt its applied signal
    private fun onDeviceChanged() {
        val usable = bridge.usable
        val s = if (usable) bridge.selectedSerial else ""
        if (s == serial) return
        serial = s
        applyJob?.cancel()
        _status.value = null
        sent = null
        _caps.value = null
        if (!usable) {
            updatePolling()
            return
        }
        bridge.scope.launch(bridge.main) {
            class Read(val caps: SiggenCaps?, val applied: SiggenConfig?, val status: SiggenStatus?)
            val r =
                bridge.onFfi {
                    val caps =
                        try {
                            bridge.get { bridge.core.siggenFetchCaps() }
                        } catch (e: DspiException) {
                            return@onFfi Read(null, null, null)
                        }
                    if (!caps.supported) return@onFfi Read(caps, null, null)
                    Read(
                        caps,
                        runCatching { bridge.get { bridge.core.siggenGetConfig() } }.getOrNull(),
                        runCatching { bridge.get { bridge.core.siggenGetStatus() } }.getOrNull()
                    )
                }
            if (r.caps == null) {
                // USB trouble, not a missing generator: try again on the next change
                serial = ""
                return@launch
            }
            _caps.value = r.caps
            if (r.caps.supported) {
                // The device's applied signal becomes the draft, unless it is an
                // Identify left behind (Channel ID on one output)
                val applied = r.applied
                d =
                    if (applied != null && applied.channelMask != 0 && applied.signalType != TYPE_CHANNEL_ID) {
                        applied
                    } else {
                        val m = d.channelMask and usableMask()
                        d.copy(channelMask = if (m == 0) 1 else m)
                    }
                _status.value = r.status
                if (running) sent = d
            }
            updatePolling()
        }
    }

    private fun refreshStatus() {
        if (!supported) return
        bridge.scope.launch(bridge.main) {
            val s = bridge.onFfi { runCatching { bridge.get { bridge.core.siggenGetStatus() } }.getOrNull() } ?: return@launch
            if (s != _status.value) _status.value = s
            updatePolling()
        }
    }

    // Fades, gaps and walk steps come only from polling
    private fun updatePolling() {
        val on = watching && running && supported
        if (on && pollJob?.isActive != true) {
            pollJob =
                bridge.scope.launch(bridge.main) {
                    while (isActive) {
                        delay(POLL_MS)
                        refreshStatus()
                    }
                }
        } else if (!on) {
            pollJob?.cancel()
            pollJob = null
        }
    }

    private fun usableMask(): Int {
        val c = _caps.value
        if (c != null && c.validMask != 0) return c.validMask
        val n = if (c != null && c.outputChannels != 0) c.outputChannels else bridge.numOutputChannels
        return (1 shl n.coerceIn(0, 16)) - 1
    }

    /** The device's descriptor, or the built-in one when it gave none. */
    fun typeInfo(type: Int): SiggenTypeInfo {
        val t = type.coerceIn(0, DspiConstants.SIGGEN_TYPE_COUNT - 1)
        val c = _caps.value
        if (c != null && c.supported && t < c.typeCount && c.types.getOrNull(t)?.id == t) return c.types[t]
        return FALLBACK[t]
    }

    /** What is sent: levels and parameters in range, unused parameters zero, inverted outputs a subset of the selected ones. */
    private fun normalised(c: SiggenConfig): SiggenConfig {
        val t = typeInfo(c.signalType)
        val level = if (c.levelDb.isFinite()) c.levelDb.coerceIn(-120f, 0f) else -20f
        val p =
            List(4) { k ->
                val dsc = t.params[k]
                if (dsc.semantic == UNUSED) {
                    0f
                } else {
                    var v = c.p.getOrElse(k) { dsc.def }.let { if (it.isFinite()) it else dsc.def }
                    v = maxOf(v, dsc.min)
                    if (dsc.max > 0) v = minOf(v, dsc.max)
                    v
                }
            }
        val mask = c.channelMask and usableMask()
        val dur = if (t.timing == SWEEP && c.durationMs == 0L) 1000L else c.durationMs // refused otherwise
        return c.copy(levelDb = level, p = p, channelMask = mask, invertMask = c.invertMask and mask, durationMs = dur)
    }

    override fun selectType(type: Int) {
        val t = typeInfo(type)
        d =
            d.copy(
                signalType = type.coerceIn(0, DspiConstants.SIGGEN_TYPE_COUNT - 1),
                p = t.params.map { if (it.semantic == UNUSED) 0f else it.def },
                repeat = 0,
                gapMs = 0,
                durationMs = if (t.timing == SWEEP) 5000 else 0
            )
        scheduleApply()
    }

    override fun setParam(index: Int, value: Double) {
        if (index !in 0..3 || d.p[index] == value.toFloat()) return
        d = d.copy(p = d.p.toMutableList().also { it[index] = value.toFloat() })
        scheduleApply()
    }

    override fun setFlag(flag: Int, on: Boolean) {
        val f = if (on) d.flags or flag else d.flags and flag.inv()
        if (f == d.flags) return
        d = d.copy(flags = f)
        scheduleApply()
    }

    override fun cycleOutput(output: Int) {
        if (output !in 0..15) return
        val bit = 1 shl output
        d =
            when {
                d.channelMask and bit == 0 -> d.copy(channelMask = d.channelMask or bit)
                d.invertMask and bit == 0 -> d.copy(invertMask = d.invertMask or bit)
                else -> d.copy(channelMask = d.channelMask and bit.inv(), invertMask = d.invertMask and bit.inv())
            }
        scheduleApply()
    }

    override fun selectAllOutputs() {
        if (d.channelMask == usableMask()) return
        d = d.copy(channelMask = usableMask())
        scheduleApply()
    }

    override fun clearOutputs() {
        if (d.channelMask == 0 && d.invertMask == 0) return
        d = d.copy(channelMask = 0, invertMask = 0)
        scheduleApply()
    }

    override fun setLevelDb(db: Float) = setDraft(d.copy(levelDb = db.coerceIn(-120f, 0f)))

    override fun setDurationMs(ms: Long) = setDraft(d.copy(durationMs = maxOf(0L, ms)))

    override fun setRepeat(count: Int) = setDraft(d.copy(repeat = count.coerceIn(0, 65535)))

    override fun setGapMs(ms: Int) = setDraft(d.copy(gapMs = ms.coerceIn(0, 65535)))

    private fun setDraft(n: SiggenConfig) {
        if (n == d) return
        d = n
        scheduleApply()
    }

    // Edits while a signal plays apply after a pause in the typing or dragging
    private fun scheduleApply() {
        if (!running) return
        applyJob?.cancel()
        applyJob =
            bridge.scope.launch(bridge.main) {
                delay(APPLY_DELAY_MS)
                applyLive()
            }
    }

    private suspend fun applyLive() {
        if (!running || !supported) return
        val c = normalised(d)
        if (c == sent) return
        if (c.channelMask == 0) {
            // The device refuses an empty selection: no outputs means stop
            stop()
            return
        }
        if (bridge.onFfi { runCatching { bridge.core.siggenSetConfig(c) }.isSuccess }) sent = c
        refreshStatus()
    }

    /** Gated: [DangerousOp.SIGGEN_START] (audible). False if refused or not confirmed. */
    override suspend fun start(): Boolean {
        if (!bridge.dangerousOps.consume(DangerousOp.SIGGEN_START)) {
            bridge.emit(BridgeEvent.CommandFailed("siggen.start", DangerousOpNotConfirmed(DangerousOp.SIGGEN_START)))
            return false
        }
        if (!supported) return false
        applyJob?.cancel()
        val c = normalised(d)
        if (c.channelMask == 0) return false
        val wasRunning = running
        val ok =
            bridge.onFfi {
                if (runCatching { bridge.core.siggenSetConfig(c) }.isFailure) return@onFfi false
                // The device ACKs a config it refuses; idle, it applies one at once
                if (!wasRunning) {
                    val back = runCatching { bridge.core.siggenGetConfig() }.getOrNull()
                    if (back != null && (back.signalType != c.signalType || back.channelMask != c.channelMask)) return@onFfi false
                }
                runCatching { bridge.core.siggenControl(DspiConstants.SIGGEN_CTL_START) }.isSuccess
            }
        if (ok) sent = c
        refreshStatus()
        return ok
    }

    /** Not gated: stopping a test signal must always work. */
    override fun stop() = control(DspiConstants.SIGGEN_CTL_STOP)

    override fun stopNow() = control(DspiConstants.SIGGEN_CTL_STOP_NOW)

    private fun control(action: Int) {
        applyJob?.cancel()
        bridge.scope.launch(bridge.main) {
            bridge.onFfi { runCatching { bridge.core.siggenControl(action) } }
            refreshStatus()
        }
    }

    /** Channel ID on one output, leaving the draft alone. Gated: [DangerousOp.SIGGEN_START]. */
    override fun identify(output: Int) {
        if (!bridge.dangerousOps.consume(DangerousOp.SIGGEN_START)) {
            bridge.emit(BridgeEvent.CommandFailed("siggen.identify", DangerousOpNotConfirmed(DangerousOp.SIGGEN_START)))
            return
        }
        if (!supported || output !in 0..15) return
        val mask = (1 shl output) and usableMask()
        if (mask == 0) return
        val c = SiggenConfig(TYPE_CHANNEL_ID, mask, 0, 0, -12f, 0, 2, 0, listOf(120f, 0f, 0f, 0f))
        applyJob?.cancel()
        bridge.scope.launch(bridge.main) {
            val ok =
                bridge.onFfi {
                    runCatching {
                        bridge.core.siggenSetConfig(c)
                        bridge.core.siggenControl(DspiConstants.SIGGEN_CTL_START)
                    }.isSuccess
                }
            if (ok) sent = c
            refreshStatus()
        }
    }

    companion object {
        const val POLL_MS = 300L
        const val APPLY_DELAY_MS = 350L
        const val TYPE_CHANNEL_ID = 14
        private const val UNUSED = 0
        private const val SWEEP = 1

        /** The macOS default: a 1 kHz sine on outputs 1-2 at -20 dBFS. */
        val DEFAULT_DRAFT = SiggenConfig(0, 0x0003, 0, 0, -20f, 0, 0, 0, listOf(1000f, 0f, 0f, 0f))

        private fun p(semantic: Int, min: Float, max: Float, def: Float) = SiggenParam(semantic, min, max, def)

        private fun t(id: Int, timing: Int, vararg ps: SiggenParam) = SiggenTypeInfo(
            id,
            "",
            timing,
            ps.toList() + List(4 - ps.size) { p(0, 0f, 0f, 0f) }
        )

        /** The macOS Console's built-in table, for a device that gives no descriptor. */
        private val FALLBACK =
            listOf(
                t(0, 0, p(1, 1f, 30000f, 1000f)),
                t(1, 0, p(1, 1f, 30000f, 100f)),
                t(2, 0),
                t(3, 0),
                t(4, 1, p(1, 1f, 30000f, 20f), p(1, 1f, 30000f, 20000f)),
                t(5, 1, p(1, 1f, 30000f, 20f), p(1, 1f, 30000f, 20000f)),
                t(6, 1, p(1, 1f, 30000f, 20f), p(1, 1f, 30000f, 20000f), p(4, 1f, 24f, 3f), p(2, 20f, 10000f, 250f)),
                t(7, 2, p(2, 10f, 60000f, 500f)),
                t(8, 2, p(2, 10f, 60000f, 500f)),
                t(9, 2, p(2, 1f, 100f, 5f), p(2, 10f, 60000f, 500f)),
                t(10, 2, p(1, 1f, 30000f, 1000f), p(3, 1f, 1000f, 8f), p(3, 0f, 1000f, 8f), p(3, 0f, 100f, 2f)),
                t(11, 0, p(1, 1f, 30000f, 60f), p(1, 1f, 30000f, 7000f), p(5, 0.1f, 10f, 4f)),
                t(12, 0, p(4, 2f, 16f, 10f), p(1, 1f, 30000f, 20f), p(1, 1f, 30000f, 20000f)),
                t(13, 0, p(6, 0f, 1f, 0f)),
                t(14, 2, p(2, 30f, 1000f, 120f))
            )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// StatsController
// ═══════════════════════════════════════════════════════════════════════

/**
 * System Statistics: device counters every 2 s and buffer fill levels every
 * 60 ms, polled only while [watching]. [info] holds the STAT_* counters (the
 * receiver/LG/ADAT readings are on the bridge's fetch* calls).
 */
class StatsControllerImpl internal constructor(private val bridge: DspiBridgeImpl) : StatsController {
    private val _info = MutableStateFlow<Map<Int, Long>>(emptyMap())
    private val _buffers = MutableStateFlow<BufferStats?>(null)
    private val _reconnects = MutableStateFlow(0)
    override val info: StateFlow<Map<Int, Long>> = _info.asStateFlow()
    override val buffers: StateFlow<BufferStats?> = _buffers.asStateFlow()
    override val reconnects: StateFlow<Int> = _reconnects.asStateFlow()

    private var serial = ""
    private var seenDevice = false
    private var slowJob: Job? = null
    private var fastJob: Job? = null

    override var watching: Boolean = false
        set(w) {
            if (w == field) return
            field = w
            updateTimers()
            if (w) pollNow()
        }

    init {
        bridge.deviceChangedHooks += { onDeviceChanged() }
    }

    internal fun close() {
        slowJob?.cancel()
        fastJob?.cancel()
    }

    // Counts reconnections after the first connection this session
    private fun onDeviceChanged() {
        val s = if (bridge.usable) bridge.selectedSerial else ""
        if (s == serial) return
        serial = s
        _buffers.value = null
        _info.value = emptyMap()
        if (s.isNotEmpty()) {
            if (seenDevice) _reconnects.value += 1
            seenDevice = true
        }
        updateTimers()
        if (watching) pollNow()
    }

    private fun updateTimers() {
        val on = watching && serial.isNotEmpty()
        if (on) {
            if (slowJob?.isActive != true) slowJob = loop(SLOW_MS) { pollSlow() }
            if (fastJob?.isActive != true) fastJob = loop(FAST_MS) { pollFast() }
        } else {
            slowJob?.cancel()
            fastJob?.cancel()
            slowJob = null
            fastJob = null
        }
    }

    private fun loop(period: Long, body: suspend () -> Unit) = bridge.scope.launch(bridge.main) {
        while (isActive) {
            delay(period)
            body()
        }
    }

    private fun pollNow() {
        bridge.scope.launch(bridge.main) {
            pollSlow()
            pollFast()
        }
    }

    private suspend fun pollSlow() {
        if (serial.isEmpty()) return
        val m =
            bridge.onFfi {
                STATS.mapNotNull { which ->
                    runCatching { bridge.get { bridge.core.fetchStat(which) } }.getOrNull()?.let { which to it }
                }.toMap()
            }
        if (m != _info.value) _info.value = m
    }

    private suspend fun pollFast() {
        if (serial.isEmpty()) return
        val b = bridge.onFfi { runCatching { bridge.get { bridge.core.fetchBufferStats() } }.getOrNull() } ?: return
        // Every sample moves the traces
        _buffers.value = b
    }

    /** Gated: [DangerousOp.RESET_BUFFER_STATS] (0xB1 is an IN request the firmware acts on). */
    override fun resetWatermarks() {
        if (!bridge.dangerousOps.consume(DangerousOp.RESET_BUFFER_STATS)) {
            bridge.emit(BridgeEvent.CommandFailed("stats.resetWatermarks", DangerousOpNotConfirmed(DangerousOp.RESET_BUFFER_STATS)))
            return
        }
        bridge.scope.launch(bridge.main) {
            if (serial.isNotEmpty()) bridge.onFfi { runCatching { bridge.core.resetBufferStats() } }
            pollFast()
        }
    }

    companion object {
        const val SLOW_MS = 2000L
        const val FAST_MS = 60L

        /** The STAT_* counters System Statistics shows. */
        val STATS: List<Int> =
            listOf(
                DspiConstants.STAT_CLOCK_HZ, DspiConstants.STAT_CORE_MV, DspiConstants.STAT_SAMPLE_RATE, DspiConstants.STAT_TEMPERATURE,
                DspiConstants.STAT_USB_RING_OVERRUNS, DspiConstants.STAT_SPDIF_OVERRUNS, DspiConstants.STAT_SPDIF_UNDERRUNS,
                DspiConstants.STAT_PDM_RING_OVERRUNS, DspiConstants.STAT_PDM_RING_UNDERRUNS, DspiConstants.STAT_PDM_DMA_OVERRUNS,
                DspiConstants.STAT_PDM_DMA_UNDERRUNS, DspiConstants.STAT_STARVATION_TOTAL, DspiConstants.STAT_STARVATION_FIRST,
                DspiConstants.STAT_STARVATION_FIRST + 1, DspiConstants.STAT_STARVATION_FIRST + 2, DspiConstants.STAT_STARVATION_FIRST + 3
            )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// CsController
// ═══════════════════════════════════════════════════════════════════════

/**
 * Control Surfaces: read once per device and kept by the core; every
 * operation goes through [apply] and the core re-reads what it changed.
 * [model] is the core's `cs_snapshot` JSON, updated only when it differs.
 */
class CsControllerImpl internal constructor(private val bridge: DspiBridgeImpl) : CsController {
    private val _model = MutableStateFlow("")
    private val _supported = MutableStateFlow(false)
    private val _loaded = MutableStateFlow(false)
    private val _unsaved = MutableStateFlow(false)
    override val model: StateFlow<String> = _model.asStateFlow()
    override val supported: StateFlow<Boolean> = _supported.asStateFlow()
    override val loaded: StateFlow<Boolean> = _loaded.asStateFlow()
    override val unsaved: StateFlow<Boolean> = _unsaved.asStateFlow()

    private var serial = ""
    private var macroJob: Job? = null

    init {
        bridge.deviceChangedHooks += { onDeviceChanged() }
        bridge.csHooks += { aux, learn -> if (aux || learn) bridge.scope.launch(bridge.main) { reload() } }
    }

    internal fun close() {
        macroJob?.cancel()
    }

    // A new device (or none): read its configuration
    private fun onDeviceChanged() {
        val usable = bridge.usable
        val s = if (usable) bridge.selectedSerial else ""
        if (s == serial) return
        serial = s
        macroJob?.cancel()
        setModel("")
        if (usable) refresh()
    }

    override fun refresh() {
        if (!bridge.connected) return
        bridge.scope.launch(bridge.main) {
            val ok = bridge.onFfi { runCatching { bridge.get { bridge.core.csFetch() } }.isSuccess }
            if (!ok) {
                // USB trouble rather than missing support: read again on the next change
                serial = ""
                return@launch
            }
            reload()
        }
    }

    /** Re-read the core's snapshot; tell the pages only if something changed. */
    private suspend fun reload() {
        val (snap, pins) = bridge.onFfi { bridge.core.csSnapshot() to runCatching { bridge.core.csPinUses() }.getOrDefault(emptyList()) }
        bridge.csPinUses = pins
        setModel(snap)
    }

    private fun setModel(json: String) {
        if (json == _model.value) return
        _model.value = json
        val m = if (json.isEmpty()) null else Json.parseObject(json)
        _loaded.value = m != null
        _supported.value = m?.bool("supported") == true
        _unsaved.value = m?.obj("status")?.bool("dirty") == true
    }

    /** One operation `{op, index, ...}`; the gated ones ([DangerousOps.csOpOf]) need a token. */
    override suspend fun apply(opJson: String): String {
        val gated = DangerousOps.csOpOf(opJson)
        if (gated != null && !bridge.dangerousOps.consume(gated)) {
            bridge.emit(BridgeEvent.CommandFailed("cs.apply", DangerousOpNotConfirmed(gated)))
            return """{"ok":false,"status":255,"error":"not confirmed"}"""
        }
        if (!bridge.connected) return """{"ok":false,"status":255}"""
        val r = bridge.onFfi { runCatching { bridge.core.csApply(opJson) }.getOrDefault("""{"ok":false,"status":255}""") }
        reload()
        if (Json.parseObject(opJson)?.str("op") == "macroFire" && Json.parseObject(r)?.bool("ok") == true) followMacro()
        return r
    }

    // A running macro pushes nothing, so follow it until it ends (with a ceiling:
    // steps can carry minute-long delays)
    private fun followMacro() {
        macroJob?.cancel()
        macroJob =
            bridge.scope.launch(bridge.main) {
                repeat(MACRO_POLLS) {
                    delay(MACRO_POLL_MS)
                    bridge.onFfi { runCatching { bridge.core.csApply("""{"op":"refreshStatus"}""") } }
                    reload()
                    val running = Json.parseObject(_model.value)?.obj("ext")?.int("macroRunning") ?: 0xFF
                    if (running == 0xFF) return@launch
                }
            }
    }

    /** An aux level while its slider is dragged: sent, the model isn't refreshed until the release commits with [apply]. */
    override fun sendOnly(opJson: String) {
        if (DangerousOps.csOpOf(opJson) != null || !bridge.connected) return
        bridge.scope.launch(bridge.ffi) { runCatching { bridge.core.csApply(opJson) } }
    }

    /** Write the live configuration to flash (gated: [DangerousOp.CS_SAVE]); the device's status code, 0 = done. */
    override suspend fun save(): Int = Json.parseObject(apply("""{"op":"save"}"""))?.int("status") ?: 0xFF

    /** Reload the saved configuration (gated: [DangerousOp.CS_REVERT]). */
    override suspend fun revert(): Int = Json.parseObject(apply("""{"op":"revert"}"""))?.int("status") ?: 0xFF

    /** A PIN_CONFIG_* / CS status code as the macOS Console words it ("" = done). */
    override fun statusMessage(code: Int): String = when (code) {
        0x00 -> ""
        0x01 -> "A pin is out of range, or an encoder's two pins are equal"
        0x02 -> "A pin is already claimed by another peripheral or binding"
        0x11 -> "Unsupported component type"
        0x12 -> "Unsupported function"
        0x13 -> "That action isn't allowed for this component and function"
        0x14 -> "A value, step, range, or flag is out of bounds"
        0x15 -> "A potentiometer must use an ADC pin (GPIO 26, 27, or 28)"
        0x10 -> "Invalid slot"
        0x16 -> "Still applying, please retry"
        0x17 -> "The selected channel or band isn't valid for this function"
        0x18 -> "That press gesture isn't allowed here"
        0x19 -> "This PWM pin conflicts with another dimmable LED or output"
        0x1A -> "Another button already uses this GPIO and gesture"
        0x1B -> "The device was busy; please try again"
        0x1C -> "The device could not write to flash"
        0x1D -> "Another slot already holds the IR receiver (one per device)"
        0x1E -> "Add an IR receiver before learning a remote button"
        0x1F -> "That group is empty, missing, or holds the wrong kind of channel"
        0x20 -> "Invalid macro or step count"
        0x21 -> "A macro step isn't valid"
        0x22 -> "Another slot already holds the display (one per device)"
        0x23 -> "SDA and SCL must be an even/odd GPIO pair on the same I2C bus"
        0x24 -> "That I2C bus belongs to the I2C control interface"
        0x25 -> "That display page isn't valid"
        0x26 -> "The target isn't an auxiliary output, or a level control needs a dimmable one"
        else -> "Failed to apply the binding"
    }

    companion object {
        const val MACRO_POLL_MS = 250L
        const val MACRO_POLLS = 120
    }
}

// ═══════════════════════════════════════════════════════════════════════
// MonitorModel
// ═══════════════════════════════════════════════════════════════════════

/**
 * The Interrupt Monitor's log: the device's notifications, one decoded line
 * each, from the core's log (which keeps the last 2000 whether or not the
 * view is open). Lines are read when a notification batch arrives and the
 * view shows; paused, new lines are skipped rather than queued.
 */
class MonitorModelImpl internal constructor(private val bridge: DspiBridgeImpl) : MonitorModel {
    private val _lines = MutableStateFlow<List<MonitorLine>>(emptyList())
    override val lines: StateFlow<List<MonitorLine>> = _lines.asStateFlow()
    private var lastId = 0L
    private var reading = false

    override var watching: Boolean = false
        set(w) {
            if (w == field) return
            field = w
            if (w) readNew() // catch up on what came while closed
        }

    override var paused: Boolean = false
        set(p) {
            if (p == field) return
            field = p
            if (!p) readNew()
        }

    init {
        bridge.notificationHooks += { readNew() }
    }

    private fun readNew() {
        if (!watching || reading) return
        reading = true
        bridge.scope.launch(bridge.main) {
            val skip = paused
            val (last, entries) =
                bridge.onFfi {
                    var id = lastId
                    val all = ArrayList<MonitorLine>()
                    while (true) {
                        val b = bridge.core.monitorRead(id)
                        if (b.lastId == id) break
                        id = b.lastId
                        if (!skip) b.entries.mapTo(all) { MonitorLine(it.id, it.timeMs, it.text) }
                    }
                    id to all
                }
            lastId = last
            reading = false
            if (entries.isEmpty()) return@launch
            // Keep the newest 2000
            _lines.value = (_lines.value + entries).takeLast(LIMIT)
        }
    }

    override fun clear() {
        bridge.scope.launch(bridge.ffi) { runCatching { bridge.core.monitorClear() } }
        _lines.value = emptyList()
    }

    /** Every line, for copying: `HH:mm:ss.SSS  text`. */
    override fun allText(): String = _lines.value.joinToString("\n") { TIME.format(Instant.ofEpochMilli(it.timeMs)) + "  " + it.text }

    companion object {
        const val LIMIT = 2000
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    }
}

// ═══════════════════════════════════════════════════════════════════════
// ConfigFiles
// ═══════════════════════════════════════════════════════════════════════

/**
 * Device configuration files (.dspipreset, shared with the macOS and Windows
 * Consoles) and filter text files (REW / AutoEQ compatible), as text: the UI
 * reads and writes them through SAF. Results are the core's JSON.
 */
class ConfigFilesImpl internal constructor(private val bridge: DspiBridgeImpl) : ConfigFiles {
    override suspend fun exportConfiguration(name: String): String {
        if (!bridge.connected) return ""
        val links = bridge.inputLinks
        return bridge.onFfi { bridge.core.configExport(name, bridge.appVersion, links) }
    }

    /** {ok, error, name, platform, firmware, savedUtc, crossPlatform}; stateless (no USB). */
    override fun inspectConfiguration(text: String): String {
        val m =
            Json.parseObject(bridge.core.configInspect(text))?.toMutableMap()
                ?: return """{"ok":false,"error":"The file could not be read."}"""
        if (m.bool("ok") == true) {
            val platform = m.str("platform") ?: ""
            m["crossPlatform"] = platform.isNotEmpty() && platform != bridge.platformName
        }
        return Json.write(m)
    }

    /** Gated: [DangerousOp.CONFIG_IMPORT] (bulk write + pin writes). {ok, error, lines, clean, links, hardwareWritten}. */
    override suspend fun importConfiguration(text: String, volumes: Boolean, hardware: Boolean): String {
        if (!bridge.dangerousOps.consume(DangerousOp.CONFIG_IMPORT)) {
            bridge.emit(BridgeEvent.CommandFailed("configFiles.importConfiguration", DangerousOpNotConfirmed(DangerousOp.CONFIG_IMPORT)))
            return """{"ok":false,"error":"Not confirmed."}"""
        }
        if (!bridge.connected) return """{"ok":false,"error":"No device connected."}"""
        val options = (if (volumes) 1L else 0L) or (if (hardware) 2L else 0L)
        val r = bridge.onFfi { bridge.core.configImport(text, options) }
        val m = Json.parseObject(r)
        if (m?.bool("ok") == true) {
            m.long("links")?.let { bridge.restoreInputLinks(it.toInt()) }
            if (m.bool("hardwareWritten") == true) bridge.markHardwareUnsaved()
        }
        bridge.refreshAll()
        return r
    }

    override suspend fun exportFilters(): String {
        if (!bridge.connected) return ""
        val inputs = bridge.liveInputCount()
        return bridge.onFfi { bridge.core.filtersExport(inputs) }
    }

    /** {ok, error, kind, channels: [{wire, label, bands, checked}], filters, preamp}. */
    override suspend fun inspectFilters(text: String): String {
        val inputs = bridge.liveInputCount()
        val m = Json.parseObject(bridge.onFfi { bridge.core.filtersInspect(text, inputs) })?.toMutableMap() ?: mutableMapOf()
        val ok = (m["channels"] as? List<*>)?.isNotEmpty() == true
        m["ok"] = ok
        if (!ok) m["error"] = "No filters this device can use were found in the file."
        return Json.write(m)
    }

    override suspend fun importFilters(text: String, wires: List<Int>): String {
        if (!bridge.connected) return """{"ok":false,"error":"No device connected."}"""
        val r = bridge.onFfi { bridge.core.filtersImport(text, wires) }
        bridge.refreshAll()
        return r
    }
}
