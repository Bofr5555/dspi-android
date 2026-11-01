package se.bofr.dspi.feature.graph

import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.core.DspiConstants.RTA_TAP_INPUT
import se.bofr.dspi.core.DspiConstants.RTA_TAP_OUTPUT
import se.bofr.dspi.ui.settings.AppSettings
import se.bofr.dspi.ui.settings.InMemoryAppSettingsStore

/**
 * HIL bug S1: in the graph's gear panel, tapping SPECTRUM channel chips had
 * no visible effect while the dialog was open, and only one tap survived per
 * opening. The dialog read the selection through StateFlow.value (never
 * invalidating its composition) and each tap wrote `staleSelection.toggled(ch)`.
 */
class SpectrumSelectionStaleStateTest {
    private val outputs = listOf(0, 1, 2, 3)
    private val inputs = listOf(0, 1)

    private inner class Harness(activeEq: Int?, start: AppSettings = AppSettings(rtaDashboardSelectionKey = "out:0")) {
        val store = InMemoryAppSettingsStore(start)

        // collectAsStateWithLifecycle's mirror: catches up with the store only on a frame.
        private val mirror = mutableStateOf(store.settings.value)
        val spectrum =
            SpectrumSelectionState(
                store,
                mirror,
                activeEq,
                { tap -> if (tap == RTA_TAP_INPUT) inputs else outputs },
                mutableStateOf(RtaChannelSelection(RTA_TAP_OUTPUT, listOf(0))),
                mutableStateOf(null)
            )

        /** The open dialog: its own composition reading `spectrum.value`. */
        val dialog = ObservedRead { spectrum.value }

        fun frame() {
            mirror.value = store.settings.value
            dialog.settle()
        }
    }

    @Test
    fun dashboardDialogShowsEveryToggleAndKeepsThemAll() {
        val h = Harness(activeEq = null)
        assertEquals(listOf(0), h.dialog.value.channels)
        // Two taps within one frame (the dialog has not recomposed in between).
        h.spectrum.toggle(1)
        h.spectrum.toggle(2)
        h.frame()
        assertTrue("the dialog's composition is invalidated", h.dialog.invalidations > 0)
        assertEquals(listOf(0, 1, 2), h.dialog.value.channels)
        // And frame by frame, as the owner tapped.
        h.spectrum.toggle(3)
        h.frame()
        h.spectrum.toggle(0)
        h.frame()
        assertEquals(listOf(1, 2, 3), h.dialog.value.channels)
        assertEquals("out:1,2,3", h.store.settings.value.rtaDashboardSelectionKey)
        h.dialog.close()
    }

    @Test
    fun dashboardClearAndSwitchSideUseTheCurrentSelection() {
        val h = Harness(activeEq = null)
        h.spectrum.toggle(3)
        h.spectrum.switchSide(RTA_TAP_INPUT) // same frame: must stash out:0,3, not the stale out:0
        h.frame()
        assertEquals(RtaChannelSelection(RTA_TAP_INPUT, emptyList()), h.dialog.value)
        h.spectrum.toggle(1)
        h.spectrum.switchSide(RTA_TAP_OUTPUT)
        h.frame()
        assertEquals(RtaChannelSelection(RTA_TAP_OUTPUT, listOf(0, 3)), h.dialog.value)
        h.spectrum.clear()
        h.frame()
        assertEquals(RtaChannelSelection(RTA_TAP_OUTPUT, emptyList()), h.dialog.value)
        assertEquals("in:1", h.store.settings.value.rtaDashboardOtherSideKey)
        h.dialog.close()
    }

    @Test
    fun channelPageDialogShowsEveryToggle() {
        val h = Harness(activeEq = 2)
        h.spectrum.toggle(1)
        h.spectrum.toggle(3)
        h.frame()
        assertTrue(h.dialog.invalidations > 0)
        assertEquals(listOf(0, 1, 3), h.dialog.value.channels)
        h.spectrum.clear()
        h.frame()
        assertTrue(h.dialog.value.isEmpty)
        assertEquals(false, h.store.settings.value.rtaChannelPagesShowSpectrum)
        h.dialog.close()
    }
}
