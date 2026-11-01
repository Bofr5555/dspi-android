// Port of DSPi-Console-b5:DSPi Console/StatsView.swift @ b5fdeac (StatsView,
// StarvationTimerRow, StarvationIntervalRow, BufferFillRow,
// BufferFillSparkline, SystemInfoRow, StatRow), reading the bridge's
// StatsController (2 s counters, 60 ms buffer fills) and its fetch* calls the
// way DSPi-Console-Linux:qt/qml/StatsWindow.qml @ 4683a2c does.
// Deviations (docs/UPSTREAM-DEVIATIONS.md, ANALYSIS): one column on phones.
package se.bofr.dspi.feature.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.bridge.SpdifChannelStatus
import se.bofr.dspi.core.AdatOutStatus
import se.bofr.dspi.core.BufferStats
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.core.InputLockStatus
import se.bofr.dspi.core.LgStatus
import se.bofr.dspi.core.SpdifRxStatus
import se.bofr.dspi.ui.Caption
import se.bofr.dspi.ui.ChannelPalette
import se.bofr.dspi.ui.MacColors
import se.bofr.dspi.ui.SectionLabel
import se.bofr.dspi.ui.WhileVisible

/** What System Statistics shows apart from the 60 ms buffer fills. */
data class StatsUiState(
    val connected: Boolean,
    val platformName: String,
    val firmwareVersion: String,
    val serialNumber: String,
    val reconnectCount: Int,
    val counters: StatCounters,
    val starvationTotal: Long,
    val starvationDelta: Long,
    val starvationLastEventMs: Long?,
    val starvationPreviousEventMs: Long?,
    /** Output slot types (0 S/PDIF, 1 I2S) for the buffer row titles. */
    val outputSlotTypes: List<Int>,
    /** Swift `inputSourceSupported`: the receiver answered. */
    val spdifRx: SpdifRxStatus?,
    /** Decoded IEC 60958 channel status, only while locked. */
    val spdifChannelStatus: SpdifChannelStatus?,
    /** GPIO of the active S/PDIF receiver. */
    val spdifActiveRxPin: Int?,
    val lg: LgStatus?,
    /** Swift `adatSupported`: an answer on an RP2350. */
    val adat: AdatOutStatus?,
    /** Shown only while the device is the I2S clock slave. */
    val i2sSlave: InputLockStatus?,
    /** Trace colours: S/PDIF slot i takes its pair's left output colour, PDM the sub's. */
    val slotColors: List<Color>,
    val pdmColor: Color
) {
    val hasInterfaceSections: Boolean get() = spdifRx != null || lg != null || adat != null || i2sSlave != null
}

