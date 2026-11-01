package se.bofr.dspi.bridge

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import se.bofr.dspi.core.DspState
import se.bofr.dspi.core.FilterParams
import se.bofr.dspi.core.FlashStatus
import se.bofr.dspi.core.PinConfigStatus
import se.bofr.dspi.core.PresetStatus

/**
 * The ViewModel/UI-facing API: a port of DSPi-Console-Linux
 * `qt/src/DSPiBridge.h` with names kept parallel (Qt casing included:
 * `masterVolumeDB`, `loudnessRefSPL`, `crossfeedITD`).
 *
 * Mapping of Qt concepts:
 * - `Q_PROPERTY ... NOTIFY stateChanged`  -> read the getter; observe [state]
 *   (or [stateRevision]) to recompose. Getters always read the latest snapshot.
 * - `NOTIFY statusChanged`                -> [status] (60 ms poll: peaks, CPU, clips)
 * - `NOTIFY devicesChanged`/`inaccessibleDevicesChanged` -> [devices]
 * - `presetDirtyChanged`                  -> [presetDirty]
 * - `magnitudesChanged`                   -> [magnitudesRevision]
 * - other signals                         -> [events] ([BridgeEvent])
 * - `Q_INVOKABLE` getters                 -> plain functions (app ids)
 * - `Q_INVOKABLE void set*`               -> fire-and-forget; run on the bridge's
 *   single FFI dispatcher in call order; errors surface via [events]
 * - `Q_INVOKABLE int ...` (status codes)  -> `suspend` returning a typed status
 *
 * Channel arguments named `ch` are APP ids ([ChannelMap]); `input`/`output`/
 * `idx`/`out` are input/output indices; `slot` is a preset slot 0..9;
 * `band` a PEQ band 0..9 or crossover band 0..3 (crossover functions).
 *
 * `sendOnly = true` (Qt: live drag) updates the device and the core state but
 * emits nothing; the UI commits with a final `sendOnly = false` call. Throttle
 * (first value at once, then latest every 30 ms) is the caller's job via
 * [Throttle].
 *
 * Threading: all members are main-thread safe; flows emit on the main thread.
 *
 * Gating (coordinator decisions D1, E1b (supersedes D2), see [DangerousOp]): members marked
 * GATED take a [ConfirmationToken] from [dangerousOps] as their first
 * parameter. A token that is not valid for the call (other op, spent,
 * revoked, expired, other bridge) makes the call throw
 * [DangerousOpNotConfirmed] before anything reaches the device — gated calls
 * never silently do nothing. Members marked "Ungated (D1)" are one click in
 * the official clients; "Flash write, ungated (D3)" members write flash
 * without a confirmation in the official clients either, and the bridge arms
 * its retry blackout after them (commands.md §1.3).
 */
// Wave B batch 3 members carry default bodies only so StubDspiBridge keeps
// compiling until APP deletes it (D9); DspiBridgeImpl overrides every one.
interface DspiBridge {
    // ═══ Observable state (Qt NOTIFY signals) ═══════════════════════════

    /** Full core state (wire-indexed), re-emitted after every applied change (stateChanged). */
    val state: StateFlow<DspState>

    /** Increments on every stateChanged; cheap key for recomposition. */
    val stateRevision: StateFlow<Long>

    /** statusChanged: connection + 60 ms status poll. */
    val status: StateFlow<BridgeStatus>

    /** devicesChanged / inaccessibleDevicesChanged. */
    val devices: StateFlow<DeviceListState>

    /** presetDirtyChanged: live settings differ from the active preset. */
    val presetDirty: StateFlow<Boolean>

    /**
     * Qt model (`DSPiBridge::hardwareUnsaved`): a successful pin/IO setter in
     * independent mode or a hardware import; cleared by saveOutputConfig,
     * a mode change away from independent and device selection. The macOS
     * Settings Save bar uses [outputConfigDirty] instead.
     */
    val hardwareUnsaved: StateFlow<Boolean>

