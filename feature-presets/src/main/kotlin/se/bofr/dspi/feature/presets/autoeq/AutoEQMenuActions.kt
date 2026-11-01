// Port of DSPi-Console (macOS) `DSPi Console/DSPi_ConsoleApp.swift` @ v1.1.6-beta5
// `AutoEQMenuActions` (Update Database…: Import File…, Reset to Built-in) and
// the AutoEQ menu's Favorite Profiles / Clear Favorites. "Rebuild from GitHub"
// is not offered (PRESETS deviation 2).
package se.bofr.dspi.feature.presets.autoeq

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.ui.alert.AlertButton
import se.bofr.dspi.ui.alert.AlertButtonRole
import se.bofr.dspi.ui.alert.AlertHost
import se.bofr.dspi.ui.alert.AlertStyle
import se.bofr.dspi.ui.alert.ConsoleAlert

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

    /** Parses the database on [io] if it isn't yet (the browser opening, a favourite, Update Database). */
    fun loadDatabase() {
        scope.launch { withContext(io) { manager.ensureLoaded() } }
    }

    /** Favorite Profiles > entry: loads the database if needed, then applies. */
    fun applyFavorite(favorite: FavoriteProfile) {
        scope.launch {
            withContext(io) { manager.ensureLoaded() }
            val entry = manager.entry(favorite.id)
            if (entry == null) {
                host.showError("${favorite.name} is not in the AutoEQ database.")
                return@launch
            }
            applyProfile(entry)
        }
    }

    /** "Update Database...": shows the current database and the update methods. */
    fun updateDatabase(onImportFile: () -> Unit) {
        scope.launch {
            withContext(io) { manager.ensureLoaded() }
            showUpdateDatabase(onImportFile)
        }
    }

    private fun showUpdateDatabase(onImportFile: () -> Unit) {
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
