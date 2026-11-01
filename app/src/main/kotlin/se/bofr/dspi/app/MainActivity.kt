package se.bofr.dspi.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.bofr.dspi.ui.DspiTheme

/**
 * The only Activity. Owns nothing: the stack lives in [DspiApplication].
 * - `USB_DEVICE_ATTACHED` launches (implicit permission, device_filter) are
 *   handed to the session in [onCreate] and [onNewIntent] (singleTask);
 * - API 33+: asks for POST_NOTIFICATIONS on a fresh launch so the
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
            app.session.onAttachedIntent(this, intent)
            requestNotificationPermission()
        }
        setContent {
            DspiTheme {
                val modes = app.modes
                val bridge by modes.bridge.collectAsStateWithLifecycle()
                val mode by modes.mode.collectAsStateWithLifecycle()
                val demoAvailable by modes.demoAvailable.collectAsStateWithLifecycle()
                val usbState by app.session.state.collectAsStateWithLifecycle()
                AppScaffold(
                    bridge = bridge,
                    strip = connectionStrip(mode, usbState, demoAvailable),
                    onEnterDemo = { modes.enterDemo() },
                    onExitDemo = { modes.exitDemo() },
                    onRequestPermission = { app.session.requestPermission(this) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        app.session.onAttachedIntent(this, intent)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
