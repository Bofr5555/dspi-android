// Port of DSPi-Console-b5:DSPi Console/CustomSlider.swift (SliderValueDelivery,
// DragValueBox) @ b5fdeac.
package se.bofr.dspi.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Coalesces a drag's value stream for the USB send: at most one value per
 * [intervalMs] (30 a second), one pending value rather than a backlog, so a
 * fast drag cannot queue writes ahead of the status poll. It never gates
 * rendering; the thumb and readout follow every event. Values equal to the
 * last delivered one are dropped. Exists only while dragging; [cancel] it on
 * release (or use [finish]).
 *
 * Use with the bridge's `sendOnly = true` setters for [onValue], then commit
 * with the normal setter on release.
 */
class SliderValueDelivery(private val scope: CoroutineScope, private val intervalMs: Long = 1000L / 30L) {
    var onValue: ((Float) -> Unit)? = null
    private var timer: Job? = null
    private var pending: Float? = null
    private var lastDelivered: Float? = null

    /** Forget any pending value and treat [value] as already delivered. */
    fun synchronize(value: Float) {
        cancel()
        lastDelivered = value
    }

    /** A drag value: delivered now if the cooldown has passed, else kept as the one pending value. */
    fun submit(value: Float) {
        pending = value
        if (timer != null) return
        deliverPending()
    }

    /** Drop any pending value and deliver [value] now. */
    fun finish(value: Float) {
        cancel()
        deliver(value)
    }

    fun cancel() {
        timer?.cancel()
        timer = null
        pending = null
    }

    private fun deliverPending() {
        timer = null
        val value = pending ?: return
        pending = null
        // Install the cooldown before the callback, which may recompose.
        timer =
            scope.launch {
                delay(intervalMs)
                deliverPending()
            }
        deliver(value)
    }

    private fun deliver(value: Float) {
        if (value == lastDelivered) return
        lastDelivered = value
        onValue?.invoke(value)
    }
}

/**
 * A drag's value and whether one is running, kept outside Compose state on
 * purpose (macOS `DragValueBox`): the model is not an input while a drag is
 * running, so a late echo cannot pull the thumb back.
 */
class DragValueBox {
    var value: Float = 0f
    var active: Boolean = false
}
