// The shared selection state of DSPi-Console-b5:DSPi Console/DSPViewModel.swift
// @ b5fdeac (`activeEqChannel`, `isOverviewMode`, `updateSelection(to:)`)
// that macOS keeps on the one view model every window reads. On Android the
// main window is split over routes (home, channel/{ch}, graph, matrix), so
// the selection lives here, one per process, provided by :app through
// [LocalAppState]. Curve visibility stays the bridge's (`channelVisible`).
package se.bofr.dspi.ui.state

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which channel page the main window shows. HOME writes it (the channel page
 * on a phone, the sidebar selection on a tablet, null on the dashboard);
 * the pop-out graph follows it ("Follow Channel Selection") and the matrix
 * reads [isOverviewMode] (Commands.swift `setOutputEnable`,
 * `applyDirectRouting`). Main thread only.
 */
class AppState {
    private val _activeEqChannel = MutableStateFlow<Int?>(null)

    /** macOS `activeEqChannel`: the app channel id of the open channel page, null on the overview. */
    val activeEqChannel: StateFlow<Int?> = _activeEqChannel.asStateFlow()

    /** macOS `isOverviewMode`: the graph shows the dashboard rather than one channel page. */
    val isOverviewMode: Boolean get() = _activeEqChannel.value == null

    /** macOS `updateSelection(to:)` / `updateSelectionToOutput`: open a channel page ([channel]) or the overview (null). */
    fun updateSelection(channel: Int?) {
        _activeEqChannel.value = channel
    }
}

private val processAppState = AppState()

/** The app's one [AppState]; :app provides it, previews and tests get a shared default. */
val LocalAppState = staticCompositionLocalOf { processAppState }
