// Port of DSPi-Console (macOS) `DSPi Console/DSPi_ConsoleApp.swift` @ v1.1.6-beta5
// `AutoEQMenuActions` (Update Database…: Import File…, Reset to Built-in) and
// the AutoEQ menu's Favorite Profiles / Clear Favorites. "Rebuild from GitHub"
// is not offered (PRESETS deviation 5).
package se.bofr.dspi.feature.presets.autoeq

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.feature.presets.AlertButton
import se.bofr.dspi.feature.presets.AlertButtonRole
import se.bofr.dspi.feature.presets.AlertHost
import se.bofr.dspi.feature.presets.AlertStyle
import se.bofr.dspi.feature.presets.ConsoleAlert

class AutoEQMenuActions(
    private val manager: AutoEQManager,
    private val bridge: DspiBridge,
    private val scope: CoroutineScope,
    private val host: AlertHost,
    /** Where database files are read and parsed (Dispatchers.IO in the app). */
    private val io: CoroutineDispatcher
) {
    /** Favorite Profiles > entry, and the browser's Apply: applies immediately (live, unsaved). */
    fun applyProfile(entry: HeadphoneEntry): Boolean {
        if (!manager.applyProfile(entry, bridge)) {
            host.showError("No device connected.")
            return false
        }
        return true
    }

    /** "Update Database...": shows the current database and the update methods. */
    fun updateDatabase(onImportFile: () -> Unit) {
        val buttons =
            buildList {
                add(AlertButton("Import File...", AlertButtonRole.DEFAULT) { onImportFile() })
                if (manager.hasUserDatabase) add(AlertButton("Reset to Built-in", AlertButtonRole.NORMAL) { resetToBuiltIn() })
                add(AlertButton("Cancel", AlertButtonRole.CANCEL))
            }
        host.show(
            ConsoleAlert(
                "Update AutoEQ Database",
                "Current database: ${manager.databaseDate.value ?: "Unknown"}\nEntries: ${manager.entries.value.size}\n\nChoose an update method:",
                AlertStyle.INFORMATIONAL,
                buttons
            )
        )
    }

    /** "Import File...": an `autoeq_database.json` was picked and read. */
    fun importDatabase(text: String) {
        scope.launch {
            try {
                withContext(io) { manager.updateDatabaseFromText(text) }
                host.showSuccess("Database updated successfully.\nEntries: ${manager.entries.value.size}")
            } catch (e: Exception) {
                host.showError("Failed to import database: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun resetToBuiltIn() {
        scope.launch {
            try {
                withContext(io) { manager.resetToBuiltInDatabase() }
                host.showSuccess("Reset to built-in database.\nEntries: ${manager.entries.value.size}")
            } catch (e: Exception) {
                host.showError("Failed to reset: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
}
