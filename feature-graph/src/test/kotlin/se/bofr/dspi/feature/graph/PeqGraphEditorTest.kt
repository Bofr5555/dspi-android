// Translation of DSPi-Console-Windows:DSPiConsole.Core.Tests/PeqGraphEditorTests.cs
// @ v1.1.6-beta4 (itself ported from DSPi-Console-b5:DSPi ConsoleTests/
// PeqGraphEditorTests.swift). One JUnit test per xUnit [Fact], same names in
// camelCase, same fixtures and tolerances. The Mac's Command is CTRL here and
// its Option is ALT (see PeqMods).
package se.bofr.dspi.feature.graph

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.core.DspiConstants.FILTER_ALLPASS
import se.bofr.dspi.core.DspiConstants.FILTER_ALLPASS1
import se.bofr.dspi.core.DspiConstants.FILTER_FLAT
import se.bofr.dspi.core.DspiConstants.FILTER_HIGHPASS
import se.bofr.dspi.core.DspiConstants.FILTER_HIGHPASS1
import se.bofr.dspi.core.DspiConstants.FILTER_HIGHSHELF
import se.bofr.dspi.core.DspiConstants.FILTER_HIGHSHELF1
import se.bofr.dspi.core.DspiConstants.FILTER_LINKWITZ_TRANSFORM
import se.bofr.dspi.core.DspiConstants.FILTER_LOWPASS
import se.bofr.dspi.core.DspiConstants.FILTER_LOWPASS1
import se.bofr.dspi.core.DspiConstants.FILTER_LOWSHELF
import se.bofr.dspi.core.DspiConstants.FILTER_LOWSHELF1
import se.bofr.dspi.core.DspiConstants.FILTER_NOTCH
import se.bofr.dspi.core.DspiConstants.FILTER_PEAKING
import se.bofr.dspi.core.DspiConstants.FILTER_XOVER_FIRST
import se.bofr.dspi.core.FilterParams

class PeqGraphEditorTest {
    // ── Fixtures ────────────────────────────────────────────────────────────

    private fun band(t: Int, f: Float, q: Float, g: Float) = FilterTypes.band(t, f, q, g)

    private val hardBands =
        listOf(
            band(FILTER_PEAKING, 10f, 20f, 24f),
            band(FILTER_PEAKING, 12f, 20f, -24f),
            band(FILTER_PEAKING, 25f, 8f, 12f),
            band(FILTER_PEAKING, 1000f, 1f, 6f),
            band(FILTER_PEAKING, 18000f, 5f, -12f),
            band(FILTER_LOWSHELF, 30f, 0.707f, 15f),
            band(FILTER_HIGHSHELF, 8000f, 1.2f, -9f),
            band(FILTER_LOWSHELF1, 200f, 0.707f, 6f),
            band(FILTER_HIGHSHELF1, 3000f, 0.707f, -6f),
            band(FILTER_HIGHPASS, 15f, 10f, 0f),
            band(FILTER_HIGHPASS, 80f, 0.5f, 0f),
            band(FILTER_LOWPASS, 12000f, 2f, 0f),
            band(FILTER_HIGHPASS1, 40f, 0.707f, 0f),
            band(FILTER_LOWPASS1, 5000f, 0.707f, 0f),
            band(FILTER_ALLPASS, 500f, 3f, 0f),
            band(FILTER_ALLPASS1, 500f, 0.707f, 0f),
            band(FILTER_NOTCH, 60f, 4f, 0f)
        )

    private class Rig {
        val host = RecordingHost()
        val clock = FakeClock()
        lateinit var view: PeqGraphEditor
        val g = PeqGraphGeometry(800.0, 300.0, 20.0, 20000.0, 25.0, -25.0)
        lateinit var config: PeqEditorConfig

        fun click(p: PeqPoint, mods: Int = PeqMods.NONE) {
            view.pointerPressed(p, mods, 1)
            view.pointerReleased(p)
        }

        fun doubleClick(p: PeqPoint) {
            click(p)
            view.pointerPressed(p, PeqMods.NONE, 2)
            view.pointerReleased(p)
        }

        fun drag(a: PeqPoint, b: PeqPoint, mods: Int = PeqMods.NONE, steps: Int = 8) {
            view.pointerPressed(a, mods, 1)
            for (i in 1..steps) {
                val t = i.toDouble() / steps
                view.pointerDragged(PeqPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t), mods)
            }
            view.pointerReleased(b)
        }

        fun at(freq: Double, db: Double) = PeqPoint(g.x(freq), g.y(db))

