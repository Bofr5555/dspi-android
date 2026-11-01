// Port of DSPi-Console (macOS) `DSPi Console/PresetSnapshot.swift` @ v1.1.6-beta5
// (`PresetDiff.summary`, `UnsavedChangesAction`, `PresetAlerts`). The NSAlert
// model the menu actions use is :ui-common's (`se.bofr.dspi.ui.alert`, E6).
// The diff sentences come from the core (`presetChanges`, presetdiff.rs); only
// the summary formatting is the UI's, as on macOS and Linux (main.qml withUnsaved).
package se.bofr.dspi.feature.presets

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import se.bofr.dspi.ui.alert.AlertButton
import se.bofr.dspi.ui.alert.AlertButtonRole
import se.bofr.dspi.ui.alert.AlertStyle
import se.bofr.dspi.ui.alert.ConsoleAlert

/** PresetSnapshot.swift `PresetDiff`: the core's change sentences. */
data class PresetDiff(val changes: List<String>) {
    val hasChanges: Boolean get() = changes.isNotEmpty()

    /** Bulleted, truncated to [MAX_LINES] lines with "...and N more change(s)". */
    val summary: String
        get() {
            if (changes.size <= MAX_LINES) return changes.joinToString("\n") { "• $it" }
            val remaining = changes.size - MAX_LINES
            return changes.take(MAX_LINES).joinToString("\n") { "• $it" } +
                "\n...and $remaining more change${if (remaining == 1) "" else "s"}"
        }

    companion object {
        const val MAX_LINES = 15
    }
}

/** PresetSnapshot.swift `UnsavedChangesAction`. */
enum class UnsavedChangesAction { SAVE, DISCARD, CANCEL }

/** PresetSnapshot.swift `PresetAlerts`. */
object PresetAlerts {
    /** `showUnsavedChangesAlert(diff:)`: Save / Discard / Cancel. */
    fun unsavedChangesAlert(diff: PresetDiff, onAction: (UnsavedChangesAction) -> Unit) = ConsoleAlert(
        messageText = "Unsaved Changes",
        informativeText = "The current preset has unsaved changes:\n\n${diff.summary}\n\nSave before continuing?",
        style = AlertStyle.WARNING,
        buttons =
        listOf(
            AlertButton("Save", AlertButtonRole.DEFAULT) { onAction(UnsavedChangesAction.SAVE) },
            AlertButton("Discard", AlertButtonRole.NORMAL) { onAction(UnsavedChangesAction.DISCARD) },
            AlertButton("Cancel", AlertButtonRole.CANCEL) { onAction(UnsavedChangesAction.CANCEL) }
        )
    )
}

/**
 * Decision E9: a device sequence (one or more calls, flash writes, the result
 * alert) runs to its end even when the screen goes away; it is launched on the
 * controller's ViewModel scope and is not cancelled with it.
 */
internal fun CoroutineScope.launchDeviceSequence(block: suspend CoroutineScope.() -> Unit): Job = launch(NonCancellable, block = block)
