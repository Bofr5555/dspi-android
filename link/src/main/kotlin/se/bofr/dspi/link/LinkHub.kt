// Port of DSPi-Console release/v1.1.6 `Link/HubBrowser.swift` `DiscoveredHub`
// (the fields the client uses) and the DNS-SD TXT keys of DSPi-Link spec 3.1.
package se.bofr.dspi.link

/** DNS-SD service type of a DSPi Link hub (spec 3.1). */
const val LINK_SERVICE_TYPE = "_dspi._tcp"

/** Default hub port: decimal of the USB VID 0x2E8B (spec 3.1). */
const val LINK_DEFAULT_PORT = 11915

const val LINK_DEFAULT_PATH = "/dspi/v1"

/** A hub found by DNS-SD or added by address (macOS `DiscoveredHub`). */
data class LinkHub(
    /** `hid` (lowercase UUID); `manual:host:port` for a typed address until its hello names it. */
    val id: String,
    val name: String,
    /** `console`, `bridge` or `unknown`. */
    val kind: String,
    /** `none` (open) or `pin` (private). */
    val auth: String,
    val deviceCount: Int,
    /** TXT `d`: serials of the shared devices, when the hub lists them. */
    val serials: List<String>,
    val host: String,
    val port: Int,
    val path: String = LINK_DEFAULT_PATH,
    val isManual: Boolean = false
) {
    /** "192.168.1.20" or "studio.local:8080" (the port only when it is not the default). */
    val displayAddress: String
        get() {
            val h = if (':' in host) "[$host]" else host
            return if (port == LINK_DEFAULT_PORT) h else "$h:$port"
        }

    /** Hubs that answer to `auth: none` (macOS badge "Open"; else "Private"). */
    val isOpen: Boolean get() = auth == "none"

    companion object {
        fun manualId(host: String, port: Int) = "manual:$host:$port"

        /** A typed address (already parsed); resolved to a real id on hello. */
        fun manual(host: String, port: Int) =
            LinkHub(manualId(host, port), host, "unknown", "pin", 0, emptyList(), host, port, isManual = true)
    }
}

/** TXT record of a browsed hub (spec 3.1). Unknown keys are ignored. */
object HubTxt {
    /**
     * [attrs] as Android's `NsdServiceInfo.attributes` gives them. Returns
     * null when a required key is missing or the major version is not 1.
     */
    fun parse(serviceName: String, attrs: Map<String, ByteArray?>, host: String, port: Int): LinkHub? {
        fun s(k: String) = attrs[k]?.toString(Charsets.US_ASCII)?.trim()
        val v = s("v") ?: return null
        if (v.toIntOrNull()?.let { it >= 1 } != true) return null
        val hid = s("hid")?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        // TLS (tls=1) is not implemented by any hub and not by this client
        if (s("tls") == "1") return null
        val serials = s("d")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        return LinkHub(
            id = hid,
            name = serviceName,
            kind = s("kind")?.takeIf { it.isNotEmpty() } ?: "unknown",
            auth = s("auth")?.takeIf { it == "none" || it == "pin" } ?: "pin",
            deviceCount = s("n")?.toIntOrNull() ?: serials.size,
            serials = serials,
            host = host,
            port = port,
            path = s("path")?.takeIf { it.startsWith("/") } ?: LINK_DEFAULT_PATH
        )
    }

    /** DNS-SD escapes characters in instance names as `\DDD`; NsdManager passes some through. */
    fun unescapeName(name: String): String = Regex("""\\(\d{3})""").replace(name) { m ->
        m.groupValues[1].toInt().toChar().toString()
    }
}
