// Port of DSPi-Console-b5:DSPi Console/Components.swift (MARK: Filter Row:
// FilterRowView with its PEQ row, Linkwitz Transform panel and XO row;
// CrossoverFamilyOption) @ b5fdeac. Linux qt/qml/components/{FilterRow,
// CrossoverRow,LinkwitzEditor,FilterTypeMenu}.qml for the same on Qt.
package se.bofr.dspi.feature.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.bofr.dspi.core.FilterParams
import se.bofr.dspi.feature.graph.PeqBandPalette
import se.bofr.dspi.feature.graph.PeqGraphSelection
import se.bofr.dspi.ui.BandFields
import se.bofr.dspi.ui.BypassCheckbox
import se.bofr.dspi.ui.CrossoverFamilyOption
import se.bofr.dspi.ui.DspiIcons
import se.bofr.dspi.ui.DspiTheme
import se.bofr.dspi.ui.DspiType
import se.bofr.dspi.ui.FilterType
import se.bofr.dspi.ui.PeqMenuGroup
import se.bofr.dspi.ui.PopUpButton
import se.bofr.dspi.ui.ValueField
import se.bofr.dspi.ui.ValueFormat

/** Column widths of the macOS table (pt -> dp), shared by the header and the rows. */
internal object FilterColumns {
    val bypass = 36.dp
    val index = 24.dp
    val type = 150.dp
    val xoFamily = 120.dp
    val xoDir = 100.dp
    val xoSlope = 100.dp
    val freq = 80.dp
    val gain = 60.dp
    val q = 50.dp
    val unit = 24.dp
}

/**
 * One band of a FilterListView (macOS `FilterRowView`). [wide] lays it out
 * as the macOS table row; otherwise the type picker sits on the first line
 * and the numeric fields on the second, so the row fits a phone.
 *
 * Edits go out through [onChange] once per committed field (no live drag on
 * the table). [onBypassToggle] flips just the bypass flag.
 *
 * [graph] is set on PEQ lists whose bands are edited on the response graph
 * (macOS `graphSelection` / `liveReadouts`): the row then carries its band's
 * graph colour, lights up with its dot ([PeqRowHighlight]), selects it on a
 * tap of the number, and follows a graph drag live.
 */
@Composable
fun FilterRowView(
    index: Int,
    params: BandFields,
    availableTypes: List<FilterType>,
    bypassSupported: Boolean,
    isCrossoverMode: Boolean,
    wide: Boolean,
    onChange: (BandFields) -> Unit,
    onBypassToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    graph: RowGraphLink? = null
) {
    val c = DspiTheme.colors
    val isActive = params.type != FilterType.FLAT
    val isBypassed = params.bypass
    val bandColor = graph?.let { Color(PeqBandPalette.argb(index)) }
    val indexColor = when {
        !isActive || isBypassed -> c.secondaryLabel.copy(alpha = c.secondaryLabel.alpha * 0.5f)
        bandColor != null && !bypassSupported -> bandColor
        else -> c.label
    }
    val stripe = if (index % 2 == 0) c.alternatingRow else Color.Transparent
    val hover = remember { MutableInteractionSource() }
    if (graph != null) {
        val hovered by hover.collectIsHoveredAsState()
        LaunchedEffect(hovered, isActive) {
            val sel = graph.selection
            if (hovered && isActive) {
                sel.listHovered = index
            } else if (sel.listHovered == index) {
                sel.listHovered = null
            }
        }
    }
    val rowModifier = if (graph != null) {
        modifier.hoverable(hover).drawBehind {
            PeqRowHighlight.draw(this, bandColor!!, isActive && graph.isSelected, isActive && graph.isGraphHovered)
        }
    } else {
        modifier
    }

    val lead: @Composable () -> Unit = {
        if (bypassSupported) {
            BypassCheckbox(
                isActive = isActive && !isBypassed,
                isEnabled = isActive,
                onToggle = { onBypassToggle(!isBypassed) },
                color = if (isActive) bandColor else null
            )
        }
        Text(
            "${index + 1}",
            style = DspiType.body,
            color = indexColor,
            modifier =
            Modifier.width(FilterColumns.index).then(
                if (graph != null && isActive) {
                    Modifier.clickable(onClickLabel = "Select band ${index + 1} on the graph") { graph.onIndexTap(index) }
                } else {
                    Modifier
                }
            )
        )
    }
    val typePicker: @Composable (Modifier) -> Unit = { m ->
        if (isCrossoverMode) {
            CrossoverFamilyPicker(params, availableTypes, onChange, m.alpha(if (isBypassed || !isActive) 0.45f else 1f))
        } else {
            PeqTypeMenu(params, availableTypes, onChange, m.alpha(if (isBypassed || !isActive) 0.45f else 1f))
        }
    }
    val controls: @Composable () -> Unit = {
        if (isActive) {
            Row(
                Modifier.alpha(if (isBypassed) 0.45f else 1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isCrossoverMode) {
                    CrossoverControls(params, onChange)
                } else if (params.type.isLinkwitzTransform) {
                    LinkwitzControls(params, onChange)
                } else {
                    PeqFields(params, onChange, live = graph?.live)
                }
            }
        }
    }

    if (wide) {
        Row(
            rowModifier.fillMaxWidth().background(stripe).heightIn(min = 44.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            lead()
            typePicker(Modifier.width(if (isCrossoverMode) FilterColumns.xoFamily else FilterColumns.type))
            controls()
            Spacer(Modifier.weight(1f))
        }
    } else {
        Column(rowModifier.fillMaxWidth().background(stripe).padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                lead()
                typePicker(Modifier.weight(1f))
            }
            if (isActive) {
                Row(Modifier.padding(start = if (bypassSupported) FilterColumns.bypass + 8.dp else 0.dp, bottom = 4.dp)) { controls() }
            }
        }
    }
}

