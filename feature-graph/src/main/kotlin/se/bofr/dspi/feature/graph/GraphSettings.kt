// What the response graph derives from macOS AppSettings (DSPi-Console-b5:
// DSPi Console/DSPi_ConsoleApp.swift l.84-200 @ b5fdeac; the store itself is
// :ui-common's `AppSettings`, read through `LocalAppSettings`, decision F3),
// and RtaChannelSelection (SpectrumAnalyserView.swift l.611).
package se.bofr.dspi.feature.graph

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.ui.settings.AppSettings
import se.bofr.dspi.ui.settings.RtaSpectrumView

/** dB at the top of the plot (macOS GraphView `dbTop`). */
val AppSettings.dbTop get() = graphDBCenter + graphDBRange / 2.0

/** dB at the bottom of the plot (macOS GraphView `dbBottom`). */
val AppSettings.dbBottom get() = graphDBCenter - graphDBRange / 2.0

/** Phase axis scales with the vertical zoom: ±180° at the default 50 dB range. */
val AppSettings.phaseTop get() = 180.0 * graphDBRange / 50.0

/** Whether a page draws its spectrum in the graph (macOS `rtaShows(.graph, onDashboard:)`). */
fun AppSettings.rtaShowsGraph(onDashboard: Boolean) = rtaShows(RtaSpectrumView.GRAPH, onDashboard)

fun AppSettings.withRtaShowsGraph(onDashboard: Boolean, on: Boolean) = withRtaShows(RtaSpectrumView.GRAPH, onDashboard, on)

/** The four SCALE settings restored by Graph Setup's Reset. */
fun AppSettings.resetScale() = copy(graphMinFreq = 15.0, graphMaxFreq = 20000.0, graphDBRange = 50.0, graphDBCenter = 0.0)

/**
 * The band selection shared between the graph and HOME's band list. HOME
 * provides one per channel page; without it each GraphView keeps its own.
 */
val LocalPeqGraphSelection = staticCompositionLocalOf<PeqGraphSelection?> { null }

/** Channels at one analyser tap (macOS `RtaChannelSelection`). */
class RtaChannelSelection(val tap: Int, channels: List<Int>) {
    /** Channels at [tap] (input row, or matrix output index), ascending. */
    val channels: List<Int> = channels.toSortedSet().toList()

    override fun equals(other: Any?) = other is RtaChannelSelection && other.tap == tap && other.channels == channels

    override fun hashCode() = tap * 31 + channels.hashCode()

    override fun toString() = storageKey

    val isEmpty get() = channels.isEmpty()

    val mask: Int get() = channels.fold(0) { m, c -> m or (1 shl c) }

    /** "in:0,2" or "out:1"; "out:" is a deliberate choice of nothing. */
    val storageKey: String get() = (if (tap == DspiConstants.RTA_TAP_INPUT) "in:" else "out:") + channels.joinToString(",")

    fun restricted(to: List<Int>) = RtaChannelSelection(tap, channels.filter { it in to })

    fun toggled(channel: Int) = RtaChannelSelection(tap, if (channel in channels) channels - channel else channels + channel)

    companion object {
        val NONE = RtaChannelSelection(DspiConstants.RTA_TAP_OUTPUT, emptyList())

        fun fromStorageKey(key: String): RtaChannelSelection? {
            val colon = key.indexOf(':')
            if (colon < 0) return null
            val tap =
                when (key.substring(0, colon)) {
                    "in" -> DspiConstants.RTA_TAP_INPUT
                    "out" -> DspiConstants.RTA_TAP_OUTPUT
                    else -> return null
                }
            val rest = key.substring(colon + 1)
            if (rest.isEmpty()) return RtaChannelSelection(tap, emptyList())
            val chans = rest.split(',').map { it.trim().toIntOrNull() ?: return null }
            if (chans.any { it < 0 || it >= DspiConstants.RTA_MAX_CHANNELS }) return null
            return RtaChannelSelection(tap, chans)
        }
    }
}

/**
 * The polled active-input count (status only; it never bumps stateRevision),
 * observed: a key for anything built on `liveInputCount()` /
 * `rta.availableChannels(INPUT)` (reviews/stale-state-audit.md G2).
 */
@Composable
internal fun DspiBridge.observeLiveInputs(): Int {
    val flow =
        remember(this) {
            status.map { it.activeInputChannels }.distinctUntilChanged()
        }
    return flow.collectAsStateWithLifecycle(status.value.activeInputChannels).value
}
