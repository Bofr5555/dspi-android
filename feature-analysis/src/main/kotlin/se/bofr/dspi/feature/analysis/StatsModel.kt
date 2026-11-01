// Port of DSPi-Console-b5:DSPi Console/StatsView.swift @ b5fdeac: the
// formatters, BufferFillThresholds, BufferFillHistory, the starvation
// bookkeeping of StatsViewModel.fetchStarvationStats, and the status strings
// of AdatStatus / I2sSlaveStatus / LgSoundSyncStatus (DSPViewModel.swift).
// The device reads themselves are the bridge's StatsController and fetch*
// calls; this file only turns their values into what the view shows.
package se.bofr.dspi.feature.analysis

import java.util.Locale
import se.bofr.dspi.core.AdatOutStatus
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.core.InputLockStatus
import se.bofr.dspi.core.LgStatus
import se.bofr.dspi.core.SpdifRxStatus

/** "-" for a value the device has not given (Swift's dash). */
const val STATS_DASH = "-"

/** SYSTEM INFORMATION rows (StatsView.systemSection). */
object SystemInfoFormat {
    fun clock(hz: Long?): String = hz?.let { cFixed(it / 1_000_000.0, 1) + " MHz" } ?: STATS_DASH

    fun coreVoltage(mv: Long?): String = mv?.let { cFixed(it / 1000.0, 2) + " V" } ?: STATS_DASH

    fun sampleRate(hz: Long?): String = hz?.let { cFixed(it / 1000.0, 1) + " kHz" } ?: STATS_DASH

    /** Centi-degrees, a signed 16-bit value widened to u32 by the device. */
    fun temperature(centi: Long?): String = centi?.let {
        val signed = if (it and 0x8000L != 0L && it <= 0xFFFFL) it - 0x10000L else it.toInt().toLong()
        cFixed(signed / 100.0, 1) + " °C"
    } ?: STATS_DASH
}

/** Swift `formatElapsed(_:)` (starvation timers): `1h 02m 03s` or `2m 03s`. */
fun statsFormatElapsed(seconds: Long): String {
    val total = maxOf(0L, seconds)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%dh %02dm %02ds", h, m, s) else String.format(Locale.US, "%dm %02ds", m, s)
}

/** The three colours a buffer fill can be (Swift `.green/.yellow/.red`). */
enum class FillHealth { GREEN, YELLOW, RED }

/** Swift `BufferFillThresholds`. */
enum class BufferFillThresholds {
    SPDIF,
    PDM_DMA,
    PDM_RING;

    fun health(pct: Int): FillHealth = when (this) {
        SPDIF -> when {
            pct == 0 || pct == 100 -> FillHealth.RED
            pct < 25 || pct > 75 -> FillHealth.YELLOW
            else -> FillHealth.GREEN
        }
        PDM_DMA -> when {
            pct > 50 -> FillHealth.RED
            pct < 5 || pct > 30 -> FillHealth.YELLOW
            else -> FillHealth.GREEN
        }
        PDM_RING -> when {
            pct > 50 -> FillHealth.RED
            pct > 20 -> FillHealth.YELLOW
            else -> FillHealth.GREEN
        }
    }
}

/**
 * Swift `BufferFillHistory`: the last ~15 s of buffer fill samples, one ring
 * per series (S/PDIF slots 0-3, then PDM DMA and PDM ring). Sample `s` lives
 * at ring index `s % CAPACITY` for as long as it is kept.
 */
class BufferFillHistory {
    private val rings = Array(SERIES_COUNT) { FloatArray(CAPACITY) { ABSENT } }

    /** Samples appended so far. */
    var total: Int = 0
        private set

    fun append(stats: se.bofr.dspi.core.BufferStats) {
        val slot = total % CAPACITY
        val spdifCount = minOf(stats.numSpdif, 4)
        for (i in 0 until 4) {
            rings[i][slot] = if (i < spdifCount) minOf(100, stats.spdif.getOrNull(i)?.fillPct ?: 0).toFloat() else ABSENT
        }
        rings[PDM_DMA_SERIES][slot] = if (stats.pdmActive) stats.pdm.dmaFillPct.toFloat() else ABSENT
        rings[PDM_RING_SERIES][slot] = if (stats.pdmActive) stats.pdm.ringFillPct.toFloat() else ABSENT
        total++
    }

    /** Fill % of [series] at sample [s], or NaN when aged out or that buffer was not running. */
    fun value(series: Int, s: Int): Float {
        if (s < 0 || s >= total || s < total - CAPACITY) return Float.NaN
        val v = rings[series][s % CAPACITY]
        return if (v == ABSENT) Float.NaN else v
    }

    fun clear() {
        for (r in rings) r.fill(ABSENT)
        total = 0
    }

    companion object {
        /** About 15 s at the 60 ms buffer poll. */
        const val CAPACITY = 256
        const val SERIES_COUNT = 6
        const val PDM_DMA_SERIES = 4
        const val PDM_RING_SERIES = 5
        private const val ABSENT = -1f
    }
}

/**
 * StatsViewModel.fetchStarvationStats: the delta since the previous 2 s read
 * and the host times of the last two reads that saw new events.
 */
class StarvationTracker {
    var total: Long = 0
        private set
    var delta: Long = 0
        private set
    var lastEventMs: Long? = null
        private set
    var previousEventMs: Long? = null
        private set
    private var previousTotal: Long? = null