    /**
     * E1b / TOOLS (D): macOS `SettingsSaveCoordinator.outputDirty` — the live
     * output configuration (pins, output types, I2S/MCK clocks, S/PDIF/I2S/ADAT
     * RX and ADAT out, input rate, I2S clock mode and clock-pin mode, and the
     * output limiters) has edits not yet flashed, in independent mode.
     * Value = raw flag && `outputConfigMode == OUTPUT_CONFIG_MODE_INDEPENDENT`
     * (the raw flag survives a mode change, as on macOS).
     *
     * Set: by [beginOutputEdit] (which every output-config edit calls first).
     * Cleared (raw flag): by a successful [saveOutputConfig] (the Save bar
     * calls it whenever this is true), by [setOutputConfigMode] (only the Save
     * bar's global draft changes the mode, and macOS clears the flag on every
     * Save), by [revertOutputConfig], and when a DIFFERENT device is selected
     * (a re-plug of the same serial keeps it). NOT cleared by preset load or
     * by a disconnect (macOS keeps it).
     */
    val outputConfigDirty: StateFlow<Boolean>

    /**
     * macOS `SettingsSaveCoordinator.beginOutputEdit()`: call at the start of
     * every user output-config edit (any hardware setter, any limiter setter
     * incl. link groups/copy/disable-all, a `.dspipreset` import that writes
     * limiters or hardware). Independent mode only (no-op otherwise); on the
     * first edit since the last clean point captures the revert baseline
     * (macOS `OutputConfigSnapshot`: limiter per output, outputPins,
     * outputTypes, i2sBckPin, mck enable/pin/multiplier, S/PDIF RX pins and
     * enables, I2S RX pins, I2S input channels, input rate, I2S clock mode,
     * clock-pin mode, slave BCK pin, ADAT enable/pin) and sets [outputConfigDirty].
     */
    fun beginOutputEdit()

    /**
     * Save bar Revert for the output configuration (macOS
     * `SettingsSaveCoordinator.revert`): re-applies the captured baseline,
     * best effort, in the macOS order (slot types, pins, BCK, MCK, S/PDIF 1,
     * S/PDIF 2/3 disable-repin-enable, I2S channels down / RX pins / channels
     * up, input rate, clock mode, slave BCK then clock-pin mode, ADAT pin then
     * enable, limiters last via ungang-set-regang), then clears
     * [outputConfigDirty]. GATED [DangerousOp.HARDWARE_APPLY], minted by the
     * Revert tap (E1b). Aborts when the device changes mid-sequence.
     * Returns false when nothing was pending or the device changed.
     */
    suspend fun revertOutputConfig(token: ConfirmationToken): Boolean

    /** magnitudesChanged: increments when any channel's curve was recomputed. */
    val magnitudesRevision: StateFlow<Long>

    /** One-shot signals (siggenNotified, csNotified, deviceArrived, …). */
    val events: SharedFlow<BridgeEvent>

    // ═══ Q_PROPERTY getters — connection / device ═══════════════════════

    val connected: Boolean
    val connectionError: String
    val selectedSerial: String
    val availableSerials: List<String>
    val inaccessibleDevices: Int
    val platformName: String

    /** +18 dB RP2040, +24 dB RP2350. */
    val preampMaxDB: Float
    val numChannels: Int
    val numInputChannels: Int
    val numOutputChannels: Int

    /** "1.1.6-beta5", "" when unknown. */
    val firmwareVersion: String

    /** `COMPAT_*` (see [se.bofr.dspi.core.Compat]); UNKNOWN when disconnected. */
    val compat: Int

    /** Banner text for an incompatible firmware, "" otherwise. */
    val compatMessage: String
    val maxDelayMs: Float

    // ═══ Q_PROPERTY getters — global / volume / loudness / crossfeed ═══

