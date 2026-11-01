package se.bofr.dspi.fake

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.core.DspiConstants.FILTER_ALLPASS
import se.bofr.dspi.core.DspiConstants.FILTER_ALLPASS1
import se.bofr.dspi.core.DspiConstants.FILTER_FLAT
import se.bofr.dspi.core.DspiConstants.FILTER_HIGHPASS
import se.bofr.dspi.core.DspiConstants.FILTER_HIGHSHELF
import se.bofr.dspi.core.DspiConstants.FILTER_LINKWITZ_TRANSFORM
import se.bofr.dspi.core.DspiConstants.FILTER_LOWPASS
import se.bofr.dspi.core.DspiConstants.FILTER_LOWPASS1
import se.bofr.dspi.core.DspiConstants.FILTER_LOWSHELF
import se.bofr.dspi.core.DspiConstants.FILTER_LOWSHELF1
import se.bofr.dspi.core.DspiConstants.FILTER_NOTCH
import se.bofr.dspi.core.DspiConstants.FILTER_PEAKING
import se.bofr.dspi.core.FilterParams

/** The tests of DSPi-Console-Linux:core/src/dsp_math.rs @ 4683a2c, translated. */
class DspMathTest {
    private fun band(t: Int, freq: Float, q: Float = 0.707f, gain: Float = 0f) = FilterParams(t, false, freq, q, gain, 0.707f)

    private fun r(f: Float, vararg b: FilterParams) = DspMath.responseAt(f, b.toList())

    @Test fun flatIsZeroDb() = assertTrue(abs(r(1000f, band(FILTER_FLAT, 1000f))) < 0.001f)

    @Test fun peakingHitsGainAtCenterOnly() {
        val f = band(FILTER_PEAKING, 1000f, 1f, 6f)
        assertTrue(abs(r(1000f, f) - 6f) < 0.01f)
        assertTrue(abs(r(20f, f)) < 0.5f)
    }

    @Test fun bypassedBandIsIgnored() = assertTrue(abs(r(1000f, band(FILTER_PEAKING, 1000f, 1f, 6f).copy(bypass = true))) < 0.001f)

    @Test fun lowAndHighPass() {
        val lp = band(FILTER_LOWPASS, 1000f)
        assertTrue(abs(r(20f, lp)) < 0.5f)
        assertTrue(r(10000f, lp) < -20f)
        val hp = band(FILTER_HIGHPASS, 1000f)
        assertTrue(abs(r(10000f, hp)) < 0.5f)
        assertTrue(r(100f, hp) < -20f)
    }

    @Test fun shelves() {
        val ls = band(FILTER_LOWSHELF, 200f, 0.707f, 6f)
        assertTrue(abs(r(20f, ls) - 6f) < 1f)
        assertTrue(abs(r(10000f, ls)) < 0.5f)
        val hs = band(FILTER_HIGHSHELF, 5000f, 0.707f, 6f)
        assertTrue(abs(r(20000f, hs) - 6f) < 1f)
        assertTrue(abs(r(100f, hs)) < 0.5f)
    }

    @Test fun firstOrderTypes() {
        val lp1 = band(FILTER_LOWPASS1, 1000f)
        assertTrue(abs(r(1000f, lp1) + 3.01f) < 0.1f)
        assertTrue(abs(r(10000f, lp1) + 20f) < 1.5f)
        val ap1 = band(FILTER_ALLPASS1, 1000f)
        assertTrue(abs(r(300f, ap1)) < 0.001f)
        assertTrue(abs(DspMath.phaseAt(1000f, listOf(ap1)) + 90f) < 0.5f)
        assertTrue(abs(r(10f, band(FILTER_LOWSHELF1, 100f, 0.707f, 6f)) - 6f) < 0.5f)
    }

