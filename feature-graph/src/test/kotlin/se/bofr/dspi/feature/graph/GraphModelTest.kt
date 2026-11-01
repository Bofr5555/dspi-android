package se.bofr.dspi.feature.graph

import androidx.compose.ui.graphics.Color
import kotlin.math.exp
import kotlin.math.log10
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.core.DspiConstants.FILTER_ALLPASS1
import se.bofr.dspi.core.DspiConstants.FILTER_HIGHPASS1
import se.bofr.dspi.core.DspiConstants.FILTER_LINKWITZ_TRANSFORM
import se.bofr.dspi.core.DspiConstants.FILTER_PEAKING
import se.bofr.dspi.ui.settings.AppSettings

/** The pure parts of GraphView, GraphSpectrumOverlay, AppSettings and PeqBandHud. */
class GraphModelTest {
    // ── GraphView ───────────────────────────────────────────────────────────

    @Test
    fun identicalCurvesShareOneLineAndTheEditedChannelIsLeftOut() {
        val flat = List(201) { 0.0 }
        val boost = List(201) { 3.0 }
        val curves = mapOf(0 to flat, 1 to flat, 2 to boost, 3 to flat)
        val groups = groupedChannels(listOf(0, 1, 2, 3), { curves.getValue(it) }, activeEq = 2, edited = 3)
        assertEquals(listOf(listOf(0, 1), listOf(2)), groups.map { it.id })
        assertFalse("other channels are dashed on a channel page", groups[0].entries.any { it.isActive })
        assertTrue(groups[1].entries.single().isActive)
        val dashboard = groupedChannels(listOf(0, 1, 2), { curves.getValue(it) }, activeEq = null, edited = null)
        assertTrue(dashboard.all { g -> g.entries.all { it.isActive } })
    }

    @Test
    fun gradientStopsMatchMacOS() {
        val two = gradientStops(listOf(Color.Red, Color.Blue))
        assertEquals(listOf(0.0, 0.2, 0.3, 0.7, 0.8, 1.0), two.map { Math.round(it.first * 1000) / 1000.0 })
        assertEquals(Color.Red, two[2].second)
        assertEquals(Color.Blue, two[3].second)
        assertEquals(2, gradientStops(listOf(Color.Red)).size)
    }

    @Test
    fun dbAxisStepsAndLabels() {
        assertEquals(1.0, dbGridStep(10.0), 0.0)
        assertEquals(3.0, dbGridStep(30.0), 0.0)
        assertEquals(5.0, dbGridStep(50.0), 0.0)
        assertEquals(10.0, dbGridStep(100.0), 0.0)
        assertEquals("+0", dbLabel(0.0))
        assertEquals("+6", dbLabel(6.0))
        assertEquals("-12", dbLabel(-12.0))
        assertEquals("+2.5", dbLabel(2.5))
        assertEquals("%g: six significant digits", "-0.333333", dbLabel(-1.0 / 3))
        assertEquals("+100", dbLabel(100.0))
    }

    @Test
    fun settingsDefaultsAreTheMacOSOnes() {
        val s = AppSettings()
        assertEquals(25.0, s.dbTop, 0.0)
        assertEquals(-25.0, s.dbBottom, 0.0)
        assertEquals(15.0, s.graphMinFreq, 0.0)
        assertEquals(20000.0, s.graphMaxFreq, 0.0)
        assertEquals(0.5, s.graphGridOpacity, 0.0)
        assertEquals(180.0, s.phaseTop, 0.0)
        assertEquals(360.0, s.copy(graphDBRange = 100.0).phaseTop, 0.0)
        assertEquals(s, s.copy(graphMinFreq = 50.0, graphDBRange = 20.0, graphDBCenter = -10.0).resetScale())
        assertFalse(s.withRtaShowsGraph(onDashboard = true, on = false).rtaShowsGraph(onDashboard = true))
        assertTrue(s.withRtaShowsGraph(onDashboard = true, on = false).rtaShowsGraph(onDashboard = false))
    }

    // ── Spectrum selection ──────────────────────────────────────────────────

