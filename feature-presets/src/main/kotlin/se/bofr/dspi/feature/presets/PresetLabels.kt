// Port of DSPi-Console (macOS) @ v1.1.6-beta5:
// - `ContentView.swift` `presetLabel(_:)`, `presetDropdownLabel(_:)`, the
//   "Preset N" default name;
// - `Constants.swift` `presetFailureText(_:)`;
// - `PresetDocument.swift` `fileExtension`, `FileMenuActions` default file
//   names, the export summary and the import-options wording
//   (`DSPi_ConsoleApp.swift` FileMenuActions.exportConfiguration /
//   askImportOptions / showSingleChannelPicker / showMultiChannelPicker).
// Pure functions: JVM-tested (PresetLabelsTest, FileNamingTest).
package se.bofr.dspi.feature.presets

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import se.bofr.dspi.core.PresetStatus

/** Preset slots on the device (ContentView `0..<10`). */
const val PRESET_SLOT_COUNT = 10

/** README "Rename...": up to 31 characters. */
const val PRESET_NAME_MAX_LENGTH = 31

/** The name a slot gets when it is saved without one. */
fun defaultPresetName(slot: Int) = "Preset ${slot + 1}"

/** `presetDropdownLabel(_:)`: "Empty", the trimmed name, or "Preset N". */
fun presetDropdownLabel(slot: Int, occupied: Boolean, name: String): String {
    if (!occupied) return "Empty"
    val trimmed = name.trim()
    return trimmed.ifEmpty { defaultPresetName(slot) }
}

/** `presetLabel(_:)`: "N: label". */
fun presetLabel(slot: Int, occupied: Boolean, name: String) = "${slot + 1}: ${presetDropdownLabel(slot, occupied, name)}"

/** The picker face: the label plus "*" on the active slot while it has unsaved changes. */
fun presetPickerTitle(slot: Int, occupied: Boolean, name: String, active: Boolean, dirty: Boolean): String {
    val base = presetDropdownLabel(slot, occupied, name)
    return if (active && dirty) "$base*" else base
}

/**
 * The firmware's UInt8 status code as the macOS alerts print it. A transfer
 * failure has no code on the wire; it is shown as 255 (the C API's "no reply").
 */
fun presetStatusCode(status: PresetStatus): Int = if (status == PresetStatus.NO_REPLY) 255 else status.code

/** `presetFailureText(_:)` (no DSPi Link lock on Android). */
fun presetFailureText(status: PresetStatus) = "error ${presetStatusCode(status)}"

/** `PresetDocument.swift` file naming and the FileMenuActions panel defaults. */
object PresetDocumentFiles {
    /** `PresetDocument.fileExtension`. */
    const val FILE_EXTENSION = "dspipreset"

    /** Export Device Configuration's suggested name. */
    const val DEFAULT_CONFIGURATION_FILE_NAME = "DSPi Configuration.$FILE_EXTENSION"

    /** Export Filters' suggested name. */
    const val DEFAULT_FILTERS_FILE_NAME = "DSPi Filters.txt"

    /** MIME type used for SAF / share (`.dspipreset` has no registered type). */
    const val CONFIGURATION_MIME = "application/octet-stream"
    const val FILTERS_MIME = "text/plain"

    private val knownExtensions = setOf(FILE_EXTENSION, "json", "bin")

    /**
     * The document `name` macOS writes into the file: the chosen file name
     * without its extension (`url.deletingPathExtension().lastPathComponent`).
     * Some document providers append a type extension of their own
     * ("….dspipreset.json"): the last extension is always stripped, as macOS
     * does, and further ones only while they are configuration extensions.
     */
    fun configurationName(displayName: String?): String {
        var name = displayName?.substringAfterLast('/')?.trim().orEmpty()
        var first = true
        while (true) {
            val dot = name.lastIndexOf('.')
            if (dot <= 0) break
            val ext = name.substring(dot + 1).lowercase(Locale.ROOT)
            if (!first && ext !in knownExtensions) break
            name = name.substring(0, dot)
            first = false
        }
        return name.trim().ifEmpty { DEFAULT_CONFIGURATION_FILE_NAME.removeSuffix(".$FILE_EXTENSION") }
    }

