// Port of DSPi-Console:DSPi Console/UpmixerView.swift @ v1.1.6-beta5 (b5fdeac);
// core driving as Linux qt/qml/UpmixerWindow.qml (setUpmixParam(UPMIX_PARAM_*, v),
// fetchUpmixStatus polled while the view is up; macOS polls at ~16 Hz).
package se.bofr.dspi.feature.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.core.UpmixStatus

/** Telemetry poll period while the view is up (macOS: ~16 Hz). */
internal const val UPMIX_STATUS_POLL_MS = 60L

/** UpmixerView: STATUS, ENGINES, CENTRE, SURROUND, ROUTING. A single column. */
@Composable
fun UpmixerView(bridge: DspiBridge, modifier: Modifier = Modifier) {
    val st by bridge.state.collectAsState()
    val status by bridge.status.collectAsState()
    val connected = status.connected
    val upmix = UpmixParameters.from(st)
    val supported = ToolFirmware.supportsUpmixer(st.formatVersion, st.platformId)

    var telemetry by remember { mutableStateOf<UpmixStatus?>(null) }
    LaunchedEffect(supported, connected) {
        telemetry = null
        while (isActive && supported && connected) {
            telemetry = bridge.fetchUpmixStatus()
            delay(UPMIX_STATUS_POLL_MS)
        }
        telemetry = null
    }

    @Composable
    fun paramRow(
        title: String,
        unit: String,
        value: Float,
        range: ClosedFloatingPointRange<Float>,
        maxDecimals: Int,
        id: Int,
        help: String
    ) = ParameterRow(
        title = title,
        unit = unit,
        value = value,
        range = range,
        maxDecimals = maxDecimals,
        isEnabled = connected,
        caption = help,
        live = { bridge.setUpmixParam(id, it.coerceIn(range), sendOnly = true) },
        set = { bridge.setUpmixParam(id, it.coerceIn(range)) }
    )

    ToolScaffold(header = {
        ToolHeader(
            title = "Stereo Upmixer",
            subtitle = "Derive Centre and Surround from stereo",
            isOn = upmix.enabled,
            switchEnabled = connected && supported,
            onToggle = { bridge.setUpmixParam(DspiConstants.UPMIX_PARAM_ENABLED, if (it) 1f else 0f) }
        )
    }, modifier = modifier) {
        if (!supported) {
            UnsupportedNote(
                "Requires an RP2350 device with firmware wire format V25 or newer.",
                "The upmixer runs on stereo input at 48 kHz or below."
            )
        } else {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ToolColumn {
                    SectionLabel("STATUS")
                    UpmixStatusPane(connected, upmix.centreOff, upmix.surroundOn, telemetry)
                    HorizontalDivider()

                    SectionLabel("ENGINES")
                    EngineRow("Centre", UpmixParameters.CENTER_SEGMENTS, upmix.centerMode, connected) {
                        bridge.setUpmixParam(DspiConstants.UPMIX_PARAM_CENTER_MODE, it.toFloat())
                    }
                    EngineRow("Surround", UpmixParameters.SURROUND_SEGMENTS, upmix.surroundMode, connected) {
                        bridge.setUpmixParam(DspiConstants.UPMIX_PARAM_SURROUND_MODE, it.toFloat())
                    }
                    Caption(
                        "Logician centre gates extraction on running L/R correlation; Logician surround uses a Pro Logic II-style " +
                            "matrix decoder. Sinner modes are fixed (C = 0.7071(L+R), surround = L-R) - a Hafler-style passive " +
                            "matrix like the one in the Schiit Syn."
                    )

                    // An engine that is off has nothing to configure: its whole block goes.
                    if (!upmix.centreOff) {
                        HorizontalDivider()
                        SectionLabel("CENTRE")
                        paramRow(
                            "Strength",
                            "%",
                            upmix.strengthPct,
                            0f..100f,
                            0,
                            DspiConstants.UPMIX_PARAM_STRENGTH,
                            "Centre extraction strength; scales both the C output and how much centre energy is removed from L/R. In " +
                                "Sinner mode this is the fixed centre gain."
                        )
                        HorizontalDivider()
                        paramRow(
                            "Centre Width",
                            "%",
                            upmix.centerWidthPct,
                            0f..100f,
                            0,
                            DspiConstants.UPMIX_PARAM_WIDTH,
                            "How much extracted centre stays in L/R. 0 = full removal (discrete centre); 100 = L/R untouched (expect " +
                                "combing if a real centre speaker plays)."
                        )
                        HorizontalDivider()
                        paramRow(
                            "Presence",
                            "dB",
                            upmix.presenceDB,
                            -12f..12f,
                            1,
                            DspiConstants.UPMIX_PARAM_PRESENCE,
                            "Voice presence bell at 3 kHz (Q 0.6). Positive brings voices forward, negative pushes them back " +
                                "(Syn-style). Stored in 0.5 dB steps."
                        )
                        // The Logician steering controls only, hidden in Sinner mode.
                        if (upmix.centreAdaptive) {
                            HorizontalDivider()
                            paramRow(
                                "Correlation Threshold",
                                "%",
                                upmix.thresholdPct,
                                0f..95f,
                                0,
                                DspiConstants.UPMIX_PARAM_THRESHOLD,
                                "Correlation gate. Below this, nothing is extracted; above it, extraction scales up to full. Raise to " +
                                    "extract only strongly-correlated content."
                            )
                            HorizontalDivider()
                            paramRow(
                                "Attack",
                                "ms",
                                upmix.attackMs,
                                1f..500f,
                                0,
                                DspiConstants.UPMIX_PARAM_ATTACK,
                                "Centre gain rise time (Logician mode)."
                            )
                            HorizontalDivider()
                            paramRow(
                                "Release",
                                "ms",
                                upmix.releaseMs,
                                5f..2000f,
                                0,
                                DspiConstants.UPMIX_PARAM_RELEASE,
                                "Centre gain fall time (Logician mode)."
                            )
                            HorizontalDivider()
                            paramRow(
                                "Detector HPF",
                                "Hz",
                                upmix.detectorHpfHz,
                                20f..1000f,
                                0,
                                DspiConstants.UPMIX_PARAM_DETECTOR_HPF,
                                "Detector bass-cut corner. Content below this is ignored by the steering detector (the audio itself is " +
                                    "not filtered) so bass does not pump the centre."
                            )
                        }
                    }

                    if (upmix.surroundOn) {
                        HorizontalDivider()
                        SectionLabel("SURROUND")
                        paramRow(
                            "Delay",
                            "ms",
                            upmix.surroundDelayMs,
                            0f..20f,
                            1,
                            DspiConstants.UPMIX_PARAM_SURROUND_DELAY,
                            "Haas delay on Ls/Rs (precedence effect). Rule of thumb ~1 ms per foot of listener distance."
                        )
                        HorizontalDivider()
                        paramRow(
                            "Band-limit HPF",
                            "Hz",
                            upmix.surroundHpfHz,
                            20f..2000f,
                            0,
                            DspiConstants.UPMIX_PARAM_SURROUND_HPF,
                            "Surround high-pass; keeps rumble out of the rears."
                        )
                        HorizontalDivider()
                        paramRow(
                            "Band-limit LPF",
                            "Hz",
                            upmix.surroundLpfHz,
                            1000f..20000f,
                            0,
                            DspiConstants.UPMIX_PARAM_SURROUND_LPF,
                            "Surround low-pass. 7 kHz is the classic surround voicing; raise for full-band rears."
                        )
                        HorizontalDivider()
                        paramRow(
                            "Decorrelation",
                            "%",
                            upmix.decorrPct,
                            0f..100f,
                            0,
                            DspiConstants.UPMIX_PARAM_DECORRELATION,
                            "Schroeder allpass decorrelator amount. 0 disables decorrelation."
                        )
                    }

                    HorizontalDivider()
                    SectionLabel("ROUTING")
                    Caption(
                        "The derived channels appear as matrix source rows: row 2 = Centre, row 3 = Left Surround, row 4 = Right " +
                            "Surround. Open the Matrix Mixer to route them to your output slots (a centre crosspoint gain of -3 dB is " +
                            "a safe start, since the centre row can reach +3 dBFS)."
                    )
                }
            }
        }
    }
}

