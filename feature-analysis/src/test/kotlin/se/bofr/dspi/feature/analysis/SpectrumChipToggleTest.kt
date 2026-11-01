package se.bofr.dspi.feature.analysis

import org.junit.Assert.assertEquals
import org.junit.Test
import se.bofr.dspi.core.DspiConstants.RTA_TAP_INPUT
import se.bofr.dspi.core.DspiConstants.RTA_TAP_OUTPUT
import se.bofr.dspi.ui.settings.AppSettings
import se.bofr.dspi.ui.settings.InMemoryAppSettingsStore

/**
 * reviews/stale-state-audit.md A2: the Spectrum page's chips and side switch
 * edit the store's latest selection, so taps landing before the page
 * recomposes all take effect.
 */
class SpectrumChipToggleTest {
    private val available: (Int) -> List<Int> = { tap -> if (tap == RTA_TAP_INPUT) listOf(0, 1) else listOf(0, 1, 2, 3) }

    @Test
    fun chipTapsBetweenFramesAllLand() {
        val store = InMemoryAppSettingsStore(AppSettings(rtaDashboardSelectionKey = "out:0"))
        // Two taps, no recomposition in between (each handler only gets the channel).
        store.update { spectrumChannelToggled(it, 2, available) }
        store.update { spectrumChannelToggled(it, 3, available) }
        assertEquals("out:0,2,3", store.settings.value.rtaDashboardSelectionKey)
        store.update { spectrumSideSwitched(it, RTA_TAP_INPUT, available) }
        store.update { spectrumChannelToggled(it, 1, available) }
        store.update { spectrumSideSwitched(it, RTA_TAP_OUTPUT, available) }
        assertEquals("out:0,2,3", store.settings.value.rtaDashboardSelectionKey)
        assertEquals("in:1", store.settings.value.rtaDashboardOtherSideKey)
    }
}
