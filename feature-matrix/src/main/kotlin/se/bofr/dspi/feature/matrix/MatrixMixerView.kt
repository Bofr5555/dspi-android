// Port of DSPi-Console (macOS) `DSPi Console/MatrixMixerView.swift` (MatrixMixerView)
// @ v1.1.6-beta5; how it drives the core follows DSPi-Console-Linux
// qt/qml/MatrixMixerWindow.qml @ 4683a2c.
//
// Tablet (>= 600 dp wide): the macOS grid (outputs as columns, inputs as rows,
// OUTPUT rows below). Phone: one page per output (deviation, see
// docs/UPSTREAM-DEVIATIONS.md "MATRIX").
package se.bofr.dspi.feature.matrix

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.ui.DspiRoutes

/** Tablet threshold: below this the grid becomes one page per output. */
val MatrixGridMinWidth: Dp = 600.dp

/**
 * Entry point of :feature-matrix (route [DspiRoutes.MATRIX]).
 * The signature is the contract with :app — keep it.
 */
@Composable
fun MatrixMixerView(bridge: DspiBridge, onNavigate: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val rev by bridge.stateRevision.collectAsStateWithLifecycle()
    val activeFlow = remember(bridge) { bridge.status.map { it.activeInputChannels }.distinctUntilChanged() }
    val active by activeFlow.collectAsStateWithLifecycle(bridge.status.value.activeInputChannels)
    val connectedFlow = remember(bridge) { bridge.status.map { it.connected }.distinctUntilChanged() }
    val connected by connectedFlow.collectAsStateWithLifecycle(bridge.status.value.connected)
    val siggenCaps by bridge.siggen.caps.collectAsStateWithLifecycle()
    val siggenSupported = siggenCaps?.supported == true
    val state = remember(bridge, rev, active, connected) { MatrixMixerState.from(bridge, active, connected) }
    // App-scoped controller (E9): sequences and open alerts survive rotation.
    val actions = remember(bridge) { MatrixActions.forBridge(bridge) }

    MatrixMixerContent(state, actions, siggenSupported, onBack, modifier)
}

/** The view over a [MatrixMixerState]; split out so previews and the entry share it. */
@Composable
fun MatrixMixerContent(
    state: MatrixMixerState,
    actions: MatrixActions,
    siggenSupported: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    forceGrid: Boolean? = null
) {
    val pendingConflict by actions.pendingConflict.collectAsStateWithLifecycle()
    val renamingOutput by actions.renamingOutput.collectAsStateWithLifecycle()
    val ui =
        MatrixUi(
            state = state,
            actions = actions,
            siggenSupported = siggenSupported,
            requestOutputEnable = { o -> actions.requestOutputEnable(state, o) },
            startRename = actions::startRename
        )

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Matrix Mixer", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Done") }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (forceGrid ?: (maxWidth >= MatrixGridMinWidth)) MatrixGrid(ui) else MatrixPages(ui)
        }
    }

    pendingConflict?.let { conflict ->
        val alert = state.conflictAlert(conflict)
        AlertDialog(
            onDismissRequest = actions::dismissConflict,
            title = { Text(alert.title) },
            text = { Text(alert.message) },
            confirmButton = {
                TextButton(onClick = {
                    actions.resolveConflict(conflict, state.eqWorkerRange, state.pdmOutputIndex)
                }) { Text(alert.confirm) }
            },
            dismissButton = { TextButton(onClick = actions::dismissConflict) { Text("Cancel") } }
        )
    }
    renamingOutput?.let { idx ->
        RenameDialog(
            initial = state.visibleOutputs.getOrNull(idx)?.name.orEmpty(),
            onDismiss = actions::cancelRename,
            onCommit = { actions.commitRename(idx, it) }
        )
    }
}

/** Callbacks shared by both layouts. */
private class MatrixUi(
    val state: MatrixMixerState,
    val actions: MatrixActions,
    val siggenSupported: Boolean,
    val requestOutputEnable: (Int) -> Unit,
    val startRename: (Int) -> Unit
)

// ═══ Shared pieces ═════════════════════════════════════════════════════════

private val secondaryColor: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f)).padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = secondaryColor.copy(alpha = 0.6f),
            modifier = Modifier.weight(1f)
        )
        trailing()
    }
}

/** macOS routingHeader: in 8-channel mode it carries Direct 1:1 / Clear. */
@Composable
private fun RoutingHeader(ui: MatrixUi) {
    SectionLabel("ROUTING", Modifier.height(if (ui.state.is8ch) 48.dp else 26.dp)) {
        if (ui.state.is8ch) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = ui.actions::applyDirectRouting) { Text("Direct 1:1") }
                OutlinedButton(onClick = ui.actions::clearAllRoutes) { Text("Clear") }
            }
        }
    }
}

