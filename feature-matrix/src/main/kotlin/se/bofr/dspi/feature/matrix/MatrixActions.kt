// Port of DSPi-Console (macOS) `DSPi Console/MatrixMixerView.swift` bindings and
// actions (matrixRoutingBinding/GainBinding/InvertBinding, requestOutputEnable,
// commitRename, the alert's switchToPDM/switchFromPDM, the header menu) and
// `Commands.swift` setOutputEnable/applyDirectRouting/clearAllRoutes @ v1.1.6-beta5,
// driving DSPi-Console-Linux's bridge calls as qt/qml/MatrixMixerWindow.qml does
// (setMatrixRoute, setOutput*, directRouting, clearRouting) @ 4683a2c.
package se.bofr.dspi.feature.matrix

import java.util.WeakHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.bridge.Throttle
import se.bofr.dspi.ui.roundOutputDelay
import se.bofr.dspi.ui.roundOutputGain

/**
 * The matrix screen's controller: what the UI does to the device, plus the
 * open alert/dialog state (decision E9: device sequences and open alerts
 * survive configuration changes, so this lives as long as the bridge — see
 * [forBridge] — and never in a composition scope).
 *
 * Live drags go through one [Throttle] per control (first value at once, then
 * the latest every 30 ms, `sendOnly` where the bridge has it); the release
 * cancels the throttle and commits once.
 *
 * [isOverviewMode]: macOS `DSPViewModel.isOverviewMode` (graph showing the
 * dashboard rather than one channel page; :ui-common `AppState`). Graph
 * visibility follows ENABLE and Direct 1:1 only in overview mode, as
 * Commands.swift setOutputEnable / applyDirectRouting do.
 */
class MatrixActions(
    private val bridge: DspiBridge,
    private val scope: CoroutineScope,
    private val isOverviewMode: () -> Boolean = { false }
) {
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
     * matrixGainBinding during a drag: throttled `setMatrixRoute(sendOnly = true)`;
     * [matrixGainCommit] writes the final value once with `sendOnly = false`.
     */
    fun matrixGainLive(row: Int, col: Int, route: MatrixRoute, db: Float) = routeThrottles
        .getOrPut(routeKey(row, col)) {
            Throttle(scope, THROTTLE_MS) { r -> bridge.setMatrixRoute(row, col, r.isConnected, r.gain, r.isInverted, sendOnly = true) }
        }.submit(route.copy(gain = db))

    /** matrixGainBinding set (release / typed value). */
    fun matrixGainCommit(row: Int, col: Int, route: MatrixRoute, db: Float) {
        routeThrottles[routeKey(row, col)]?.cancel()
        bridge.setMatrixRoute(row, col, route.isConnected, db, route.isInverted, sendOnly = false)
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

    /** ENABLE tapped without a conflict (macOS requestOutputEnable -> vm.setOutputEnable). */
    fun setOutputEnable(output: Int, enabled: Boolean) {
        scope.launch { outputEnable(output, enabled) }
    }

    /**
     * Commands.swift setOutputEnable: the output, then — in overview mode and
     * only if the device took it — its graph visibility follows.
     */
    private suspend fun outputEnable(output: Int, enabled: Boolean): Boolean {
        val ok = bridge.setOutputEnable(output, enabled)
        if (ok && isOverviewMode()) bridge.setChannelVisible(output + 2, enabled)
        return ok
    }

    // ═══ PDM conflict alert (state survives rotation, E9) ═══

    private val _pendingConflict = MutableStateFlow<PendingConflict?>(null)

    /** macOS `@State pendingConflict`: the open "Warning" alert, if any. */
    val pendingConflict: StateFlow<PendingConflict?> = _pendingConflict.asStateFlow()

    /** macOS requestOutputEnable: disable at once, enable directly or ask first. */
    fun requestOutputEnable(state: MatrixMixerState, output: Int) {
        when (val a = state.requestOutputEnable(output)) {
            is EnableAction.Set -> setOutputEnable(output, a.enabled)
            is EnableAction.Confirm -> _pendingConflict.value = a.conflict
        }
    }

    fun dismissConflict() {
        _pendingConflict.value = null
    }

    /**
     * The alert's primary button: macOS switchToPDM (every EQ-worker output
     * off, then PDM on) or switchFromPDM (PDM off, then [PendingConflict.output]
     * on). Each step is a confirmed setOutputEnable; the bridge's snapshot
     * after each call is the macOS read-back.
     */
    fun resolveConflict(conflict: PendingConflict, eqWorkerRange: IntRange, pdmOutputIndex: Int) {
        _pendingConflict.value = null
        scope.launch {
            if (conflict.isPDM) {
                for (i in eqWorkerRange) outputEnable(i, false)
                outputEnable(pdmOutputIndex, true)
            } else {
                outputEnable(pdmOutputIndex, false)
                outputEnable(conflict.output, true)
            }
        }
    }

    // ═══ Rename dialog (state survives rotation, E9) ═══

    private val _renamingOutput = MutableStateFlow<Int?>(null)

    /** macOS `@State renamingOutput`. */
    val renamingOutput: StateFlow<Int?> = _renamingOutput.asStateFlow()

    fun startRename(output: Int) {
        _renamingOutput.value = output
    }

    fun cancelRename() {
        _renamingOutput.value = null
    }

    // ═══ ROUTING band (8-channel) ═══

    /** "Direct 1:1" (macOS applyDirectRouting). */
    fun applyDirectRouting() = bridge.directRouting(-1, overview = isOverviewMode())

    /** "Clear" (macOS clearAllRoutes). */
    fun clearAllRoutes() = bridge.clearRouting()

    // ═══ Column header menu ═══

    /** macOS commitRename: trimmed; an empty name is ignored. */
    fun commitRename(output: Int, text: String) {
        _renamingOutput.value = null
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

        private val controllers = WeakHashMap<DspiBridge, MatrixActions>()

        /**
         * The app-scoped controller for [bridge] (one per bridge, as long as
         * the bridge lives): its scope outlives the screen, so a rotation
         * never cuts a multi-call sequence short. [isOverviewMode] reads the
         * app's one AppState. Main thread only.
         */
        fun forBridge(bridge: DspiBridge, isOverviewMode: () -> Boolean = { false }): MatrixActions = controllers.getOrPut(bridge) {
            MatrixActions(bridge, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), isOverviewMode)
        }

        /** README: "Names can be up to 31 characters". */
        const val MAX_NAME_LENGTH = 31
    }
}
