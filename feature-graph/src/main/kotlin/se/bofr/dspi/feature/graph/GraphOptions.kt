// Port of the graph's gear panel: DSPi-Console-b5:DSPi Console/
// SpectrumAnalyserView.swift `GraphOptionsButton` / `GraphOptionsPanel` /
// `GraphSetupPage` (l.808-1300) @ b5fdeac, and the page spectrum selection of
// DSPViewModel (`rtaSelection`, `setRtaSelection`, `switchRtaSide`,
// `resetRtaPageSelection`, `dashboardRtaSelection`). A dialog instead of a
// popover on a phone. RTA Bars and Pop Out Graph are not offered here
// (docs/UPSTREAM-DEVIATIONS.md GRAPH).
package se.bofr.dspi.feature.graph

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.util.Locale
import kotlin.math.roundToInt
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.core.DspiConstants

/**
 * The spectrum channels of the page showing the graph: the dashboard's are
 * remembered in the settings, a channel page's start as its own channel each
 * time one opens (or nothing, if the user hid the spectrum on channel pages).
 */
class SpectrumSelectionState internal constructor(
    private val store: GraphSettingsStore,
    private val activeEq: Int?,
    private val available: (Int) -> List<Int>,
    private val page: MutableState<RtaChannelSelection>,
    private val pageOther: MutableState<RtaChannelSelection?>
) {
    /** macOS `DSPViewModel.rtaSelection`. */
    val value: RtaChannelSelection
        get() {
            if (activeEq != null) return page.value.restricted(available(page.value.tap))
            return dashboardSelection(store.settings.value, available)
        }

    /** macOS `setRtaSelection`. */
    fun set(selection: RtaChannelSelection) {
        if (activeEq == null) {
            store.update { it.copy(rtaDashboardSelectionKey = selection.storageKey) }
        } else {
            page.value = selection
            // Hiding the spectrum on one channel page keeps it hidden on the next.
            store.update { it.copy(rtaChannelPagesShowSpectrum = !selection.isEmpty) }
        }
    }

    /** macOS `switchRtaSide`: show the other side, bringing back what was checked there. */
    fun switchSide(tap: Int) {
        val current = value
        if (current.tap == tap) return
        if (activeEq == null) {
            val s = store.settings.value
            val other =
                RtaChannelSelection.fromStorageKey(s.rtaDashboardOtherSideKey)?.takeIf { it.tap == tap }
                    ?: RtaChannelSelection(tap, emptyList())
            store.update { it.copy(rtaDashboardSelectionKey = other.storageKey, rtaDashboardOtherSideKey = current.storageKey) }
        } else {
            val other = pageOther.value?.takeIf { it.tap == tap } ?: RtaChannelSelection(tap, emptyList())
            pageOther.value = page.value
            page.value = other
        }
    }

    companion object {
        /** macOS `dashboardRtaSelection`: the stored channels that are live, else the first output, else input 1. */
        fun dashboardSelection(s: GraphSettings, available: (Int) -> List<Int>): RtaChannelSelection {
            val stored = RtaChannelSelection.fromStorageKey(s.rtaDashboardSelectionKey)
            if (stored != null) {
                if (stored.isEmpty) return stored
                val live = stored.restricted(available(stored.tap))
                if (!live.isEmpty) return live
            }
            available(DspiConstants.RTA_TAP_OUTPUT).firstOrNull()?.let {
                return RtaChannelSelection(DspiConstants.RTA_TAP_OUTPUT, listOf(it))
            }
            return RtaChannelSelection(DspiConstants.RTA_TAP_INPUT, listOf(0))
        }

        /** macOS `resetRtaPageSelection(for:)`. */
        fun pageStart(bridge: DspiBridge, eqCh: Int, showSpectrum: Boolean): RtaChannelSelection {
            val tap = if (bridge.isInputChannel(eqCh)) DspiConstants.RTA_TAP_INPUT else DspiConstants.RTA_TAP_OUTPUT
            val index = bridge.rta.tapIndex(eqCh)
            return RtaChannelSelection(tap, if (showSpectrum && index >= 0) listOf(index) else emptyList())
        }
    }
}

