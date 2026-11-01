// Port of DSPi-Console-b5:DSPi Console/TestSignalsView.swift @ b5fdeac
// (TestSignalsView: header + status pill, SIGNAL tiles, OUTPUTS chips, LEVEL,
// PARAMETERS, TIMING, OPTIONS, transport bar), driven the way
// DSPi-Console-Linux:qt/qml/SignalGeneratorWindow.qml @ 4683a2c drives the
// core (bridge `siggen` controller: draft, live re-apply, start/stop).
// Deviations (docs/UPSTREAM-DEVIATIONS.md, ANALYSIS): the generator is stopped
// (with its fade) when this view leaves the screen; one column on phones.
package se.bofr.dspi.feature.analysis

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.core.SiggenCaps
import se.bofr.dspi.core.SiggenConfig
import se.bofr.dspi.core.SiggenStatus
import se.bofr.dspi.ui.Caption
import se.bofr.dspi.ui.MacColors
import se.bofr.dspi.ui.SectionLabel
import se.bofr.dspi.ui.SegmentedPicker
import se.bofr.dspi.ui.SwitchRow
import se.bofr.dspi.ui.ValueField
import se.bofr.dspi.ui.WhileVisible
import se.bofr.dspi.ui.writesAllowed

/** Everything the Signal Generator draws (the Swift view's `vm.*` reads). */
data class TestSignalsUiState(
    val connected: Boolean,
    val caps: SiggenCaps?,
    val draft: SiggenConfig,
    val status: SiggenStatus?,
    /** Output names, one per chip (Swift `outputName`). */
    val outputNames: List<String>,
    /** Swift `outputEnabled`: dimmed chips are disabled in the matrix mixer. */
    val outputEnabled: List<Boolean>,
    /** Decision G1: the device accepts writes (false while read-only on a firmware mismatch). */
    val writable: Boolean = true
) {
    val supported: Boolean get() = caps?.supported == true

    /** Swift: the unsupported notice needs a device that answered without a generator. */
    val showUnsupported: Boolean get() = connected && caps != null && !supported
    val controlsEnabled: Boolean get() = connected && supported && writable
    val running: Boolean get() = status.isRunning
    val info: SiggenTypePresentation get() = typeInfo(draft.signalType)
    val desc get() = siggenTypeDesc(caps, draft.signalType)
    val startBlocker: String? get() = siggenStartBlocker(connected, supported, draft, desc)
    val outputCount: Int get() = outputNames.size

    fun outputName(o: Int): String = outputNames.getOrNull(o) ?: "Out ${o + 1}"
}

/** What the view can ask of the generator (the Swift view's actions). */
interface TestSignalsActions {
    fun selectType(id: Int)

    fun selectAll()

    fun selectNone()

    fun cycleOutput(o: Int)

    fun setLevel(db: Float)

    fun setParam(index: Int, value: Float)

    fun setFlag(flag: Int, on: Boolean)

    fun setDurationMs(ms: Long)

    fun setRepeat(count: Int)

    fun setGapMs(ms: Int)

    fun start()

    fun stop(immediate: Boolean)
}

/** The macOS default draft (SiggenController DEFAULT_DRAFT): 1 kHz sine on outputs 1-2 at -20 dBFS. */
internal val DEFAULT_SIGGEN_DRAFT = SiggenConfig(SIGGEN_SINE, 0x0003, 0, 0, -20f, 0, 0, 0, listOf(1000f, 0f, 0f, 0f))

/**
 * The Signal Generator page. Watches the generator's status while shown and
 * stops a playing signal (with its fade) when it leaves the screen; a start
 * is only ever the user's Start button.
 */
