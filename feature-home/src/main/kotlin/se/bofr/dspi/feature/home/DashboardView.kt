// Port of DSPi-Console-b5:DSPi Console/DashboardView.swift @ b5fdeac
// (DashboardOverview, StereoDashboardCard, MonoDashboardCard,
// StereoOutputDashboardCard, OutputDashboardCard, DashboardRow,
// DashboardCardFrame, DashboardLayoutPanel, DashboardLayoutGlyph).
// Linux qt/qml/DashboardView.qml + components/DashboardCard.qml.
package se.bofr.dspi.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.core.DspState
import se.bofr.dspi.ui.BandFields
import se.bofr.dspi.ui.ChannelPalette
import se.bofr.dspi.ui.DspiIcons
import se.bofr.dspi.ui.DspiTheme
import se.bofr.dspi.ui.DspiType
import se.bofr.dspi.ui.FilterType
import se.bofr.dspi.ui.HorizontalRule
import se.bofr.dspi.ui.ValueFormat
import se.bofr.dspi.ui.VerticalRule

/**
 * The overview: one card per stereo pair (inputs, each S/PDIF pair) or mono
 * output (a lone side, PDM), each a read-only table of its ten PEQ bands.
 * Cards per row: Auto (as many 440 dp cards as fit) or the user's 1-3.
 */
@Composable
fun DashboardOverview(bridge: DspiBridge, prefs: HomePreferences, modifier: Modifier = Modifier) {
    val s = bridge.observeState()
    val ready = bridge.observeDeviceReady()
    val cards = DashboardCard.cards(s.numOutputChannels, s.outputEnabled)
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp).alpha(if (ready) 1f else 0f)) {
        val columns = DashboardCard.columnCount(prefs.dashboardCardsPerRow, maxWidth.value, cards.size)
        Column(verticalArrangement = Arrangement.spacedBy(DashboardCard.SPACING.dp)) {
            cards.chunked(columns).forEach { row ->
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(DashboardCard.SPACING.dp)) {
                    row.forEach { card ->
                        Box(Modifier.weight(1f)) { DashboardCardFrame(prefs) { DashboardCardView(bridge, s, card) } }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private fun outputColor(s: DspState, index: Int) = ChannelPalette.output(index, isPdm = index == s.numOutputChannels - 1)

@Composable
private fun DashboardCardView(bridge: DspiBridge, s: DspState, card: DashboardCard) {
    when (card) {
        DashboardCard.Input ->
            StereoDashboardCard(
                left = ChannelColumn(bridge.channelName(0), ChannelPalette.input(0), null, bands(bridge, 0)),
                right = ChannelColumn(bridge.channelName(1), ChannelPalette.input(1), null, bands(bridge, 1))
            )
        is DashboardCard.OutputPair ->
            StereoDashboardCard(
                left = outputColumn(bridge, s, card.left),
                right = outputColumn(bridge, s, card.right)
            )
        is DashboardCard.Output -> MonoDashboardCard(outputColumn(bridge, s, card.index))
    }
}

private fun bands(bridge: DspiBridge, ch: Int): List<BandFields> = bridge.channelBands(ch)?.peq?.map { it.toFields() } ?: emptyList()

private fun outputColumn(bridge: DspiBridge, s: DspState, index: Int) =
    ChannelColumn(bridge.channelName(2 + index), outputColor(s, index), s.outputDelayMs.getOrElse(index) { 0f }, bands(bridge, 2 + index))

/** One side of a card: name, colour, delay readout (null = hidden, as on the input card), bands. */
data class ChannelColumn(val name: String, val color: Color, val delayMs: Float?, val bands: List<BandFields>)

/** Two channels side by side (`StereoDashboardCard` / `StereoOutputDashboardCard`). */
@Composable
fun StereoDashboardCard(left: ChannelColumn, right: ChannelColumn, modifier: Modifier = Modifier) {
    val c = DspiTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .clip(shape)
            .background(c.card, shape)
            .border(
                1.dp,
                Brush.horizontalGradient(0.4f to left.color.copy(alpha = 0.3f), 0.6f to right.color.copy(alpha = 0.3f)),
                shape
            )
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            CardHeader(left, Modifier.weight(1f))
            VerticalRule()
            CardHeader(right, Modifier.weight(1f).padding(end = 18.dp))
        }
        HorizontalRule(Modifier.fillMaxWidth())
        Row(Modifier.height(IntrinsicSize.Min)) {
            BandColumn(left, Modifier.weight(1f))
            VerticalRule()
            BandColumn(right, Modifier.weight(1f))
        }
    }
}

/** One channel alone (`MonoDashboardCard` / `OutputDashboardCard`). */
@Composable
fun MonoDashboardCard(channel: ChannelColumn, modifier: Modifier = Modifier) {
    val c = DspiTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Column(modifier.clip(shape).background(c.card, shape).border(1.dp, channel.color.copy(alpha = 0.3f), shape)) {
        CardHeader(channel, Modifier.fillMaxWidth().padding(end = 18.dp))
        HorizontalRule(Modifier.fillMaxWidth())
        BandColumn(channel, Modifier.fillMaxWidth())
    }
}

@Composable
private fun CardHeader(ch: ChannelColumn, modifier: Modifier) {
    val c = DspiTheme.colors
    Row(
        modifier.height(32.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(6.dp).background(ch.color, CircleShape))
        Text(
            ch.name,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = c.secondaryLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (ch.delayMs != null) {
            Text("Delay: ${ValueFormat.fixedPoint(ch.delayMs, 0)}ms", style = DspiType.mono9, color = c.secondaryLabel, maxLines = 1)
        }
    }
}

@Composable
private fun BandColumn(ch: ChannelColumn, modifier: Modifier) {
    Column(modifier) {
        ch.bands.forEachIndexed { i, p ->
            DashboardRow(i + 1, p, ch.color, Modifier.background(if (i % 2 == 0) DspiTheme.colors.alternatingRow else Color.Transparent))
        }
    }
}

/** A compact read-only band row: number, type code, then Hz / dB / Q (peaking only), or a dash when Off. */
@Composable
fun DashboardRow(band: Int, params: BandFields, color: Color, modifier: Modifier = Modifier) {
    val c = DspiTheme.colors
    val isActive = params.type != FilterType.FLAT
    Row(modifier.fillMaxWidth().height(24.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$band",
            style = DspiType.mono10,
            color = c.secondaryLabel.copy(alpha = c.secondaryLabel.alpha * 0.7f),
            modifier = Modifier.width(18.dp)
        )
        Text(
            params.type.dashboardCode,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (isActive) color else c.secondaryLabel.copy(alpha = 0.4f),
            modifier = Modifier.width(34.dp),
            maxLines = 1
        )
        Spacer(Modifier.weight(1f))
        if (isActive) {
            val value = c.label.copy(alpha = 0.8f)
            val unit = c.secondaryLabel.copy(alpha = c.secondaryLabel.alpha * 0.7f)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(ValueFormat.fixedPoint(params.freq, 0), style = DspiType.mono10, color = value)
                Text("Hz", fontSize = 9.sp, color = unit)
                if (params.type.usesGain) {
                    Spacer(Modifier.width(4.dp))
                    Text(ValueFormat.formatTrimmed(params.gain.toDouble(), 2, signed = true), style = DspiType.mono10, color = value)
                    Text("dB", fontSize = 9.sp, color = unit)
                }
                if (params.type == FilterType.PEAKING) {
                    Spacer(Modifier.width(4.dp))
                    Text(ValueFormat.formatTrimmed(params.q.toDouble(), 3), style = DspiType.mono10, color = value)
                    Text("Q", fontSize = 9.sp, color = unit)
                }
            }
        } else {
            Text("—", fontSize = 11.sp, color = c.secondaryLabel.copy(alpha = 0.2f))
        }
    }
}

/**
 * A card with the dashboard-layout gear at its top right (macOS shows it on
 * hover; touch has no hover, so it is always there, dimmed).
 */
@Composable
private fun DashboardCardFrame(prefs: HomePreferences, content: @Composable () -> Unit) {
    val c = DspiTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        content()
        Box(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 2.dp)) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).clickable(onClickLabel = "Dashboard layout") { open = !open },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    DspiIcons.gear,
                    contentDescription = "Dashboard layout",
                    tint = if (open) c.label else c.secondaryLabel.copy(alpha = 0.6f),
                    modifier = Modifier.size(13.dp)
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) { DashboardLayoutPanel(prefs) }
        }
    }
}

