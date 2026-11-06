// Port of DSPi-Console-Linux:qt/src/DSPiBridge.{h,cpp} @ 4683a2c, following
// its CLAUDE.md "Rendering and updates" rules. Names and behaviour are kept;
// the deviations (threading, Android device handling, DangerousOps, retry)
// are listed in docs/UPSTREAM-DEVIATIONS.md under "BRIDGE".
package se.bofr.dspi.bridge

import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.bofr.dspi.core.AdatOutStatus
import se.bofr.dspi.core.DeviceEvents
import se.bofr.dspi.core.DspState
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.core.DspiConstants.BANDS_PER_CHANNEL
import se.bofr.dspi.core.DspiConstants.COMPAT_OK
import se.bofr.dspi.core.DspiConstants.FILTER_FLAT
import se.bofr.dspi.core.DspiConstants.FILTER_LINKWITZ_TRANSFORM
import se.bofr.dspi.core.DspiConstants.LIMITER_ALL_OUTPUTS
import se.bofr.dspi.core.DspiConstants.MAX_CHANNELS
import se.bofr.dspi.core.DspiConstants.MAX_INPUTS
import se.bofr.dspi.core.DspiConstants.MAX_OUTPUTS
import se.bofr.dspi.core.DspiConstants.MAX_PRESETS
import se.bofr.dspi.core.DspiConstants.MAX_XOVER_BANDS
import se.bofr.dspi.core.DspiConstants.XOVER_BAND_BASE
import se.bofr.dspi.core.DspiCoreApi
import se.bofr.dspi.core.DspiException
import se.bofr.dspi.core.FilterParams
import se.bofr.dspi.core.FlashStatus
import se.bofr.dspi.core.I2cConfig
import se.bofr.dspi.core.InputLockStatus
import se.bofr.dspi.core.LgStatus
import se.bofr.dspi.core.NotifyListener
import se.bofr.dspi.core.PinConfigStatus
import se.bofr.dspi.core.PresetStatus
import se.bofr.dspi.core.UartConfig
import se.bofr.dspi.core.UpmixStatus
import se.bofr.dspi.core.statusOf

/**
 * The real [DspiBridge] over a [DspiCoreApi] (the FFI core or a fake).
 *
 * Threading (PLAN §3.4/§5.4): every core call runs on [ffi], one serial
 * dispatcher, in call order; results, flows and events are published on
 * [main] (Android: `Dispatchers.Main.immediate`; tests: a test dispatcher).
 * Public members are called on the main thread. Core callbacks (notify, RTA,
 * hotplug) only post to [main].
 *
 * @param scope   lifetime of the bridge's coroutines (app scope; tests: backgroundScope)
 * @param clock   monotonic milliseconds (clip latch, notify batching, retry window, tokens)
 * @param appVersion written into exported configuration files
 * @param hotplugPolling run Qt's 500 ms `pollHotplug`/rescan timer (desktop
 *        libusb only; Android gets devices from `:usb` via [attachDevice])
 */