@Composable
fun TestSignalsView(bridge: DspiBridge, modifier: Modifier = Modifier) {
    val siggen = bridge.siggen
    val bridgeStatus by bridge.status.collectAsState()

    // Read, so this scope subscribes (an unused `by` delegate never does): names and matrix enables (audit A1)
    bridge.stateRevision.collectAsState().value
    val caps by siggen.caps.collectAsState()
    val draft by siggen.draft.collectAsState()
    val status by siggen.status.collectAsState()

    val connected = bridgeStatus.connected
    val count = caps?.outputChannels?.takeIf { it > 0 } ?: bridge.numOutputChannels
    val state =
        TestSignalsUiState(
            connected = connected,
            caps = caps,
            draft = draft ?: DEFAULT_SIGGEN_DRAFT,
            status = status,
            outputNames = List(count) { o -> bridge.channelName(o + 2).ifEmpty { "Out ${o + 1}" } },
            outputEnabled = List(count) { o -> bridge.outputEnabled(o) },
            writable = writesAllowed()
        )

    val running by rememberUpdatedState(state.running)
    WhileVisible(
        key = siggen,
        onShow = { siggen.watching = true },
        onHide = { configurationChange ->
            siggen.watching = false
            // Safety (ANALYSIS deviation): test signals never keep playing unattended
            if (running && !configurationChange) siggen.stop()
        }
    )

    val actions =
        object : TestSignalsActions {
            override fun selectType(id: Int) = siggen.selectType(id)

            override fun selectAll() = siggen.selectAllOutputs()

            override fun selectNone() = siggen.clearOutputs()

            override fun cycleOutput(o: Int) = siggen.cycleOutput(o)

            override fun setLevel(db: Float) = siggen.setLevelDb(db.coerceIn(SIGGEN_LEVEL_MIN_DB, SIGGEN_LEVEL_MAX_DB))

            override fun setParam(index: Int, value: Float) = siggen.setParam(index, value.toDouble())

            override fun setFlag(flag: Int, on: Boolean) = siggen.setFlag(flag, on)

            override fun setDurationMs(ms: Long) = siggen.setDurationMs(ms)

            override fun setRepeat(count: Int) = siggen.setRepeat(count)

            override fun setGapMs(ms: Int) = siggen.setGapMs(ms)

            override fun start() {
                // E9: the start sequence (config, read-back, START) outlives a rotation
                AnalysisScope.launch { siggen.start() }
            }

            override fun stop(immediate: Boolean) = if (immediate) siggen.stopNow() else siggen.stop()
        }
    TestSignalsContent(state, actions, modifier)
}

@Composable
fun TestSignalsContent(state: TestSignalsUiState, actions: TestSignalsActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        SiggenHeader(state)
        HorizontalDivider()
        if (state.showUnsupported) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Notice(
                    "Signal generator not available",
                    "The connected firmware does not include the onboard test signal generator. Update the DSPi firmware to use this tool."
                )
            }
        } else {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                if (maxWidth >= 720.dp) {
                    // What plays and where on the left, how it plays on the right
                    Row(Modifier.fillMaxSize()) {
                        Column(
                            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            SignalSection(state, actions)
                            HorizontalDivider()
                            OutputsSection(state, actions)
                        }
                        VerticalDivider()
                        Column(
                            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            HowSections(state, actions)
                        }
                    }
                } else {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        SignalSection(state, actions)
                        HorizontalDivider()
                        OutputsSection(state, actions)
                        HorizontalDivider()
                        HowSections(state, actions)
                    }
                }
            }
        }
        HorizontalDivider()
        TransportBar(state, actions)
    }
}

@Composable
private fun HowSections(state: TestSignalsUiState, actions: TestSignalsActions) {
    LevelSection(state, actions)
    ParametersSection(state, actions)
    TimingSection(state, actions)
    HorizontalDivider()
    OptionsSection(state, actions)
}

// ── Header ──

@Composable
private fun SiggenHeader(state: TestSignalsUiState) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Signal Generator", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Caption("Onboard test and measurement signals")
        }
        StatusPill(state)
    }
}