/** macOS column header context menu (Identify / Rename / Copy / Paste Parameters). */
@Composable
private fun OutputHeaderMenu(ui: MatrixUi, output: Int, expanded: Boolean, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (ui.state.connected && ui.siggenSupported) {
            // A matrix-disabled output would render silent, so Identify needs it enabled.
            DropdownMenuItem(
                text = { Text("Identify") },
                enabled = ui.state.output(output).enabled,
                onClick = {
                    onDismiss()
                    ui.actions.identifyOutput(output)
                }
            )
            HorizontalDivider()
        }
        DropdownMenuItem(text = { Text("Rename") }, onClick = {
            onDismiss()
            ui.startRename(output)
        })
        HorizontalDivider()
        DropdownMenuItem(text = { Text("Copy Parameters") }, onClick = {
            onDismiss()
            ui.actions.copyChannelParams(output)
        })
        DropdownMenuItem(text = { Text("Paste Parameters") }, enabled = expanded && ui.actions.canPaste(), onClick = {
            onDismiss()
            ui.actions.pasteChannelParams(output)
        })
    }
}

/** Help text macOS shows on hover over ENABLE; shown as a caption on touch. */
internal fun enableHelp(o: MatrixOutputControls): String = when {
    o.enabled -> "Disable output (saves CPU)"
    o.wouldConflict -> "Conflict: shared Core 1 resource"
    else -> "Enable output"
}

private fun enableColor(o: MatrixOutputControls, secondary: Color): Color = when {
    o.enabled -> MatrixBlue
    o.wouldConflict -> MatrixOrange.copy(alpha = 0.4f)
    else -> secondary.copy(alpha = 0.3f)
}

@Composable
private fun EnableButton(ui: MatrixUi, idx: Int, modifier: Modifier = Modifier) {
    val o = ui.state.output(idx)
    Box(
        modifier
            .size(44.dp)
            .clickable(role = Role.Switch, onClickLabel = enableHelp(o), onClick = { ui.requestOutputEnable(idx) }),
        contentAlignment = Alignment.Center
    ) { PowerGlyph(enableColor(o, secondaryColor)) }
}

@Composable
private fun MuteButton(ui: MatrixUi, idx: Int, modifier: Modifier = Modifier) {
    val o = ui.state.output(idx)
    Box(
        modifier
            .size(44.dp)
            .clickable(role = Role.Switch, onClickLabel = if (o.muted) "Unmute" else "Mute", onClick = {
                ui.actions.setOutputMute(idx, !o.muted)
            }),
        contentAlignment = Alignment.Center
    ) { SpeakerGlyph(o.muted, if (o.muted) MatrixRed else secondaryColor.copy(alpha = 0.4f), size = 16.dp) }
}

@Composable
private fun OutputGainField(ui: MatrixUi, idx: Int, width: Dp = 62.dp) {
    CompactGainField(
        gain = ui.state.output(idx).gainDB,
        onLive = { ui.actions.outputGainLive(idx, it) },
        onCommit = { ui.actions.outputGainCommit(idx, it) },
        width = width,
        title = "Output gain"
    )
}

@Composable
private fun OutputDelayField(ui: MatrixUi, idx: Int, width: Dp = 62.dp) {
    CompactDelayField(
        delay = ui.state.output(idx).delayMS,
        maxDelay = ui.state.maxDelay,
        onLive = { ui.actions.outputDelayLive(idx, it) },
        onCommit = { ui.actions.outputDelayCommit(idx, it) },
        width = width,
        title = "Output delay"
    )
}

@Composable
private fun InputTrimField(ui: MatrixUi, row: MatrixInput) {
    CompactGainField(
        gain = ui.state.inputTrimDB.getOrElse(row.index) { 0f },
        onLive = { ui.actions.inputTrimLive(row.index, it) },
        onCommit = { ui.actions.inputTrimCommit(row.index, it) },
        title = "Input trim (preamp) for ${row.fullName}"
    )
}

/** macOS crosspointCell. */
@Composable
private fun Crosspoint(ui: MatrixUi, row: MatrixInput, out: MatrixOutput, modifier: Modifier = Modifier) {
    val enabled = ui.state.output(out.index).enabled
    val route = ui.state.route(row.index, out.index)
    MatrixPoint(
        isConnected = route.isConnected,
        gain = route.gain,
        isInverted = route.isInverted,
        // .saturation(enabled ? 1 : 0)
        inputColor = if (enabled) parseHexColor(row.color) else Color.Gray,
        onToggleConnected = { ui.actions.toggleRoute(row.index, out.index, route) },
        onGainLive = { ui.actions.matrixGainLive(row.index, out.index, route, it) },
        onGainCommit = { ui.actions.matrixGainCommit(row.index, out.index, route, it) },
        onToggleInverted = { ui.actions.toggleInvert(row.index, out.index, route) },
        outlineColor = conflictOutline(ui.state, out.index),
        modifier = modifier.alpha(if (enabled) 1f else 0.3f)
    )
}