    val preampDB: Float
    val bypass: Boolean
    val masterVolumeDB: Float
    val masterVolumeMode: Int
    val userVolumeDB: Float
    val userMute: Boolean
    val loudnessEnabled: Boolean
    val loudnessRefSPL: Float
    val loudnessIntensity: Float
    val loudnessOutputMask: Int
    val crossfeedEnabled: Boolean
    val crossfeedPreset: Int
    val crossfeedFreq: Float
    val crossfeedFeed: Float
    val crossfeedITD: Boolean
    val crossfeedOutputPairMask: Int

    // ═══ Q_PROPERTY getters — status (statusChanged) ════════════════════

    val cpu0: Int
    val cpu1: Int

    /**
     * Sticky clip flags re-keyed by APP id (Qt `clipFlags()`): bit `id` set when
     * app channel `id` clipped. [BridgeStatus.clipFlags] is the raw per-WIRE value.
     */
    val clipFlags: Long
    val activeInputChannels: Int

    // ═══ Q_PROPERTY getters — presets / config modes / sources ═════════

    val activePresetSlot: Int
    val presetOccupied: Int
    val presetStartupMode: Int
    val presetDefaultSlot: Int
    val outputConfigMode: Int
    val inputSource: Int

    /** Available, enabled input sources (firmware InputSource ids). */
    val inputSources: List<InputSourceOption>
    val core1Mode: Int

    // ═══ Q_PROPERTY getters — tools ════════════════════════════════════

    val levellerEnabled: Boolean
    val levellerAmount: Float
    val levellerSpeed: Int
    val levellerMaxGain: Float
    val levellerGate: Float
    val levellerLookahead: Boolean
    val levellerDetectorMask: Int
    val levellerApplyMask: Int
    val psybassEnabled: Boolean
    val psybassCutoff: Float
    val psybassHarmonics: Float
    val psybassDrive: Float
    val psybassCharacter: Float
    val psybassOriginal: Float
    val psybassOutputMask: Int

    /** RP2350 and compatible firmware. */
    val upmixSupported: Boolean

    /** By `UPMIX_PARAM_*` id. */
    val upmixParams: List<Float>

    /** By `SUBHARM_PARAM_*` id. */
    val subharmParams: List<Float>
    val subharmSolo: Boolean

    /** By `TUBE_PARAM_*` index. */
    val tubeParams: List<Float>

    // ═══ Hardware IO (Settings) ════════════════════════════════════════
    // Qt's `hardware` QVariantMap is a projection of DspState: read the
    // fields from [state] (outputPins, outputTypes, i2sBckPin, mck*, adat*,
    // spdifRxPins, spdifInputsEnabled, i2sRxPins, …).

    /** GPIOs a user may assign (0..28 except 23..25). */
    val validPins: List<Int>

    /** Every claimed GPIO with its owner, for the pin overview / conflicts. */
    fun pinOwners(): List<PinOwner>

    // ═══ Channel identity (app ids) ════════════════════════════════════

    fun channelExists(ch: Int): Boolean

    fun isInputChannel(ch: Int): Boolean

    /** Input index -> app id. */
    fun inputAppId(input: Int): Int

    /** Last polled peak 0..1. */
    fun peakLevel(ch: Int): Float

    fun isClipping(ch: Int): Boolean

    fun channelName(ch: Int): String

    /** Hex colour from the channel palette (ChannelPalette). */
    fun channelColor(ch: Int): String

    /** "IN1", "OUT3", … as Qt (`QString("IN%1")`); "" for an absent channel. */
    fun channelDescriptor(ch: Int): String

    // ═══ PEQ / crossover band reads (app id) ═══════════════════════════

    fun filterType(ch: Int, band: Int): Int

    fun filterFreq(ch: Int, band: Int): Float

    fun filterGain(ch: Int, band: Int): Float

    fun filterQ(ch: Int, band: Int): Float

    fun filterQp(ch: Int, band: Int): Float

    fun filterBypass(ch: Int, band: Int): Boolean

    fun crossoverType(ch: Int, band: Int): Int

    fun crossoverFreq(ch: Int, band: Int): Float

    fun crossoverBypass(ch: Int, band: Int): Boolean

    // ═══ Inputs / outputs / matrix reads ═══════════════════════════════

