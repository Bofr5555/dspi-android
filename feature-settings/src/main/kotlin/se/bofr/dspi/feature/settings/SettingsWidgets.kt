// Local building blocks of the Settings pages: a touch adaptation of the
// grouped-Form rows the macOS tabs use (Section header + card, label with
// caption, trailing control) and of DSPi-Console-Linux qt/qml/settings/
// Settings{Section,Row,SwitchRow,ChoiceRow,SegmentedRow,SliderRow,PinRow,
// StatusLine}.qml. Consolidation note: generic rows belong in :ui-common
// (owned by HOME); kept here to avoid editing it in parallel.
package se.bofr.dspi.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.bofr.dspi.ui.Caption
import se.bofr.dspi.ui.MacColors
import se.bofr.dspi.ui.SegmentedPicker
import se.bofr.dspi.ui.SliderRow
import se.bofr.dspi.ui.SwitchRow
import se.bofr.dspi.ui.writeEnabled
import se.bofr.dspi.ui.writesAllowed

/** A grouped-Form section: header label, card of rows, optional footer. */
@Composable
internal fun SettingsSection(
    title: String?,
    modifier: Modifier = Modifier,
    footer: String? = null,
    footerColor: Color = Color.Unspecified,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
            )
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(vertical = 4.dp), content = content)
        }
        if (!footer.isNullOrEmpty()) {
            Text(
                footer,
                style = MaterialTheme.typography.bodySmall,
                color = if (footerColor == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else footerColor,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp, end = 4.dp)
            )
        }
    }
}

/** Title + caption on the left, [trailing] on the right (macOS settingRow). */
@Composable
internal fun SettingRow(
    title: String,
    detail: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    titleColor: Color = Color.Unspecified,
    detailColor: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {}
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null && enabled && writesAllowed()) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .alpha(if (enabled) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            if (!detail.isNullOrEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (detailColor == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else detailColor
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

/** A form row with a switch: ui-common's [SwitchRow] with the form's row insets. */
@Composable
internal fun FormSwitchRow(title: String, detail: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) =
    SwitchRow(title, detail, checked, enabled, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), onChange = onChange)

/** A value and its menu (macOS Picker in a Form row). */
@Composable
internal fun <T> ChoiceRow(
    title: String,
    detail: String?,
    options: List<Pair<T, String>>,
    selected: T,
    enabled: Boolean = true,
    onSelect: (T) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == selected }?.second ?: selected.toString()

    // G1: a device setting's menu is disabled read-only (preference tabs are WritesExempt)
    @Suppress("NAME_SHADOWING")
    val enabled = writeEnabled(enabled)
    SettingRow(title, detail, enabled = enabled, onClick = { open = true }) {
        Box {
            TextButton(onClick = { open = true }, enabled = enabled) { Text("$label ▾") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = {
                        open = false
                        if (value != selected) onSelect(value)
                    })
                }
            }
        }
    }
}

/** A GPIO picker: [pins] plus the current pin (always listed). */
@Composable
internal fun PinRow(
    title: String,
    detail: String?,
    pin: Int,
    pins: List<Int>,
    enabled: Boolean = true,
    unsetText: String? = null,
    onSelect: (Int) -> Unit
) {
    val all = buildList {
        if (unsetText != null) add(OutputConfigSnapshot.PIN_UNSET to unsetText)
        (pins + listOfNotNull(pin.takeIf { it != OutputConfigSnapshot.PIN_UNSET && it !in pins })).sorted()
            .forEach { add(it to "GPIO $it") }
    }
    ChoiceRow(title, detail, all, pin, enabled, onSelect)
}

/** Segmented choice (macOS `.pickerStyle(.segmented)`): ui-common's [SegmentedPicker] under a title. */
@Composable
internal fun <T> SegmentedRow(
    title: String,
    detail: String?,
    options: List<Pair<T, String>>,
    selected: T,
    enabled: Boolean = true,
    onSelect: (T) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).alpha(if (enabled) 1f else 0.45f)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (!detail.isNullOrEmpty()) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SegmentedPicker(
            options.map { it.first },
            selected,
            { v -> options.first { it.first == v }.second },
            onSelect,
            Modifier.padding(top = 6.dp),
            enabled
        )
    }
}

/** ui-common's [SliderRow] with the form's row insets. */
@Composable
internal fun FormSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    enabled: Boolean = true,
    detail: String? = null,
    onChange: (Float) -> Unit
) = SliderRow(label, value, range, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), steps, enabled, detail, onChange)

@Composable
internal fun RowDivider() =
    HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

/** Inline outcome line (macOS `statusRow`): orange warning or green check. */
@Composable
internal fun StatusLine(message: String?, isError: Boolean) {
    if (message.isNullOrEmpty()) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (isError) "⚠" else "✓", color = if (isError) MacColors.orange else MacColors.green, fontSize = 13.sp)
        Spacer(Modifier.width(6.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MacColors.orange else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** ui-common's [Caption] with the form's row insets. */
@Composable
internal fun FormCaption(text: String, color: Color = Color.Unspecified) =
    Caption(text, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), color)

/** A capsule label (macOS status pill / "Default" capsule). */
@Composable
internal fun Pill(text: String, color: Color) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

/** The rounded tinted badge of a Settings sidebar row (macOS `SettingsBadge`). */
@Composable
internal fun SettingsBadge(tint: Long, glyph: String) {
    Box(
        Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(Color(tint)),
        contentAlignment = Alignment.Center
    ) {
        Text(glyph, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** A role-tinted monospaced GPIO chip (Overview). */
@Composable
internal fun GpioChip(text: String, tint: Color?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(tint ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = if (tint != null) FontWeight.SemiBold else FontWeight.Normal,
            color = if (tint != null) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
