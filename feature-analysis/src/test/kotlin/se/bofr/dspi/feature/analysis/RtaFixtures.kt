package se.bofr.dspi.feature.analysis

import kotlin.math.log10
import kotlin.math.sin
import se.bofr.dspi.core.BufferStats
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.core.PdmBufferStats
import se.bofr.dspi.core.RtaCapsInfo
import se.bofr.dspi.core.RtaChannelFrame
import se.bofr.dspi.core.RtaSnapshot
import se.bofr.dspi.core.SpdifBufferStats

/** Third-octave centres 12 Hz .. 20 kHz (33 bands), as the fake's table. */
val TEST_CENTRES =
    listOf(
        12, 16, 20, 25, 31, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800, 1000,
        1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000, 20000
    )

fun testRtaCaps(): RtaCapsInfo = RtaCapsInfo(
    supported = true,
    inputChannels = 2,
    outputChannels = 9,
    orderMin = 8,
    orderMax = 12,
    orderDefault = 10,
    bassBands = 6,
    maxBands = TEST_CENTRES.size,
    dynamicRangeDb = 100,
    bassDynamicRangeDb = 90,
    centres = TEST_CENTRES + List(DspiConstants.RTA_MAX_BANDS - TEST_CENTRES.size) { 0 }
)

/** A snapshot laid out as the core does: frames packed in channel order, every band populated. */
fun testRtaSnapshot(tap: Int, channels: List<Int>): RtaSnapshot {
    val nb = TEST_CENTRES.size
    val frames =
        List(DspiConstants.RTA_MAX_CHANNELS) { i ->
            val ch = channels.getOrNull(i)
            val avg =
                List(DspiConstants.RTA_MAX_BANDS) { b ->
                    if (ch == null || b >= nb) {
                        DspiConstants.RTA_FLOOR_DB
                    } else {
                        (-30.0 - 10 * log10(TEST_CENTRES[b] / 1000.0) + 3 * sin(b + ch * 1.0) - 6 * ch).toFloat()
                    }
                }
            RtaChannelFrame(ch ?: 0, if (ch == null) 0 else nb, ch == null, avg, avg.map { it + 4f })
        }
    return RtaSnapshot(
        version = 1,
        telemetryVersion = 1,
        tap = tap,
        count = channels.size,
        frames = frames,
        populated = (1L shl nb) - 1,
        binsCount = 0,
        binsChannel = 0,
        binsSampleRate = 48_000,
        binsDb = List(DspiConstants.RTA_MAX_BINS) { DspiConstants.RTA_FLOOR_DB },
        running = true,
        liveCount = channels.size,
        liveMask = channels.fold(0) { m, c -> m or (1 shl c) },
        framesPerS = 40,
        busyUsPerS = 42_000,
        lastFrameUs = 900,
        bassBusyUsPerS = 8_000,
        sampleRate = 48_000,
        firstBand = 0,
        fftOrder = 10,
        configRejected = false,
        refreshIntervalS = channels.size / 40f
    )
}

fun testBufferStats(seq: Int): BufferStats {
    val fill = 50 + (seq % 7) - 3
    return BufferStats(
        numSpdif = 4,
        pdmActive = true,
        streaming = true,
        sequence = seq,
        spdif = List(4) { SpdifBufferStats(2, 2, 4, fill, 44, 56) },
        pdm = PdmBufferStats(50, 45, 55, 50, 40, 60)
    )
}
