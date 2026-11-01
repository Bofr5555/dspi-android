package se.bofr.dspi.usb

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * PLAN §5.3: the bridge's 60 ms status poll runs only while some activity of
 * the app is started (Qt: timers run while the window is visible). Counts
 * started activities and publishes edges to [onVisible] (wire it to
 * `UsbSession.setUiVisible`, which forwards to the bridge). "Visible" is
 * published at once; "hidden" only after [hideDebounceMs] with nothing
 * started, so a configuration change (rotation: stop → start) does not flap
 * the poll. Uses no extra library (lifecycle-process is not a dependency).
 */
class UiVisibilityTracker(
    private val scope: CoroutineScope,
    private val hideDebounceMs: Long = HIDE_DEBOUNCE_MS,
    private val onVisible: (Boolean) -> Unit
) {
    private var started = 0
    private var visible: Boolean? = null
    private var pendingHide: Job? = null

    fun activityStarted() {
        started++
        pendingHide?.cancel()
        pendingHide = null
        publish(true)
    }

    fun activityStopped() {
        if (started > 0) started--
        if (started > 0 || pendingHide != null) return
        pendingHide = scope.launch {
            delay(hideDebounceMs)
            pendingHide = null
            if (started == 0) publish(false)
        }
    }

    private fun publish(now: Boolean) {
        if (now != visible) {
            visible = now
            onVisible(now)
        }
    }

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) = activityStarted()

                override fun onActivityStopped(activity: Activity) = activityStopped()

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

                override fun onActivityResumed(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            }
        )
    }

    companion object {
        const val HIDE_DEBOUNCE_MS = 300L
    }
}