    fun inputPreampDB(input: Int): Float

    fun channelDelayMS(ch: Int): Float

    fun outputEnabled(idx: Int): Boolean

    fun outputMuted(idx: Int): Boolean

    fun outputGainDB(idx: Int): Float

    fun outputDelayMS(idx: Int): Float

    fun isPdmOutput(idx: Int): Boolean

    fun matrixRouting(input: Int, output: Int): Boolean

    fun matrixGain(input: Int, output: Int): Float

    fun matrixInvert(input: Int, output: Int): Boolean

    fun outputPin(physOut: Int): Int

    fun numPinOutputs(): Int

    fun presetName(slot: Int): String

    fun isPresetOccupied(slot: Int): Boolean

    // ═══ Curves (cached per app id; recomputed on change) ══════════════

    /** 201 points, dB, on the [curveFrequencies] grid; includes gain offset. */
    fun magnitudeCurve(ch: Int): List<Double>

    /** Phase (degrees) of a channel's PEQ + crossover bands, 201 points. */
    fun phaseCurve(ch: Int, unwrap: Boolean): List<Double>

    /** The 201 frequencies (Hz) of the curve grid. */
    val curveFrequencies: List<Double>

    // ═══ Setters — global / volume ═════════════════════════════════════

    fun setPreamp(db: Float)

    fun sendPreampToDevice(db: Float)

    fun setInputPreamp(input: Int, db: Float, sendOnly: Boolean = false)

    fun setBypass(en: Boolean)

    fun setMasterVolume(db: Float, sendOnly: Boolean = false)

    /** Flash write, ungated (D3). */
    fun setMasterVolumeMode(mode: Int)

    /** GATED [DangerousOp.SAVE_MASTER_VOLUME] — flash write (0xD6). */
    suspend fun saveMasterVolume(token: ConfirmationToken): PresetStatus

    fun setUserVolume(db: Float, sendOnly: Boolean = false)

    fun setUserMute(muted: Boolean)

    // ═══ Setters — EQ (app id) ═════════════════════════════════════════

    fun setFilter(ch: Int, band: Int, type: Int, freq: Float, gain: Float, q: Float)

    fun setLinkwitzTransform(ch: Int, band: Int, f0: Float, q0: Float, fp: Float, qp: Float)

    fun setBandBypass(ch: Int, band: Int, bypass: Boolean)

    fun setCrossover(ch: Int, band: Int, type: Int, freq: Float)

    fun setCrossoverBypass(ch: Int, band: Int, bypass: Boolean)

    fun setChannelDelay(ch: Int, ms: Float)

    fun clearPeq(ch: Int)

    fun clearAllBands(ch: Int, crossover: Boolean)

    fun setAllBandsBypass(ch: Int, bypass: Boolean, crossover: Boolean)

    fun canSetAllBypass(ch: Int, bypass: Boolean, crossover: Boolean): Boolean

    // ═══ Setters — loudness / crossfeed ════════════════════════════════

    fun setLoudness(en: Boolean)

    fun setLoudnessRef(spl: Float, sendOnly: Boolean = false)

    fun setLoudnessIntensity(pct: Float, sendOnly: Boolean = false)

    fun setLoudnessOutputMask(mask: Int)

    fun setCrossfeed(en: Boolean)

    fun setCrossfeedPreset(p: Int)

    fun setCrossfeedFreq(freq: Float, sendOnly: Boolean = false)

    fun setCrossfeedFeed(feed: Float, sendOnly: Boolean = false)

    fun setCrossfeedITD(en: Boolean)

    fun setCrossfeedOutputPairMask(mask: Int)

    // ═══ Setters — matrix / outputs ════════════════════════════════════

    /**
     * Qt/macOS `setMatrixRoute` (same five arguments). E4: `sendOnly = true`
     * is the live variant for a gain drag (device + core state, no emission;
     * commit with a final `sendOnly = false`), like the other `sendOnly` setters.
     */
    fun setMatrixRoute(input: Int, output: Int, enabled: Boolean, gain: Float, invert: Boolean, sendOnly: Boolean = false)

