package se.bofr.dspi.feature.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.bofr.dspi.ui.ValueFormat

class ToolFormatTest {
    @Test
    fun formatTrimsTrailingZerosLikeValueField() {
        assertEquals("83.0", ValueFormat.format(83f, 1))
        assertEquals("83", ValueFormat.format(83f, 1, stripTrailingZeros = true))
        assertEquals("4.5", ValueFormat.format(4.5f, 1))
        assertEquals("700", ValueFormat.format(700f, 0))
        assertEquals("-96.0", ValueFormat.format(-96f, 1))
        assertEquals("0.71", ValueFormat.format(0.707f, 2))
        assertEquals("0.7", ValueFormat.format(0.70f, 2))
        assertEquals("-12.5", ValueFormat.format(-12.5f, 1))
    }

    @Test
    fun fixedAndSignedMatchPrintf() {
        assertEquals("4.2", ToolFormat.fixed(4.2f, 1))
        assertEquals("0", ToolFormat.fixed(-0.2f, 0))
        assertEquals("+4.2", ValueFormat.signed(4.2f, 1))
        assertEquals("+12", ValueFormat.signed(12f, 0))
        assertEquals("-0.35", ValueFormat.signed(-0.35f, 2))
    }

    @Test
    fun typedValuesAreParsedAndClamped() {
        val r = -30f..0f
        assertEquals(-3.5f, ValueFormat.parse("-3.5", r))
        assertEquals(-3.5f, ValueFormat.parse("−3.5 dB", r))
        assertEquals(-3.5f, ValueFormat.parse(" -3,5", r))
        assertEquals(0f, ValueFormat.parse("6", r))
        assertEquals(-30f, ValueFormat.parse("-100dB", r))
        assertNull(ValueFormat.parse("abc", r))
        assertNull(ValueFormat.parse("", r))
    }
}
