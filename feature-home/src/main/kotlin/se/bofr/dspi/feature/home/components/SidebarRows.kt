// Port of DSPi-Console-b5:DSPi Console/Components.swift (MARK: Sidebar Rows:
// ChannelVisibilityPill, ChannelRow, OutputRow; ChannelMeterBar) @ b5fdeac.
package se.bofr.dspi.feature.home.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.bofr.dspi.bridge.BridgeStatus
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.ui.DspiTheme
import se.bofr.dspi.ui.DspiType
import se.bofr.dspi.ui.HorizontalMeterBar

/**
 * The descriptor badge that doubles as the graph curve's visibility toggle
 * (macOS `ChannelVisibilityPill`): coloured when the curve is drawn, grey
 * when hidden; padded beyond the capsule so it can be hit without selecting
 * the row.
 */
@Composable
fun ChannelVisibilityPill(descriptor: String, color: Color, isVisible: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val gray = Color.Gray
    Box(
        modifier
            .clickable(
                onClickLabel = if (isVisible) "Hide $descriptor on the graph" else "Show $descriptor on the graph",
                onClick = onToggle
            )
            .padding(start = 10.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
    ) {
        Text(
            descriptor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = if (isVisible) color else DspiTheme.colors.secondaryLabel,
            maxLines = 1,
            modifier =
            Modifier
                .background(if (isVisible) color.copy(alpha = 0.20f) else gray.copy(alpha = 0.12f), CircleShape)
                .border(1.dp, if (isVisible) color.copy(alpha = 0.60f) else gray.copy(alpha = 0.35f), CircleShape)
                .widthIn(min = 40.dp)
                .padding(horizontal = 7.dp, vertical = 3.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

/**
 * A sidebar channel meter following the bridge's 60 ms status itself
 * (macOS `ChannelMeterBar`): [status] is read in the draw phase, so a poll
 * moves the bar and recomposes nothing.
 */
@Composable
fun ChannelMeterBar(
    bridge: DspiBridge,
    status: State<BridgeStatus>,
    ch: Int,
    color: Color,
    modifier: Modifier = Modifier,
    isMuted: Boolean = false
) {
    HorizontalMeterBar(
        level = { status.value.peaks.getOrElse(ch) { 0f } },
        color = color,
        modifier = modifier,
        isMuted = isMuted,
        clipping = {
            status.value // subscribe
            bridge.isClipping(ch)
        }
    )
}

/**
 * One sidebar channel row (macOS `ChannelRow` / `OutputRow`: they differ
 * only in the muted meter): selection bar, name (or the rename field), level
 * meter, visibility pill. Tap selects; long-press opens the context menu
 * (macOS right-click); renaming is inline as on macOS.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelRow(
    bridge: DspiBridge,
    status: State<BridgeStatus>,
    ch: Int,
    color: Color,
    descriptor: String,
    name: String,
    isSelected: Boolean,
    isMuted: Boolean,
    isRenaming: Boolean,
    rowHeight: Int,
    isCurveVisible: Boolean,
    onToggleCurve: () -> Unit,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onCommitRename: (String) -> Unit,
    modifier: Modifier = Modifier,
    menu: @Composable () -> Unit = {}
) {
    val c = DspiTheme.colors
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(rowHeight.dp)
                .background(if (isSelected) c.label.copy(alpha = 0.05f) else Color.Transparent)
                .combinedClickable(onClick = onTap, onLongClick = onLongPress),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .padding(vertical = 4.dp)
                    .background(if (isSelected) c.accent else Color.Transparent)
            )
            if (isRenaming) {
                RenameField(name, onCommitRename, Modifier.padding(start = 8.dp).width(96.dp))
            } else {
                Text(
                    name,
                    style = DspiType.body,
                    color = if (isSelected) c.label else c.label.copy(alpha = c.label.alpha * 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp).width(96.dp)
                )
            }
            ChannelMeterBar(bridge, status, ch, color, Modifier.weight(1f).padding(start = 4.dp), isMuted = isMuted)
            ChannelVisibilityPill(descriptor, color, isCurveVisible, onToggleCurve)
        }
        menu()
    }
}

/** The inline rename field: commits on Done or when focus leaves (macOS `onSubmit` / focus loss). */
@Composable
private fun RenameField(initial: String, onCommit: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = DspiTheme.colors
    var text by remember { mutableStateOf(initial) }
    var hadFocus by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    fun commit() {
        if (done) return
        done = true
        onCommit(text)
    }
    BasicTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        textStyle = DspiType.body.copy(color = c.label),
        cursorBrush = SolidColor(c.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier =
        modifier
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
}
