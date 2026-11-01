package se.bofr.dspi.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import se.bofr.dspi.bridge.RtaController
import se.bofr.dspi.core.RtaCapsInfo
import se.bofr.dspi.core.RtaSnapshot
import se.bofr.dspi.ui.settings.AppSettings
import se.bofr.dspi.ui.settings.RtaOptions

@OptIn(ExperimentalCoroutinesApi::class)
class RtaOptionsBindingTest {
    private class FakeRta : RtaController {
        val capsFlow = MutableStateFlow<RtaCapsInfo?>(null)
        override val caps: StateFlow<RtaCapsInfo?> = capsFlow
        override val snapshot: StateFlow<RtaSnapshot?> = MutableStateFlow(null)
        var writes = 0
        override var fftOrder = 0
            set(v) {
                writes++
                field = v
            }
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

    @Test
    fun bindingPushesAtStartOnChangeOnLateCapsAndIntoANewBridge() = runTest {
        val settings = MutableStateFlow(AppSettings())
        val a = FakeRta()
        val b = FakeRta()
        val current = MutableStateFlow<RtaController>(a)
        val job = bindRtaControllers(CoroutineScope(UnconfinedTestDispatcher(testScheduler)), settings, current)
        assertEquals("pushed at start", listOf(10, 300, 12), listOf(a.fftOrder, a.avgMs, a.peakDecay))
        assertEquals(1, a.writes)

        settings.value = settings.value.copy(showPhase = true)
        assertEquals("an unrelated preference pushes nothing", 1, a.writes)

        settings.value = settings.value.copy(rtaFftOrder = 9)
        assertEquals(9, a.fftOrder)
        assertEquals(2, a.writes)

        settings.value = settings.value.copy(rtaFftOrder = 11)
        assertEquals("protocol range without caps", 10, a.fftOrder)
        a.capsFlow.value = caps(8, 11)
        assertEquals("caps arriving later widen the range", 11, a.fftOrder)

        current.value = b
        assertEquals("the new bridge gets the options at once", listOf(10, 300, 12), listOf(b.fftOrder, b.avgMs, b.peakDecay))
        val aWrites = a.writes
        settings.value = settings.value.copy(rtaAvgMs = 200)
        assertEquals("only the current bridge follows", aWrites, a.writes)
        assertEquals(200, b.avgMs)
        job.cancel()
    }
}
