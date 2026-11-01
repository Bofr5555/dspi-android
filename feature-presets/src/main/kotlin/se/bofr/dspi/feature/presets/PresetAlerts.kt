// Port of DSPi-Console (macOS) `DSPi Console/PresetSnapshot.swift` @ v1.1.6-beta5
// (`PresetDiff.summary`, `UnsavedChangesAction`, `PresetAlerts`) plus the NSAlert
// model every menu action of ContentView / ToolsMenuActions / FileMenuActions /
// AutoEQMenuActions uses (`messageText`, `informativeText`, `alertStyle`,
// `addButton(withTitle:)`, accessory checkboxes / text field).
// The diff sentences come from the core (`presetChanges`, presetdiff.rs); only
// the summary formatting is the UI's, as on macOS and Linux (main.qml withUnsaved).
package se.bofr.dspi.feature.presets

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** NSAlert.Style. */
enum class AlertStyle { INFORMATIONAL, WARNING, CRITICAL }

/** How a button is drawn: the default (first) button, a destructive one, or a plain one. */
enum class AlertButtonRole { DEFAULT, DESTRUCTIVE, NORMAL, CANCEL }

/** An accessory checkbox (NSButton(checkboxWithTitle:)). */
data class AlertCheckbox(val key: String, val title: String, val detail: String = "", val checked: Boolean = false)

/** An accessory text field (the preset Rename sheet). */
data class AlertTextField(val placeholder: String, val initialText: String, val maxLength: Int = PRESET_NAME_MAX_LENGTH)

/** What the user entered in the alert when a button was pressed. */
data class AlertResponse(val checked: Set<String> = emptySet(), val text: String = "")

/** `alert.addButton(withTitle:)` with its handler (the `runModal()` return branch). */
class AlertButton(val title: String, val role: AlertButtonRole = AlertButtonRole.NORMAL, val action: (AlertResponse) -> Unit = {})

/**
 * One modal alert. [buttons] are in NSAlert `addButton` order: the first is the
 * default (rightmost) button.
 */
data class ConsoleAlert(
    val messageText: String,
    val informativeText: String,
    val style: AlertStyle,
    val buttons: List<AlertButton>,
    val checkboxes: List<AlertCheckbox> = emptyList(),
    val textField: AlertTextField? = null
) {
    fun button(title: String): AlertButton = buttons.first { it.title == title }
}

/**
 * The single modal alert slot (NSAlert.runModal(): one at a time). A button
 * press closes the alert first, then runs the handler, which may show the next
 * alert.
 */
class AlertHost {
    private val _alert = MutableStateFlow<ConsoleAlert?>(null)
    val alert: StateFlow<ConsoleAlert?> = _alert.asStateFlow()

    /** ConfigurationProgressSheet: text of the running operation, null when idle. */
    private val _progress = MutableStateFlow<String?>(null)
    val progress: StateFlow<String?> = _progress.asStateFlow()

    fun show(alert: ConsoleAlert) {
        _alert.value = alert
    }

    /** The user pressed [button] of the current alert. */
    fun respond(button: AlertButton, response: AlertResponse = AlertResponse()) {
        _alert.value = null
        button.action(response)
    }

    /** Dismissed without a button (back / outside tap): the cancel button's handler, if any. */
    fun dismiss() {
        val a = _alert.value ?: return
        val cancel = a.buttons.firstOrNull { it.role == AlertButtonRole.CANCEL } ?: a.buttons.singleOrNull()
        _alert.value = null
        cancel?.action?.invoke(AlertResponse())
    }

    fun beginProgress(text: String) {
        _progress.value = text
    }

    fun endProgress() {
        _progress.value = null
    }

    // The menu action structs' showSuccess / showError / showInfo helpers.
    fun showSuccess(message: String) = show(ConsoleAlert("Success", message, AlertStyle.INFORMATIONAL, listOf(ok())))

    fun showError(message: String) = show(ConsoleAlert("Error", message, AlertStyle.CRITICAL, listOf(ok())))

    fun showInfo(message: String) = show(ConsoleAlert("Information", message, AlertStyle.INFORMATIONAL, listOf(ok())))

    /** A titled warning with a single OK ("Save Failed", "Load Failed", "Copy Failed", "Clear Failed"). */
    fun showWarning(title: String, message: String) = show(ConsoleAlert(title, message, AlertStyle.WARNING, listOf(ok())))

    private fun ok() = AlertButton("OK", AlertButtonRole.DEFAULT)
}

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
