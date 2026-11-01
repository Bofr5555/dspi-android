// How the home composables observe the bridge (the Compose side of the Qt
// NOTIFY signals; Linux qt/qml binds the same properties). Getters on the
// bridge read the latest snapshot, so a composable collects the flow that
// announces a change and then reads the getters.
package se.bofr.dspi.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import se.bofr.dspi.bridge.BridgeStatus
import se.bofr.dspi.bridge.ChannelMap
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.core.DspState

/** stateChanged: the full state; recomposes on every applied change. */
@Composable
internal fun DspiBridge.observeState(): DspState {
    val s by state.collectAsStateWithLifecycle()
    return s
}

/**
 * A slice of statusChanged that changes rarely (connection, active input
 * count), so the 60 ms poll does not recompose the screen.
 */
@Composable
internal fun <T> DspiBridge.observeStatus(select: (BridgeStatus) -> T): T {
    val flow = remember(this) { status.map(select).distinctUntilChanged() }
    val v by flow.collectAsStateWithLifecycle(select(status.value))
    return v
}

/** The 60 ms status itself, for draw-phase readers (meters, CPU). */
@Composable
internal fun DspiBridge.statusState(): State<BridgeStatus> = status.collectAsStateWithLifecycle()

@Composable
internal fun DspiBridge.observeDirty(): Boolean {
    val d by presetDirty.collectAsStateWithLifecycle()
    return d
}

/** The channel map of a state snapshot. */
internal val DspState.channelMap: ChannelMap get() = ChannelMap(numInputChannels, numOutputChannels)

/**
 * macOS `isDeviceReady`: connected and the connect fetches have landed (the
 * core's bulk image is valid), so a layout never fades in half-formed.
 */
@Composable
internal fun DspiBridge.observeDeviceReady(): Boolean {
    val connected = observeStatus { it.connected }
    return connected && observeState().bulkValid
}
