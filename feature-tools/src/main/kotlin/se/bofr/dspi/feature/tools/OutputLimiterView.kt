// Port of DSPi-Console:DSPi Console/OutputLimiterView.swift @ v1.1.6-beta5
// (b5fdeac): `LimiterGlyph`/`LimiterIcon`, `LinkGroupSegments` and the
// `OutputLimiterSettings` popover, which on a phone is a full screen (route
// `tools/limiter/{out}`, opened from an output page). Core driving as Linux
// qt/qml/components/LimiterButton.qml (setLimiter*, copyLimiterToAll,
// linkAllLimiterPairs, unlinkAllLimiters, disableAllLimiters). The output
// page's `OutputLimiterCell` (tap toggles, long-press opens this) is HOME's.
package se.bofr.dspi.feature.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.ui.Caption
import se.bofr.dspi.ui.HelpTip
import se.bofr.dspi.ui.MacColors
import se.bofr.dspi.ui.ParameterRow
import se.bofr.dspi.ui.PullDownMenu
import se.bofr.dspi.ui.SegmentedPicker
import se.bofr.dspi.ui.ToolHeader

/**
 * Entry point for [se.bofr.dspi.ui.DspiRoutes.LIMITER] (macOS
 * `OutputLimiterSettings`, the settings popover of output [out]).
 */
@Composable
fun OutputLimiterView(bridge: DspiBridge, out: Int, onNavigate: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val st by bridge.state.collectAsState()
    val status by bridge.status.collectAsState()
    val connected = status.connected
    val outputCount = minOf(st.numOutputChannels, st.limiterEnabled.size)
    val limiter = LimiterParameters.from(st)
    val supported = ToolFirmware.supportsLimiter(st.formatVersion)
    val present = out in 0 until outputCount
    val s = limiter.outputs.getOrElse(out) { LimiterOutputSettings() }
    val reduction = status.limiterReduction.getOrElse(out) { 0f }
    val limiting = LimiterParameters.isLimiting(s.enabled, reduction)
    fun outputName(o: Int) = bridge.channelName(2 + o)

    ToolScaffold(header = {
        ToolHeader(
            title = "Output Limiter",
            subtitle = if (present) outputName(out) else "Output ${out + 1}",
            isOn = s.enabled,
            switchEnabled = connected && supported && present,
            onToggle = { on -> edit(bridge) { bridge.setLimiterEnabled(out, on) } },
            icon = {
                LimiterIcon(
                    color = if (limiting) {
                        MacColors.orange
                    } else if (s.enabled) {
                        accentColor()
                    } else {
                        secondaryColor()
                    },
                    modifier = Modifier.size(24.dp)
                )
            }
        )
    }, modifier = modifier) {
        if (!supported) {
            UnsupportedNote("Requires firmware with wire format V32 or newer.", "Update the DSPi firmware to use the Output Limiter.")
        } else if (!present) {
            UnsupportedNote("This output does not exist on the connected device.", "Open the limiter from an output that is present.")
        } else {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ToolColumn {
                    HelpTip(
                        "A test signal from the Signal Generator is limited like any other signal. Switch this output's limiter off " +
                            "for an unaltered full-scale measurement."
                    ) {
                        Caption(
                            if (s.enabled) {
                                "Output limiter on, ceiling ${ToolFormat.fixed(s.thresholdDB, 1)} dBFS."
                            } else {
                                "Output limiter off."
                            }
                        )
                    }
                    // Greyed out and disabled while off, so an output without a limiter
                    // never looks as if a ceiling were in force.
                    LimiterSettings(bridge, out, s, limiter, outputCount, connected && s.enabled, ::outputName)
                }
            }
        }
    }
}

