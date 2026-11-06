// The client half of DSPi-Console release/v1.1.6 `NetworkingSettingsView.swift`
// (`lookForHubs`, the manual hubs) plus the Android decisions D2 (off by
// default) and D7 (client name = Build.MODEL, editable).
package se.bofr.dspi.link

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A hub added by address (`host`, `port` as parsed). */
data class ManualHub(val host: String, val port: Int)

data class LinkSettings(
    /** DSPi Link is opt-in (owner decision D2): nothing touches the network while false. */
    val enabled: Boolean = false,
    /** macOS "Find hubs automatically" (DNS-SD browsing). */
    val findAutomatically: Boolean = true,
    /** Shown in the hub's client list; blank = the device model (D7). */
    val clientName: String = "",
    val manualHubs: List<ManualHub> = emptyList()
)

interface LinkSettingsStore {
    val settings: StateFlow<LinkSettings>

    fun update(change: (LinkSettings) -> LinkSettings)
}

class InMemoryLinkSettingsStore(initial: LinkSettings = LinkSettings()) : LinkSettingsStore {
    private val flow = MutableStateFlow(initial)
    override val settings: StateFlow<LinkSettings> = flow.asStateFlow()

    override fun update(change: (LinkSettings) -> LinkSettings) {
        flow.value = change(flow.value)
    }
}

/** [LinkSettingsStore] on a private SharedPreferences file (read once, written on change). */
class PrefsLinkSettingsStore(context: Context) : LinkSettingsStore {
    private val prefs = context.applicationContext.getSharedPreferences("dspi_link_settings", Context.MODE_PRIVATE)
    private val flow = MutableStateFlow(read())
    override val settings: StateFlow<LinkSettings> = flow.asStateFlow()

    private fun read() = LinkSettings(
        enabled = prefs.getBoolean(K_ENABLED, false),
        findAutomatically = prefs.getBoolean(K_AUTO, true),
        clientName = prefs.getString(K_NAME, "").orEmpty(),
        manualHubs = decodeManual(prefs.getString(K_MANUAL, "").orEmpty())
    )

    @Synchronized
    override fun update(change: (LinkSettings) -> LinkSettings) {
        val next = change(flow.value)
        if (next == flow.value) return
        flow.value = next
        prefs.edit()
            .putBoolean(K_ENABLED, next.enabled)
            .putBoolean(K_AUTO, next.findAutomatically)
            .putString(K_NAME, next.clientName)
            .putString(K_MANUAL, encodeManual(next.manualHubs))
            .apply()
    }

    private companion object {
        const val K_ENABLED = "enabled"
        const val K_AUTO = "find_automatically"
        const val K_NAME = "client_name"
        const val K_MANUAL = "manual_hubs"
    }
}

/** One `port host` per line (hosts have no newline; IPv6 hosts contain ':'). */
fun encodeManual(hubs: List<ManualHub>): String = hubs.joinToString("\n") { "${it.port} ${it.host}" }

fun decodeManual(text: String): List<ManualHub> = text.lineSequence().mapNotNull { line ->
    val sp = line.indexOf(' ')
    if (sp <= 0) return@mapNotNull null
    val port = line.substring(0, sp).toIntOrNull()?.takeIf { it in 1..65535 } ?: return@mapNotNull null
    line.substring(sp + 1).takeIf { it.isNotBlank() }?.let { ManualHub(it, port) }
}.toList()