/**
 * Freq / Gain / Q fields of a PEQ band; placeholders keep the columns aligned.
 * [live]: the band's value during a graph drag, shown instead (macOS `liveReadout`).
 */
@Composable
private fun PeqFields(p: BandFields, onChange: (BandFields) -> Unit, live: FilterParams? = null) {
    ValueField(
        label = "Hz",
        value = p.freq,
        width = FilterColumns.freq,
        scrollStep = 10f,
        minValue = BandFields.MIN_FREQ,
        liveText = live?.let { ValueFormat.format(it.freq, 1, false) }
    ) {
        onChange(p.copy(freq = it))
    }
    if (p.type.usesGain) {
        ValueField(
            label = "dB",
            value = p.gain,
            width = FilterColumns.gain,
            maxDecimals = 3,
            liveText = live?.let { ValueFormat.format(it.gain, 3, false) }
        ) { onChange(p.copy(gain = it)) }
    } else {
        Spacer(Modifier.width(FilterColumns.gain + FilterColumns.unit))
    }
    if (p.type.usesQ) {
        ValueField(
            label = "Q",
            value = p.q,
            width = FilterColumns.q,
            minValue = BandFields.Q_MIN,
            maxDecimals = 3,
            stripTrailingZeros = true,
            liveText = live?.let { ValueFormat.format(it.q, 3, true) }
        ) {
            onChange(p.copy(q = it))
        }
    } else {
        Spacer(Modifier.width(FilterColumns.q + FilterColumns.unit))
    }
}

/**
 * The PEQ type picker: shapes at the top level, shapes with two orders open
 * their variants (macOS hierarchical Menu; here the dropdown swaps to the
 * group's variants with a back row). Changing type goes through
 * [BandFields.retyped] so a dB gain never lands as a Linkwitz fp.
 */
@Composable
private fun PeqTypeMenu(p: BandFields, availableTypes: List<FilterType>, onChange: (BandFields) -> Unit, modifier: Modifier) {
    val c = DspiTheme.colors
    var open by remember { mutableStateOf(false) }
    var group by remember { mutableStateOf<PeqMenuGroup?>(null) }
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    group = null
                    open = true
                }.heightIn(min = 36.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                p.type.displayName,
                fontSize = 14.sp,
                color = c.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(DspiIcons.chevronUpDown, null, tint = c.secondaryLabel, modifier = Modifier.size(12.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val g = group
            fun pick(t: FilterType) {
                open = false
                if (t != p.type) onChange(p.retyped(t))
            }
            if (g == null) {
                PeqMenuGroup.all.forEach { grp ->
                    val avail = grp.variants.filter { it in availableTypes }
                    when {
                        avail.size == 1 -> TypeItem(grp.name, avail[0] == p.type) { pick(avail[0]) }
                        avail.size > 1 ->
                            DropdownMenuItem(
                                text = { Text(grp.name) },
                                leadingIcon = { CheckSlot(p.type in avail) },
                                trailingIcon = { Text("▸", color = c.secondaryLabel) },
                                onClick = { group = grp }
                            )
                    }
                }
            } else {
                DropdownMenuItem(text = { Text("‹ ${g.name}", color = c.secondaryLabel) }, onClick = { group = null })
                HorizontalDivider()
                g.variants.filter { it in availableTypes }.forEach { t -> TypeItem(t.orderLabel ?: t.displayName, t == p.type) { pick(t) } }
            }
        }
    }
}