/** The dashboard layout panel: Auto, or a fixed number of cards per row. */
@Composable
private fun DashboardLayoutPanel(prefs: HomePreferences) {
    val c = DspiTheme.colors
    val chosen = prefs.dashboardCardsPerRow.coerceIn(0, 3)
    Column(Modifier.width(240.dp)) {
        Text(
            "DASHBOARD LAYOUT",
            style = DspiType.sectionLabel,
            color = c.secondaryLabel,
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp)
        )
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (n in 0..3) {
                val on = chosen == n
                val shape = RoundedCornerShape(6.dp)
                Column(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(shape)
                        .background(if (on) c.accent.copy(alpha = 0.16f) else c.label.copy(alpha = 0.05f), shape)
                        .border(1.dp, if (on) c.accent.copy(alpha = 0.7f) else Color.Transparent, shape)
                        .clickable(
                            onClickLabel = if (n ==
                                0
                            ) {
                                "Fit cards to the window width"
                            } else {
                                "$n ${if (n == 1) "card" else "cards"} per row"
                            }
                        ) {
                            prefs.dashboardCardsPerRow = n
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    val tint = if (on) c.accent else c.secondaryLabel
                    if (n == 0) Text("↔", color = tint, fontSize = 12.sp) else DashboardLayoutGlyph(n, tint)
                    Text(
                        if (n ==
                            0
                        ) {
                            "Auto"
                        } else {
                            "$n"
                        },
                        fontSize = 10.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = tint
                    )
                }
            }
        }
        Text(DashboardCard.layoutCaption(chosen), fontSize = 11.sp, color = c.secondaryLabel, modifier = Modifier.padding(12.dp))
    }
}

/** Two rows of rounded cells in [columns] columns: a picture of a cards-per-row layout. */
@Composable
private fun DashboardLayoutGlyph(columns: Int, tint: Color) {
    Column(Modifier.size(22.dp, 13.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(2) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(columns) { Box(Modifier.weight(1f).height(5.5.dp).background(tint, RoundedCornerShape(2.dp))) }
            }
        }
    }
}