/** macOS: orange rings in the PDM column where enabling PDM would conflict. */
private fun conflictOutline(s: MatrixMixerState, outIndex: Int): Color? =
    if (outIndex == s.pdmOutputIndex && s.output(s.pdmOutputIndex).wouldConflict) MatrixOrange else null

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onCommit: (String) -> Unit) {
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.text.length <= MatrixActions.MAX_NAME_LENGTH) text = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onCommit(text.text) }),
                modifier = Modifier.focusRequester(focus)
            )
        },
        confirmButton = { TextButton(onClick = { onCommit(text.text) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

// ═══ Tablet: the macOS grid ════════════════════════════════════════════════

private val ColumnWidth = 76.dp
private val LabelWidth = 88.dp

@Composable
private fun MatrixGrid(ui: MatrixUi) {
    val s = ui.state
    val cardShape = RoundedCornerShape(10.dp)
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(16.dp)) {
        Column(
            Modifier
                .width(LabelWidth + ColumnWidth * s.visibleOutputs.size)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), cardShape)
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), cardShape)
        ) {
            // ── Column headers ──
            Row {
                Spacer(Modifier.width(LabelWidth).height(52.dp))
                s.visibleOutputs.forEach { out -> GridColumnHeader(ui, out) }
            }
            SectionDivider()
            // ── ROUTING ──
            RoutingHeader(ui)
            s.matrixInputs.forEach { row ->
                Row(Modifier.height(84.dp), verticalAlignment = Alignment.CenterVertically) {
                    GridInputLabel(ui, row)
                    s.visibleOutputs.forEach { out -> Crosspoint(ui, row, out, Modifier.width(ColumnWidth)) }
                }
                if (s.rowNeedsDivider(row.index)) SubtleDivider()
            }
            SectionDivider()
            // ── OUTPUT CONTROLS ──
            SectionLabel("OUTPUT", Modifier.height(26.dp))
            GridControlRow("ENABLE") { s.visibleOutputs.forEach { EnableButton(ui, it.index, Modifier.width(ColumnWidth)) } }
            SubtleDivider()
            GridControlRow("GAIN") {
                s.visibleOutputs.forEach {
                    Box(Modifier.width(ColumnWidth).alpha(dim(s, it.index)), contentAlignment = Alignment.Center) {
                        OutputGainField(ui, it.index)
                    }
                }
            }
            SubtleDivider()
            GridControlRow("DELAY") {
                s.visibleOutputs.forEach {
                    Box(Modifier.width(ColumnWidth).alpha(dim(s, it.index)), contentAlignment = Alignment.Center) {
                        OutputDelayField(ui, it.index)
                    }
                }
            }
            SubtleDivider()
            GridControlRow("MUTE") {
                s.visibleOutputs.forEach { MuteButton(ui, it.index, Modifier.width(ColumnWidth).alpha(dim(s, it.index))) }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

private fun dim(s: MatrixMixerState, idx: Int) = if (s.output(idx).enabled) 1f else 0.3f

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridColumnHeader(ui: MatrixUi, out: MatrixOutput) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier
                .width(ColumnWidth)
                .height(52.dp)
                .combinedClickable(onClick = { menu = true }, onLongClick = { menu = true }),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                out.name,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = secondaryColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(out.descriptor, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = parseHexColor(out.color).copy(alpha = 0.8f))
        }
        OutputHeaderMenu(ui, out.index, menu) { menu = false }
    }
}

