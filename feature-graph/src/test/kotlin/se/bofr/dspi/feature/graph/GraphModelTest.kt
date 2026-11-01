package se.bofr.dspi.feature.graph

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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

/** The pure parts of GraphView, GraphSpectrumOverlay, GraphSettings and PeqBandHud. */
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
    }

    @Test
    fun settingsDefaultsAreTheMacOSOnes() {
        val s = GraphSettings()
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
        val s = GraphSettings()
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

    @Test
    fun binSmoothingAveragesPowerOverASixthOfAnOctave() {
        val flat = List(64) { -40.0 }
        rtaSmoothBins(flat, RTA_BIN_SMOOTHING_OCTAVES).forEach { assertEquals(-40.0, it, 1e-9) }
        val tone = MutableList(512) { -120.0 }
        tone[400] = 0.0
        val out = rtaSmoothBins(tone, RTA_BIN_SMOOTHING_OCTAVES)
        assertTrue("a treble tone is spread over its window and reads low", out[400] < -10)
        assertTrue(out[395] > -60)
        assertEquals("the bass is left alone", -120.0, out[3], 1e-9)
    }

    @Test
    fun bandPointsKeepOneBandEitherSideOfTheAxis() {
        val b = RtaCurveBuilder(20.0, 20000.0, 300.0, 100.0, RtaScale(-90.0, 6.0))
        val centres = listOf(10, 16, 25, 40, 63, 100, 20000, 25000, 31500)
        val levels = centres.map { -42f }
        val pts = b.bandPoints(centres, levels, centres.size, firstBand = 1)
        assertEquals("16 Hz (the one below), 25 … 20000, and 25000 (the one above)", 7, pts.size)
        assertTrue(pts.first().x < 0)
        assertTrue(pts.last().x > 300)
        assertEquals(50.0, pts[1].y.toDouble(), 1e-4)
        assertEquals(0.0, b.y(6.0), 1e-9)
        assertEquals(100.0, b.y(-120.0), 1e-9)
    }

    @Test
    fun binsLandOnePerColumnAndBlendIntoTheBands() {
        val b = RtaCurveBuilder(20.0, 20000.0, 300.0, 100.0, RtaScale(-90.0, 6.0))
        val bins = List(512) { -42f }
        val pts = b.binPoints(bins, 512, 48000)
        assertTrue(pts.size > 100)
        assertTrue(pts.zipWithNext().all { (a, c) -> c.x - a.x == 1f })
        pts.forEach { assertEquals(50.0, it.y.toDouble(), 0.01) }
        val bands = listOf(Offset(0f, 80f), Offset(300f, 80f))
        val blended = b.blend(bands, pts, listOf(20, 40, 80, 160, 315, 630), bassBands = 3)
        assertEquals(80f, blended.first().y, 1e-3f)
        assertEquals(50f, blended.last().y, 1e-3f)
        assertEquals("one point per column", 300, blended.size)
    }

    // ── Chip rows ───────────────────────────────────────────────────────────

    @Test
    fun chipRowsFollowTheShape() {
        fun rows(t: Int, gain: Float = 0f) = hudRows(FilterTypes.band(t, 1234.5f, 0.707f, gain)).map { it.second.first }
        assertEquals(listOf("Freq", "Gain", "Width"), rows(FILTER_PEAKING, 3f))
        assertEquals(listOf("Freq", "Slope"), rows(FILTER_HIGHPASS1))
        assertEquals(listOf("Freq", "Order"), rows(FILTER_ALLPASS1))
        assertEquals(listOf("f0", "fp"), rows(FILTER_LINKWITZ_TRANSFORM, 25f))
        val freq = hudRows(FilterTypes.band(FILTER_PEAKING, 1234.5f, 0.707f, 3f))[0].second
        assertEquals("1.23", freq.second)
        assertEquals("kHz", freq.third)
        // The editor's chip size counts the same rows.
        assertEquals(28.0 + 3 * 15 + 5, PeqGraphEditor.hudSizeFor(FilterTypes.band(FILTER_PEAKING, 1000f, 1f, 3f)).height, 0.0)
        assertEquals(28.0 + 2 * 15 + 5, PeqGraphEditor.hudSizeFor(FilterTypes.band(FILTER_HIGHPASS1, 1000f, 1f, 0f)).height, 0.0)
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
