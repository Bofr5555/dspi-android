// Shared building blocks of the tool windows, ported from
// DSPi-Console:DSPi Console @ v1.1.6-beta5 (b5fdeac):
// - CustomSlider.swift `ParameterRow` (live drag via the coalescer, commit on
//   release, typed entry clamped to the range);
// - Components.swift `ValueField` (display/typed entry) and `toolColumn()`;
// - LiveGraphHost.swift `GraphLive` (what a graph follows during a drag);
// - the header / section label / channel chip / "Presets" menu / unsupported
//   note that every tool view repeats inline;
// and how Linux qt/qml/components/{ParamRow,ChannelChips,ToolHeader}.qml drive
// the same core (Throttle: first value at once, then the latest every 30 ms).
//
// CONSOLIDATION NOTE: these belong in :ui-common (owned by package HOME, built
// in parallel). They are `internal` here so nothing outside :feature-tools
// depends on them; move them to ui-common once HOME's CustomSlider/ValueField
// land and switch the tool views over.
package se.bofr.dspi.feature.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.bofr.dspi.bridge.Throttle

// ═══ Colours (macOS system colours, dark appearance) ═══════════════════

internal object ToolColors {
    val orange = Color(0xFFFF9F0A)
    val green = Color(0xFF32D74B)
    val purple = Color(0xFFBF5AF2)
    val pink = Color(0xFFFF375F)
    val red = Color(0xFFFF453A)
}

@Composable
internal fun accentColor(): Color = MaterialTheme.colorScheme.primary

@Composable
internal fun secondaryColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant

// ═══ GraphLive ══════════════════════════════════════════════════════════

/**
 * LiveGraphHost.swift `GraphLive`: the values a display-only graph follows
 * during a drag, keyed per parameter, over the committed values.
 */
@Stable
internal class GraphLive<K> {
    private val overrides = mutableStateMapOf<K, Float>()

    fun set(key: K, value: Float) {
        overrides[key] = value
    }

    fun clear() {
        if (overrides.isNotEmpty()) overrides.clear()
    }

    operator fun get(key: K, or: Float): Float = overrides[key] ?: or
}

/** A [GraphLive] cleared whenever the committed [base] changes (`.onChange(of: base) { live.clear() }`). */
@Composable
internal fun <K> rememberGraphLive(base: Any): GraphLive<K> {
    val live = remember { GraphLive<K>() }
    LaunchedEffect(base) { live.clear() }
    return live
}

// ═══ Layout ═════════════════════════════════════════════════════════════

/**
 * The tool windows' two `toolColumn()`s side by side when there is room
 * (tablet, landscape); stacked, left first, on a phone.
 */
@Composable
internal fun ToolColumns(
    left: @Composable ColumnScope.() -> Unit,
    right: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth >= 600.dp) {
            val divider = MaterialTheme.colorScheme.outlineVariant
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = left)
                Column(
                    Modifier
                        .weight(1f)
                        .drawBehind { drawLine(divider, Offset(0f, 0f), Offset(0f, size.height), 1f) }
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    content = right
                )
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                left()
                HorizontalDivider()
                right()
            }
        }
    }
}

/** A single-column tool body (Volume Leveller, Upmixer). */
@Composable
internal fun ToolColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
}

// ═══ Header ═════════════════════════════════════════════════════════════

/**
 * The tool window header: icon, title, subtitle, extras and the on/off switch
 * at the top right ("the same switch as the sidebar button").
 */
@Composable
internal fun ToolHeader(
    title: String,
    subtitle: String,
    isOn: Boolean,
    switchEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    extras: @Composable () -> Unit = {}
) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { icon() }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, fontSize = 12.sp, color = secondaryColor())
        }
        extras()
        Spacer(Modifier.width(8.dp))
        Switch(checked = isOn, onCheckedChange = onToggle, enabled = switchEnabled)
    }
}

/** The bold, small, secondary section heading ("PARAMETERS", "OUTPUTS", …). */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = secondaryColor())
}

/** The 9-pt secondary explanation under a control. */
@Composable
internal fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, fontSize = 12.sp, color = secondaryColor(), lineHeight = 16.sp)
}

