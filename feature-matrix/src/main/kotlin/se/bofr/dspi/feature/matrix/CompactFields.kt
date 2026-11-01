// Port of DSPi-Console (macOS) `DSPi Console/MatrixMixerView.swift`
// (CompactGainField, CompactDelayField, ScrollWheelHandler) @ v1.1.6-beta5,
// plus the rounding of `Commands.swift` setOutputGain (0.1 dB) and
// setOutputDelay (whole ms).
//
// Touch adaptation (docs/UPSTREAM-DEVIATIONS.md "MATRIX"): tap = type a value
// (macOS: click the field); long-press then drag up/right = more, down/left =
// less (macOS: plain scroll wheel over the field). The drag is live: values go
// out through the caller's Throttle (sendOnly) and commit once on release.
//
// Consolidation note: these are local building blocks; once :ui-common (HOME)
// ships its own value field, they can move there.
package se.bofr.dspi.feature.matrix

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

// ═══ Formatting / parsing (pure, JVM-tested) ═══════════════════════════════

const val COMPACT_GAIN_MIN = -60f
const val COMPACT_GAIN_MAX = 12f

/** Swift `Float.rounded()`: to nearest, ties away from zero. */
internal fun roundHalfAwayFromZero(v: Float): Float {
    val r = floor(abs(v) + 0.5f)
    return if (v < 0) -r else r
}

/** CompactGainField.formatGain: "0", "+3", "-1.5", "+0.25". */
fun formatGain(value: Float): String {
    if (value == 0f) return "0"
    var s = String.format(Locale.ROOT, "%+.2f", value)
    if (s.contains('.')) {
        s = s.trimEnd('0')
        if (s.endsWith('.')) s = s.dropLast(1)
    }
    return s
}

/** CompactGainField.updateDisplay. */
fun compactGainDisplay(gain: Float): String = formatGain(gain) + " dB"

/** CompactDelayField.updateDisplay. */
fun compactDelayDisplay(delay: Float): String = if (delay == 0f) "0 ms" else String.format(Locale.ROOT, "%.1f ms", delay)

/** The text the editor starts with (macOS on focus: formatGain / "%.1f"). */
fun compactGainEditText(gain: Float): String = formatGain(gain)

fun compactDelayEditText(delay: Float): String = String.format(Locale.ROOT, "%.1f", delay)

private val NUMBER = Regex("""^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$""")

/**
 * Swift `Float(cleaned)` after removing the unit: null when the text is not
 * a number (the old value returns). A decimal comma is accepted (phone
 * keyboards in comma locales).
 */
internal fun parseCompactNumber(text: String, unit: String): Float? {
    val cleaned = text.replace(unit, "").trim().replace(',', '.')
    if (!NUMBER.matches(cleaned)) return null
    return cleaned.toFloatOrNull()?.takeIf { it.isFinite() }
}

/** CompactGainField.commitValue: clamped to -60…+12 dB, null if not a number. */
fun parseCompactGain(text: String): Float? = parseCompactNumber(text, "dB")?.coerceIn(COMPACT_GAIN_MIN, COMPACT_GAIN_MAX)

/** CompactDelayField.commitValue: clamped to 0…[maxDelay] ms, null if not a number. */
fun parseCompactDelay(text: String, maxDelay: Float): Float? = parseCompactNumber(text, "ms")?.coerceIn(0f, maxDelay)

/** Commands.swift setOutputGain: rounded to 0.1 dB, no negative zero. */
fun roundOutputGain(db: Float): Float {
    val v = roundHalfAwayFromZero(db * 10f) / 10f
    return if (v == 0f) 0f else v
}

/** Commands.swift setOutputDelay: whole milliseconds ("Applied in whole milliseconds"). */
fun roundOutputDelay(ms: Float): Float {
    val v = roundHalfAwayFromZero(ms)
    return if (v == 0f) 0f else v
}

// ═══ Drag math (touch stand-in for ScrollWheelHandler) ═════════════════════

/**
 * Value after dragging [dragDp] dp (positive = up/right) from [start].
 * Gain: 0.1 dB per [GAIN_DP_PER_STEP] dp, snapped to 0.1 dB. Delay: 1 ms per
 * [DELAY_DP_PER_STEP] dp, snapped to whole ms (what the device applies).
 */
object CompactDrag {
    const val GAIN_DP_PER_STEP = 3f
    const val DELAY_DP_PER_STEP = 4f

