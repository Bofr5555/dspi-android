// Port of DSPi-Console (macOS) `DSPi Console/DSPi_ConsoleApp.swift` @ v1.1.6-beta5
// `FileMenuActions` (Import/Export Filters…, Import/Export Device
// Configuration…) with `PresetDocument*.swift` / `FilterFile.swift` UX. The
// documents themselves are the core's (presetfile.rs, filterfile.rs) through
// `DspiBridge.configFiles`, as Linux `qt/src/ConfigFiles.cpp` + `main.qml`
// fileAction do. This class is text-level (JVM-tested); the SAF pickers and
// the share sheet live in PresetFiles.kt.
package se.bofr.dspi.feature.presets

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import se.bofr.dspi.bridge.DangerousOp
import se.bofr.dspi.bridge.DspiBridge

class FileMenuActions(private val bridge: DspiBridge, private val scope: CoroutineScope, private val host: AlertHost) {
    // ═══ Device configuration ═════════════════════════════════════════════

    /** `exportConfiguration`'s preconditions; false (after an alert) when the export can't start. */
    fun canExportConfiguration(): Boolean {
        if (!bridge.connected) {
            host.showError("No device connected.")
            return false
        }
        // The document's channel ids depend on the platform: never write one
        // before the device has identified itself.
        if (bridge.platformName.isEmpty()) {
            host.showError("Still identifying the device. Try again in a moment.")
            return false
        }
        return true
    }

    /** The `.dspipreset` text named after the chosen file ([displayName]), null (after an alert) on failure. */
    suspend fun configurationDocument(displayName: String?): String? {
        val text = bridge.configFiles.exportConfiguration(PresetDocumentFiles.configurationName(displayName))
        if (text.isEmpty()) {
            host.showError("No device connected.")
            return null
        }
        return text
    }

    /** The file was written: what went into it. */
    fun configurationExported(documentText: String) = host.showSuccess(PresetDocumentFiles.exportSummary(documentText))

    fun canImportConfiguration(): Boolean {
        if (!bridge.connected) {
            host.showError("No device connected.")
            return false
        }
        return true
    }

    /**
     * A configuration file was picked: inspect it, ask what to bring in
     * (`askImportOptions`), then apply it (GATED CONFIG_IMPORT, token minted in
     * the Import button's handler) and report (`showImportResult`).
     */
    fun importConfiguration(text: String) {
        val info = PresetsJson.parseObject(bridge.configFiles.inspectConfiguration(text))
        if (info?.bool("ok") != true) {
            host.showError(info?.str("error")?.ifEmpty { null } ?: "The file could not be read.")
            return
        }
        val informative =
            PresetDocumentFiles.importOptionsText(
                platform = info.str("platform").orEmpty(),
                firmware = info.str("firmware").orEmpty(),
                savedUtc = info.str("savedUtc").orEmpty(),
                connectedPlatform = bridge.platformName
            )
        host.show(
            ConsoleAlert(
                "Import Device Configuration",
                informative,
                AlertStyle.INFORMATIONAL,
                listOf(
                    AlertButton("Import", AlertButtonRole.DEFAULT) { r ->
                        applyConfiguration(
                            text,
                            VOLUMES in r.checked,
                            HARDWARE in r.checked
                        )
                    },
                    AlertButton("Cancel", AlertButtonRole.CANCEL)
                ),
                checkboxes =
                listOf(
                    AlertCheckbox(VOLUMES, "Volume levels (master and listening volume)"),
                    AlertCheckbox(HARDWARE, "Hardware I/O (GPIO pins, clocks, ADAT, inputs, output limiters)")
                )
            )
        )
    }