private fun stateColor(state: Int): Color? = when (state) {
    SIGGEN_STATE_FADE_IN, SIGGEN_STATE_RUN -> MacColors.green
    SIGGEN_STATE_GAP -> MacColors.yellow
    SIGGEN_STATE_FADE_OUT -> MacColors.orange
    else -> null
}

@Composable
private fun StatusPill(state: TestSignalsUiState) {
    val s = state.status?.state ?: SIGGEN_STATE_IDLE
    val color = stateColor(s) ?: secondaryText()
    val running = state.running
    val pulse =
        if (running) {
            val t = rememberInfiniteTransition(label = "pill")
            t.animateFloat(0.75f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse").value
        } else {
            0.75f
        }
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .border(1.dp, color.copy(alpha = if (running) 0.5f else 0.2f), shape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Dot(color, 8.dp, Modifier.scale(pulse))
        Text(
            siggenStateLabel(s),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (running) MaterialTheme.colorScheme.onSurface else secondaryText()
        )
    }
}

// ── Signal type grid ──

@Composable
private fun SignalSection(state: TestSignalsUiState, actions: TestSignalsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("SIGNAL")
        for (row in siggenTypeInfos.chunked(5)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (ti in row) {
                    val selected = state.draft.signalType == ti.id
                    SignalTile(ti, selected, state.controlsEnabled, Modifier.weight(1f)) { actions.selectType(ti.id) }
                }
                repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Caption(state.info.blurb)
    }
}

@Composable
private fun SignalTile(ti: SiggenTypePresentation, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(7.dp)
    Column(
        modifier
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .border(1.dp, if (selected) accent.copy(alpha = 0.7f) else Color.Gray.copy(alpha = 0.2f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp)
            .alpha(if (enabled) 1f else 0.5f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SiggenGlyph(ti.id, if (selected) accent else secondaryText(), Modifier.fillMaxWidth().height(22.dp).padding(horizontal = 6.dp))
        Text(
            ti.tileLabel,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) accent else secondaryText(),
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
    }
}

// ── Outputs ──

@Composable
private fun OutputsSection(state: TestSignalsUiState, actions: TestSignalsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("OUTPUTS", Modifier.weight(1f))
            PlainButton("All", state.controlsEnabled) { actions.selectAll() }
            PlainButton("None", state.controlsEnabled) { actions.selectNone() }
        }
        for (row in (0 until state.outputCount).chunked(3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (o in row) OutputChip(state, o, Modifier.weight(1f)) { actions.cycleOutput(o) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Caption(
            "Tap to select, tap again to invert polarity (ø). Dimmed outputs are disabled in the matrix mixer and stay silent."
        )
    }
}

@Composable
private fun OutputChip(state: TestSignalsUiState, o: Int, modifier: Modifier, onClick: () -> Unit) {
    val os = siggenOutputState(state.draft, o)
    val selected = os != SiggenOutputState.OFF
    val matrixEnabled = state.outputEnabled.getOrElse(o) { true }
    val walkActive = state.running && state.status?.activeChannel == o
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier
            .alpha(if (matrixEnabled) 1f else 0.4f)
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .border(
                if (walkActive) 1.5.dp else 1.dp,
                when {
                    walkActive -> MacColors.green
                    selected -> accent.copy(alpha = 0.6f)
                    else -> Color.Gray.copy(alpha = 0.2f)
                },
                shape
            )
            .clickable(enabled = state.controlsEnabled, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            state.outputName(o),
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSurface else secondaryText(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (os == SiggenOutputState.INVERTED) {
            Spacer(Modifier.width(4.dp))
            Text("ø", fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = MacColors.orange)
        }
    }
}

// ── Level ──

@Composable
private fun LevelSection(state: TestSignalsUiState, actions: TestSignalsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("LEVEL", Modifier.weight(1f))
            ValueField(
                label = "dB",
                value = state.draft.levelDb,
                width = 64.dp,
                enabled = state.controlsEnabled,
                stripTrailingZeros = true
            ) { actions.setLevel(it) }
        }
        Slider(
            value = state.draft.levelDb.coerceIn(SIGGEN_SLIDER_MIN_DB, SIGGEN_LEVEL_MAX_DB),
            onValueChange = { actions.setLevel(it.coerceIn(SIGGEN_SLIDER_MIN_DB, SIGGEN_LEVEL_MAX_DB)) },
            valueRange = SIGGEN_SLIDER_MIN_DB..SIGGEN_LEVEL_MAX_DB,
            enabled = state.controlsEnabled
        )
        Caption("Peak level in dBFS. Output trim, master volume and mute still apply downstream.")
    }
}

// ── Parameters ──

@Composable
private fun ParametersSection(state: TestSignalsUiState, actions: TestSignalsActions) {
    val desc = state.desc
    val used = (0 until 4).filter { desc.params[it].isUsed }
    if (used.isEmpty()) return
    val draft = state.draft
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("PARAMETERS")
        if (draft.signalType == SIGGEN_ISP) {
            SegmentedPicker(
                options = listOf(0, 1),
                selected = Math.round(draft.p.getOrElse(0) { 0f }),
                label = { if (it == 0) "fs/4 · +3.01 dBTP" else "fs/6 · +1.25 dBTP" },
                onSelect = { actions.setParam(0, it.toFloat()) },
                enabled = state.controlsEnabled
            )
            Caption(
                "Sample-peak-normalized sequences with a known inter-sample true-peak over. " +
                    "Set the level at or below the headroom the over should fit into."
            )
        } else {
            for (i in used) {
                val pd = desc.params[i]
                val label = state.info.paramLabels.getOrNull(i) ?: "p${i + 1}"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    ValueField(
                        label = siggenUnitLabel(pd.semantic),
                        value = draft.p.getOrElse(i) { 0f },
                        width = 72.dp,
                        enabled = state.controlsEnabled,
                        maxDecimals = siggenParamDecimals(pd.semantic),
                        stripTrailingZeros = true
                    ) { actions.setParam(i, siggenClampParam(pd, maxOf(it, pd.min))) }
                }
            }
        }
        if (draft.signalType == SIGGEN_TONE_PAIR) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Caption("Presets:")
                PlainButton("SMPTE 60/7k", state.controlsEnabled) {
                    actions.setParam(0, 60f)
                    actions.setParam(1, 7000f)
                    actions.setParam(2, 4f)
                }
                PlainButton("CCIF 19k/20k", state.controlsEnabled) {
                    actions.setParam(0, 19000f)
                    actions.setParam(1, 20000f)
                    actions.setParam(2, 1f)
                }
            }
        }
        val max = state.caps?.multitoneMax ?: 0
        if (draft.signalType == SIGGEN_MULTITONE && max > 0) Caption("Up to $max tones on this device.")
    }
}

