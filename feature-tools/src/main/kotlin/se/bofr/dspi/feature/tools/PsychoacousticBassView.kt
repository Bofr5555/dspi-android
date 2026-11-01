// Port of DSPi-Console:DSPi Console/PsychoacousticBassView.swift @ v1.1.6-beta5
// (b5fdeac); core driving as Linux qt/qml/PsychoacousticBassWindow.qml
// (setPsybassParam(PSYBASS_PARAM_*, v), setPsybassOutputMask).
package se.bofr.dspi.feature.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.core.DspiConstants

/** The schematic spectrum's band heights. */
object PsybassSpectrum {
    /** Original band height fraction: 0 dB = full, -60 dB = gone. */
    fun originalFrac(originalDB: Float): Float = ((originalDB + 60f) / 60f).coerceIn(0f, 1f)

    /** Harmonics band height fraction: -24 dB = low, +12 dB = full. */
    fun harmonicsFrac(harmonicsDB: Float): Float = ((harmonicsDB + 24f) / 36f).coerceIn(0f, 1f)
}

internal enum class PsybassGraphKey { CUTOFF, HARMONICS_DB, ORIGINAL_DB }

/** PsychoacousticBassView: SPECTRUM + HARMONICS | OUTPUTS + SHAPING. */
@Composable
fun PsychoacousticBassView(bridge: DspiBridge, modifier: Modifier = Modifier) {
    val st by bridge.state.collectAsState()
    val status by bridge.status.collectAsState()
    val connected = status.connected
    val psybass = PsybassParameters.from(st)
    val outputCount = st.numOutputChannels
    val supported = ToolFirmware.supportsPsybass(st.formatVersion)
    val graphLive = rememberGraphLive<PsybassGraphKey>(Triple(psybass.cutoffHz, psybass.harmonicsDB, psybass.originalDB))

    @Composable
    fun paramRow(
        title: String,
        unit: String,
        value: Float,
        range: ClosedFloatingPointRange<Float>,
        maxDecimals: Int,
        param: Int,
        liveKey: PsybassGraphKey?,
        help: String
    ) = ParameterRow(
        title = title,
        unit = unit,
        value = value,
        range = range,
        maxDecimals = maxDecimals,
        isEnabled = connected,
        caption = help,
        live = { v ->
            val c = v.coerceIn(range)
            bridge.setPsybassParam(param, c, sendOnly = true)
            if (liveKey != null) graphLive.set(liveKey, c)
        },
        set = { bridge.setPsybassParam(param, it.coerceIn(range)) }
    )

    ToolScaffold(header = {
        ToolHeader(
            title = "Psychoacoustic Bass",
            subtitle = "Phantom fundamental bass enhancement",
            isOn = st.psybassEnabled,
            switchEnabled = connected && supported,
            onToggle = { bridge.setPsybassEnabled(it) }
        )
    }, modifier = modifier) {
        if (!supported) {
            UnsupportedNote("Requires firmware with wire format V23 or newer.", "Update the DSPi firmware to use Psychoacoustic Bass.")
        } else {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ToolColumns(left = {
                    SectionHeader("SPECTRUM") {
                        ToolMenu(
                            "Apply preset",
                            connected,
                            PsybassParameters.startingPoints.map { p ->
                                "${p.name} - ${p.detail}" to {
                                    bridge.setPsybassParam(DspiConstants.PSYBASS_PARAM_CUTOFF, p.cutoff)
                                    bridge.setPsybassParam(DspiConstants.PSYBASS_PARAM_HARMONICS, p.harmonics)
                                    bridge.setPsybassParam(DspiConstants.PSYBASS_PARAM_DRIVE, p.drive)
                                    bridge.setPsybassParam(DspiConstants.PSYBASS_PARAM_CHARACTER, p.character)
                                    bridge.setPsybassParam(DspiConstants.PSYBASS_PARAM_ORIGINAL, p.original)
                                }
                            }
                        )
                    }
                    GraphFrame(180.dp) {
                        PsybassSpectrumView(
                            cutoff = graphLive[PsybassGraphKey.CUTOFF, psybass.cutoffHz],
                            harmonicsDB = graphLive[PsybassGraphKey.HARMONICS_DB, psybass.harmonicsDB],
                            originalDB = graphLive[PsybassGraphKey.ORIGINAL_DB, psybass.originalDB],
                            isEnabled = st.psybassEnabled
                        )
                    }
                    HorizontalDivider()
                    SectionLabel("HARMONICS")
                    paramRow(
                        "Cutoff Frequency",
                        "Hz",
                        psybass.cutoffHz,
                        PsybassParameters.CUTOFF_RANGE,
                        0,
                        DspiConstants.PSYBASS_PARAM_CUTOFF,
                        PsybassGraphKey.CUTOFF,
                        "The speaker's low-frequency limit. Content below this feeds the harmonic generator; generated harmonics " +
                            "span roughly this to 4x."
                    )
                    HorizontalDivider()
                    paramRow(
                        "Harmonics",
                        "dB",
                        psybass.harmonicsDB,
                        PsybassParameters.HARMONICS_RANGE,
                        1,
                        DspiConstants.PSYBASS_PARAM_HARMONICS,
                        PsybassGraphKey.HARMONICS_DB,
                        "Level of the synthesized harmonics. The primary amount-of-effect control. Higher = more perceived bass."
                    )
                }, right = {
                    SectionHeader("OUTPUTS") {
                        ToolMenu(
                            "Presets",
                            connected,
                            listOf(
                                "All outputs" to { bridge.setPsybassOutputMask(ToolMasks.allOutputs(outputCount)) },
                                "Exclude sub (recommended)" to { bridge.setPsybassOutputMask(ToolMasks.excludeSub(outputCount)) },
                                "None" to { bridge.setPsybassOutputMask(0x0000) }
                            )
                        )
                    }
                    Caption(
                        "Enhance only the small-speaker outputs. Mask off the sub and any full-range outputs - synthesizing " +
                            "harmonics on a channel that can reproduce real bass is counterproductive."
                    )
                    ChannelChips(
                        count = outputCount,
                        isOn = { ToolMasks.isOn(psybass.outputMask, it) },
                        names = (0 until outputCount).map { bridge.channelName(2 + it) },
                        enabled = connected,
                        onToggle = { bridge.setPsybassOutputMask(ToolMasks.toggled(psybass.outputMask, it)) }
                    )
                    HorizontalDivider()
                    SectionLabel("SHAPING")
                    paramRow(
                        "Drive",
                        "dB",
                        psybass.driveDB,
                        PsybassParameters.DRIVE_RANGE,
                        1,
                        DspiConstants.PSYBASS_PARAM_DRIVE,
                        null,
                        "Pre-gain into the odd-harmonic soft clipper. Higher makes the effect audible on quieter passages. " +
                            "Mostly affects aggressive character."
                    )
                    HorizontalDivider()
                    ParameterRow(
                        title = "Character",
                        unit = "%",
                        value = psybass.characterPct,
                        range = PsybassParameters.CHARACTER_RANGE,
                        maxDecimals = 0,
                        ends = "Warm" to "Aggressive",
                        isEnabled = connected,
                        live = { bridge.setPsybassParam(DspiConstants.PSYBASS_PARAM_CHARACTER, it.coerceIn(0f, 100f), sendOnly = true) },
                        set = { bridge.setPsybassParam(DspiConstants.PSYBASS_PARAM_CHARACTER, it.coerceIn(0f, 100f)) }
                    )
                    HorizontalDivider()
                    paramRow(
                        "Original Bass",
                        "dB",
                        psybass.originalDB,
                        PsybassParameters.ORIGINAL_RANGE,
                        1,
                        DspiConstants.PSYBASS_PARAM_ORIGINAL,
                        PsybassGraphKey.ORIGINAL_DB,
                        "Level of the un-reproducible fundamental below the cutoff. Lower attenuates it, freeing driver excursion " +
                            "and headroom. -60 dB is full removal. Speaker protection."
                    )
                })
            }
        }
    }
}

