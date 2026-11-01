// Port of DSPi-Console-b5:DSPi Console/ContentView.swift @ b5fdeac: the right
// pane's `.input(ch)` case and OutputChannelDetail (OutputChannelTab,
// availableCrossoverTypes). Linux qt/qml/ChannelSettingsView.qml +
// FilterListView.qml drive the same bridge calls.
package se.bofr.dspi.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.core.FilterParams
import se.bofr.dspi.feature.home.components.ChannelSettingsView
import se.bofr.dspi.feature.home.components.FilterListView
import se.bofr.dspi.feature.home.components.InputChannelHeader
import se.bofr.dspi.ui.BandFields
import se.bofr.dspi.ui.FilterType
import se.bofr.dspi.ui.SegmentTab

/** Core band -> presentation fields. */
internal fun FilterParams.toFields(): BandFields = BandFields(FilterType.of(filterType), freq, q, gain, qp, bypass)

/** Write a PEQ band: the Linkwitz Transform carries four parameters, everything else three. */
internal fun DspiBridge.applyPeqBand(ch: Int, band: Int, p: BandFields) {
    if (p.type.isLinkwitzTransform) {
        setLinkwitzTransform(ch, band, p.freq, p.q, p.gain, p.qp)
    } else {
        setFilter(ch, band, p.type.raw, p.freq, p.gain, p.q)
    }
}

/**
 * An input channel's page body: the header (link, preamp, Clear PEQ) and its
 * PEQ list. Edits mirror onto a linked partner inside the bridge (macOS does
 * it at the call site). Inputs never offer the Linkwitz Transform.
 */
@Composable
fun InputChannelDetail(bridge: DspiBridge, ch: Int, modifier: Modifier = Modifier) {
    bridge.observeState() // recompose on stateChanged; the getters read the snapshot
    val bands = bridge.channelBands(ch)?.peq?.map { it.toFields() } ?: emptyList()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        InputChannelHeader(bridge, ch, onClearPEQ = { bridge.clearPeq(ch) })
        FilterListView(
            bands = bands,
            availableTypes = FilterType.availableFilterTypes(includeLinkwitz = false),
            onUpdate = { band, p -> bridge.applyPeqBand(ch, band, p) },
            onBypassToggle = { band, bypass -> bridge.setBandBypass(ch, band, bypass) },
            onSetAllBypass = { bridge.setAllBandsBypass(ch, it, crossover = false) },
            onClear = null // handled by InputChannelHeader's Clear button
        )
    }
}

/** The output page's band-list tab (macOS `OutputChannelTab`). */
enum class OutputChannelTab { PEQ, CROSSOVER }

/**
 * Right-pane content for an output (macOS `OutputChannelDetail`): the
 * settings card, then PEQ | XO tabs switching the band list between PEQ and
 * crossover bands.
 */
@Composable
fun OutputChannelDetail(bridge: DspiBridge, outputIndex: Int, onNavigate: (String) -> Unit, modifier: Modifier = Modifier) {
    bridge.observeState() // recompose on stateChanged; the getters read the snapshot
    var tab by rememberSaveable(outputIndex) { mutableStateOf(OutputChannelTab.PEQ) }
    val ch = 2 + outputIndex
    val bands = bridge.channelBands(ch)
    val tabs =
        listOf(
            SegmentTab("PEQ", tab == OutputChannelTab.PEQ) { tab = OutputChannelTab.PEQ },
            SegmentTab("XO", tab == OutputChannelTab.CROSSOVER) { tab = OutputChannelTab.CROSSOVER }
        )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ChannelSettingsView(bridge, outputIndex, onNavigate)
        when (tab) {
            OutputChannelTab.PEQ ->
                FilterListView(
                    bands = bands?.peq?.map { it.toFields() } ?: emptyList(),
                    availableTypes = FilterType.availableFilterTypes(),
                    tabs = tabs,
                    onUpdate = { band, p -> bridge.applyPeqBand(ch, band, p) },
                    onBypassToggle = { band, bypass -> bridge.setBandBypass(ch, band, bypass) },
                    onSetAllBypass = { bridge.setAllBandsBypass(ch, it, crossover = false) },
                    onClear = { bridge.clearAllBands(ch, crossover = false) }
                )
            OutputChannelTab.CROSSOVER ->
                FilterListView(
                    bands = bands?.xover?.map { it.toFields() } ?: emptyList(),
                    // FLAT is included so a band can be disarmed by picking "Off".
                    availableTypes = FilterType.crossoverTypes,
                    tabs = tabs,
                    isCrossoverMode = true,
                    onUpdate = { band, p -> bridge.setCrossover(ch, band, p.type.raw, p.freq) },
                    onBypassToggle = { band, bypass -> bridge.setCrossoverBypass(ch, band, bypass) },
                    onSetAllBypass = { bridge.setAllBandsBypass(ch, it, crossover = true) },
                    onClear = { bridge.clearAllBands(ch, crossover = true) }
                )
        }
    }
}