@Composable
fun StatsView(bridge: DspiBridge, modifier: Modifier = Modifier) {
    val stats = bridge.stats
    val bridgeStatus by bridge.status.collectAsState()
    val bridgeState by bridge.state.collectAsState()
    val info by stats.info.collectAsState()
    val reconnects by stats.reconnects.collectAsState()
    val connected = bridgeStatus.connected
    var visible by remember { mutableStateOf(false) }
    WhileVisible(key = stats, onShow = {
        visible = true
        stats.watching = true
    }, onHide = {
        visible = false
        stats.watching = false
    })

    // Starvation bookkeeping and the fill traces start over with each device
    val serial = if (connected) bridge.selectedSerial else ""
    val tracker = remember(serial) { StarvationTracker() }
    val history = remember(serial) { BufferFillHistory() }
    val historyTick = remember { mutableLongStateOf(0L) }
    var starvationVersion by remember { mutableLongStateOf(0L) }
    LaunchedEffect(tracker) {
        // Swift re-reads the total every 2 s; the controller only emits changed
        // counters, so a quiet poll is a read with no new events (delta 0)
        stats.info.collectLatest { m ->
            val total = m[DspiConstants.STAT_STARVATION_TOTAL] ?: return@collectLatest
            while (true) {
                tracker.update(total, System.currentTimeMillis())
                starvationVersion++
                delay(2500)
            }
        }
    }
    LaunchedEffect(history) {
        stats.buffers.collect { b ->
            if (b != null) {
                history.append(b)
                historyTick.longValue++
            }
        }
    }

    // The receiver/LG/ADAT/I2S-slave readings, on the counters' 2 s cadence
    var spdifRx by remember { mutableStateOf<SpdifRxStatus?>(null) }
    var spdifCs by remember { mutableStateOf<SpdifChannelStatus?>(null) }
    var spdifPin by remember { mutableStateOf<Int?>(null) }
    var lg by remember { mutableStateOf<LgStatus?>(null) }
    var adat by remember { mutableStateOf<AdatOutStatus?>(null) }
    var i2s by remember { mutableStateOf<InputLockStatus?>(null) }
    LaunchedEffect(visible, serial) {
        spdifRx = null
        spdifCs = null
        spdifPin = null
        lg = null
        adat = null
        i2s = null
        if (!visible || serial.isEmpty()) return@LaunchedEffect
        while (true) {
            spdifRx = bridge.fetchSpdifRxStatus()
            spdifCs = if (spdifRx?.let { spdifRxIsLocked(it.state) } == true) bridge.fetchSpdifChannelStatus() else null
            spdifPin = if (spdifRx != null) bridge.fetchActiveSpdifRxPin() else null
            lg = bridge.fetchLgStatus()
            adat = bridge.fetchAdatOutStatus()?.takeIf { bridge.platformName == "RP2350" }
            i2s = bridge.fetchInputLock(0)?.takeIf { it.clockMode == I2S_CLOCK_MODE_SLAVE }
            delay(2000)
        }
    }

    @Suppress("UNUSED_EXPRESSION")
    starvationVersion
    val outputs = bridge.numOutputChannels
    val state =
        StatsUiState(
            connected = connected,
            platformName = if (connected) bridge.platformName.ifEmpty { STATS_DASH } else STATS_DASH,
            firmwareVersion = if (connected && bridge.firmwareVersion.isNotEmpty()) "v" + bridge.firmwareVersion else STATS_DASH,
            serialNumber = bridge.selectedSerial.ifEmpty { STATS_DASH },
            reconnectCount = reconnects,
            counters = StatCounters(info),
            starvationTotal = tracker.total,
            starvationDelta = tracker.delta,
            starvationLastEventMs = tracker.lastEventMs,
            starvationPreviousEventMs = tracker.previousEventMs,
            outputSlotTypes = bridgeState.outputTypes,
            spdifRx = spdifRx,
            spdifChannelStatus = spdifCs,
            spdifActiveRxPin = spdifPin,
            lg = lg,
            adat = adat,
            i2sSlave = i2s,
            slotColors = List(4) { i -> channelColorOf(bridge, 2 + i * 2) },
            pdmColor = channelColorOf(bridge, 1 + outputs)
        )
    StatsContent(state, stats.buffers, history, historyTick, onResetWatermarks = { stats.resetWatermarks() }, modifier = modifier)
}

private fun channelColorOf(bridge: DspiBridge, app: Int): Color =
    ChannelPalette.fromHex(bridge.channelColor(app), ChannelPalette.color(app, bridge.numOutputChannels))

@Composable
fun StatsContent(
    state: StatsUiState,
    buffers: StateFlow<BufferStats?>,
    history: BufferFillHistory,
    historyTick: LongState,
    onResetWatermarks: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= 840.dp
            val scroll = rememberScrollState()
            if (wide) {
                // Device facts and counters, buffer health, then the optional interfaces
                Row(Modifier.fillMaxWidth().verticalScroll(scroll).padding(vertical = 16.dp)) {
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        DeviceAndPipeline(state)
                    }
                    VerticalDivider()
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        StarvationSection(state, buffers)
                        HorizontalDivider()
                        BufferFillSection(state, buffers, history, historyTick, onResetWatermarks)
                    }
                    if (state.hasInterfaceSections) {
                        VerticalDivider()
                        Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            InterfaceSections(state)
                        }
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(scroll).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    DeviceAndPipeline(state)
                    HorizontalDivider()
                    StarvationSection(state, buffers)
                    HorizontalDivider()
                    BufferFillSection(state, buffers, history, historyTick, onResetWatermarks)
                    if (state.hasInterfaceSections) {
                        HorizontalDivider()
                        InterfaceSections(state)
                    }
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(if (state.connected) MacColors.green else MacColors.red, 6.dp)
            Text(
                if (state.connected) "  Connected" else "  Disconnected",
                fontSize = 11.sp,
                color = secondaryText(),
                modifier = Modifier.weight(1f)
            )
            Text("Updated every 2 seconds", fontSize = 11.sp, color = secondaryText())
        }
    }
}

