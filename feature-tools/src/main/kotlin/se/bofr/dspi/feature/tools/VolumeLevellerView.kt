// Port of DSPi-Console:DSPi Console/VolumeLevellerView.swift @ v1.1.6-beta5
// (b5fdeac); core driving as Linux qt/qml/VolumeLevellerWindow.qml (input
// count = min(numInputChannels, max(2, activeInputChannels)), names through
// inputAppId). A single column, as the macOS window.
package se.bofr.dspi.feature.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.bridge.MaskField
import se.bofr.dspi.ui.Caption
import se.bofr.dspi.ui.ChannelChips
import se.bofr.dspi.ui.ParameterRow
import se.bofr.dspi.ui.PullDownMenu
import se.bofr.dspi.ui.SectionHeader
import se.bofr.dspi.ui.SectionLabel
import se.bofr.dspi.ui.SegmentedPicker
import se.bofr.dspi.ui.SwitchRow
import se.bofr.dspi.ui.ToolHeader

/** VolumeLevellerView: CHANNELS (multichannel only), PARAMETERS. */
@Composable
fun VolumeLevellerView(bridge: DspiBridge, modifier: Modifier = Modifier) {
    val st by bridge.state.collectAsState()
    val status by bridge.status.collectAsState()
    val connected = status.connected
    val leveller = LevellerParameters.from(st)
    val channelCount = LevellerParameters.channelCount(st.numInputChannels, status.activeInputChannels)
    val showMasks = ToolFirmware.supportsLevellerMasks(st.formatVersion) && LevellerParameters.showMasks(st.numInputChannels, channelCount)
    val names = (0 until channelCount).map { bridge.channelName(bridge.inputAppId(it)) }

    ToolScaffold(header = {
        ToolHeader(
            title = "Volume Leveller",
            subtitle = "Upward Dynamic Range Compression",
            isOn = st.levellerEnabled,
            switchEnabled = connected,
            onToggle = { bridge.setLevellerEnabled(it) }
        )
    }, modifier = modifier) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            ToolColumn {
                if (showMasks) {
                    SectionHeader("CHANNELS") {
                        PullDownMenu(
                            "Presets",
                            connected,
                            LevellerParameters.maskPresets.map { (title, det, app) -> title to { bridge.setLevellerMasks(det, app) } }
                        )
                    }
                    MaskRow("Detector", "sets the shared gain", channelCount, leveller.detectorMask, names, connected) { ch ->
                        bridge.toggleMaskBit(MaskField.LEVELLER_DETECTOR, ch)
                    }
                    MaskRow("Apply", "receives the gain", channelCount, leveller.applyMask, names, connected) { ch ->
                        bridge.toggleMaskBit(MaskField.LEVELLER_APPLY, ch)
                    }
                    HorizontalDivider()
                }

                SectionLabel("PARAMETERS")
                ParameterRow(
                    title = "Amount",
                    unit = "%",
                    value = leveller.amount,
                    range = LevellerParameters.AMOUNT_RANGE,
                    maxDecimals = 1,
                    isEnabled = connected,
                    caption = "Compression strength. Higher values reduce dynamic range more aggressively.",
                    live = { bridge.setLevellerAmount(it.coerceIn(LevellerParameters.AMOUNT_RANGE), sendOnly = true) },
                    set = { bridge.setLevellerAmount(it.coerceIn(LevellerParameters.AMOUNT_RANGE)) }
                )
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Speed", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    SegmentedPicker(listOf("Slow", "Medium", "Fast"), listOf(0, 1, 2), leveller.speed, connected, {
                        bridge.setLevellerSpeed(it)
                    })
                    Caption(LevellerParameters.speedDescription(leveller.speed))
                }
                HorizontalDivider()
                ParameterRow(
                    title = "Max Gain",
                    unit = "dB",
                    value = leveller.maxGainDB,
                    range = LevellerParameters.MAX_GAIN_RANGE,
                    maxDecimals = 1,
                    isEnabled = connected,
                    caption = "Maximum boost for quiet passages. Higher values risk amplifying noise.",
                    live = { bridge.setLevellerMaxGain(it.coerceIn(LevellerParameters.MAX_GAIN_RANGE), sendOnly = true) },
                    set = { bridge.setLevellerMaxGain(it.coerceIn(LevellerParameters.MAX_GAIN_RANGE)) }
                )
                HorizontalDivider()
                ParameterRow(
                    title = "Gate Threshold",
                    unit = "dB",
                    value = leveller.gateDB,
                    range = LevellerParameters.GATE_RANGE,
                    maxDecimals = 1,
                    isEnabled = connected,
                    caption = "Silence gate. Signals below this level are not boosted, preventing noise amplification.",
                    live = { bridge.setLevellerGate(it.coerceIn(LevellerParameters.GATE_RANGE), sendOnly = true) },
                    set = { bridge.setLevellerGate(it.coerceIn(LevellerParameters.GATE_RANGE)) }
                )
                HorizontalDivider()
                SwitchRow(
                    title = "Lookahead",
                    subtitle = "Adds 5ms latency. Improves transient handling.",
                    checked = leveller.lookahead,
                    enabled = connected,
                    onChange = { bridge.setLevellerLookahead(it) }
                )
            }
        }
    }
}

/** VolumeLevellerView `maskRow`: a label, its hint, and one chip per input. */
@Composable
private fun MaskRow(label: String, hint: String, count: Int, mask: Int, names: List<String>, enabled: Boolean, toggle: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Caption(hint)
        }
        ChannelChips(count = count, isOn = { ToolMasks.isOn(mask, it) }, names = names, enabled = enabled, onToggle = toggle)
    }
}
