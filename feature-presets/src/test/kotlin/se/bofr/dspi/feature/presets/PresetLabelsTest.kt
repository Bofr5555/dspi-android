package se.bofr.dspi.feature.presets

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.core.PresetStatus

class PresetLabelsTest {
    @Test
    fun dropdownLabels() {
        assertEquals("Empty", presetDropdownLabel(0, occupied = false, name = "Ignored"))
        assertEquals("Preset 3", presetDropdownLabel(2, occupied = true, name = "  "))
        assertEquals("Living Room", presetDropdownLabel(0, occupied = true, name = " Living Room "))
        assertEquals("10: Empty", presetLabel(9, occupied = false, name = ""))
        assertEquals("1: Living Room", presetLabel(0, occupied = true, name = "Living Room"))
    }

    @Test
    fun dirtyMarkerOnlyOnTheActiveSlot() {
        assertEquals("Living Room*", presetPickerTitle(0, true, "Living Room", active = true, dirty = true))
        assertEquals("Living Room", presetPickerTitle(0, true, "Living Room", active = false, dirty = true))
        assertEquals("Living Room", presetPickerTitle(0, true, "Living Room", active = true, dirty = false))
    }

    @Test
    fun failureText() {
        assertEquals("error 4", presetFailureText(PresetStatus.FLASH_WRITE))
        assertEquals("error 255", presetFailureText(PresetStatus.NO_REPLY))
        assertEquals("Preset 7", defaultPresetName(6))
    }

    @Test
    fun startupModeNames() {
        assertEquals("Specified Default", presetStartupModeName(PRESET_STARTUP_SPECIFIED))
        assertEquals("Last Used", presetStartupModeName(PRESET_STARTUP_LAST_USED))
    }
}

class PresetDiffTest {
    private fun diff(n: Int) = PresetDiff(List(n) { "Change ${it + 1}" })

    @Test
    fun summaryListsUpToFifteen() {
        val s = diff(15).summary
        assertEquals(15, s.lines().size)
        assertEquals("• Change 1", s.lines().first())
        assertEquals("• Change 15", s.lines().last())
    }

    @Test
    fun summaryCountsTheRest() {
        assertEquals("...and 1 more change", diff(16).summary.lines().last())
        assertEquals("...and 5 more changes", diff(20).summary.lines().last())
        assertEquals(16, diff(20).summary.lines().size)
    }

    @Test
    fun alertWording() {
        val a = PresetAlerts.unsavedChangesAlert(PresetDiff(listOf("Preamp L: -3.0 dB → 0.0 dB"))) {}
        assertEquals("Unsaved Changes", a.messageText)
        assertEquals(
            "The current preset has unsaved changes:\n\n• Preamp L: -3.0 dB → 0.0 dB\n\nSave before continuing?",
            a.informativeText
        )
        assertEquals(AlertStyle.WARNING, a.style)
    }

    @Test
    fun alertHostRoutesButtons() {
        val host = AlertHost()
        var got: UnsavedChangesAction? = null
        host.show(PresetAlerts.unsavedChangesAlert(diff(1)) { got = it })
        host.press("Discard")
        assertEquals(UnsavedChangesAction.DISCARD, got)
        assertNull(host.alert.value)
        host.show(PresetAlerts.unsavedChangesAlert(diff(1)) { got = it })
        host.dismiss()
        assertEquals(UnsavedChangesAction.CANCEL, got)
    }
}

class FileNamingTest {
    @Test
    fun defaultNames() {
        assertEquals("DSPi Configuration.dspipreset", PresetDocumentFiles.DEFAULT_CONFIGURATION_FILE_NAME)
        assertEquals("DSPi Filters.txt", PresetDocumentFiles.DEFAULT_FILTERS_FILE_NAME)
    }

