// Port of DSPi-Console-b5:DSPi Console/CustomSlider.swift (CustomSlider,
// ParameterRow) @ b5fdeac; Linux qt/qml/components/{CustomSlider,ParamRow}.qml.
package se.bofr.dspi.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Console's slider (macOS `CustomSlider` over SwiftUI `Slider`).
 *
 * Contract used by every feature: [onValueChange] fires during a drag (send
 * it to the device with the bridge's `sendOnly = true` setters through
 * [SliderValueDelivery] or the bridge's Throttle); [onValueChangeFinished]
 * fires once on release (commit with the normal setter).
 * [onEditingChanged] reports drag start (true) and end (false), as SwiftUI's
 * `onEditingChanged` does.
 *
 * [defaultValue]: a double-tap resets to it (the touch counterpart of the
 * macOS right-click reset): [onReset] when given, otherwise [onValueChange]
 * with the default, then [onValueChangeFinished].
 *
 * @param trackColor tint of the filled track and thumb (`.tint(.red)` for the
 *   master volume); the accent colour when null.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    var editing by remember { mutableStateOf(false) }
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    val editingChanged by rememberUpdatedState(onEditingChanged)
    val resetNow by rememberUpdatedState(onReset)
    val scope = rememberCoroutineScope()
    val slop = LocalViewConfiguration.current.touchSlop
    val reset =
        if (defaultValue == null || !enabled) {
            Modifier
        } else {
            Modifier.pointerInput(defaultValue) {
                var lastTapUp = 0L
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val up = waitForUpOrCancellation(PointerEventPass.Final) ?: return@awaitEachGesture
                    val isTap = up.uptimeMillis - down.uptimeMillis < 300 && (up.position - down.position).getDistance() < slop
                    if (!isTap) {
                        lastTapUp = 0L
                    } else if (lastTapUp != 0L && down.uptimeMillis - lastTapUp < 350) {
                        lastTapUp = 0L
                        // After the slider has committed the tap itself.
                        scope.launch {
                            delay(32)
                            val r = resetNow
                            if (r != null) {
                                r()
                            } else {
                                change(defaultValue)
                                finished?.invoke()
                            }
                        }
                    } else {
                        lastTapUp = up.uptimeMillis
                    }
                }
            }
        }
    Slider(
        value = value.coerceIn(valueRange.start, valueRange.endInclusive),
        onValueChange = {
            if (!editing) {
                editing = true
                editingChanged?.invoke(true)
            }
            change(it)
        },
        onValueChangeFinished = {
            if (editing) {
                editing = false
                editingChanged?.invoke(false)
            }
            finished?.invoke()
        },
        modifier = modifier.then(reset).height(28.dp),
        valueRange = valueRange,
        enabled = enabled,
        colors =
        SliderDefaults.colors(
            thumbColor = if (colors.isDark) Color(0xFFD8D8D8) else Color.White,
            activeTrackColor = tint,
            inactiveTrackColor = colors.gray.copy(alpha = 0.25f),
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent
        )
    )
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
    val scope = rememberCoroutineScope()
    val delivery = remember { SliderValueDelivery(scope) }
    val drag = remember { DragValueBox() }
    var localValue by remember { mutableFloatStateOf(value) }
    var liveText by remember { mutableStateOf<String?>(null) }
    val setNow by rememberUpdatedState(set)
    val liveNow by rememberUpdatedState(live)

    LaunchedEffect(value) { if (!drag.active) localValue = value }
    DisposableEffect(Unit) { onDispose { delivery.cancel() } }

    fun liveFormat(v: Float) = formatLive?.invoke(v) ?: ValueFormat.format(v, maxDecimals, stripTrailingZeros = false)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (isEnabled) colors.label else colors.tertiaryLabel)
                if (subtitle != null) Text(subtitle, fontSize = 10.sp, color = colors.secondaryLabel)
            }
            ValueField(
                label = unit,
                value = localValue,
                width = fieldWidth,
                scrollStep = scrollStep,
                maxDecimals = maxDecimals,
                displayOverride = displayOverride,
                liveText = liveText,
                enabled = isEnabled
            ) { typed -> setNow(typed) }
        }
        CustomSlider(
            value = if (drag.active) drag.value else localValue,
            onValueChange = { v ->
                drag.value = v
                localValue = v
                if (drag.active) {
                    liveText = liveFormat(v)
                    liveNow?.let {
                        delivery.onValue = it
                        delivery.submit(v)
                    }
                }
            },
            onEditingChanged = { editing ->
                if (editing) {
                    drag.active = true
                    delivery.synchronize(localValue)
                    liveText = liveFormat(drag.value)
                    liveNow?.let {
                        delivery.onValue = it
                        delivery.submit(drag.value)
                    }
                } else {
                    drag.active = false
                    liveText = null
                    // The commit is the last write: drop any live value still waiting.
                    delivery.cancel()
                    localValue = drag.value
                    setNow(drag.value)
                }
            },
            valueRange = range,
            defaultValue = defaultValue,
            onReset =
            defaultValue?.let { d ->
                {
                    localValue = d
                    setNow(d)
                }
            },
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
