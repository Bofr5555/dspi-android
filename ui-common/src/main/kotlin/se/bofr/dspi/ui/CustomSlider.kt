package se.bofr.dspi.ui

import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Port target of macOS CustomSlider.swift. Contract used by every feature:
 * [onValueChange] fires during a drag (send through the bridge with
 * `sendOnly = true`, via Throttle), [onValueChangeFinished] commits once.
 * WAVE 0: thin Material Slider wrapper; package HOME implements the macOS
 * behaviour (fine drag, double-tap to reset to [defaultValue], value field).
 */
@Composable
fun CustomSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    defaultValue: Float? = null,
    onValueChangeFinished: (() -> Unit)? = null
) {
    @Suppress("UNUSED_VARIABLE")
    val unused = defaultValue
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        valueRange = valueRange,
        onValueChangeFinished = onValueChangeFinished
    )
}
