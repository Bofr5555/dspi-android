package se.bofr.dspi.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import se.bofr.dspi.bridge.BridgeEvent
import se.bofr.dspi.core.DspiException
import se.bofr.dspi.ui.DspiTheme
import se.bofr.dspi.ui.link.LocalNetworking
import se.bofr.dspi.ui.settings.LocalAppSettings
import se.bofr.dspi.ui.state.LocalAppState

/**
 * The only Activity. Owns nothing: the stack lives in [DspiApplication].
 * - `USB_DEVICE_ATTACHED` launches (implicit permission, device_filter) are
 *   handed to the session in [onCreate] and [onNewIntent] (singleTask);
 * - API 33+: asks for POST_NOTIFICATIONS (see [shouldAskNotificationPermission]) so the
 *   connectedDevice foreground service's notification (volume/mute/preset)
 *   is visible (review U7). Denial is fine: the service still runs.
 */
class MainActivity : ComponentActivity() {
    private val app get() = application as DspiApplication

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* nothing to do either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // a recreated Activity (rotation) still carries the launch intent: hand it over once
        if (savedInstanceState == null) {
            handAttachedIntent(intent)
            requestNotificationPermission()
        }
        setContent {
            DspiTheme {
                val modes = app.modes
                val bridge by modes.bridge.collectAsStateWithLifecycle()
                val mode by modes.mode.collectAsStateWithLifecycle()
                val demoAvailable by modes.demoAvailable.collectAsStateWithLifecycle()
                val usbState by app.session.state.collectAsStateWithLifecycle()
                val remote by modes.remoteState.collectAsStateWithLifecycle()
                val usbPrompt by modes.usbPrompt.collectAsStateWithLifecycle()
                val link by app.linkSettings.settings.collectAsStateWithLifecycle()
                var networkingOpen by remember { mutableStateOf(false) }
                // macOS DeviceLockNotice: "Another console is applying changes" for 4 s after a LOCKED refusal
                var lockNotice by remember { mutableStateOf(false) }
                LaunchedEffect(bridge) {
                    bridge.events.filter { it is BridgeEvent.CommandFailed && it.error is DspiException.Locked }.collectLatest {
                        lockNotice = true
                        delay(4_000)
                        lockNotice = false
                    }
                }
                CompositionLocalProvider(
                    LocalAppSettings provides app.appSettings,
                    LocalAppState provides app.appState,
                    LocalNetworking provides app.networking
                ) {
                    AppScaffold(
                        bridge = bridge,
                        strip = connectionStrip(mode, usbState, demoAvailable, remote, link.enabled, lockNotice),
                        onEnterDemo = { modes.enterDemo() },
                        onExitDemo = { modes.exitDemo() },
                        onRequestPermission = { app.session.requestPermission(this) },
                        bootloaderLocator = app.bootloaderLocator,
                        onRemoteAction = { a ->
                            when (a) {
                                RemoteAction.RETRY -> modes.remoteSession?.retryNow()
                                RemoteAction.CONNECT -> remote.hub?.let { modes.remoteSession?.connect(it) }
                                RemoteAction.DISCONNECT -> modes.exitRemote()
                            }
                        },
                        onConnectNetwork = { networkingOpen = true },
                        overlay = {
                            RemoteDialogs(
                                state = remote,
                                usbPrompt = usbPrompt,
                                onPin = { modes.remoteSession?.submitPin(it) },
                                onCancelPin = { modes.remoteSession?.cancelPin() },
                                onChoose = { modes.remoteSession?.chooseDevice(it) },
                                onCancelChoice = { modes.exitRemote() },
                                onUsbAnswer = { modes.answerUsbPrompt(it) }
                            )
                            if (networkingOpen) NetworkingDialog(app.networking) { networkingOpen = false }
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handAttachedIntent(intent)
    }

    /** Via the app's gate: after the links are loaded (review A3); application context, the Activity may be gone by then. */
    private fun handAttachedIntent(intent: Intent) {
        val ctx = applicationContext
        app.usbGate.run { app.session.onAttachedIntent(ctx, intent) }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val prefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
        val ask = shouldAskNotificationPermission(
            granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            askedBefore = prefs.getBoolean(KEY_NOTIF_ASKED, false),
            rationale = shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        )
        if (!ask) return
        prefs.edit().putBoolean(KEY_NOTIF_ASKED, true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        const val UI_PREFS = "dspi_ui"
        const val KEY_NOTIF_ASKED = "notificationPermissionAsked"
    }
}

/**
 * Review A4: ask on the first launch, and again only while Android would
 * still show its dialog (`shouldShowRequestPermissionRationale` is true
 * after one denial, false after "don't ask again"). Never after a permanent
 * denial: `launch` would be a silent no-op on every start.
 */
fun shouldAskNotificationPermission(granted: Boolean, askedBefore: Boolean, rationale: Boolean): Boolean =
    !granted && (!askedBefore || rationale)
