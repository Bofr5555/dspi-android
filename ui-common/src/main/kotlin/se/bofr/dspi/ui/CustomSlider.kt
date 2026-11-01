// Port of DSPi-Console-b5:DSPi Console/CustomSlider.swift (CustomSlider,
// ParameterRow) @ b5fdeac; Linux qt/qml/components/{CustomSlider,ParamRow}.qml.
package se.bofr.dspi.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope

/** Pure slider math (JVM-tested). */
object SliderMath {
    /** The value after a horizontal drag of [dxPx] on a track [widthPx] wide, starting at [start]. */
    fun dragged(start: Float, dxPx: Float, widthPx: Float, range: ClosedFloatingPointRange<Float>): Float {
        if (widthPx <= 0f) return start
        val span = range.endInclusive - range.start
        return (start + dxPx / widthPx * span).coerceIn(range.start, range.endInclusive)
    }

    /** 0..1 position of [value] in [range]. */
    fun fraction(value: Float, range: ClosedFloatingPointRange<Float>): Float {
        val span = range.endInclusive - range.start
        return if (span <= 0f) 0f else ((value - range.start) / span).coerceIn(0f, 1f)
    }
}

/**
 * The Console's slider (macOS `CustomSlider` over SwiftUI `Slider`).
 * Stable API (lot 3); device-facing (decision F4): it emits only values the
 * user produced.
 *
 * Touch behaviour: a horizontal drag anywhere on the slider moves the value
 * relative to where it was (no jump to the touch point, so a stray tap or a
 * scroll that brushes the slider never changes a level); vertical gestures
 * pass through to the enclosing scroll view. A double-tap resets to
 * [defaultValue] (the macOS right-click reset) without any intermediate
 * value. Accessibility services can set the value directly.
 *
 * Callbacks: [onEditingChanged] true when a drag starts (nothing is emitted
 * on the grab), then [onValueChange] for every value the drag produces,
 * then [onEditingChanged] false and [onValueChangeFinished] on release.
 * Send drag values with the bridge's `sendOnly = true` setters through
 * [SliderValueDelivery] (or [SliderDragState]); commit on release.
 * Reset: [onReset] when given, otherwise [onValueChange] with the default,
 * then [onValueChangeFinished].
 *
 * @param trackColor tint of the filled track (`.tint(.red)` for the master
 *   volume); the accent colour when null.
 */
@Composable
fun CustomSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    defaultValue: Float? = null,
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true,
    trackColor: Color? = null,
    onEditingChanged: ((Boolean) -> Unit)? = null,
    onReset: (() -> Unit)? = null
) {
    val colors = DspiTheme.colors
    val tint = trackColor ?: colors.accent
    val current by rememberUpdatedState(value.coerceIn(valueRange.start, valueRange.endInclusive))
    val range by rememberUpdatedState(valueRange)
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    val editingChanged by rememberUpdatedState(onEditingChanged)
    val resetNow by rememberUpdatedState(onReset)
    val default by rememberUpdatedState(defaultValue)

    fun reset() {
        val d = default ?: return
        val r = resetNow
        if (r != null) {
            r()
        } else {
            change(d)
            finished?.invoke()
        }
    }

    val thumbColor = if (colors.isDark) Color(0xFFD8D8D8) else Color.White
    val inactive = colors.gray.copy(alpha = 0.25f)
    Canvas(
        modifier
            .height(32.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(current, range)
                if (enabled) {
                    setProgress { v ->
                        editingChanged?.invoke(true)
                        change(v.coerceIn(range.start, range.endInclusive))
                        editingChanged?.invoke(false)
                        finished?.invoke()
                        true
                    }
                }
            }.then(
                if (!enabled) {
                    Modifier
                } else {
                    Modifier
                        .pointerInput(Unit) {
                            var dragValue = 0f
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    dragValue = current
                                    editingChanged?.invoke(true)
                                },
                                onDragEnd = {
                                    editingChanged?.invoke(false)
                                    finished?.invoke()
                                },
                                onDragCancel = {
                                    editingChanged?.invoke(false)
                                    finished?.invoke()
                                },
                                onHorizontalDrag = { ch, dx ->
                                    ch.consume()
                                    val trackWidth = size.width - 2 * THUMB_RADIUS.toPx()
                                    val next = SliderMath.dragged(dragValue, dx, trackWidth, range)
                                    if (next != dragValue) {
                                        dragValue = next
                                        change(next)
                                    }
                                }
                            )
                        }.pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = { reset() })
                        }
                }
            )
    ) {
        val r = THUMB_RADIUS.toPx()
        val trackH = 4.dp.toPx()
        val left = r
        val right = size.width - r
        val y = size.height / 2
        val x = left + (right - left) * SliderMath.fraction(current, range)
        val cr = CornerRadius(trackH / 2)
        drawRoundRect(inactive, Offset(left, y - trackH / 2), Size(right - left, trackH), cr)
        drawRoundRect(tint, Offset(left, y - trackH / 2), Size(x - left, trackH), cr)
        drawCircle(Color.Black.copy(alpha = 0.25f), r + 0.5.dp.toPx(), Offset(x, y))
        drawCircle(thumbColor, r, Offset(x, y))
    }
}

