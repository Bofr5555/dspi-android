package se.bofr.dspi.ui.link

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.StateFlow

/** One hub row of the Networking page (macOS NetworkingSettingsView `hubRow`). */
data class NetworkHubRow(
    val id: String,
    val name: String,
    /** "192.168.1.20, 1 device" (macOS: address, device count). */
    val detail: String,
    /** `console`, `bridge`, `unknown`. */
    val kind: String,
    /** macOS badge: "Open" (auth none) or "Private" (pin). */
    val open: Boolean,
    /** Added by address: can be removed. */
    val manual: Boolean,
    /** The hub this phone is connected to. */
    val active: Boolean
)

data class PairedHubRow(val id: String, val name: String)

/** What Settings › Networking shows (DSPi Link client, owner decisions D2/D7). */
data class NetworkingUiState(
    /** DSPi Link is opt-in: off by default. */
    val enabled: Boolean = false,
    val findAutomatically: Boolean = true,
    /** "" = [defaultClientName]. */
    val clientName: String = "",
    /** The phone's model (Build.MODEL). */
    val defaultClientName: String = "",
    val hubs: List<NetworkHubRow> = emptyList(),
    val browsing: Boolean = false,
    val browseError: String? = null,
    val paired: List<PairedHubRow> = emptyList(),
    /** One line on the current connection, "" when none. */
    val status: String = "",
    val connected: Boolean = false
)

/**
 * The Networking page's model, provided by `:app` (which owns the Link
 * engine) through [LocalNetworking]; null = no networking (previews, tests).
 */
interface NetworkingController {
    val state: StateFlow<NetworkingUiState>

    fun setEnabled(enabled: Boolean)

    fun setFindAutomatically(on: Boolean)

    fun setClientName(name: String)

    /** Connect to the hub (switches the app to the hub's DSPi). */
    fun connect(hubId: String)

    /** Leave the hub (back to USB). */
    fun disconnect()

    /** macOS `HubBrowser.parseAddress`: a host, `host:port` or `[v6]:port`. */
    fun isValidAddress(text: String): Boolean

    fun addManual(text: String): Boolean

    fun removeManual(hubId: String)

    /** Forget the pairing token of a hub. */
    fun forget(hubId: String)

    /** The page is on screen: look for hubs while it is (and "Find hubs automatically" is on). */
    fun setVisible(visible: Boolean)
}

val LocalNetworking = staticCompositionLocalOf<NetworkingController?> { null }