@Composable
private fun DeviceAndPipeline(state: StatsUiState) {
    val c = state.counters
    SectionLabel("DEVICE INFORMATION")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SystemInfoRow("Platform", state.platformName)
        SystemInfoRow("Firmware", state.firmwareVersion)
        SystemInfoRow("Serial", state.serialNumber)
        SystemInfoRow("Reconnects", "${state.reconnectCount}")
    }
    HorizontalDivider()
    SectionLabel("SYSTEM INFORMATION")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SystemInfoRow("Clock Frequency", SystemInfoFormat.clock(c[DspiConstants.STAT_CLOCK_HZ]))
        SystemInfoRow("Core Voltage", SystemInfoFormat.coreVoltage(c[DspiConstants.STAT_CORE_MV]))
        SystemInfoRow("Sample Rate", SystemInfoFormat.sampleRate(c[DspiConstants.STAT_SAMPLE_RATE]))
        SystemInfoRow("Temperature", SystemInfoFormat.temperature(c[DspiConstants.STAT_TEMPERATURE]))
    }
    HorizontalDivider()
    SectionLabel("AUDIO OUTPUT")
    StatRow("USB Ring", "ISR → Main Loop", c.count(DspiConstants.STAT_USB_RING_OVERRUNS), null)
    StatRow("Buffer Pool", "USB → DMA", c.count(DspiConstants.STAT_SPDIF_OVERRUNS), c.count(DspiConstants.STAT_SPDIF_UNDERRUNS))
    HorizontalDivider()
    SectionLabel("PDM (SUBWOOFER)")
    StatRow("Ring Buffer", "Core 0 → Core 1", c.count(DspiConstants.STAT_PDM_RING_OVERRUNS), c.count(DspiConstants.STAT_PDM_RING_UNDERRUNS))
    StatRow("DMA Buffer", "Core 1 → PIO", c.count(DspiConstants.STAT_PDM_DMA_OVERRUNS), c.count(DspiConstants.STAT_PDM_DMA_UNDERRUNS))
}

@Composable
private fun SystemInfoRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

/** A row whose value carries a coloured state dot (Swift `stateRow`). */
@Composable
private fun StateRow(title: String, dot: StateDot, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Dot(dot.color(), 6.dp)
        Text("  $value", fontSize = 13.sp, color = secondaryText())
    }
}

@Composable
private fun StatRow(title: String, subtitle: String, overruns: Long?, underruns: Long?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Caption(subtitle)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (overruns != null) Counter(overruns, "over", MacColors.orange)
            if (underruns != null) Counter(underruns, "under", MacColors.red)
        }
    }
}

@Composable
private fun Counter(value: Long, label: String, alarm: Color) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            "$value",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = if (value > 0) alarm else MaterialTheme.colorScheme.onSurface
        )
        Text(label, fontSize = 10.sp, color = secondaryText())
    }
}

// ── Starvation ──

@Composable
private fun StarvationSection(state: StatsUiState, buffers: StateFlow<BufferStats?>) {
    // numSpdif decides the per-instance rows; read here, not by the whole view
    val numSpdif by remember(buffers) { buffers.map { it?.numSpdif ?: 0 }.distinctUntilChanged() }.collectAsState(0)
    SectionLabel("SPDIF DMA STARVATION")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Total", Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (state.starvationDelta > 0) {
                Text(
                    "+${state.starvationDelta}",
                    Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(MacColors.red.copy(alpha = 0.15f))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MacColors.red
                )
                Box(Modifier.width(6.dp))
            }
            Text(
                "${state.starvationTotal}",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = if (state.starvationTotal > 0) MacColors.red else MaterialTheme.colorScheme.onSurface
            )
        }
        for (i in 0 until minOf(starvationInstanceCount(numSpdif), 4)) {
            val v = state.counters.starvationInstance(i)
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                Text(starvationInstanceLabel(i), Modifier.weight(1f), fontSize = 13.sp, color = secondaryText())
                Text(
                    "$v",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    color = if (v > 0) MacColors.red else MaterialTheme.colorScheme.onSurface
                )
            }
        }
        StarvationTimerRow("Time since last event", state.starvationLastEventMs)
        val last = state.starvationLastEventMs
        val prev = state.starvationPreviousEventMs
        TimerRow("Time between last two", if (last != null && prev != null) statsFormatElapsed((last - prev) / 1000) else STATS_DASH)
    }
}

/** Swift `StarvationTimerRow`: ticks once a second while there is an event to count from. */
@Composable
private fun StarvationTimerRow(label: String, sinceMs: Long?) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sinceMs) {
        while (sinceMs != null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    TimerRow(label, if (sinceMs == null) STATS_DASH else statsFormatElapsed(maxOf(0L, now - sinceMs) / 1000))
}

@Composable
private fun TimerRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, Modifier.weight(1f), fontSize = 12.sp, color = secondaryText())
        Text(value, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = secondaryText())
    }
}

// ── Buffer fill levels ──