@Composable
internal fun rememberSpectrumSelection(bridge: DspiBridge, store: GraphSettingsStore, activeEq: Int?): SpectrumSelectionState {
    // Recompose when the analyser's caps or the settings change.
    bridge.rta.caps.collectAsState().value
    store.settings.collectAsState().value
    val page = remember(activeEq) {
        mutableStateOf(
            if (activeEq !=
                null
            ) {
                SpectrumSelectionState.pageStart(bridge, activeEq, store.settings.value.rtaChannelPagesShowSpectrum)
            } else {
                RtaChannelSelection.NONE
            }
        )
    }
    val pageOther = remember(activeEq) { mutableStateOf<RtaChannelSelection?>(null) }
    return SpectrumSelectionState(store, activeEq, { bridge.rta.availableChannels(it) }, page, pageOther)
}

/** The gear's panel: SPECTRUM channels, FFT Graph, and Graph Setup. */
@Composable
internal fun GraphOptions(bridge: DspiBridge, onDashboard: Boolean, spectrum: SpectrumSelectionState, onDismiss: () -> Unit) {
    val store = LocalGraphSettings.current
    val settings by store.settings.collectAsState()
    val caps by bridge.rta.caps.collectAsState()
    var setupPage by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 6.dp) {
            Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                if (setupPage) {
                    GraphSetupPage(settings, store) { setupPage = false }
                    return@Column
                }
                Text(
                    if (onDashboard) "Graph options for the dashboard" else "Graph options for this channel page",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp)
                )
                val c = caps
                if (c != null && c.supported && bridge.connected) {
                    SpectrumChannelSection(bridge, spectrum)
                    HorizontalDivider()
                    ToggleRow("FFT Graph", settings.rtaShowsGraph(onDashboard)) { on ->
                        store.update { it.withRtaShowsGraph(onDashboard, on) }
                    }
                } else {
                    Text(
                        if (bridge.connected) "This firmware has no spectrum analyser." else "Connect a DSPi to show its spectrum.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().clickable { setupPage = true }.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Graph Setup", modifier = Modifier.weight(1f))
                    Text("›", fontSize = 18.sp)
                }
            }
        }
    }
}

