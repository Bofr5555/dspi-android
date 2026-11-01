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
// The generic parts (ParameterRow, ValueField, header, section header,
// captions, tooltips, switch rows, segmented pickers, menus, channel chips,
// colours, visibility) are :ui-common's (decision E6); what stays here is
// tool-window layout only.
package se.bofr.dspi.feature.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ═══ Colours ═══════════════════════════════════════════════════════════

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