@Composable
private fun TypeItem(title: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(title) }, leadingIcon = { CheckSlot(checked) }, onClick = onClick)
}

@Composable
private fun CheckSlot(checked: Boolean) {
    if (checked) Icon(DspiIcons.check, null, Modifier.size(16.dp)) else Spacer(Modifier.size(16.dp))
}

/** The XO TYPE picker: Off or a crossover family. */
@Composable
private fun CrossoverFamilyPicker(p: BandFields, availableTypes: List<FilterType>, onChange: (BandFields) -> Unit, modifier: Modifier) {
    PopUpButton(
        items = CrossoverFamilyOption.available(availableTypes),
        selection = CrossoverFamilyOption.from(p.type),
        titleForItem = { it.label },
        onSelect = { opt -> p.withFamily(opt)?.let(onChange) },
        modifier = modifier,
        textStyle = DspiType.body.copy(fontSize = 14.sp)
    )
}

/** XO row: Low/High Pass, slope ("24 dB/oct"), frequency. */
@Composable
private fun CrossoverControls(p: BandFields, onChange: (BandFields) -> Unit) {
    PopUpButton(
        items = listOf(true, false),
        selection = p.type.crossoverIsLowPass ?: true,
        titleForItem = { if (it) "Low Pass" else "High Pass" },
        onSelect = { lp -> p.withLowPass(lp)?.let(onChange) },
        textStyle = DspiType.body.copy(fontSize = 14.sp)
    )
    PopUpButton(
        items = p.type.crossoverFamily?.availableOrders ?: emptyList(),
        selection = p.type.crossoverOrder,
        titleForItem = { "${it * 6} dB/oct" },
        onSelect = { o -> p.withOrder(o)?.let(onChange) },
        textStyle = DspiType.body.copy(fontSize = 14.sp)
    )
    ValueField(label = "Hz", value = p.freq, width = FilterColumns.freq, scrollStep = 10f, minValue = BandFields.MIN_FREQ) {
        onChange(p.copy(freq = it))
    }
}

/**
 * The Linkwitz Transform row: one config button that opens the parameter
 * panel (macOS popover; a dialog here). Edits stage in a draft and reach the
 * device only on Apply: a mistyped fp is tens of dB of boost into a driver.
 */
@Composable
private fun LinkwitzControls(p: BandFields, onChange: (BandFields) -> Unit) {
    val c = DspiTheme.colors
    var draft by remember { mutableStateOf<BandFields?>(null) }
    Box(
        Modifier
            .width(FilterColumns.freq + FilterColumns.unit)
            .clickable(onClickLabel = "Edit Linkwitz Transform parameters (f0, Q0, fp, Qp)") { draft = p }
            .heightIn(min = 36.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            DspiIcons.sliders,
            contentDescription = "Edit Linkwitz Transform parameters (f0, Q0, fp, Qp)",
            tint = c.secondaryLabel,
            modifier = Modifier.size(18.dp)
        )
    }
    draft?.let { d ->
        LinkwitzEditor(applied = p, draft = d, onDraft = { draft = it }, onApply = { onChange(it) }, onClose = { draft = null })
    }
}

/**
 * The Linkwitz Transform panel: driver alignment (f0, Q0) to a target
 * (fp, Qp), the implied DC boost (orange with a warning above 15 dB),
 * Applied / Not applied yet, Revert and Apply. Closing drops unapplied edits.
 */
