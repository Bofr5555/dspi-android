// Shared building blocks. Ports of DSPi-Console-b5:DSPi Console/Components.swift
// (FilterListTabToggle, BypassAllControls, FilterListHeaderButton,
// BorderlessPopUpButton, SidebarIconButton, BypassCheckbox, the card style)
// @ b5fdeac, and DSPi-Console-Linux:qt/qml/components/{ToolHeader,
// SectionLabel,ChannelChips,SegmentedControl,AppDialog}.qml @ 4683a2c.
package se.bofr.dspi.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Console's card: `controlBackgroundColor.opacity(0.6)`, 10 pt corners,
 * a 1 pt `gray.opacity(0.2)` outline (ChannelSettingsView, FilterListView,
 * InputChannelHeader, dashboard cards). [stroke] overrides the outline colour.
 */
@Composable
fun Modifier.dspiCard(stroke: Color? = null, shape: Shape = RoundedCornerShape(10.dp)): Modifier {
    val c = DspiTheme.colors
    return this
        .clip(shape)
        .background(c.card, shape)
        .border(1.dp, stroke ?: c.cardStroke, shape)
}

/** A thin vertical rule between the sections of a card (SwiftUI `Divider()` in an HStack). */
@Composable
fun VerticalRule(modifier: Modifier = Modifier) {
    Box(modifier.width(1.dp).fillMaxHeight().background(DspiTheme.colors.separator))
}

/** A thin horizontal rule (SwiftUI `Divider()` in a VStack). */
@Composable
fun HorizontalRule(modifier: Modifier = Modifier) {
    Box(modifier.height(1.dp).background(DspiTheme.colors.separator))
}

/** Small-caps section heading in tool windows ("GAIN", "OUTPUTS", "COMPENSATION CURVE"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = DspiType.sectionLabel, color = DspiTheme.colors.secondaryLabel)
}

/** One segment of [SegmentedControl] (macOS `FilterListTab`). */
data class SegmentTab(val title: String, val isSelected: Boolean, val action: () -> Unit)

/**
 * The outlined, equal-width segmented toggle (macOS `FilterListTabToggle`,
 * "PEQ | XO"); also the limiter link-group picker shape. A selected segment
 * is not re-triggered.
 */
@Composable
fun SegmentedControl(tabs: List<SegmentTab>, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = DspiTheme.colors
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier
            .height(IntrinsicSize.Min)
            .clip(shape)
            .border(1.dp, c.pillStroke, shape)
            .alpha(if (enabled) 1f else 0.4f)
    ) {
        tabs.forEachIndexed { i, tab ->
            if (i > 0) Box(Modifier.width(1.dp).height(16.dp).align(Alignment.CenterVertically).background(c.pillStroke))
            Box(
                Modifier
                    .weight(1f)
                    .background(if (tab.isSelected) c.gray.copy(alpha = 0.22f) else Color.Transparent)
                    .clickable(enabled = enabled) { if (!tab.isSelected) tab.action() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(tab.title, style = DspiType.captionMedium, color = if (tab.isSelected) c.label else c.secondaryLabel, maxLines = 1)
            }
        }
    }
}

/**
 * Two one-shot actions in one outlined pill (macOS `BypassAllControls`:
 * "Enable All | Bypass All"). A half whose action would be a no-op is dimmed
 * and disabled.
 */
@Composable
fun SplitActionPill(
    leftTitle: String,
    leftDisabled: Boolean,
    onLeft: () -> Unit,
    rightTitle: String,
    rightDisabled: Boolean,
    onRight: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = DspiTheme.colors
    val shape = RoundedCornerShape(6.dp)
    Row(modifier.height(IntrinsicSize.Min).clip(shape).border(1.dp, c.pillStroke, shape), verticalAlignment = Alignment.CenterVertically) {
        PillHalf(leftTitle, leftDisabled, onLeft)
        Box(Modifier.width(1.dp).height(16.dp).background(c.pillStroke))
        PillHalf(rightTitle, rightDisabled, onRight)
    }
}

@Composable
private fun PillHalf(title: String, disabled: Boolean, action: () -> Unit) {
    Box(Modifier.clickable(enabled = !disabled, onClick = action).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(
            title,
            style = DspiType.captionMedium,
            color = DspiTheme.colors.secondaryLabel,
            modifier = Modifier.alpha(if (disabled) 0.4f else 1f)
        )
    }
}

/**
 * The outlined caption pill (macOS `FilterListHeaderButton` and the Link /
 * Clear PEQ buttons): secondary text, gray outline. [selected] tints it with
 * the accent as the Link button does when linked.
 */
@Composable
fun PillButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    cornerRadius: Dp = 6.dp
) {
    val c = DspiTheme.colors
    val shape = RoundedCornerShape(cornerRadius)
    val fg = if (selected) c.accent else c.secondaryLabel
    Row(
        modifier
            .clip(shape)
            .background(if (selected) c.accent.copy(alpha = 0.18f) else Color.Transparent, shape)
            .border(1.dp, if (selected) c.accent.copy(alpha = 0.45f) else c.pillStroke, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(14.dp))
        Text(title, style = DspiType.captionMedium, color = fg, maxLines = 1)
    }
}

/**
 * A borderless pop-up picker (macOS `BorderlessPopUpButton`): the selected
 * item's title with the up/down chevron; a tap opens the list, the current
 * item checked. [onLongClick] stands in for the macOS right-click hooks
 * (e.g. reconnect on the device picker).
 */
