package se.bofr.dspi.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** macOS CompactGainField / CompactDelayField formatting and commit rules. */
class CompactFieldsTest {
    @Test
    fun formatGainTrimsTrailingZeros() {
        assertEquals("0", formatGain(0f))
        assertEquals("0", formatGain(-0f))
        assertEquals("+3", formatGain(3f))
        assertEquals("-1.5", formatGain(-1.5f))
        assertEquals("+0.25", formatGain(0.25f))
        assertEquals("-60", formatGain(-60f))
        assertEquals("+12", formatGain(12f))
        assertEquals("-0.1", formatGain(-0.1f))
    }

    @Test
    fun displays() {
        assertEquals("0 dB", compactGainDisplay(0f))
        assertEquals("-6 dB", compactGainDisplay(-6f))
        assertEquals("0 ms", compactDelayDisplay(0f))
        assertEquals("0.5 ms", compactDelayDisplay(0.5f))
        assertEquals("12.0 ms", compactDelayDisplay(12f))
        assertEquals("-6", compactGainEditText(-6f))
        assertEquals("12.0", compactDelayEditText(12f))
    }

    @Test
    fun parseGainAcceptsAnOptionalUnitAndClamps() {
        assertEquals(-3f, parseCompactGain("-3")!!, 0f)
        assertEquals(-3f, parseCompactGain(" -3 dB ")!!, 0f)
        assertEquals(2.5f, parseCompactGain("+2.5dB")!!, 0f)
        assertEquals(2.5f, parseCompactGain("2,5")!!, 0f)
        assertEquals(12f, parseCompactGain("40")!!, 0f)
        assertEquals(-60f, parseCompactGain("-100")!!, 0f)
        assertNull(parseCompactGain("loud"))
        assertNull(parseCompactGain(""))
        assertNull(parseCompactGain("NaN"))
        assertNull(parseCompactGain("1f"))
    }

    @Test
    fun parseDelayClampsToThePlatformMaximum() {
        assertEquals(10f, parseCompactDelay("10 ms", 85f)!!, 0f)
        assertEquals(85f, parseCompactDelay("200", 85f)!!, 0f)
        assertEquals(42f, parseCompactDelay("200", 42f)!!, 0f)
        assertEquals(0f, parseCompactDelay("-5", 85f)!!, 0f)
        assertNull(parseCompactDelay("x", 85f))
    }

    @Test
    fun outputRoundingFollowsCommandsSwift() {
        assertEquals(-3.2f, roundOutputGain(-3.24f), 1e-6f)
        assertEquals(-3.3f, roundOutputGain(-3.25f), 1e-6f)
        assertEquals(0f, roundOutputGain(-0.04f), 0f)
        assertEquals(Float.POSITIVE_INFINITY, 1f / roundOutputGain(-0.04f), 0f) // no negative zero
        assertEquals(13f, roundOutputDelay(12.5f), 0f)
        assertEquals(12f, roundOutputDelay(12.4f), 0f)
        assertEquals(0f, roundOutputDelay(0.3f), 0f)
    }

    @Test
    fun dragMathStepsAndClamps() {
        // 0.1 dB per 3 dp, snapped to 0.1 dB
        assertEquals(-6f, CompactDrag.gain(-6f, 1f), 1e-6f)
        assertEquals(-5.9f, CompactDrag.gain(-6f, 3f), 1e-6f)
        assertEquals(-7f, CompactDrag.gain(-6f, -30f), 1e-6f)
        assertEquals(12f, CompactDrag.gain(11f, 3000f), 0f)
        assertEquals(-60f, CompactDrag.gain(-59f, -3000f), 0f)
        // crosspoint gain: unquantised like the macOS scroll wheel
        assertEquals(-5.95f, CompactDrag.gain(-6f, 1.5f, quantized = false), 1e-6f)
        assertEquals(12f, CompactDrag.gain(11f, 3000f, quantized = false), 0f)
        // 1 ms per 4 dp, whole ms
        assertEquals(15f, CompactDrag.delay(10f, 20f, 85f), 0f)
        assertEquals(0f, CompactDrag.delay(1f, -40f, 85f), 0f)
        assertEquals(42f, CompactDrag.delay(40f, 400f, 42f), 0f)
    }
}
