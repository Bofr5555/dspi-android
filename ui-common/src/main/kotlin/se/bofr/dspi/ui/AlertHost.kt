// An NSAlert queue for Compose: macOS code calls `alert.runModal()` from
// anywhere; here a screen hoists one [AlertHostState] and renders
// [AlertHost], and any handler can [AlertHostState.show] an alert.
package se.bofr.dspi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember

/** One queued alert (macOS `messageText`, `informativeText`, buttons in NSAlert order). */
data class ConsoleAlert(val title: String, val message: String, val buttons: List<AlertButton> = listOf(AlertButton("OK") {}))

/**
 * Alerts waiting to be shown, first in first out. A button's action runs
 * after its alert is dismissed, so it may [show] the next one (e.g. "Save
 * Failed" after a confirmation).
 */
@Stable
class AlertHostState {
    private val queue = mutableStateListOf<ConsoleAlert>()

    val current: ConsoleAlert? get() = queue.firstOrNull()

    fun show(alert: ConsoleAlert) {
        queue.add(alert)
    }

    /** A one-button "OK" alert (failure reports). */
    fun show(title: String, message: String) = show(ConsoleAlert(title, message))

    internal fun pop() {
        if (queue.isNotEmpty()) queue.removeAt(0)
    }
}

@Composable
fun rememberAlertHostState(): AlertHostState = remember { AlertHostState() }

/** Renders the head of [state]'s queue as a [DspiAlertDialog]; outside tap = the Cancel-role button (or nothing). */
@Composable
fun AlertHost(state: AlertHostState) {
    val alert = state.current ?: return
    DspiAlertDialog(
        title = alert.title,
        message = alert.message,
        buttons =
        alert.buttons.map { b ->
            b.copy(action = {
                state.pop()
                b.action()
            })
        },
        onDismiss = {
            state.pop()
            alert.buttons.firstOrNull { it.role == AlertButton.Role.Cancel }?.action?.invoke()
        }
    )
}
