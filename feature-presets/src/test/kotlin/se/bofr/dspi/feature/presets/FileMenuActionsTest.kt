package se.bofr.dspi.feature.presets

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.ui.alert.AlertHost
import se.bofr.dspi.ui.alert.AlertResponse

@OptIn(ExperimentalCoroutinesApi::class)
class FileMenuActionsTest {
    @Test
    fun configurationRoundTrip() = runTest {
        val core = presetFake()
        val b = attached(core)
        val host = AlertHost()
        val f = FileMenuActions(b, this, host)
        assertTrue(f.canExportConfiguration())
        val text = f.configurationDocument("Living Room.dspipreset")!!
        assertTrue(text.contains("\"Living Room\""))
        f.configurationExported(text)
        assertEquals("Success", host.alert.value!!.messageText)
        assertTrue(host.alert.value!!.informativeText.startsWith("Configuration exported.\n\n"))
        host.press("OK")

        f.importConfiguration(text)
        val ask = host.alert.value!!
        assertEquals("Import Device Configuration", ask.messageText)
        assertTrue(ask.informativeText.endsWith("EQ, crossover, delays, gains, routing and the DSP features are always applied."))
        assertEquals(listOf(FileMenuActions.VOLUMES, FileMenuActions.HARDWARE), ask.checkboxes.map { it.key })
        assertFalse(ask.checkboxes.any { it.checked })
        host.press("Import", AlertResponse(checked = setOf(FileMenuActions.VOLUMES)))
        runCurrent()
        assertNull(host.progress.value)
        assertTrue(core.presetCalls().any { it.startsWith("configImport") })
        assertTrue(host.alert.value!!.messageText in setOf("Success", "Import Device Configuration"))
    }

    @Test
    fun importingSomethingElseIsAnError() = runTest {
        val b = attached()
        val host = AlertHost()
        val f = FileMenuActions(b, this, host)
        f.importConfiguration("not a configuration")
        assertEquals("Error", host.alert.value!!.messageText)
    }

    @Test
    fun exportNeedsADevice() = runTest {
        val b = detached()
        val host = AlertHost()
        val f = FileMenuActions(b, this, host)
        assertFalse(f.canExportConfiguration())
        assertEquals("No device connected.", host.alert.value!!.informativeText)
    }

    @Test
    fun filterRoundTrip() = runTest {
        val core = presetFake()
        val b = attached(core)
        val host = AlertHost()
        val f = FileMenuActions(b, this, host)
        val text = f.filtersDocument()!!
        f.filtersExported()
        assertEquals("Filters exported successfully", host.alert.value!!.informativeText)
        host.press("OK")
        f.importFilters(text)
        runCurrent()
        val pick = host.alert.value!!
        assertEquals("Import Filters", pick.messageText)
        assertTrue(pick.checkboxes.isNotEmpty())
        host.press("Import", AlertResponse(checked = pick.checkboxes.filter { it.checked }.map { it.key }.toSet()))
        runCurrent()
        assertTrue(core.presetCalls().any { it.startsWith("filtersImport") })
        assertEquals("Success", host.alert.value!!.messageText)
    }

    @Test
    fun rewFileGetsTheSingleChannelPrompt() = runTest {
        val b = attached()
        val host = AlertHost()
        val f = FileMenuActions(b, this, host)
        f.importFilters("Preamp: -6.0 dB\nFilter 1: ON PK Fc 100 Hz Gain -3.0 dB Q 1.00\nFilter 2: ON PK Fc 1000 Hz Gain 2.0 dB Q 1.00\n")
        runCurrent()
        val pick = host.alert.value!!
        assertTrue(pick.informativeText, pick.informativeText.startsWith("Found 2 filter(s)"))
        assertTrue(pick.informativeText.endsWith("Select which channel(s) to apply them to:"))
        // Inputs 1–2 ticked by default
        assertEquals(setOf("0", "1"), pick.checkboxes.filter { it.checked }.map { it.key }.toSet())
    }
}