/**
 * The macOS `.help(...)` tooltip, on touch: long-press the [content] to read it.
 * Nothing is wrapped when [help] is empty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HelpTip(help: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (help.isEmpty()) {
        Box(modifier) { content() }
        return
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(help) } },
        state = rememberTooltipState(isPersistent = true),
        modifier = modifier
    ) { content() }
}

/** A section heading with a "Presets" / "Apply preset" menu on the right. */
@Composable
internal fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SectionLabel(title)
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

/** The borderless "Presets" / "Apply preset" / "All outputs" menu. */
@Composable
internal fun ToolMenu(label: String, enabled: Boolean, items: List<Pair<String, () -> Unit>>, dividerBefore: Set<Int> = emptySet()) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled) { Text(label, fontSize = 14.sp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEachIndexed { i, (title, action) ->
                if (i in dividerBefore) HorizontalDivider()
                DropdownMenuItem(text = { Text(title) }, onClick = {
                    open = false
                    action()
                })
            }
        }
    }
}

/** A labelled switch row ("Interaural Time Delay", "Lookahead", "Link output pairs"). */
@Composable
internal fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    help: String = "",
    modifier: Modifier = Modifier
) {
    HelpTip(help, modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Caption(subtitle)
            }
            Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
        }
    }
}

/** `Picker(.segmented)`. [values] are the tags in display order. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SegmentedPicker(
    labels: List<String>,
    values: List<Int>,
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        labels.forEachIndexed { i, label ->
            SegmentedButton(
                selected = values[i] == selected,
                onClick = { if (values[i] != selected) onSelect(values[i]) },
                shape = SegmentedButtonDefaults.itemShape(i, labels.size),
                enabled = enabled,
                icon = {},
                label = { Text(label, maxLines = 1, fontSize = 13.sp) }
            )
        }
    }
}

/** "Requires firmware with wire format Vnn or newer." */
@Composable
internal fun UnsupportedNote(primary: String, secondary: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("⚠", fontSize = 28.sp, color = secondaryColor())
        Text(primary, fontSize = 15.sp, color = secondaryColor(), textAlign = TextAlign.Center)
        Text(secondary, fontSize = 13.sp, color = secondaryColor(), textAlign = TextAlign.Center)
    }
}

/** The rounded, faintly filled, outlined frame graphs sit in. */
@Composable
internal fun GraphFrame(height: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), shape)
            .border(1.dp, Color.Gray.copy(alpha = 0.2f), shape)
            .padding(8.dp)
    ) { content() }
}

// ═══ Channel chips ══════════════════════════════════════════════════════

/**
 * A row of numbered chips: filled accent = on, grey = off; tap toggles, a
 * long-press shows the channel's name (`.help(outputName(out))`). [below]
 * draws under each chip (the Subharmonic Synthesizer's sub meter).
 */
@Composable
internal fun ChannelChips(
    count: Int,
    isOn: (Int) -> Boolean,
    names: List<String>,
    enabled: Boolean,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
    label: (Int) -> String = { "${it + 1}" },
    below: (@Composable (Int, Boolean) -> Unit)? = null
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until count) {
            val on = isOn(i)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                HelpTip(names.getOrElse(i) { "" }) {
                    ToolChip(label(i), on, enabled, Modifier.fillMaxWidth()) { onToggle(i) }
                }
                below?.invoke(i, on)
            }
        }
    }
}

