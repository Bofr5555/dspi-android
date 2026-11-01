package se.bofr.dspi.fake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.core.DspiConstants

class FakeDspiCoreTest {
    @Test
    fun demoStateIsCompatibleV32() {
        val s = FakeDspiCore().getState()
        assertEquals(DspiConstants.COMPAT_OK, s.compat)
        assertEquals(32, s.formatVersion)
        assertEquals(DspiConstants.MAX_CHANNELS, s.filters.size)
    }

    @Test
    fun volumeSetterUpdatesState() {
        val core = FakeDspiCore()
        core.setMasterVolume(-12f)
        assertEquals(-12f, core.getState().masterVolumeDb)
        assertEquals(listOf("setMasterVolume(-12.0)"), core.calls)
    }

    @Test
    fun unimplementedThrows() {
        val core = FakeDspiCore()
        val e = runCatching { core.savePreset(0) }.exceptionOrNull()
        assertTrue(e is NotImplementedError)
    }
}