    @Test
    fun selectionStorageKeysRoundTrip() {
        val sel = RtaChannelSelection(DspiConstants.RTA_TAP_INPUT, listOf(2, 0, 2))
        assertEquals(listOf(0, 2), sel.channels)
        assertEquals("in:0,2", sel.storageKey)
        assertEquals(0b101, sel.mask)
        assertEquals(sel, RtaChannelSelection.fromStorageKey("in:0,2"))
        assertTrue(RtaChannelSelection.fromStorageKey("out:")!!.isEmpty)
        assertNull(RtaChannelSelection.fromStorageKey("sideways:1"))
        assertNull(RtaChannelSelection.fromStorageKey("in:x"))
        assertEquals(listOf(0), sel.toggled(2).channels)
        assertEquals("Spectrum hidden", spectrumSummary(RtaChannelSelection.NONE))
        assertEquals("1 channel", spectrumSummary(sel.toggled(2)))
        assertEquals("2 channels", spectrumSummary(sel))
    }

    @Test
    fun dashboardSelectionFallsBackToTheFirstOutput() {
        val avail = { tap: Int -> if (tap == DspiConstants.RTA_TAP_OUTPUT) listOf(1, 3) else listOf(0, 1) }
        val s = AppSettings()
        assertEquals("out:1", SpectrumSelectionState.dashboardSelection(s, avail).storageKey)
        assertEquals("in:1", SpectrumSelectionState.dashboardSelection(s.copy(rtaDashboardSelectionKey = "in:1,5"), avail).storageKey)
        assertEquals(
            "a deliberate nothing stays nothing",
            "in:",
            SpectrumSelectionState.dashboardSelection(s.copy(rtaDashboardSelectionKey = "in:"), avail).storageKey
        )
        assertEquals("out:1", SpectrumSelectionState.dashboardSelection(s.copy(rtaDashboardSelectionKey = "in:7"), avail).storageKey)
        assertEquals("in:0", SpectrumSelectionState.dashboardSelection(s, { emptyList() }).storageKey)
    }

    // ── Spectrum curves ─────────────────────────────────────────────────────

    private fun smooth(levels: DoubleArray): DoubleArray {
        val out = DoubleArray(levels.size)
        rtaSmoothBins(levels, levels.size, RTA_BIN_SMOOTHING_OCTAVES, DoubleArray(levels.size + 1), out)
        return out
    }

    @Test
    fun binSmoothingAveragesPowerOverASixthOfAnOctave() {
        smooth(DoubleArray(64) { -40.0 }).forEach { assertEquals(-40.0, it, 1e-9) }
        val tone = DoubleArray(512) { -120.0 }
        tone[400] = 0.0
        val out = smooth(tone)
        assertTrue("a treble tone is spread over its window and reads low", out[400] < -10)
        assertTrue(out[395] > -60)
        assertEquals("the bass is left alone", -120.0, out[3], 1e-9)
    }

    @Test
    fun bandPointsKeepOneBandEitherSideOfTheAxis() {
        val b = RtaCurveBuilder()
        b.configure(20.0, 20000.0, 300.0, 100.0, RtaScale(-90.0, 6.0))
        val centres = listOf(10, 16, 25, 40, 63, 100, 20000, 25000, 31500)
        val levels = DoubleArray(centres.size) { -42.0 }
        val xs = FloatArray(16)
        val ys = FloatArray(16)
        val n = b.bandPoints(centres, levels, levels.size, centres.size, firstBand = 1, xs, ys)
        assertEquals("16 Hz (the one below), 25 … 20000, and 25000 (the one above)", 7, n)
        assertTrue(xs[0] < 0)
        assertTrue(xs[n - 1] > 300)
        assertEquals(50.0, ys[1].toDouble(), 1e-4)
        assertEquals(0.0, b.y(6.0), 1e-9)
        assertEquals(100.0, b.y(-120.0), 1e-9)
    }

    @Test
    fun missingBandLevelsReadTheFloor() {
        val b = RtaCurveBuilder()
        b.configure(20.0, 20000.0, 300.0, 100.0, RtaScale(-90.0, 6.0))
        val xs = FloatArray(8)
        val ys = FloatArray(8)
        // Five slots, levels for only three: the last two sit on the floor (macOS).
        val n = b.bandPoints(listOf(25, 50, 100, 200, 400), DoubleArray(3) { -42.0 }, 3, 5, 0, xs, ys)
        assertEquals(5, n)
        assertEquals(100.0, ys[4].toDouble(), 1e-4)
        assertEquals(50.0, ys[2].toDouble(), 1e-4)
    }

