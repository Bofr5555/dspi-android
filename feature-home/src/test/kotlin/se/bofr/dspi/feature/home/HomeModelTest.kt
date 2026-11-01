package se.bofr.dspi.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.core.DspiConstants

class HomeModelTest {
    @Test
    fun linkAlertWording() {
        assertEquals("Inputs 1 and 2 don't match", HomeModel.linkMismatchTitle(0, 1))
        assertTrue(HomeModel.linkMismatchMessage(true, true).startsWith("These inputs have different filters and input trims."))
        assertTrue(HomeModel.linkMismatchMessage(false, true).startsWith("These inputs have different input trims."))
        assertEquals("3/4", HomeModel.pairLabel(3))
        assertEquals("7/8", HomeModel.pairLabel(6))
    }

    @Test
    fun outputsPerPlatform() {
        val rp2040 = MatrixOutput.visible("RP2040", listOf(0, 1))
        assertEquals(listOf("OUT1", "OUT2", "OUT3", "OUT4", "OUT5"), rp2040.map { it.descriptor })
        assertEquals("I2S 2 L", rp2040[2].name)
        assertTrue(rp2040[4].isPdm)
        val rp2350 = MatrixOutput.visible("RP2350", emptyList())
        assertEquals(9, rp2350.size)
        assertEquals("PDM", rp2350[8].name)
        assertEquals(10, rp2350[8].appCh)
    }

    @Test
    fun dashboardCards() {
        val all = DashboardCard.cards(9, List(9) { true })
        assertEquals(
            listOf(
                DashboardCard.Input,
                DashboardCard.OutputPair(0, 1),
                DashboardCard.OutputPair(2, 3),
                DashboardCard.OutputPair(4, 5),
                DashboardCard.OutputPair(6, 7),
                DashboardCard.Output(8)
            ),
            all
        )
        val some = DashboardCard.cards(5, listOf(true, false, false, true, false))
        assertEquals(listOf(DashboardCard.Input, DashboardCard.Output(0), DashboardCard.Output(3)), some)
        assertEquals(1, DashboardCard.columnCount(0, 380f, 6))
        assertEquals(2, DashboardCard.columnCount(0, 900f, 6))
        assertEquals(2, DashboardCard.columnCount(3, 380f, 2))
        assertEquals("Up to 1 card per row.", DashboardCard.layoutCaption(1))
    }

    @Test
    fun firmwareBanner() {
        assertNull(HomeModel.firmwareBannerMessage(DspiConstants.COMPAT_OK, "1.1.6", "1.1.6-beta5"))
        assertEquals(
            "This device runs firmware 1.1.4; DSPi Console expects 1.1.6-beta5. " +
                "Until the firmware matches, this app is read-only: settings are shown but cannot be changed.",
            HomeModel.firmwareBannerMessage(DspiConstants.COMPAT_FIRMWARE_TOO_OLD, "1.1.4", "1.1.6-beta5")
        )
        assertTrue(
            HomeModel.firmwareBannerMessage(
                DspiConstants.COMPAT_FIRMWARE_TOO_NEW,
                "1.2",
                "1.1.6-beta5"
            )!!.endsWith(HomeModel.READ_ONLY_NOTE)
        )
        assertNull(
            "no banner while compat is still unknown",
            HomeModel.firmwareBannerMessage(DspiConstants.COMPAT_UNKNOWN, "", "1.1.6-beta5")
        )
        assertEquals("Details...", HomeModel.firmwareBannerButton(DspiConstants.COMPAT_FIRMWARE_TOO_NEW))
        assertEquals("DSPi (E6614103)", HomeModel.deviceDisplayName("DEMO0000E6614103"))
    }
}