    /** Returns the resulting state (the firmware may refuse a PDM/Core 1 conflict). */
    suspend fun setOutputEnable(output: Int, enabled: Boolean): Boolean

    fun setOutputGain(output: Int, db: Float)

    fun sendOutputGainToDevice(output: Int, db: Float)

    fun setOutputMute(output: Int, muted: Boolean)

    fun setOutputDelay(output: Int, ms: Float)

    fun sendOutputDelayToDevice(output: Int, ms: Float)

    /**
     * macOS `numMatrixInputs` (`effectiveInputChannels`): the input rows the
     * current source carries — S/PDIF 2, I2S its channel count, ADAT 8, USB the
     * host's active channels — clamped to 2..[numInputChannels].
     */
    val effectiveInputChannels: Int get() = numInputChannels

    /**
     * macOS `applyDirectRouting` (E5): with n = min([rows], outputs), input
     * i -> output i at 0 dB for i < n; every other crosspoint of the [rows]
     * input rows reset to off / 0 dB / normal polarity; the PDM sub disabled
     * first, the diagonal outputs enabled. [rows] < 0 = [effectiveInputChannels].
     * [overview] = the graph is in its overview (macOS `isOverviewMode`): the
     * outputs it switches show/hide their curves, as macOS `setOutputEnable`.
     */
    fun directRouting(rows: Int, overview: Boolean = true) = directRouting()

    /** [directRouting] over [effectiveInputChannels] rows, overview visibility (Qt name, no arguments). */
    fun directRouting()

    /** macOS `clearAllRoutes`: disconnect every crosspoint of the [rows] input rows (< 0 = [effectiveInputChannels]), keep gains/polarity. */
    fun clearRouting(rows: Int) = clearRouting()

    /** [clearRouting] over [effectiveInputChannels] rows. */
    fun clearRouting()

    /** Outputs that would be switched off to enable `output` (PDM vs Core 1 EQ worker). */
    fun core1ConflictOutputs(output: Int): List<Int>

    fun enableOutputResolvingConflict(output: Int)

    suspend fun checkCore1Conflict(output: Int): Boolean

    // ═══ Pins / output config / names ══════════════════════════════════

    /** GATED [DangerousOp.HARDWARE_APPLY] (minted by the pin picker; applied live, E1b). */
    suspend fun setOutputPin(token: ConfirmationToken, output: Int, pin: Int): PinConfigStatus

    /** Flash write, ungated (D3; 0x98). */
    fun setOutputConfigMode(mode: Int)

    /** GATED [DangerousOp.SAVE_OUTPUT_CONFIG] — flash write (0x52). */
    suspend fun saveOutputConfig(token: ConfirmationToken): PresetStatus

    fun setChannelName(ch: Int, name: String)

    fun resetChannelNames()

    // ═══ Presets / flash ═══════════════════════════════════════════════

    /** GATED [DangerousOp.SAVE_PRESET] — flash write (0x90). */
    suspend fun savePreset(token: ConfirmationToken, slot: Int): PresetStatus

    /**
     * Ungated (D1): one click in the official clients. The UI runs the
     * Unsaved Changes flow first when [presetDirty].
     */
    suspend fun loadPreset(slot: Int): PresetStatus

    /** GATED [DangerousOp.DELETE_PRESET] — flash write (0x92). */
    suspend fun deletePreset(token: ConfirmationToken, slot: Int): PresetStatus

    /** GATED [DangerousOp.COPY_PRESET] — flash writes (0x90). */
    suspend fun copyPreset(token: ConfirmationToken, fromSlot: Int, toSlot: Int): PresetStatus

    /** Flash write (directory), ungated (D3; 0x94). */
    fun setPresetName(slot: Int, name: String)

    /** Flash write (directory), ungated (D3; 0x96). */
    fun setPresetStartup(mode: Int, slot: Int)

    /** What differs from the active preset, one sentence each (Unsaved Changes sheet). */
    suspend fun presetChanges(): List<String>