    private fun applyConfiguration(text: String, volumes: Boolean, hardware: Boolean) {
        if (!bridge.connected) {
            host.showError("No device connected.")
            return
        }
        val token = bridge.dangerousOps.confirm(DangerousOp.CONFIG_IMPORT)
        scope.launch {
            host.beginProgress("Importing configuration…")
            val result =
                try {
                    PresetsJson.parseObject(bridge.configFiles.importConfiguration(token, text, volumes, hardware))
                } finally {
                    host.endProgress()
                }
            if (result?.bool("ok") != true) {
                host.showError(result?.str("error")?.ifEmpty { null } ?: "The configuration was not applied.")
                return@launch
            }
            val message = result.list("lines").orEmpty().filterIsInstance<String>().joinToString("\n\n")
            // Anything the device refused or couldn't do isn't a success: no "Success" heading over it.
            if (result.bool("clean") != false) {
                host.showSuccess(message)
            } else {
                host.show(
                    ConsoleAlert(
                        "Import Device Configuration",
                        message,
                        AlertStyle.INFORMATIONAL,
                        listOf(AlertButton("OK", AlertButtonRole.DEFAULT))
                    )
                )
            }
        }
    }

    // ═══ Filter files ═════════════════════════════════════════════════════

    /** The filter file text, null (after an alert) when there is no device. */
    suspend fun filtersDocument(): String? {
        val text = if (bridge.connected) bridge.configFiles.exportFilters() else ""
        if (text.isEmpty()) {
            host.showError("No device connected.")
            return null
        }
        return text
    }

    fun filtersExported() = host.showSuccess("Filters exported successfully")

    /**
     * A filter file was picked: the core detects the format; a REW/AutoEQ file
     * gets the single-channel picker (inputs ticked), a DSPi Console file the
     * multi-channel picker (its channels ticked). Not gated (live setters only).
     */
    fun importFilters(text: String) {
        scope.launch {
            val f = PresetsJson.parseObject(bridge.configFiles.inspectFilters(text))
            if (f?.bool("ok") != true) {
                host.showError(f?.str("error")?.ifEmpty { null } ?: "No valid filters found in file")
                return@launch
            }
            val channels = f.list("channels").orEmpty().filterIsInstance<Map<String, Any?>>()
            val prompt =
                if (f.str("kind") == "rew") {
                    PresetDocumentFiles.singleChannelPrompt(f.int("filters") ?: 0, f.double("preamp"))
                } else {
                    PresetDocumentFiles.MULTI_CHANNEL_PROMPT
                }
            val boxes =
                channels.mapNotNull { c ->
                    val wire = c.int("wire") ?: return@mapNotNull null
                    val bands = c.int("bands") ?: 0
                    AlertCheckbox(
                        wire.toString(),
                        c.str("label") ?: "Ch $wire",
                        "$bands band${if (bands == 1) "" else "s"}",
                        c.bool("checked") == true
                    )
                }
            host.show(
                ConsoleAlert(
                    "Import Filters",
                    prompt,
                    AlertStyle.INFORMATIONAL,
                    listOf(
                        AlertButton("Import", AlertButtonRole.DEFAULT) { r ->
                            applyFilters(text, r.checked.mapNotNull { it.toIntOrNull() }.sorted())
                        },
                        AlertButton("Cancel", AlertButtonRole.CANCEL)
                    ),
                    checkboxes = boxes
                )
            )
        }
    }

    private fun applyFilters(text: String, wires: List<Int>) {
        if (wires.isEmpty()) return
        scope.launch {
            val r = PresetsJson.parseObject(bridge.configFiles.importFilters(text, wires))
            if (r?.bool("ok") == false || r == null) {
                host.showError(r?.str("error")?.ifEmpty { null } ?: "Failed to import filters")
                return@launch
            }
            val lines = r.list("lines").orEmpty().filterIsInstance<String>()
            host.showSuccess(lines.joinToString("\n\n").ifEmpty { "Filters imported successfully" })
        }
    }

    // ═══ File errors (the SAF side) ═══════════════════════════════════════

    fun readFailed(error: Throwable) = host.showError("Failed to read file: ${error.message ?: error.javaClass.simpleName}")

    fun writeFailed(error: Throwable) = host.showError("Failed to write file: ${error.message ?: error.javaClass.simpleName}")

    companion object {
        const val VOLUMES = "volumes"
        const val HARDWARE = "hardware"
    }
}