        fun apply(change: (PeqEditorConfig) -> PeqEditorConfig) {
            config = change(config)
            view.apply(config)
        }
    }

    private fun empty(n: Int = 10): List<FilterParams> = List(n) { FilterTypes.off() }

    private fun makeRig(bands: List<FilterParams>? = null): Rig {
        val rig = Rig()
        rig.view = PeqGraphEditor(rig.host, rig.clock)
        rig.view.resize(800.0, 300.0)
        rig.config =
            PeqEditorConfig(
                channel = 0,
                bands = bands ?: empty(),
                minFreq = 20.0,
                maxFreq = 20000.0,
                availableTypes = FilterTypes.PEQ_TYPES.filter { it != FILTER_FLAT }.toSet(),
                bypassSupported = true
            )
        rig.view.apply(rig.config)
        return rig
    }

    private fun with(vararg set: Pair<Int, FilterParams>): List<FilterParams> {
        val bands = empty().toMutableList()
        for ((i, p) in set) bands[i] = p
        return bands
    }

    private fun near(expected: Double, actual: Double, tolerance: Double, why: String? = null) =
        assertTrue("${why ?: "value"}: expected $expected ± $tolerance, got $actual", abs(expected - actual) <= tolerance)

    private fun near(expected: Double, actual: Float, tolerance: Double, why: String? = null) =
        near(expected, actual.toDouble(), tolerance, why)

    private fun Set<Int>.ordered() = sorted()

    // ── Geometry and dots ───────────────────────────────────────────────────

    @Test
    fun geometryRoundTripsAndMatchesTheGraph() {
        val g = PeqGraphGeometry(900.0, 250.0, 15.0, 20000.0, 25.0, -25.0)
        near(0.0, g.x(15.0), 1e-6)
        near(900.0, g.x(20000.0), 1e-6)
        near(0.0, g.y(25.0), 1e-6)
        near(125.0, g.y(0.0), 1e-6)
        for (f in listOf(20.0, 440.0, 1000.0, 12345.0)) near(f, g.freq(g.x(f)), f * 1e-9)
        for (db in listOf(-20.0, 0.0, 7.5)) near(db, g.db(g.y(db)), 1e-9)
    }

    @Test
    fun dotsSitOnTheirBandsOwnCurve() {
        for (b in hardBands) {
            if (PeqShape.of(b.filterType) == null) continue
            val dot = PeqNodeRole.of(b).dbFor(b)
            if (b.filterType == FILTER_NOTCH || b.filterType == FILTER_ALLPASS || b.filterType == FILTER_ALLPASS1) {
                near(0.0, dot, 1e-9, "${b.filterType} sits on 0 dB")
            } else {
                near(PeqDspMath.responseAt(b.freq, listOf(b)).toDouble(), dot, 0.05, "${b.filterType} dot on its curve")
            }
        }
        val shelf = PeqNodeRole.of(band(FILTER_LOWSHELF, 100f, 0.707f, 6f))
        assertEquals(PeqNodeRole.Kind.GAIN, shelf.kind)
        near(0.5, shelf.scale, 0.001, "an RBJ shelf is half its gain at the corner")
        assertEquals(PeqNodeRole.Kind.RESONANCE, PeqNodeRole.of(band(FILTER_LOWPASS, 100f, 2f, 0f)).kind)
    }

    @Test
    fun doubleClickAlwaysMakesABell() {
        val g = PeqGraphGeometry(1000.0, 300.0, 20.0, 20000.0, 25.0, -25.0)
        val all = FilterTypes.PEQ_TYPES.toSet()
        for ((x, y) in listOf(20.0 to 150.0, 980.0 to 150.0, 500.0 to 290.0, 500.0 to 60.0)) {
            val b = PeqCreation.band(x, y, g, all, fromCurve = false)
            assertEquals(FILTER_PEAKING, b.filterType)
            near(g.freq(x), b.freq, 0.01)
            near(g.db(y), b.gain, 0.01)
            assertEquals(1f, b.q)
        }

        fun curveType(x: Double) = PeqCreation.band(x, 150.0, g, all, fromCurve = true).filterType
        assertEquals(FILTER_LOWSHELF, curveType(60.0))
        assertEquals(FILTER_HIGHSHELF, curveType(940.0))
        assertEquals(FILTER_PEAKING, curveType(500.0))
    }

    @Test
    fun shapeMappingRoundTrips() {
        for (shape in PeqShape.ALL) {
            for (order in listOf(1, 2)) {
                val t = shape.type(order) ?: continue
                assertEquals(shape, PeqShape.of(t)!!.first)
                assertEquals(order, PeqShape.of(t)!!.second)
            }
        }
        assertNull(PeqShape.of(FILTER_LINKWITZ_TRANSFORM))
        // Lr4Lp: Linkwitz-Riley 4th-order low-pass (crossover pair 1, LP).
        assertNull(PeqShape.of(FILTER_XOVER_FIRST + 2))
    }

    @Test
    fun valueEntryAcceptsFabFilterShortcuts() {
        near(2000.0, PeqValueText.parseFrequency("2k")!!, 1e-9)
        near(1500.0, PeqValueText.parseFrequency("1.5 kHz")!!, 1e-9)
        near(100.0, PeqValueText.parseFrequency("100hz")!!, 1e-9)
        near(440.0, PeqValueText.parseFrequency("A4")!!, 1e-9)
        near(261.6256, PeqValueText.parseFrequency("C4")!!, 1e-3)
        near(233.0819, PeqValueText.parseFrequency("Bb3")!!, 1e-3)
        near(69.2957 * 2.0.pow(13.0 / 1200), PeqValueText.parseFrequency("C#2+13")!!, 1e-3)
        assertNull(PeqValueText.parseFrequency("loud"))
        assertEquals(3.5, PeqValueText.parseNumber("+3.5 dB", "db"))
        assertEquals(2.0, PeqValueText.parseNumber("Q 2", ""))
    }

    @Test
    fun chipValuesTruncateToTwoDecimals() {
        assertEquals("4.99", PeqValueText.truncated(4.999))
        assertEquals("1.10", PeqValueText.truncated(1.1f.toDouble()))
        assertEquals("-6.50", PeqValueText.truncated(-6.506, sign = true))
        assertEquals("+5.00", PeqValueText.truncated(5.0, sign = true))
        assertEquals("+0.00", PeqValueText.truncated(-0.004, sign = true))
        assertEquals("1.79", PeqValueText.truncated(1799.99 / 1000))
    }

    // ── Band list ───────────────────────────────────────────────────────────

    /** A band number in the list takes the graph's click modifiers. */
    @Test
    fun listNumberClicksSelectLikeDots() {
        val selection = PeqGraphSelection()
        val active = { i: Int -> i != 4 }
        selection.listClick(2, command = false, shift = false, isActive = active)
        assertEquals(listOf(2), selection.selected.ordered())
        selection.listClick(6, command = false, shift = true, isActive = active)
        assertEquals(listOf(2, 3, 5, 6), selection.selected.ordered())
        selection.listClick(0, command = false, shift = true, isActive = active)
        assertEquals(listOf(0, 1, 2), selection.selected.ordered())
        selection.listClick(8, command = true, shift = false, isActive = active)
        assertEquals(listOf(0, 1, 2, 8), selection.selected.ordered())
        selection.listClick(1, command = true, shift = false, isActive = active)
        assertEquals(listOf(0, 2, 8), selection.selected.ordered())
        assertEquals(listOf(0, 2, 8), selection.madeByList!!.ordered())

        selection.setSelected(listOf(7))
        selection.listClick(9, command = false, shift = true, isActive = active)
        assertEquals(listOf(7, 8, 9), selection.selected.ordered())
        selection.setSelected(listOf(1, 5))
        selection.listClick(9, command = false, shift = true, isActive = active)
        assertEquals(listOf(9), selection.selected.ordered())
        selection.listClick(3, command = false, shift = false, isActive = active)
        assertEquals(listOf(3), selection.selected.ordered())
    }

    // ── Editor interaction ──────────────────────────────────────────────────

    @Test
    fun singleClickOnEmptyGraphCreatesNothing() {
        val rig = makeRig()
        rig.click(PeqPoint(400.0, 90.0))
        assertTrue(rig.host.commits.isEmpty())
    }

    @Test
    fun doubleClickOnEmptyGraphCreatesTheBandUnderThePointer() {
        val rig = makeRig()
        val p = PeqPoint(400.0, 90.0)
        rig.doubleClick(p)
        assertEquals(1, rig.host.commits.size)
        val created = rig.host.commits[0][0]
        assertEquals(0, created.first)
        assertEquals(FILTER_PEAKING, created.second.filterType)
        near(rig.g.freq(p.x), created.second.freq, 0.01)
        near(rig.g.db(p.y), created.second.gain, 0.01)
        assertEquals(listOf(0), rig.host.selection.selected.ordered())
    }

    @Test
    fun doubleClickWithEveryBandInUseBeeps() {
        val bands = List(10) { i -> band(FILTER_PEAKING, 100f * (i + 1), 1f, 3f) }
        val rig = makeRig(bands)
        var beeps = 0
        rig.view.onBeep = { beeps++ }
        rig.doubleClick(rig.at(15000.0, -20.0))
        assertTrue(rig.host.commits.isEmpty())
        assertEquals(1, beeps)
        rig.view.pointerMoved(rig.at(15000.0, -20.0))
        assertEquals("All 10 bands in use", rig.view.axisLabel)
        assertNull(rig.view.gainLabel)
    }

    @Test
    fun clickOnEmptyGraphDeselectsWhenBandsAreSelected() {
        val rig = makeRig(with(3 to band(FILTER_PEAKING, 1000f, 1f, 6f)))
        rig.click(rig.at(1000.0, 6.0))
        assertEquals(listOf(3), rig.host.selection.selected.ordered())
        rig.click(PeqPoint(150.0, 60.0))
        assertTrue(rig.host.selection.selected.isEmpty())
        assertTrue(rig.host.commits.isEmpty())
    }

    /** A drag reaches the device while it happens and the model exactly once, on release. */
    @Test
    fun dragCommitsOnceOnRelease() {
        val rig = makeRig(with(2 to band(FILTER_PEAKING, 1000f, 1.5f, 3f)))
        val start = rig.at(1000.0, 3.0)
        val end = rig.at(2000.0, 9.0)
        rig.view.pointerPressed(start, PeqMods.NONE, 1)
        for (i in 1..10) {
            val t = i / 10.0
            rig.view.pointerDragged(PeqPoint(start.x + (end.x - start.x) * t, start.y + (end.y - start.y) * t), PeqMods.NONE)
            rig.clock.advance(0.016)
        }
        assertTrue(rig.host.commits.isEmpty())
        assertTrue("the device follows the drag", rig.host.sends > 0)
        rig.view.pointerReleased(end)
        assertEquals(1, rig.host.commits.size)
        val moved = rig.host.commits[0][0]
        assertEquals(2, moved.first)
        near(2000.0, moved.second.freq, 2.0)
        near(9.0, moved.second.gain, 0.05)
        assertEquals(1.5f, moved.second.q)
    }

    /** Scrolling Q in the middle of a drag must survive further movement. */
    @Test
    fun wheelQDuringDragSurvivesMovement() {
        val rig = makeRig(with(0 to band(FILTER_PEAKING, 1000f, 1.5f, 3f)))
        val dot = rig.at(1000.0, 3.0)
        rig.view.pointerPressed(dot, PeqMods.NONE, 1)
        rig.view.pointerDragged(PeqPoint(dot.x + 10, dot.y), PeqMods.NONE)
        assertTrue(rig.view.wheel(dot, 100.0, PeqMods.NONE))
        rig.view.pointerDragged(PeqPoint(dot.x + 30, dot.y - 10), PeqMods.NONE)
        rig.view.pointerReleased(PeqPoint(dot.x + 30, dot.y - 10))
        val p = rig.host.lastCommitted
        assertTrue("the scrolled Q is kept after the drag moves on", p.q > 1.6)
        assertTrue("and the drag still moved the band", p.freq > 1000)
    }

    /** Ctrl-wheel gain moves the dot away from the pointer; the gesture must stay with its band. */
    @Test
    fun ctrlWheelGainStaysWithItsBand() {
        val rig = makeRig(with(0 to band(FILTER_PEAKING, 1000f, 4f, 3f)))
        val dot = rig.at(1000.0, 3.0)
        repeat(18) { assertTrue(rig.view.wheel(dot, -10.0, PeqMods.CTRL)) }
        rig.clock.advance(0.3)
        repeat(12) { assertTrue(rig.view.wheel(dot, 10.0, PeqMods.CTRL)) }
        rig.clock.advance(0.7)
        near(0.0, rig.host.lastCommitted.gain, 0.01, "down 9 dB then up 6 dB, all on the one band")
    }

    /** The chip follows its dot; a graph Ctrl-wheel gesture keeps adjusting gain when a chip field slides under. */
    @Test
    fun graphWheelGestureIgnoresChipFieldSlidingUnderPointer() {
        val rig = makeRig(with(0 to band(FILTER_PEAKING, 1000f, 2f, 6f)))
        val dot = rig.at(1000.0, 6.0)
        rig.view.pointerMoved(dot)
        assertTrue(rig.view.wheel(dot, -10.0, PeqMods.CTRL))
        val onChip = PeqPoint(rig.view.hudFrame.x + 60, rig.view.hudFrame.maxY - 8)
        assertTrue(rig.view.graphWheelActive)
        assertTrue(rig.view.wheel(onChip, -10.0, PeqMods.CTRL))
        rig.clock.advance(0.7)
        val p = rig.host.lastCommitted
        assertEquals(2f, p.q)
        assertTrue("both steps went to the gain", p.gain < 5.6)
    }

    /** A pause longer than a gesture ends the graph's hold, but Ctrl-wheel over the chip is still the band's gain. */
    @Test
    fun ctrlWheelOnChipAfterPauseAdjustsGain() {
        val rig = makeRig(with(0 to band(FILTER_PEAKING, 1000f, 2f, 6f)))
        val dot = rig.at(1000.0, 6.0)
        rig.view.pointerMoved(dot)
        assertTrue(rig.view.wheel(dot, -10.0, PeqMods.CTRL))
        rig.clock.advance(0.7)
        assertTrue(rig.view.hudVisible)
        val onChip = PeqPoint(rig.view.hudFrame.x + 60, rig.view.hudFrame.maxY - 8)
        assertTrue(rig.view.wheel(onChip, -10.0, PeqMods.CTRL))
        assertTrue(rig.view.wheel(onChip, -10.0, PeqMods.CTRL))
        rig.clock.advance(0.7)
        val p = rig.host.lastCommitted
        assertEquals(2f, p.q)
        assertEquals(1000f, p.freq)
        near(4.5, p.gain, 0.01, "all three steps went to the gain")
    }

    /** An explicit selection owns the wheel. */
    @Test
    fun selectionOwnsTheWheel() {
        val rig = makeRig(with(0 to band(FILTER_PEAKING, 1000f, 2f, 6f), 1 to band(FILTER_PEAKING, 4000f, 1f, 6f)))
        val selectedDot = rig.at(1000.0, 6.0)
        val otherDot = rig.at(4000.0, 6.0)
        val otherArea = rig.at(4000.0, 3.0)
        rig.click(selectedDot)
        assertEquals(listOf(0), rig.host.selection.selected.ordered())

        assertTrue(rig.view.wheel(otherDot, -10.0, PeqMods.CTRL, begins = true))
        assertTrue(rig.view.wheel(otherArea, 100.0, PeqMods.NONE, begins = true))
        assertFalse("empty graph still zooms or scrolls", rig.view.wheel(rig.at(200.0, -15.0), 10.0, PeqMods.CTRL, begins = true))
        rig.clock.advance(1.1)
        var p = rig.host.latest()
        near(5.5, p.getValue(0).gain, 0.01, "the gain step went to the selection")
        near(4.0, p.getValue(0).q, 0.01, "and so did the Q step")
        assertFalse("the band under the pointer was not touched", p.containsKey(1))
        assertEquals(0, rig.view.hudBand)

        // The selected band's own chip fields still take the wheel.
        rig.view.hudScroll(PeqHudField.FREQ, 100.0, fine = false)
        rig.clock.advance(0.7)
        assertTrue(rig.host.latest().getValue(0).freq > 1000)

        // With nothing selected the wheel adjusts the band under the pointer.
        rig.click(rig.at(200.0, -15.0))
        assertTrue(rig.host.selection.selected.isEmpty())
        assertTrue(rig.view.wheel(otherDot, -10.0, PeqMods.CTRL, begins = true))
        rig.clock.advance(0.7)
        p = rig.host.latest()
        near(5.5, p.getValue(1).gain, 0.01)
        near(5.5, p.getValue(0).gain, 0.01, "the earlier band is unchanged")
    }

    /** With a selection the chip stays on it, wherever the pointer goes; with none it follows the hovered dot. */
    @Test
    fun chipStaysOnTheSelection() {
        val rig = makeRig(with(0 to band(FILTER_PEAKING, 1000f, 2f, 6f), 1 to band(FILTER_PEAKING, 4000f, 1f, 6f)))
        val dot0 = rig.at(1000.0, 6.0)
        val dot1 = rig.at(4000.0, 6.0)
        val empty = rig.at(200.0, -15.0)
        rig.click(dot0)
        rig.view.pointerMoved(dot1)
        assertEquals(0, rig.view.hudBand)
        assertEquals(1, rig.host.selection.graphHovered)
        rig.view.pointerMoved(empty)
        rig.clock.advance(0.6)
        assertEquals(0, rig.view.hudBand)

        rig.host.selection.setSelected(listOf(1))
        assertEquals(1, rig.view.hudBand)

        rig.click(empty)
        assertTrue(rig.host.selection.selected.isEmpty())
        assertNull(rig.view.hudBand)
        rig.clock.advance(0.6)
        assertNull(rig.view.hudBand)
        rig.view.pointerMoved(dot1)
        assertEquals(1, rig.view.hudBand)
    }

    @Test
    fun chipHidesShortlyAfterLeavingADotAndComesBack() {
        val rig = makeRig(with(0 to band(FILTER_PEAKING, 1000f, 2f, 6f), 1 to band(FILTER_PEAKING, 4000f, 1f, 6f)))
        rig.view.pointerMoved(rig.at(1000.0, 6.0))
        assertTrue(rig.view.hudVisible)
        rig.view.pointerMoved(rig.at(200.0, -15.0))
        rig.clock.advance(0.2)
        assertTrue("a grace period lets the pointer reach the chip", rig.view.hudVisible)
        rig.clock.advance(0.3)
        assertFalse(rig.view.hudVisible)
        rig.view.pointerMoved(rig.at(4000.0, 6.0))
        assertEquals(1, rig.view.hudBand)
        assertTrue(rig.view.hudVisible)
        rig.view.pointerMoved(rig.at(200.0, -15.0))
        rig.clock.advance(0.42)
        assertTrue(rig.view.wheel(rig.at(1000.0, 6.0), 10.0, PeqMods.NONE, begins = true))
        assertEquals(0, rig.view.hudBand)
        assertTrue("wheeling a band shows its chip", rig.view.hudVisible)
    }

    /** A band's fill highlights it and takes the wheel, but clicks go through to the graph. */
    @Test
    fun clicksPassThroughBandFills() {
        val rig = makeRig(with(0 to band(FILTER_PEAKING, 1000f, 0.5f, 10f)))
        val dot = rig.at(1000.0, 10.0)
        val fill = rig.at(400.0, 3.0)

        rig.view.pointerMoved(fill)
        assertEquals(0, rig.host.selection.graphHovered)
        assertNotNull(rig.view.axisLabel)

        rig.click(dot)
        assertEquals(listOf(0), rig.host.selection.selected.ordered())
        rig.click(fill)
        assertTrue(rig.host.selection.selected.isEmpty())

        rig.doubleClick(fill)
        val created = rig.host.commits.last()[0]
        assertEquals(1, created.first)
        assertEquals(FILTER_PEAKING, created.second.filterType)
        near(400.0, created.second.freq, 4.0)
        near(3.0, created.second.gain, 0.2)
    }

    /** With the channel's gain shown on the graph, the dots, fills and pointer placement follow it. */
    @Test
    fun bandsFollowTheLevelOffset() {
        val rig = makeRig(with(0 to band(FILTER_PEAKING, 1000f, 0.5f, 3f)))
        rig.apply { it.copy(offsetDb = -6f) }

        // The +3 dB bell's dot sits at -3 dB on the axis, on the shifted curve.
        rig.view.pointerMoved(rig.at(1000.0, -3.0))
        assertEquals(0, rig.host.selection.graphHovered)
        rig.view.pointerMoved(PeqPoint(5.0, 5.0))
        rig.view.pointerMoved(rig.at(1000.0, 3.0))
        assertNull(rig.host.selection.graphHovered)

        // Its fill lies between -6 dB (its baseline now) and the curve.
        rig.view.pointerMoved(rig.at(700.0, -4.5))
        assertEquals(0, rig.host.selection.graphHovered)

        // Dragging the dot moves it by what the pointer moved.
        rig.drag(rig.at(1000.0, -3.0), rig.at(1000.0, 1.0))
        near(7.0, rig.host.lastCommitted.gain, 0.1, "dragged 4 dB up from +3")

        // A bell placed at -4 dB on the axis is a +2 dB bell, its dot under the pointer.
        rig.doubleClick(rig.at(100.0, -4.0))
        val created = rig.host.commits.last()[0]
        assertEquals(1, created.first)
        near(2.0, created.second.gain, 0.05)
    }

    /** Ctrl-click on empty graph opens the shape card there. */
    @Test
    fun ctrlClickCardCreatesShapeThenSlope() {
        val rig = makeRig()
        val chooser = PeqShapeChooserState()
        chooser.configure(rig.view.availableTypes)

        fun pick(shape: PeqShape, order: Int? = null) {
            var picked = chooser.pickShape(shape)
            if (picked == null && order != null) picked = chooser.pickOrder(order)
            if (picked != null) {
                rig.view.cardPick(picked.first, picked.second)
                chooser.reset()
            }
        }

        val at = rig.at(300.0, 4.0)
        rig.click(at, PeqMods.CTRL)
        assertTrue(rig.view.cardOpen)
        assertTrue(rig.host.commits.isEmpty())
        pick(PeqShape.BELL)
        assertFalse(rig.view.cardOpen)
        var created = rig.host.commits.last()[0]
        assertEquals(listOf(created.first), rig.host.selection.selected.ordered())
        assertEquals(created.first, rig.view.hudBand)
        assertTrue(rig.view.hudVisible)
        assertEquals(FILTER_PEAKING, created.second.filterType)
        near(rig.g.freq(at.x), created.second.freq, 0.5)
        near(4.0, created.second.gain, 0.05)

        // A shelf asks for its slope first, with neither marked.
        val shelfAt = rig.at(120.0, -6.0)
        rig.click(shelfAt, PeqMods.CTRL)
        val count = rig.host.commits.size
        assertNull(chooser.pickShape(PeqShape.LOW_SHELF))
        assertEquals(count, rig.host.commits.size)
        assertFalse(chooser.isOrderMarked(1))
        val pk = chooser.pickOrder(1)!!
        rig.view.cardPick(pk.first, pk.second)
        chooser.reset()
        created = rig.host.commits.last()[0]
        assertEquals(FILTER_LOWSHELF1, created.second.filterType)
        near(rig.g.freq(shelfAt.x), created.second.freq, 0.5)
        near(-6.0, created.second.gain, 0.05)
        assertFalse(rig.view.cardOpen)

        // A cut at 12 dB.
        rig.click(rig.at(8000.0, 0.0), PeqMods.CTRL)
        pick(PeqShape.HIGH_CUT, 2)
        assertEquals(FILTER_LOWPASS, rig.host.commits.last()[0].second.filterType)

        // Every way out cancels without making anything.
        val before = rig.host.commits.size
        rig.click(rig.at(1000.0, 0.0), PeqMods.CTRL)
        assertNull(chooser.pickShape(PeqShape.LOW_CUT))
        assertFalse("back returns to the shapes", chooser.back())
        assertNull(chooser.pendingShape)
        assertTrue(chooser.back())
        rig.view.cardCancel()
        assertFalse(rig.view.cardOpen)
        rig.click(rig.at(1000.0, 0.0), PeqMods.CTRL)
        rig.view.keyDown(PeqKey.ESCAPE, PeqMods.NONE)
        assertFalse("Escape cancels", rig.view.cardOpen)
        rig.click(rig.at(1000.0, 0.0), PeqMods.CTRL)
        assertNull(rig.view.rightPressed(rig.at(2000.0, -20.0)))
        assertFalse("right-click only closes the card", rig.view.cardOpen)
        rig.click(rig.at(1000.0, 0.0), PeqMods.CTRL)
        rig.click(rig.at(2000.0, -20.0))
        assertFalse("a click away dismisses it", rig.view.cardOpen)
        assertEquals(before, rig.host.commits.size)
    }

    /** The card and the chip of the bell it makes sit on the same side of the point. */
    @Test
    fun ctrlClickCardAndNewChipShareASide() {
        val rig = makeRig()

        fun side(f: PeqRect, p: PeqPoint): String {
            if (f.maxY <= p.y) return "north"
            if (f.minY >= p.y) return "south"
            return if (f.minX >= p.x) "east" else "west"
        }
        for ((freq, db, expected) in listOf(
            Triple(100.0, 4.0, "north"),
            Triple(300.0, 22.0, "east"),
            Triple(1000.0, -3.0, "south"),
            Triple(3000.0, -22.0, "east")
        )) {
            val at = rig.at(freq, db)
            rig.click(at, PeqMods.CTRL)
            val card =
                PeqRect(
                    rig.view.cardOrigin.x,
                    rig.view.cardOrigin.y,
                    PeqGraphEditor.Tuning.CARD_SIZE.width,
                    PeqGraphEditor.Tuning.CARD_SIZE.height
                )
            val cardSide = side(card, at)
            rig.view.cardPick(PeqShape.BELL, 2)
            val chipSide = side(rig.view.hudFrame, at)
            assertEquals(expected, cardSide)
            assertEquals(cardSide, chipSide)
        }
    }

    /** The chip's shape button turns it to a page the size of its values; nothing changes before the last click. */
    @Test
    fun chipShapePagePicksShapeThenSlope() {
        val rig = makeRig(with(0 to band(FILTER_LOWSHELF1, 200f, 0.707f, 6f)))
        rig.host.selection.setSelected(listOf(0))
        val values = rig.view.hudFrame
        rig.view.hudShapeButton()
        assertTrue(rig.view.showsShapes)
        assertEquals(values, rig.view.hudFrame)

        val chooser = PeqShapeChooserState()
        chooser.configure(rig.view.availableTypes)
        chooser.own = PeqShape.of(rig.view.hudParams!!.filterType)
        val count = rig.host.commits.size
        assertNull(chooser.pickShape(PeqShape.HIGH_SHELF))
        assertEquals(count, rig.host.commits.size)
        assertFalse("another shape's slope starts unselected", chooser.isOrderMarked(1))
        assertEquals(values, rig.view.hudFrame)
        chooser.back()
        chooser.pickShape(PeqShape.LOW_SHELF)
        assertTrue("the band's own 6 dB is shown", chooser.isOrderMarked(1))
        assertFalse(chooser.isOrderMarked(2))
        chooser.back()
        chooser.pickShape(PeqShape.HIGH_SHELF)
        val pk = chooser.pickOrder(2)!!
        rig.view.hudPick(pk.first, pk.second)
        assertEquals(FILTER_HIGHSHELF, rig.host.lastCommitted.filterType)
        assertEquals(count + 1, rig.host.commits.size)
        assertFalse(rig.view.showsShapes)

        // A bell has one order, so it applies straight away.
        rig.view.hudShapeButton()
        chooser.reset()
        val bell = chooser.pickShape(PeqShape.BELL)!!
        rig.view.hudPick(bell.first, bell.second)
        assertEquals(FILTER_PEAKING, rig.host.lastCommitted.filterType)
        assertEquals(0.707f, rig.host.lastCommitted.q) // a shelf already had a Q, so it carries over
        assertFalse(rig.view.showsShapes)

        // Escape abandons a choice half made.
        val before = rig.host.commits.size
        rig.view.hudShapeButton()
        rig.view.keyDown(PeqKey.ESCAPE, PeqMods.NONE)
        assertFalse(rig.view.showsShapes)
        assertEquals(before, rig.host.commits.size)
        assertEquals(listOf(0), rig.host.selection.selected.ordered())
    }

    @Test
    fun shapeChangeKeepsFrequencyZeroesGainAndSeedsQ() {
        val rig = makeRig(with(0 to band(FILTER_LOWSHELF1, 200f, 0.707f, 6f)))
        rig.host.selection.setSelected(listOf(0))
        rig.view.hudPick(PeqShape.NOTCH, 2)
        val p = rig.host.lastCommitted
        assertEquals(FILTER_NOTCH, p.filterType)
        assertEquals(200f, p.freq)
        assertEquals(0f, p.gain)
        assertEquals(1f, p.q)
    }

    /** Hovering empty graph reads out the pointer's frequency and level; each can be switched off. */
    @Test
    fun emptyGraphReadsOutFrequencyAndGain() {
        val rig = makeRig(with(0 to band(FILTER_PEAKING, 1000f, 2f, 6f)))
        rig.view.pointerMoved(rig.at(200.0, 4.0))
        assertNotNull(rig.view.axisLabel)
        assertEquals("+4.00 dB", rig.view.gainLabel)

        rig.view.pointerMoved(PeqPoint(2.0, rig.g.y(-3.0)))
        assertNotNull(rig.view.axisLabel)
        assertEquals("-3.00 dB", rig.view.gainLabel)

        rig.view.pointerMoved(rig.at(1000.0, 6.0))
        assertNull(rig.view.axisLabel)
        assertNull(rig.view.gainLabel)

        rig.apply { it.copy(showFrequencyReadout = false) }
        rig.view.pointerMoved(rig.at(200.0, 4.0))
        assertNull(rig.view.axisLabel)
        assertEquals("+4.00 dB", rig.view.gainLabel)
        rig.apply { it.copy(showFrequencyReadout = true, showLevelReadout = false) }
        rig.view.pointerMoved(rig.at(300.0, 4.0))
        assertNotNull(rig.view.axisLabel)
        assertNull(rig.view.gainLabel)
    }

    /** The list scrolls to a band only once the pointer rests on it, and not while a band is being wheeled. */
    @Test
    fun restingOnABandRevealsItsRow() {
        val rig = makeRig(with(0 to band(FILTER_PEAKING, 1000f, 2f, 6f), 1 to band(FILTER_PEAKING, 4000f, 1f, 6f)))
        val dot0 = rig.at(1000.0, 6.0)
        val dot1 = rig.at(4000.0, 6.0)
        rig.view.pointerMoved(dot0)
        rig.clock.advance(0.1)
        rig.view.pointerMoved(dot1)
        assertTrue(rig.host.revealed.isEmpty())
        rig.clock.advance(0.4)
        assertEquals(listOf(1), rig.host.revealed)

        assertTrue(rig.view.wheel(dot1, -10.0, PeqMods.CTRL, begins = true))
        rig.view.pointerMoved(dot0)
        rig.clock.advance(0.35)
        assertEquals(listOf(1), rig.host.revealed)
        rig.clock.advance(0.6)
        assertEquals(listOf(1, 0), rig.host.revealed)
    }

    /** Alt held on a dot: a drag locks to one axis, a click bypasses. */
    @Test
    fun altDragLocksToOneAxis() {
        val rig = makeRig(with(0 to band(FILTER_PEAKING, 1000f, 1f, 3f)))
        val dot = rig.at(1000.0, 3.0)
        rig.drag(dot, PeqPoint(dot.x + 60, dot.y + 12), PeqMods.ALT)
        val p = rig.host.lastCommitted
        assertTrue(p.freq > 1100)
        assertEquals(3f, p.gain)
        assertTrue(rig.host.bypasses.isEmpty())
    }

    /** Alt pressed part-way through a drag locks to the axis moved along so far, without a jump either way. */
    @Test
    fun altMidDragLocksWithoutAJump() {
        val rig = makeRig(with(0 to band(FILTER_PEAKING, 1000f, 1f, 0f)))
        val g = rig.g
        val dot = rig.at(1000.0, 0.0)
        rig.view.pointerPressed(dot, PeqMods.NONE, 1)
        val a = PeqPoint(dot.x + 60, g.y(2.0))
        rig.view.pointerDragged(PeqPoint(dot.x + 30, g.y(1.0)), PeqMods.NONE)
        rig.view.pointerDragged(a, PeqMods.NONE)
        rig.view.pointerDragged(PeqPoint(a.x + 20, g.y(8.0)), PeqMods.ALT)
        val b = PeqPoint(a.x + 40, g.y(-5.0))
        rig.view.pointerDragged(b, PeqMods.ALT)
        rig.view.pointerDragged(PeqPoint(b.x, b.y - (g.y(0.0) - g.y(3.0))), PeqMods.NONE)
        rig.view.pointerReleased(b)
        val p = rig.host.lastCommitted
        near(g.freq(dot.x + 100), p.freq, 2.0, "frequency followed all the sideways movement")
        near(5.0, p.gain, 0.05, "held at +2 dB while locked, then +3 dB more once free")
    }

    @Test
    fun shiftDragIsFine() {
        val rig = makeRig(with(0 to band(FILTER_PEAKING, 1000f, 1f, 0f)))
        val dot = rig.at(1000.0, 0.0)
        rig.drag(dot, PeqPoint(dot.x, rig.g.y(10.0)), PeqMods.SHIFT)
        near(10 * 0.12, rig.host.lastCommitted.gain, 0.15, "fine drag moves about an eighth as far")
    }

    /** Dragging one of several selected bands offsets every gain; Ctrl mid-drag scales them in proportion. */
    @Test
    fun selectionGainsOffsetAndCtrlMidDragScales() {
        val bands = with(0 to band(FILTER_PEAKING, 1000f, 1f, 6f), 1 to band(FILTER_PEAKING, 200f, 1f, -4f))

        fun gains(scale: Boolean): Pair<Float, Float> {
            val rig = makeRig(bands)
            rig.host.selection.setSelected(listOf(0, 1))
            val dot = rig.at(1000.0, 6.0)
            val up = PeqPoint(dot.x, rig.g.y(9.0))
            rig.view.pointerPressed(dot, PeqMods.NONE, 1)
            rig.view.pointerDragged(PeqPoint(dot.x, dot.y - 3), PeqMods.NONE)
            if (scale) rig.view.modifiersChanged(PeqMods.CTRL)
            for (i in 1..8) {
                rig.view.pointerDragged(
                    PeqPoint(dot.x, dot.y - 3 + (up.y - dot.y + 3) * i / 8.0),
                    if (scale) PeqMods.CTRL else PeqMods.NONE
                )
            }
            rig.view.pointerReleased(up)
            val last = rig.host.commits.last()
            return last.first { it.first == 0 }.second.gain to last.first { it.first == 1 }.second.gain
        }
        val offset = gains(false)
        near(9.0, offset.first, 0.1)
        near(-1.0, offset.second, 0.1, "the cut moves up by the same 3 dB")
        val scaled = gains(true)
        near(9.0, scaled.first, 0.1)
        near(-6.0, scaled.second, 0.1, "the cut deepens by the boost's 1.5 times")
    }

    @Test
    fun notchDragMovesFrequencyOnly() {
        val rig = makeRig(with(0 to band(FILTER_NOTCH, 1000f, 3f, 0f)))
        val dot = rig.at(1000.0, 0.0)
        rig.drag(dot, PeqPoint(dot.x + 40, dot.y - 50))
        val p = rig.host.lastCommitted
        assertTrue(p.freq > 1050)
        assertEquals(3f, p.q)
    }

    @Test
    fun wheelOnACutChangesQNotSlope() {
        val rig = makeRig(with(0 to band(FILTER_HIGHPASS, 100f, 0.707f, 0f)))
        val dot = rig.at(100.0, 20 * log10(0.707))
        repeat(5) { assertTrue(rig.view.wheel(dot, 20.0, PeqMods.NONE)) }
        rig.clock.advance(0.7)
        val p = rig.host.lastCommitted
        assertEquals(FILTER_HIGHPASS, p.filterType)
        assertTrue(p.q > 0.9)
    }

    @Test
    fun ctrlDragChangesOnlyQ() {
        val rig = makeRig(with(0 to band(FILTER_PEAKING, 500f, 1f, 4f)))
        val dot = rig.at(500.0, 4.0)
        rig.drag(dot, PeqPoint(dot.x + 40, dot.y - 60), PeqMods.CTRL)
        val p = rig.host.lastCommitted
        assertEquals(500f, p.freq)
        assertEquals(4f, p.gain)
        near(2.0, p.q, 0.01, "60 points up doubles Q")
    }

    @Test
    fun draggingASecondOrderCutSetsQFromItsHeight() {
        val rig = makeRig(with(0 to band(FILTER_HIGHPASS, 100f, 0.707f, 0f)))
        val dot = rig.at(100.0, 20 * log10(0.707))
        rig.drag(dot, PeqPoint(dot.x, rig.g.y(6.0)))
        near(10.0.pow(6.0 / 20), rig.host.lastCommitted.q, 0.02)
    }

    @Test
    fun multiSelectionMovesTogether() {
        val rig = makeRig(with(0 to band(FILTER_PEAKING, 200f, 1f, 4f), 1 to band(FILTER_PEAKING, 2000f, 1f, -2f)))
        rig.host.selection.setSelected(listOf(0, 1))
        rig.drag(rig.at(200.0, 4.0), rig.at(400.0, 8.0))
        val byBand = rig.host.commits.last().toMap()
        near(400.0, byBand.getValue(0).freq, 1.0)
        near(4000.0, byBand.getValue(1).freq, 4.0, "same frequency ratio")
        near(2.0, byBand.getValue(1).gain, 0.05, "gains move by the same 4 dB")
    }

    @Test
    fun altClickBypassesAndDeleteKeyRemoves() {
        val rig = makeRig(with(5 to band(FILTER_PEAKING, 3000f, 2f, -6f)))
        val dot = rig.at(3000.0, -6.0)
        rig.click(dot, PeqMods.ALT)
        assertEquals(5 to true, rig.host.bypasses.last())
        rig.click(dot)
        assertTrue(rig.view.keyDown(PeqKey.DELETE, PeqMods.NONE))
        val removed = rig.host.commits.last()[0]
        assertEquals(5, removed.first)
        assertEquals(FILTER_FLAT, removed.second.filterType)
        assertTrue(rig.host.selection.selected.isEmpty())
    }

    @Test
    fun altClickOnASelectionBypassesItAll() {
        val rig = makeRig(with(0 to band(FILTER_PEAKING, 200f, 1f, 4f), 1 to band(FILTER_PEAKING, 2000f, 1f, -2f)))
        rig.host.selection.setSelected(listOf(0, 1))
        rig.click(rig.at(2000.0, -2.0), PeqMods.ALT)
        assertEquals(listOf(0 to true, 1 to true), rig.host.bypasses.sortedBy { it.first })
    }

    @Test
    fun pullingTheCurveCreatesABandFromFlat() {
        val rig = makeRig()
        val onCurve = rig.at(800.0, 0.0)
        rig.drag(onCurve, PeqPoint(onCurve.x, rig.g.y(-7.0)))
        val created = rig.host.commits.last()[0]
        assertEquals(FILTER_PEAKING, created.second.filterType)
        near(-7.0, created.second.gain, 0.05)
        near(800.0, created.second.freq, 1.0)
    }

    @Test
    fun marqueeSelectsTheDotsInside() {
        val rig =
            makeRig(
                with(
                    0 to band(FILTER_PEAKING, 100f, 1f, 6f),
                    1 to band(FILTER_PEAKING, 300f, 1f, 6f),
                    2 to band(FILTER_PEAKING, 5000f, 1f, 6f)
                )
            )
        rig.drag(rig.at(70.0, 12.0), rig.at(500.0, 2.0))
        assertEquals(listOf(0, 1), rig.host.selection.selected.ordered())
        assertTrue(rig.host.commits.isEmpty())
    }

    @Test
    fun shiftClickSelectsAFrequencyRangeAndTabSteps() {
        // Rows out of frequency order: 0 = 4 kHz, 1 = 100 Hz, 2 = 1 kHz.
        val rig =
            makeRig(
                with(
                    0 to band(FILTER_PEAKING, 4000f, 1f, 3f),
                    1 to band(FILTER_PEAKING, 100f, 1f, 3f),
                    2 to band(FILTER_PEAKING, 1000f, 1f, 3f)
                )
            )
        rig.click(rig.at(100.0, 3.0))
        rig.click(rig.at(1000.0, 3.0), PeqMods.SHIFT)
        assertEquals(listOf(1, 2), rig.host.selection.selected.ordered())
        rig.click(rig.at(100.0, 3.0))
        rig.click(rig.at(4000.0, 3.0), PeqMods.SHIFT)
        assertEquals(listOf(0, 1, 2), rig.host.selection.selected.ordered())

        rig.click(rig.at(1000.0, 3.0))
        rig.view.keyDown(PeqKey.TAB, PeqMods.NONE)
        assertEquals(listOf(0), rig.host.selection.selected.ordered())
        rig.view.keyDown(PeqKey.TAB, PeqMods.NONE)
        assertEquals(listOf(1), rig.host.selection.selected.ordered())
        rig.view.keyDown(PeqKey.TAB, PeqMods.SHIFT)
        assertEquals(listOf(0), rig.host.selection.selected.ordered())
        rig.view.keyDown(PeqKey.A, PeqMods.CTRL)
        assertEquals(listOf(0, 1, 2), rig.host.selection.selected.ordered())
    }

    /** A band below the axis floor moves with a selection drag by ratio but is never pulled up onto the axis. */
    @Test
    fun selectionDragLeavesAnOffAxisBandWhereItIs() {
        val rig = makeRig(with(0 to band(FILTER_PEAKING, 12f, 1f, -6f), 1 to band(FILTER_PEAKING, 1000f, 1f, 0f)))
        rig.host.selection.setSelected(listOf(0, 1))
        rig.drag(rig.at(1000.0, 0.0), rig.at(1000.0, 6.0))
        val latest = rig.host.latest()
        near(1000.0, latest.getValue(1).freq, 1.0)
        latest[0]?.let { near(12.0, it.freq, 0.01, "the off-axis band keeps its frequency") }
    }

    @Test
    fun nudgeLeavesAnOffAxisBandWhereItIs() {
        val rig = makeRig(with(0 to band(FILTER_PEAKING, 12f, 1f, -6f)))
        rig.host.selection.setSelected(listOf(0))
        rig.view.keyDown(PeqKey.LEFT, PeqMods.NONE)
        rig.clock.advance(0.5)
        rig.host.latest()[0]?.let { near(12.0, it.freq, 0.01, "not pulled up to the 20 Hz floor") }
        rig.view.keyDown(PeqKey.RIGHT, PeqMods.NONE)
        rig.clock.advance(0.5)
        near(12 * 2.0.pow(1.0 / 12), rig.host.latest().getValue(0).freq, 0.01, "it can still move toward the axis")
    }

    /** A drag out and back released before the throttled return send still commits. */
    @Test
    fun dragBackToStartRestoresTheDevice() {
        val rig = makeRig(with(2 to band(FILTER_PEAKING, 1000f, 1.5f, 3f)))
        val start = rig.at(1000.0, 3.0)
        rig.view.pointerPressed(start, PeqMods.NONE, 1)
        rig.view.pointerDragged(PeqPoint(start.x + 40, start.y - 20), PeqMods.NONE)
        assertTrue("the device was sent the dragged value", rig.host.sends > 0)
        rig.view.pointerDragged(start, PeqMods.NONE)
        rig.view.pointerReleased(start)
        assertEquals(1, rig.host.commits.size)
        val back = rig.host.commits[0].single { it.first == 2 }.second
        near(1000.0, back.freq, 0.5)
        near(3.0, back.gain, 0.01)
    }

    /** Delete with the button still held removes the band for good. */
    @Test
    fun deleteMidDragSticks() {
        val rig = makeRig(with(2 to band(FILTER_PEAKING, 1000f, 1.5f, 3f)))
        val start = rig.at(1000.0, 3.0)
        rig.view.pointerPressed(start, PeqMods.NONE, 1)
        rig.view.pointerDragged(PeqPoint(start.x + 30, start.y), PeqMods.NONE)
        assertTrue(rig.view.keyDown(PeqKey.DELETE, PeqMods.NONE))
        rig.view.pointerDragged(PeqPoint(start.x + 60, start.y - 10), PeqMods.NONE)
        rig.view.pointerReleased(PeqPoint(start.x + 60, start.y - 10))
        rig.clock.advance(1.0)
        assertEquals(FILTER_FLAT, rig.host.latest().getValue(2).filterType)
    }

    @Test
    fun arrowKeysNudgeAndCommitAfterAPause() {
        val rig = makeRig(with(0 to band(FILTER_PEAKING, 1000f, 1f, 0f), 1 to band(FILTER_NOTCH, 500f, 2f, 0f)))
        rig.host.selection.setSelected(listOf(0, 1))
        rig.view.keyDown(PeqKey.RIGHT, PeqMods.NONE)
        rig.view.keyDown(PeqKey.UP, PeqMods.NONE)
        rig.view.keyDown(PeqKey.UP, PeqMods.SHIFT)
        assertTrue(rig.host.commits.isEmpty())
        rig.clock.advance(0.5)
        val p = rig.host.latest()
        near(1000 * 2.0.pow(1.0 / 12), p.getValue(0).freq, 0.5, "a semitone up")
        near(0.6, p.getValue(0).gain, 0.001, "0.5 dB then 0.1 dB")
        assertEquals(0f, p.getValue(1).gain)
        assertTrue("a notch has no gain, so Up raises Q", p.getValue(1).q > 2)
        rig.view.keyDown(PeqKey.DOWN, PeqMods.ALT)
        rig.clock.advance(0.5)
        assertTrue("Alt-Down lowers Q", rig.host.latest().getValue(0).q < 1)
    }

    @Test
    fun bandMenuActsOnTheSelection() {
        val shelf = band(FILTER_LOWSHELF, 200f, 0.707f, 4f)
        val rig = makeRig(with(0 to shelf, 1 to band(FILTER_PEAKING, 2000f, 1f, -2f)))
        rig.host.selection.setSelected(listOf(0, 1))
        val menu = rig.view.rightPressed(rig.at(200.0, PeqNodeRole.of(shelf).dbFor(shelf)))!!
        assertEquals("2 Bands", menu[0].title)
        assertTrue(menu.any { it.title == "Slope" && it.submenu!!.size == 2 })
        menu.first { it.title == "Invert Gain" }.invoke!!()
        val p = rig.host.latest()
        assertEquals(-4f, p.getValue(0).gain)
        assertEquals(2f, p.getValue(1).gain)
        menu.first { it.title == "Delete 2 Bands" }.invoke!!()
        assertEquals(FILTER_FLAT, rig.host.latest().getValue(0).filterType)
        assertEquals(FILTER_FLAT, rig.host.latest().getValue(1).filterType)

        val graphMenu = rig.view.rightPressed(rig.at(100.0, -20.0))!!
        assertEquals(listOf("Select All Bands", "Deselect All"), graphMenu.map { it.title })
        assertFalse("nothing to select", graphMenu[0].enabled)
    }

    @Test
    fun typedValuesParseOrBeep() {
        val rig = makeRig(with(0 to band(FILTER_PEAKING, 1000f, 1f, 0f)))
        rig.host.selection.setSelected(listOf(0))
        var beeps = 0
        rig.view.onBeep = { beeps++ }
        assertTrue(rig.view.hudText(PeqHudField.FREQ, "A4"))
        near(440.0, rig.host.lastCommitted.freq, 0.01)
        assertFalse(rig.view.hudText(PeqHudField.GAIN, "loud"))
        assertEquals(1, beeps)
        assertTrue(rig.view.hudText(PeqHudField.GAIN, "+40 dB"))
        assertEquals(30f, rig.host.lastCommitted.gain)
        assertTrue(rig.view.hudText(PeqHudField.Q, "q2.5"))
        assertEquals(2.5f, rig.host.lastCommitted.q)
    }

    @Test
    fun modelChangesElsewhereAnimateIntoPlace() {
        val rig = makeRig(with(0 to band(FILTER_PEAKING, 1000f, 1f, 0f)))
        rig.apply { it.copy(bands = with(0 to band(FILTER_PEAKING, 1000f, 1f, 12f))) }
        assertTrue(rig.view.animating)
        assertTrue(rig.view.frame(rig.clock.now + 0.05))
        val mid = rig.view.shownForTesting(0).gain
        assertTrue("mid-animation gain $mid", mid in 0.5f..11.5f)
        rig.view.frame(rig.clock.now + 0.3)
        assertEquals(12f, rig.view.shownForTesting(0).gain)
    }

    @Test
    fun linkwitzTransformCannotBeDragged() {
        val lt = FilterTypes.band(FILTER_LINKWITZ_TRANSFORM, 55f, 1.1f, 25f).copy(qp = 0.55f)
        val rig = makeRig(with(0 to lt))
        val dot = rig.view.nodePointForTesting(0)
        rig.drag(dot, PeqPoint(dot.x + 50, dot.y - 40))
        assertTrue(rig.host.commits.isEmpty())
    }

    @Test
    fun crossoverBandsAreNotGraphBands() {
        // Lr4Hp: Linkwitz-Riley 4th-order high-pass (crossover pair 1, HP).
        assertFalse(band(FILTER_XOVER_FIRST + 3, 80f, 0.707f, 0f).isGraphBand())
        assertFalse(FilterTypes.off().isGraphBand())
        assertTrue(band(FILTER_NOTCH, 80f, 2f, 0f).isGraphBand())
    }
}

