// "While on screen" for the analysis views: the Android equivalent of the
// macOS windows' visibility/occlusion handling (SpectrumAnalyserWindowController
// isRendering, the Qt windows' `shown`). A view counts as shown while it is
// composed and its lifecycle is at least STARTED.
package se.bofr.dspi.feature.analysis

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope

/**
 * Decision E9: multi-call device sequences run outside the composition so a
 * configuration change cannot cut them in half. The bridge is main-thread
 * safe, so this is a process-lifetime main scope.
 */
internal object AnalysisScope : CoroutineScope by MainScope()

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
internal fun WhileVisible(key: Any?, onShow: () -> Unit, onHide: (configurationChange: Boolean) -> Unit) {
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