/** macOS inputLabel: the row's name; in 8-channel mode with an input trim field. */
@Composable
private fun GridInputLabel(ui: MatrixUi, row: MatrixInput) {
    Column(
        Modifier.width(LabelWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            row.name,
            fontSize = 12.sp,
            fontWeight = if (ui.state.is8ch) FontWeight.SemiBold else FontWeight.Medium,
            color = parseHexColor(row.color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        if (ui.state.is8ch) InputTrimField(ui, row)
    }
}

@Composable
private fun GridControlRow(label: String, content: @Composable () -> Unit) {
    Row(Modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = secondaryColor,
            textAlign = TextAlign.End,
            modifier = Modifier.width(LabelWidth).padding(end = 8.dp)
        )
        content()
    }
}

@Composable
private fun SectionDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

@Composable
private fun SubtleDivider() =
    HorizontalDivider(Modifier.padding(start = LabelWidth), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

// ═══ Phone: one page per output ════════════════════════════════════════════

@Composable
private fun MatrixPages(ui: MatrixUi) {
    val outputs = ui.state.visibleOutputs
    if (outputs.isEmpty()) return
    val pager = rememberPagerState { ui.state.visibleOutputs.size }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = pager.currentPage.coerceAtMost(outputs.lastIndex), edgePadding = 8.dp) {
            outputs.forEach { out ->
                Tab(
                    selected = pager.currentPage == out.index,
                    onClick = { scope.launch { pager.animateScrollToPage(out.index) } },
                    modifier = Modifier.alpha(if (ui.state.output(out.index).enabled) 1f else 0.45f),
                    text = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(out.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 96.dp))
                            Text(out.descriptor, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = parseHexColor(out.color))
                        }
                    }
                )
            }
        }
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            outputs.getOrNull(page)?.let { OutputPage(ui, it) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OutputPage(ui: MatrixUi, out: MatrixOutput) {
    val s = ui.state
    val o = s.output(out.index)
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        // Column header: name, OUTn, header menu
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).combinedClickable(onClick = {}, onLongClick = { menu = true })) {
                Text(out.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(out.descriptor, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = parseHexColor(out.color).copy(alpha = 0.8f))
            }
            Box {
                TextButton(onClick = { menu = true }) { Text("•••") }
                OutputHeaderMenu(ui, out.index, menu) { menu = false }
            }
        }
        Spacer(Modifier.height(8.dp))

        // ── ROUTING ──
        RoutingHeader(ui)
        s.matrixInputs.forEach { row ->
            PhoneRouteRow(ui, row, out)
            if (s.rowNeedsDivider(row.index)) {
                HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            }
        }

        Spacer(Modifier.height(8.dp))
        // ── OUTPUT ──
        SectionLabel("OUTPUT", Modifier.height(26.dp))
        PhoneControlRow("ENABLE", enableHelp(o)) { EnableButton(ui, out.index) }
        PhoneControlRow("GAIN", dimmed = !o.enabled) { OutputGainField(ui, out.index, 84.dp) }
        PhoneControlRow("DELAY", dimmed = !o.enabled) { OutputDelayField(ui, out.index, 84.dp) }
        PhoneControlRow("MUTE", dimmed = !o.enabled) { MuteButton(ui, out.index) }
    }
}

/** One input's crosspoint on this output: name (+ trim) left; gain, INV and the dot right. */
@Composable
private fun PhoneRouteRow(ui: MatrixUi, row: MatrixInput, out: MatrixOutput) {
    val s = ui.state
    val enabled = s.output(out.index).enabled
    val route = s.route(row.index, out.index)
    Row(Modifier.fillMaxWidth().height(60.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ColorDot(parseHexColor(row.color))
                Spacer(Modifier.width(8.dp))
                Text(
                    row.name,
                    fontWeight = FontWeight.Medium,
                    color = parseHexColor(row.color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (row.fullName != row.name) Text(row.fullName, fontSize = 11.sp, color = secondaryColor, maxLines = 1)
        }
        if (s.is8ch) {
            InputTrimField(ui, row)
            Spacer(Modifier.width(12.dp))
        }
        Row(Modifier.alpha(if (enabled) 1f else 0.3f), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(70.dp), contentAlignment = Alignment.Center) {
                if (route.isConnected) {
                    CompactGainField(
                        gain = route.gain,
                        onLive = { ui.actions.matrixGainLive(row.index, out.index, route, it) },
                        onCommit = { ui.actions.matrixGainCommit(row.index, out.index, route, it) },
                        title = "${row.name} → ${out.name}",
                        quantized = false
                    )
                }
            }
            Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) {
                if (route.isConnected) MatrixInvertToggle(route.isInverted, true, { ui.actions.toggleInvert(row.index, out.index, route) })
            }
            MatrixConnectToggle(
                route.isConnected,
                if (enabled) parseHexColor(row.color) else Color.Gray,
                conflictOutline(s, out.index),
                true
            ) { ui.actions.toggleRoute(row.index, out.index, route) }
        }
    }
}

@Composable
private fun PhoneControlRow(label: String, caption: String? = null, dimmed: Boolean = false, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = secondaryColor)
            if (caption != null) Text(caption, fontSize = 11.sp, color = secondaryColor.copy(alpha = 0.7f))
        }
        Box(Modifier.alpha(if (dimmed) 0.3f else 1f), contentAlignment = Alignment.CenterEnd) { control() }
    }
}
