package se.bofr.dspi.bridge

import kotlinx.coroutines.flow.StateFlow
import se.bofr.dspi.core.BufferStats
import se.bofr.dspi.core.RtaCapsInfo
import se.bofr.dspi.core.RtaSnapshot
import se.bofr.dspi.core.SiggenCaps
import se.bofr.dspi.core.SiggenConfig
import se.bofr.dspi.core.SiggenStatus

/*
 * Ports of the Qt controllers that sit beside DSPiBridge over the same core
 * (DSPi-Console-Linux qt/src/{Rta,Siggen,Stats,Cs}Controller.h, MonitorModel.h,
 * ConfigFiles.h). Names parallel; QVariantMaps become typed data / JSON strings.
 */

/** RtaController.h — spectrum analyser. Several views share it via ids. */
interface RtaController {
    val caps: StateFlow<RtaCapsInfo?>
    val snapshot: StateFlow<RtaSnapshot?>

    var fftOrder: Int
    var avgMs: Int
    var peakDecay: Int

    /** A view (id) wants `mask` of `tap` (0 input, 1 output); the union is requested. */
    fun update(id: Int, tap: Int, mask: Int, wantsBins: Boolean)

    /** The view went away; the analyser stops 300 ms after the last release. */
    fun release(id: Int)

    fun appChannel(tap: Int, index: Int): Int

    /** 0 input, 1 output, -1 none. */
    fun tapOf(appChannel: Int): Int

    fun tapIndex(appChannel: Int): Int

    fun availableChannels(tap: Int): List<Int>
}

/** SiggenController.h — signal generator. */
interface SiggenController {
    val caps: StateFlow<SiggenCaps?>

    /** The config being edited (applied live while running). */
    val draft: StateFlow<SiggenConfig?>
    val status: StateFlow<SiggenStatus?>

    /** Poll status while a view watches. */
    var watching: Boolean

    fun selectType(type: Int)

    fun setParam(index: Int, value: Double)

    fun setFlag(flag: Int, on: Boolean)

    /** off -> on -> inverted -> off. */
    fun cycleOutput(output: Int)

    fun selectAllOutputs()

    fun clearOutputs()

    fun setLevelDb(db: Float)

    fun setDurationMs(ms: Long)

    fun setRepeat(count: Int)

    fun setGapMs(ms: Int)

    suspend fun start(): Boolean

    fun stop()

    fun stopNow()

    /** Short pink-noise burst on one output ("Identify"). */
    fun identify(output: Int)
}

/** StatsController.h — System Statistics. */
interface StatsController {
    var watching: Boolean

    /** STAT_* counter id -> value, refreshed slowly while watching. */
    val info: StateFlow<Map<Int, Long>>
    val buffers: StateFlow<BufferStats?>
    val reconnects: StateFlow<Int>

    fun resetWatermarks()
}

/** CsController.h — control surfaces (JSON model from the core's cs_snapshot). */
interface CsController {
    /** `cs_snapshot` JSON, "" before the first load. */
    val model: StateFlow<String>
    val supported: StateFlow<Boolean>
    val loaded: StateFlow<Boolean>
    val unsaved: StateFlow<Boolean>

    /** One op `{op, index, ...}` -> result JSON {ok, status, error, ...}. Some ops write flash. */
    suspend fun apply(opJson: String): String

    /** Live edit without waiting for the result. */
    fun sendOnly(opJson: String)

    fun refresh()

    suspend fun save(): Int

    suspend fun revert(): Int

    fun statusMessage(code: Int): String
}

/** One line of the Interrupt Monitor. */
data class MonitorLine(val id: Long, val timeMs: Long, val text: String)

/** MonitorModel.h — Interrupt Monitor log. */
interface MonitorModel {
    var watching: Boolean
    var paused: Boolean
    val lines: StateFlow<List<MonitorLine>>

    fun clear()

    fun allText(): String
}

/**
 * ConfigFiles.h — .dspipreset and filter text files. Android passes text, not
 * file URLs: the UI does SAF reads/writes. Results are the core's JSON.
 */
interface ConfigFiles {
    /** The `.dspipreset` document text. */
    suspend fun exportConfiguration(name: String): String

    /** JSON {ok, error, name, platform, firmware, savedUtc}. */
    fun inspectConfiguration(text: String): String

    /** JSON {ok, error, lines, clean, links, hardwareWritten}. */
    suspend fun importConfiguration(text: String, volumes: Boolean, hardware: Boolean): String

    suspend fun exportFilters(): String

    /** JSON for the channel picker. */
    suspend fun inspectFilters(text: String): String

    /** Apply to the chosen wire channels. JSON {ok, error, lines}. */
    suspend fun importFilters(text: String, wires: List<Int>): String
}
