package se.bofr.dspi.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.link.LinkServiceControl
import se.bofr.dspi.usb.BridgeNotificationControls
import se.bofr.dspi.usb.ConnectionServiceControl
import se.bofr.dspi.usb.NotificationControls
import se.bofr.dspi.usb.NotificationModel

/**
 * One `connectedDevice` foreground service for two clients (the USB session
 * and the DSPi Link engine): started when the first needs it, stopped when
 * the last lets go. Main thread.
 */
class SharedServiceControl(private val service: ConnectionServiceControl) {
    private val holders = mutableSetOf<String>()

    fun client(name: String): ConnectionServiceControl = object : ConnectionServiceControl {
        override fun start(): Boolean {
            holders += name
            return service.start()
        }

        override fun stop() {
            if (holders.remove(name) && holders.isEmpty()) service.stop()
        }
    }

    fun linkClient(): LinkServiceControl {
        val c = client("link")
        return object : LinkServiceControl {
            override fun start() = c.start()

            override fun stop() = c.stop()
        }
    }
}

/**
 * The notification's controls follow the bridge the UI shows (device, demo
 * or REMOTE), so the volume/mute/preset actions act on the unit on screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwitchingNotificationControls(private val bridge: StateFlow<DspiBridge>, private val scope: CoroutineScope) : NotificationControls {
    private fun of(b: DspiBridge) = BridgeNotificationControls(b, scope)

    override val model: Flow<NotificationModel> = bridge.flatMapLatest { of(it).model }

    override fun current(): NotificationModel = of(bridge.value).current()

    override fun setUserVolume(db: Float) = bridge.value.setUserVolume(db)

    override fun setUserMute(muted: Boolean) = bridge.value.setUserMute(muted)

    override fun loadPreset(slot: Int) = of(bridge.value).loadPreset(slot)
}
