package se.bofr.dspi.feature.tools

import org.junit.Assert.assertEquals
import org.junit.Test
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.core.WireCompat
import se.bofr.dspi.fake.demoDspState

/** Core patch 0003: the Tools list hides what an older firmware (display only) lacks. */
class ToolGatingTest {
    private fun shown(wireRead: Int, features: Long) =
        ToolRoute.entries.filter { it.supported(demoDspState().copy(wireRead = wireRead, wireFeatures = features)) }

    @Test
    fun v32AndNewerShowEveryTool() {
        assertEquals(ToolRoute.entries, shown(WireCompat.WIRE_READ_EXACT, WireCompat.ALL))
        assertEquals(ToolRoute.entries, shown(WireCompat.WIRE_READ_NEWER, WireCompat.ALL))
        // Nothing decoded yet (or an unreadable image): no gating here, `usable` decides elsewhere.
        assertEquals(ToolRoute.entries, shown(WireCompat.WIRE_READ_NONE, 0L))
    }

    @Test
    fun olderFirmwareHidesWhatItLacks() {
        // 1.1.5 (V28): no subharmonic synth (V29) or tube (V31)
        val v28 = (1L shl 11) - 1
        assertEquals(
            listOf(ToolRoute.LOUDNESS, ToolRoute.CROSSFEED, ToolRoute.PSYBASS, ToolRoute.UPMIX, ToolRoute.LEVELLER),
            shown(WireCompat.WIRE_READ_OLDER, v28)
        )
        // 1.1.5-beta4 (V21): no psybass (V23), no upmixer (V25/V26)
        val v21 = (1L shl 5) - 1
        assertEquals(
            listOf(ToolRoute.LOUDNESS, ToolRoute.CROSSFEED, ToolRoute.LEVELLER),
            shown(WireCompat.WIRE_READ_OLDER, v21)
        )
        assertEquals(DspiConstants.COMPAT_OK, demoDspState().compat)
    }
}
