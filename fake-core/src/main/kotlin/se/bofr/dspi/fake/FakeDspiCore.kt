package se.bofr.dspi.fake

import se.bofr.dspi.core.CoreInfo
import se.bofr.dspi.core.DeviceEvents
import se.bofr.dspi.core.DspState
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.core.DspiException
import se.bofr.dspi.core.FilterParams
import se.bofr.dspi.core.NotifyListener
import se.bofr.dspi.core.NotifyResult
import se.bofr.dspi.core.RtaListener
import se.bofr.dspi.core.SystemStatus
import se.bofr.dspi.core.UnimplementedDspiCore

/**
 * Hardware-free [se.bofr.dspi.core.DspiCoreApi] for demo mode and tests.
 *
 * WAVE 0: minimal — a connected RP2040-shaped device with the core's default
 * state, volume/mute setters, flat curves. Everything else falls through to
 * [UnimplementedDspiCore] (throws). Package BRIDGE turns this into a full fake
 * seeded from a V32 bulk fixture (docs/PACKAGES.md).
 *
 * Thread-safe enough for the bridge's single FFI dispatcher (all access is
 * @Synchronized).
 */
open class FakeDspiCore(initial: DspState = demoState()) : UnimplementedDspiCore() {
    @Volatile private var state: DspState = initial

    @Volatile private var connected = true

    @Volatile var notifyListener: NotifyListener? = null
        private set

    @Volatile var rtaListener: RtaListener? = null
        private set

    @Volatile var deviceEvents: DeviceEvents? = null
        private set

    /** Calls made, in order, as `name(args)` — for tests. */
    val calls: MutableList<String> = mutableListOf()

    @Synchronized protected fun update(call: String, f: (DspState) -> DspState) {
        requireConnected()
        calls += call
        state = f(state)
    }

    private fun requireConnected() {
        if (!connected) throw DspiException.Disconnected()
    }

    // ── Device management ──
    override fun openFd(fd: Int, serial: String, vid: Int) {
        connected = true
    }

    override fun closeFd() {
        connected = false
    }

    override fun selectDevice(serial: String) {
        connected = true
    }

    override fun disconnect() {
        connected = false
    }

    override fun isConnected(): Boolean = connected

    override fun preampMaxDb(): Float = if (state.platformId == 1) 24f else 18f

    override fun maxDelayMs(): Float = 170f

    // ── Sync / status ──
    override fun fetchAll() = requireConnected()

    override fun refreshParams() = requireConnected()

    override fun getState(): DspState = state

    override fun fetchStatus(): SystemStatus {
        requireConnected()
        return SystemStatus(
            peaks = List(DspiConstants.MAX_CHANNELS) { 0f },
            cpu0 = 12,
            cpu1 = 8,
            clipFlags = 0,
            activeInputChannels = state.numInputChannels,
            numChannels = state.numChannels
        )
    }

    // ── Volume ──
    override fun setMasterVolume(db: Float) = update("setMasterVolume($db)") { it.copy(masterVolumeDb = db) }

    override fun setUserVolume(db: Float) = update("setUserVolume($db)") { it.copy(userVolumeDb = db) }

    override fun setUserMute(muted: Boolean) = update("setUserMute($muted)") { it.copy(userMute = muted) }

    // ── Notifications / RTA / hotplug ──
    override fun setNotifyListener(listener: NotifyListener?) {
        notifyListener = listener
    }

    override fun processNotifications(): NotifyResult = NotifyResult(flags = 0, filterChannels = 0)

    override fun rtaSetListener(listener: RtaListener?) {
        rtaListener = listener
    }

    override fun setHotplugCallback(listener: DeviceEvents?) {
        deviceEvents = listener
    }

    override fun pollHotplug() {}

    // ── Unsaved changes ──
    override fun captureBaseline() {}

    override fun presetDirty(): Boolean = false

    override fun presetChanges(): List<String> = emptyList()

    // ── Stateless ──
    override fun computeMagnitudeCurve(filters: List<FilterParams>): List<Double> = List(MAGNITUDE_POINTS) { 0.0 }

    override fun computePhaseCurve(filters: List<FilterParams>, unwrap: Boolean): List<Double> = List(MAGNITUDE_POINTS) { 0.0 }

    override fun curveFreq(i: Long): Double {
        // 10 Hz .. 20 kHz log-spaced over 201 points (dsp_math.rs curve_freq).
        val logMin = kotlin.math.log10(10.0)
        val logMax = kotlin.math.log10(20000.0)
        return Math.pow(10.0, logMin + i.toDouble() / (MAGNITUDE_POINTS - 1) * (logMax - logMin))
    }

    override fun coreInfo(): CoreInfo = CoreInfo(
        ffiVersion = "fake",
        wireFormatVersion = DspiConstants.WIRE_FORMAT_VERSION,
        minWireFormatVersion = DspiConstants.MIN_WIRE_FORMAT_VERSION,
        bulkParamsSize = DspiConstants.BULK_PARAMS_SIZE.toLong(),
        magnitudePoints = MAGNITUDE_POINTS.toLong()
    )

    companion object {
        const val MAGNITUDE_POINTS = 201

        /** A compatible V32 RP2040 device with the core's defaults. */
        fun demoState(): DspState = defaultDspState().copy(
            formatVersion = DspiConstants.WIRE_FORMAT_VERSION,
            fwMajor = 1,
            fwMinor = 1,
            fwPatch = 6,
            fwBeta = 5,
            compat = DspiConstants.COMPAT_OK,
            bulkValid = true,
            channelNames = listOf("USB L", "USB R", "Out 1", "Out 2", "Out 3", "Out 4", "Sub") + List(10) { "" },
            presetNames = listOf("Default") + List(9) { "" },
            presetOccupied = 0x0001
        )
    }
}
