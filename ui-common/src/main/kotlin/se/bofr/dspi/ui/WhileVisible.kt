// "While on screen": the Android equivalent of the macOS windows'
// visibility/occlusion handling (SpectrumAnalyserWindowController isRendering,
// tool windows' timers stopping on close, the Qt windows' `shown`). A view
// counts as shown while it is composed and its lifecycle is at least STARTED.
// The one visibility/lifecycle helper of the app (decision E6; from ANALYSIS
// WhileVisible and TOOLS rememberWhileVisible).
package se.bofr.dspi.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Calls [onShow] when the view becomes visible and [onHide] when it stops
 * being visible (left, covered, app in background). [onHide] is told whether
 * that is only a configuration change (rotation), after which the view comes
 * straight back.
 */
@Composable
fun WhileVisible(key: Any?, onShow: () -> Unit, onHide: (configurationChange: Boolean) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = LocalContext.current.findActivity()
    val show by rememberUpdatedState(onShow)
    val hide by rememberUpdatedState(onHide)
    DisposableEffect(lifecycle, key) {
        var shown = false
        fun changing() = activity?.isChangingConfigurations == true
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> if (!shown) {
                        shown = true
                        show()
                    }
                    Lifecycle.Event.ON_STOP -> if (shown) {
                        shown = false
                        hide(changing())
                    }
                    else -> Unit
                }
            }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            shown = true
            show()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (shown) {
                shown = false
                hide(changing())
            }
        }
    }
}

/**
 * True while the hosting lifecycle is at least STARTED: a view's own device
 * polls (upmix telemetry, subharm meter, statistics) run only then, as macOS
 * stops them when the window closes and the bridge stops its status poll at
 * `setUiVisible(false)`.
 */
@Composable
fun rememberWhileVisible(): Boolean {
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return state.isAtLeast(Lifecycle.State.STARTED)
}
