package se.bofr.dspi.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import se.bofr.dspi.ui.settings.AppSettings

class SettingsTabsTest {
    @Test
    fun centerLabelMatchesMacOs() {
        assertEquals("Center: 0 dB → +25 to -25", centerLabel(AppSettings()))
        assertEquals("Center: -10 dB → +10 to -30", centerLabel(AppSettings(graphDBCenter = -10.0, graphDBRange = 40.0)))
    }

    @Test
    fun spectrumOrdersFollowCapsOrTheProtocolRange() {
        assertEquals(listOf(8, 9, 10), spectrumOrders(null, null, false))
        assertEquals(listOf(9, 10, 11), spectrumOrders(9, 11, true))
        assertEquals(listOf(8, 9, 10), spectrumOrders(11, 9, true))
    }
}
