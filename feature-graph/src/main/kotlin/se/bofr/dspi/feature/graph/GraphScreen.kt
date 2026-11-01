// The pop-out graph window (DSPi-Console-b5:DSPi Console/GraphView.swift
// BodePlotView(isPopOut: true) + GraphLegend, README "Pop-Out Graph Window")
// as a full-screen route: the graph fills the screen (landscape-friendly) and
// the legend toggles the curves underneath. With "Follow Channel Selection"
// (AppSettings.popoutGraphFollowsSelection, Graph Setup) the edited channel is
// the main window's selection (:ui-common AppState, macOS activeEqChannel);
// without it a channel menu picks the channel edited here
// (docs/UPSTREAM-DEVIATIONS.md GRAPH).
package se.bofr.dspi.feature.graph

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.bofr.dspi.bridge.ChannelMap
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.ui.DspiRoutes
import se.bofr.dspi.ui.settings.LocalAppSettings
import se.bofr.dspi.ui.state.LocalAppState

/** The channels the graph can edit: the legend's pills (active inputs, enabled outputs). */
internal fun editableChannels(bridge: DspiBridge): List<Int> = (0 until ChannelMap.APP_CHANNEL_COUNT).filter { ch ->
    bridge.channelExists(ch) &&
        if (bridge.isInputChannel(ch)) {
            (0 until bridge.liveInputCount()).any { bridge.inputAppId(it) == ch }
        } else {
            val out = bridge.rta.tapIndex(ch)
            out >= 0 && bridge.outputEnabled(out)
        }
}

/**
 * Entry point of :feature-graph (route [DspiRoutes.GRAPH]).
 * The signature is the contract with :app — keep it.
 */
@Composable
fun GraphScreen(bridge: DspiBridge, onNavigate: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val stateRev by bridge.stateRevision.collectAsStateWithLifecycle()
    val settings by LocalAppSettings.current.settings.collectAsStateWithLifecycle()
    val selected by LocalAppState.current.activeEqChannel.collectAsStateWithLifecycle()
    val follows = settings.popoutGraphFollowsSelection
    var picked by rememberSaveable { mutableStateOf(-1) }
    var menuOpen by remember { mutableStateOf(false) }
    val liveInputs = bridge.observeLiveInputs()
    val choices = remember(stateRev, liveInputs) { editableChannels(bridge) }
    if (picked >= 0 && picked !in choices) picked = -1
    val edited = if (follows) selected?.takeIf { it in choices } ?: -1 else picked
    // Plain getters read here, keyed on the observed revision, and handed to the button and the
    // menu as values: they follow a rename by value, not by the identity of `choices`
    // (review-stalestate #7, audit N4).
    val labels =
        remember(stateRev, choices) {
            choices.associateWith { "${bridge.channelDescriptor(it)}  ${bridge.channelName(it)}" }
        }
    val editedDescriptor = remember(stateRev, edited) { if (edited >= 0) bridge.channelDescriptor(edited) else null }
    Column(modifier.fillMaxSize().background(Color(0xFF1A1A1C)).padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text("Response Graph", fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
            if (follows) {
                Text(
                    if (editedDescriptor != null) "Following: $editedDescriptor" else "Following: overview",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            } else {
                Box {
                    TextButton(onClick = { menuOpen = true }) {
                        Text(if (editedDescriptor != null) "Edit: $editedDescriptor" else "Edit: none", fontSize = 13.sp)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("None (view only)") }, onClick = {
                            picked = -1
                            menuOpen = false
                        })
                        for (ch in choices) {
                            DropdownMenuItem(
                                text = { Text(labels[ch].orEmpty()) },
                                onClick = {
                                    picked = ch
                                    if (!bridge.channelVisible(ch)) bridge.setChannelVisible(ch, true)
                                    menuOpen = false
                                }
                            )
                        }
                    }
                }
            }
        }
        BodePlotView(
            bridge = bridge,
            modifier = Modifier.weight(1f).fillMaxSize(),
            channels = if (edited >= 0) setOf(edited) else emptySet(),
            editable = edited >= 0,
            isPopOut = true
        )
        GraphLegend(bridge, Modifier.fillMaxWidth())
        if (edited >= 0) {
            Text(
                "Open channel page",
                fontSize = 12.sp,
                color = Color(0xFF7AA7FF),
                modifier = Modifier.padding(top = 4.dp).clickable { onNavigate(DspiRoutes.channel(edited)) }
            )
        }
    }
}
