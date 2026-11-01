package se.bofr.dspi.feature.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolFormatTest {
    @Test
    fun formatTrimsTrailingZerosLikeValueField() {
        assertEquals("83.0", ToolFormat.format(83f, 1))
        assertEquals("83", ToolFormat.format(83f, 1, stripTrailingZeros = true))
        assertEquals("4.5", ToolFormat.format(4.5f, 1))
        assertEquals("700", ToolFormat.format(700f, 0))
        assertEquals("-96.0", ToolFormat.format(-96f, 1))
        assertEquals("0.71", ToolFormat.format(0.707f, 2))
        assertEquals("0.7", ToolFormat.format(0.70f, 2))
        assertEquals("-12.5", ToolFormat.format(-12.5f, 1))
    }

    @Test
    fun fixedAndSignedMatchPrintf() {
        assertEquals("4.2", ToolFormat.fixed(4.2f, 1))
        assertEquals("0", ToolFormat.fixed(-0.2f, 0))
        assertEquals("+4.2", ToolFormat.signed(4.2f, 1))
        assertEquals("+12", ToolFormat.signed(12f, 0))
        assertEquals("-0.35", ToolFormat.signed(-0.35f, 2))
    }

    @Test
    fun typedValuesAreParsedAndClamped() {
        val r = -30f..0f
        assertEquals(-3.5f, ToolFormat.parse("-3.5", r))
        assertEquals(-3.5f, ToolFormat.parse("−3.5 dB", r))
        assertEquals(-3.5f, ToolFormat.parse(" -3,5", r))
        assertEquals(0f, ToolFormat.parse("6", r))
        assertEquals(-30f, ToolFormat.parse("-100dB", r))
        assertNull(ToolFormat.parse("abc", r))
        assertNull(ToolFormat.parse("", r))
    }
}