@Composable
private fun BufferFillSection(
    state: StatsUiState,
    buffers: StateFlow<BufferStats?>,
    history: BufferFillHistory,
    historyTick: LongState,
    onResetWatermarks: () -> Unit
) {
    val b by buffers.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionLabel("BUFFER FILL LEVELS", Modifier.weight(1f))
        OutlinedButton(onClick = onResetWatermarks, enabled = state.connected) { Text("Reset Watermarks", fontSize = 12.sp) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(if (b?.streaming == true) MacColors.green else MacColors.gray, 6.dp)
        Caption("Audio Streaming")
        Dot(if (b?.pdmActive == true) MacColors.green else MacColors.gray, 6.dp)
        Caption("PDM Active")
    }
    val stats = b ?: return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until minOf(stats.numSpdif, 4)) {
            val s = stats.spdif[i]
            BufferFillRow(
                title = bufferSlotTitle(i, state.outputSlotTypes.getOrElse(i) { 0 }),
                fillPct = s.fillPct,
                minPct = s.minPct,
                maxPct = s.maxPct,
                thresholds = BufferFillThresholds.SPDIF,
                color = state.slotColors[i],
                hollow = false,
                history = history,
                series = i,
                historyTick = historyTick
            )
        }
        if (stats.pdmActive) {
            BufferFillRow(
                "PDM DMA", stats.pdm.dmaFillPct, stats.pdm.dmaMinPct, stats.pdm.dmaMaxPct, BufferFillThresholds.PDM_DMA,
                state.pdmColor, false, history, BufferFillHistory.PDM_DMA_SERIES, historyTick
            )
            BufferFillRow(
                "PDM Ring", stats.pdm.ringFillPct, stats.pdm.ringMinPct, stats.pdm.ringMaxPct, BufferFillThresholds.PDM_RING,
                state.pdmColor, true, history, BufferFillHistory.PDM_RING_SERIES, historyTick
            )
        }
    }
}

/**
 * Swift `BufferFillRow` + `BufferFillSparkline`: name, watermarks and the
 * current fill in a top strip; the 15 s trace scrolls over a band spanning
 * the watermarks. The trace redraws from the history on each sample without
 * allocating (one cached Path).
 */
@Composable
private fun BufferFillRow(
    title: String,
    fillPct: Int,
    minPct: Int,
    maxPct: Int,
    thresholds: BufferFillThresholds,
    color: Color,
    hollow: Boolean,
    history: BufferFillHistory,
    series: Int,
    historyTick: LongState
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(secondaryText().copy(alpha = 0.07f))
    ) {
        BufferFillSparkline(history, series, color, minPct, maxPct, historyTick)
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .padding(end = 6.dp)
                    .width(7.dp)
                    .height(7.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (hollow) Color.Transparent else color)
                    .border(1.5.dp, color, RoundedCornerShape(50))
            )
            Text(title, Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(watermarkText(minPct, maxPct), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = secondaryText())
            Text(
                "$fillPct%",
                Modifier.width(44.dp),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = thresholds.health(fillPct.coerceIn(0, 100)).color(),
                textAlign = TextAlign.End
            )
        }
    }
}

/**
 * Swift `BufferFillSparkline`: the 15 s trace over a band spanning the
 * watermarks. Its own composable so a new fill figure does not rebuild its
 * draw cache; each sample redraws from the history into one cached Path.
 */
@Composable
private fun BufferFillSparkline(history: BufferFillHistory, series: Int, color: Color, minPct: Int, maxPct: Int, historyTick: LongState) {
    val separator = MaterialTheme.colorScheme.outlineVariant
    Box(
        Modifier.fillMaxSize().drawWithCache {
            val header = 17.dp.toPx()
            val inset = 2.dp.toPx()
            val h = size.height
            val w = size.width
            fun y(p: Float) = (h - inset) - p / 100f * ((h - inset) - header)
            val path = Path()
            val step = w / (BufferFillHistory.CAPACITY - 1)
            val guide = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))
            val ringDash =
                if (series == BufferFillHistory.PDM_RING_SERIES) PathEffect.dashPathEffect(floatArrayOf(3 * density, 2 * density)) else null
            val traceStyle =
                Stroke(
                    width = 1.25f * density,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = ringDash
                )
            val bandTop = y(maxOf(minPct, maxPct).toFloat())
            val bandBottom = y(minOf(minPct, maxPct).toFloat())
            onDrawBehind {
                historyTick.longValue
                drawRect(color.copy(alpha = 0.14f), Offset(0f, bandTop), Size(w, maxOf(1f, bandBottom - bandTop)))
                drawLine(separator, Offset(0f, y(50f)), Offset(w, y(50f)), strokeWidth = 0.5f * density, pathEffect = guide)
                path.reset()
                val total = history.total
                var penDown = false
                for (s in maxOf(0, total - BufferFillHistory.CAPACITY) until total) {
                    val v = history.value(series, s)
                    if (v.isNaN()) {
                        penDown = false
                        continue
                    }
                    val x = w - (total - 1 - s) * step
                    if (penDown) path.lineTo(x, y(v)) else path.moveTo(x, y(v))
                    penDown = true
                }
                drawPath(path, color, style = traceStyle)
            }
        }
    )
}