    @Test
    fun theBandCurveIsSampledWithMacOSHermite() {
        val b = RtaCurveBuilder()
        b.configure(20.0, 20000.0, 300.0, 100.0, RtaScale(-90.0, 6.0))
        val noBins = DoubleArray(b.columns)
        assertFalse(b.binColumns(noBins, 0, 48000, noBins))
        val out = FloatArray(b.columns)
        // A straight line stays straight.
        b.blend(floatArrayOf(0f, 100f, 200f), floatArrayOf(20f, 40f, 60f), 3, noBins, emptyList(), 0, out)
        assertEquals(30f, out[50], 1e-4f)
        assertEquals(50f, out[150], 1e-4f)
        assertTrue("past the last band: no data", out[250].isNaN())
        // A peak: the Hermite curve passes through the points and rounds over
        // between them (macOS sampleBands, slopes from the neighbours).
        b.blend(floatArrayOf(0f, 100f, 200f), floatArrayOf(80f, 20f, 80f), 3, noBins, emptyList(), 0, out)
        assertEquals(20f, out[100], 1e-4f)
        val t = 0.5f
        val slope0 = (20f - 80f) / 100f
        val expected =
            (2 * t * t * t - 3 * t * t + 1) * 80f + (t * t * t - 2 * t * t + t) * 100f * slope0 + (-2 * t * t * t + 3 * t * t) * 20f
        assertEquals(expected, out[50], 1e-3f)
    }

    @Test
    fun binsLandOnePerColumnAndBlendIntoTheBands() {
        val b = RtaCurveBuilder()
        b.configure(20.0, 20000.0, 300.0, 100.0, RtaScale(-90.0, 6.0))
        val cols = DoubleArray(b.columns)
        assertTrue(b.binColumns(DoubleArray(512) { -42.0 }, 512, 48000, cols))
        assertTrue(b.binLast - b.binFirst > 100)
        for (c in b.binFirst..b.binLast) assertEquals(-42.0, cols[c], 1e-9)
        val bx = floatArrayOf(0f, 300f)
        val by = floatArrayOf(80f, 80f)
        val out = FloatArray(b.columns)
        val filled = b.blend(bx, by, 2, cols, listOf(20, 40, 80, 160, 315, 630), bassBands = 3, outY = out)
        assertEquals("one point per column", 300, filled)
        assertEquals(80f, out[0], 1e-3f)
        assertEquals(50f, out[299], 1e-3f)
        assertFalse("geometry unchanged: nothing to rebuild", b.configure(20.0, 20000.0, 300.0, 100.0, RtaScale(-90.0, 6.0)))
    }

    @Test
    fun barSmootherRisesFastFallsSlowAndSnapsOnANewIdentity() {
        val s = RtaBarSmoother()
        val target = DoubleArray(2) { -60.0 }
        assertEquals(-60.0, s.step(0.0, target, 2, 1, 0.1, 0.4)[0], 0.0)
        target[0] = 0.0
        target[1] = -100.0
        val v = s.step(0.1, target, 2, 1, 0.1, 0.4)
        assertEquals("rise: 1 - e^-1 of the way", -60.0 + 60 * (1 - exp(-1.0)), v[0], 1e-9)
        assertEquals("fall: 1 - e^-0.25 of the way", -60.0 - 40 * (1 - exp(-0.25)), v[1], 1e-9)
        assertEquals("no time passed: unchanged", v[0], s.step(0.1, target, 2, 1, 0.1, 0.4)[0], 0.0)
        assertEquals("tau 0 jumps", 0.0, s.step(0.2, target, 2, 1, 0.0, 0.4)[0], 0.0)
        target[0] = -30.0
        assertEquals("a new channel snaps", -30.0, s.step(0.3, target, 2, 2, 0.1, 0.4)[0], 0.0)
        assertEquals("a new count snaps", -30.0, s.step(0.4, target, 1, 2, 0.1, 0.4)[0], 0.0)
    }

    @Test
    fun fallTauFollowsTheRefreshInterval() {
        assertEquals(0.0, rtaFallTau(0.1, 0.0), 0.0)
        assertEquals(0.035, rtaFallTau(0.01, AppSettings.RTA_SMOOTHING_AMOUNT), 1e-12)
        assertEquals(0.40, rtaFallTau(2.0, AppSettings.RTA_SMOOTHING_AMOUNT), 1e-12)
        assertEquals(0.07, rtaFallTau(0.2, AppSettings.RTA_SMOOTHING_AMOUNT), 1e-12)
        assertEquals(0.35, AppSettings().rtaSmoothing, 0.0)
        assertEquals(0.0, AppSettings(rtaSmoothingOn = false).rtaSmoothing, 0.0)
    }

