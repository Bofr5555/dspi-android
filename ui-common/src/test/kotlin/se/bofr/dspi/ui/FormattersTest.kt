package se.bofr.dspi.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormattersTest {
    @Test
    fun valueFieldFormatTrimsLikeSwift() {
        assertEquals("1.0", ValueFormat.format(1f, 1))
        assertEquals("1.0", ValueFormat.format(1f, 3))
        assertEquals("1", ValueFormat.format(1f, 3, stripTrailingZeros = true))
        assertEquals("0.707", ValueFormat.format(0.707f, 3, stripTrailingZeros = true))
        assertEquals("-3.5", ValueFormat.format(-3.5f, 1))
        assertEquals("1000.0", ValueFormat.format(1000f, 1))
        assertEquals("12.25", ValueFormat.format(12.25f, 2))
        assertEquals("100", ValueFormat.format(100f, 0))
    }

    @Test
    fun formatTrimmedKeepsSign() {
        assertEquals("+3.0", ValueFormat.formatTrimmed(3.0, 2, signed = true))
        assertEquals("-2.5", ValueFormat.formatTrimmed(-2.5, 2, signed = true))
        assertEquals("0.71", ValueFormat.formatTrimmed(0.707, 2))
    }

    @Test
    fun parseAcceptsCommaAndMinus() {
        assertEquals(1.5f, ValueFormat.parse("1,5"))
        assertEquals(-3f, ValueFormat.parse("−3"))
        assertEquals(20f, ValueFormat.parse(" 20 "))
        assertNull(ValueFormat.parse("abc"))
        assertNull(ValueFormat.parse(""))
    }

    @Test
    fun userVolumeTaperRoundTrips() {
        assertEquals(-60f, UserVolumeTaper.scalarToDB(0.0))
        assertEquals(0f, UserVolumeTaper.scalarToDB(1.0))
        assertEquals(-30f, UserVolumeTaper.scalarToDB(0.25), 1e-4f)
        for (db in listOf(-59f, -40f, -12.5f, -1f, 0f)) {
            assertEquals(db, UserVolumeTaper.scalarToDB(UserVolumeTaper.dbToScalar(db)), 1e-3f)
        }
        assertEquals("-12.5 dB", UserVolumeTaper.displayString(-12.5f))
    }

    @Test
    fun masterTaperBreakpoints() {
        assertEquals(-10f, MasterVolumeTaper.sliderToDB(MasterVolumeTaper.BREAK1), 1e-3f)
        assertEquals(-40f, MasterVolumeTaper.sliderToDB(MasterVolumeTaper.BREAK2), 1e-3f)
        assertEquals(-128f, MasterVolumeTaper.sliderToDB(0f))
        assertEquals(0f, MasterVolumeTaper.sliderToDB(1f))
        for (db in listOf(-127f, -60f, -40f, -25f, -10f, -3.3f, 0f)) {
            assertEquals(db, MasterVolumeTaper.sliderToDB(MasterVolumeTaper.dbToSlider(db)), 1e-3f)
        }
        // Monotonic.
        var prev = -1000f
        for (i in 0..100) {
            val v = MasterVolumeTaper.sliderToDB(i / 100f)
            assertTrue(v >= prev)
            prev = v
        }
    }

    @Test
    fun masterTaperFormatSnapsToFirmwareSteps() {
        assertEquals("−∞", MasterVolumeTaper.format(-128f))
        assertEquals("0", MasterVolumeTaper.format(-0.02f))
        assertEquals("-7.5", MasterVolumeTaper.format(-7.46f))
        assertEquals("-12.5", MasterVolumeTaper.format(-12.4f))
        assertEquals("-12", MasterVolumeTaper.format(-12.2f))
        assertEquals("-57", MasterVolumeTaper.format(-56.6f))
    }

    @Test
    fun paletteFromHexAndAppIds() {
        assertEquals(ChannelPalette.inputs[0], ChannelPalette.color(0))
        assertEquals(ChannelPalette.inputs[2], ChannelPalette.color(11))
        assertEquals(ChannelPalette.outputs[0], ChannelPalette.color(2))
        assertEquals(ChannelPalette.pdm, ChannelPalette.color(10))
        assertEquals(ChannelPalette.pdm, ChannelPalette.color(6, numOutputs = 5))
        assertEquals(androidx.compose.ui.graphics.Color(0xFF4A8FE3), ChannelPalette.fromHex("#4A8FE3"))
    }
}