/** `PsybassSpectrumView`: a schematic, not a measurement. */
@Composable
internal fun PsybassSpectrumView(cutoff: Float, harmonicsDB: Float, originalDB: Float, isEnabled: Boolean, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val accent = accentColor()
    val secondary = secondaryColor()
    val primary = MaterialTheme.colorScheme.onSurface
    val bg = MaterialTheme.colorScheme.surface
    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        fun x(f: Double) = ToolGraphMath.logX(f, 20.0, 20000.0, w)
        val fcX = x(cutoff.toDouble())
        val fc4X = x(cutoff.toDouble() * 4)
        val labelStrip = 14.dp.toPx()
        val baseline = h - labelStrip
        val grid = Color.Gray.copy(alpha = 0.15f)
        listOf(100, 1000, 10000).forEach { f -> drawLine(grid, Offset(x(f.toDouble()), 0f), Offset(x(f.toDouble()), baseline), 1f) }
        drawLine(grid, Offset(0f, baseline), Offset(w, baseline), 1f)
        if (isEnabled) {
            val origH = baseline * PsybassSpectrum.originalFrac(originalDB)
            drawRect(accent.copy(alpha = 0.35f), Offset(0f, baseline - origH), Size(maxOf(0f, fcX), maxOf(0f, origH)))
            val harmH = baseline * PsybassSpectrum.harmonicsFrac(harmonicsDB)
            drawRect(ToolColors.orange.copy(alpha = 0.55f), Offset(fcX, baseline - harmH), Size(maxOf(0f, fc4X - fcX), maxOf(0f, harmH)))
            drawLine(
                primary.copy(alpha = 0.4f),
                Offset(fcX, 0f),
                Offset(fcX, baseline),
                1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
            )
            labelAt(measurer, "fc", Offset(fcX, 10.dp.toPx()), primary.copy(alpha = 0.6f), 9.sp, bold = true)
            drawLine(
                primary.copy(alpha = 0.2f),
                Offset(fc4X, 0f),
                Offset(fc4X, baseline),
                1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f))
            )
            labelAt(measurer, "4fc", Offset(fc4X, 10.dp.toPx()), primary.copy(alpha = 0.4f), 9.sp, bold = true)
            // Legend
            val box = Size(84.dp.toPx(), 30.dp.toPx())
            val o = Offset(w - box.width - 4.dp.toPx(), 4.dp.toPx())
            drawRect(bg.copy(alpha = 0.85f), o, box)
            listOf("Original" to accent, "Harmonics" to ToolColors.orange).forEachIndexed { i, (label, c) ->
                val cy = o.y + 8.dp.toPx() + i * 13.dp.toPx()
                drawRect(c, Offset(o.x + 6.dp.toPx(), cy - 3.dp.toPx()), Size(10.dp.toPx(), 6.dp.toPx()))
                labelAt(measurer, label, Offset(o.x + 48.dp.toPx(), cy), primary, 9.sp, bold = true, mono = false)
            }
        } else {
            labelAt(measurer, "Disabled", Offset(w / 2, h / 2), secondary.copy(alpha = 0.5f), 13.sp, mono = false)
        }
        listOf(100, 1000, 10000).forEach { f ->
            labelAt(measurer, ToolGraphMath.freqLabel(f), Offset(x(f.toDouble()), h - 6f), secondary.copy(alpha = 0.6f), 9.sp)
        }
    }
}
