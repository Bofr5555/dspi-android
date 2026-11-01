// Port of DSPi-Console-b5:DSPi Console/Components.swift (MARK: Channel
// Settings: ChannelSettingsView; MARK: Input Routing Panel: InputRoutingPanel,
// InputRoutingRow) and OutputLimiterView.swift (OutputLimiterCell) @ b5fdeac.
// Linux qt/qml/ChannelSettingsView.qml for the bridge calls.
package se.bofr.dspi.feature.home.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.feature.home.HomeModel
import se.bofr.dspi.feature.home.observeState
import se.bofr.dspi.feature.home.observeStatus
import se.bofr.dspi.ui.ChannelPalette
import se.bofr.dspi.ui.CustomSlider
import se.bofr.dspi.ui.DspiIcons
import se.bofr.dspi.ui.DspiRoutes
import se.bofr.dspi.ui.DspiTheme
import se.bofr.dspi.ui.DspiType
import se.bofr.dspi.ui.HorizontalRule
import se.bofr.dspi.ui.SliderDragState
import se.bofr.dspi.ui.ValueField
import se.bofr.dspi.ui.ValueFormat
import se.bofr.dspi.ui.VerticalRule
import se.bofr.dspi.ui.dspiCard
import se.bofr.dspi.ui.rememberSliderDrag
import se.bofr.dspi.ui.writesAllowed

/**
 * The output channel's settings card (macOS `ChannelSettingsView`): input
 * routing preview, GAIN, DELAY, and MUTE over the output limiter. Wide
 * screens get the macOS single row; phones stack routing + mute/limiter
 * above full-width GAIN and DELAY sliders.
 *
 * Gain: drag -> `sendOutputGainToDevice` (live), release -> `setOutputGain`.
 * Delay: drag -> `sendOutputDelayToDevice`, release -> `setOutputDelay`.
 * Double-tap a slider = the macOS right-click reset to 0.
 */
@Composable
fun ChannelSettingsView(bridge: DspiBridge, outputIndex: Int, onNavigate: (String) -> Unit, modifier: Modifier = Modifier) {
    val s = bridge.observeState()
    val gainDB = s.outputGainDb.getOrElse(outputIndex) { 0f }
    val delayMS = s.outputDelayMs.getOrElse(outputIndex) { 0f }
    val isMuted = s.outputMuted.getOrElse(outputIndex) { false }
    val maxDelay = bridge.maxDelayMs.takeIf { it > 0f } ?: if (bridge.platformName == "RP2040") 42f else 85f

    val gain = rememberSliderDrag(gainDB)
    val delay = rememberSliderDrag(delayMS)

    val gainSection: @Composable (Modifier) -> Unit = { m ->
        SliderSection(
            "GAIN",
            "dB",
            gain,
            -60f..10f,
            live = { bridge.sendOutputGainToDevice(outputIndex, it) },
            commit = { bridge.setOutputGain(outputIndex, it) },
            modifier = m
        )
    }
    val delaySection: @Composable (Modifier) -> Unit = { m ->
        SliderSection(
            "DELAY",
            "ms",
            delay,
            0f..maxDelay,
            live = { bridge.sendOutputDelayToDevice(outputIndex, it) },
            commit = { bridge.setOutputDelay(outputIndex, it.coerceIn(0f, maxDelay)) },
            modifier = m
        )
    }
    val muteColumn: @Composable (Modifier) -> Unit = { m ->
        MuteLimiterColumn(bridge, outputIndex, isMuted, onNavigate, m)
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth >= 600.dp) {
            Row(Modifier.height(IntrinsicSize.Min).dspiCard()) {
                InputRoutingPanel(bridge, outputIndex, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                VerticalRule()
                gainSection(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp))
                VerticalRule()
                delaySection(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp))
                VerticalRule()
                muteColumn(Modifier.width(64.dp).fillMaxHeight())
            }
        } else {
            Column(Modifier.dspiCard()) {
                Row(Modifier.height(IntrinsicSize.Min)) {
                    InputRoutingPanel(bridge, outputIndex, Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp))
                    VerticalRule()
                    muteColumn(Modifier.width(64.dp).fillMaxHeight())
                }
                HorizontalRule(Modifier.fillMaxWidth())
                gainSection(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp))
                HorizontalRule(Modifier.fillMaxWidth())
                delaySection(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
    }
}