    /** GATED [DangerousOp.SAVE_PARAMS] — flash write (0x51). */
    suspend fun saveParams(token: ConfirmationToken): FlashStatus

    /** Revert to saved. Ungated (D1). */
    suspend fun loadParams(): FlashStatus

    /** GATED [DangerousOp.FACTORY_RESET] — flash write (0x53). */
    suspend fun factoryReset(token: ConfirmationToken): FlashStatus

    /** GATED [DangerousOp.ENTER_BOOTLOADER] — the device reboots into BOOTSEL and disappears (0xF0). */
    fun enterBootloader(token: ConfirmationToken)

    /** Ungated (D1). */
    fun clearClips()

    // ═══ Devices ═══════════════════════════════════════════════════════
    // On Android the USB layer (:usb UsbSession) owns attach/permission and
    // hands fds to the bridge via [attachDevice]/[detachDevice]; scanDevices/
    // selectDevice/rescan keep the Qt names for parity.

    fun scanDevices()

    fun selectDevice(serial: String)

    fun disconnectDevice()

    fun reconnect()

    /** Drop the connection, rescan and reconnect (same device if still there). */
    fun rescan()

    /** Android: adopt an opened usbfs fd (calls core.openFd + fetchAll + captureBaseline). */
    suspend fun attachDevice(fd: Int, serial: String, vid: Int): Boolean

    /** Android: the device went away or the app closes it (core.closeFd). */
    suspend fun detachDevice(serial: String)

    // ═══ Input source / tools ══════════════════════════════════════════

    /** GATED [DangerousOp.HARDWARE_APPLY] (minted by the source menu/picker tap, e.g. HOME's input-source menu; rebuilds the input pipeline). */
    fun setInputSource(token: ConfirmationToken, source: Int)

    fun setLevellerEnabled(en: Boolean)

    fun setLevellerAmount(pct: Float, sendOnly: Boolean = false)

    fun setLevellerSpeed(speed: Int)

    fun setLevellerMaxGain(db: Float, sendOnly: Boolean = false)

    fun setLevellerGate(db: Float, sendOnly: Boolean = false)

    fun setLevellerLookahead(en: Boolean)

    fun setLevellerMasks(detector: Int, apply: Int)

    fun setPsybassEnabled(en: Boolean)

    /** param: 0 cutoff, 1 harmonics, 2 drive, 3 character, 4 original. */
    fun setPsybassParam(param: Int, value: Float, sendOnly: Boolean = false)

    fun setPsybassOutputMask(mask: Int)

    fun setUpmixParam(id: Int, value: Float, sendOnly: Boolean = false)

    fun setSubharmParam(id: Int, value: Float, sendOnly: Boolean = false)

    fun setSubharmSolo(solo: Boolean)

    fun setTubeParam(idx: Int, value: Float, sendOnly: Boolean = false)

    /** Live readings polled by the tool views while open. */
    suspend fun fetchUpmixStatus(): se.bofr.dspi.core.UpmixStatus?

    /** dB, null on failure. */
    suspend fun fetchSubharmHeadroom(): Float?

    /** 0..1 per output, empty on failure. */
    suspend fun fetchSubharmMeter(): List<Float>

    // ═══ Output limiter (output index) ═════════════════════════════════
    // TOOLS (C): linked outputs are ganged by the firmware (limiter spec
    // §2.2; macOS `LimiterGang`): an enable/threshold/release SET on a linked
    // output reaches its whole link group, and an output joining a group adopts
    // the settings of the group's lowest-numbered existing member (the first
    // member keeps its own; leaving a group keeps the current values). The
    // published state ([state] limiter fields and the getters below) mirrors
    // that propagation in one emission, so the UI shows what the device holds.
    // `sendOnly = true` sends to the device only (macOS
    // `sendLimiterParamToDevice`). In independent mode limiter edits are part
    // of the output configuration: call [beginOutputEdit] first.

    fun limiterEnabled(out: Int): Boolean