    /** A new total read at host time [nowMs]. Wrap-safe 32-bit subtraction. */
    fun update(newTotal: Long, nowMs: Long) {
        val prev = previousTotal
        delta = if (prev == null) 0 else (newTotal - prev) and 0xFFFF_FFFFL
        previousTotal = newTotal
        total = newTotal
        if (delta > 0) {
            previousEventMs = lastEventMs
            lastEventMs = nowMs
        }
    }

    /** A new connection: counters start over (Swift keeps a new view model per window). */
    fun reset() {
        total = 0
        delta = 0
        lastEventMs = null
        previousEventMs = null
        previousTotal = null
    }
}

/** Per-instance starvation counters (Out 1/2 …): at least two rows, as many as the device has S/PDIF slots. */
fun starvationInstanceCount(numSpdif: Int): Int = maxOf(numSpdif, 2)

fun starvationInstanceLabel(i: Int): String = "Out ${i * 2 + 1}/${i * 2 + 2}"

/** Swift BufferFillRow title for slot [i]: `Out 1/2 (SPDIF)` or `(I2S)`. */
fun bufferSlotTitle(i: Int, outputType: Int): String = "Out ${i * 2 + 1}/${i * 2 + 2} (${if (outputType == 1) "I2S" else "SPDIF"})"

/** Swift `BufferFillRow` watermarks text. */
fun watermarkText(min: Int, max: Int): String = "$min–$max%"

/** A state row's dot colour (Swift `.gray/.yellow/.green/.orange`). */
enum class StateDot { GRAY, YELLOW, GREEN, ORANGE }

/** Swift `AdatStatus.stateString` / `stateColor`. */
fun adatStateString(s: AdatOutStatus): String = when {
    !s.enabled -> "Disabled"
    s.active -> "Streaming"
    !s.rateOk -> "Suspended (rate above 48 kHz)"
    else -> "Enabled"
}

fun adatStateDot(s: AdatOutStatus): StateDot = when {
    !s.enabled -> StateDot.GRAY
    s.active -> StateDot.GREEN
    !s.rateOk -> StateDot.ORANGE
    else -> StateDot.YELLOW
}

/** I2S slave clock (Swift `I2sSlaveStatus`): 0 inactive, 1 acquiring, 2 relocking, 3 locked. */
const val I2S_CLOCK_MODE_SLAVE = 1

fun i2sSlaveStateString(state: Int): String = when (state) {
    0 -> "Inactive"
    1 -> "Acquiring"
    2 -> "Relocking"
    3 -> "Locked"
    else -> "Unknown ($state)"
}

fun i2sSlaveStateDot(state: Int): StateDot = when (state) {
    1 -> StateDot.YELLOW
    2 -> StateDot.ORANGE
    3 -> StateDot.GREEN
    else -> StateDot.GRAY
}

fun i2sDetectedRateString(s: InputLockStatus): String =
    if (s.state == 3 && s.detectedRate > 0) cFixed(s.detectedRate / 1000.0, 1) + " kHz" else STATS_DASH

fun i2sMeasuredRateString(s: InputLockStatus): String = if (s.measuredHz > 0) cFixed(s.measuredHz / 1000.0, 1) + " kHz" else STATS_DASH

/** Swift `LgSoundSyncStatus.volumeString`: 0xFF = never decoded. */
fun lgVolumeString(s: LgStatus): String = if (s.volume == 0xFF) STATS_DASH else "${s.volume} / 100"

/** The STAT_* counters as the view reads them (null = not read yet). */
data class StatCounters(private val info: Map<Int, Long>) {
    operator fun get(which: Int): Long? = info[which]

    fun count(which: Int): Long = info[which] ?: 0L

    val starvationTotal: Long? get() = info[DspiConstants.STAT_STARVATION_TOTAL]

    fun starvationInstance(i: Int): Long = count(DspiConstants.STAT_STARVATION_FIRST + i)
}

// ── S/PDIF receiver (Swift `SpdifRxStatus`, StatsView.swift) ──

/** 0 inactive, 1 acquiring, 2 locked, 3 relocking. */
fun spdifRxIsLocked(state: Int): Boolean = state == 2

fun spdifRxStateString(state: Int): String = when (state) {
    0 -> "Inactive"
    1 -> "Acquiring"
    2 -> "Locked"
    3 -> "Relocking"
    else -> "Unknown ($state)"
}

fun spdifRxStateDot(state: Int): StateDot = when (state) {
    1 -> StateDot.YELLOW
    2 -> StateDot.GREEN
    3 -> StateDot.ORANGE
    else -> StateDot.GRAY
}

/** Swift `sourceString` (INPUT_SOURCE_*: 1 S/PDIF, 2 I2S, 4..6 S/PDIF 2..4, else USB). */
fun spdifRxSourceString(source: Int): String = when (source) {
    1 -> "S/PDIF 1"
    4 -> "S/PDIF 2"
    5 -> "S/PDIF 3"
    6 -> "S/PDIF 4"
    2 -> "I2S"
    else -> "USB"
}

fun spdifRxSampleRateString(s: SpdifRxStatus): String =
    if (spdifRxIsLocked(s.state) && s.sampleRate > 0) cFixed(s.sampleRate / 1000.0, 1) + " kHz" else STATS_DASH

/** Library internal state: 0 no signal, 1 waiting stable, 2 stable. */
fun spdifRxLibStateString(libState: Int): String = when (libState) {
    0 -> "No Signal"
    1 -> "Waiting Stable"
    2 -> "Stable"
    else -> "Unknown ($libState)"
}
