package se.bofr.dspi.app

import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.bridge.DspiBridgeImpl
import se.bofr.dspi.bridge.LinkStore
import se.bofr.dspi.core.DspiCoreApi
import se.bofr.dspi.core.LinkDevice
import se.bofr.dspi.link.BridgeLinkCoreOps
import se.bofr.dspi.link.LinkHub
import se.bofr.dspi.link.LinkServiceControl
import se.bofr.dspi.link.LinkSessionEngine
import se.bofr.dspi.link.LinkSessionState
import se.bofr.dspi.link.LinkTokenStore

/** A running REMOTE stack (DSPi Link client): its own core, bridge and session engine. */
interface RemoteSession {
    val bridge: DspiBridge
    val state: StateFlow<LinkSessionState>

    fun connect(hub: LinkHub)

    fun submitPin(pin: String)

    fun cancelPin()

    fun chooseDevice(device: LinkDevice)

    fun retryNow()

    /** Leave the hub (the engine goes Idle; the app then leaves REMOTE). */
    fun disconnect()

    /** Tear the stack down (detach, close the session, stop the thread, free the core). */
    fun close()
}

/**
 * The REMOTE stack, built like the demo stack ([startDemoSession]): [core]
 * (the real FfiDspiCore in the app) behind its own [DspiBridgeImpl] on its own
 * FFI thread, so a USB unit and a remote unit are never mixed; the Link
 * session runs on the same core and thread ([BridgeLinkCoreOps]).
 */
fun startRemoteSession(
    parent: CoroutineScope,
    appVersion: String,
    core: DspiCoreApi,
    tokens: LinkTokenStore,
    clientName: () -> String,
    service: LinkServiceControl,
    linkStore: LinkStore,
    main: CoroutineDispatcher = Dispatchers.Main.immediate,
    log: (String) -> Unit = {}
): RemoteSession {
    val ffi: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dspi-link-ffi").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob(parent.coroutineContext.job) + main)
    val remoteBridge = DspiBridgeImpl(core, scope, ffi = ffi, main = main, linkStore = linkStore, appVersion = appVersion)
    val engine = LinkSessionEngine(BridgeLinkCoreOps(core, remoteBridge, ffi), tokens, clientName, appVersion, scope, service, log = log)
    log("remote mode on")
    return object : RemoteSession {
        override val bridge: DspiBridge = remoteBridge
        override val state: StateFlow<LinkSessionState> = engine.state

        override fun connect(hub: LinkHub) = engine.connect(hub)

        override fun submitPin(pin: String) = engine.submitPin(pin)

        override fun cancelPin() = engine.cancelPin()

        override fun chooseDevice(device: LinkDevice) = engine.chooseDevice(device)

        override fun retryNow() = engine.retryNow()

        override fun disconnect() = engine.disconnect()

        override fun close() {
            parent.launch(main) {
                runCatching { engine.close() }
                runCatching { remoteBridge.close() }
                scope.cancel()
                ffi.close()
                runCatching { core.close() }
                log("remote mode off")
            }
        }
    }
}
