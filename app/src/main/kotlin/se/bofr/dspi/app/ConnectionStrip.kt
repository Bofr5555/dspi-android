package se.bofr.dspi.app

import se.bofr.dspi.usb.UsbSessionState

/** What the strip under the top bar offers; pure so it is JVM-tested. */
sealed interface ConnectionStrip {
    val text: String

    /** Open device: the strip is hidden (HOME shows the connection and the compat banner). */
    data object Hidden : ConnectionStrip {
        override val text = ""
    }

    /** No DSPi attached; [demoAvailable] adds the "Demo (no device)" action. */
    data class NoDevice(val demoAvailable: Boolean) : ConnectionStrip {
        override val text = "No DSPi connected. Plug one in over USB."
    }

    /** A DSPi is attached but may not be opened: offer the permission prompt. */
    data class NeedsPermission(override val text: String) : ConnectionStrip

    data class Info(override val text: String) : ConnectionStrip

    /** Demo mode is on: always visible, always labelled, with the way out. */
    data object Demo : ConnectionStrip {
        override val text = "Demo (no device): a simulated DSPi. Nothing is sent over USB."
    }
}

fun connectionStrip(mode: AppMode, state: UsbSessionState, demoAvailable: Boolean): ConnectionStrip = when {
    mode == AppMode.DEMO -> ConnectionStrip.Demo
    else -> when (state) {
        UsbSessionState.NoDevice -> ConnectionStrip.NoDevice(demoAvailable)
        is UsbSessionState.PermissionRequired -> ConnectionStrip.NeedsPermission("A DSPi is connected. Allow USB access to use it.")
        is UsbSessionState.PermissionDenied -> ConnectionStrip.NeedsPermission("USB access to the DSPi was denied.")
        // queued prompts are shown by the session as soon as a UI is visible: no second request
        is UsbSessionState.PermissionPending -> ConnectionStrip.Info("Waiting for USB permission…")
        is UsbSessionState.Failed -> ConnectionStrip.Info("Could not open the DSPi: ${state.reason}")
        is UsbSessionState.Open -> ConnectionStrip.Hidden
    }
}
