// Port of DSPi-Console (macOS) `DSPi Console/MatrixMixerView.swift` bindings and
// actions (matrixRoutingBinding/GainBinding/InvertBinding, requestOutputEnable,
// commitRename, the alert's switchToPDM/switchFromPDM, the header menu) and
// `Commands.swift` setOutputEnable/applyDirectRouting/clearAllRoutes @ v1.1.6-beta5,
// driving DSPi-Console-Linux's bridge calls as qt/qml/MatrixMixerWindow.qml does
// (setMatrixRoute, setOutput*, directRouting, clearRouting,
// enableOutputResolvingConflict) @ 4683a2c.
package se.bofr.dspi.feature.matrix

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.bridge.Throttle

/**
 * What the matrix UI does to the device. Live drags go through one [Throttle]
 * per control (first value at once, then the latest every 30 ms, `sendOnly`
 * where the bridge has it); the release cancels the throttle and commits once.
 */
class MatrixActions(private val bridge: DspiBridge, private val scope: CoroutineScope) {
    private val throttles = HashMap<String, Throttle<Float>>()
    private val routeThrottles = HashMap<Long, Throttle<MatrixRoute>>()

    private fun throttle(key: String, send: (Float) -> Unit): Throttle<Float> = throttles.getOrPut(key) {
        Throttle(scope, THROTTLE_MS, send)
    }

    // ═══ Crosspoints (matrix*Binding setters) ═══

    /** matrixRoutingBinding set: toggle the connection, keep gain and polarity. */
    fun toggleRoute(row: Int, col: Int, route: MatrixRoute) =
        bridge.setMatrixRoute(row, col, !route.isConnected, route.gain, route.isInverted)

    /** matrixInvertBinding set. */
    fun toggleInvert(row: Int, col: Int, route: MatrixRoute) =
        bridge.setMatrixRoute(row, col, route.isConnected, route.gain, !route.isInverted)

    /**
     * matrixGainBinding during a drag. The contract's `setMatrixRoute` has no
     * sendOnly flag, so live values are throttled full writes
     * (contract change request in the package report).
     */
    fun matrixGainLive(row: Int, col: Int, route: MatrixRoute, db: Float) = routeThrottles
        .getOrPut(routeKey(row, col)) {
            Throttle(scope, THROTTLE_MS) { r -> bridge.setMatrixRoute(row, col, r.isConnected, r.gain, r.isInverted) }
        }.submit(route.copy(gain = db))

    /** matrixGainBinding set (release / typed value). */
    fun matrixGainCommit(row: Int, col: Int, route: MatrixRoute, db: Float) {
        routeThrottles[routeKey(row, col)]?.cancel()
        bridge.setMatrixRoute(row, col, route.isConnected, db, route.isInverted)
    }

    private fun routeKey(row: Int, col: Int): Long = (row.toLong() shl 32) or col.toLong()

    // ═══ Input trim (8-channel mode; macOS setPreampChannel) ═══

    fun inputTrimLive(input: Int, db: Float) = throttle("trim/$input") { bridge.setInputPreamp(input, it, sendOnly = true) }.submit(db)

    fun inputTrimCommit(input: Int, db: Float) {
        throttles["trim/$input"]?.cancel()
        bridge.setInputPreamp(input, db, sendOnly = false)
    }

    // ═══ Output controls ═══

    fun outputGainLive(output: Int, db: Float) = throttle("og/$output") {
        bridge.sendOutputGainToDevice(output, roundOutputGain(it))
    }.submit(db)

    /** Commands.swift setOutputGain (0.1 dB). */
    fun outputGainCommit(output: Int, db: Float) {
        throttles["og/$output"]?.cancel()
        bridge.setOutputGain(output, roundOutputGain(db))
    }

    fun outputDelayLive(output: Int, ms: Float) =
        throttle("od/$output") { bridge.sendOutputDelayToDevice(output, roundOutputDelay(it)) }.submit(ms)

    /** Commands.swift setOutputDelay (whole ms). */
    fun outputDelayCommit(output: Int, ms: Float) {
        throttles["od/$output"]?.cancel()
        bridge.setOutputDelay(output, roundOutputDelay(ms))
    }

    fun setOutputMute(output: Int, muted: Boolean) = bridge.setOutputMute(output, muted)

    /**
     * Commands.swift setOutputEnable: the output, then (macOS overview mode)
     * its graph visibility follows the enable state.
     */
    fun setOutputEnable(output: Int, enabled: Boolean) {
        scope.launch {
            bridge.setOutputEnable(output, enabled)
            bridge.setChannelVisible(output + 2, enabled)
        }
    }

    /**
     * The alert's primary button (macOS switchToPDM / switchFromPDM): switch
     * Core 1 to [output]'s side, disabling the outputs on the other side.
     */
    fun resolveConflict(conflict: PendingConflict) {
        val off = bridge.core1ConflictOutputs(conflict.output)
        bridge.enableOutputResolvingConflict(conflict.output)
        for (o in off) bridge.setChannelVisible(o + 2, false)
        bridge.setChannelVisible(conflict.output + 2, true)
    }

    // ═══ ROUTING band (8-channel) ═══

    /** "Direct 1:1" (macOS applyDirectRouting). */
    fun applyDirectRouting() = bridge.directRouting()

    /** "Clear" (macOS clearAllRoutes). */
    fun clearAllRoutes() = bridge.clearRouting()

    // ═══ Column header menu ═══

    /** macOS commitRename: trimmed; an empty name is ignored. */
    fun commitRename(output: Int, text: String) {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) bridge.setChannelName(output + 2, trimmed)
    }

    fun identifyOutput(output: Int) = bridge.siggen.identify(output)

    fun copyChannelParams(output: Int) = bridge.copyChannel(output + 2)

    fun pasteChannelParams(output: Int) = bridge.pasteChannel(output + 2)

    fun canPaste(): Boolean = bridge.canPaste()

    fun cancelAll() {
        throttles.values.forEach { it.cancel() }
        routeThrottles.values.forEach { it.cancel() }
    }

    companion object {
        const val THROTTLE_MS = 30L

        /** README: "Names can be up to 31 characters". */
        const val MAX_NAME_LENGTH = 31
    }
}
