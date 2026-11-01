package se.bofr.dspi.feature.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.core.DspiConstants

class SpectrumAnalyserTest {
    private val out = DspiConstants.RTA_TAP_OUTPUT
    private val inp = DspiConstants.RTA_TAP_INPUT

    @Test
    fun scaleNormalisesAndGridsEvery12Db() {
        val s = RtaScale(-90.0, 6.0)
        assertEquals(0.0, s.norm(-120.0), 0.0)
        assertEquals(1.0, s.norm(12.0), 0.0)
        assertEquals(0.5, s.norm(-42.0), 1e-9)
        assertEquals(listOf(0, -12, -24, -36, -48, -60, -72, -84), s.gridLines().toList())
    }

    @Test
    fun fallTauIsClampedAndOffAtZero() {
        assertEquals(0.0, rtaFallTau(0.1, 0.0), 0.0)
        assertEquals(0.035, rtaFallTau(0.01, 0.35), 1e-9)
        assertEquals(0.40, rtaFallTau(5.0, 0.35), 1e-9)
        assertEquals(0.07, rtaFallTau(0.2, 0.35), 1e-9)
    }

    @Test
    fun smootherSnapsOnNewIdentityRisesFasterThanItFalls() {
        val sm = RtaBarSmoother(4)
        sm.setTarget(floatArrayOf(-60f, -60f), 2, identity = 1)
        assertEquals(-60f, sm[0], 0f)
        assertTrue(sm.isSettled)
        // Same series: glide
        sm.setTarget(floatArrayOf(-30f, -90f), 2, identity = 1)
        assertFalse(sm.isSettled)
        assertTrue(sm.step(0L, riseTau = 0.04, fallTau = 0.1))
        val up = sm[0] - -60f
        val down = -60f - sm[1]
        assertTrue("rises faster than it falls", up / 30f > down / 30f)
        // A new channel snaps instead of sliding
        sm.setTarget(floatArrayOf(-10f, -10f), 2, identity = 2)
        assertEquals(-10f, sm[0], 0f)
        assertEquals(-10f, sm[1], 0f)
    }

    @Test
    fun smootherSettlesAndPeaksJumpUp() {
        val sm = RtaBarSmoother(4)
        sm.setTarget(floatArrayOf(-60f, -60f), 2, 7)
        sm.setTarget(floatArrayOf(-60f, -20f), 2, 7)
        sm.step(0L, 0.05, 0.1, peakFrom = 1)
        assertEquals("peak cap jumps up at once", -20f, sm[1], 0f)
        sm.setTarget(floatArrayOf(-20f, -20f), 2, 7)
        var t = 0L
        var frames = 0
        while (sm.step(t, 0.05, 0.1) && frames < 1000) {
            t += 16_666_667L
            frames++
        }
        assertTrue(sm.isSettled)
        assertEquals(-20f, sm[0], 0f)
        assertTrue(frames in 5..200)
    }

    @Test
    fun snapIgnoresSmoothing() {
        val sm = RtaBarSmoother(2)
        sm.setTarget(floatArrayOf(-50f), 1, 1)
        sm.setTarget(floatArrayOf(-10f), 1, 1)
        sm.snap()
        assertEquals(-10f, sm[0], 0f)
        assertFalse(sm.step(1L, 0.1, 0.1))
    }

    @Test
    fun selectionTogglesWithinOneTapAndRemembersTheOtherSide() {
        val a = RtaChannelSelection.of(out, listOf(3, 1))
        assertEquals(listOf(1, 3), a.channels)
        assertEquals(0b1010, a.mask)
        assertEquals(listOf(1), a.toggling(out, 3).channels)
        assertEquals(listOf(1, 2, 3), a.toggling(out, 2).channels)
        assertEquals(a, a.toggling(inp, 0)) // never mixes taps
        assertEquals("out:1,3", a.storageKey)
        assertEquals(a, RtaChannelSelection.fromStorageKey("out:1,3"))
        assertEquals(RtaChannelSelection.of(inp, emptyList()), RtaChannelSelection.fromStorageKey("in:"))
        assertNull(RtaChannelSelection.fromStorageKey("x:1"))
        assertNull(RtaChannelSelection.fromStorageKey("out:16"))

        val (active, remembered) = RtaChannelSelection.switchingSides(a, null, inp)
        assertEquals(RtaChannelSelection.of(inp, emptyList()), active)
        assertEquals(a, remembered)
        val (back, other) = RtaChannelSelection.switchingSides(active.toggling(inp, 0), remembered, out)
        assertEquals(a, back)
        assertEquals(RtaChannelSelection.of(inp, listOf(0)), other)
    }