    @Test fun notchAndAllpass() {
        assertTrue(r(1000f, band(FILTER_NOTCH, 1000f, 2f)) < -40f)
        val ap = band(FILTER_ALLPASS, 1000f)
        assertTrue(abs(r(400f, ap)) < 0.001f)
        assertTrue(abs(abs(DspMath.phaseAt(1000f, listOf(ap))) - 180f) < 0.5f)
    }

    @Test fun linkwitzTransformShiftsTheCorner() {
        val lt = band(FILTER_LINKWITZ_TRANSFORM, 50f, 1f, 25f)
        assertTrue(r(20f, lt) > 6f)
        assertTrue(abs(r(2000f, lt)) < 0.1f)
    }

    @Test fun crossoversAreMinus3Or6DbAtFc() {
        for ((t, want) in listOf(46 to -3.01f, 47 to -3.01f, 34 to -6.02f, 35 to -6.02f, 38 to -6.02f, 44 to -3.01f)) {
            val v = r(1000f, band(t, 1000f))
            assertTrue("type $t: $v dB at fc", abs(v - want) < 0.1f)
        }
        assertTrue(r(4000f, band(34, 1000f)) < -40f)
        val bes = band(58, 1000f)
        assertTrue(abs(r(20f, bes)) < 0.1f)
        assertTrue(r(10000f, bes) < -30f)
    }

    @Test fun crossoverSectionCounts() {
        assertEquals(1, DspMath.crossoverSections(32, 1000.0).size)
        assertEquals(4, DspMath.crossoverSections(38, 1000.0).size)
        assertEquals(2, DspMath.crossoverSections(44, 1000.0).size)
        assertEquals(4, DspMath.crossoverSections(62, 1000.0).size)
    }

    @Test fun crossoverMetaDecodesTable() {
        assertEquals(DspMath.XoverMeta(DspMath.XoverFamily.LINKWITZ_RILEY, 2, false), DspMath.crossoverMeta(32))
        assertEquals(DspMath.XoverMeta(DspMath.XoverFamily.LINKWITZ_RILEY, 8, true), DspMath.crossoverMeta(39))
        assertEquals(DspMath.XoverMeta(DspMath.XoverFamily.BUTTERWORTH, 1, false), DspMath.crossoverMeta(40))
        assertEquals(DspMath.XoverMeta(DspMath.XoverFamily.BUTTERWORTH, 8, true), DspMath.crossoverMeta(55))
        assertEquals(DspMath.XoverMeta(DspMath.XoverFamily.BESSEL, 2, false), DspMath.crossoverMeta(56))
        assertEquals(DspMath.XoverMeta(DspMath.XoverFamily.BESSEL, 8, true), DspMath.crossoverMeta(63))
        assertEquals(null, DspMath.crossoverMeta(13))
        assertEquals(null, DspMath.crossoverMeta(64))
    }

    @Test fun curveShapes() {
        val f = listOf(band(FILTER_PEAKING, 1000f, 1f, 6f))
        val m = DspMath.magnitudeCurve(f)
        assertEquals(201, m.size)
        assertTrue(abs(DspMath.curveFreq(0) - 10.0) < 1e-9)
        assertTrue(abs(DspMath.curveFreq(200) - 20000.0) < 1e-6)
        assertTrue(abs(m[120] - DspMath.responseAt(DspMath.curveFreq(120).toFloat(), f)) < 0.01)
        val p = DspMath.phaseCurve(listOf(band(38, 1000f)), true)
        assertTrue("LR8 unwrapped phase should pass −540°, got ${p[200]}", p[200] < -500.0)
    }

    @Test fun quantize() {
        assertEquals(3.1f, DspMath.quantizeGain(3.14f))
        assertEquals(3.2f, DspMath.quantizeGain(3.15f))
        assertEquals(0f.toBits(), DspMath.quantizeGain(-0.0f).toBits())
        assertEquals(0f.toBits(), DspMath.quantizeDelay(-0.00002f).toBits())
        assertEquals(0.25f, DspMath.quantizeDelay(0.25f))
        assertEquals(1.2346f, DspMath.quantizeDelay(1.23456f))
    }
}
