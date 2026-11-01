// E9: the install session (installer, locator, chosen source and drive,
// commitment, open confirmation) lives outside the composition. The route's
// ViewModel keeps it across configuration changes; the session's own
// app-scoped coroutine scope runs the device sequence, so popping the route
// mid-install never cancels a write (macOS: the installer queue finishes even
// if the window closes).
package se.bofr.dspi.feature.firmware

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.usb.AndroidBootloaderLocator
import se.bofr.dspi.usb.BootloaderLocator

class FirmwareUpdateViewModel internal constructor(internal val session: FirmwareInstallSession) : ViewModel() {
    init {
        session.attach()
    }

    override fun onCleared() {
        session.detach()
    }

    companion object {
        fun factory(appContext: Context, bridge: DspiBridge, locator: BootloaderLocator?): ViewModelProvider.Factory = viewModelFactory {
            initializer { FirmwareUpdateViewModel(FirmwareInstallSession.obtain(appContext, bridge, locator)) }
        }
    }
}

/**
 * Owns the installer, its locator and the view state for one Firmware Update
 * visit. macOS gives every window open fresh content; so does this, except
 * that an install in progress outlives leaving the screen and is shown again
 * on return. The firmware screen starts the locator when it opens and stops
 * it when the visit ends (an injected, shared locator included: like macOS,
 * it polls only while a flashing UI is open).
 */
internal class FirmwareInstallSession private constructor(context: Context, val bridge: DspiBridge, injected: BootloaderLocator?) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val usbLocator: BootloaderLocator = injected ?: AndroidBootloaderLocator(appContext, scope)
    private val locating = UsbBootloaderLocating(usbLocator, scope, { usbLocator.start(appContext) }, { usbLocator.stop(appContext) })
    private val picoboot = PicobootFirmwareWriter(
        LocatorBoardPermissions(usbLocator),
        AndroidPicobootTransport.factory(appContext, usbLocator)
    )
    private val bundled: (BootloaderBoard.Chip) -> FirmwareImage = { BundledFirmware.image(appContext, it) }

    val model = FirmwareUpdateModel(
        installer = FirmwareInstaller(locating, BridgeFirmwareVerifier(bridge), picoboot, bundled, scope),
        bundled = bundled,
        picobootWriter = picoboot
    )

    private var attached = false

    fun attach() {
        attached = true
        model.installer.beginWatching()
    }

    fun detach() {
        attached = false
        if (!model.installer.isBusy) return close()
        scope.launch {
            model.installer.state.first { s -> s is FirmwareInstallState.Verified || s is FirmwareInstallState.Failed }
            if (!attached) close()
        }
    }

    /** Reads and checks a picked .uf2 off the main thread, then offers it. */
    fun readLocalFile(uri: Uri) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { LocalFirmware.read(appContext, uri) } }
            result.fold(
                onSuccess = { model.chooseLocal(it) },
                onFailure = { e ->
                    model.localFileRefused(
                        if (e is Uf2FormatException) {
                            "This is not a firmware file the board can take: ${e.message}."
                        } else {
                            "The file could not be read."
                        }
                    )
                }
            )
        }
    }

    /** The board's drive, picked with the SAF tree picker. */
    fun useDrive(tree: Uri) {
        scope.launch {
            val volume = withContext(Dispatchers.IO) { runCatching { SafUf2Volume(appContext, tree) }.getOrNull() }
            if (volume != null) model.useDrive(volume)
        }
    }

    private fun close() {
        model.installer.stopWatching()
        scope.cancel()
        if (current === this) current = null
    }

    companion object {
        private var current: FirmwareInstallSession? = null

        /** The running install's session when there is one, else a fresh one. Main thread. */
        fun obtain(context: Context, bridge: DspiBridge, locator: BootloaderLocator? = null): FirmwareInstallSession {
            current?.let { if (it.bridge === bridge && it.model.installer.isBusy) return it }
            return FirmwareInstallSession(context, bridge, locator).also { current = it }
        }
    }
}
