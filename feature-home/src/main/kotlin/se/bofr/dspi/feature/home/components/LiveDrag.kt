// The drag pattern every Console slider uses (ContentView.swift
// UserVolumeSection / MasterModeSection, Components.swift ChannelSettingsView,
// InputChannelHeader @ b5fdeac): a local value that follows the thumb, the
// model ignored while dragging, live values to the device (sendOnly, at most
// 30/s through SliderValueDelivery), one commit on release.
package se.bofr.dspi.feature.home.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import se.bofr.dspi.ui.SliderValueDelivery

/** Local slider state with live delivery; see the file comment. */
@Stable
class LiveDrag(scope: CoroutineScope, initial: Float) {
    var value by mutableFloatStateOf(initial)
        private set
    var dragging = false
        private set
    private val delivery = SliderValueDelivery(scope)

    /** The model changed: follow it unless a drag owns the value. */
    fun sync(model: Float) {
        if (!dragging) value = model
    }

    /** A thumb value; sent through [live] (sendOnly) while dragging. */
    fun change(v: Float, live: (Float) -> Unit) {
        value = v
        if (dragging) {
            delivery.onValue = live
            delivery.submit(v)
        }
    }

    /** SwiftUI `onEditingChanged`: start, or end = drop pending live values and [commit] once. */
    fun editing(started: Boolean, commit: (Float) -> Unit) {
        if (started) {
            dragging = true
            delivery.synchronize(value)
        } else {
            dragging = false
            delivery.cancel()
            commit(value)
        }
    }

    /** A typed value or a reset: set locally and [commit]. */
    fun set(v: Float, commit: (Float) -> Unit) {
        value = v
        commit(v)
    }

    fun cancel() = delivery.cancel()
}

/** A [LiveDrag] that follows [model] while no drag runs. */
@Composable
fun rememberLiveDrag(model: Float): LiveDrag {
    val scope = rememberCoroutineScope()
    val drag = remember { LiveDrag(scope, model) }
    LaunchedEffect(model) { drag.sync(model) }
    DisposableEffect(Unit) { onDispose { drag.cancel() } }
    return drag
}
