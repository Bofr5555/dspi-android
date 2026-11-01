package se.bofr.dspi.feature.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.core.AdatOutStatus
import se.bofr.dspi.core.InputLockStatus
import se.bofr.dspi.core.LgStatus
import se.bofr.dspi.core.SpdifRxStatus

class StatsModelTest {
    @Test
    fun systemInformationRows() {
        assertEquals("150.0 MHz", SystemInfoFormat.clock(150_000_000))
        assertEquals("1.10 V", SystemInfoFormat.coreVoltage(1100))
        assertEquals("48.0 kHz", SystemInfoFormat.sampleRate(48_000))
        assertEquals("31.2 °C", SystemInfoFormat.temperature(3125))
        assertEquals("-5.0 °C", SystemInfoFormat.temperature(0xFFFF_FE0CL)) // sign-extended
        assertEquals("-5.0 °C", SystemInfoFormat.temperature(0xFE0CL)) // i16 bits only
        assertEquals("-", SystemInfoFormat.clock(null))
    }

    @Test
    fun elapsedAsMac() {
        assertEquals("0m 00s", statsFormatElapsed(0))
        assertEquals("1m 05s", statsFormatElapsed(65))
        assertEquals("1h 02m 03s", statsFormatElapsed(3723))
        assertEquals("0m 00s", statsFormatElapsed(-4))
    }

    @Test
    fun thresholds() {
        assertEquals(FillHealth.RED, BufferFillThresholds.SPDIF.health(0))
        assertEquals(FillHealth.RED, BufferFillThresholds.SPDIF.health(100))
        assertEquals(FillHealth.YELLOW, BufferFillThresholds.SPDIF.health(24))
        assertEquals(FillHealth.GREEN, BufferFillThresholds.SPDIF.health(50))
        assertEquals(FillHealth.YELLOW, BufferFillThresholds.PDM_DMA.health(4))
        assertEquals(FillHealth.GREEN, BufferFillThresholds.PDM_DMA.health(20))
        assertEquals(FillHealth.RED, BufferFillThresholds.PDM_DMA.health(51))
        assertEquals(FillHealth.GREEN, BufferFillThresholds.PDM_RING.health(20))
        assertEquals(FillHealth.YELLOW, BufferFillThresholds.PDM_RING.health(21))
    }

    @Test
    fun historyKeepsTheNewest256AndMarksAbsentBuffers() {
        val h = BufferFillHistory()
        assertTrue(h.value(0, 0).isNaN())
        repeat(300) { h.append(testBufferStats(it)) }
        assertEquals(300, h.total)
        assertTrue(h.value(0, 10).isNaN()) // aged out
        assertEquals(testBufferStats(299).spdif[0].fillPct.toFloat(), h.value(0, 299), 0f)
        h.append(testBufferStats(0).copy(numSpdif = 1, pdmActive = false))
        assertTrue(h.value(1, 300).isNaN())
        assertTrue(h.value(BufferFillHistory.PDM_DMA_SERIES, 300).isNaN())
        assertEquals(50f, h.value(BufferFillHistory.PDM_DMA_SERIES, 299), 0f)
        h.clear()
        assertEquals(0, h.total)
    }

    @Test
    fun starvationDeltaAndEventTimes() {
        val t = StarvationTracker()
        t.update(5, 1000)
        assertEquals(0, t.delta) // first read sets the baseline
        assertNull(t.lastEventMs)
        t.update(7, 3000)
        assertEquals(2, t.delta)
        assertEquals(3000L, t.lastEventMs)
        assertNull(t.previousEventMs)
        t.update(7, 5000)
        assertEquals(0, t.delta)
        t.update(8, 9000)
        assertEquals(9000L, t.lastEventMs)
        assertEquals(3000L, t.previousEventMs)
        // 32-bit wrap
        t.update(0xFFFF_FFFFL, 10_000)
        t.update(1, 11_000)
        assertEquals(2, t.delta)
        t.reset()
        assertEquals(0, t.total)
        assertNull(t.lastEventMs)
    }

    @Test
    fun spdifInputRowsAsMac() {
        val locked = SpdifRxStatus(2, 4, 3, 1, 44_100, 7, 52, 2, 3, 1)
        assertEquals("Locked", spdifRxStateString(2))
        assertEquals(StateDot.ORANGE, spdifRxStateDot(3))
        assertEquals(StateDot.GRAY, spdifRxStateDot(0))
        assertEquals("S/PDIF 2", spdifRxSourceString(locked.inputSource))
        assertEquals("S/PDIF 1", spdifRxSourceString(1))
        assertEquals("I2S", spdifRxSourceString(2))
        assertEquals("USB", spdifRxSourceString(0))
        assertEquals("44.1 kHz", spdifRxSampleRateString(locked))
        assertEquals("-", spdifRxSampleRateString(locked.copy(state = 1)))
        assertEquals("Waiting Stable", spdifRxLibStateString(1))
        assertEquals("Unknown (9)", spdifRxLibStateString(9))
    }

    @Test
    fun rowsAndInterfaceStrings() {
        assertEquals(2, starvationInstanceCount(0))
        assertEquals(4, starvationInstanceCount(4))
        assertEquals("Out 3/4", starvationInstanceLabel(1))
        assertEquals("Out 5/6 (I2S)", bufferSlotTitle(2, 1))
        assertEquals("Out 1/2 (SPDIF)", bufferSlotTitle(0, 0))
        assertEquals("44–56%", watermarkText(44, 56))

        assertEquals("Disabled", adatStateString(AdatOutStatus(false, false, 20, true, 0, 0)))
        assertEquals("Streaming", adatStateString(AdatOutStatus(true, true, 20, true, 0, 0)))
        assertEquals("Suspended (rate above 48 kHz)", adatStateString(AdatOutStatus(true, false, 20, false, 0, 0)))
        assertEquals(StateDot.YELLOW, adatStateDot(AdatOutStatus(true, false, 20, true, 0, 0)))

        val locked = InputLockStatus(3, 1, 48_000, 47_998, true, 1, 0)
        assertEquals("Locked", i2sSlaveStateString(3))
        assertEquals(StateDot.ORANGE, i2sSlaveStateDot(2))
        assertEquals("48.0 kHz", i2sDetectedRateString(locked))
        assertEquals("-", i2sDetectedRateString(locked.copy(state = 1)))
        assertEquals("-", i2sMeasuredRateString(locked.copy(measuredHz = 0)))

        assertEquals("-", lgVolumeString(LgStatus(true, false, 0xFF, false)))
        assertEquals("23 / 100", lgVolumeString(LgStatus(true, true, 23, false)))
    }
}
