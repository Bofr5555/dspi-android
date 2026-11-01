package se.bofr.dspi.feature.graph

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import se.bofr.dspi.bridge.DspiBridge

/**
 * The response graph as an embeddable composable (macOS GraphView.swift),
 * used by :feature-home's channel page and dashboard as well as by
 * [GraphScreen]. This signature is a cross-package contract (HOME consumes
 * it): keep it stable.
 *
 * @param channels app channel ids to draw (empty = the visible set,
 *   [DspiBridge.channelVisible]).
 * @param editable enables on-graph PEQ editing (PeqGraphEditor) for the
 *   single channel in [channels].
 * WAVE 0 placeholder; package GRAPH implements it.
 */
@Composable
fun GraphView(
    bridge: DspiBridge,
    modifier: Modifier = Modifier,
    channels: Set<Int> = emptySet(),
    editable: Boolean = false,
) {
    Box(modifier.fillMaxWidth().height(200.dp)) {
        Text("Graph (${if (channels.isEmpty()) "visible channels" else channels.joinToString()}${if (editable) ", editable" else ""})")
    }
}