// ── Timing ──

@Composable
private fun TimingSection(state: TestSignalsUiState, actions: TestSignalsActions) {
    val d = state.draft
    val enabled = state.controlsEnabled
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("TIMING")
        @Composable
        fun seconds(min: Float) = ValueField(
            label = "s",
            value = d.durationMs / 1000f,
            width = 72.dp,
            enabled = enabled,
            maxDecimals = 2,
            stripTrailingZeros = true
        ) {
            actions.setDurationMs((maxOf(min, it) * 1000).toLong())
        }

        @Composable
        fun repeat() = ValueField(
            label = "",
            value = d.repeat.toFloat(),
            width = 72.dp,
            enabled = enabled,
            maxDecimals = 0,
            stripTrailingZeros = true
        ) {
            actions.setRepeat(Math.round(maxOf(it, 0f)).coerceIn(0, 65535))
        }

        @Composable
        fun gap() = ValueField(
            label = "ms",
            value = d.gapMs.toFloat(),
            width = 72.dp,
            enabled = enabled,
            maxDecimals = 0,
            stripTrailingZeros = true
        ) {
            actions.setGapMs(Math.round(maxOf(it, 0f)).coerceIn(0, 65535))
        }
        when (state.desc.timing) {
            SIGGEN_TIMING_SWEEP -> {
                TimingRow("Sweep length", null) { seconds(0.01f) }
                TimingRow("Repeat", "0 = repeat forever") { repeat() }
                TimingRow("Gap between sweeps", null) { gap() }
            }
            SIGGEN_TIMING_PATTERN -> {
                TimingRow(
                    "Repeat",
                    if (d.signalType == SIGGEN_CHANNEL_ID) {
                        "Passes over the selected outputs. 0 = forever"
                    } else {
                        "Pattern periods. 0 = repeat forever"
                    }
                ) { repeat() }
                TimingRow("Extra gap per period", null) { gap() }
            }
            else ->
                if (siggenWalkEnabled(d)) {
                    TimingRow("Dwell per channel", "0 = 2 s default") { seconds(0f) }
                    TimingRow("Passes", "Full passes over the outputs. 0 = forever") { repeat() }
                } else {
                    TimingRow("Duration", "0 = play until stopped") { seconds(0f) }
                }
        }
    }
}

