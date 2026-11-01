package se.bofr.dspi.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import se.bofr.dspi.bridge.RtaController
import se.bofr.dspi.core.RtaCapsInfo
import se.bofr.dspi.core.RtaSnapshot
import se.bofr.dspi.ui.settings.AppSettings
import se.bofr.dspi.ui.settings.RtaOptions

class RtaOptionsBindingTest {
    private class FakeRta : RtaController {
        override val caps: StateFlow<RtaCapsInfo?> = MutableStateFlow(null)
        override val snapshot: StateFlow<RtaSnapshot?> = MutableStateFlow(null)
        override var fftOrder = 0
        override var avgMs = 0
        override var peakDecay = 0

        override fun update(id: Int, tap: Int, mask: Int, wantsBins: Boolean) = Unit

        override fun release(id: Int) = Unit

        override fun appChannel(tap: Int, index: Int) = 0

        override fun tapOf(appChannel: Int) = -1

        override fun tapIndex(appChannel: Int) = -1

        override fun availableChannels(tap: Int) = emptyList<Int>()
    }

    private fun caps(min: Int, max: Int, supported: Boolean = true) = RtaCapsInfo(
        supported = supported, inputChannels = 2, outputChannels = 9, orderMin = min, orderMax = max, orderDefault = 10,
        bassBands = 0, maxBands = 0, dynamicRangeDb = 0, bassDynamicRangeDb = 0, centres = emptyList()
    )

    @Test
    fun optionsUseTheDevicesRangeOnlyWhenItIsUsable() {
        val s = AppSettings(rtaFftOrder = 12, rtaAvgMs = 70_000, rtaPeakDecayDBs = 300)
        assertEquals(RtaOptions(10, 0xFFFF, 0xFF), rtaOptionsFor(s, null))
        assertEquals(RtaOptions(11, 0xFFFF, 0xFF), rtaOptionsFor(s, caps(8, 11)))
        assertEquals(RtaOptions(10, 0xFFFF, 0xFF), rtaOptionsFor(s, caps(11, 9)))
        assertEquals(RtaOptions(10, 0xFFFF, 0xFF), rtaOptionsFor(s, caps(8, 11, supported = false)))
    }

    @Test
    fun applyWritesAllThree() {
        val rta = FakeRta()
        rta.apply(RtaOptions(9, 250, 20))
        assertEquals(listOf(9, 250, 20), listOf(rta.fftOrder, rta.avgMs, rta.peakDecay))
    }
}