/** One chip (channel chips, tube shelf). */
@Composable
internal fun ToolChip(text: String, on: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    val bg = if (on) accentColor() else secondaryColor().copy(alpha = 0.12f)
    Box(
        modifier
            .heightIn(min = 36.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .background(bg, shape)
            .border(1.dp, if (on) Color.Transparent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            color = if (on) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

// ═══ ParameterRow ═══════════════════════════════════════════════════════

/**
 * CustomSlider.swift `ParameterRow`: title (+ subtitle), a value field with its
 * unit, a slider, optional end labels and caption. During a drag the thumb and
 * readout run off local state and [live] receives values through [Throttle]
 * (first at once, then the latest every 30 ms) — callers send them with
 * `sendOnly = true`. On release the pending live value is dropped and [set]
 * commits once. A typed entry is clamped to [range] and committed. While a
 * drag runs the committed [value] is not an input, so a late echo cannot pull
 * the thumb back.
 */
@Composable
internal fun ParameterRow(
    title: String,
    unit: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    set: (Float) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    maxDecimals: Int = 0,
    ends: Pair<String, String>? = null,
    displayOverride: String? = null,
    formatLive: ((Float) -> String?)? = null,
    isEnabled: Boolean = true,
    help: String = "",
    caption: String? = null,
    live: ((Float) -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val liveNow by rememberUpdatedState(live)
    val throttle = remember(scope) { Throttle<Float>(scope, 30) { v -> liveNow?.invoke(v) } }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(value) }
    val shown = if (dragging) dragValue else value

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HelpTip(help, Modifier.weight(1f)) {
                Column {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    if (subtitle != null) Caption(subtitle)
                }
            }
            val text =
                if (dragging) {
                    formatLive?.invoke(dragValue) ?: ToolFormat.format(dragValue, maxDecimals)
                } else {
                    displayOverride ?: ToolFormat.format(value, maxDecimals)
                }
            ValueField(text = text, unit = unit, value = value, maxDecimals = maxDecimals, range = range, enabled = isEnabled) { typed ->
                set(typed)
            }
        }
        Slider(
            value = shown.coerceIn(range.start, range.endInclusive),
            onValueChange = { v ->
                dragging = true
                dragValue = v
                if (live != null) throttle.submit(v)
            },
            onValueChangeFinished = {
                // The commit is the last write: drop any live value still waiting.
                throttle.cancel()
                dragging = false
                set(dragValue)
            },
            valueRange = range,
            enabled = isEnabled
        )
        if (ends != null) {
            Row(Modifier.fillMaxWidth()) {
                Caption(ends.first)
                Spacer(Modifier.weight(1f))
                Caption(ends.second)
            }
        }
        if (caption != null) Caption(caption)
    }
}

/**
 * Components.swift `ValueField`: shows [text] and the unit; tap to type a
 * value (the unit is optional), committed on Done or when focus leaves.
 */
@Composable
internal fun ValueField(
    text: String,
    unit: String,
    value: Float,
    maxDecimals: Int,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    width: Dp = 76.dp,
    onCommit: (Float) -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val shape = RoundedCornerShape(5.dp)
    val textColor = MaterialTheme.colorScheme.onSurface
    fun commit() {
        if (!editing) return
        editing = false
        ToolFormat.parse(typed, range)?.let(onCommit)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .widthIn(min = width)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), shape)
                .clickable(enabled = enabled && !editing) {
                    typed = ToolFormat.format(value, maxDecimals)
                    editing = true
                }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            if (editing) {
                BasicTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp, color = textColor, textAlign = TextAlign.End),
                    cursorBrush = SolidColor(textColor),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commit() }),
                    modifier = Modifier.focusRequester(focus).onFocusChanged { if (!it.isFocused) commit() }
                )
                LaunchedEffect(Unit) { focus.requestFocus() }
            } else {
                Text(text, fontSize = 15.sp, color = textColor.copy(alpha = if (enabled) 1f else 0.5f), maxLines = 1)
            }
        }
        if (unit.isNotEmpty()) {
            Text(unit, Modifier.padding(start = 4.dp).widthIn(min = 22.dp), fontSize = 13.sp, color = secondaryColor())
        }
    }
}

/** A horizontal level bar (upmix telemetry, subharm sub meter). */
@Composable
internal fun LevelBar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    Box(modifier.fillMaxWidth().height(height).background(secondaryColor().copy(alpha = 0.15f), RoundedCornerShape(3.dp))) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(color, RoundedCornerShape(3.dp))
        )
    }
}

/** Fills the tool's body when nothing is connected or supported (keeps previews sane). */
@Composable
internal fun ToolScaffold(header: @Composable () -> Unit, modifier: Modifier = Modifier, body: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize()) {
        header()
        HorizontalDivider()
        body()
    }
}
