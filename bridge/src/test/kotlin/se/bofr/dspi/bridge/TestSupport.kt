package se.bofr.dspi.bridge

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.fake.FakeDspiCore

/** A bridge over [core] whose main and FFI dispatchers and clock are the test scheduler's. */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.newBridge(core: FakeDspiCore = FakeDspiCore(), links: LinkStore = InMemoryLinkStore()): DspiBridgeImpl {
    val d = StandardTestDispatcher(testScheduler)
    return DspiBridgeImpl(core, backgroundScope, ffi = d, main = d, linkStore = links, clock = { testScheduler.currentTime })
}

/** Attach the fake as the USB layer would, and settle. */
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun TestScope.attach(b: DspiBridgeImpl, core: FakeDspiCore): Boolean {
    val ok = b.attachDevice(42, core.serial, DspiConstants.VENDOR_ID)
    runCurrent()
    return ok
}

/** Collects the bridge's events from now on. */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.collectEvents(b: DspiBridge): List<BridgeEvent> {
    val out = mutableListOf<BridgeEvent>()
    backgroundScope.launch(StandardTestDispatcher(testScheduler)) { b.events.toList(out) }
    runCurrent()
    return out
}