private val THUMB_RADIUS = 9.dp

/**
 * The drag pattern every Console slider uses (macOS UserVolumeSection,
 * ChannelSettingsView, InputChannelHeader, ParameterRow): a local value that
 * follows the thumb; the model is ignored while dragging (a late echo can't
 * pull the thumb back); values go to the device live (sendOnly, at most
 * 30/s, never on the grab, never a value equal to the last one sent); one
 * commit on release. Stable API (lot 3).
 */
@Stable
class SliderDragState(scope: CoroutineScope, initial: Float, intervalMs: Long = 1000L / 30L) {
    var value by mutableFloatStateOf(initial)
        private set
    var dragging by mutableStateOf(false)
        private set
    private val delivery = SliderValueDelivery(scope, intervalMs)

    /** The model changed: follow it unless a drag owns the value. */
    fun sync(model: Float) {
        if (!dragging) value = model
    }

    /** A value the user produced; sent through [live] while dragging. */
    fun change(v: Float, live: (Float) -> Unit) {
        value = v
        if (dragging) {
            delivery.onValue = live
            delivery.submit(v)
        }
    }

    /** `onEditingChanged`: the grab sends nothing; the end drops pending live values and [commit]s once. */
    fun editing(started: Boolean, commit: (Float) -> Unit) {
        if (started) {
            dragging = true
            delivery.synchronize(value)
        } else if (dragging) {
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

/** A [SliderDragState] that follows [model] while no drag runs. */
@Composable
fun rememberSliderDrag(model: Float): SliderDragState {
    val scope = rememberCoroutineScope()
    val drag = remember { SliderDragState(scope, model) }
    LaunchedEffect(model) { drag.sync(model) }
    DisposableEffect(Unit) { onDispose { drag.cancel() } }
    return drag
}

/**
 * The labelled value-field + slider row the tool windows use (macOS
 * `ParameterRow`). The thumb and readout run off local state, so a drag
 * re-renders this row only. [live] sends each drag value straight to the
 * device (coalesced to 30/s; use the bridge's `sendOnly = true` setter);
 * [set] clamps, publishes and sends — once on release, and on a typed entry.
 * While a drag is running [value] is ignored, so a late echo cannot pull the
 * thumb back.
 *
 * @param subtitle a second, smaller line under the title.
 * @param ends labels under the two ends of the slider.
 * @param formatLive text for a value during a drag instead of the number (e.g. "Off").
 * @param help the macOS tooltip; touch has no hover, so it is kept for API
 *   parity only — pass the text as [caption] to show it.
 * @param caption secondary text under the slider.
 * @param defaultValue double-tap on the slider resets to it (macOS: right-click; optional).
 */
@Composable
fun ParameterRow(
    title: String,
    unit: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    set: (Float) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    scrollStep: Float = 1f,
    maxDecimals: Int = 0,
    ends: Pair<String, String>? = null,
    displayOverride: String? = null,
    fieldWidth: Dp = 60.dp,
    formatLive: ((Float) -> String?)? = null,
    isEnabled: Boolean = true,
    @Suppress("UNUSED_PARAMETER") help: String = "",
    caption: String? = null,
    defaultValue: Float? = null,
    live: ((Float) -> Unit)? = null
) {
    val colors = DspiTheme.colors
    val drag = rememberSliderDrag(value)
    val setNow by rememberUpdatedState(set)
    val liveNow by rememberUpdatedState(live)

    fun liveFormat(v: Float) = formatLive?.invoke(v) ?: ValueFormat.format(v, maxDecimals, stripTrailingZeros = false)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (isEnabled) colors.label else colors.tertiaryLabel)
                if (subtitle != null) Text(subtitle, fontSize = 10.sp, color = colors.secondaryLabel)
            }
            ValueField(
                label = unit,
                value = drag.value,
                width = fieldWidth,
                scrollStep = scrollStep,
                maxDecimals = maxDecimals,
                displayOverride = displayOverride,
                liveText = if (drag.dragging) liveFormat(drag.value) else null,
                enabled = isEnabled
            ) { typed ->
                // Commit and let the published value echo back (a clamped entry shows the clamp).
                setNow(typed)
            }
        }
        CustomSlider(
            value = drag.value,
            onValueChange = { v -> drag.change(v) { liveNow?.invoke(it) } },
            onEditingChanged = { e -> drag.editing(e) { setNow(it) } },
            valueRange = range,
            defaultValue = defaultValue,
            onReset = defaultValue?.let { d -> { drag.set(d) { setNow(it) } } },
            enabled = isEnabled,
            modifier = Modifier.fillMaxWidth()
        )
        if (ends != null) {
            Row {
                Text(ends.first, fontSize = 10.sp, color = colors.secondaryLabel)
                Spacer(Modifier.weight(1f))
                Text(ends.second, fontSize = 10.sp, color = colors.secondaryLabel)
            }
        }
        if (caption != null) Text(caption, fontSize = 10.sp, color = colors.secondaryLabel)
    }
}
