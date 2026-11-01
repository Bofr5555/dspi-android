package se.bofr.dspi.usb

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Foreground service (`connectedDevice`) that keeps the process — and with it
 * the core's notify thread and the bridge's status poll — alive while a DSPi
 * is open (PLAN §5.3). Its notification shows user volume / mute / preset with
 * actions (set_user_volume, set_user_mute, load_preset).
 *
 * WAVE 0: skeleton that never starts itself. Package USB implements
 * startForeground + notification + actions.
 */
class DspiConnectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
