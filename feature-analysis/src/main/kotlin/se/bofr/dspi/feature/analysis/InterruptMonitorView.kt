// Port of DSPi-Console-b5:DSPi Console/InterruptMonitor.swift @ b5fdeac
// (InterruptMonitorView: Pause/Resume, Clear, Listening/Paused/Inactive, event
// count, monospaced log following the newest line) over the bridge's
// MonitorModel, as DSPi-Console-Linux:qt/qml/InterruptMonitorWindow.qml @
// 4683a2c drives it (watching while shown; its Copy button, since a lazy
// list's text cannot be selected as one block on Android). The log is
// read-only: nothing here writes to the device.
package se.bofr.dspi.feature.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.bridge.MonitorLine
import se.bofr.dspi.ui.MacColors
import se.bofr.dspi.ui.WhileVisible

@Composable
fun InterruptMonitorView(bridge: DspiBridge, modifier: Modifier = Modifier) {
    val monitor = bridge.monitor
    val bridgeStatus by bridge.status.collectAsState()
    val lines by monitor.lines.collectAsState()
    var paused by remember { mutableStateOf(monitor.paused) }
    WhileVisible(key = monitor, onShow = { monitor.watching = true }, onHide = { monitor.watching = false })
    val clipboard = LocalClipboardManager.current
    InterruptMonitorContent(
        connected = bridgeStatus.connected,
        paused = paused,
        lines = lines,
        onTogglePause = {
            monitor.paused = !monitor.paused
            paused = monitor.paused
        },
        onClear = { monitor.clear() },
        onCopy = { clipboard.setText(AnnotatedString(monitor.allText())) },
        modifier = modifier
    )
}

@Composable
fun InterruptMonitorContent(
    connected: Boolean,
    paused: Boolean,
    lines: List<MonitorLine>,
    onTogglePause: () -> Unit,
    onClear: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onTogglePause) { Text(InterruptMonitorFormat.pauseLabel(paused)) }
            OutlinedButton(onClick = onClear) { Text("Clear") }
            OutlinedButton(onClick = onCopy, enabled = lines.isNotEmpty()) { Text("Copy") }
            Box(Modifier.weight(1f))
            val listening = InterruptMonitorFormat.listening(connected, paused)
            Text(
                listening.label,
                fontSize = 12.sp,
                color =
                when (listening) {
                    MonitorListening.INACTIVE -> secondaryText()
                    MonitorListening.PAUSED -> MacColors.orange
                    MonitorListening.LISTENING -> MacColors.green
                }
            )
            Text(
                InterruptMonitorFormat.eventCount(lines.size),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = secondaryText()
            )
        }
        HorizontalDivider()
        run {
            val listState = rememberLazyListState()
            // Follow the newest line unless paused (Swift onChange of the event count)
            LaunchedEffect(lines.size) {
                if (lines.isNotEmpty() && InterruptMonitorFormat.shouldAutoScroll(paused)) listState.animateScrollToItem(lines.size - 1)
            }
            SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(state = listState, contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    items(lines, key = { it.id }) { line ->
                        Text(
                            InterruptMonitorFormat.formattedLine(line),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}