@Composable
private fun TimingRow(label: String, caption: String?, field: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (caption != null) Caption(caption)
        }
        field()
    }
}

// ── Options ──

@Composable
private fun OptionsSection(state: TestSignalsUiState, actions: TestSignalsActions) {
    val d = state.draft
    val enabled = state.controlsEnabled
    fun on(flag: Int) = d.flags and flag != 0
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("OPTIONS")
        SwitchRow(
            "Bypass output EQ (RAW)",
            "Skips crossover and PEQ on the selected outputs. Trim, master volume, mute and delay still apply.",
            on(DspiConstants.SIGGEN_FLAG_RAW),
            enabled
        ) { actions.setFlag(DspiConstants.SIGGEN_FLAG_RAW, it) }
        if (d.signalType == SIGGEN_WHITE || d.signalType == SIGGEN_PINK) {
            SwitchRow(
                "Decorrelate channels",
                "Independent noise per output instead of one copied signal.",
                on(DspiConstants.SIGGEN_FLAG_DECORR),
                enabled
            ) { actions.setFlag(DspiConstants.SIGGEN_FLAG_DECORR, it) }
        }
        if (d.signalType == SIGGEN_CHANNEL_ID) {
            SwitchRow(
                "Walk outputs one at a time",
                "Channel ID always walks: each output plays its channel number as counted blips.",
                checked = true,
                enabled = false
            ) {}
        } else {
            SwitchRow(
                "Walk outputs one at a time",
                "Plays the selected outputs sequentially instead of together.",
                on(DspiConstants.SIGGEN_FLAG_WALK),
                enabled
            ) { actions.setFlag(DspiConstants.SIGGEN_FLAG_WALK, it) }
        }
    }
}

// ── Transport ──

@Composable
private fun TransportBar(state: TestSignalsUiState, actions: TestSignalsActions) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                siggenTransportTitle(state.status, state.startBlocker, state.connected, state.supported, state.draft),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                siggenTransportDetail(state.status, state.draft, state::outputName),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = secondaryText()
            )
        }
        if (state.running) {
            // Stop immediately, no fade
            OutlinedButton(onClick = { actions.stop(immediate = true) }) { Text("■") }
            Button(
                onClick = { actions.stop(immediate = false) },
                colors = ButtonDefaults.buttonColors(containerColor = MacColors.red, contentColor = Color.White)
            ) { Text("Stop") }
        } else {
            Button(onClick = { actions.start() }, enabled = state.startBlocker == null && state.writable) { Text("▶  Start") }
        }
    }
}