@Composable
private fun SpectrumChannelSection(bridge: DspiBridge, spectrum: SpectrumSelectionState) {
    val selection = spectrum.value
    val channels = bridge.rta.availableChannels(selection.tap)
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "SPECTRUM",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            SingleChoiceSegmentedButtonRow {
                val taps = listOf(DspiConstants.RTA_TAP_INPUT to "Inputs", DspiConstants.RTA_TAP_OUTPUT to "Outputs")
                for ((i, t) in taps.withIndex()) {
                    SegmentedButton(
                        selected = selection.tap == t.first,
                        onClick = { spectrum.switchSide(t.first) },
                        shape = SegmentedButtonDefaults.itemShape(i, taps.size)
                    ) { Text(t.second, fontSize = 11.sp) }
                }
            }
        }
        if (channels.isEmpty()) {
            Text(if (selection.tap == DspiConstants.RTA_TAP_INPUT) "No active inputs." else "No enabled outputs.", fontSize = 11.sp)
        } else {
            // Two equal columns, so chips line up whatever the names.
            for (pair in channels.chunked(2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (ch in pair) {
                        val on = ch in selection.channels
                        val app = bridge.rta.appChannel(selection.tap, ch)
                        val color = channelColour(bridge, app)
                        Row(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (on) color.copy(alpha = 0.15f) else Color.Transparent)
                                .border(1.dp, if (on) color.copy(alpha = 0.6f) else Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                .clickable { spectrum.set(selection.toggled(ch)) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(color.copy(alpha = if (on) 1f else 0.4f)))
                            Text(bridge.channelName(app), fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                spectrumSummary(selection),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (!selection.isEmpty) {
                TextButton(onClick = { spectrum.set(RtaChannelSelection(selection.tap, emptyList())) }) { Text("Clear", fontSize = 10.sp) }
            }
        }
    }
}

/** "Spectrum hidden", "1 channel", "N channels". */
fun spectrumSummary(selection: RtaChannelSelection): String = when (selection.channels.size) {
    0 -> "Spectrum hidden"
    1 -> "1 channel"
    else -> "${selection.channels.size} channels"
}

@Composable
private fun ToggleRow(title: String, on: Boolean, enabled: Boolean = true, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), color = if (enabled) Color.Unspecified else Color.Gray)
        Switch(checked = on, onCheckedChange = set, enabled = enabled)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun ChoiceRow(title: String, options: List<Double>, value: Double, label: (Double) -> String, set: (Double) -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(title, fontSize = 12.sp)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            for ((i, o) in options.withIndex()) {
                SegmentedButton(selected = o == value, onClick = { set(o) }, shape = SegmentedButtonDefaults.itemShape(i, options.size)) {
                    Text(label(o), fontSize = 10.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SliderRow(title: String, value: Double, range: ClosedFloatingPointRange<Float>, steps: Int = 0, set: (Double) -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp)) {
        Text(title, fontSize = 12.sp)
        Slider(value = value.toFloat(), onValueChange = { set(it.toDouble()) }, valueRange = range, steps = steps)
    }
}

private fun hz(f: Double) = if (f >= 1000) "${(f / 1000).roundToInt()}k" else f.roundToInt().toString()

/** Graph Setup (README "Graph Setup"; Settings > Graphing holds the same). */
@Composable
private fun GraphSetupPage(settings: GraphSettings, store: GraphSettingsStore, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onBack).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("‹ Back", fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Text("Graph Setup", fontWeight = FontWeight.Bold)
    }
    HorizontalDivider()
    SectionTitle("SCALE")
    ChoiceRow("Frequency from", GraphSettings.MIN_FREQS, settings.graphMinFreq, ::hz) { v -> store.update { it.copy(graphMinFreq = v) } }
    ChoiceRow("Frequency to", GraphSettings.MAX_FREQS, settings.graphMaxFreq, ::hz) { v -> store.update { it.copy(graphMaxFreq = v) } }
    SliderRow("Range: ${settings.graphDBRange.roundToInt()} dB", settings.graphDBRange, 10f..100f) { v ->
        store.update { it.copy(graphDBRange = v.roundToInt().toDouble()) }
    }
    SliderRow(
        "Center: ${dbLabel(
            settings.graphDBCenter.roundToInt().toDouble()
        )} dB (${dbLabel(settings.dbTop.roundToInt().toDouble())} to ${dbLabel(
            settings.dbBottom.roundToInt().toDouble()
        )} dB)",
        settings.graphDBCenter,
        -40f..20f
    ) { v -> store.update { it.copy(graphDBCenter = v.roundToInt().toDouble()) } }
    TextButton(onClick = { store.update { it.resetScale() } }, modifier = Modifier.padding(start = 4.dp)) { Text("Reset") }
    HorizontalDivider()
    SectionTitle("GRID & LABELS")
    ToggleRow("Frequency Grid", settings.showFrequencyGrid) { on -> store.update { it.copy(showFrequencyGrid = on) } }
    ToggleRow("Frequency Labels", settings.showFrequencyLabels) { on -> store.update { it.copy(showFrequencyLabels = on) } }
    ToggleRow("dB Grid", settings.showDBGrid) { on -> store.update { it.copy(showDBGrid = on) } }
    ToggleRow("dB Labels", settings.showDBLabels) { on -> store.update { it.copy(showDBLabels = on) } }
    ToggleRow("Frequency Readout", settings.showFrequencyReadout) { on -> store.update { it.copy(showFrequencyReadout = on) } }
    ToggleRow("Gain Readout", settings.showLevelReadout) { on -> store.update { it.copy(showLevelReadout = on) } }
    SliderRow("Grid Opacity: ${(settings.graphGridOpacity * 100).roundToInt()}%", settings.graphGridOpacity, 0f..2f) { v ->
        store.update { it.copy(graphGridOpacity = (v * 20).roundToInt() / 20.0) }
    }
    HorizontalDivider()
    SectionTitle("CURVES")
    SliderRow("Line Width: ${String.format(Locale.ROOT, "%.1f", settings.graphLineWidth)} pt", settings.graphLineWidth, 1f..4f) { v ->
        store.update { it.copy(graphLineWidth = (v * 2).roundToInt() / 2.0) }
    }
    ToggleRow("Glow", settings.showGraphGlow) { on -> store.update { it.copy(showGraphGlow = on) } }
    ToggleRow("Phase Response", settings.showPhase) { on -> store.update { it.copy(showPhase = on) } }
    ToggleRow("Unwrap Phase", settings.phaseUnwrapped, enabled = settings.showPhase) { on -> store.update { it.copy(phaseUnwrapped = on) } }
    // "Follow Channel Selection" (pop-out only) has no counterpart: GraphScreen
    // picks the channel it edits itself (docs/UPSTREAM-DEVIATIONS.md GRAPH).
    Spacer(Modifier.size(12.dp))
}