@Composable
private fun LimiterSettings(
    bridge: DspiBridge,
    out: Int,
    s: LimiterOutputSettings,
    limiter: LimiterParameters,
    outputCount: Int,
    enabled: Boolean,
    outputName: (Int) -> String
) {
    Column(Modifier.alpha(if (s.enabled) 1f else 0.4f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ParameterRow(
            title = "Threshold",
            subtitle = "The ceiling, in dBFS. No sample leaves this output above it.",
            unit = "dB",
            value = s.thresholdDB,
            range = LimiterParameters.THRESHOLD_RANGE,
            maxDecimals = 1,
            ends = "-30 dBFS" to "0 dBFS",
            isEnabled = enabled,
            help = "The limiter runs after every gain stage, so this is the absolute level leaving the device. The -1 dBFS default " +
                "leaves room for the small overshoot a DAC can produce between samples.",
            live = { bridge.setLimiterThreshold(out, it, sendOnly = true) },
            set = { v -> edit(bridge) { bridge.setLimiterThreshold(out, v.coerceIn(LimiterParameters.THRESHOLD_RANGE)) } }
        )
        ParameterRow(
            title = "Release",
            subtitle = "How fast the gain recovers after a peak",
            unit = "ms",
            value = s.releaseMs,
            range = LimiterParameters.RELEASE_RANGE,
            maxDecimals = 0,
            ends = "10 ms" to "1000 ms",
            isEnabled = enabled,
            help = "The gain recovers 8.7 dB per release time. Short releases keep the level up but can be heard pumping on dense " +
                "material; long ones are smoother but hold the level down for longer after a peak. Attack is fixed at 16 samples " +
                "and always completes before the peak arrives.",
            live = { bridge.setLimiterRelease(out, it, sendOnly = true) },
            set = { v -> edit(bridge) { bridge.setLimiterRelease(out, v.coerceIn(LimiterParameters.RELEASE_RANGE)) } }
        )
        HelpTip(
            "Outputs in the same group act as one limiter: they share on/off, threshold and release, so changing one changes them " +
                "all, and each applies the deepest gain reduction any of them needs. A stereo image cannot shift and a pair of woofers " +
                "stays matched. An output joining a group takes on the group's settings; leaving keeps them."
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Link group", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                SegmentedPicker(
                    (0..LimiterParameters.LIMITER_LINK_GROUP_MAX).map { if (it == 0) "Off" else "$it" },
                    (0..LimiterParameters.LIMITER_LINK_GROUP_MAX).toList(),
                    s.linkGroup,
                    enabled,
                    { g -> edit(bridge) { bridge.setLimiterLinkGroup(out, g) } }
                )
                Caption(LimiterParameters.linkSummary(out, s.linkGroup, limiter.outputs, outputCount, outputName))
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HelpTip("Give every output this output's threshold, release and on/off state. Link groups are left as they are.") {
                OutlinedButton(onClick = {
                    edit(bridge) { bridge.copyLimiterToAll(out) }
                }, enabled = enabled) { Text("Copy to all outputs") }
            }
            Spacer(Modifier.weight(1f))
            PullDownMenu(
                "All outputs",
                enabled,
                listOf(
                    "Link all stereo pairs" to { edit(bridge) { bridge.linkAllLimiterPairs() } },
                    "Unlink all outputs" to { edit(bridge) { bridge.unlinkAllLimiters() } },
                    "Switch every limiter off" to { edit(bridge) { bridge.disableAllLimiters() } }
                ),
                dividerBefore = setOf(2)
            )
        }
    }
}

/**
 * OutputLimiterSettings `edit`: every committed change goes through here. In
 * independent mode the limiter is part of the output configuration, so the
 * edit marks it unsaved (and captures the revert baseline) before it lands —
 * `SettingsSaveCoordinator.shared.beginOutputEdit()` on macOS. Live drag
 * values (`sendOnly`) do not, as on macOS.
 */
internal inline fun edit(bridge: DspiBridge, change: () -> Unit) {
    bridge.beginOutputEdit()
    change()
}

/**
 * `LimiterGlyph` + `LimiterIcon`: a gauge with a needle and an arrowhead
 * pointing in at the dial, lines stroked and the hub filled in one colour.
 */
@Composable
internal fun LimiterIcon(color: Color, modifier: Modifier = Modifier, lineWidth: Dp = 1.5.dp) {
    Canvas(modifier) {
        val s = minOf(size.width, size.height)
        val rad = s * 0.33f
        val c = Offset(size.width / 2 + s * 0.07f, size.height / 2 + s * 0.1f)
        fun pt(deg: Float, rr: Float): Offset {
            val a = deg * PI.toFloat() / 180f
            return Offset(c.x + rr * sin(a), c.y - rr * cos(a))
        }
        val p = Path()
        for (i in 0..60) {
            val q = pt(-135f + 270f * i / 60f, rad)
            if (i == 0) p.moveTo(q.x, q.y) else p.lineTo(q.x, q.y)
        }
        p.moveTo(c.x, c.y)
        pt(-40f, rad * 0.72f).let { p.lineTo(it.x, it.y) }
        val tip = pt(-45f, rad * 1.3f)
        val back = pt(-45f, rad * 1.95f)
        val dx = back.x - tip.x
        val dy = back.y - tip.y
        val len = sqrt(dx * dx + dy * dy)
        val ux = dx / len
        val uy = dy / len
        val head = rad * 0.34f
        p.moveTo(tip.x + head * (ux - uy), tip.y + head * (uy + ux))
        p.lineTo(tip.x, tip.y)
        p.lineTo(tip.x + head * (ux + uy), tip.y + head * (uy - ux))
        drawPath(p, color, style = Stroke(lineWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(color, rad * 0.17f, c)
    }
}