// ── Inputs and interfaces ──

/** Swift `spdifInputSection`: lock state, source, rate, counters, pin, channel status while locked, DEBUG rows. */
@Composable
private fun SpdifInputSection(rx: SpdifRxStatus, cs: SpdifChannelStatus?, pin: Int?) {
    val locked = spdifRxIsLocked(rx.state)
    SectionLabel("S/PDIF INPUT")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StateRow("State", spdifRxStateDot(rx.state), spdifRxStateString(rx.state))
        SystemInfoRow("Active Source", spdifRxSourceString(rx.inputSource))
        SystemInfoRow("Sample Rate", spdifRxSampleRateString(rx))
        SystemInfoRow("Lock Count", "${rx.lockCount}")
        SystemInfoRow("Loss Count", "${rx.lossCount}")
        SystemInfoRow("Parity Errors", if (locked) "${rx.parityErrors}" else STATS_DASH)
        SystemInfoRow("FIFO Fill", if (locked) "${rx.fifoFillPct}%" else STATS_DASH)
        SystemInfoRow("RX Pin", pin?.let { "GPIO $it" } ?: STATS_DASH)
        if (locked && cs != null) {
            HorizontalDivider()
            SectionLabel("CHANNEL STATUS")
            SystemInfoRow("Format", if (cs.consumer) "Consumer" else "Professional")
            SystemInfoRow("Audio", if (cs.pcm) "PCM" else "Non-PCM")
            SystemInfoRow("Category", cs.category)
            SystemInfoRow("Word Length", cs.wordLength)
            SystemInfoRow("Copy", if (cs.copyPermitted) "Permitted" else "Prohibited")
        }
        if (rx.state != 0) {
            HorizontalDivider()
            SectionLabel("DEBUG")
            SystemInfoRow("Library State", spdifRxLibStateString(rx.libState))
            SystemInfoRow("Stable Callbacks", "${rx.stableCallbacks}")
            SystemInfoRow("Lost Callbacks", "${rx.lostCallbacks}")
        }
    }
}

@Composable
private fun InterfaceSections(state: StatsUiState) {
    var first = true

    @Composable
    fun divider() {
        if (!first) HorizontalDivider()
        first = false
    }
    state.spdifRx?.let { rx ->
        divider()
        SpdifInputSection(rx, state.spdifChannelStatus, state.spdifActiveRxPin)
    }
    state.lg?.let { lg ->
        divider()
        SectionLabel("LG SOUND SYNC")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SystemInfoRow("Enabled", if (lg.enabled) "Yes" else "No")
            StateRow("Present", if (lg.present) StateDot.GREEN else StateDot.GRAY, if (lg.present) "Yes" else "No")
            SystemInfoRow("TV Volume", lgVolumeString(lg))
            SystemInfoRow("TV Mute", if (lg.muted) "On" else "Off")
        }
    }
    state.adat?.let { a ->
        divider()
        SectionLabel("ADAT BULK OUTPUT")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StateRow("State", adatStateDot(a), adatStateString(a))
            SystemInfoRow("Streaming", if (a.active) "Yes" else "No")
            SystemInfoRow("Rate Supported", if (a.rateOk) "Yes" else "No")
            SystemInfoRow("Data Pin", "GPIO ${a.pin}")
            SystemInfoRow("Resync Count", "${a.resyncCount}")
            SystemInfoRow("Slip Count", "${a.slipCount}")
        }
    }
    state.i2sSlave?.let { s ->
        divider()
        SectionLabel("I2S INPUT (SLAVE CLOCK)")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StateRow("State", i2sSlaveStateDot(s.state), i2sSlaveStateString(s.state))
            SystemInfoRow("Detected Rate", i2sDetectedRateString(s))
            SystemInfoRow("Measured Rate", i2sMeasuredRateString(s))
            SystemInfoRow("Lock Count", "${s.lockCount}")
            SystemInfoRow("Loss Count", "${s.lossCount}")
        }
    }
}