    @Test
    fun configurationNameIsTheFileNameWithoutExtension() {
        assertEquals("DSPi Configuration", PresetDocumentFiles.configurationName("DSPi Configuration.dspipreset"))
        assertEquals("Living Room", PresetDocumentFiles.configurationName("Living Room.json"))
        // A provider that appends its own extension
        assertEquals("Living Room", PresetDocumentFiles.configurationName("Living Room.dspipreset.json"))
        // Only the last unknown extension goes, as deletingPathExtension does
        assertEquals("my.setup", PresetDocumentFiles.configurationName("my.setup.dspipreset"))
        assertEquals("v1.2", PresetDocumentFiles.configurationName("v1.2.txt"))
        assertEquals("No extension", PresetDocumentFiles.configurationName("No extension"))
        assertEquals("DSPi Configuration", PresetDocumentFiles.configurationName(null))
        assertEquals("Bedroom", PresetDocumentFiles.configurationName("primary:Download/Bedroom.dspipreset"))
    }

    @Test
    fun savedDates() {
        val utc = ZoneId.of("UTC")
        assertEquals("2026-01-18 05:41", PresetDocumentFiles.formatSavedUtc("2026-01-18T05:41:39.649Z", utc))
        assertEquals("2026-01-18 06:41", PresetDocumentFiles.formatSavedUtc("2026-01-18T05:41:39+00:00", ZoneId.of("Europe/Stockholm")))
        assertNull(PresetDocumentFiles.formatSavedUtc("", utc))
        assertNull(PresetDocumentFiles.formatSavedUtc("yesterday", utc))
    }

    @Test
    fun importOptionsWording() {
        val utc = ZoneId.of("UTC")
        assertEquals(
            "Saved from RP2350, firmware 1.1.6-beta5, 2026-01-18 05:41.\n\n" +
                "EQ, crossover, delays, gains, routing and the DSP features are always applied.",
            PresetDocumentFiles.importOptionsText("RP2350", "1.1.6-beta5", "2026-01-18T05:41:00Z", "rp2350", utc)
        )
        assertEquals(
            "Saved from RP2040.\n\nThis file came from a RP2040 device and you are connected to RP2350. " +
                "Anything the connected device doesn't have will be skipped.\n\n" +
                "EQ, crossover, delays, gains, routing and the DSP features are always applied.",
            PresetDocumentFiles.importOptionsText("RP2040", "", "", "RP2350", utc)
        )
        assertTrue(
            PresetDocumentFiles.importOptionsText(
                "",
                "",
                "",
                "RP2350",
                utc
            ).startsWith("Applies the settings in this file to the connected device.")
        )
    }

    @Test
    fun exportSummaryCountsWhatDoesSomething() {
        val doc =
            """{"channels":[{"eq":[{"type":1},{"type":0}],"crossover":[{"type":34}]},{"eq":[{"type":2}],"crossover":[]}],
               "matrix":[{"enabled":true},{"enabled":false},{"in":1}]}"""
        assertEquals(
            "Configuration exported.\n\n2 channels, 2 active EQ bands, 1 crossover bands, 3 crosspoints.",
            PresetDocumentFiles.exportSummary(doc)
        )
    }

    @Test
    fun filterPrompts() {
        assertEquals("Found 3 filter(s). Select which channel(s) to apply them to:", PresetDocumentFiles.singleChannelPrompt(3, null))
        assertEquals(
            "Found 1 filter(s) and a -6.5 dB preamp. Select which channel(s) to apply them to:",
            PresetDocumentFiles.singleChannelPrompt(1, -6.5)
        )
        assertEquals(
            "Found 1 filter(s) and a +2.0 dB preamp. Select which channel(s) to apply them to:",
            PresetDocumentFiles.singleChannelPrompt(1, 2.0)
        )
    }

    @Test
    fun masterVolumeDisplay() {
        assertEquals("−∞ dB (mute)", PresetDocumentFiles.masterVolumeDisplay(-128f))
        assertEquals("-12.5 dB", PresetDocumentFiles.masterVolumeDisplay(-12.5f))
    }
}