    fun limiterThreshold(out: Int): Float

    fun limiterRelease(out: Int): Float

    fun limiterLinkGroup(out: Int): Int

    /**
     * dB, > 0 while limiting (polled with status while a limiter is enabled);
     * same value as [BridgeStatus.limiterReduction], observe [status] for changes.
     */
    fun limiterReduction(out: Int): Float

    fun setLimiterEnabled(out: Int, en: Boolean)

    fun setLimiterThreshold(out: Int, db: Float, sendOnly: Boolean = false)

    fun setLimiterRelease(out: Int, ms: Float, sendOnly: Boolean = false)

    fun setLimiterLinkGroup(out: Int, group: Int)

    fun copyLimiterToAll(out: Int)

    fun linkAllLimiterPairs()

    fun unlinkAllLimiters()

    fun disableAllLimiters()

    // ═══ Hardware IO setters (Settings) ════════════════════════════════
    // E1b (supersedes D2): the pipeline-rebuilding setters are GATED
    // [DangerousOp.HARDWARE_APPLY]. As on macOS they apply LIVE to RAM: the
    // token is minted by the explicit user interaction with the hardware control
    // (picker / toggle / menu choice) and the call is made at once — no pending
    // queue. Call [beginOutputEdit] first. The Save bar then flashes with
    // [saveOutputConfig] (independent mode); Revert uses [revertOutputConfig].

    suspend fun setOutputType(token: ConfirmationToken, slot: Int, kind: Int): PinConfigStatus

    suspend fun setI2sBckPin(token: ConfirmationToken, role: Int, pin: Int): PinConfigStatus

    suspend fun setI2sClockPinMode(token: ConfirmationToken, mode: Int): PinConfigStatus

    suspend fun setMckEnabled(token: ConfirmationToken, enabled: Boolean): PinConfigStatus

    suspend fun setMckPin(token: ConfirmationToken, pin: Int): PinConfigStatus

    suspend fun setMckMultiplier(token: ConfirmationToken, mult: Int): PinConfigStatus

    suspend fun setAdatOutEnabled(token: ConfirmationToken, enabled: Boolean): PinConfigStatus

    suspend fun setAdatOutPin(token: ConfirmationToken, pin: Int): PinConfigStatus

    suspend fun setSpdifRxPin(token: ConfirmationToken, index: Int, pin: Int): PinConfigStatus

    suspend fun setSpdifInputEnabled(token: ConfirmationToken, index: Int, enabled: Boolean): PinConfigStatus

    suspend fun setI2sRxPin(token: ConfirmationToken, pair: Int, pin: Int): PinConfigStatus

    suspend fun setI2sInputChannels(token: ConfirmationToken, channels: Int): PinConfigStatus

    suspend fun setAdatInputEnabled(token: ConfirmationToken, enabled: Boolean): PinConfigStatus

    suspend fun setAdatInputPin(token: ConfirmationToken, pin: Int): PinConfigStatus

    suspend fun setAdatInputClockMode(token: ConfirmationToken, mode: Int): PinConfigStatus

    /** GATED [DangerousOp.HARDWARE_APPLY]. */
    fun setInputRate(token: ConfirmationToken, index: Int)

    /** GATED [DangerousOp.HARDWARE_APPLY]. */
    fun setI2sClockMode(token: ConfirmationToken, mode: Int)

    fun setLgSoundSync(enabled: Boolean)

    /** Flash write (config), ungated (D3). Outcome arrives as [BridgeEvent.DacMuteApplied]. */
    fun setDacMute(enabled: Boolean, activeLow: Boolean, pin: Int, holdMs: Int, releaseMs: Int)

    /** Ungated (D1). 0 started, 3 feature disabled, 255 no reply. */
    suspend fun testDacMute(): Int

    /** Flash write (config persist), ungated (D3). Outcome arrives as [BridgeEvent.CtrlIfaceApplied]. */
    fun setUart(enabled: Boolean, txPin: Int, rxPin: Int, notify: Boolean, baud: Int)