/** "GAIN  [-3.0] dB" over a full-width slider (one ChannelSettingsView section). */
@Composable
internal fun SliderSection(
    title: String,
    unit: String,
    drag: SliderDragState,
    range: ClosedFloatingPointRange<Float>,
    live: (Float) -> Unit,
    commit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    fieldWidth: Dp = 64.dp,
    enabled: Boolean = true
) {
    val c = DspiTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = DspiType.captionBold, color = c.secondaryLabel)
            Spacer(Modifier.weight(1f))
            ValueField(label = unit, value = drag.value, width = fieldWidth, enabled = enabled) { drag.set(it, commit) }
        }
        CustomSlider(
            value = drag.value,
            onValueChange = { drag.change(it, live) },
            valueRange = range,
            defaultValue = 0f,
            enabled = enabled,
            onEditingChanged = { drag.editing(it, commit) },
            onReset = { drag.set(0f, commit) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * MUTE over the output limiter (`OutputLimiterCell`): tap the limiter to
 * toggle it, long-press for its settings (macOS: right-click opens the
 * popover; here the TOOLS limiter page).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MuteLimiterColumn(bridge: DspiBridge, outputIndex: Int, isMuted: Boolean, onNavigate: (String) -> Unit, modifier: Modifier) {
    val c = DspiTheme.colors
    val s = bridge.observeState()
    val connected = bridge.observeStatus { it.connected }
    val canWrite = writesAllowed()
    val reduction = bridge.observeStatus { it.limiterReduction.getOrElse(outputIndex) { 0f } }
    val enabled = s.limiterEnabled.getOrElse(outputIndex) { false }
    val limiting = enabled && reduction >= 0.05f
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
        Box(
            Modifier
                .size(44.dp)
                .clickable(enabled = writesAllowed(), onClickLabel = if (isMuted) "Unmute" else "Mute") {
                    bridge.setOutputMute(outputIndex, !isMuted)
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isMuted) DspiIcons.speakerMute else DspiIcons.speaker,
                contentDescription = if (isMuted) "Muted" else "Not muted",
                tint = if (isMuted) c.red else c.secondaryLabel,
                modifier = Modifier.size(22.dp)
            )
        }
        // macOS firmwareSupportsLimiter: without it mute keeps the whole column.
        if (bridge.hardwareCaps.limiterSupported) {
            val desc =
                if (enabled) {
                    "Output limiter on, ceiling ${ValueFormat.fixedPoint(s.limiterThresholdDb.getOrElse(outputIndex) { 0f }, 1)} dBFS. " +
                        "Tap to switch off, long-press for settings."
                } else {
                    "Output limiter off. Tap to switch on, long-press for settings."
                }
            Box(
                Modifier
                    .size(44.dp)
                    .alpha(if (connected) 1f else 0.5f)
                    .combinedClickable(
                        enabled = connected,
                        onClickLabel = desc,
                        onClick = {
                            // G1: read-only keeps the long-press to the settings page, not the toggle
                            if (!canWrite) return@combinedClickable
                            // Part of the output configuration in independent mode (macOS beginOutputEdit, F2).
                            bridge.beginOutputEdit()
                            bridge.setLimiterEnabled(outputIndex, !enabled)
                        },
                        onLongClick = { onNavigate(DspiRoutes.limiter(outputIndex)) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    DspiIcons.gauge,
                    contentDescription = desc,
                    tint = if (limiting) {
                        c.orange
                    } else if (enabled) {
                        c.accent
                    } else {
                        c.secondaryLabel
                    },
                    modifier = Modifier.size(21.dp)
                )
            }
        }
    }
}

/**
 * The matrix routing for one output column: up to the two USB inputs, each
 * `[●] Name  0.00 dB  INV`. Tap the dot/name to (dis)connect; the gain stays
 * editable while disconnected; INV toggles polarity (macOS `InputRoutingPanel`).
 */
@Composable
fun InputRoutingPanel(bridge: DspiBridge, outputIndex: Int, modifier: Modifier = Modifier) {
    val s = bridge.observeState()
    // liveInputCount() follows the polled active-input count.
    val active = bridge.observeStatus { it.activeInputChannels }
    val inputs = remember(active, s.numInputChannels) { minOf(bridge.liveInputCount(), HomeModel.BASE_MATRIX_INPUTS) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (input in 0 until inputs) {
            InputRoutingRow(bridge, input, outputIndex, s.matrixRouting, s.matrixGain, s.matrixInvert)
        }
    }
}

@Composable
private fun InputRoutingRow(
    bridge: DspiBridge,
    input: Int,
    output: Int,
    routing: List<List<Boolean>>,
    gains: List<List<Float>>,
    inverts: List<List<Boolean>>
) {
    val c = DspiTheme.colors
    // Names are plain getters: observe here, the list params may be the same instances (H3).
    bridge.observeState()
    val connected = routing.getOrNull(input)?.getOrNull(output) ?: false
    val inverted = inverts.getOrNull(input)?.getOrNull(output) ?: false
    val liveGain = gains.getOrNull(input)?.getOrNull(output) ?: 0f
    val inputColor = ChannelPalette.input(input)
    val name = bridge.channelName(bridge.inputAppId(input))
    val outputName = bridge.channelName(2 + output).trim().ifEmpty { "Out ${output + 1}" }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .clickable(
                    enabled = writesAllowed(),
                    onClickLabel = if (connected) "Disconnect from $outputName" else "Connect to $outputName"
                ) {
                    bridge.setMatrixRoute(input, output, !connected, liveGain, inverted)
                }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Canvas(Modifier.size(9.dp)) {
                if (connected) {
                    drawCircle(inputColor)
                } else {
                    val w = 1.2.dp.toPx()
                    drawCircle(c.secondaryLabel.copy(alpha = 0.55f), radius = size.minDimension / 2 - w / 2, style = Stroke(w))
                }
            }
            Text(
                name,
                fontSize = 12.sp,
                fontWeight = if (connected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (connected) inputColor else c.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(72.dp)
            )
        }
        ValueField(
            label = "dB",
            value = liveGain,
            width = 52.dp,
            scrollStep = 0.5f,
            maxDecimals = 2,
            modifier = Modifier.alpha(if (connected) 1f else 0.55f),
            // macOS right-click -> 0 dB.
            onReset = { bridge.setMatrixRoute(input, output, connected, 0f, inverted) }
        ) { v -> bridge.setMatrixRoute(input, output, connected, v, inverted) }
        Text(
            "INV",
            fontSize = 10.sp,
            fontWeight = if (inverted) FontWeight.Bold else FontWeight.Medium,
            color = if (inverted) c.orange else c.secondaryLabel.copy(alpha = if (connected) 0.45f else 0.3f),
            modifier =
            Modifier
                .clickable(enabled = connected && writesAllowed(), onClickLabel = if (inverted) "Phase inverted" else "Normal phase") {
                    bridge.setMatrixRoute(input, output, connected, liveGain, !inverted)
                }.padding(8.dp)
        )
    }
}
