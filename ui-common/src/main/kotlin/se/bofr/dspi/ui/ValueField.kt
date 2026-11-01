// Port of DSPi-Console-b5:DSPi Console/Components.swift (ValueField,
// LiveValueReadout) @ b5fdeac; Linux qt/qml/components/ValueField.qml.
package se.bofr.dspi.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A numeric value with a unit label, edited in place (macOS `ValueField`).
 * Tap to type (decimal keyboard); Done or leaving the field commits through
 * [onCommit] when the text is a number, otherwise the field reverts. The
 * caller clamps; the published value echoes back into [value].
 *
 * @param width width of the number part (the unit label is [labelWidth] beside it).
 * @param scrollStep the macOS Cmd-scroll step. Kept for API parity; touch has
 *   no scroll wheel, so it is unused (documented in UPSTREAM-DEVIATIONS HOME).
 * @param minValue lower clamp applied to stepped values (parity; see [scrollStep]).
 * @param displayOverride text shown instead of the number while not editing (e.g. "Off").
 * @param liveText while non-null (a slider drag in progress), shown instead of
 *   the number, as macOS `LiveValueReadout`.
 */
@Composable
fun ValueField(
    label: String,
    value: Float,
    width: Dp,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") scrollStep: Float = 0.1f,
    @Suppress("UNUSED_PARAMETER") minValue: Float? = null,
    maxDecimals: Int = 1,
    labelWidth: Dp = 20.dp,
    stripTrailingZeros: Boolean = false,
    displayOverride: String? = null,
    liveText: String? = null,
    enabled: Boolean = true,
    textStyle: TextStyle = DspiType.bodyMono,
    onCommit: (Float) -> Unit
) {
    val colors = DspiTheme.colors
    var editing by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf(TextFieldValue("")) }
    var hadFocus by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val shown = ValueFormat.format(value, maxDecimals, stripTrailingZeros)

    fun commit() {
        if (!editing) return
        editing = false
        hadFocus = false
        ValueFormat.parse(field.text)?.let(onCommit)
    }

    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .width(width)
                .border(1.dp, if (editing) colors.accent else colors.pillStroke.copy(alpha = 0.15f), RoundedCornerShape(5.dp))
                .clickable(enabled = enabled && !editing, interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    field = TextFieldValue(shown, TextRange(0, shown.length))
                    editing = true
                }.padding(horizontal = 4.dp, vertical = 4.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            if (editing) {
                BasicTextField(
                    value = field,
                    onValueChange = { field = it },
                    singleLine = true,
                    textStyle = textStyle.copy(color = colors.accent, textAlign = TextAlign.End),
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commit() }),
                    modifier =
                    Modifier
                        .focusRequester(focus)
                        .onFocusChanged {
                            if (it.isFocused) {
                                hadFocus = true
                            } else if (hadFocus) {
                                commit()
                            }
                        }
                )
                LaunchedEffect(Unit) { focus.requestFocus() }
            } else {
                Text(
                    liveText ?: displayOverride ?: shown,
                    style = textStyle.copy(textAlign = TextAlign.End),
                    color = if (enabled) colors.label else colors.tertiaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }
        }
        if (label.isNotEmpty()) {
            Text(label, style = DspiType.caption, color = colors.secondaryLabel, modifier = Modifier.width(labelWidth), maxLines = 1)
        }
    }
}