@Composable
fun LinkwitzEditor(
    applied: BandFields,
    draft: BandFields,
    onDraft: (BandFields) -> Unit,
    onApply: (BandFields) -> Unit,
    onClose: () -> Unit
) {
    val c = DspiTheme.colors
    val dirty = draft.linkwitzDiffers(applied)
    val boost = draft.linkwitzDcBoostDb
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Linkwitz Transform", style = DspiType.headline)
                Text(
                    "Re-align a sealed woofer's rolloff from the driver's (f0, Q0) to a target (fp, Qp).",
                    style = DspiType.caption,
                    color = c.secondaryLabel
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LinkwitzRow("Driver", "f0", "Hz", draft.freq, 1, "Q0", draft.q, {
                    onDraft(draft.copy(freq = it))
                }, { onDraft(draft.copy(q = it)) })
                LinkwitzRow("Target", "fp", "Hz", draft.gain, 1, "Qp", draft.qp, {
                    onDraft(draft.copy(gain = it))
                }, { onDraft(draft.copy(qp = it)) })
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("DC boost", style = DspiType.caption, color = c.secondaryLabel)
                    Text(
                        "${ValueFormat.signed(boost, 1)} dB",
                        style = DspiType.bodyMono,
                        fontWeight = FontWeight.SemiBold,
                        color = if (boost > 15f) c.orange else c.label
                    )
                    if (boost > 15f) Icon(DspiIcons.warning, null, tint = c.orange, modifier = Modifier.size(14.dp))
                }
                Text(
                    "Real low-frequency gain (40 x log10(f0/fp)). It uses driver excursion and amp headroom - reduce preamp or " +
                        "master volume to match.",
                    fontSize = 11.sp,
                    color = c.secondaryLabel
                )
                HorizontalDivider()
                Text(
                    if (dirty) "Not applied yet" else "Applied",
                    style = DspiType.caption,
                    color = if (dirty) c.orange else c.secondaryLabel
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { onDraft(applied) }, enabled = dirty) { Text("Revert") }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onClose) { Text("Done") }
                TextButton(onClick = { onApply(draft) }, enabled = dirty) { Text("Apply", fontWeight = FontWeight.SemiBold) }
            }
        }
    )
}

@Composable
private fun LinkwitzRow(
    title: String,
    fLabel: String,
    fUnit: String,
    f: Float,
    fDecimals: Int,
    qLabel: String,
    q: Float,
    onF: (Float) -> Unit,
    onQ: (Float) -> Unit
) {
    val c = DspiTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = DspiType.captionBold, color = c.secondaryLabel, modifier = Modifier.width(52.dp))
        LabeledField(fLabel, fUnit, f, 64.dp, fDecimals) { onF(it) }
        LabeledField(qLabel, "", q, 56.dp, 3) { onQ(it) }
    }
}

@Composable
private fun LabeledField(label: String, unit: String, value: Float, width: Dp, decimals: Int, onCommit: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = DspiType.caption, color = DspiTheme.colors.secondaryLabel, modifier = Modifier.width(20.dp))
        ValueField(
            label = unit,
            value = value,
            width = width,
            maxDecimals = decimals,
            stripTrailingZeros = true,
            labelWidth = if (unit.isEmpty()) 0.dp else 20.dp,
            onCommit = onCommit
        )
    }
}

/**
 * A row's link to the response graph's band selection (macOS FilterRowView
 * `graphSelection` + `liveReadouts`): whether its band is selected or under
 * the pointer on the graph, its live value during a graph drag, and what a
 * tap on its number does.
 */
class RowGraphLink(
    val selection: PeqGraphSelection,
    val isSelected: Boolean,
    val isGraphHovered: Boolean,
    val live: FilterParams?,
    val onIndexTap: (Int) -> Unit
)

/** macOS `PeqRowHighlight`: a 2 dp bar in the band's colour plus a tint while selected, a fainter tint while hovered. */
internal object PeqRowHighlight {
    fun draw(scope: DrawScope, color: Color, selected: Boolean, hovered: Boolean) = with(scope) {
        val tint = if (selected) {
            0.13f
        } else if (hovered) {
            0.07f
        } else {
            0f
        }
        if (tint > 0f) drawRect(color.copy(alpha = tint))
        if (selected) drawRect(color, size = Size(2.dp.toPx(), size.height))
    }
}
