package se.bofr.dspi.feature.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.core.SiggenCaps
import se.bofr.dspi.core.SiggenParam
import se.bofr.dspi.core.SiggenStatus
import se.bofr.dspi.core.SiggenTypeInfo

class TestSignalsModelTest {
    private val draft = DEFAULT_SIGGEN_DRAFT

    @Test
    fun catalogueMatchesTheMacTilesInOrder() {
        assertEquals(DspiConstants.SIGGEN_TYPE_COUNT, siggenTypeInfos.size)
        assertEquals((0 until 15).toList(), siggenTypeInfos.map { it.id })
        val tiles =
            listOf(
                "Sine", "Square", "White", "Pink", "Log Swp", "Lin Swp", "Step Swp", "Impulse",
                "Clicks", "Polarity", "Burst", "2-Tone", "Multi", "ISP", "Chan ID"
            )
        assertEquals(tiles, siggenTypeInfos.map { it.tileLabel })
        assertTrue(siggenTypeInfos.all { it.fallback.params.size == 4 })
        assertEquals("Sine", typeInfo(99).displayName)
    }

    @Test
    fun deviceDescriptorWinsOverFallback() {
        val custom = SiggenTypeInfo(0, "SINE", SIGGEN_TIMING_CONTINUOUS, List(4) { SiggenParam(if (it == 0) 1 else 0, 5f, 20000f, 440f) })
        val caps = SiggenCaps(true, 1, 8, 16, 0xFF, listOf(custom))
        assertEquals(440f, siggenTypeDesc(caps, SIGGEN_SINE).params[0].def)
        // Beyond typeCount (or unsupported): the built-in table
        assertEquals(20f, siggenTypeDesc(caps, SIGGEN_SWEEP_LOG).params[0].def)
        assertEquals(1000f, siggenTypeDesc(caps.copy(supported = false), SIGGEN_SINE).params[0].def)
    }

    @Test
    fun startBlockerInMacOrder() {
        val sine = typeInfo(SIGGEN_SINE).fallback
        val sweep = typeInfo(SIGGEN_SWEEP_LOG).fallback
        assertEquals("No device connected", siggenStartBlocker(false, true, draft, sine))
        assertEquals("Firmware has no signal generator", siggenStartBlocker(true, false, draft, sine))
        assertEquals("Select at least one output", siggenStartBlocker(true, true, draft.copy(channelMask = 0), sine))
        assertEquals("Sweep length must be greater than 0", siggenStartBlocker(true, true, draft.copy(durationMs = 0), sweep))
        assertNull(siggenStartBlocker(true, true, draft, sine))
    }

    @Test
    fun outputChipCyclesOffOnInverted() {
        assertEquals(SiggenOutputState.ON, siggenOutputState(draft, 0))
        assertEquals(SiggenOutputState.OFF, siggenOutputState(draft, 2))
        assertEquals(SiggenOutputState.INVERTED, siggenOutputState(draft.copy(invertMask = 0x2), 1))
    }

    @Test
    fun paramClampAndUnits() {
        val count = SiggenParam(SIGGEN_PARAM_COUNT, 1f, 24f, 3f)
        assertEquals(24f, siggenClampParam(count, 99.4f))
        assertEquals(3f, siggenClampParam(count, 2.6f))
        val unbounded = SiggenParam(SIGGEN_PARAM_MS, 10f, 0f, 500f)
        assertEquals(99999f, siggenClampParam(unbounded, 99999f))
        assertEquals(10f, siggenClampParam(unbounded, 1f))
        assertEquals("Hz", siggenUnitLabel(SIGGEN_PARAM_FREQ_HZ))
        assertEquals("×", siggenUnitLabel(SIGGEN_PARAM_RATIO))
        assertEquals("", siggenUnitLabel(SIGGEN_PARAM_PATTERN))
        assertEquals("1.5", formatFieldValue(1.50f, 2))
        assertEquals("1000", formatFieldValue(1000f, 1))
        assertEquals("-20", formatFieldValue(-20f, 1))
    }

    @Test
    fun walkFollowsFlagAndChannelId() {
        assertTrue(siggenWalkEnabled(draft.copy(flags = DspiConstants.SIGGEN_FLAG_WALK)))
        assertTrue(siggenWalkEnabled(draft.copy(signalType = SIGGEN_CHANNEL_ID)))
        assertTrue(!siggenWalkEnabled(draft))
    }

    @Test
    fun formattersAsMac() {
        assertEquals("2.4 s", siggenFormatElapsed(2400))
        assertEquals("1:05.0", siggenFormatElapsed(65_000))
        assertEquals("1.24 kHz", siggenFormatFreq(1240f))
        assertEquals("440 Hz", siggenFormatFreq(440f))
    }

    @Test
    fun transportLines() {
        val running = SiggenStatus(SIGGEN_STATE_RUN, SIGGEN_SWEEP_LOG, 1, 2400, 2, 0, 1240f)
        assertEquals("Running · Log sweep", siggenTransportTitle(running, null, true, true, draft))
        assertEquals(
            "2.4 s · 1.24 kHz · cycle 2 · Out 2 · edits apply live",
            siggenTransportDetail(running, draft) { "Out ${it + 1}" }
        )
        val idle = SiggenStatus(SIGGEN_STATE_IDLE, 0, 0xFF, 0, 0, SIGGEN_STOP_NONE, 0f)
        assertEquals("Ready · Sine", siggenTransportTitle(idle, null, true, true, draft))
        assertEquals("Select at least one output", siggenTransportTitle(idle, "Select at least one output", true, true, draft))
        assertEquals("2 outputs · peak -20.0 dBFS", siggenTransportDetail(idle, draft) { "" })
        assertEquals("1 output · peak -20.0 dBFS", siggenTransportDetail(null, draft.copy(channelMask = 4)) { "" })
        assertEquals("Stopped by preset load", siggenTransportDetail(idle.copy(stopReason = SIGGEN_STOP_PRESET), draft) { "" })
        val done = idle.copy(stopReason = SIGGEN_STOP_COMPLETED, elapsedMs = 5000)
        assertEquals("Finished after 5.0 s", siggenTransportDetail(done, draft) { "" })
        assertEquals("Stopped", siggenTransportDetail(idle.copy(stopReason = SIGGEN_STOP_HOST), draft) { "" })
    }

    @Test
    fun defaultLevelIsConservative() {
        assertEquals(-20f, DEFAULT_SIGGEN_DRAFT.levelDb)
        assertEquals("Idle", siggenStateLabel(SIGGEN_STATE_IDLE))
        assertEquals("Fading out", siggenStateLabel(SIGGEN_STATE_FADE_OUT))
    }

    @Test
    fun readOnlyDisablesTheGeneratorControls() {
        val caps = SiggenCaps(true, 1, 8, 16, 0xFF, emptyList())
        val ok = TestSignalsUiState(true, caps, DEFAULT_SIGGEN_DRAFT, null, listOf("Out 1"), listOf(true))
        assertTrue(ok.controlsEnabled)
        // Decision G1: firmware mismatch -> the bridge refuses writes, the page offers none
        assertEquals(false, ok.copy(writable = false).controlsEnabled)
    }
}
