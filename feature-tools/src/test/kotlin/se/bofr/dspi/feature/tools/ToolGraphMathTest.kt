package se.bofr.dspi.feature.tools

import kotlin.math.abs
import kotlin.math.log10
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolGraphMathTest {
    @Test
    fun logAndLinearMapping() {
        assertEquals(0f, ToolGraphMath.logX(20.0, 20.0, 20000.0, 300f), 1e-3f)
        assertEquals(100f, ToolGraphMath.logX(200.0, 20.0, 20000.0, 300f), 1e-3f)
        assertEquals(300f, ToolGraphMath.logX(50000.0, 20.0, 20000.0, 300f), 1e-3f)
        assertEquals(100f, ToolGraphMath.linY(-10.0, -10.0, 10.0, 100f), 1e-4f)
        assertEquals(0f, ToolGraphMath.linY(10.0, -10.0, 10.0, 100f), 1e-4f)
    }

    @Test
    fun dynamicDomainHasAtLeastTenDbAndPadding() {
        assertEquals(Pair(-2.0, 12.0), ToolGraphMath.dynamicDomain(listOf(0.0, 10.0)))
        val (lo, hi) = ToolGraphMath.dynamicDomain(listOf(1.0, 1.0))
        assertEquals(-1.0, lo, 1e-9)
        assertEquals(3.0, hi, 1e-9)
        assertEquals(listOf(-5.0, 0.0, 5.0), ToolGraphMath.gridSteps(-5.0, 7.0))
        assertEquals(listOf(-50.0, -40.0, -30.0, -20.0, -10.0), ToolGraphMath.gridSteps(-55.0, -5.0))
        assertEquals("1k", ToolGraphMath.freqLabel(1000))
        assertEquals("10k", ToolGraphMath.freqLabel(10000))
        assertEquals("100", ToolGraphMath.freqLabel(100))
    }

    @Test
    fun loudnessCurveIsFlatAtOneKilohertzAndScalesWithIntensity() {
        val curve = LoudnessCurve.computeCurve(83f, 100f)
        assertEquals(30, curve.size)
        val at1k = curve.first { it.first == 1000f }.second
        assertEquals(0f, at1k, 0.05f)
        // Bass is boosted at low volume.
        assertTrue(curve.first().second > 10f)
        val half = LoudnessCurve.computeCurve(83f, 50f)
        curve.zip(half).forEach { (a, b) -> assertEquals(a.second / 2, b.second, 1e-3f) }
        LoudnessCurve.computeCurve(83f, 0f).forEach { assertEquals(0f, it.second, 0f) }
        // At or above the reference there is nothing to compensate.
        assertEquals(0f, LoudnessCurve.loudnessCompensationDB(78.5f, 0.532f, -31.6f, 60f, 60f, 100f), 0f)
    }

    @Test
    fun crossfeedLowpassAndComplement() {
        val c = CrossfeedCurve.computeCurves(700f, 4.5f)
        assertEquals(100, c.size)
        assertEquals(20.0, c.first().first, 1e-9)
        assertEquals(20000.0, c.last().first, 1e-6)
        // At DC the lowpass passes G = 1 / (1 + 10^(feed/20)).
        val g = 1.0 / (1.0 + Math.pow(10.0, 4.5 / 20.0))
        assertEquals(20 * log10(g), c.first().second, 0.05)
        // The crossfeed path falls off above the cutoff; the direct one rises toward 0 dB.
        assertTrue(c.last().second < c.first().second - 20)
        assertTrue(abs(c.last().third) < 0.5)
    }

    @Test
    fun psybassSpectrumFractions() {
        assertEquals(1f, PsybassSpectrum.originalFrac(0f))
        assertEquals(0f, PsybassSpectrum.originalFrac(-60f))
        assertEquals(0.5f, PsybassSpectrum.originalFrac(-30f))
        assertEquals(1f, PsybassSpectrum.harmonicsFrac(12f))
        assertEquals(0f, PsybassSpectrum.harmonicsFrac(-24f))
        assertEquals(2f / 3f, PsybassSpectrum.harmonicsFrac(0f), 1e-6f)
    }

    @Test
    fun subharmBellPeaksAtSeventyHertz() {
        assertEquals(0.0, SubharmBandMath.bellDB(70.0, 0f), 0.0)
        assertEquals(6.0, SubharmBandMath.bellDB(70.0, 6f), 1e-9)
        assertTrue(SubharmBandMath.bellDB(20.0, 6f) < 2.0)
        // A 0 dB band is the divider's 1.4 dB below its program.
        assertEquals(-1.42, SubharmBandMath.subDB(0f, 30.0, 0f), 0.01)
        assertTrue(SubharmBandMath.isOff(-30f))
    }

    @Test
    fun tubeShaperIsLevelNeutralAndMeasuresHarmonics() {
        val clean = TubeShaper(-12f, 10f, 3f, 40f, 100f, 0f)
        assertEquals(0.0, clean.output(0.0), 1e-12)
        // Small-signal gain is unity (makeup gain in the half scales).
        assertEquals(0.001, clean.output(0.001), 1e-5)
        val (h2, h3) = clean.harmonics()
        assertTrue(h2 < -20 && h2 > -120)
        assertTrue(h3 < 0)
        // Dry only: the identity, no harmonics.
        val dry = TubeShaper(24f, 50f, 6f, 100f, 0f, 0f)
        assertEquals(0.5, dry.output(0.5), 1e-12)
        assertEquals(Pair(-120.0, -120.0), dry.harmonics())
        // Knees: m x + b = 1 at 0 dB drive and no bias is x = 1.
        val unity = TubeShaper(0f, 0f, 0f, 0f, 100f, 0f)
        assertEquals(1.0, unity.positiveKneeX, 1e-12)
        assertEquals(-1.0, unity.negativeKneeX, 1e-12)
        assertEquals("none", TubeShaper.harmonicText(-120.0))
        assertEquals("-34 dB", TubeShaper.harmonicText(-34.2))
    }
}
