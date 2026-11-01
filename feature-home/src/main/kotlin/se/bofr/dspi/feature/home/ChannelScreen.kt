// Phone page for one channel: the right pane of DSPi-Console-b5:DSPi
// Console/ContentView.swift @ b5fdeac with a channel selected (graph soloed
// on the channel, then InputChannelHeader + FilterListView or
// OutputChannelDetail).
package se.bofr.dspi.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.feature.graph.GraphResizeHandle
import se.bofr.dspi.feature.graph.GraphView
import se.bofr.dspi.feature.graph.LocalPeqGraphSelection
import se.bofr.dspi.feature.graph.PeqGraphSelection
import se.bofr.dspi.ui.ChannelPalette
import se.bofr.dspi.ui.DspiIcons
import se.bofr.dspi.ui.DspiRoutes
import se.bofr.dspi.ui.DspiTheme
import se.bofr.dspi.ui.DspiType
import se.bofr.dspi.ui.state.LocalAppState

/**
 * Entry point for [DspiRoutes.CHANNEL]: one channel's page. [ch] is an APP
 * channel id. Losing the device or the output being disabled returns to
 * home, as macOS falls back to the overview.
 */
@Composable
fun ChannelScreen(bridge: DspiBridge, ch: Int, onNavigate: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val c = DspiTheme.colors
    val s = bridge.observeState()
    val connected = bridge.observeStatus { it.connected }
    val map = s.channelMap
    val input = map.inputOf(ch)
    val output = map.outputOf(ch)
    val exists = connected && (input >= 0 || (output >= 0 && s.outputEnabled.getOrElse(output) { false }))
    var left by remember { mutableStateOf(false) }
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(exists) {
        if (exists) {
            seen = true
        } else if (seen && !left) {
            left = true
            onBack()
        }
    }
    val color = if (input >= 0) ChannelPalette.input(input) else ChannelPalette.output(output, output == s.numOutputChannels - 1)
    // The main window's selection (macOS updateSelection(to:)): the pop-out graph and the matrix read it
    val appState = LocalAppState.current
    LaunchedEffect(ch) { appState.updateSelection(ch) }
    // One band selection per channel page, shared by the graph and the band list (macOS peqSelection, reset per page)
    val graphSelection = remember(ch) { PeqGraphSelection() }
    val tabState = rememberSaveable(ch) { mutableStateOf(OutputChannelTab.PEQ) }

    Column(modifier.fillMaxSize().background(c.mainContent).verticalScroll(rememberScrollState())) {
        FirmwareMismatchBanner(bridge, onUpdate = { onNavigate(DspiRoutes.FIRMWARE) })
        Row(Modifier.fillMaxWidth().padding(end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(DspiIcons.chevronLeft, contentDescription = "Back", tint = c.accent) }
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Spacer(Modifier.size(8.dp))
            Text(
                bridge.channelName(ch).ifEmpty { bridge.channelDescriptor(ch).ifEmpty { "Channel $ch" } },
                style = DspiType.headline,
                color = c.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(bridge.channelDescriptor(ch), style = DspiType.captionBold, color = color)
        }
        if (!exists) {
            Text(
                if (connected) "This channel is not available on the connected device." else "No device connected.",
                color = c.secondaryLabel,
                modifier = Modifier.padding(16.dp)
            )
            return@Column
        }
        CompositionLocalProvider(LocalPeqGraphSelection provides graphSelection) {
            // No on-graph editing while the XO tab lists the crossover bands (macOS editedChannel, crossoverTabShown)
            val editable = input >= 0 || tabState.value != OutputChannelTab.CROSSOVER
            GraphView(bridge, Modifier.fillMaxWidth().padding(horizontal = 16.dp), channels = setOf(ch), editable = editable)
            GraphResizeHandle(Modifier.padding(horizontal = 16.dp))
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (input >= 0) {
                    InputChannelDetail(bridge, ch)
                } else {
                    OutputChannelDetail(bridge, output, onNavigate, tabState = tabState)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
