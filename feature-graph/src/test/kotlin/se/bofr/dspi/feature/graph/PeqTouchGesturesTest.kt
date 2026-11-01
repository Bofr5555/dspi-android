package se.bofr.dspi.feature.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.core.DspiConstants.FILTER_FLAT
import se.bofr.dspi.core.DspiConstants.FILTER_PEAKING
import se.bofr.dspi.core.FilterParams

/** The touch mapping of PORT-MAP "Touch gestures" over the ported editor. */
class PeqTouchGesturesTest {
    private val g = PeqGraphGeometry(800.0, 300.0, 20.0, 20000.0, 25.0, -25.0)
    private val host = RecordingHost()
    private val clock = FakeClock()
    private val editor = PeqGraphEditor(host, clock)
    private val touch =
        PeqTouchGestures(editor, PeqTouchConfig(touchSlop = 8.0, doubleTapTimeoutMs = 300, doubleTapSlop = 32.0, longPressTimeoutMs = 400))
    private var menu: Pair<List<PeqMenuItem>, PeqPoint>? = null
    private var zoom = 1.0

    private fun setUp(vararg set: Pair<Int, FilterParams>) {
        val bands = MutableList(10) { FilterTypes.off() }
        for ((i, p) in set) bands[i] = p
        editor.hitRadius = PeqGraphEditor.Tuning.TOUCH_HIT_RADIUS
        editor.curveHitDistance = PeqGraphEditor.Tuning.TOUCH_CURVE_HIT_DISTANCE
        editor.resize(800.0, 300.0)
        editor.apply(
            PeqEditorConfig(
                channel = 0,
                bands = bands,
                minFreq = 20.0,
                maxFreq = 20000.0,
                availableTypes = FilterTypes.PEQ_TYPES.filter { it != FILTER_FLAT }.toSet(),
                bypassSupported = true
            )
        )
        touch.onMenu = { items, at -> menu = items to at }
        touch.onZoom = { zoom *= it }
    }

    private fun at(freq: Double, db: Double) = PeqPoint(g.x(freq), g.y(db))

    private fun tap(p: PeqPoint, t: Long) {
        touch.down(p, t)
        touch.up(p, t + 50)
    }

    @Test
    fun tapSelectsADotAndJitterIsNotADrag() {
        setUp(0 to FilterTypes.band(FILTER_PEAKING, 1000f, 1f, 6f))
        val dot = at(1000.0, 6.0)
        touch.down(dot, 0)
        touch.move(PeqPoint(dot.x + 5, dot.y - 4))
        touch.up(PeqPoint(dot.x + 5, dot.y - 4), 80)
        assertEquals(setOf(0), host.selection.selected)
        assertTrue("a tap is not an edit", host.commits.isEmpty())
        assertEquals(0, host.sends)
    }

    @Test
    fun aFingerNearADotHitsIt() {
        setUp(0 to FilterTypes.band(FILTER_PEAKING, 1000f, 1f, 6f))
        val dot = at(1000.0, 6.0)
        tap(PeqPoint(dot.x + 18, dot.y + 10), 0)
        assertEquals(setOf(0), host.selection.selected)
    }

    @Test
    fun doubleTapEmptyGraphAddsABell() {
        setUp()
        val p = at(500.0, 4.0)
        tap(p, 0)
        tap(p, 200)
        assertEquals(1, host.commits.size)
        val created = host.commits[0][0].second
        assertEquals(FILTER_PEAKING, created.filterType)
        assertEquals(500.0, created.freq.toDouble(), 3.0)
        assertEquals(4.0, created.gain.toDouble(), 0.1)
    }

    @Test
    fun slowSecondTapIsNotADoubleTap() {
        setUp()
        val p = at(500.0, 4.0)
        tap(p, 0)
        tap(p, 900)
        assertTrue(host.commits.isEmpty())
    }

    @Test
    fun doubleTapADotAsksToTypeTheFrequency() {
        setUp(0 to FilterTypes.band(FILTER_PEAKING, 1000f, 1f, 6f))
        var asked: PeqHudField? = null
        editor.onBeginHudEditRequested = { asked = it }
        val dot = at(1000.0, 6.0)
        tap(dot, 0)
        tap(dot, 150)
        assertEquals(PeqHudField.FREQ, asked)
        assertEquals(0, editor.hudBand)
    }

    @Test
    fun dragADotMovesItAndCommitsOnce() {
        setUp(0 to FilterTypes.band(FILTER_PEAKING, 1000f, 1f, 0f))
        val dot = at(1000.0, 0.0)
        val end = at(2000.0, 6.0)
        touch.down(dot, 0)
        for (i in 1..10) {
            touch.move(PeqPoint(dot.x + (end.x - dot.x) * i / 10, dot.y + (end.y - dot.y) * i / 10))
            clock.advance(0.016)
        }
        assertTrue(host.commits.isEmpty())
        assertTrue(host.sends > 0)
        touch.up(end, 300)
        assertEquals(1, host.commits.size)
        assertEquals(2000.0, host.lastCommitted.freq.toDouble(), 3.0)
        assertEquals(6.0, host.lastCommitted.gain.toDouble(), 0.1)
    }

