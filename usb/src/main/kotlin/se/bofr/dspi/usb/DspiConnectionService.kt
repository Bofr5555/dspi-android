package se.bofr.dspi.usb

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import se.bofr.dspi.core.DspiConstants

/**
 * Foreground service (`connectedDevice`) that keeps the process — and with it
 * the core's notify thread and the bridge's status poll — alive while a DSPi
 * is open (PLAN §5.3). Started by [AndroidUsbSession] after the device was
 * opened (Android 14 accepts `connectedDevice` only once the app holds USB
 * permission for a device) and stopped when it closes. Its notification shows
 * user volume / mute / preset with an action for each.
 */
class DspiConnectionService : LifecycleService() {
    private var collector: Job? = null
    private val strings by lazy {
        NotificationStrings(
            titleDisconnected = getString(R.string.dspi_usb_title_disconnected),
            connecting = getString(R.string.dspi_usb_connecting),
            titleSerial = getString(R.string.dspi_usb_title_serial),
            titleConnected = getString(R.string.dspi_usb_title_connected),
            muted = getString(R.string.dspi_usb_muted),
            volume = getString(R.string.dspi_usb_volume),
            preset = getString(R.string.dspi_usb_preset),
            presetNamed = getString(R.string.dspi_usb_preset_named),
            unsaved = getString(R.string.dspi_usb_unsaved)
        )
    }
    private var foreground = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val controls = ConnectionServiceLink.controls
        // startForeground first, always: a service started with
        // startForegroundService() that stops without it crashes the app.
        if (!foreground && !goForeground(controls?.current() ?: NotificationModel())) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (controls == null) {
            // Process was recreated without a session: nothing to host.
            stopSelf()
            return START_NOT_STICKY
        }
        val action = actionOf(intent?.action)
        if (action != null) {
            NotificationActions.apply(action, controls.current(), controls)
        }
        if (collector == null) {
            collector = lifecycleScope.launch {
                controls.model.collect { update(it) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        collector?.cancel()
        collector = null
        if (foreground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
        super.onDestroy()
    }

    private fun goForeground(model: NotificationModel): Boolean {
        ensureChannel()
        return try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, build(model), type)
            foreground = true
            true
        } catch (e: RuntimeException) {
            // SecurityException (API 34: no USB permission held) or
            // ForegroundServiceStartNotAllowedException (API 31+: from background).
            Log.w(TAG, "startForeground refused: $e")
            false
        }
    }

    private fun update(model: NotificationModel) {
        if (!foreground || !canNotify()) return
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, build(model))
    }

    private fun canNotify(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.dspi_usb_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.dspi_usb_channel_description)
                setShowBadge(false)
            }
        )
    }

    private fun build(model: NotificationModel): Notification {
        val text = NotificationText.of(model, strings)
        val big = RemoteViews(packageName, R.layout.dspi_notification_big).apply {
            setTextViewText(R.id.dspi_notif_status, text.body)
            setTextViewText(
                R.id.dspi_notif_mute,
                getString(if (model.userMute) R.string.dspi_usb_action_unmute else R.string.dspi_usb_action_mute)
            )
            setOnClickPendingIntent(R.id.dspi_notif_vol_down, actionIntent(NotificationAction.VOLUME_DOWN))
            setOnClickPendingIntent(R.id.dspi_notif_vol_up, actionIntent(NotificationAction.VOLUME_UP))
            setOnClickPendingIntent(R.id.dspi_notif_mute, actionIntent(NotificationAction.TOGGLE_MUTE))
            setOnClickPendingIntent(R.id.dspi_notif_preset, actionIntent(NotificationAction.NEXT_PRESET))
            setViewVisibility(R.id.dspi_notif_preset, if (model.canCyclePreset) View.VISIBLE else View.GONE)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_dspi_notification)
            .setContentTitle(text.title)
            .setContentText(text.body)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomBigContentView(big)
            .setContentIntent(launchIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun actionIntent(action: NotificationAction): PendingIntent {
        val intent = Intent(this, DspiConnectionService::class.java).setAction(ACTION_PREFIX + action.name)
        return PendingIntent.getService(
            this,
            action.ordinal + 1,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun launchIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        private const val TAG = "DspiConnectionService"
        const val CHANNEL_ID = "dspi_connection"
        const val NOTIFICATION_ID = 0x2E8B
        const val ACTION_PREFIX = "se.bofr.dspi.usb.action."

        fun actionOf(intentAction: String?): NotificationAction? {
            if (intentAction == null || !intentAction.startsWith(ACTION_PREFIX)) return null
            return NotificationAction.entries.firstOrNull { it.name == intentAction.removePrefix(ACTION_PREFIX) }
        }
    }
}

/** Localised pieces of the notification text (from `res/values/strings.xml` in the service). */
data class NotificationStrings(
    val titleDisconnected: String = "DSPi",
    val connecting: String = "Connecting…",
    /** `%1$s` = last 8 characters of the serial. */
    val titleSerial: String = "DSPi %1\$s",
    val titleConnected: String = "DSPi connected",
    val muted: String = "Muted",
    /** `%1$s` = "-20.0 dB". */
    val volume: String = "Volume %1\$s",
    /** `%1$d` = slot (1-based). */
    val preset: String = "Preset %1\$d",
    /** `%1$d` = slot (1-based), `%2$s` = name. */
    val presetNamed: String = "Preset %1\$d: %2\$s",
    val unsaved: String = "unsaved changes"
)

/** Title/body of the notification (pure, JVM-tested). */
data class NotificationText(val title: String, val body: String) {
    companion object {
        fun of(m: NotificationModel, s: NotificationStrings = NotificationStrings()): NotificationText {
            if (!m.connected) return NotificationText(s.titleDisconnected, s.connecting)
            val title = if (m.serial.isNotEmpty()) s.titleSerial.format(m.serial.takeLast(8)) else s.titleConnected
            val vol = if (m.userMute) s.muted else s.volume.format(m.volumeText())
            val preset = when {
                m.presetSlot !in 0 until DspiConstants.MAX_PRESETS -> null
                m.presetName.isNotBlank() -> s.presetNamed.format(m.presetSlot + 1, m.presetName)
                else -> s.preset.format(m.presetSlot + 1)
            }
            val parts = listOfNotNull(vol, preset, if (m.presetDirty) s.unsaved else null)
            return NotificationText(title, parts.joinToString(" · "))
        }
    }
}

/** Starts/stops the service from the session. */
class AndroidServiceControl(context: Context) : ConnectionServiceControl {
    private val appContext = context.applicationContext

    override fun start(): Boolean = try {
        ContextCompat.startForegroundService(appContext, Intent(appContext, DspiConnectionService::class.java))
        true
    } catch (e: RuntimeException) {
        // ForegroundServiceStartNotAllowedException (API 31+) when the app is
        // in the background: the session retries when a UI becomes visible.
        Log.w("DspiUsb", "foreground service not started: $e")
        false
    }

    override fun stop() {
        appContext.stopService(Intent(appContext, DspiConnectionService::class.java))
    }
}