    /** Flash write (config persist), ungated (D3). Outcome arrives as [BridgeEvent.CtrlIfaceApplied]. */
    fun setI2c(enabled: Boolean, sdaPin: Int, sclPin: Int, address: Int)

    fun refreshCtrlIfaces()

    /** which: 0 I2S slave, 1 ADAT in. */
    suspend fun fetchInputLock(which: Int): se.bofr.dspi.core.InputLockStatus?

    suspend fun fetchAdatOutStatus(): se.bofr.dspi.core.AdatOutStatus?

    suspend fun fetchLgStatus(): se.bofr.dspi.core.LgStatus?

    suspend fun fetchInputRate(): Int

    /** S/PDIF receiver status (lock, source, rate, counters), null on failure. */
    suspend fun fetchSpdifRxStatus(): se.bofr.dspi.core.SpdifRxStatus? = null

    /** The received IEC 60958 channel status, decoded as macOS StatsView shows it; null when not locked or on failure. */
    suspend fun fetchSpdifChannelStatus(): SpdifChannelStatus? = null

    /**
     * GPIO of the S/PDIF receiver the current input source uses (Qt
     * StatsController: source 1 -> RX 1, sources 4..6 -> RX 2..4), null if
     * none / on failure.
     */
    suspend fun fetchActiveSpdifRxPin(): Int? = null

    /**
     * What the connected firmware supports, for SETTINGS (derived from
     * platform + wire format; see [HardwareCaps]). All false while not usable.
     */
    val hardwareCaps: HardwareCaps get() = HardwareCaps()

    // ═══ Input stereo-pair links (IN1/2, IN3/4, …) ═════════════════════

    fun liveInputCount(): Int

    fun pairPartner(ch: Int): Int

    fun pairAvailable(ch: Int): Boolean

    fun isInputLinked(ch: Int): Boolean

    fun linkedPartner(ch: Int): Int

    fun inputPairMatches(ch: Int): Boolean

    /** keepCh: app id whose settings are copied onto the other input, or -1. */
    fun setInputLinked(ch: Int, linked: Boolean, keepCh: Int)

    /** Bit p: input pair p linked (for config export). */
    val inputLinks: Int

    fun restoreInputLinks(links: Int)

    // ═══ Channel clipboard (Copy / Paste Parameters) ═══════════════════

    fun copyChannel(ch: Int)

    fun canPaste(): Boolean

    fun pasteChannel(ch: Int)

    // ═══ On-graph editor support (DSPiBridge.h "C++ only" section) ═════

    /** PEQ bands and (outputs) crossover bands of a channel, null if absent. */
    fun channelBands(ch: Int): ChannelBands?

    /** dB the graph adds to the channel's curve (output gain, input preamp). */
    fun channelGainOffset(ch: Int): Float

    /** Mid-drag: device (and linked partner) only, no emissions. */
    fun sendBandLive(ch: Int, band: Int, params: FilterParams)

    /** Release: write the bands once, then refresh everything. */
    fun commitBands(ch: Int, bands: List<Int>, params: List<FilterParams>)

    // ═══ Graph channel visibility (app ids) ════════════════════════════

    fun channelVisible(ch: Int): Boolean

    fun setChannelVisible(ch: Int, visible: Boolean)

    // ═══ Controllers (Qt: separate QObjects over the same core) ═══════

    val rta: RtaController
    val siggen: SiggenController

    /** macOS `siggenSupported`: the generator's caps probe answered with a generator (false until it did). */
    val siggenSupported: Boolean get() = siggen.caps.value?.supported == true
    val stats: StatsController
    val cs: CsController
    val monitor: MonitorModel
    val configFiles: ConfigFiles

    /** Token issuer for the GATED members (D5). Same instance for the bridge's lifetime. */
    val dangerousOps: DangerousOps

    /**
     * Status polling on/off (Qt: timers run while the window is visible).
     * The app turns it off when no UI is visible (PLAN §5.3).
     */
    fun setUiVisible(visible: Boolean)
}
