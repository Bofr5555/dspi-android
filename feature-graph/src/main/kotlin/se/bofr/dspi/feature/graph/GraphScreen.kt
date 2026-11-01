// The pop-out graph window (DSPi-Console-b5:DSPi Console/GraphView.swift
// BodePlotView(isPopOut: true) + GraphLegend, README "Pop-Out Graph Window")
// as a full-screen route: the graph fills the screen (landscape-friendly),
// the legend toggles the curves underneath, and a channel menu picks the
// channel edited on the graph. macOS's "Follow Channel Selection" mirrors the
// main window's selected channel; a phone has one screen, so GraphScreen picks
// its channel itself (docs/UPSTREAM-DEVIATIONS.md GRAPH).
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
    var edited by rememberSaveable { mutableStateOf(-1) }
    var menuOpen by remember { mutableStateOf(false) }
    val choices = remember(stateRev) { editableChannels(bridge) }
    if (edited >= 0 && edited !in choices) edited = -1
    Column(modifier.fillMaxSize().background(Color(0xFF1A1A1C)).padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text("Response Graph", fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
            Box {
                TextButton(onClick = { menuOpen = true }) {
                    Text(if (edited >= 0) "Edit: ${bridge.channelDescriptor(edited)}" else "Edit: none", fontSize = 13.sp)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("None (view only)") }, onClick = {
                        edited = -1
                        menuOpen = false
                    })
                    for (ch in choices) {
                        DropdownMenuItem(
                            text = { Text("${bridge.channelDescriptor(ch)}  ${bridge.channelName(ch)}") },
                            onClick = {
                                edited = ch
                                if (!bridge.channelVisible(ch)) bridge.setChannelVisible(ch, true)
                                menuOpen = false
                            }
                        )
                    }
                }
            }
        }
        BodePlotView(
            bridge = bridge,
            modifier = Modifier.weight(1f).fillMaxSize(),
            channels = if (edited >= 0) setOf(edited) else emptySet(),
            editable = edited >= 0
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
