package se.bofr.dspi.link

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.core.DspiCoreApi
import se.bofr.dspi.core.LinkDevice
import se.bofr.dspi.core.LinkHello
import se.bofr.dspi.core.LinkInfo
import se.bofr.dspi.core.LinkListener
import se.bofr.dspi.core.LinkStatus

/**
 * What [LinkSessionEngine] needs from the REMOTE stack (narrow, so tests can
 * fake it). Every suspend call may block on the network for seconds.
 */
interface LinkCoreOps {
    suspend fun connect(url: String, clientName: String, appVersion: String, token: String): LinkHello

    suspend fun pair(pin: String): String

    suspend fun devices(): List<LinkDevice>

    suspend fun status(): LinkStatus

    suspend fun fetchInfo(host: String, port: Int): LinkInfo

    /** Close the session (the bridge's device must be detached first). */
    suspend fun close()

    /** Bind the bridge to a device of the open session ([DspiBridge.attachRemote]). */
    suspend fun attach(handle: Long, serial: String): Boolean

    suspend fun detach(serial: String)

    fun wsUrl(host: String, port: Int, path: String): String

    fun setListener(listener: LinkListener?)

    /** The hub's role is `viewer` ([DspiBridge.setRemoteReadOnly]). */
    fun setReadOnly(readOnly: Boolean)
}

/**
 * [LinkCoreOps] on the REMOTE stack: [core] is the stack's own core, [bridge]
 * its bridge, [ffi] the bridge's serial FFI dispatcher (so session calls
 * queue with the bridge's own calls, as every core call does).
 */
class BridgeLinkCoreOps(private val core: DspiCoreApi, private val bridge: DspiBridge, private val ffi: CoroutineDispatcher) : LinkCoreOps {
    override suspend fun connect(url: String, clientName: String, appVersion: String, token: String): LinkHello =
        withContext(ffi) { core.linkConnect(url, clientName, appVersion, token) }

    override suspend fun pair(pin: String): String = withContext(ffi) { core.linkPair(pin) }

    override suspend fun devices(): List<LinkDevice> = withContext(ffi) { core.linkDevices() }

    override suspend fun status(): LinkStatus = withContext(ffi) { core.linkStatus() }

    override suspend fun fetchInfo(host: String, port: Int): LinkInfo = withContext(ffi) { core.linkFetchInfo(host, port) }

    override suspend fun close() = withContext(ffi) { core.linkClose() }

    override suspend fun attach(handle: Long, serial: String): Boolean = bridge.attachRemote(handle, serial)

    override suspend fun detach(serial: String) = bridge.detachDevice(serial)

    override fun wsUrl(host: String, port: Int, path: String): String = core.linkWsUrl(host, port, path)

    override fun setListener(listener: LinkListener?) = core.setLinkListener(listener)

    override fun setReadOnly(readOnly: Boolean) = bridge.setRemoteReadOnly(readOnly)
}
