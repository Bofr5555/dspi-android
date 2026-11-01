// Small building blocks the analysis views share. They stand in for pieces
// that belong in :ui-common (owned by HOME): ValueField (macOS
// Components.swift ValueField), the segmented picker, section labels and the
// state dots/colours of the macOS views. Kept internal here; listed for
// consolidation into :ui-common in the package report.
package se.bofr.dspi.feature.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The macOS system colours the analysis views use. */
internal object MacColors {
    val green = Color(0xFF32D74B)
    val yellow = Color(0xFFFFD60A)
    val orange = Color(0xFFFF9F0A)
    val red = Color(0xFFFF453A)
    val gray = Color(0xFF8E8E93)
}

internal fun StateDot.color(): Color = when (this) {
    StateDot.GRAY -> MacColors.gray
    StateDot.YELLOW -> MacColors.yellow
    StateDot.GREEN -> MacColors.green
    StateDot.ORANGE -> MacColors.orange
}

internal fun FillHealth.color(): Color = when (this) {
    FillHealth.GREEN -> MacColors.green
    FillHealth.YELLOW -> MacColors.yellow
    FillHealth.RED -> MacColors.red
}

@Composable
internal fun secondaryText(): Color = MaterialTheme.colorScheme.onSurfaceVariant

/** Swift `sectionLabel(_:)`: bold, small, secondary capitals. */
@Composable
internal fun SectionLabel(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier = modifier, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = secondaryText())
}

@Composable
internal fun Dot(color: Color, size: Dp = 7.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

/** Secondary caption under a row. */
@Composable
internal fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, fontSize = 11.sp, color = secondaryText())
}

/** A tappable text button in the accent colour (Swift `.buttonStyle(.plain)` + accent). */
@Composable
internal fun PlainButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = if (enabled) MaterialTheme.colorScheme.primary else secondaryText().copy(alpha = 0.4f)
    Text(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        color = c,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium
    )
}

/** Swift `Picker(...).pickerStyle(.segmented)`. */
@Composable
internal fun <T> SegmentedPicker(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
    ) {
        for (o in options) {
            val on = o == selected
            Box(
                Modifier
                    .weight(1f, fill = false)
                    .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable(enabled = enabled) { onSelect(o) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label(o),
                    fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color =
                    when {
                        !enabled -> secondaryText().copy(alpha = 0.4f)
                        on -> MaterialTheme.colorScheme.primary
                        else -> secondaryText()
                    },
                    maxLines = 1
                )
            }
        }
    }
}

/** A title, an optional caption, and a switch (Swift `optionRow`). */
@Composable
internal fun ToggleRow(title: String, caption: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (caption != null) Caption(caption)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/**
 * Swift `ValueField`: a number with its unit; tap to type, commit on Done or
 * when focus leaves. Values outside [minValue] are raised to it; the caller
 * clamps the rest. Shows up to [maxDecimals] places, trailing zeros stripped.
 */
@Composable
internal fun ValueField(
    value: Float,
    unit: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    width: Dp = 72.dp,
    minValue: Float? = null,
    maxDecimals: Int = 1,
    onCommit: (Float) -> Unit
) {
    val shown = formatFieldValue(value, maxDecimals)
    var text by remember { mutableStateOf(shown) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(shown, focused) { if (!focused) text = shown }
    fun commit() {
        val v = text.replace(',', '.').trim().toFloatOrNull()
        if (v == null || !v.isFinite()) {
            text = shown
            return
        }
        onCommit(if (minValue != null) maxOf(v, minValue) else v)
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val shape = RoundedCornerShape(6.dp)
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                textAlign = TextAlign.End,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else secondaryText()
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier
                .width(width)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .padding(horizontal = 6.dp, vertical = 6.dp)
                .onFocusChanged {
                    if (focused && !it.isFocused) commit()
                    focused = it.isFocused
                }
        )
        if (unit.isNotEmpty()) {
            Spacer(Modifier.width(4.dp))
            Text(unit, fontSize = 12.sp, color = secondaryText())
        }
    }
}

/** A centred notice: title and explanation (the macOS unavailable / hidden notices). */
@Composable
internal fun Notice(title: String, detail: String?, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        if (detail != null) Text(detail, fontSize = 13.sp, color = secondaryText(), textAlign = TextAlign.Center)
    }
}