    fun gain(start: Float, dragDp: Float, min: Float = COMPACT_GAIN_MIN, max: Float = COMPACT_GAIN_MAX): Float {
        val steps = roundHalfAwayFromZero(dragDp / GAIN_DP_PER_STEP)
        val v = roundHalfAwayFromZero((start + steps * 0.1f) * 10f) / 10f
        return (if (v == 0f) 0f else v).coerceIn(min, max)
    }

    fun delay(start: Float, dragDp: Float, maxDelay: Float): Float {
        val steps = roundHalfAwayFromZero(dragDp / DELAY_DP_PER_STEP)
        return roundOutputDelay(start + steps).coerceIn(0f, maxDelay)
    }
}

// ═══ Composables ═══════════════════════════════════════════════════════════

/**
 * macOS `CompactGainField`. [onLive] fires during a drag (route it through a
 * Throttle with sendOnly), [onCommit] once on release or after typing.
 */
@Composable
fun CompactGainField(
    gain: Float,
    onLive: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 62.dp,
    enabled: Boolean = true,
    title: String = "Gain"
) {
    CompactValueField(
        signed = true,
        value = gain,
        display = ::compactGainDisplay,
        editText = ::compactGainEditText,
        parse = ::parseCompactGain,
        drag = { start, dp -> CompactDrag.gain(start, dp) },
        onLive = onLive,
        onCommit = onCommit,
        modifier = modifier,
        width = width,
        enabled = enabled,
        title = title,
        unit = "dB"
    )
}

/** macOS `CompactDelayField`. */
@Composable
fun CompactDelayField(
    delay: Float,
    maxDelay: Float,
    onLive: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 62.dp,
    enabled: Boolean = true,
    title: String = "Delay"
) {
    CompactValueField(
        signed = false,
        value = delay,
        display = ::compactDelayDisplay,
        editText = ::compactDelayEditText,
        parse = { parseCompactDelay(it, maxDelay) },
        drag = { start, dp -> CompactDrag.delay(start, dp, maxDelay) },
        onLive = onLive,
        onCommit = onCommit,
        modifier = modifier,
        width = width,
        enabled = enabled,
        title = title,
        unit = "ms"
    )
}

@Composable
private fun CompactValueField(
    signed: Boolean,
    value: Float,
    display: (Float) -> String,
    editText: (Float) -> String,
    parse: (String) -> Float?,
    drag: (Float, Float) -> Float,
    onLive: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    modifier: Modifier,
    width: Dp,
    enabled: Boolean,
    title: String,
    unit: String
) {
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(value) }
    var editing by remember { mutableStateOf(false) }
    val current by rememberUpdatedState(value)
    val live by rememberUpdatedState(onLive)
    val commit by rememberUpdatedState(onCommit)
    val dragTo by rememberUpdatedState(drag)
    val shown = if (dragging) dragValue else value
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier =
        modifier
            .width(width)
            .background(
                if (dragging) accent.copy(alpha = 0.15f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                RoundedCornerShape(5.dp)
            )
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                // A long press belongs to the drag below, not to typing.
                detectTapGestures(onLongPress = {}, onTap = { editing = true })
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                var start = 0f
                var totalPx = 0f
                var moved = false
                val end = {
                    dragging = false
                    if (moved) commit(dragValue)
                }
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        start = current
                        totalPx = 0f
                        moved = false
                        dragValue = current
                        dragging = true
                    },
                    onDragEnd = end,
                    onDragCancel = end,
                    onDrag = { change, amount ->
                        change.consume()
                        // up or right = more (the scroll wheel's "up")
                        totalPx += amount.x - amount.y
                        val next = dragTo(start, with(density) { totalPx.toDp().value })
                        if (next != dragValue) {
                            moved = true
                            dragValue = next
                            live(next)
                        }
                    }
                )
            }
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            display(shown),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
            color = if (dragging) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        )
    }
    if (editing) {
        CompactValueEditor(
            title = title,
            unit = unit,
            signed = signed,
            initial = editText(value),
            onDismiss = { editing = false },
            onSubmit = { text ->
                editing = false
                parse(text)?.let { commit(it) }
            }
        )
    }
}

/** Typing a value (macOS: focus the field, type, Return). */
@Composable
internal fun CompactValueEditor(
    title: String,
    unit: String,
    signed: Boolean,
    initial: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                suffix = { Text(unit) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (signed) KeyboardType.Text else KeyboardType.Decimal,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { onSubmit(text.text) }),
                modifier = Modifier.focusRequester(focus)
            )
        },
        confirmButton = { TextButton(onClick = { onSubmit(text.text) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
