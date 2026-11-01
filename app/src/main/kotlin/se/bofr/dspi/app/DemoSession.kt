package se.bofr.dspi.app

import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.bridge.DspiBridgeImpl
import se.bofr.dspi.bridge.InMemoryLinkStore
import se.bofr.dspi.fake.FakeDspiCore

/**
 * The demo stack: [FakeDspiCore.demo] (seeded from `/dspstate_v32.json`, FFI's
 * V32 capture on fake-core's classpath; animated analyser) behind its own
 * [DspiBridgeImpl] with a child scope of [parent], its own FFI thread and
 * in-memory links (demo links are not persisted). No USB is involved: the
 * fake reports a device, so `scanDevices()` selects it at once.
 * [DemoSession.close] closes the bridge (timers, controllers, core listeners),
 * cancels the scope, stops the thread and closes the fake.
 */
fun startDemoSession(
    parent: CoroutineScope,
    appVersion: String,
    main: CoroutineDispatcher = Dispatchers.Main.immediate,
    core: FakeDspiCore = FakeDspiCore.demo(),
    log: (String) -> Unit = {}
): DemoSession {
    val ffi: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dspi-demo-ffi").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob(parent.coroutineContext.job) + main)
    val demoBridge = DspiBridgeImpl(core, scope, ffi = ffi, main = main, linkStore = InMemoryLinkStore(), appVersion = appVersion)
    demoBridge.scanDevices()
    log("demo mode on (serial ${core.serial})")
    return object : DemoSession {
        override val bridge: DspiBridge = demoBridge

        override fun close() {
            // review A2: timers, the four controllers and the core's listeners first
            runCatching { demoBridge.close() }
            scope.cancel()
            ffi.close()
            runCatching { core.close() }
            log("demo mode off")
        }
    }
}
