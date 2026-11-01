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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.ui.SegmentedPicker
import se.bofr.dspi.ui.SwitchRow
import se.bofr.dspi.ui.settings.AppSettings
import se.bofr.dspi.ui.settings.AppSettingsStore
import se.bofr.dspi.ui.settings.LocalAppSettings

/**
 * The spectrum channels of the page showing the graph: the dashboard's are
 * remembered in the settings, a channel page's start as its own channel each
 * time one opens (or nothing, if the user hid the spectrum on channel pages).
 *
 * [value] is snapshot-observable: it reads [settings] (a Compose State
 * mirroring the store) and the page's MutableStates, never the store's
 * StateFlow.value, so whichever composition reads it — the graph, or the
 * gear panel's Dialog, which is a composition of its own — recomposes when
 * the selection changes. The edits ([toggle], [clear], [switchSide]) compute
 * from the store's latest value inside [AppSettingsStore.update], never
 * from a selection read at composition time, so two taps between frames
 * both land (reviews/stale-state-audit.md S1).
 */
class SpectrumSelectionState internal constructor(
    private val store: AppSettingsStore,
    private val settings: State<AppSettings>,
    private val activeEq: Int?,
    private val available: (Int) -> List<Int>,
    private val page: MutableState<RtaChannelSelection>,
    private val pageOther: MutableState<RtaChannelSelection?>
) {
    /** Changes whenever the channel page's own selection does (a key for callers caching [value]). */
    val pageKey: Any? get() = page.value to pageOther.value

    /** macOS `DSPViewModel.rtaSelection` (snapshot-observable; for display). */
    val value: RtaChannelSelection
        get() = selectionIn(settings.value)

    private fun selectionIn(s: AppSettings): RtaChannelSelection {
        if (activeEq != null) return page.value.restricted(available(page.value.tap))
        return dashboardSelection(s, available)
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

    /** A channel chip: show or hide [channel] in the CURRENT selection (atomic against the store). */
    fun toggle(channel: Int) = edit { it.toggled(channel) }

    /** "Clear": hide the spectrum, keeping the current side. */
    fun clear() = edit { RtaChannelSelection(it.tap, emptyList()) }

    private fun edit(change: (RtaChannelSelection) -> RtaChannelSelection) {
        if (activeEq == null) {
            store.update { s -> s.copy(rtaDashboardSelectionKey = change(dashboardSelection(s, available)).storageKey) }
        } else {
            set(change(selectionIn(store.settings.value)))
        }
    }

    /** macOS `switchRtaSide`: show the other side, bringing back what was checked there. */
    fun switchSide(tap: Int) {
        if (activeEq == null) {
            store.update { s ->
                val current = dashboardSelection(s, available)
                if (current.tap == tap) return@update s
                val other =
                    RtaChannelSelection.fromStorageKey(s.rtaDashboardOtherSideKey)?.takeIf { it.tap == tap }
                        ?: RtaChannelSelection(tap, emptyList())
                s.copy(rtaDashboardSelectionKey = other.storageKey, rtaDashboardOtherSideKey = current.storageKey)
            }
        } else {
            if (selectionIn(store.settings.value).tap == tap) return
            val other = pageOther.value?.takeIf { it.tap == tap } ?: RtaChannelSelection(tap, emptyList())
            pageOther.value = page.value
            page.value = other
        }
    }

    companion object {
        /** macOS `dashboardRtaSelection`: the stored channels that are live, else the first output, else input 1. */
        fun dashboardSelection(s: AppSettings, available: (Int) -> List<Int>): RtaChannelSelection {
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
internal fun rememberSpectrumSelection(bridge: DspiBridge, store: AppSettingsStore, activeEq: Int?): SpectrumSelectionState {
    // A channel page starts on its own channel each time one opens.
    val page =
        remember(activeEq) {
            val start =
                if (activeEq != null) {
                    SpectrumSelectionState.pageStart(bridge, activeEq, store.settings.value.rtaChannelPagesShowSpectrum)
                } else {
                    RtaChannelSelection.NONE
                }
            mutableStateOf(start)
        }
    val pageOther = remember(activeEq) { mutableStateOf<RtaChannelSelection?>(null) }
    // A snapshot State mirroring the store, so `value` is observable from any composition.
    val settings = store.settings.collectAsStateWithLifecycle()
    // One object per page; `value` is observable, callers cache it with their device inputs (caps, stateRevision) as keys.
    return remember(bridge, store, settings, activeEq, page, pageOther) {
        SpectrumSelectionState(store, settings, activeEq, { bridge.rta.availableChannels(it) }, page, pageOther)
    }
}

/** The gear's panel: SPECTRUM channels, FFT Graph, and Graph Setup. */
@Composable
internal fun GraphOptions(
    bridge: DspiBridge,
    onDashboard: Boolean,
    spectrum: SpectrumSelectionState,
    onDismiss: () -> Unit,
    inPopOutWindow: Boolean = false
) {
    val store = LocalAppSettings.current
    val settings by store.settings.collectAsStateWithLifecycle()
    val caps by bridge.rta.caps.collectAsStateWithLifecycle()
    // Observed, not the plain `bridge.connected` getter: the Dialog is its own composition (S1).
    val connectedFlow = remember(bridge) { bridge.status.map { it.connected }.distinctUntilChanged() }
    val connected by connectedFlow.collectAsStateWithLifecycle(bridge.connected)
    var setupPage by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 6.dp) {
            Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                if (setupPage) {
                    GraphSetupPage(settings, store, inPopOutWindow) { setupPage = false }
                    return@Column
                }
                Text(
                    if (onDashboard) "Graph options for the dashboard" else "Graph options for this channel page",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp)
                )
                val c = caps
                if (c != null && c.supported && connected) {
                    SpectrumChannelSection(bridge, spectrum)
                    HorizontalDivider()
                    ToggleRow("FFT Graph", settings.rtaShowsGraph(onDashboard)) { on ->
                        store.update { it.withRtaShowsGraph(onDashboard, on) }
                    }
                } else {
                    Text(
                        if (connected) "This firmware has no spectrum analyser." else "Connect a DSPi to show its spectrum.",
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
    // Inside the Dialog's own composition: `spectrum.value` is snapshot-observable, and the
    // device-derived reads (channels, names, colours) are keyed on stateRevision read HERE.
    val stateRev by bridge.stateRevision.collectAsStateWithLifecycle()
    val selection = spectrum.value
    val channels = remember(stateRev, selection.tap) { bridge.rta.availableChannels(selection.tap) }
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
                                .clickable { spectrum.toggle(ch) }
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
                TextButton(onClick = { spectrum.clear() }) { Text("Clear", fontSize = 10.sp) }
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

/** The panel's switch row: ui-common's [SwitchRow] with the panel's insets. */
@Composable
private fun ToggleRow(title: String, on: Boolean, enabled: Boolean = true, set: (Boolean) -> Unit) =
    SwitchRow(title, null, on, enabled, Modifier.padding(horizontal = 12.dp, vertical = 2.dp), onChange = set)

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

/** A titled segmented choice (ui-common's [SegmentedPicker]). */
@Composable
private fun ChoiceRow(title: String, options: List<Double>, value: Double, label: (Double) -> String, set: (Double) -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(title, fontSize = 12.sp)
        SegmentedPicker(options, value, label, set, fontSize = 10.sp)
    }
}

/** ui-common's [SliderRow] with the panel's insets, over a Double preference. */
@Composable
private fun SliderRow(
    title: String,
    value: Double,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    enabled: Boolean = true,
    set: (Double) -> Unit
) = se.bofr.dspi.ui.SliderRow(title, value.toFloat(), range, Modifier.padding(horizontal = 12.dp), steps, enabled) { set(it.toDouble()) }

private fun hz(f: Double) = if (f >= 1000) "${(f / 1000).roundToInt()}k" else f.roundToInt().toString()

/** Graph Setup (README "Graph Setup"; Settings > Graphing holds the same). */
@Composable
private fun GraphSetupPage(settings: AppSettings, store: AppSettingsStore, inPopOutWindow: Boolean, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onBack).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("‹ Back", fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Text("Graph Setup", fontWeight = FontWeight.Bold)
    }
    HorizontalDivider()
    // macOS GraphSetupPage: the SCALE header carries its Reset.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { SectionTitle("SCALE") }
        TextButton(onClick = {
            store.update { it.resetScale() }
        }, modifier = Modifier.padding(end = 4.dp)) { Text("Reset", fontSize = 11.sp) }
    }
    ChoiceRow("Frequency from", AppSettings.MIN_FREQS, settings.graphMinFreq, ::hz) { v -> store.update { it.copy(graphMinFreq = v) } }
    ChoiceRow("Frequency to", AppSettings.MAX_FREQS, settings.graphMaxFreq, ::hz) { v -> store.update { it.copy(graphMaxFreq = v) } }
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
    HorizontalDivider()
    SectionTitle("GRID & LABELS")
    ToggleRow("Frequency Grid", settings.showFrequencyGrid) { on -> store.update { it.copy(showFrequencyGrid = on) } }
    ToggleRow("Frequency Labels", settings.showFrequencyLabels) { on -> store.update { it.copy(showFrequencyLabels = on) } }
    ToggleRow("dB Grid", settings.showDBGrid) { on -> store.update { it.copy(showDBGrid = on) } }
    ToggleRow("dB Labels", settings.showDBLabels) { on -> store.update { it.copy(showDBLabels = on) } }
    ToggleRow("Frequency Readout", settings.showFrequencyReadout) { on -> store.update { it.copy(showFrequencyReadout = on) } }
    ToggleRow("Gain Readout", settings.showLevelReadout) { on -> store.update { it.copy(showLevelReadout = on) } }
    // Continuous, as macOS; meaningless with both grids off.
    SliderRow(
        "Grid Opacity: ${(settings.graphGridOpacity * 100).roundToInt()}%",
        settings.graphGridOpacity,
        0f..2f,
        enabled = settings.showFrequencyGrid || settings.showDBGrid
    ) { v -> store.update { it.copy(graphGridOpacity = v) } }
    HorizontalDivider()
    SectionTitle("CURVES")
    SliderRow("Line Width: ${String.format(Locale.ROOT, "%.1f", settings.graphLineWidth)} pt", settings.graphLineWidth, 1f..4f) { v ->
        store.update { it.copy(graphLineWidth = (v * 2).roundToInt() / 2.0) }
    }
    ToggleRow("Glow", settings.showGraphGlow) { on -> store.update { it.copy(showGraphGlow = on) } }
    ToggleRow("Phase Response", settings.showPhase) { on -> store.update { it.copy(showPhase = on) } }
    ToggleRow("Unwrap Phase", settings.phaseUnwrapped, enabled = settings.showPhase) { on -> store.update { it.copy(phaseUnwrapped = on) } }
    if (inPopOutWindow) {
        ToggleRow("Follow Channel Selection", settings.popoutGraphFollowsSelection) { on ->
            store.update { it.copy(popoutGraphFollowsSelection = on) }
        }
    }
    Spacer(Modifier.size(12.dp))
}