    @Test
    fun resolveFallsBackLikeTheDashboard() {
        val available = mapOf(out to listOf(0, 2), inp to listOf(0, 1))
        fun resolve(s: RtaChannelSelection?) = RtaChannelSelection.resolve(s) { available.getValue(it) }
        assertEquals(RtaChannelSelection.of(out, listOf(0)), resolve(null))
        assertEquals(RtaChannelSelection.of(out, listOf(2)), resolve(RtaChannelSelection.of(out, listOf(2, 5))))
        // A deliberately empty selection stays empty
        assertEquals(RtaChannelSelection.of(out, emptyList()), resolve(RtaChannelSelection.of(out, emptyList())))
        // Every chosen channel gone, no outputs: input 1
        val none = RtaChannelSelection.resolve(RtaChannelSelection.of(out, listOf(5))) { emptyList() }
        assertEquals(RtaChannelSelection.of(inp, listOf(0)), none)
    }

    @Test
    fun preferencesReadTheMacKeys() {
        assertEquals(RtaPreferences(), rtaPreferencesFrom { null })
        val store =
            InMemoryRtaSettingsStore(
                mutableMapOf(
                    "rtaFloorDB" to -120.0,
                    "rtaSmoothingOn" to false,
                    "rtaBarColumns" to 3,
                    "rtaFftOrder" to 12,
                    "rtaDashboardShowBars" to true
                )
            )
        val p = store.load()
        assertEquals(-120.0, p.floorDb, 0.0)
        assertEquals(0.0, p.smoothing, 0.0)
        assertEquals(3, p.barColumns)
        assertEquals(12, p.fftOrder)
        assertEquals(RtaDisplayMode.BOTH, rtaInitialMode(p.dashboardShowGraph, p.dashboardShowBars))
        assertEquals(RtaDisplayMode.CURVES, rtaInitialMode(RtaPreferences().dashboardShowGraph, RtaPreferences().dashboardShowBars))
        assertEquals(RtaDisplayMode.BARS, rtaInitialMode(false, true))
        store.putString(RtaSettingsKeys.DASHBOARD_SOURCE, "out:0,2")
        assertEquals(RtaChannelSelection.of(out, listOf(0, 2)), RtaChannelSelection.fromStorageKey(store.string("rtaDashboardSource")))
    }

    @Test
    fun statusTexts() {
        val one = testRtaSnapshot(out, listOf(0))
        assertEquals("1 channel, each refreshed every 25 ms", rtaRefreshDescription(one, 10))
        val four = testRtaSnapshot(out, listOf(0, 1, 2, 3)).copy(refreshIntervalS = 0f, framesPerS = 40)
        assertEquals("4 channels, each refreshed every 100 ms", rtaRefreshDescription(four, 10))
        assertEquals("idle", rtaRefreshDescription(one.copy(running = false), 10))
        assertEquals("idle", rtaRefreshDescription(null, 10))
        assertEquals("bass 0.80%", rtaBassLoadDescription(8000))
        assertEquals("bass ≥6.55%", rtaBassLoadDescription(65535))
        assertEquals("main loop 4.2%", rtaMainLoopDescription(42_000))
        assertEquals(1024 / 48000.0, rtaChannelRefreshInterval(null, 10), 1e-12)
    }

    @Test
    fun lowestMeasurableBand() {
        val caps = testRtaCaps()
        val s = testRtaSnapshot(out, listOf(0))
        assertNull(rtaLowestMeasurableCentreHz(s, caps))
        assertEquals(25.0, rtaLowestMeasurableCentreHz(s.copy(firstBand = 3), caps)!!, 0.0)
        assertNull(rtaLowestMeasurableCentreHz(s.copy(firstBand = 0xFF), caps))
    }

    @Test
    fun frameLookupByChannelAtTheTap() {
        val s = testRtaSnapshot(out, listOf(2, 5))
        assertEquals(5, rtaFrame(s, out, 5)!!.channel)
        assertNull(rtaFrame(s, inp, 2))
        assertNull(rtaFrame(s, out, 7))
    }

