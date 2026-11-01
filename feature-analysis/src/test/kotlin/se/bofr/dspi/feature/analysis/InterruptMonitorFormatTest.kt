package se.bofr.dspi.feature.analysis

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.bridge.MonitorLine

class InterruptMonitorFormatTest {
    @Test
    fun toolbarTexts() {
        assertEquals(MonitorListening.INACTIVE, InterruptMonitorFormat.listening(connected = false, paused = true))
        assertEquals(MonitorListening.PAUSED, InterruptMonitorFormat.listening(connected = true, paused = true))
        assertEquals("Listening", InterruptMonitorFormat.listening(connected = true, paused = false).label)
        assertEquals("0 events", InterruptMonitorFormat.eventCount(0))
        assertEquals("1 event", InterruptMonitorFormat.eventCount(1))
        assertEquals("2000 events", InterruptMonitorFormat.eventCount(2000))
        assertEquals("Resume", InterruptMonitorFormat.pauseLabel(true))
        assertEquals("Pause", InterruptMonitorFormat.pauseLabel(false))
    }

    @Test
    fun linesCarryAMillisecondTimestamp() {
        val line = MonitorLine(7, 3_723_045L, "[  7] v2.PresetLoaded                slot=2")
        val expected = "01:02:03.045  [  7] v2.PresetLoaded                slot=2"
        assertEquals(expected, InterruptMonitorFormat.formattedLine(line, ZoneOffset.UTC))
        assertEquals("00:00:00.000", InterruptMonitorFormat.timestamp(0, ZoneOffset.UTC))
    }

    @Test
    fun followsUnlessPaused() {
        assertTrue(InterruptMonitorFormat.shouldAutoScroll(paused = false))
        assertFalse(InterruptMonitorFormat.shouldAutoScroll(paused = true))
    }

    @Test
    fun routesRoundTrip() {
        for (r in AnalysisRoute.entries) assertEquals(r, AnalysisRoute.fromRoute(r.route))
        assertEquals(AnalysisRoute.TEST_SIGNALS, AnalysisRoute.fromRoute("nope"))
    }
}
