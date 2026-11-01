// The notification is the Android counterpart of the macOS menu-bar item
// (`DSPi Console/MenuBarMode.swift` @ v1.1.6-beta5, "spirit" only: what the
// device is doing at a glance plus the few actions worth having without the
// window). User volume range/format from ContentView.swift UserVolumeSection
// (USER_VOLUME_MIN_DB = -60, "%.1f dB").
package se.bofr.dspi.usb

import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.core.PresetStatus

/** What the connection notification shows. */
data class NotificationModel(
    val connected: Boolean = false,
    val serial: String = "",
    val userVolumeDb: Float = 0f,
    val userMute: Boolean = false,
    /** -1 when no preset is active. */
    val presetSlot: Int = -1,
    val presetName: String = "",
    val presetOccupied: Int = 0,
    /** Live settings differ from the active preset: loading another one would discard them. */
    val presetDirty: Boolean = false
) {
    /** Next occupied slot after the active one (wrapping), null if none other. */
    val nextPresetSlot: Int?
        get() {
            for (i in 1..DspiConstants.MAX_PRESETS) {
                val s = (presetSlot.coerceAtLeast(-1) + i).mod(DspiConstants.MAX_PRESETS)
                if (s != presetSlot && presetOccupied and (1 shl s) != 0) return s
            }
            return null
        }

    /** The preset action is offered only when it cannot silently discard edits. */
    val canCyclePreset: Boolean get() = connected && !presetDirty && nextPresetSlot != null

    fun volumeText(): String = String.format(Locale.ROOT, "%.1f dB", userVolumeDb)
}

/** Notification actions; the service maps its PendingIntent actions onto these. */
enum class NotificationAction { VOLUME_DOWN, VOLUME_UP, TOGGLE_MUTE, NEXT_PRESET }

/** The bridge calls the notification may make (narrow, so it is faked in tests). */
interface NotificationControls {
    val model: Flow<NotificationModel>

    /** Latest model, synchronously. */
    fun current(): NotificationModel

    fun setUserVolume(db: Float)

    fun setUserMute(muted: Boolean)

    fun loadPreset(slot: Int)
}

object NotificationActions {
    const val USER_VOLUME_MIN_DB = -60f
    const val USER_VOLUME_MAX_DB = 0f
    const val VOLUME_STEP_DB = 2f

    /** Applies [action] against [model]. Returns false when it was not applicable. */
    fun apply(action: NotificationAction, model: NotificationModel, controls: NotificationControls): Boolean {
        if (!model.connected) return false
        when (action) {
            NotificationAction.VOLUME_DOWN, NotificationAction.VOLUME_UP -> {
                val step = if (action == NotificationAction.VOLUME_UP) VOLUME_STEP_DB else -VOLUME_STEP_DB
                val target = (model.userVolumeDb + step).coerceIn(USER_VOLUME_MIN_DB, USER_VOLUME_MAX_DB)
                if (target == model.userVolumeDb) return false
                controls.setUserVolume(target)
            }

            NotificationAction.TOGGLE_MUTE -> controls.setUserMute(!model.userMute)

            NotificationAction.NEXT_PRESET -> {
                if (!model.canCyclePreset) return false
                controls.loadPreset(model.nextPresetSlot ?: return false)
            }
        }
        return true
    }
}

/** [NotificationControls] through the [DspiBridge] contract (implementation: package BRIDGE). */
class BridgeNotificationControls(private val bridge: DspiBridge, private val scope: CoroutineScope) : NotificationControls {
    override val model: Flow<NotificationModel> =
        combine(bridge.state, bridge.status, bridge.presetDirty, bridge.devices) { s, st, dirty, devs ->
            NotificationModel(
                connected = st.connected,
                serial = devs.selectedSerial,
                userVolumeDb = s.userVolumeDb,
                userMute = s.userMute,
                presetSlot = s.activePresetSlot,
                presetName = s.presetNames.getOrNull(s.activePresetSlot).orEmpty(),
                presetOccupied = s.presetOccupied,
                presetDirty = dirty
            )
        }.distinctUntilChanged()

    override fun current(): NotificationModel {
        val s = bridge.state.value
        return NotificationModel(
            connected = bridge.connected,
            serial = bridge.selectedSerial,
            userVolumeDb = s.userVolumeDb,
            userMute = s.userMute,
            presetSlot = s.activePresetSlot,
            presetName = s.presetNames.getOrNull(s.activePresetSlot).orEmpty(),
            presetOccupied = s.presetOccupied,
            presetDirty = bridge.presetDirty.value
        )
    }

    override fun setUserVolume(db: Float) = bridge.setUserVolume(db)

    override fun setUserMute(muted: Boolean) = bridge.setUserMute(muted)

    override fun loadPreset(slot: Int) {
        scope.launch {
            try {
                val r = bridge.loadPreset(slot)
                if (r != PresetStatus.OK) android.util.Log.w("DspiNotification", "loadPreset($slot): $r")
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                android.util.Log.w("DspiNotification", "loadPreset($slot) failed", t)
            }
        }
    }
}

/** Process-wide hand-off from the session (which has the bridge) to the service. */
object ConnectionServiceLink {
    @Volatile var controls: NotificationControls? = null
}