    @Test
    fun barLabelsPlaceDecadesFirstAndNeverOverlap() {
        val caps = testRtaCaps()
        val shown = IntArray(33) { it }
        val wide = rtaPlaceBandLabels(caps.centres, shown, 1000f, 5f)
        assertEquals(listOf("20", "50", "100", "200", "500", "1k", "2k", "5k", "10k", "20k").sorted(), wide.map { it.text }.sorted())
        val narrow = rtaPlaceBandLabels(caps.centres, shown, 120f, 5f)
        assertTrue(narrow.map { it.text }.containsAll(listOf("100", "1k")))
        val sorted = narrow.sortedBy { it.x }
        for (i in 1 until sorted.size) assertTrue(sorted[i].x - sorted[i - 1].x > 5f)
        assertEquals(1, rtaBarColumns(1, 2))
        assertEquals(2, rtaBarColumns(5, 2))
        assertEquals(4, rtaBarColumns(9, 7))
    }

    @Test
    fun curveColumnsPassThroughBandPointsAndHandOverToBins() {
        val caps = testRtaCaps()
        val nb = 33
        val sm = RtaBarSmoother()
        val levels = FloatArray(2 * nb) { if (it < nb) -40f else -30f }
        sm.setTarget(levels, 2 * nb, 1)
        val cols = RtaCurveColumns()
        val w = 400f
        cols.compute(sm, nb, false, (1L shl nb) - 1, caps, w, 15.0, 20000.0, null, 0, 0)
        assertEquals(201, cols.count)
        // A flat spectrum stays flat between the band points
        val c1k = Math.round(cols.xForFreq(1000.0, w, 15.0, 20000.0) / RtaCurveColumns.COLUMN_STEP)
        assertEquals(-40f, cols.columns[c1k], 1e-3f)
        // A band point beyond the edge keeps the curve running off the plot
        assertEquals(-40f, cols.columns[0], 1e-3f)
        // Peaks read the second half
        cols.compute(sm, nb, true, (1L shl nb) - 1, caps, w, 15.0, 20000.0, null, 0, 0)
        assertEquals(-30f, cols.columns[c1k], 1e-3f)
        // Unpopulated bands are skipped: only bands 10..20 drawn
        val mid = ((1L shl 21) - 1) and ((1L shl 10) - 1).inv()
        cols.compute(sm, nb, false, mid, caps, w, 15.0, 20000.0, null, 0, 0)
        assertTrue(cols.columns[Math.round(cols.xForFreq(50.0, w, 15.0, 20000.0) / 2f)].isNaN())
        // Bins well above the bass bank take over completely
        val bins = FloatArray(512) { -70f }
        cols.compute(sm, nb, false, (1L shl nb) - 1, caps, w, 15.0, 20000.0, bins, 512, 48000)
        val c5k = Math.round(cols.xForFreq(5000.0, w, 15.0, 20000.0) / 2f)
        assertEquals(-70f, cols.columns[c5k], 1e-3f)
        // ...and the bass stays on the bands
        val c20 = Math.round(cols.xForFreq(20.0, w, 15.0, 20000.0) / 2f)
        assertEquals(-40f, cols.columns[c20], 1e-3f)
    }

    @Test
    fun renderStateTakesFramesAtItsTapOnly() {
        val caps = testRtaCaps()
        val snap = testRtaSnapshot(out, listOf(1, 3))
        val r = RtaRenderState()
        r.take(snap, caps, out, intArrayOf(1, 3), wantsBins = false, fallTau = 0.0)
        assertEquals(33, r.bandCount(0))
        assertEquals(snap.frames[1].avgDb[5], r.levels(1)[5], 0f)
        assertEquals(snap.frames[1].peakDb[5], r.levels(1)[33 + 5], 0f)
        val shown = IntArray(37)
        assertEquals(33, r.shownBands(33, shown))
        // The other tap's snapshot draws nothing
        r.take(snap, caps, inp, intArrayOf(1, 3), wantsBins = false, fallTau = 0.0)
        assertEquals(0, r.bandCount(0))
        assertEquals(0, r.shownBands(33, shown))
    }

    @Test
    fun renderStateGlidesWhenSmoothing() {
        val caps = testRtaCaps()
        val r = RtaRenderState()
        val a = testRtaSnapshot(out, listOf(0))
        r.take(a, caps, out, intArrayOf(0), false, 0.1)
        val b = a.copy(frames = a.frames.map { f -> f.copy(avgDb = f.avgDb.map { it + 20 }) })
        r.take(b, caps, out, intArrayOf(0), false, 0.1)
        assertTrue(r.step(0L))
        val v = r.levels(0)[10]
        assertTrue(v > a.frames[0].avgDb[10] && v < b.frames[0].avgDb[10])
    }
}
