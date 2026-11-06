package se.bofr.dspi.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCategoryTest {
    @Test
    fun groupsAreTheMacOsSidebarWithoutUpdate() {
        assertEquals(listOf("Application", "Display", "System", "Control"), SettingsCategory.groups.map { it.first })
        assertEquals(SettingsCategory.entries.toSet(), SettingsCategory.groups.flatMap { it.second }.toSet())
    }

    @Test
    fun pagesHideWhenTheDeviceLacksTheFeature() {
        val none = SettingsAvailability(connected = true, platformName = "RP2350")
        assertTrue(SettingsCategory.I2S_CONFIG.isAvailable(none))
        assertFalse(SettingsCategory.I2S_CONFIG.isAvailable(none.copy(platformName = "STM32H723")))
        assertFalse(SettingsCategory.CONTROL_INTERFACES.isAvailable(none))
        assertFalse(SettingsCategory.CONTROL_SURFACES.isAvailable(none))
        // Shown while disconnected (the page carries its own placeholder)
        assertTrue(SettingsCategory.CONTROL_SURFACES.isAvailable(none.copy(connected = false)))
        assertFalse(SettingsCategory.MACROS.isAvailable(none))
        assertTrue(SettingsCategory.MACROS.isAvailable(none.copy(csMacrosSupported = true)))
        // Networking (DSPi Link client) only where the app provides it
        assertFalse(SettingsCategory.NETWORKING.isAvailable(none))
        assertTrue(SettingsCategory.NETWORKING.isAvailable(none.copy(connected = false, networkingAvailable = true)))
        assertEquals(
            listOf(SettingsCategory.GENERAL, SettingsCategory.ADVANCED, SettingsCategory.NETWORKING),
            SettingsCategory.groups[0].second
        )
    }

    @Test
    fun idsRoundTrip() {
        SettingsCategory.entries.forEach { assertEquals(it, SettingsCategory.ofId(it.id)) }
    }
}