    @Test
    fun longPressADotOpensTheBandMenu() {
        setUp(3 to FilterTypes.band(FILTER_PEAKING, 1000f, 1f, 6f))
        val id = touch.down(at(1000.0, 6.0), 0)
        touch.longPress(id)
        val m = menu
        assertNotNull(m)
        assertEquals("Band 4", m!!.first[0].title)
        assertTrue(m.first.any { it.title == "Delete Band" })
        touch.up(at(1000.0, 6.0), 600)
        assertTrue("the long press is not also a click or a drag", host.commits.isEmpty())
    }

    @Test
    fun longPressEmptyGraphOffersAddBand() {
        setUp()
        val p = at(300.0, 3.0)
        val id = touch.down(p, 0)
        touch.longPress(id)
        val items = menu!!.first
        assertEquals("Add Band…", items[0].title)
        assertTrue(items.any { it.title == "Select All Bands" })
        items[0].invoke!!()
        assertTrue(editor.cardOpen)
        editor.cardPick(PeqShape.BELL, 2)
        assertEquals(FILTER_PEAKING, host.lastCommitted.filterType)
        assertEquals(3.0, host.lastCommitted.gain.toDouble(), 0.1)
    }

    @Test
    fun aStaleLongPressTimerDoesNothing() {
        setUp(0 to FilterTypes.band(FILTER_PEAKING, 1000f, 1f, 6f))
        val id = touch.down(at(1000.0, 6.0), 0)
        touch.up(at(1000.0, 6.0), 100)
        touch.longPress(id)
        assertNull(menu)
        val id2 = touch.down(at(1000.0, 6.0), 1000)
        touch.move(at(1500.0, 6.0))
        touch.longPress(id2)
        assertNull("a drag is not a long press", menu)
    }

    @Test
    fun pinchOverABandChangesItsQ() {
        setUp(0 to FilterTypes.band(FILTER_PEAKING, 1000f, 1f, 6f))
        val dot = at(1000.0, 6.0)
        touch.down(dot, 0)
        touch.pinchStart(dot, 100.0)
        touch.pinch(dot, 200.0)
        touch.pinchEnd()
        touch.up(dot, 300)
        clock.advance(1.0)
        assertEquals("spreading the fingers to twice the span doubles Q", 2.0, host.lastCommitted.q.toDouble(), 0.01)
        assertEquals(1000f, host.lastCommitted.freq)
        assertEquals(1.0, zoom, 0.0)
    }

    @Test
    fun twoFingerPushUpRaisesQ() {
        setUp(0 to FilterTypes.band(FILTER_PEAKING, 1000f, 1f, 6f))
        val dot = at(1000.0, 6.0)
        touch.pinchStart(dot, 100.0)
        touch.pinch(PeqPoint(dot.x, dot.y - 60), 100.0)
        touch.pinchEnd()
        clock.advance(1.0)
        assertEquals("60 points up doubles Q, as a Cmd-drag", 2.0, host.lastCommitted.q.toDouble(), 0.01)
    }

    @Test
    fun pinchOverEmptyGraphZooms() {
        setUp(0 to FilterTypes.band(FILTER_PEAKING, 1000f, 4f, 6f))
        val empty = at(100.0, -20.0)
        touch.pinchStart(empty, 100.0)
        touch.pinch(empty, 150.0)
        touch.pinch(empty, 200.0)
        touch.pinchEnd()
        assertEquals(2.0, zoom, 1e-9)
        assertTrue(host.commits.isEmpty())
    }

    @Test
    fun dragEmptyGraphIsAMarquee() {
        setUp(0 to FilterTypes.band(FILTER_PEAKING, 100f, 1f, 6f), 1 to FilterTypes.band(FILTER_PEAKING, 300f, 1f, 6f))
        val a = at(70.0, 12.0)
        val b = at(500.0, 2.0)
        touch.down(a, 0)
        for (i in 1..8) touch.move(PeqPoint(a.x + (b.x - a.x) * i / 8, a.y + (b.y - a.y) * i / 8))
        touch.up(b, 400)
        assertEquals(setOf(0, 1), host.selection.selected)
    }

    @Test
    fun liftingTheFingerClearsTheHoverReadouts() {
        setUp()
        tap(at(300.0, 3.0), 0)
        assertNull(editor.axisLabel)
        assertNull(editor.gainLabel)
        assertFalse(touch.active)
    }
}