    // ── Chip rows and typing ────────────────────────────────────────────────

    @Test
    fun chipRowsFollowTheShape() {
        fun rows(t: Int, gain: Float = 0f) = hudRows(FilterTypes.band(t, 1234.5f, 0.707f, gain)).map { it.label }
        assertEquals(listOf("Freq", "Gain", "Width"), rows(FILTER_PEAKING, 3f))
        assertEquals(listOf("Freq", "Slope"), rows(FILTER_HIGHPASS1))
        assertEquals(listOf("Freq", "Order"), rows(FILTER_ALLPASS1))
        assertEquals(listOf("f0", "fp"), rows(FILTER_LINKWITZ_TRANSFORM, 25f))
        val freq = hudRows(FilterTypes.band(FILTER_PEAKING, 1234.5f, 0.707f, 3f))[0]
        assertEquals("1.23", freq.number)
        assertEquals("kHz", freq.unit)
        assertFalse(hudRows(FilterTypes.band(FILTER_HIGHPASS1, 100f, 0.707f, 0f))[1].adjustable)
        // The editor's chip size counts the same rows.
        assertEquals(28.0 + 3 * 15 + 5, PeqGraphEditor.hudSizeFor(FilterTypes.band(FILTER_PEAKING, 1000f, 1f, 3f)).height, 0.0)
        assertEquals(28.0 + 2 * 15 + 5, PeqGraphEditor.hudSizeFor(FilterTypes.band(FILTER_HIGHPASS1, 1000f, 1f, 0f)).height, 0.0)
    }

    @Test
    fun typingSeedsTheBareNumberAndTabSkipsFixedRows() {
        val bell = FilterTypes.band(FILTER_PEAKING, 1234.5f, 0.707f, -3.456f)
        assertEquals("1.23k", hudEditSeed(PeqHudField.FREQ, bell))
        assertEquals("-3.46", hudEditSeed(PeqHudField.GAIN, bell))
        assertEquals("Q keeps three places, so Apply unchanged is a no-op", "0.707", hudEditSeed(PeqHudField.Q, bell))
        assertEquals(PeqHudField.GAIN, nextHudField(PeqHudField.FREQ, bell, backwards = false))
        assertEquals(PeqHudField.FREQ, nextHudField(PeqHudField.Q, bell, backwards = false))
        assertEquals(PeqHudField.Q, nextHudField(PeqHudField.FREQ, bell, backwards = true))
        val cut = FilterTypes.band(FILTER_HIGHPASS1, 100f, 0.707f, 0f)
        assertEquals("a 6 dB cut has only Freq to type", PeqHudField.FREQ, nextHudField(PeqHudField.FREQ, cut, backwards = false))
    }

    @Test
    fun negativeZeroIsTheSameBand() {
        val a = FilterTypes.band(FILTER_PEAKING, 1000f, 1f, 0f)
        val b = a.copy(gain = -0f)
        assertFalse("the data class tells them apart", a == b)
        assertTrue(a.sameAs(b))
        assertTrue(bandsSame(listOf(a), listOf(b)))
        assertTrue(PeqEditorConfig(bands = listOf(a)).sameAs(PeqEditorConfig(bands = listOf(b))))
    }

    @Test
    fun rendererCombinesBandsStaticsAndOffset() {
        val g = PeqGraphGeometry(400.0, 200.0, 20.0, 20000.0, 25.0, -25.0)
        val bell = FilterTypes.band(FILTER_PEAKING, 1000f, 1f, 6f)
        val pic = PeqPicture(geometry = g, bands = listOf(bell) + List(11) { null }, offsetDb = -3f)
        val r = PeqGraphRenderer()
        val combined = r.combined(pic)
        val at1k = ((log10(1000.0) - log10(20.0)) / (log10(20000.0) - log10(20.0)) * (combined.size - 1)).toInt()
        assertEquals(3.0, combined.maxOrNull()!!, 0.1)
        assertEquals(3.0, combined[at1k], 0.2)
        assertEquals(-3.0, combined.first(), 0.05)
        val passes = r.rowPasses
        r.combined(pic)
        assertEquals("unchanged bands are not re-evaluated", passes, r.rowPasses)
        assertEquals(
            "a bypassed band leaves the curve",
            -3.0,
            r.combined(pic.copy(bands = listOf(bell.copy(bypass = true)))).maxOrNull()!!,
            1e-9
        )
    }
}