    /** "yyyy-MM-dd HH:mm" in [zone] of the document's `savedUtc`, null when unparsable or empty. */
    fun formatSavedUtc(savedUtc: String, zone: ZoneId = ZoneId.systemDefault()): String? {
        if (savedUtc.isBlank()) return null
        val instant =
            runCatching { Instant.parse(savedUtc) }.getOrNull()
                ?: runCatching { OffsetDateTime.parse(savedUtc).toInstant() }.getOrNull()
                ?: return null
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(zone).format(instant)
    }

    /**
     * `askImportOptions` informative text. [connectedPlatform] is the bridge's
     * `platformName` ("" while the device is still identifying itself).
     */
    fun importOptionsText(
        platform: String,
        firmware: String,
        savedUtc: String,
        connectedPlatform: String,
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        val provenance = mutableListOf<String>()
        if (platform.isNotEmpty()) provenance += platform
        if (firmware.isNotEmpty()) provenance += "firmware $firmware"
        formatSavedUtc(savedUtc, zone)?.let { provenance += it }
        var informative =
            if (provenance.isEmpty()) {
                "Applies the settings in this file to the connected device."
            } else {
                "Saved from ${provenance.joinToString(", ")}."
            }
        if (platform.isNotEmpty() && connectedPlatform.isNotEmpty() && !platform.equals(connectedPlatform, ignoreCase = true)) {
            informative += "\n\nThis file came from a $platform device and you are connected to " +
                "$connectedPlatform. Anything the connected device doesn't have will be skipped."
        }
        informative += "\n\nEQ, crossover, delays, gains, routing and the DSP features are always applied."
        return informative
    }

    /**
     * `exportConfiguration`'s success text, counted from the exported document
     * (channels[].eq / crossover with type != 0, every matrix entry) exactly as
     * macOS counts its `PresetDocument` (Linux ConfigFiles.cpp counts only the
     * enabled crosspoints).
     */
    fun exportSummary(documentText: String): String {
        val doc = PresetsJson.parseObject(documentText)
        val channels = doc?.list("channels").orEmpty().filterIsInstance<Map<String, Any?>>()
        fun active(c: Map<String, Any?>, key: String) = c.list(key).orEmpty().count { b ->
            val type = (b as? Map<String, Any?>)?.int("type") ?: 0
            type != 0
        }
        val bands = channels.sumOf { active(it, "eq") }
        val xover = channels.sumOf { active(it, "crossover") }
        // macOS: `doc.matrix.count` (every crosspoint entry of the document)
        val points = doc?.list("matrix").orEmpty().size
        return "Configuration exported.\n\n${channels.size} channels, $bands active EQ bands, $xover crossover bands, $points crosspoints."
    }

    /** showSingleChannelPicker's prompt (a REW / AutoEQ style file). */
    fun singleChannelPrompt(filters: Int, preamp: Double?): String {
        var prompt = "Found $filters filter(s)"
        if (preamp != null) prompt += String.format(Locale.ROOT, " and a %+.1f dB preamp", preamp)
        return "$prompt. Select which channel(s) to apply them to:"
    }

    /** showMultiChannelPicker's prompt (a DSPi Console file). */
    const val MULTI_CHANNEL_PROMPT = "This file contains filter settings for multiple channels. Select which channels to import:"

    /** `saveMasterVolume`'s value display. */
    fun masterVolumeDisplay(db: Float): String = if (db <= -128f) "−∞ dB (mute)" else String.format(Locale.ROOT, "%.1f dB", db)
}

/** Startup Preset mode 0: "Specified Default" (GlobalSettingsTab). */
const val PRESET_STARTUP_SPECIFIED = 0

/** Startup Preset mode 1: "Last Used". */
const val PRESET_STARTUP_LAST_USED = 1

/** GlobalSettingsTab's Startup Preset mode names. */
fun presetStartupModeName(mode: Int) = if (mode == PRESET_STARTUP_SPECIFIED) "Specified Default" else "Last Used"