@Composable
fun <T> PopUpButton(
    items: List<T>,
    selection: T,
    titleForItem: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showsChevron: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    textStyle: androidx.compose.ui.text.TextStyle = DspiType.body
) {
    val c = DspiTheme.colors
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier
                .clip(RoundedCornerShape(5.dp))
                .combinedClickable(
                    enabled = enabled || onLongClick != null,
                    onClick = { if (enabled) open = true },
                    onLongClick = onLongClick
                ).alpha(if (enabled) 1f else 0.4f)
                .heightIn(min = 32.dp)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(titleForItem(selection), style = textStyle, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (showsChevron) Icon(DspiIcons.chevronUpDown, null, tint = c.secondaryLabel, modifier = Modifier.size(12.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(titleForItem(item)) },
                    onClick = {
                        open = false
                        onSelect(item)
                    },
                    leadingIcon = {
                        if (item ==
                            selection
                        ) {
                            Icon(DspiIcons.check, null, modifier = Modifier.size(16.dp))
                        } else {
                            Spacer(Modifier.size(16.dp))
                        }
                    }
                )
            }
        }
    }
}

/**
 * A quick-access icon (macOS `SidebarIconButton`): white when active,
 * secondary otherwise. [onLongClick] is the touch counterpart of the macOS
 * right-click that opens the tool's settings window.
 */
@Composable
fun SidebarIconButton(
    icon: ImageVector,
    isActive: Boolean,
    tooltip: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color? = null,
    onLongClick: (() -> Unit)? = null
) {
    val c = DspiTheme.colors
    val tint = if (isActive) activeColor ?: (if (c.isDark) Color.White else c.accent) else c.secondaryLabel.copy(alpha = 0.6f)
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = tooltip),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = tooltip, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/**
 * Per-band bypass indicator (macOS `BypassCheckbox`): a filled disc when the
 * band is active, a hollow ring when bypassed or Off; dimmed when the band is
 * Off. [color] is the band's graph colour when the graph edits it.
 */
@Composable
fun BypassCheckbox(isActive: Boolean, isEnabled: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, color: Color? = null) {
    val c = DspiTheme.colors
    val base = color ?: Color(0.5f, 0.5f, 0.5f)
    val fillAlpha by animateFloatAsState(if (isActive) 1f else 0f, label = "bypass")
    Box(
        modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(
                enabled = isEnabled,
                interactionSource = remember {
                    MutableInteractionSource()
                },
                indication = null,
                onClick = onToggle
            )
            .alpha(if (isEnabled) 1f else 0.35f),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(base.copy(alpha = fillAlpha))
                .border(1.2.dp, if (isActive) base else (color?.copy(alpha = 0.6f) ?: c.secondaryLabel.copy(alpha = 0.55f)), CircleShape)
        )
    }
}

/**
 * Tool window header (macOS tool views' `headerSection`, Linux ToolHeader):
 * accent icon, title, subtitle, optional accessories, and the effect's
 * on/off switch.
 */
@Composable
fun ToolHeader(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showSwitch: Boolean = true,
    accessories: @Composable () -> Unit = {}
) {
    val c = DspiTheme.colors
    Row(
        modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, null, tint = c.accent, modifier = Modifier.size(26.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.label)
            Text(subtitle, fontSize = 11.sp, color = c.secondaryLabel)
        }
        accessories()
        if (showSwitch) {
            Switch(
                checked = checked,
                onCheckedChange = onToggle,
                enabled = enabled,
                colors = SwitchDefaults.colors(checkedTrackColor = c.accent)
            )
        }
    }
}

/** One chip of [ChannelChips]. */
data class ChannelChip(val label: String, val isOn: Boolean, val color: Color? = null, val name: String = label)

/**
 * A row of channel chips, filled = on, grey = off; tap toggles (Linux
 * ChannelChips; macOS tool windows' output masks). [onToggle] gets the index.
 */
@Composable
fun ChannelChips(chips: List<ChannelChip>, onToggle: (Int) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = DspiTheme.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEachIndexed { i, chip ->
            val shape = RoundedCornerShape(6.dp)
            val on = chip.isOn
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 32.dp)
                    .clip(shape)
                    .background(if (on) chip.color ?: c.accent else c.secondaryLabel.copy(alpha = 0.12f), shape)
                    .border(1.dp, if (on) Color.Transparent else c.label.copy(alpha = 0.08f), shape)
                    .clickable(enabled = enabled, onClickLabel = chip.name) { onToggle(i) }
                    .alpha(if (enabled) 1f else 0.5f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    chip.label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (on) Color.White else c.label.copy(alpha = 0.6f)
                )
            }
        }
    }
}

/** A button of [DspiAlertDialog]. */
data class AlertButton(val title: String, val role: Role = Role.Default, val action: () -> Unit) {
    enum class Role { Default, Cancel, Destructive }
}

/**
 * An NSAlert (macOS `alert.messageText` / `informativeText` / buttons in
 * order, first = default). Buttons are laid out right to left as on macOS:
 * the first is the rightmost. [onDismiss] runs on outside tap / back (the
 * Cancel button's action should match it).
 */
@Composable
fun DspiAlertDialog(title: String, message: String, buttons: List<AlertButton>, onDismiss: () -> Unit) {
    val c = DspiTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                buttons.asReversed().forEach { b ->
                    TextButton(onClick = b.action) {
                        Text(
                            b.title,
                            color = if (b.role == AlertButton.Role.Destructive) c.red else c.accent,
                            fontWeight = if (b === buttons.first()) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    )
}
