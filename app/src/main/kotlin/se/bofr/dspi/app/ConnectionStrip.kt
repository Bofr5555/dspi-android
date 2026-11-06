package se.bofr.dspi.app

import se.bofr.dspi.core.DspiException
import se.bofr.dspi.link.LinkSessionState
import se.bofr.dspi.usb.UsbSessionState

/** What the strip under the top bar offers; pure so it is JVM-tested. */
sealed interface ConnectionStrip {
    val text: String

    /** Open device: the strip is hidden (HOME shows the connection and the compat banner). */
    data object Hidden : ConnectionStrip {
        override val text = ""
    }

    /**
     * No DSPi attached; [demoAvailable] adds the "Demo (no device)" action,
     * [networkAvailable] (DSPi Link on) "Connect over network".
     */
    data class NoDevice(val demoAvailable: Boolean, val networkAvailable: Boolean = false) : ConnectionStrip {
        override val text = "No DSPi connected. Plug one in over USB."
    }

    /** A DSPi is attached but may not be opened: offer the permission prompt. */
    data class NeedsPermission(override val text: String) : ConnectionStrip

    data class Info(override val text: String) : ConnectionStrip

    /** Demo mode is on: always visible, always labelled, with the way out. */
    data object Demo : ConnectionStrip {
        override val text = "Demo (no device): a simulated DSPi. Nothing is sent over USB."
    }

    /** REMOTE (DSPi Link): always visible, so a remote unit is never mistaken for the USB one. */
    data class Remote(override val text: String, val actions: List<RemoteAction>) : ConnectionStrip
}

enum class RemoteAction(val label: String) {
    RETRY("Retry now"),
    CONNECT("Connect"),
    DISCONNECT("Disconnect")
}

fun connectionStrip(
    mode: AppMode,
    state: UsbSessionState,
    demoAvailable: Boolean,
    remote: LinkSessionState = LinkSessionState.Idle,
    networkAvailable: Boolean = false,
    lockNotice: Boolean = false
): ConnectionStrip = when {
    mode == AppMode.DEMO -> ConnectionStrip.Demo
    mode == AppMode.REMOTE -> remoteStrip(remote, lockNotice)
    else -> when (state) {
        UsbSessionState.NoDevice -> ConnectionStrip.NoDevice(demoAvailable, networkAvailable)
        is UsbSessionState.PermissionRequired -> ConnectionStrip.NeedsPermission("A DSPi is connected. Allow USB access to use it.")
        is UsbSessionState.PermissionDenied -> ConnectionStrip.NeedsPermission("USB access to the DSPi was denied.")
        // queued prompts are shown by the session as soon as a UI is visible: no second request
        is UsbSessionState.PermissionPending -> ConnectionStrip.Info("Waiting for USB permission…")
        is UsbSessionState.Failed -> ConnectionStrip.Info("Could not open the DSPi: ${state.reason}")
        is UsbSessionState.Open -> ConnectionStrip.Hidden
    }
}

/**
 * The REMOTE strip. Wording follows the macOS Console where it has some: a
 * remote device is "<name> via <hub>" (`DSPiDevice.displayName`), a refused
 * command "Another console is applying changes" (`DeviceLock.lockedMessage`),
 * a PIN hub "This hub needs pairing." (NetworkTransport).
 */
fun remoteStrip(s: LinkSessionState, lockNotice: Boolean = false): ConnectionStrip {
    val d = RemoteAction.DISCONNECT
    return when (s) {
        LinkSessionState.Idle -> ConnectionStrip.Remote("Not connected to a hub.", listOf(d))
        is LinkSessionState.Connecting -> ConnectionStrip.Remote("Connecting to ${s.hub.name}…", listOf(d))
        is LinkSessionState.NeedsPin -> ConnectionStrip.Remote("${s.hub.name}: this hub needs pairing.", listOf(d))
        is LinkSessionState.ChooseDevice -> ConnectionStrip.Remote("${s.hub.name} shares ${s.devices.size} DSPi devices.", listOf(d))
        is LinkSessionState.WaitingForDevice -> ConnectionStrip.Remote(
            if (s.serial.isEmpty()) {
                "${s.hub.name} shares no DSPi right now."
            } else {
                "Waiting for DSPi ${s.serial.takeLast(
                    8
                )} on ${s.hub.name}…"
            },
            listOf(d)
        )
        is LinkSessionState.Connected -> {
            val name = s.device.name.ifEmpty { "DSPi (${s.device.serial.takeLast(8)})" }
            val text = when {
                lockNotice -> DspiException.LOCKED_MESSAGE
                s.role == "viewer" -> "$name via ${s.hub.name} · view only"
                else -> "$name via ${s.hub.name}"
            }
            ConnectionStrip.Remote(text, listOf(d))
        }
        is LinkSessionState.Reconnecting -> ConnectionStrip.Remote(
            "Lost ${s.hub.name}: ${s.reason}. Reconnecting…",
            listOf(RemoteAction.RETRY, d)
        )
        is LinkSessionState.Failed -> ConnectionStrip.Remote(s.reason, listOf(RemoteAction.CONNECT, d))
    }
}