class DspiBridgeImpl(
    core: DspiCoreApi,
    internal val scope: CoroutineScope,
    internal val ffi: CoroutineDispatcher = newFfiDispatcher(),
    internal val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val linkStore: LinkStore = InMemoryLinkStore(),
    internal val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    internal val appVersion: String = "",
    private val hotplugPolling: Boolean = false
) : DspiBridge {
    /**
     * G1: the core as every member and controller sees it — writes refused
     * while [writesAllowed] is false (a safety net; members refuse first).
     */
    internal val guardedCore = WriteGuardedCore(core) { _writesAllowed.value }
    internal val core: DspiCoreApi get() = guardedCore

    // ═══ Main-thread state (Qt: the bridge's members + the core's state) ═══

    /**
     * Latest core state; getters read this (Qt reads the core live). Seeded
     * once at construction (review B7): `getState` is a copy, no USB, and the
     * bridge is the core's only user then (it registers the core's listeners
     * in the same breath); everything after runs on [ffi].
     */
    private var current: DspState = core.getState()

    private val _state = MutableStateFlow(current)
    private val _stateRevision = MutableStateFlow(0L)

    // B7: start disconnected; scanDevices()/attachDevice() publish the connection
    private val _status = MutableStateFlow(BridgeStatus())
    private val _devices = MutableStateFlow(DeviceListState())
    private val _presetDirty = MutableStateFlow(false)
    private val _hardwareUnsaved = MutableStateFlow(false)
    private val _magnitudesRevision = MutableStateFlow(0L)
    private val _events = MutableSharedFlow<BridgeEvent>(extraBufferCapacity = 256)

    override val state: StateFlow<DspState> = _state.asStateFlow()
    override val stateRevision: StateFlow<Long> = _stateRevision.asStateFlow()
    override val status: StateFlow<BridgeStatus> = _status.asStateFlow()
    override val devices: StateFlow<DeviceListState> = _devices.asStateFlow()
    override val presetDirty: StateFlow<Boolean> = _presetDirty.asStateFlow()
    override val hardwareUnsaved: StateFlow<Boolean> = _hardwareUnsaved.asStateFlow()

    // E1b / macOS SettingsSaveCoordinator (contract hook; revert is BRIDGE's to finish)
    private val _outputConfigDirty = MutableStateFlow(false)
    override val outputConfigDirty: StateFlow<Boolean> = _outputConfigDirty.asStateFlow()
    private var outputEditRaw = false
    private var outputBaseline: DspState? = null
    private var outputEditSerial = ""
    override val magnitudesRevision: StateFlow<Long> = _magnitudesRevision.asStateFlow()
    override val events: SharedFlow<BridgeEvent> = _events.asSharedFlow()

    // G1: connected && compat == COMPAT_OK; recomputed whenever either changes
    private val _writesAllowed = MutableStateFlow(false)
    override val writesAllowed: StateFlow<Boolean> = _writesAllowed.asStateFlow()

    /** FFI thread: whether the firmware answers EXT_CROSSFEED_PAIR_MAP (extension channel, bofr-main). */
    private fun probeCrossfeedPairMap(): Boolean {
        repeat(2) {
            try {
                core.fetchCrossfeedPairMap()
                return true
            } catch (e: DspiException.Transfer) {
                return false // STALL: upstream firmware
            } catch (e: Exception) {
                // timeout / busy: try once more
            }
        }
        return false
    }

    /** K340 fork: the connected firmware answered the pair-map probe (main thread). */
    private var crossfeedPairMapSupported = false

    private fun refreshWritesAllowed() {
        val v = usable
        if (_writesAllowed.value != v) _writesAllowed.value = v
    }

    /**
     * G1: true (and [BridgeEvent.CommandFailed] emitted) when [name] must not
     * reach the device because [writesAllowed] is false. A gated member passes
     * its [token]/[op]: the token is validated (an invalid one still throws
     * [DangerousOpNotConfirmed]) but not spent. Call on main, before anything
     * else the member does.
     */
    internal fun writeRefused(name: String, token: ConfirmationToken? = null, op: DangerousOp? = null): Boolean {
        if (usable) return false
        if (token != null && op != null) ops.require(token, op, spend = false)
        val why = when {
            !connected -> WritesNotAllowedException.NOT_CONNECTED
            remoteReadOnly && current.compat == COMPAT_OK -> WritesNotAllowedException.VIEWER
            else -> WritesNotAllowedException.FIRMWARE_MISMATCH
        }
        emit(BridgeEvent.CommandFailed(name, WritesNotAllowedException(why)))
        return true
    }

    private val ops = DangerousOpsImpl(clock)
    override val dangerousOps: DangerousOps get() = ops

    /**
     * Check [token] for [op] or throw [DangerousOpNotConfirmed] — before any
     * device traffic. Spent only while connected (review N2): a call made
     * while disconnected fails with its usual no-reply value and leaves the
     * token valid; an invalid token throws either way.
     */
    internal fun requireToken(token: ConfirmationToken, op: DangerousOp) = ops.require(token, op, spend = connected)

    private val map: ChannelMap get() = ChannelMap(current.numInputChannels, current.numOutputChannels)

    /** Raw (offset-free) magnitude curves per app id; null = recompute. */
    private val magnitudeCache = arrayOfNulls<List<Double>>(ChannelMap.APP_CHANNEL_COUNT)

    /** Graph visibility; only the first input pair by default. */
    private val channelVisibleFlags = BooleanArray(ChannelMap.APP_CHANNEL_COUNT) { it < 2 }
    private var links = 0

    /** From the core on every snapshot (Qt reads dspi_preamp_max_db / dspi_max_delay_ms live). */
    private var preampMax = 18f
    private var maxDelay = 21f
    private var attachedFd: Triple<Int, String, Int>? = null

    /** DSPi Link: the remote device attached by [attachRemote] (handle, serial). */
    private var attachedRemote: Pair<Long, String>? = null

    private class ChannelClip(
        val output: Boolean,
        val filters: List<FilterParams>,
        val xover: List<FilterParams>,
        val gain: Float,
        val delay: Float,
        val mute: Boolean
    )

    private var clip: ChannelClip? = null

    // ═══ FFI-thread state ═══

    /** App ids whose curve changed since the last emission (Qt m_magnitudeDirty). */
    private val pendingDirty = HashSet<Int>()

    /** Per wire channel, when the last clip was seen (ms, 0 = never): Qt m_lastClipMs. */
    private val lastClipMs = LongArray(MAX_CHANNELS)
    private var pollTick = 0

    /** Until when idempotent GETs are retried (after a flash write). */
    @Volatile private var blackoutUntil = Long.MIN_VALUE

    // ═══ Construction ═══

    /** Controllers' hooks for Qt's devicesChanged / notification signals. */
    internal val deviceChangedHooks = ArrayList<() -> Unit>()
    internal val notificationHooks = ArrayList<() -> Unit>()
    internal val siggenHooks = ArrayList<() -> Unit>()
    internal val csHooks = ArrayList<(Boolean, Boolean) -> Unit>()

    override val rta: RtaControllerImpl
    override val siggen: SiggenControllerImpl
    override val stats: StatsControllerImpl
    override val cs: CsControllerImpl
    override val monitor: MonitorModelImpl
    override val configFiles: ConfigFilesImpl

    private var uiVisible = true
    private var pollJob: Job? = null
    private var hotplugJob: Job? = null
    private var notifyJob: Job? = null
    private var lastNotify = Long.MIN_VALUE / 2

    init {
        core.setHotplugCallback(
            object : DeviceEvents {
                override fun onArrived(serial: String) = post { deviceArrived(serial) }

                override fun onDeparted(serial: String) = post { deviceDeparted(serial) }
            }
        )
        core.setNotifyListener(NotifyListener { post { scheduleNotifications() } })
        rta = RtaControllerImpl(this)
        siggen = SiggenControllerImpl(this)
        stats = StatsControllerImpl(this)
        cs = CsControllerImpl(this)
        monitor = MonitorModelImpl(this)
        configFiles = ConfigFilesImpl(this)
        updateTimers()
        // As the Qt constructor: pick up a device the core can already see
        // (desktop libusb, the fake in demo mode; on Android the list is
        // empty and :usb calls attachDevice)
        scanDevices()
    }

    /** Stop the timers and drop the core's listeners (the core itself belongs to the caller). */
    fun close() {
        pollJob?.cancel()
        hotplugJob?.cancel()
        notifyJob?.cancel()
        rta.close()
        siggen.close()
        stats.close()
        cs.close()
        core.setNotifyListener(null)
        core.setHotplugCallback(null)
    }

    // ═══ Plumbing ═══

    internal fun post(block: () -> Unit) {
        scope.launch(main) { block() }
    }

    internal fun emit(e: BridgeEvent) {
        _events.tryEmit(e)
    }

    /** Run on the FFI dispatcher (suspending). */
    internal suspend fun <T> onFfi(block: suspend () -> T): T = withContext(ffi) { block() }

    /**
     * Idempotent GET with the flash-blackout retry (DESIGN §4.5 rule 4,
     * `commands.md:67-80`): a transfer error within [FLASH_BLACKOUT_MS] of a
     * flash write is retried up to [GET_RETRIES] times, [GET_RETRY_DELAY_MS]
     * apart. Never use it for a SET. Call on the FFI dispatcher.
     */
    internal suspend fun <T> get(block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: DspiException.Transfer) {
                if (attempt >= GET_RETRIES || clock() >= blackoutUntil) throw e
                attempt++
                delay(GET_RETRY_DELAY_MS)
            }
        }
    }

    /**
     * A deferred / flash-writing op just ran (commands.md §1.3: preset
     * load/save/delete, factory reset, output-type switch, pin move,
     * input-source switch, bulk apply; D3: directory/config writes): the
     * device may stall for a while, so idempotent GETs retry until
     * [FLASH_BLACKOUT_MS] from now. Call on the FFI dispatcher.
     */
    internal fun flashWritten() {
        blackoutUntil = clock() + FLASH_BLACKOUT_MS
    }

    private enum class Emit { NONE, SILENT, PREVIEW, STATE, CURVES }

    private class Snapshot(
        val state: DspState,
        val dirty: Boolean,
        val curves: Map<Int, List<Double>>,
        val all: Boolean,
        val preampMax: Float,
        val maxDelay: Float
    )

    /** FFI: the state after a change, the preset-dirty flag and the curves that changed. */
    private fun snapshot(all: Boolean = false): Snapshot {
        val st = core.getState()
        val usable = core.isConnected() && st.compat == COMPAT_OK
        val dirty = usable && runCatching { core.presetDirty() }.getOrDefault(false)
        val ids = if (all) (0 until ChannelMap.APP_CHANNEL_COUNT).toSet() else pendingDirty.toSet()
        pendingDirty.clear()
        val m = ChannelMap(st.numInputChannels, st.numOutputChannels)
        val pre = runCatching { core.preampMaxDb() }.getOrDefault(if (st.platformId == 1) 24f else 18f)
        val dl = runCatching { core.maxDelayMs() }.getOrDefault(if (st.platformId == 1) 42f else 21f)
        return Snapshot(st, dirty, ids.associateWith { rawCurve(st, m, it) }, all, pre, dl)
    }

    private fun bandsOf(st: DspState, m: ChannelMap, appCh: Int): List<FilterParams> {
        val w = m.wire(appCh)
        if (w < 0) return emptyList()
        return if (m.outputOf(appCh) >= 0) st.filters[w] + st.xover[w] else st.filters[w]
    }

    /** DSPiBridge::computeCurve (without the gain offset; [magnitudeCurve] adds it). */
    private fun rawCurve(st: DspState, m: ChannelMap, appCh: Int): List<Double> = core.computeMagnitudeCurve(bandsOf(st, m, appCh))

    /** Main: apply a snapshot — Qt's `emit stateChanged()` (+ `magnitudesChanged()`). */
    private fun publish(snap: Snapshot, magnitudes: Boolean = false) {
        current = snap.state
        preampMax = snap.preampMax
        maxDelay = snap.maxDelay
        _state.value = snap.state
        _stateRevision.value += 1
        if (snap.all) magnitudeCache.fill(null)
        for ((id, c) in snap.curves) magnitudeCache[id] = c
        if (magnitudes || snap.curves.isNotEmpty()) _magnitudesRevision.value += 1
        // Qt: stateChanged -> updatePresetDirty
        if (_presetDirty.value != snap.dirty) _presetDirty.value = snap.dirty
        refreshOutputConfigDirty()
        refreshWritesAllowed()
    }

    /** macOS `outputDirty`: raw flag && independent mode (0). */
    private fun refreshOutputConfigDirty() {
        val v = outputEditRaw && current.outputConfigMode == 0
        if (_outputConfigDirty.value != v) _outputConfigDirty.value = v
    }

    private fun clearOutputEdit() {
        outputEditRaw = false
        outputBaseline = null
        refreshOutputConfigDirty()
    }

    override fun beginOutputEdit() {
        if (current.outputConfigMode != 0) return
        if (!outputEditRaw) {
            outputBaseline = current
            outputEditRaw = true
        }
        refreshOutputConfigDirty()
    }

    /**
     * Bumped on every device open (selection / attach) and close (detach /
     * disconnect / loss), on main — macOS `transport.generation`. Read from
     * the FFI thread too (StateFlow.value is thread-safe).
     */
    private val _deviceGeneration = MutableStateFlow(0L)
    override val deviceGeneration: StateFlow<Long> = _deviceGeneration.asStateFlow()
    private val generation: Long get() = _deviceGeneration.value

    private fun bumpGeneration() {
        _deviceGeneration.value += 1
    }

    /**
     * macOS `SettingsSaveCoordinator.revert` (output part): re-apply the
     * captured baseline, best effort, in the macOS order — slot types, pins,
     * BCK, MCK enable/pin/multiplier, S/PDIF 1 pin; S/PDIF 2-4 disable,
     * repin, enable; I2S channels down, RX pins, channels up; input rate;
     * clock mode; slave BCK then clock-pin mode; ADAT pin then enable;
     * limiters last (ungang, set, regang). Only fields that differ are
     * written. Stops when the device changes mid-sequence. GATED
     * [DangerousOp.HARDWARE_APPLY]; arms the retry blackout.
     */
    override suspend fun revertOutputConfig(token: ConfirmationToken): Boolean {
        if (writeRefused("revertOutputConfig", token, DangerousOp.HARDWARE_APPLY)) return false
        requireToken(token, DangerousOp.HARDWARE_APPLY)
        val base = outputBaseline
        if (!_outputConfigDirty.value || base == null) return false
        val gen = generation
        val caps = hardwareCaps
        val done =
            onFfi {
                fun sameDevice() = generation == gen
                fun step(block: () -> Unit) = runCatching(block)
                var v = core.getState()
                try {
                    if (!sameDevice()) return@onFfi false
                    for (slot in 0 until minOf(maxOf(v.numPinOutputs - 1, 0), base.outputTypes.size, v.outputTypes.size)) {
                        if (v.outputTypes[slot] != base.outputTypes[slot]) step { core.setOutputType(slot, base.outputTypes[slot]) }
                    }
                    for (i in 0 until minOf(v.numPinOutputs, base.outputPins.size)) {
                        if (v.outputPins[i] != base.outputPins[i]) step { core.setOutputPin(i, base.outputPins[i]) }
                    }
                    if (!sameDevice()) return@onFfi false
                    if (v.i2sBckPin != base.i2sBckPin) step { core.setI2sBckPin(0, base.i2sBckPin) }
                    if (v.mckEnabled != base.mckEnabled) step { core.setMckEnabled(base.mckEnabled) }
                    if (v.mckPin != base.mckPin) step { core.setMckPin(base.mckPin) }
                    if (v.mckMultiplier != base.mckMultiplier) step { core.setMckMultiplier(base.mckMultiplier) }
                    if (v.spdifRxPins[0] != base.spdifRxPins[0]) step { core.setSpdifRxPin(0, base.spdifRxPins[0]) }
                    if (!sameDevice()) return@onFfi false
                    // S/PDIF 2-4: drop an enable first (so a repin can't clash), repin, then enable
                    if (caps.multiSpdifSupported) {
                        for (idx in 1 until minOf(caps.spdifInputCount, 4)) {
                            val on = (v.spdifInputsEnabled shr idx) and 1 == 1
                            val want = (base.spdifInputsEnabled shr idx) and 1 == 1
                            if (on && !want) step { core.setSpdifInputEnabled(idx, false) }
                            if (v.spdifRxPins[idx] != base.spdifRxPins[idx]) step { core.setSpdifRxPin(idx, base.spdifRxPins[idx]) }
                            if (!on && want) step { core.setSpdifInputEnabled(idx, true) }
                        }
                    }
                    if (!sameDevice()) return@onFfi false
                    // I2S input: a lowered count first (frees pairs), a raised count last
                    // (the firmware checks the newly active pairs' pins, restored first)
                    val raising = base.i2sInputChannels > v.i2sInputChannels
                    if (!raising && v.i2sInputChannels != base.i2sInputChannels) step { core.setI2sInputChannels(base.i2sInputChannels) }
                    for (pair in 0 until minOf(v.i2sRxPins.size, base.i2sRxPins.size)) {
                        if (v.i2sRxPins[pair] != base.i2sRxPins[pair]) step { core.setI2sRxPin(pair, base.i2sRxPins[pair]) }
                    }
                    if (raising) step { core.setI2sInputChannels(base.i2sInputChannels) }
                    if (v.i2sInputRate != base.i2sInputRate) step { core.setInputRate(base.i2sInputRate) }
                    if (v.i2sClockMode != base.i2sClockMode) step { core.setI2sClockMode(base.i2sClockMode) }
                    // Clock-pin mode: the slave pair first, so re-entering split finds it valid
                    if (caps.i2sClockPinModeSupported) {
                        if (v.i2sBckPinSlave != base.i2sBckPinSlave) step { core.setI2sBckPin(1, base.i2sBckPinSlave) }
                        if (v.i2sClockPinMode != base.i2sClockPinMode) step { core.setI2sClockPinMode(base.i2sClockPinMode) }
                    }
                    if (!sameDevice()) return@onFfi false
                    // ADAT: the data pin first, then the enable
                    if (caps.adatOutSupported) {
                        if (v.adatOutPin != base.adatOutPin) step { core.setAdatOutPin(base.adatOutPin) }
                        if (v.adatOutEnabled != base.adatOutEnabled) step { core.setAdatOutEnabled(base.adatOutEnabled) }
                    }
                    if (!sameDevice()) return@onFfi false
                    // Output limiters last
                    v = core.getState()
                    val targets =
                        (0 until minOf(v.numOutputChannels, MAX_OUTPUTS)).filter {
                            v.limiterEnabled[it] != base.limiterEnabled[it] ||
                                v.limiterThresholdDb[it] != base.limiterThresholdDb[it] ||
                                v.limiterReleaseMs[it] != base.limiterReleaseMs[it] ||
                                v.limiterLinkGroup[it] != base.limiterLinkGroup[it]
                        }.associateWith {
                            LimiterTarget(
                                base.limiterEnabled[it],
                                base.limiterThresholdDb[it],
                                base.limiterReleaseMs[it],
                                base.limiterLinkGroup[it]
                            )
                        }
                    if (targets.isNotEmpty()) applyLimiterSettings(targets)
                    true
                } finally {
                    flashWritten()
                }
            }
        if (!done || generation != gen) return false
        clearOutputEdit()
        publish(onFfi { snapshot() }, magnitudes = true)
        return true
    }

    /** FFI: a filter of app channel [appCh] changed (Qt markDirty). */
    private fun markDirty(appCh: Int) {
        if (appCh in 0 until ChannelMap.APP_CHANNEL_COUNT) pendingDirty += appCh
    }

    /**
     * A fire-and-forget setter (Qt `Q_INVOKABLE void set*`): [block] runs on the
     * FFI dispatcher in call order; then the state is re-read and published
     * per [emit]. A failure is reported as [BridgeEvent.CommandFailed]; the
     * core has already updated its state, so the emission still happens (as Qt,
     * which ignores the bool).
     */
    private fun fire(name: String, emit: Emit = Emit.STATE, flash: Boolean = false, write: Boolean = true, block: () -> Unit) {
        if (write && writeRefused(name)) return
        scope.launch(ffi) {
            val err =
                try {
                    block()
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e
                } catch (e: NotImplementedError) {
                    e
                }
            if (flash) flashWritten()
            when (emit) {
                Emit.NONE -> Unit
                Emit.SILENT -> {
                    // Mid-drag: getters and curves follow the device (Qt reads the core
                    // live, markDirty recomputes on the next draw), no signal
                    val snap = snapshot()
                    post {
                        current = snap.state
                        for ((id, c) in snap.curves) magnitudeCache[id] = c
                    }
                }
                Emit.PREVIEW -> {
                    val st = core.getState()
                    post {
                        current = st
                        emit(BridgeEvent.PreviewChanged)
                    }
                }
                Emit.STATE, Emit.CURVES -> {
                    val snap = snapshot()
                    post { publish(snap, emit == Emit.CURVES) }
                }
            }
            if (err != null) post { emit(BridgeEvent.CommandFailed(name, err)) }
        }
    }

    /** A suspending call returning the core's status byte, then stateChanged. */
    private suspend fun <S> statusCall(
        noReply: Int,
        of: (Int) -> S,
        magnitudes: Boolean = false,
        all: Boolean = false,
        block: () -> Unit
    ): Pair<S, Snapshot> {
        val (code, snap) =
            onFfi {
                val c = statusOf(noReply) { block() }
                c to snapshot(all)
            }
        return of(code) to snap
    }

    // ═══ Device notifications (DSPiBridge::scheduleNotifications / processNotifications) ═══

    /**
     * Qt: a single-shot timer, at most every [NOTIFY_BATCH_MS]. A wake that
     * arrives while a drain is in flight (after `core.processNotifications()`
     * already ran) re-arms the timer once the drain ends, so the last value of
     * a burst always lands (review B1; Qt's timer is inactive by then).
     */
    private fun scheduleNotifications() {
        if (notifyJob?.isActive == true) {
            if (draining) notifyRearm = true
            return
        }
        val wait = NOTIFY_BATCH_MS - (clock() - lastNotify)
        notifyJob =
            scope.launch(main) {
                if (wait > 0) delay(wait)
                draining = true
                try {
                    processNotifications()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Never let a drain failure reach the app scope (review N1)
                    emit(BridgeEvent.CommandFailed("processNotifications", e))
                } finally {
                    draining = false
                    notifyJob = null
                    if (notifyRearm && isActive) {
                        notifyRearm = false
                        scheduleNotifications()
                    }
                }
            }
    }

    private var draining = false
    private var notifyRearm = false

    /**
     * Apply what another source changed on the device (hardware controls, the
     * OS volume, another host, a preset load); our own writes are not echoed.
     * At most once per [NOTIFY_BATCH_MS], each batch one stateChanged, and only
     * the curves of channels whose filters changed are recomputed.
     */
    private suspend fun processNotifications() {
        lastNotify = clock()
        val (r, snap) =
            onFfi {
                val r = core.processNotifications()
                if (r.flags and DspiConstants.NOTIFY_STATE.toLong() == 0L) return@onFfi r to null
                val refreshed = r.flags and DspiConstants.NOTIFY_REFRESHED.toLong() != 0L
                if (!refreshed) {
                    val st = core.getState()
                    val m = ChannelMap(st.numInputChannels, st.numOutputChannels)
                    for (w in 0 until MAX_CHANNELS) if ((r.filterChannels shr w) and 1L == 1L) markDirty(m.appId(w))
                }
                r to snapshot(all = refreshed)
            }
        emit(BridgeEvent.NotificationsArrived)
        notificationHooks.forEach { it() }
        val flags = r.flags
        if (flags and DspiConstants.NOTIFY_SIGGEN.toLong() != 0L) {
            emit(BridgeEvent.SiggenNotified)
            siggenHooks.forEach { it() }
        }
        val aux = flags and DspiConstants.NOTIFY_CS_AUX.toLong() != 0L
        val learn = flags and DspiConstants.NOTIFY_IR_LEARN.toLong() != 0L
        if (aux || learn) {
            emit(BridgeEvent.CsNotified(aux, learn))
            csHooks.forEach { it(aux, learn) }
        }
        if (snap == null) return
        // Qt polls the status before stateChanged
        if (flags and DspiConstants.NOTIFY_INPUT_FORMAT.toLong() != 0L) pollStatus()
        publish(snap, flags and DspiConstants.NOTIFY_CURVES.toLong() != 0L)
    }

    // ═══ Timers (status poll 60 ms, hotplug 500 ms) ═══

    override fun setUiVisible(visible: Boolean) {
        if (visible == uiVisible) return
        uiVisible = visible
        updateTimers()
    }

    /** Status polling runs only while a UI is visible (PLAN §5.3) and a device is open. */
    private fun updateTimers() {
        val pollOn = uiVisible && connected
        if (pollOn && pollJob?.isActive != true) {
            pollJob =
                scope.launch(main) {
                    while (isActive) {
                        delay(STATUS_POLL_MS)
                        pollStatus()
                    }
                }
        } else if (!pollOn) {
            pollJob?.cancel()
            pollJob = null
        }
        val hotplugOn = hotplugPolling && uiVisible
        if (hotplugOn && hotplugJob?.isActive != true) {
            hotplugJob =
                scope.launch(main) {
                    while (isActive) {
                        delay(HOTPLUG_POLL_MS)
                        pollHotplug()
                    }
                }
        } else if (!hotplugOn) {
            hotplugJob?.cancel()
            hotplugJob = null
        }
    }

    private class StatusPoll(val status: BridgeStatus?, val lost: Boolean)

    /** DSPiBridge::pollStatus: peaks, CPU, clip latch, limiter meter (every 4th tick). */
    private suspend fun pollStatus() {
        if (!usable) return
        val m = map
        val anyLimiter = (0 until current.numOutputChannels).any { current.limiterEnabled[it] }
        val prev = _status.value
        val r =
            onFfi {
                val ns =
                    try {
                        get { core.fetchStatus() }
                    } catch (e: DspiException) {
                        return@onFfi StatusPoll(null, !core.isConnected())
                    }
                // Clip flags are sticky on the device. Latch each clip here with a
                // timestamp and read-then-clear the device flags, so a clip marker
                // clears itself 10 s after the last clip (as on the macOS Console).
                val now = clock()
                if (ns.clipFlags != 0L) {
                    for (w in 0 until MAX_CHANNELS) if ((ns.clipFlags shr w) and 1L == 1L) lastClipMs[w] = now
                    runCatching { core.clearClips() }
                }
                var latched = 0L
                for (w in 0 until MAX_CHANNELS) {
                    if (lastClipMs[w] != 0L &&
                        now - lastClipMs[w] < CLIP_HOLD_MS
                    ) {
                        latched = latched or (1L shl w)
                    }
                }
                val peaks = List(ChannelMap.APP_CHANNEL_COUNT) { id ->
                    m.wire(id).let { w ->
                        if (w >=
                            0
                        ) {
                            ns.peaks.getOrElse(w) { 0f }
                        } else {
                            0f
                        }
                    }
                }
                // D4 / review B4: the limiter meter is part of the status, so a
                // change of it alone is a status change (Qt: changed = true)
                var gr = prev.limiterReduction
                if (anyLimiter && (++pollTick % 4) == 0) {
                    runCatching { get { core.fetchLimiterMeter() } }.getOrNull()?.let { g ->
                        gr = List(MAX_OUTPUTS) { g.getOrElse(it) { 0f } }
                    }
                } else if (!anyLimiter) {
                    gr = emptyList()
                }
                val st =
                    prev.copy(
                        connected = true,
                        cpu0 = ns.cpu0,
                        cpu1 = ns.cpu1,
                        clipFlags = latched,
                        activeInputChannels = ns.activeInputChannels,
                        peaks = peaks,
                        limiterReduction = gr
                    )
                StatusPoll(st, false)
            }
        if (r.lost) {
            setDisconnected()
            return
        }
        if (r.status != null && r.status != _status.value) _status.value = r.status
    }

    private fun pollHotplug() {
        scope.launch(ffi) { runCatching { core.pollHotplug() } }
        // Retry the scan while disconnected: covers an initial scan that ran
        // before USB enumeration finished.
        if (!connected) scanDevices()
    }

    // ═══ Hotplug (DSPiBridge::hotplugCallback) ═══

    private fun deviceArrived(serial: String) {
        scanDevices()
        emit(BridgeEvent.DeviceArrived(serial))
        // The core reconnects a returning device by itself, but its state
        // has to be read again, so (re)select whenever nothing is selected.
        if (selectedSerial.isEmpty() || !connected) selectDevice(serial)
    }

    private fun deviceDeparted(serial: String) {
        if (selectedSerial == serial) {
            scope.launch(ffi) { runCatching { core.disconnect() } }
            attachedFd = null
            attachedRemote = null
            setDisconnected()
        }
        _devices.value = _devices.value.copy(availableSerials = _devices.value.availableSerials - serial)
        scanDevices()
        emit(BridgeEvent.DeviceDeparted(serial))
        deviceChangedHooks.forEach { it() }
    }

    // ═══ Q_PROPERTY getters ═══

    override val connected: Boolean get() = _status.value.connected
    override val connectionError: String get() = _status.value.connectionError
    override val selectedSerial: String get() = _devices.value.selectedSerial
    override val availableSerials: List<String> get() = _devices.value.availableSerials
    override val inaccessibleDevices: Int get() = _devices.value.inaccessibleDevices
    override val platformName: String get() = if (current.platformId == 1) "RP2350" else "RP2040"
    override val preampMaxDB: Float get() = preampMax
    override val numChannels: Int get() = current.numChannels
    override val numInputChannels: Int get() = current.numInputChannels
    override val numOutputChannels: Int get() = current.numOutputChannels

    override val firmwareVersion: String
        get() {
            val s = current
            if (!connected || (s.fwMajor == 0 && s.fwMinor == 0 && s.fwPatch == 0)) return ""
            val v = "${s.fwMajor}.${s.fwMinor}.${s.fwPatch}"
            return when (s.fwBeta) {
                0 -> v
                DspiConstants.FW_BETA_EARLY -> "$v early beta"
                else -> "$v-beta${s.fwBeta}"
            }
        }

    override val compat: Int get() = if (connected) current.compat else DspiConstants.COMPAT_UNKNOWN

    override val compatMessage: String
        get() = when (compat) {
            DspiConstants.COMPAT_FIRMWARE_TOO_OLD ->
                "This DSPi runs firmware ${firmwareVersion.ifEmpty { "older than 1.1.6" }}, which is too old for this Console. " +
                    "Update it to firmware 1.1.6."
            DspiConstants.COMPAT_FIRMWARE_TOO_NEW ->
                "This DSPi runs firmware $firmwareVersion, which is newer than this Console understands. Update the Console."
            else -> ""
        }

    /** Connected to compatible firmware (Qt usable()), and not a viewer on a Link hub. */
    internal val usable: Boolean get() = connected && current.compat == COMPAT_OK && !remoteReadOnly

    /** DSPi Link viewer role ([setRemoteReadOnly]); main thread. */
    @Volatile private var remoteReadOnly = false

    override fun setRemoteReadOnly(readOnly: Boolean) {
        remoteReadOnly = readOnly
        refreshWritesAllowed()
    }

    override val maxDelayMs: Float get() = maxDelay
    override val preampDB: Float get() = current.inputPreampDb[0]
    override val bypass: Boolean get() = current.bypass
    override val masterVolumeDB: Float get() = current.masterVolumeDb
    override val masterVolumeMode: Int get() = current.masterVolumeMode
    override val userVolumeDB: Float get() = current.userVolumeDb
    override val userMute: Boolean get() = current.userMute
    override val loudnessEnabled: Boolean get() = current.loudnessEnabled
    override val loudnessRefSPL: Float get() = current.loudnessRefSpl
    override val loudnessIntensity: Float get() = current.loudnessIntensity
    override val loudnessOutputMask: Int get() = current.loudnessOutputMask
    override val crossfeedEnabled: Boolean get() = current.crossfeedEnabled
    override val crossfeedPreset: Int get() = current.crossfeedPreset
    override val crossfeedFreq: Float get() = current.crossfeedFreq
    override val crossfeedFeed: Float get() = current.crossfeedFeed
    override val crossfeedITD: Boolean get() = current.crossfeedItd
    override val crossfeedOutputPairMask: Int get() = current.crossfeedOutputPairMask
    override val crossfeedPairMap: List<Int> get() = current.crossfeedPairMap
    override val cpu0: Int get() = _status.value.cpu0
    override val cpu1: Int get() = _status.value.cpu1

    /** Re-keyed by app id (Qt clipFlags()); [BridgeStatus.clipFlags] is per wire. */
    override val clipFlags: Long
        get() {
            var flags = 0L
            val m = map
            for (w in 0 until current.numChannels) {
                val id = m.appId(w)
                if (id >= 0 && (_status.value.clipFlags shr w) and 1L == 1L) flags = flags or (1L shl id)
            }
            return flags
        }

    override val activeInputChannels: Int get() = _status.value.activeInputChannels
    override val activePresetSlot: Int get() = current.activePresetSlot
    override val presetOccupied: Int get() = current.presetOccupied
    override val presetStartupMode: Int get() = current.presetStartupMode
    override val presetDefaultSlot: Int get() = current.presetDefaultSlot
    override val outputConfigMode: Int get() = current.outputConfigMode
    override val inputSource: Int get() = current.inputSource

    override val inputSources: List<InputSourceOption>
        get() {
            // Firmware InputSource: 0 USB, 1 S/PDIF 1, 2 I2S, 3 ADAT, 4-6 S/PDIF 2-4.
            val s = current
            val extraSpdif = (s.spdifInputsEnabled and 1.inv()) != 0
            val list = mutableListOf(InputSourceOption(0, "USB"), InputSourceOption(1, if (extraSpdif) "S/PDIF 1" else "S/PDIF"))
            for (k in 1 until 4) if (s.spdifInputsEnabled and (1 shl k) != 0) list += InputSourceOption(3 + k, "S/PDIF ${k + 1}")
            list += InputSourceOption(2, "I2S")
            if (s.platformId == 1 && s.adatInputEnabled && s.adatInputPin != 0xFF) list += InputSourceOption(3, "ADAT")
            return list
        }

    override val core1Mode: Int get() = current.core1Mode
    override val levellerEnabled: Boolean get() = current.levellerEnabled
    override val levellerAmount: Float get() = current.levellerAmount
    override val levellerSpeed: Int get() = current.levellerSpeed
    override val levellerMaxGain: Float get() = current.levellerMaxGainDb
    override val levellerGate: Float get() = current.levellerGateDb
    override val levellerLookahead: Boolean get() = current.levellerLookahead
    override val levellerDetectorMask: Int get() = current.levellerDetectorMask
    override val levellerApplyMask: Int get() = current.levellerApplyMask
    override val psybassEnabled: Boolean get() = current.psybassEnabled
    override val psybassCutoff: Float get() = current.psybassCutoffHz
    override val psybassHarmonics: Float get() = current.psybassHarmonicsDb
    override val psybassDrive: Float get() = current.psybassDriveDb
    override val psybassCharacter: Float get() = current.psybassCharacterPct
    override val psybassOriginal: Float get() = current.psybassOriginalDb
    override val psybassOutputMask: Int get() = current.psybassOutputMask
    override val upmixSupported: Boolean get() = usable && current.platformId == 1
    override val upmixParams: List<Float> get() = current.upmix
    override val subharmParams: List<Float> get() = current.subharm
    override val subharmSolo: Boolean get() = current.subharmSolo
    override val tubeParams: List<Float> get() = current.tube

    /** GPIOs a user can wire on a Pico (23-25 are internal), as on macOS. */
    override val validPins: List<Int> = (0..28).filter { it < 23 || it > 25 }

    /**
     * Qt `DSPiBridge::pinOwners` (labels, roles and order unchanged), each
     * entry tagged with its structured [PinOwnerKind] + index (macOS
     * `PinConsumer`, BRIDGE-3 / S15).
     */
    override fun pinOwners(): List<PinOwner> {
        val s = current
        val owners = mutableListOf<PinOwner>()
        fun add(pin: Int, owner: String, role: String, kind: PinOwnerKind, index: Int = -1) {
            if (pin in 0..47) owners += PinOwner(pin, owner, role, kind, index)
        }
        val n = s.numPinOutputs
        for (i in 0 until n) {
            if (i == n - 1) {
                add(s.outputPins[i], "Subwoofer", "output", PinOwnerKind.SUBWOOFER, i)
            } else {
                add(s.outputPins[i], "Output ${2 * i + 1}/${2 * i + 2}", "output", PinOwnerKind.OUTPUT_SLOT, i)
            }
        }
        if (s.adatOutEnabled) add(s.adatOutPin, "ADAT Output", "output", PinOwnerKind.ADAT_OUT)
        // The bit clock pair is always held for I2S, as on macOS
        add(s.i2sBckPin, "I2S BCK", "clock", PinOwnerKind.I2S_BCK)
        add(s.i2sBckPin + 1, "I2S LRCLK", "clock", PinOwnerKind.I2S_LRCLK)
        if (s.i2sClockPinMode == 1) {
            add(s.i2sBckPinSlave, "I2S Slave BCK", "clock", PinOwnerKind.I2S_SLAVE_BCK)
            add(s.i2sBckPinSlave + 1, "I2S Slave LRCLK", "clock", PinOwnerKind.I2S_SLAVE_LRCLK)
        }
        if (s.mckEnabled) add(s.mckPin, "I2S MCK", "clock", PinOwnerKind.MCK)
        for (k in 0 until 4) {
            if (s.spdifInputsEnabled and (1 shl k) != 0) {
                val label = if (s.spdifInputsEnabled and 1.inv() != 0) "S/PDIF RX ${k + 1}" else "S/PDIF RX"
                add(s.spdifRxPins[k], label, "input", PinOwnerKind.SPDIF_RX, k)
            }
        }
        for (p in 0 until minOf(4, s.i2sInputChannels / 2)) add(s.i2sRxPins[p], "I2S Data ${p + 1}", "input", PinOwnerKind.I2S_RX, p)
        if (s.adatInputEnabled && s.adatInputPin != 0xFF) add(s.adatInputPin, "ADAT Input", "input", PinOwnerKind.ADAT_IN)
        if (s.dacMuteEnabled && s.dacMutePin != 0xFF) add(s.dacMutePin, "DAC Mute", "other", PinOwnerKind.DAC_MUTE)
        if (s.uart.enabled) {
            add(s.uart.txPin, "UART TX", "control", PinOwnerKind.UART, 0)
            add(s.uart.rxPin, "UART RX", "control", PinOwnerKind.UART, 1)
        }
        if (s.i2c.enabled) {
            add(s.i2c.sdaPin, "I2C SDA", "control", PinOwnerKind.I2C, 0)
            add(s.i2c.sclPin, "I2C SCL", "control", PinOwnerKind.I2C, 1)
        }
        // Control surface bindings hold their pins while they're live (core
        // state, no USB: the core answers from its CS cache)
        for (u in csPinUses.take(32)) add(u.pin, "Control Surface ${u.slot + 1}", "control", PinOwnerKind.CONTROL_SURFACE, u.slot)
        return owners
    }

    /** Latest `cs_pin_uses`, refreshed with every CS model reload. */
    internal var csPinUses: List<se.bofr.dspi.core.PinUse> = emptyList()

    // ═══ Channel identity ═══

    override fun channelExists(ch: Int) = map.channelExists(ch)

    override fun isInputChannel(ch: Int) = map.isInputChannel(ch)

    override fun inputAppId(input: Int) = map.inputAppId(input)

    override fun peakLevel(ch: Int): Float = if (map.wire(ch) >= 0) _status.value.peaks.getOrElse(ch) { 0f } else 0f

    override fun isClipping(ch: Int): Boolean = map.wire(ch).let { it >= 0 && (_status.value.clipFlags shr it) and 1L == 1L }

    override fun channelName(ch: Int): String {
        val w = map.wire(ch)
        if (w < 0) return ""
        val name = current.channelNames.getOrElse(w) { "" }
        if (name.isNotEmpty()) return name
        val input = map.inputOf(ch)
        if (input >= 0) return if (input < 2) (if (input == 0) "USB L" else "USB R") else "Input ${input + 1}"
        val out = map.outputOf(ch)
        return if (isPdmOutput(out)) "PDM" else "Output ${out + 1}"
    }

    override fun channelColor(ch: Int): String {
        val input = map.inputOf(ch)
        if (input >= 0) return INPUT_COLORS[input]
        val out = map.outputOf(ch)
        if (out < 0) return "#FFFFFF"
        if (isPdmOutput(out)) return PDM_COLOR
        return OUTPUT_COLORS[out % (MAX_OUTPUTS - 1)]
    }

    override fun channelDescriptor(ch: Int): String {
        val input = map.inputOf(ch)
        if (input >= 0) return "IN${input + 1}"
        val out = map.outputOf(ch)
        return if (out >= 0) "OUT${out + 1}" else ""
    }

    // ═══ PEQ / crossover reads ═══

    private fun band(ch: Int, band: Int): FilterParams? {
        val w = map.wire(ch)
        return if (w < 0 || band !in 0 until BANDS_PER_CHANNEL) null else current.filters[w][band]
    }

    private fun xband(ch: Int, band: Int): FilterParams? =
        if (map.outputOf(ch) < 0 || band !in 0 until MAX_XOVER_BANDS) null else current.xover[map.wire(ch)][band]

    override fun filterType(ch: Int, band: Int) = band(ch, band)?.filterType ?: 0

    override fun filterFreq(ch: Int, band: Int) = band(ch, band)?.freq ?: 1000f

    override fun filterGain(ch: Int, band: Int) = band(ch, band)?.gain ?: 0f

    override fun filterQ(ch: Int, band: Int) = band(ch, band)?.q ?: 0.707f

    override fun filterQp(ch: Int, band: Int) = band(ch, band)?.qp ?: 0.707f

    override fun filterBypass(ch: Int, band: Int) = band(ch, band)?.bypass ?: false

    override fun crossoverType(ch: Int, band: Int) = xband(ch, band)?.filterType ?: 0

    override fun crossoverFreq(ch: Int, band: Int) = xband(ch, band)?.freq ?: 1000f

    override fun crossoverBypass(ch: Int, band: Int) = xband(ch, band)?.bypass ?: false

    // ═══ Inputs / outputs / matrix reads ═══

    override fun inputPreampDB(input: Int) = if (input in 0 until MAX_INPUTS) current.inputPreampDb[input] else 0f

    override fun channelDelayMS(ch: Int) = map.wire(ch).let { if (it >= 0) current.channelDelays[it] else 0f }

    override fun outputEnabled(idx: Int) = idx in 0 until MAX_OUTPUTS && current.outputEnabled[idx]

    override fun outputMuted(idx: Int) = idx in 0 until MAX_OUTPUTS && current.outputMuted[idx]

    override fun outputGainDB(idx: Int) = if (idx in 0 until MAX_OUTPUTS) current.outputGainDb[idx] else 0f

    override fun outputDelayMS(idx: Int) = if (idx in 0 until MAX_OUTPUTS) current.outputDelayMs[idx] else 0f

    override fun isPdmOutput(idx: Int) = idx >= 0 && idx == current.numOutputChannels - 1

    private fun inRange(input: Int, output: Int) = input in 0 until MAX_INPUTS && output in 0 until MAX_OUTPUTS

    override fun matrixRouting(input: Int, output: Int) = inRange(input, output) && current.matrixRouting[input][output]

    override fun matrixGain(input: Int, output: Int) = if (inRange(input, output)) current.matrixGain[input][output] else 0f

    override fun matrixInvert(input: Int, output: Int) = inRange(input, output) && current.matrixInvert[input][output]

    override fun outputPin(physOut: Int) = if (physOut in
        0 until DspiConstants.MAX_PHYSICAL_OUTPUTS
    ) {
        current.outputPins.getOrElse(physOut) { -1 }
    } else {
        -1
    }

    override fun numPinOutputs() = current.numPinOutputs

    override fun presetName(slot: Int) = if (slot in 0 until MAX_PRESETS) current.presetNames[slot] else ""

    override fun isPresetOccupied(slot: Int) = slot in 0 until MAX_PRESETS && (current.presetOccupied shr slot) and 1 == 1

    // ═══ Curves ═══

    override val curveFrequencies: List<Double> by lazy { List(DspiConstants.MAGNITUDE_POINTS) { core.curveFreq(it.toLong()) } }

    /**
     * The cached curve (recomputed on the FFI thread for the channels a change
     * touched) plus the channel's gain offset. A miss computes it here: the
     * curve functions are stateless free functions of the core (no USB).
     */
    override fun magnitudeCurve(ch: Int): List<Double> {
        if (ch !in 0 until ChannelMap.APP_CHANNEL_COUNT) return emptyList()
        val raw = magnitudeCache[ch] ?: rawCurve(current, map, ch).also { magnitudeCache[ch] = it }
        val offset = channelGainOffset(ch).toDouble()
        return if (offset == 0.0) raw else raw.map { it + offset }
    }

    override fun phaseCurve(ch: Int, unwrap: Boolean): List<Double> = core.computePhaseCurve(bandsOf(current, map, ch), unwrap)

    // ═══ Setters — global / volume ═══

    override fun setPreamp(db: Float) = fire("setPreamp", Emit.CURVES) { core.setPreamp(db) }

    /** No stateChanged: used during a drag to avoid a feedback loop. */
    override fun sendPreampToDevice(db: Float) = fire("sendPreampToDevice", Emit.NONE) { core.setPreamp(db) }

    // sendOnly: a live update during a drag. The device and the core's state
    // change, but stateChanged isn't emitted (it re-evaluates the whole UI);
    // the release commits with a normal call.
    override fun setInputPreamp(input: Int, db: Float, sendOnly: Boolean) {
        if (input !in 0 until current.numInputChannels) return
        // A linked pair gets one per-input write each (never the legacy
        // all-inputs preamp, which would clobber the other pairs).
        val partner = map.inputOf(linkedPartner(inputAppId(input)))
        fire("setInputPreamp", if (sendOnly) Emit.PREVIEW else Emit.CURVES) {
            core.setInputPreamp(input, db)
            if (partner >= 0) core.setInputPreamp(partner, db)
        }
    }

    override fun setBypass(en: Boolean) = fire("setBypass") { core.setBypass(en) }

    override fun setMasterVolume(db: Float, sendOnly: Boolean) = fire("setMasterVolume", live(sendOnly)) { core.setMasterVolume(db) }

    override fun setMasterVolumeMode(mode: Int) = fire("setMasterVolumeMode", flash = true) { core.setMasterVolumeMode(mode) }

    override suspend fun saveMasterVolume(token: ConfirmationToken): PresetStatus {
        if (writeRefused("saveMasterVolume", token, DangerousOp.SAVE_MASTER_VOLUME)) return PresetStatus.NO_REPLY
        requireToken(token, DangerousOp.SAVE_MASTER_VOLUME)
        val code = onFfi { statusOf(-1) { core.saveMasterVolume() }.also { flashWritten() } }
        return PresetStatus.of(code)
    }

    override fun setUserVolume(db: Float, sendOnly: Boolean) = fire("setUserVolume", live(sendOnly)) { core.setUserVolume(db) }

    override fun setUserMute(muted: Boolean) = fire("setUserMute") { core.setUserMute(muted) }

    private fun live(sendOnly: Boolean) = if (sendOnly) Emit.NONE else Emit.STATE

    // ═══ Setters — EQ ═══

    /** App ids [ch] and its linked partner, with their wire channels. */
    private fun targets(ch: Int): List<Pair<Int, Int>> = listOf(ch, linkedPartner(ch)).mapNotNull { a ->
        map.wire(a).takeIf { it >= 0 }?.let {
            a to
                it
        }
    }

    /** FFI: write one PEQ band to ch, and to its partner while the pair is linked. */
    private fun applyFilter(targets: List<Pair<Int, Int>>, band: Int, p: FilterParams) {
        for ((app, w) in targets) {
            core.setFilter(w, band, p)
            markDirty(app)
        }
    }

    override fun setFilter(ch: Int, band: Int, type: Int, freq: Float, gain: Float, q: Float) {
        val w = map.wire(ch)
        if (w < 0 || band !in 0 until BANDS_PER_CHANNEL) return
        val t = targets(ch)
        fire("setFilter", Emit.CURVES) {
            // Keep the band's bypass state and Linkwitz target Q across edits.
            var p = core.getState().filters[w][band]
            var g = gain
            if (type != p.filterType && type == FILTER_LINKWITZ_TRANSFORM) {
                // A new Linkwitz Transform starts with the target equal to the
                // driver, so it does nothing until fp or Qp changes.
                g = freq
                p = p.copy(qp = q)
            }
            applyFilter(t, band, p.copy(filterType = type, freq = freq, gain = g, q = q))
        }
    }

    override fun setLinkwitzTransform(ch: Int, band: Int, f0: Float, q0: Float, fp: Float, qp: Float) {
        val w = map.wire(ch)
        if (w < 0 || band !in 0 until BANDS_PER_CHANNEL) return
        val t = targets(ch)
        fire("setLinkwitzTransform", Emit.CURVES) {
            val p = core.getState().filters[w][band]
            applyFilter(t, band, p.copy(filterType = FILTER_LINKWITZ_TRANSFORM, freq = f0, q = q0, gain = fp, qp = qp))
        }
    }

    override fun setBandBypass(ch: Int, band: Int, bypass: Boolean) {
        if (map.wire(ch) < 0 || band !in 0 until BANDS_PER_CHANNEL) return
        val t = targets(ch)
        fire("setBandBypass", Emit.CURVES) {
            for ((app, tw) in t) {
                core.setBandBypass(tw, band, bypass)
                markDirty(app)
            }
        }
    }

    override fun setCrossover(ch: Int, band: Int, type: Int, freq: Float) {
        if (map.outputOf(ch) < 0 || band !in 0 until MAX_XOVER_BANDS) return
        val w = map.wire(ch)
        fire("setCrossover", Emit.CURVES) {
            val p = core.getState().xover[w][band]
            core.setCrossover(w, band, p.copy(filterType = type, freq = freq))
            markDirty(ch)
        }
    }

    override fun setCrossoverBypass(ch: Int, band: Int, bypass: Boolean) {
        if (map.outputOf(ch) < 0 || band !in 0 until MAX_XOVER_BANDS) return
        val w = map.wire(ch)
        fire("setCrossoverBypass", Emit.CURVES) {
            core.setBandBypass(w, XOVER_BAND_BASE + band, bypass)
            markDirty(ch)
        }
    }

    override fun setChannelDelay(ch: Int, ms: Float) {
        val w = map.wire(ch)
        if (w < 0) return
        fire("setChannelDelay") { core.setDelay(w, ms) }
    }

    private val off = FilterParams(filterType = FILTER_FLAT, bypass = false, freq = 1000f, q = 0.707f, gain = 0f, qp = 0.707f)

    override fun clearPeq(ch: Int) {
        val t = targets(ch)
        fire("clearPeq", Emit.CURVES) { for (b in 0 until BANDS_PER_CHANNEL) applyFilter(t, b, off) }
    }

    override fun clearAllBands(ch: Int, crossover: Boolean) {
        if (!crossover) return clearPeq(ch)
        if (map.outputOf(ch) < 0) return
        val w = map.wire(ch)
        fire("clearAllBands", Emit.CURVES) {
            for (b in 0 until MAX_XOVER_BANDS) core.setCrossover(w, b, off)
            markDirty(ch)
        }
    }

    override fun canSetAllBypass(ch: Int, bypass: Boolean, crossover: Boolean): Boolean {
        val w = map.wire(ch)
        if (w < 0 || (crossover && map.outputOf(ch) < 0)) return false
        val bands = if (crossover) current.xover[w].take(MAX_XOVER_BANDS) else current.filters[w].take(BANDS_PER_CHANNEL)
        return bands.any { it.filterType != FILTER_FLAT && it.bypass != bypass }
    }

    override fun setAllBandsBypass(ch: Int, bypass: Boolean, crossover: Boolean) {
        val w = map.wire(ch)
        if (w < 0 || (crossover && map.outputOf(ch) < 0)) return
        val t = targets(ch)
        fire("setAllBandsBypass", Emit.CURVES) {
            val s = core.getState()
            val n = if (crossover) MAX_XOVER_BANDS else BANDS_PER_CHANNEL
            for (b in 0 until n) {
                val f = if (crossover) s.xover[w][b] else s.filters[w][b]
                if (f.filterType == FILTER_FLAT || f.bypass == bypass) continue
                if (crossover) {
                    core.setBandBypass(w, XOVER_BAND_BASE + b, bypass)
                } else {
                    for ((app, tw) in t) {
                        core.setBandBypass(tw, b, bypass)
                        markDirty(app)
                    }
                }
            }
            markDirty(ch)
        }
    }

    // ═══ Setters — loudness / crossfeed ═══

    override fun setLoudness(en: Boolean) = fire("setLoudness") { core.setLoudness(en) }

    override fun setLoudnessRef(spl: Float, sendOnly: Boolean) = fire("setLoudnessRef", live(sendOnly)) { core.setLoudnessRef(spl) }

    override fun setLoudnessIntensity(pct: Float, sendOnly: Boolean) = fire("setLoudnessIntensity", live(sendOnly)) {
        core.setLoudnessIntensity(pct)
    }

    override fun setLoudnessOutputMask(mask: Int) = fire("setLoudnessOutputMask") { core.setLoudnessMask(mask and 0xFFFF) }

    override fun setCrossfeed(en: Boolean) = fire("setCrossfeed") { core.setCrossfeed(en) }

    override fun setCrossfeedPreset(p: Int) = fire("setCrossfeedPreset") { core.setCrossfeedPreset(p) }

    override fun setCrossfeedFreq(freq: Float, sendOnly: Boolean) = fire("setCrossfeedFreq", live(sendOnly)) { core.setCrossfeedFreq(freq) }

    override fun setCrossfeedFeed(feed: Float, sendOnly: Boolean) = fire("setCrossfeedFeed", live(sendOnly)) { core.setCrossfeedFeed(feed) }

    override fun setCrossfeedITD(en: Boolean) = fire("setCrossfeedITD") { core.setCrossfeedItd(en) }

    override fun setCrossfeedOutputPairMask(mask: Int) = fire("setCrossfeedOutputPairMask") { core.setCrossfeedOutputs(mask and 0xFF) }

    /**
     * Atomic: the read-modify-write runs on the serialized FFI thread from the
     * core's own state, which already holds every earlier queued write (T1).
     */
    override fun toggleMaskBit(field: MaskField, bit: Int, whenUnreported: Int) {
        if (bit !in 0 until 32) return
        val flip = 1 shl bit
        fire("toggleMaskBit") {
            val st = core.getState()
            fun param(list: List<Float>, id: Int) = list.getOrNull(id)?.toInt() ?: whenUnreported
            when (field) {
                MaskField.LOUDNESS_OUTPUTS -> core.setLoudnessMask((st.loudnessOutputMask xor flip) and 0xFFFF)
                MaskField.CROSSFEED_PAIRS -> core.setCrossfeedOutputs((st.crossfeedOutputPairMask xor flip) and 0xFF)
                MaskField.PSYBASS_OUTPUTS -> core.setPsybassMask(st.psybassOutputMask xor flip)
                MaskField.LEVELLER_DETECTOR -> core.setLevellerMasks(st.levellerDetectorMask xor flip, st.levellerApplyMask)
                MaskField.LEVELLER_APPLY -> core.setLevellerMasks(st.levellerDetectorMask, st.levellerApplyMask xor flip)
                MaskField.SUBHARM_OUTPUTS ->
                    core.setSubharmParam(
                        DspiConstants.SUBHARM_PARAM_MASK,
                        (param(st.subharm, DspiConstants.SUBHARM_PARAM_MASK) xor flip).toFloat()
                    )
                MaskField.TUBE_OUTPUTS ->
                    core.setTubeParam(DspiConstants.TUBE_PARAM_MASK, (param(st.tube, DspiConstants.TUBE_PARAM_MASK) xor flip).toFloat())
            }
        }
    }

    override fun setCrossfeedPairMap(map: List<Int>) {
        val name = "setCrossfeedPairMap"
        if (writeRefused(name)) return
        if (!crossfeedPairMapSupported) return emit(BridgeEvent.CommandFailed(name, DspiException.Unsupported()))
        val copy = map.toList() // the caller's list may change before the FFI thread runs
        fire(name) { core.setCrossfeedPairMap(copy) }
    }

    override fun crossfeedPairMapValid(map: List<Int>): Boolean =
        usable && runCatching { core.crossfeedPairMapValid(map, CrossfeedPairs.pairCount(current.numOutputChannels)) }.getOrDefault(false)

    // ═══ Setters — matrix / outputs ═══

    override fun setMatrixRoute(input: Int, output: Int, enabled: Boolean, gain: Float, invert: Boolean, sendOnly: Boolean) {
        if (input !in 0 until current.numInputChannels) return
        fire("setMatrixRoute", live(sendOnly)) { core.setMatrixRoute(input, output, enabled, gain, invert) }
    }

    /** Atomic against queued writes, as [toggleMaskBit] (T2). */
    override fun toggleMatrixRoute(input: Int, output: Int, polarity: Boolean) {
        if (!inRange(input, output) || input !in 0 until current.numInputChannels) return
        fire("toggleMatrixRoute") {
            val st = core.getState()
            val on = st.matrixRouting.getOrNull(input)?.getOrNull(output) ?: return@fire
            val inv = st.matrixInvert.getOrNull(input)?.getOrNull(output) ?: return@fire
            val gain = st.matrixGain.getOrNull(input)?.getOrNull(output) ?: return@fire
            core.setMatrixRoute(input, output, if (polarity) on else !on, gain, if (polarity) !inv else inv)
        }
    }

    /** Atomic against queued writes, as [toggleMatrixRoute]; emits as [setMatrixRoute] (review-stalestate #2). */
    override fun setMatrixGain(input: Int, output: Int, gain: Float, sendOnly: Boolean) {
        if (!inRange(input, output) || input !in 0 until current.numInputChannels) return
        fire("setMatrixGain", live(sendOnly)) {
            val st = core.getState()
            val on = st.matrixRouting.getOrNull(input)?.getOrNull(output) ?: return@fire
            val inv = st.matrixInvert.getOrNull(input)?.getOrNull(output) ?: return@fire
            core.setMatrixRoute(input, output, on, gain, inv)
        }
    }

    override suspend fun setOutputEnable(output: Int, enabled: Boolean): Boolean {
        if (writeRefused("setOutputEnable")) return false
        val (result, snap) =
            onFfi {
                val r = runCatching { core.setOutputEnable(output, enabled) }
                r to snapshot()
            }
        publish(snap, magnitudes = true)
        result.exceptionOrNull()?.let { emit(BridgeEvent.CommandFailed("setOutputEnable", it)) }
        return result.getOrNull() == enabled
    }

    override fun setOutputGain(output: Int, db: Float) = fire("setOutputGain", Emit.CURVES) { core.setOutputGain(output, db) }

    override fun sendOutputGainToDevice(output: Int, db: Float) = fire("sendOutputGainToDevice", Emit.PREVIEW) {
        core.setOutputGain(output, db)
    }

    override fun setOutputMute(output: Int, muted: Boolean) = fire("setOutputMute") { core.setOutputMute(output, muted) }

    override fun setOutputDelay(output: Int, ms: Float) = fire("setOutputDelay") { core.setOutputDelay(output, ms) }

    override fun sendOutputDelayToDevice(output: Int, ms: Float) = fire("sendOutputDelayToDevice", Emit.NONE) {
        core.setOutputDelay(output, ms)
    }

    /**
     * macOS `numMatrixInputs` = `effectiveInputChannels`: the input rows the
     * current source carries (S/PDIF 2, I2S its channel count, ADAT 8, USB the
     * host's active channels), at least 2, at most the device's inputs.
     */
    override val effectiveInputChannels: Int
        get() {
            val s = current
            val base =
                when (s.inputSource) {
                    1, 4, 5, 6 -> 2
                    2 -> s.i2sInputChannels
                    3 -> 8
                    else -> _status.value.activeInputChannels
                }
            return maxOf(2, minOf(base, s.numInputChannels))
        }

    private fun rowsOr(rows: Int) = if (rows < 0) effectiveInputChannels else minOf(rows, current.numInputChannels)

    /**
     * macOS `applyDirectRouting` (E5): input i -> output i at 0 dB for
     * i < n = min(rows, outputs), every other crosspoint of the [rows] input
     * rows reset to off / 0 dB / normal polarity, the diagonal outputs enabled
     * and the PDM sub disabled first (it shares Core 1 with the EQ workers).
     * As macOS `setOutputEnable` in overview mode, the outputs it switches
     * show/hide their curves when [overview].
     */
    override fun directRouting() = directRouting(-1, true)

    override fun clearRouting() = clearRouting(-1)

    override fun directRouting(rows: Int, overview: Boolean) {
        if (writeRefused("directRouting")) return
        val r = rowsOr(rows)
        val nOut = current.numOutputChannels
        val n = minOf(r, nOut)
        val pdm = nOut - 1
        val before = current.outputEnabled
        fire("directRouting", Emit.CURVES) {
            val s = core.getState()
            if (pdm >= 0 && s.outputEnabled[pdm]) core.setOutputEnable(pdm, false)
            for (i in 0 until r) {
                for (o in 0 until nOut) {
                    val on = i == o && i < n
                    if (s.matrixRouting[i][o] != on ||
                        s.matrixGain[i][o] != 0f ||
                        s.matrixInvert[i][o]
                    ) {
                        core.setMatrixRoute(i, o, on, 0f, false)
                    }
                }
            }
            for (o in 0 until n) if (o != pdm && !s.outputEnabled[o]) core.setOutputEnable(o, true)
        }
        if (overview) {
            if (pdm >= 0 && before[pdm]) setChannelVisible(pdm + 2, false) // output app id = output + 2
            for (o in 0 until n) if (o != pdm && !before[o]) setChannelVisible(o + 2, true)
        }
    }

    /** macOS `clearAllRoutes`: disconnect every crosspoint of the [rows] input rows, gains/polarity untouched. */
    override fun clearRouting(rows: Int) {
        val r = rowsOr(rows)
        fire("clearRouting") {
            val s = core.getState()
            for (i in 0 until r) {
                for (o in 0 until s.numOutputChannels) {
                    if (s.matrixRouting[i][o]) core.setMatrixRoute(i, o, false, s.matrixGain[i][o], s.matrixInvert[i][o])
                }
            }
        }
    }

    /** Core 1 runs either PDM or the EQ worker for outputs 2..(n-2) (RP2350: OUT3-8, RP2040: OUT3-4); never both. */
    override fun core1ConflictOutputs(output: Int): List<Int> {
        val s = current
        val n = s.numOutputChannels
        if (output !in 0 until n || s.outputEnabled[output]) return emptyList()
        return when {
            isPdmOutput(output) -> (2 until n - 1).filter { s.outputEnabled[it] }
            output >= 2 && s.outputEnabled[n - 1] -> listOf(n - 1)
            else -> emptyList()
        }
    }

    override fun enableOutputResolvingConflict(output: Int) {
        val off = core1ConflictOutputs(output)
        fire("enableOutputResolvingConflict", Emit.CURVES) {
            for (o in off) core.setOutputEnable(o, false)
            core.setOutputEnable(output, true)
        }
    }

    override suspend fun checkCore1Conflict(output: Int): Boolean = onFfi {
        runCatching { get { core.checkCore1Conflict(output) } }.getOrDefault(false)
    }

    // ═══ Pins / output config / names ═══

    /** In independent mode a hardware edit is RAM-only until Save Output Config. */
    private fun hardwareEdited(status: PinConfigStatus, snap: Snapshot): PinConfigStatus {
        if (status == PinConfigStatus.SUCCESS && snap.state.outputConfigMode == 0) _hardwareUnsaved.value = true
        publish(snap)
        return status
    }

    /** A GATED [DangerousOp.HARDWARE_APPLY] call (D2); arms the retry blackout (pipeline rebuild). */
    private suspend fun pinCall(token: ConfirmationToken, name: String, block: () -> Unit): PinConfigStatus {
        if (writeRefused(name, token, DangerousOp.HARDWARE_APPLY)) return PinConfigStatus.NO_REPLY
        requireToken(token, DangerousOp.HARDWARE_APPLY)
        return hardwareCall(block)
    }

    /** [pinCall] after the token check (the fire-and-forget forms check it before launching). */
    private suspend fun hardwareCall(block: () -> Unit): PinConfigStatus {
        val (st, snap) =
            statusCall(PinConfigStatus.NO_REPLY.code, { PinConfigStatus.of(it) }) {
                try {
                    block()
                } finally {
                    flashWritten()
                }
            }
        return hardwareEdited(st, snap)
    }

    override suspend fun setOutputPin(token: ConfirmationToken, output: Int, pin: Int) = pinCall(token, "setOutputPin") {
        core.setOutputPin(output, pin)
    }

    override fun setOutputConfigMode(mode: Int) {
        if (writeRefused("setOutputConfigMode")) return
        if (mode != 0) _hardwareUnsaved.value = false // saved with the preset instead
        clearOutputEdit() // macOS: the Save that changes the mode clears outputConfigDirty
        fire("setOutputConfigMode", flash = true) { core.setOutputConfigMode(mode) }
    }

    override suspend fun saveOutputConfig(token: ConfirmationToken): PresetStatus {
        if (writeRefused("saveOutputConfig", token, DangerousOp.SAVE_OUTPUT_CONFIG)) return PresetStatus.NO_REPLY
        requireToken(token, DangerousOp.SAVE_OUTPUT_CONFIG)
        val code = onFfi { statusOf(-1) { core.saveOutputConfig() }.also { flashWritten() } }
        val st = PresetStatus.of(code)
        if (st == PresetStatus.OK) clearOutputEdit()
        if (st == PresetStatus.OK && _hardwareUnsaved.value) {
            _hardwareUnsaved.value = false
            publish(onFfi { snapshot() })
        }
        return st
    }

    override fun setChannelName(ch: Int, name: String) {
        val w = map.wire(ch)
        if (w < 0) return
        fire("setChannelName") { core.setChannelName(w, name) }
    }

    override fun resetChannelNames() {
        val nIn = current.numInputChannels
        val nOut = current.numOutputChannels
        fire("resetChannelNames") {
            // Stereo USB input: USB L / USB R; multichannel: USB 1..8
            for (i in 0 until nIn) core.setChannelName(i, if (nIn == 2) (if (i == 0) "USB L" else "USB R") else "USB ${i + 1}")
            for (o in 0 until nOut) {
                core.setChannelName(nIn + o, if (o == nOut - 1) "PDM" else "SPDIF ${o / 2 + 1} ${if (o % 2 == 1) "R" else "L"}")
            }
        }
    }

    // ═══ Presets / flash ═══

    override suspend fun savePreset(token: ConfirmationToken, slot: Int): PresetStatus {
        if (writeRefused("savePreset", token, DangerousOp.SAVE_PRESET)) return PresetStatus.NO_REPLY
        requireToken(token, DangerousOp.SAVE_PRESET)
        val (st, snap) =
            onFfi {
                val code = statusOf(-1) { core.savePreset(slot) }
                flashWritten()
                if (code == DspiConstants.PRESET_OK && slot == core.getState().activePresetSlot) core.captureBaseline()
                PresetStatus.of(code) to snapshot()
            }
        publish(snap)
        return st
    }

    /** Ungated (D1); arms the retry blackout (deferred load, commands.md §1.3). */
    override suspend fun loadPreset(slot: Int): PresetStatus {
        if (writeRefused("loadPreset")) return PresetStatus.NO_REPLY
        val (st, snap) =
            onFfi {
                val code = statusOf(-1) { core.loadPreset(slot) }
                flashWritten()
                if (code == DspiConstants.PRESET_OK) {
                    core.captureBaseline()
                    PresetStatus.of(code) to snapshot(all = true)
                } else {
                    PresetStatus.of(code) to null
                }
            }
        snap?.let { publish(it, magnitudes = true) }
        return st
    }

    override suspend fun deletePreset(token: ConfirmationToken, slot: Int): PresetStatus {
        if (writeRefused("deletePreset", token, DangerousOp.DELETE_PRESET)) return PresetStatus.NO_REPLY
        requireToken(token, DangerousOp.DELETE_PRESET)
        val (st, snap) =
            onFfi {
                val code = statusOf(-1) { core.deletePreset(slot) }
                flashWritten()
                PresetStatus.of(code) to snapshot(all = true)
            }
        publish(snap, magnitudes = true)
        return st
    }

    override suspend fun copyPreset(token: ConfirmationToken, fromSlot: Int, toSlot: Int): PresetStatus {
        if (writeRefused("copyPreset", token, DangerousOp.COPY_PRESET)) return PresetStatus.NO_REPLY
        requireToken(token, DangerousOp.COPY_PRESET)
        val (st, snap) =
            onFfi {
                val code = statusOf(-1) { core.copyPreset(fromSlot, toSlot) }
                flashWritten()
                PresetStatus.of(code) to snapshot()
            }
        publish(snap)
        return st
    }

    override fun setPresetName(slot: Int, name: String) = fire("setPresetName", flash = true) { core.setPresetName(slot, name) }

    override fun setPresetStartup(mode: Int, slot: Int) = fire("setPresetStartup", flash = true) { core.setPresetStartup(mode, slot) }

    /** What differs from the active preset, one sentence each. */
    override suspend fun presetChanges(): List<String> = onFfi { runCatching { core.presetChanges() }.getOrDefault(emptyList()) }

    /** [token]/[op] null = ungated (loadParams, D1). Every one arms the retry blackout. */
    private suspend fun flashCall(
        name: String,
        token: ConfirmationToken?,
        op: DangerousOp?,
        refresh: Boolean,
        block: () -> Unit
    ): FlashStatus {
        if (writeRefused(name, token, op)) return FlashStatus.ERR_WRITE
        if (token != null && op != null) requireToken(token, op)
        val (st, snap) =
            onFfi {
                val code = statusOf(DspiConstants.FLASH_ERR_WRITE) { block() }
                flashWritten()
                if (code == DspiConstants.FLASH_OK) {
                    // The new starting point for change tracking
                    core.captureBaseline()
                    FlashStatus.of(code) to snapshot(all = refresh)
                } else {
                    FlashStatus.of(code) to null
                }
            }
        snap?.let { publish(it, magnitudes = refresh) }
        return st
    }

    override suspend fun saveParams(token: ConfirmationToken) = flashCall("saveParams", token, DangerousOp.SAVE_PARAMS, false) {
        core.saveParams()
    }

    override suspend fun loadParams() = flashCall("loadParams", null, null, true) { core.loadParams() }

    override suspend fun factoryReset(token: ConfirmationToken) = flashCall("factoryReset", token, DangerousOp.FACTORY_RESET, true) {
        core.factoryReset()
    }

    override fun enterBootloader(token: ConfirmationToken) {
        requireToken(token, DangerousOp.ENTER_BOOTLOADER)
        // DSPi Link: the board would reboot into BOOTSEL on the hub's USB, out
        // of this phone's reach (a firmware install through the hub, fw.install,
        // is not implemented): refuse before any traffic
        if (attachedRemote != null) {
            emit(BridgeEvent.CommandFailed("enterBootloader", WritesNotAllowedException(WritesNotAllowedException.REMOTE_BOOTLOADER)))
            return
        }
        // G1: allowed while read-only (the firmware-update path)
        fire("enterBootloader", Emit.NONE, write = false) { core.enterBootloader() }
    }

    override fun clearClips() {
        if (writeRefused("clearClips")) return
        scope.launch(ffi) {
            runCatching { core.clearClips() }
            lastClipMs.fill(0)
            post { _status.value = _status.value.copy(clipFlags = 0) }
        }
    }

    // ═══ Devices ═══

    override fun scanDevices() {
        scope.launch(ffi) {
            val serials = runCatching { core.scanDevices().map { it.serial } }.getOrDefault(emptyList())
            val inaccessible = runCatching { core.inaccessibleDevices().toInt() }.getOrDefault(0)
            post {
                // Android: libusb sees nothing; keep the device the USB layer attached
                val all = if (connected && selectedSerial.isNotEmpty() && selectedSerial !in serials) serials + selectedSerial else serials
                val d = _devices.value
                if (all != d.availableSerials || inaccessible != d.inaccessibleDevices) {
                    _devices.value = d.copy(availableSerials = all, inaccessibleDevices = inaccessible)
                }
                // Qt: `!connected()`; also when nothing is selected yet (a core
                // that starts connected, like the fake in demo mode)
                if ((!connected || selectedSerial.isEmpty()) && all.isNotEmpty()) selectDevice(all.first())
            }
        }
    }

    override fun selectDevice(serial: String) {
        scope.launch(main) {
            val ok = onFfi { runCatching { core.selectDevice(serial) }.isSuccess }
            if (!ok) {
                _status.value =
                    _status.value.copy(
                        connectionError = "Could not open DSPi ${serial.takeLast(
                            8
                        )}. Check that no other app is using it, then reconnect it."
                    )
                return@launch
            }
            deviceSelected(serial)
        }
    }

    /** DSPiBridge::selectDevice after a successful open: read everything, baseline, RTA probe. */
    private suspend fun deviceSelected(serial: String) {
        bumpGeneration()
        links = linkStore.load(serial)
        // macOS noteSelectedDevice: a different unit discards pending output edits; a re-plug keeps them
        if (serial != outputEditSerial) {
            if (outputEditSerial.isNotEmpty()) clearOutputEdit()
            outputEditSerial = serial
        }
        class Opened(val snap: Snapshot, val error: String, val pairMap: Boolean)
        val r =
            onFfi {
                lastClipMs.fill(0)
                pendingDirty.clear()
                val fetched = runCatching { get { core.fetchAll() } }.isSuccess
                val st = core.getState()
                val compatOk = core.isConnected() && st.compat == COMPAT_OK
                if (fetched && compatOk) core.captureBaseline()
                if (compatOk) runCatching { core.rtaStart() } // probes for the spectrum analyser
                // K340 fork probe (core patch 0002): upstream firmware STALLs 0x00
                // (Transfer: the answer, no retry); any other error retries once.
                val pairMap = fetched && compatOk && probeCrossfeedPairMap()
                Opened(
                    snapshot(all = true),
                    if (fetched) "" else "Connected, but the device's settings could not be read. Reconnect it to retry.",
                    pairMap
                )
            }
        crossfeedPairMapSupported = r.pairMap
        _hardwareUnsaved.value = false
        _status.value = BridgeStatus(connected = true, connectionError = r.error)
        val d = _devices.value
        _devices.value =
            d.copy(
                selectedSerial = serial,
                availableSerials = if (serial in
                    d.availableSerials
                ) {
                    d.availableSerials
                } else {
                    d.availableSerials + serial
                }
            )
        publish(r.snap, magnitudes = true)
        // Show the first input pair and every enabled output on the graph
        val m = map
        for (ch in 0 until ChannelMap.APP_CHANNEL_COUNT) {
            val out = m.outputOf(ch)
            channelVisibleFlags[ch] = ch < 2 || (out >= 0 && current.outputEnabled[out])
        }
        _magnitudesRevision.value += 1
        updateTimers()
        deviceChangedHooks.forEach { it() }
    }

    private fun setDisconnected() {
        bumpGeneration()
        crossfeedPairMapSupported = false
        _status.value = _status.value.copy(connected = false, cpu0 = 0, cpu1 = 0, clipFlags = 0, peaks = BridgeStatus().peaks)
        refreshWritesAllowed()
        _devices.value = _devices.value.copy(selectedSerial = "")
        magnitudeCache.fill(null)
        _stateRevision.value += 1
        _magnitudesRevision.value += 1
        if (_presetDirty.value) _presetDirty.value = false
        updateTimers()
    }

    override fun disconnectDevice() {
        scope.launch(main) {
            onFfi { runCatching { core.disconnect() } }
            attachedFd = null
            attachedRemote = null
            setDisconnected()
            deviceChangedHooks.forEach { it() }
        }
    }

    /** Re-open the same device: by fd on Android (the USB layer keeps the connection), by serial on desktop. */
    override fun reconnect() {
        val serial = selectedSerial
        if (serial.isEmpty()) return
        val fd = attachedFd
        val remote = attachedRemote
        scope.launch(main) {
            if (remote != null) {
                onFfi { runCatching { core.closeFd() } }
                setDisconnected()
                attachRemote(remote.first, remote.second)
            } else if (fd != null) {
                onFfi { runCatching { core.closeFd() } }
                setDisconnected()
                attachDevice(fd.first, fd.second, fd.third)
            } else {
                onFfi { runCatching { core.disconnect() } }
                setDisconnected()
                selectDevice(serial)
            }
        }
    }

    override fun rescan() {
        if (attachedFd != null || attachedRemote != null) return reconnect()
        val previous = selectedSerial
        scope.launch(main) {
            if (connected) {
                onFfi { runCatching { core.disconnect() } }
                setDisconnected()
            }
            val serials = onFfi { runCatching { core.scanDevices().map { it.serial } }.getOrDefault(emptyList()) }
            _devices.value = _devices.value.copy(availableSerials = serials)
            when {
                previous in serials -> selectDevice(previous)
                serials.isNotEmpty() -> selectDevice(serials.first())
            }
        }
    }

    override suspend fun attachDevice(fd: Int, serial: String, vid: Int): Boolean {
        val err = onFfi { runCatching { core.openFd(fd, serial, vid) }.exceptionOrNull() }
        if (err != null) {
            _status.value =
                _status.value.copy(
                    connected = false,
                    connectionError = "Could not open DSPi ${serial.takeLast(8)}. Unplug it and plug it in again."
                )
            refreshWritesAllowed()
            emit(BridgeEvent.CommandFailed("attachDevice", err))
            return false
        }
        attachedFd = Triple(fd, serial, vid)
        deviceSelected(serial)
        return true
    }

    override suspend fun attachRemote(handle: Long, serial: String): Boolean {
        val err = onFfi { runCatching { core.linkSelectDevice(handle, serial) }.exceptionOrNull() }
        if (err != null) {
            _status.value = _status.value.copy(connected = false, connectionError = "Could not open the DSPi on the hub.")
            refreshWritesAllowed()
            emit(BridgeEvent.CommandFailed("attachRemote", err))
            return false
        }
        attachedRemote = handle to serial
        deviceSelected(serial)
        return true
    }

    override suspend fun detachDevice(serial: String) {
        val mine = attachedFd?.second == serial || attachedRemote?.second == serial
        if (selectedSerial == serial || (selectedSerial.isEmpty() && mine)) {
            onFfi { runCatching { core.closeFd() } }
            attachedFd = null
            attachedRemote = null
            setDisconnected()
        }
        _devices.value = _devices.value.copy(availableSerials = _devices.value.availableSerials - serial)
        emit(BridgeEvent.DeviceDeparted(serial))
        deviceChangedHooks.forEach { it() }
    }

    // ═══ Input source / tools ═══

    /** GATED [DangerousOp.HARDWARE_APPLY]: rebuilds the input pipeline (arms the retry blackout). */
    override fun setInputSource(token: ConfirmationToken, source: Int) {
        if (writeRefused("setInputSource", token, DangerousOp.HARDWARE_APPLY)) return
        requireToken(token, DangerousOp.HARDWARE_APPLY)
        fire("setInputSource", flash = true) { core.setInputSource(source) }
    }

    override fun setLevellerEnabled(en: Boolean) = fire("setLevellerEnabled") { core.setLevellerEnabled(en) }

    override fun setLevellerAmount(pct: Float, sendOnly: Boolean) = fire("setLevellerAmount", live(sendOnly)) {
        core.setLevellerAmount(pct)
    }

    override fun setLevellerSpeed(speed: Int) = fire("setLevellerSpeed") { core.setLevellerSpeed(speed) }

    override fun setLevellerMaxGain(db: Float, sendOnly: Boolean) = fire("setLevellerMaxGain", live(sendOnly)) {
        core.setLevellerMaxGain(db)
    }

    override fun setLevellerGate(db: Float, sendOnly: Boolean) = fire("setLevellerGate", live(sendOnly)) { core.setLevellerGate(db) }

    override fun setLevellerLookahead(en: Boolean) = fire("setLevellerLookahead") { core.setLevellerLookahead(en) }

    override fun setLevellerMasks(detector: Int, apply: Int) = fire("setLevellerMasks") { core.setLevellerMasks(detector, apply) }

    override fun setPsybassEnabled(en: Boolean) = fire("setPsybassEnabled") { core.setPsybassEnabled(en) }

    override fun setPsybassParam(param: Int, value: Float, sendOnly: Boolean) = fire("setPsybassParam", live(sendOnly)) {
        core.setPsybassParam(param, value)
    }

    override fun setPsybassOutputMask(mask: Int) = fire("setPsybassOutputMask") { core.setPsybassMask(mask) }

    override fun setUpmixParam(id: Int, value: Float, sendOnly: Boolean) = fire("setUpmixParam", live(sendOnly)) {
        core.setUpmixParam(id, value)
    }

    override fun setSubharmParam(id: Int, value: Float, sendOnly: Boolean) = fire("setSubharmParam", live(sendOnly)) {
        core.setSubharmParam(id, value)
    }

    override fun setSubharmSolo(solo: Boolean) = fire("setSubharmSolo") { core.setSubharmSolo(solo) }

    override fun setTubeParam(idx: Int, value: Float, sendOnly: Boolean) = fire("setTubeParam", live(sendOnly)) {
        core.setTubeParam(idx, value)
    }

    override suspend fun fetchUpmixStatus(): UpmixStatus? = if (!upmixSupported) {
        null
    } else {
        onFfi {
            runCatching { get { core.fetchUpmixStatus() } }.getOrNull()
        }
    }

    override suspend fun fetchSubharmHeadroom(): Float? = if (!usable) {
        null
    } else {
        onFfi {
            runCatching { get { core.fetchSubharmHeadroom() } }.getOrNull()
        }
    }

    override suspend fun fetchSubharmMeter(): List<Float> {
        if (!usable) return emptyList()
        val n = current.numOutputChannels
        return onFfi { runCatching { get { core.fetchSubharmMeter() }.take(n) }.getOrDefault(emptyList()) }
    }

    // ═══ Output limiter ═══

    private fun validOut(out: Int) = out in 0 until current.numOutputChannels

    override fun limiterEnabled(out: Int) = validOut(out) && current.limiterEnabled[out]

    override fun limiterThreshold(out: Int) = if (validOut(out)) current.limiterThresholdDb[out] else -1f

    override fun limiterRelease(out: Int) = if (validOut(out)) current.limiterReleaseMs[out] else 100f

    override fun limiterLinkGroup(out: Int) = if (validOut(out)) current.limiterLinkGroup[out] else 0

    override fun limiterReduction(out: Int) = if (validOut(out)) _status.value.limiterReduction.getOrElse(out) { 0f } else 0f

    override fun setLimiterEnabled(out: Int, en: Boolean) {
        if (!validOut(out)) return
        fire("setLimiterEnabled") { limiterSetGanged(out, DspiConstants.LIMITER_PARAM_ENABLED, if (en) 1f else 0f) }
    }

    override fun setLimiterThreshold(out: Int, db: Float, sendOnly: Boolean) {
        if (!validOut(out)) return
        // sendOnly: device only (macOS sendLimiterParamToDevice); the commit gangs
        fire("setLimiterThreshold", live(sendOnly)) {
            if (sendOnly) {
                core.setLimiterParam(
                    out,
                    DspiConstants.LIMITER_PARAM_THRESHOLD_DB,
                    db
                )
            } else {
                limiterSetGanged(out, DspiConstants.LIMITER_PARAM_THRESHOLD_DB, db)
            }
        }
    }

    override fun setLimiterRelease(out: Int, ms: Float, sendOnly: Boolean) {
        if (!validOut(out)) return
        fire("setLimiterRelease", live(sendOnly)) {
            if (sendOnly) {
                core.setLimiterParam(
                    out,
                    DspiConstants.LIMITER_PARAM_RELEASE_MS,
                    ms
                )
            } else {
                limiterSetGanged(out, DspiConstants.LIMITER_PARAM_RELEASE_MS, ms)
            }
        }
    }

    override fun setLimiterLinkGroup(out: Int, group: Int) {
        if (!validOut(out)) return
        fire("setLimiterLinkGroup") { limiterSetGroup(out, group) }
    }

    override fun copyLimiterToAll(out: Int) {
        if (!validOut(out)) return
        val s = current
        val en = if (s.limiterEnabled[out]) 1f else 0f
        val th = s.limiterThresholdDb[out]
        val rel = s.limiterReleaseMs[out]
        fire("copyLimiterToAll") {
            core.setLimiterParam(LIMITER_ALL_OUTPUTS, DspiConstants.LIMITER_PARAM_THRESHOLD_DB, th)
            core.setLimiterParam(LIMITER_ALL_OUTPUTS, DspiConstants.LIMITER_PARAM_RELEASE_MS, rel)
            core.setLimiterParam(LIMITER_ALL_OUTPUTS, DspiConstants.LIMITER_PARAM_ENABLED, en)
        }
    }

    /** Outputs 1+2 in group 1, 3+4 in group 2, ...; PDM stays unlinked. */
    override fun linkAllLimiterPairs() {
        val n = current.numOutputChannels
        fire("linkAllLimiterPairs") {
            // In ascending order, so each pair's first output leads (as the firmware does)
            for (o in 0 until n) limiterSetGroup(o, if (o == n - 1) 0 else minOf(4, o / 2 + 1))
        }
    }

    /**
     * FFI: an enable/threshold/release SET as the firmware gangs it (limiter
     * spec §2.2, macOS `LimiterGang.edit`): it reaches every output of the
     * link group. The core keeps per-output state and does not mirror the
     * ganging, so the bridge repeats the SET for each other member — the same
     * value the device already applied to them (idempotent), which keeps the
     * core's state (and its preset diff / export) equal to the device's.
     */
    private fun limiterSetGanged(out: Int, param: Int, value: Float) {
        core.setLimiterParam(out, param, value)
        val s = core.getState()
        val g = s.limiterLinkGroup[out]
        if (g == 0) return
        for (k in 0 until minOf(s.numOutputChannels, MAX_OUTPUTS)) {
            if (k != out &&
                s.limiterLinkGroup[k] == g
            ) {
                core.setLimiterParam(k, param, value)
            }
        }
    }

    /**
     * FFI: macOS `LimiterGang.setGroup` — joining a group adopts the settings
     * of its lowest-numbered existing member; the first member keeps its own,
     * leaving (0) keeps the current ones. Mirrored into the core by re-sending
     * the adopted values to the joiner (the device already holds them).
     */
    private fun limiterSetGroup(out: Int, group: Int) {
        core.setLimiterParam(out, DspiConstants.LIMITER_PARAM_LINK_GROUP, group.toFloat())
        val s = core.getState()
        val g = s.limiterLinkGroup[out]
        if (g == 0) return
        val peer = (0 until minOf(s.numOutputChannels, MAX_OUTPUTS)).firstOrNull { it != out && s.limiterLinkGroup[it] == g } ?: return
        core.setLimiterParam(out, DspiConstants.LIMITER_PARAM_THRESHOLD_DB, s.limiterThresholdDb[peer])
        core.setLimiterParam(out, DspiConstants.LIMITER_PARAM_RELEASE_MS, s.limiterReleaseMs[peer])
        core.setLimiterParam(out, DspiConstants.LIMITER_PARAM_ENABLED, if (s.limiterEnabled[peer]) 1f else 0f)
    }

    /**
     * FFI: macOS `applyLimiterSettings` — put outputs back to exact per-output
     * settings: the targets leave their groups, take their own values
     * unlinked, then rejoin in ascending order; enable last.
     */
    private fun applyLimiterSettings(targets: Map<Int, LimiterTarget>) {
        val outs = targets.keys.sorted()
        val s = core.getState()
        for (k in outs) if (s.limiterLinkGroup[k] != 0) runCatching { limiterSetGroup(k, 0) }
        for (k in outs) {
            val t = targets.getValue(k)
            runCatching { core.setLimiterParam(k, DspiConstants.LIMITER_PARAM_THRESHOLD_DB, t.threshold) }
            runCatching { core.setLimiterParam(k, DspiConstants.LIMITER_PARAM_RELEASE_MS, t.release) }
            runCatching { core.setLimiterParam(k, DspiConstants.LIMITER_PARAM_ENABLED, if (t.enabled) 1f else 0f) }
        }
        for (k in outs) targets.getValue(k).group.takeIf { it != 0 }?.let { runCatching { limiterSetGroup(k, it) } }
    }

    private data class LimiterTarget(val enabled: Boolean, val threshold: Float, val release: Float, val group: Int)

    override fun unlinkAllLimiters() = fire("unlinkAllLimiters") {
        core.setLimiterParam(LIMITER_ALL_OUTPUTS, DspiConstants.LIMITER_PARAM_LINK_GROUP, 0f)
    }

    override fun disableAllLimiters() = fire("disableAllLimiters") {
        core.setLimiterParam(LIMITER_ALL_OUTPUTS, DspiConstants.LIMITER_PARAM_ENABLED, 0f)
    }

    // ═══ Hardware IO setters ═══

    override suspend fun setOutputType(token: ConfirmationToken, slot: Int, kind: Int) = pinCall(token, "setOutputType") {
        core.setOutputType(slot, kind)
    }

    override suspend fun setI2sBckPin(token: ConfirmationToken, role: Int, pin: Int) = pinCall(token, "setI2sBckPin") {
        core.setI2sBckPin(role, pin)
    }

    override suspend fun setI2sClockPinMode(token: ConfirmationToken, mode: Int) = pinCall(token, "setI2sClockPinMode") {
        core.setI2sClockPinMode(mode)
    }

    override suspend fun setMckEnabled(token: ConfirmationToken, enabled: Boolean) = pinCall(token, "setMckEnabled") {
        core.setMckEnabled(enabled)
    }

    override suspend fun setMckPin(token: ConfirmationToken, pin: Int) = pinCall(token, "setMckPin") { core.setMckPin(pin) }

    override suspend fun setMckMultiplier(token: ConfirmationToken, mult: Int) = pinCall(token, "setMckMultiplier") {
        core.setMckMultiplier(mult)
    }

    override suspend fun setAdatOutEnabled(token: ConfirmationToken, enabled: Boolean) = pinCall(token, "setAdatOutEnabled") {
        core.setAdatOutEnabled(enabled)
    }

    override suspend fun setAdatOutPin(token: ConfirmationToken, pin: Int) = pinCall(token, "setAdatOutPin") { core.setAdatOutPin(pin) }

    override suspend fun setSpdifRxPin(token: ConfirmationToken, index: Int, pin: Int) = pinCall(token, "setSpdifRxPin") {
        core.setSpdifRxPin(index, pin)
    }

    override suspend fun setSpdifInputEnabled(token: ConfirmationToken, index: Int, enabled: Boolean) =
        pinCall(token, "setSpdifInputEnabled") { core.setSpdifInputEnabled(index, enabled) }

    override suspend fun setI2sRxPin(token: ConfirmationToken, pair: Int, pin: Int) = pinCall(token, "setI2sRxPin") {
        core.setI2sRxPin(pair, pin)
    }

    override suspend fun setI2sInputChannels(token: ConfirmationToken, channels: Int) = pinCall(token, "setI2sInputChannels") {
        core.setI2sInputChannels(channels)
    }

    override suspend fun setAdatInputEnabled(token: ConfirmationToken, enabled: Boolean) = pinCall(token, "setAdatInputEnabled") {
        core.setAdatInputEnabled(enabled)
    }

    override suspend fun setAdatInputPin(token: ConfirmationToken, pin: Int) = pinCall(token, "setAdatInputPin") {
        core.setAdatInputPin(pin)
    }

    override suspend fun setAdatInputClockMode(token: ConfirmationToken, mode: Int) = pinCall(token, "setAdatInputClockMode") {
        core.setAdatInputClockMode(mode)
    }

    /** GATED [DangerousOp.HARDWARE_APPLY]; arms the retry blackout. Fire-and-forget form of [setInputRateWithStatus]. */
    override fun setInputRate(token: ConfirmationToken, index: Int) {
        if (writeRefused("setInputRate", token, DangerousOp.HARDWARE_APPLY)) return
        requireToken(token, DangerousOp.HARDWARE_APPLY)
        scope.launch(main) { hardwareCall { core.setInputRate(index) } }
    }

    /** GATED [DangerousOp.HARDWARE_APPLY]; arms the retry blackout. Fire-and-forget form of [setI2sClockModeWithStatus]. */
    override fun setI2sClockMode(token: ConfirmationToken, mode: Int) {
        if (writeRefused("setI2sClockMode", token, DangerousOp.HARDWARE_APPLY)) return
        requireToken(token, DangerousOp.HARDWARE_APPLY)
        scope.launch(main) { hardwareCall { core.setI2sClockMode(mode) } }
    }

    /** SETTINGS S7: as the pin calls — gate, blackout, output-config bookkeeping, the transfer's status. */
    override suspend fun setInputRateWithStatus(token: ConfirmationToken, index: Int) =
        pinCall(token, "setInputRateWithStatus") { core.setInputRate(index) }

    override suspend fun setI2sClockModeWithStatus(token: ConfirmationToken, mode: Int) = pinCall(token, "setI2sClockModeWithStatus") {
        core.setI2sClockMode(mode)
    }

    override fun setLgSoundSync(enabled: Boolean) = fire("setLgSoundSync") { core.setLgSoundSync(enabled) }

    /** DAC mute: the device applies it later; [BridgeEvent.DacMuteApplied] reports what it kept. */
    override fun setDacMute(enabled: Boolean, activeLow: Boolean, pin: Int, holdMs: Int, releaseMs: Int) {
        if (writeRefused("setDacMute")) return emit(BridgeEvent.DacMuteApplied(false))
        scope.launch(main) {
            // Flash write (config), ungated (D3): arm the retry blackout for the read-back
            val ok = onFfi { runCatching { core.setDacMute(enabled, activeLow, pin, holdMs, releaseMs) }.isSuccess.also { flashWritten() } }
            if (!ok) {
                emit(BridgeEvent.DacMuteApplied(false))
                return@launch
            }
            // The device validates and applies it in its main loop; read back what it kept
            delay(DAC_MUTE_READBACK_MS)
            val snap =
                onFfi {
                    runCatching { get { core.fetchDacMute() } }
                    snapshot()
                }
            val s = snap.state
            val kept =
                s.dacMuteEnabled == enabled &&
                    (
                        !enabled ||
                            (
                                s.dacMutePin == pin &&
                                    s.dacMuteActiveLow == activeLow &&
                                    s.dacMuteHoldMs == holdMs &&
                                    s.dacMuteReleaseMs == releaseMs
                                )
                        )
            publish(snap)
            emit(BridgeEvent.DacMuteApplied(kept))
        }
    }

    override suspend fun testDacMute(): Int {
        if (writeRefused("testDacMute")) return 255
        return onFfi { statusOf(255) { core.testDacMute() } }
    }

    private fun ctrlIface(which: Int, set: () -> Unit) {
        if (writeRefused(if (which == 0) "setUart" else "setI2c")) return emit(BridgeEvent.CtrlIfaceApplied(which, 255))
        scope.launch(main) {
            // Config persist (flash), ungated (D3): arm the retry blackout
            val ok = onFfi { runCatching { set() }.isSuccess.also { flashWritten() } }
            if (!ok) {
                emit(BridgeEvent.CtrlIfaceApplied(which, 255))
                return@launch
            }
            delay(CTRL_IFACE_READBACK_MS)
            val snap =
                onFfi {
                    runCatching { get { core.fetchCtrlIfaces() } }
                    snapshot()
                }
            publish(snap)
            val c = snap.state.ctrlStatus
            emit(BridgeEvent.CtrlIfaceApplied(which, if (which == 0) c.uartLastStatus else c.i2cLastStatus))
        }
    }

    override fun setUart(enabled: Boolean, txPin: Int, rxPin: Int, notify: Boolean, baud: Int) =
        ctrlIface(0) { core.setUart(UartConfig(enabled, txPin, rxPin, notify, baud.toLong())) }

    override fun setI2c(enabled: Boolean, sdaPin: Int, sclPin: Int, address: Int) = ctrlIface(1) {
        core.setI2c(I2cConfig(enabled, sdaPin, sclPin, address))
    }

    override fun refreshCtrlIfaces() {
        if (!usable) return
        scope.launch(main) {
            publish(
                onFfi {
                    runCatching { get { core.fetchCtrlIfaces() } }
                    snapshot()
                }
            )
        }
    }

    override suspend fun fetchInputLock(which: Int): InputLockStatus? = if (!usable) {
        null
    } else {
        onFfi {
            runCatching { get { core.fetchInputLock(which) } }.getOrNull()
        }
    }

    override suspend fun fetchAdatOutStatus(): AdatOutStatus? = if (!usable) {
        null
    } else {
        onFfi {
            runCatching { get { core.fetchAdatOutStatus() } }.getOrNull()
        }
    }

    override suspend fun fetchLgStatus(): LgStatus? = if (!usable) {
        null
    } else {
        onFfi {
            runCatching { get { core.fetchLgStatus() } }.getOrNull()
        }
    }

    override suspend fun fetchSpdifRxStatus(): se.bofr.dspi.core.SpdifRxStatus? =
        if (!usable) null else onFfi { runCatching { get { core.fetchSpdifRxStatus() } }.getOrNull() }

    /** Qt StatsController: channel status only while locked (state 2). */
    override suspend fun fetchSpdifChannelStatus(): SpdifChannelStatus? {
        if (!usable) return null
        return onFfi {
            runCatching {
                val st = get { core.fetchSpdifRxStatus() }
                if (st.state != 2) return@runCatching null
                decodeChannelStatus(get { core.fetchSpdifRxChannelStatus() })
            }.getOrNull()
        }
    }

    override suspend fun fetchActiveSpdifRxPin(): Int? {
        if (!usable) return null
        return onFfi {
            runCatching {
                val src = get { core.fetchSpdifRxStatus() }.inputSource
                val index = if (src in 4..6) src - 3 else 0
                get { core.fetchSpdifRxPin(index) }.takeIf { it != 0 }
            }.getOrNull()
        }
    }

    override val hardwareCaps: HardwareCaps
        get() {
            if (!usable) return HardwareCaps()
            val rp2350 = current.platformId == 1
            val v32 = current.formatVersion >= DspiConstants.WIRE_FORMAT_VERSION
            return HardwareCaps(
                // macOS bulk apply: V21+ image -> inputSourceSupported / lgSoundSyncSupported
                inputSourceSupported = v32,
                lgSoundSyncSupported = v32,
                multiSpdifSupported = v32,
                spdifInputCount = if (v32) 4 else 1,
                i2sInputSupported = v32,
                i2sMultichannelInput = v32 && rp2350,
                i2sClockModeSupported = v32,
                i2sClockPinModeSupported = v32,
                adatOutSupported = rp2350,
                adatInputSupported = rp2350,
                limiterSupported = v32,
                crossfeedPairMapSupported = crossfeedPairMapSupported,
                crossfeedPairCount = CrossfeedPairs.pairCount(current.numOutputChannels)
            )
        }

    override val siggenSupported: Boolean get() = siggen.caps.value?.supported == true

    override suspend fun fetchInputRate(): Int = if (!usable) {
        0
    } else {
        onFfi {
            runCatching { get { core.fetchInputRate() }.toInt() }.getOrDefault(0)
        }
    }

    // ═══ Input pair links ═══

    override fun liveInputCount(): Int = minOf(current.numInputChannels, maxOf(2, _status.value.activeInputChannels))

    private fun pairIndex(ch: Int): Int = map.inputOf(ch).let { if (it >= 0) it / 2 else -1 }

    override fun pairPartner(ch: Int): Int = map.inputOf(ch).let { if (it >= 0) inputAppId(it xor 1) else -1 }

    override fun pairAvailable(ch: Int): Boolean = map.inputOf(ch).let { it >= 0 && (it or 1) < liveInputCount() }

    override fun isInputLinked(ch: Int): Boolean {
        val p = pairIndex(ch)
        return p >= 0 && pairAvailable(ch) && (links shr p) and 1 == 1
    }

    override fun linkedPartner(ch: Int): Int = if (isInputLinked(ch)) pairPartner(ch) else -1

    override fun inputPairMatches(ch: Int): Boolean {
        val m = map
        val partner = pairPartner(ch)
        val a = m.wire(ch)
        val b = m.wire(partner)
        if (a < 0 || b < 0) return true
        val s = current
        if (s.inputPreampDb[m.inputOf(ch)] != s.inputPreampDb[m.inputOf(partner)]) return false
        for (band in 0 until BANDS_PER_CHANNEL) {
            val x = s.filters[a][band]
            val y = s.filters[b][band]
            if (x.filterType == FILTER_FLAT && y.filterType == FILTER_FLAT) continue
            if (x.filterType != y.filterType ||
                x.bypass != y.bypass ||
                x.freq != y.freq ||
                x.q != y.q ||
                x.gain != y.gain ||
                (x.filterType == FILTER_LINKWITZ_TRANSFORM && x.qp != y.qp)
            ) {
                return false
            }
        }
        return true
    }

    override fun setInputLinked(ch: Int, linked: Boolean, keepCh: Int) {
        if (writeRefused("setInputLinked")) return
        val p = pairIndex(ch)
        if (p < 0) return
        val m = map
        val copy =
            if (linked && keepCh >= 0 && (keepCh == ch || keepCh == pairPartner(ch))) {
                val to = if (keepCh == ch) pairPartner(ch) else ch
                Triple(keepCh, to, m)
            } else {
                null
            }
        links = if (linked) links or (1 shl p) else links and (1 shl p).inv()
        saveLinks()
        fire("setInputLinked", Emit.CURVES) {
            if (copy != null) copyInputSettings(copy.first, copy.second, copy.third)
        }
    }

    /** FFI: DSPiBridge::copyInputSettings. */
    private fun copyInputSettings(from: Int, to: Int, m: ChannelMap) {
        val wf = m.wire(from)
        val wt = m.wire(to)
        if (wf < 0 || wt < 0) return
        val s = core.getState()
        for (band in 0 until BANDS_PER_CHANNEL) core.setFilter(wt, band, s.filters[wf][band])
        core.setInputPreamp(m.inputOf(to), s.inputPreampDb[m.inputOf(from)])
        markDirty(to)
    }

    override val inputLinks: Int get() = links

    override fun restoreInputLinks(links: Int) {
        if (links == this.links) return
        this.links = links
        saveLinks()
        _stateRevision.value += 1
    }

    private fun saveLinks() {
        if (selectedSerial.isNotEmpty()) linkStore.save(selectedSerial, links)
    }

    internal fun markHardwareUnsaved() {
        _hardwareUnsaved.value = true
    }

    /** Everything changed outside the setters: redraw from the core's state. */
    internal suspend fun refreshAll() {
        publish(onFfi { snapshot(all = true) }, magnitudes = true)
    }

    // ═══ Channel clipboard ═══

    override fun copyChannel(ch: Int) {
        val w = map.wire(ch)
        if (w < 0) return
        val s = current
        val out = map.outputOf(ch)
        clip =
            ChannelClip(
                output = out >= 0,
                filters = s.filters[w],
                xover = s.xover[w],
                gain = if (out >= 0) s.outputGainDb[out] else 0f,
                delay = if (out >= 0) s.outputDelayMs[out] else 0f,
                mute = out >= 0 && s.outputMuted[out]
            )
    }

    override fun canPaste() = clip != null

    override fun pasteChannel(ch: Int) {
        val c = clip ?: return
        val w = map.wire(ch)
        if (w < 0) return
        val out = map.outputOf(ch)
        val t = targets(ch)
        fire("pasteChannel", Emit.CURVES) {
            // Always all 10 PEQ bands (a linked input updates its partner too).
            for (b in 0 until BANDS_PER_CHANNEL) applyFilter(t, b, c.filters[b])
            if (c.output && out >= 0) {
                for (b in 0 until MAX_XOVER_BANDS) core.setCrossover(w, b, c.xover[b])
                core.setOutputGain(out, c.gain)
                core.setOutputDelay(out, c.delay)
                core.setOutputMute(out, c.mute)
            }
            markDirty(ch)
        }
    }

    // ═══ On-graph editor support ═══

    override fun channelBands(ch: Int): ChannelBands? {
        val w = map.wire(ch)
        if (w < 0) return null
        return ChannelBands(
            current.filters[w].take(BANDS_PER_CHANNEL),
            if (map.outputOf(ch) >=
                0
            ) {
                current.xover[w].take(MAX_XOVER_BANDS)
            } else {
                emptyList()
            }
        )
    }

    /** The level shift drawn on a channel's curve: an output's gain, an input's preamp. */
    override fun channelGainOffset(ch: Int): Float {
        val out = map.outputOf(ch)
        if (out >= 0) return current.outputGainDb[out]
        val input = map.inputOf(ch)
        return if (input >= 0) current.inputPreampDb[input] else 0f
    }

    /** Mid-drag: device (and linked partner) only, no signals. */
    override fun sendBandLive(ch: Int, band: Int, params: FilterParams) {
        if (band !in 0 until BANDS_PER_CHANNEL) return
        val t = targets(ch)
        // Qt: device only, no signal; the cached curve follows (markDirty) — review B6
        fire("sendBandLive", Emit.SILENT) { applyFilter(t, band, params) }
    }

    /** Release: write the bands once, then refresh everything. */
    override fun commitBands(ch: Int, bands: List<Int>, params: List<FilterParams>) {
        val t = targets(ch)
        fire("commitBands", Emit.CURVES) {
            for (i in 0 until minOf(bands.size, params.size)) if (bands[i] in 0 until BANDS_PER_CHANNEL) applyFilter(t, bands[i], params[i])
        }
    }

    override fun channelVisible(ch: Int) = ch in 0 until ChannelMap.APP_CHANNEL_COUNT && channelVisibleFlags[ch]

    override fun setChannelVisible(ch: Int, visible: Boolean) {
        if (ch !in 0 until ChannelMap.APP_CHANNEL_COUNT) return
        if (channelVisibleFlags[ch] != visible) {
            channelVisibleFlags[ch] = visible
            _magnitudesRevision.value += 1
        }
    }

    companion object {
        const val STATUS_POLL_MS = 60L
        const val HOTPLUG_POLL_MS = 500L

        /** Device notifications are applied at most this often (a turning knob sends many). */
        const val NOTIFY_BATCH_MS = 30L

        /** A clip marker clears itself this long after the last clip (macOS Console). */
        const val CLIP_HOLD_MS = 10_000L
        const val DAC_MUTE_READBACK_MS = 150L
        const val CTRL_IFACE_READBACK_MS = 250L

        /** Idempotent GETs are retried this long after a flash write. */
        const val FLASH_BLACKOUT_MS = 2_000L
        const val GET_RETRIES = 4
        const val GET_RETRY_DELAY_MS = 150L

        /** Channel colours matching the macOS Console's ChannelPalette. */
        val INPUT_COLORS = listOf("#4A8FE3", "#F57373", "#73C78C", "#EDB34D", "#998CEB", "#E68CC7", "#66C7D1", "#CCB86B")
        val OUTPUT_COLORS = listOf("#45C2A3", "#59D180", "#F0C459", "#F2A64D", "#598CF2", "#8CB3F2", "#D97390", "#F299A6")
        const val PDM_COLOR = "#BA87F2"

        /** The single FFI thread (daemon): every core call runs here, in order. */
        fun newFfiDispatcher(): CoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
            Thread(r, "dspi-ffi").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }
}