@Composable
private fun EngineRow(label: String, values: List<Int>, selected: Int, enabled: Boolean, select: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(90.dp), fontSize = 15.sp, fontWeight = FontWeight.Medium)
        SegmentedPicker(UpmixParameters.ENGINE_LABELS, values, selected, enabled, select, Modifier.weight(1f))
    }
}

/**
 * `UpmixStatusPane`: the banner and the four gauges. Gauges that do not apply
 * stay at zero opacity rather than going away, so the height never changes.
 */
@Composable
private fun UpmixStatusPane(connected: Boolean, centreOff: Boolean, surroundOn: Boolean, t: UpmixStatus?) {
    val active = t?.active == true
    val dot = if (!connected) {
        secondaryColor()
    } else if (active) {
        ToolColors.green
    } else {
        ToolColors.orange
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            Text(
                UpmixParameters.statusText(connected, active, t?.parkedReason ?: UpmixParameters.UPMIX_PARKED_DISABLED),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (active) MaterialTheme.colorScheme.onSurface else secondaryColor()
            )
        }
        val corr = t?.correlation ?: 0f
        TelemetryGauge("Correlation", (corr + 1) / 2, ToolFormat.signed(corr, 2), accentColor(), active)
        TelemetryGauge(
            "Centre gain",
            t?.centerGain ?: 0f,
            ToolFormat.fixed((t?.centerGain ?: 0f) * 100, 0) + "%",
            ToolColors.green,
            active && !centreOff
        )
        TelemetryGauge(
            "Ls gain",
            t?.lsGain ?: 0f,
            ToolFormat.fixed((t?.lsGain ?: 0f) * 100, 0) + "%",
            ToolColors.purple,
            active && surroundOn
        )
        TelemetryGauge(
            "Rs gain",
            t?.rsGain ?: 0f,
            ToolFormat.fixed((t?.rsGain ?: 0f) * 100, 0) + "%",
            ToolColors.pink,
            active && surroundOn
        )
    }
}

@Composable
private fun TelemetryGauge(label: String, value: Float, display: String, color: Color, visible: Boolean) {
    Row(
        Modifier.alpha(if (visible) 1f else 0f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, Modifier.width(92.dp), fontSize = 13.sp, color = secondaryColor())
        Box(Modifier.weight(1f)) { LevelBar(value, color) }
        Spacer(Modifier.width(2.dp))
        Text(
            display,
            Modifier.width(52.dp),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            color = secondaryColor()
        )
    }
}
