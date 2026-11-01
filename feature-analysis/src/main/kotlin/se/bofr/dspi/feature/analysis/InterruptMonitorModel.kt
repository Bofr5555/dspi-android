// Port of DSPi-Console-b5:DSPi Console/InterruptMonitor.swift @ b5fdeac
// (InterruptMonitorView's toolbar texts, InterruptEvent.formattedLine's
// timestamp). The packet decoding of InterruptEvent (v1/v2,
// ParamOffsetDecoder) is the core's monitor.rs: lines arrive as text.
package se.bofr.dspi.feature.analysis

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import se.bofr.dspi.bridge.MonitorLine

/** The toolbar's state label and its colour (Swift: secondary / orange / green). */
enum class MonitorListening(val label: String) {
    INACTIVE("Inactive"),
    PAUSED("Paused"),
    LISTENING("Listening")
}

object InterruptMonitorFormat {
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    /** Swift: Inactive without a device, else Paused or Listening. */
    fun listening(connected: Boolean, paused: Boolean): MonitorListening = when {
        !connected -> MonitorListening.INACTIVE
        paused -> MonitorListening.PAUSED
        else -> MonitorListening.LISTENING
    }

    /** `N event(s)`. */
    fun eventCount(n: Int): String = "$n event${if (n == 1) "" else "s"}"

    fun pauseLabel(paused: Boolean): String = if (paused) "Resume" else "Pause"

    /** `HH:mm:ss.SSS` in [zone] (InterruptEvent.dateFormatter). */
    fun timestamp(timeMs: Long, zone: ZoneId = ZoneId.systemDefault()): String = TIME.withZone(zone).format(Instant.ofEpochMilli(timeMs))

    /** Swift `formattedLine`: timestamp, two spaces, the decoded event. */
    fun formattedLine(line: MonitorLine, zone: ZoneId = ZoneId.systemDefault()): String = timestamp(line.timeMs, zone) + "  " + line.text

    /** macOS: every append scrolls to the newest line unless paused. */
    fun shouldAutoScroll(paused: Boolean): Boolean = !paused
}