/** Qt StatsController categoryName / wordLengthName over the 24 channel-status bytes. */
internal fun decodeChannelStatus(cs: List<Int>): SpdifChannelStatus? {
    if (cs.size < 5) return null
    val category =
        when (val c = cs[1] and 0xFF) {
            0x00 -> "General"
            0x01 -> "CD Player"
            0x02 -> "DAT"
            0x03 -> "DCC"
            0x04 -> "MiniDisc"
            0x06 -> "Synthesizer"
            0x08 -> "Broadcast Receiver"
            0x09 -> "Musical Instrument"
            0x0A -> "A/D Converter"
            0x0C -> "Mixer"
            0x0D -> "Rate Converter"
            0x0E -> "Sampler"
            0x0F -> "Digital Signal Processor"
            else -> "0x%02X".format(c)
        }
    val word =
        when (cs[4] and 0x0F) {
            0x00 -> "Not indicated"
            0x02 -> "16-bit"
            0x04 -> "20-bit"
            0x08 -> "17-bit"
            0x0A -> "22-bit"
            0x0B -> "24-bit"
            else -> "-"
        }
    return SpdifChannelStatus(
        consumer = cs[0] and 0x01 == 0,
        pcm = cs[0] and 0x02 == 0,
        copyPermitted = cs[0] and 0x04 != 0,
        category = category,
        wordLength = word,
        raw = cs
    )
}