/** The editor's clock, run by hand (Windows test `FakeClock`). */
internal class FakeClock : PeqScheduler {
    private class Timer(val due: Double, val action: () -> Unit) : PeqTimer {
        var cancelled = false

        override fun cancel() {
            cancelled = true
        }
    }

    private val timers = mutableListOf<Timer>()
    override var now: Double = 100.0
        private set

    override fun schedule(seconds: Double, action: () -> Unit): PeqTimer {
        val t = Timer(now + seconds, action)
        timers += t
        return t
    }

    fun advance(seconds: Double) {
        val end = now + seconds
        while (true) {
            val next = timers.filter { !it.cancelled && it.due <= end }.minByOrNull { it.due } ?: break
            timers.remove(next)
            now = maxOf(now, next.due)
            next.action()
        }
        now = end
    }
}

/** A host that records instead of talking to a device (Windows test `RecordingHost`). */
internal class RecordingHost : PeqGraphEditorHost {
    override val selection = PeqGraphSelection()
    val commits = mutableListOf<List<Pair<Int, FilterParams>>>()
    var sends = 0
        private set
    val bypasses = mutableListOf<Pair<Int, Boolean>>()
    val revealed = mutableListOf<Int>()

    init {
        selection.addRevealListener { revealed += it }
    }

    override fun commitGraphBands(channel: Int, changes: List<Pair<Int, FilterParams>>) {
        commits += changes.toList()
    }

    override fun sendGraphBandsToDevice(channel: Int, changes: List<Pair<Int, FilterParams>>) {
        sends++
    }

    override fun setGraphBandBypass(channel: Int, bands: List<Int>, bypass: Boolean) {
        for (b in bands) bypasses += b to bypass
    }

    override fun showLive(channel: Int, band: Int, p: FilterParams) {}

    override fun endLive(channel: Int) {}

    val lastCommitted: FilterParams get() = commits.last().first().second

    fun latest(): Map<Int, FilterParams> {
        val out = LinkedHashMap<Int, FilterParams>()
        for (commit in commits) for ((b, p) in commit) out[b] = p
        return out
    }
}
