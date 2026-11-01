// Port of DSPi-Console-b5:DSPi Console/ContentView.swift @ b5fdeac
// (ContentView: sidebar, quick-access icons, global controls, main content;
// UserVolumeSection; MasterModeSection). Linux qt/qml/Sidebar.qml and
// main.qml for how a GUI drives this core.
//
// Layout: a tablet (>= 840 dp wide) keeps the macOS split view, sidebar left
// and graph + detail right, selection toggled in place. A phone shows the
// sidebar content as the home page (graph on top, dashboard below) and a
// channel row opens ChannelScreen (route channel/{ch}).
package se.bofr.dspi.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import se.bofr.dspi.bridge.ChannelMap
import se.bofr.dspi.bridge.DangerousOp
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.feature.graph.GraphResizeHandle
import se.bofr.dspi.feature.graph.GraphView
import se.bofr.dspi.feature.graph.LocalPeqGraphSelection
import se.bofr.dspi.feature.graph.PeqGraphSelection
import se.bofr.dspi.feature.home.components.ChannelRow
import se.bofr.dspi.feature.home.components.ConnectionStatusIndicator
import se.bofr.dspi.feature.home.components.SidebarVolumeMode
import se.bofr.dspi.feature.home.components.VolumeModeSelector
import se.bofr.dspi.feature.home.components.setSidebarVolumeMode
import se.bofr.dspi.feature.home.components.sidebarVolumeMode
import se.bofr.dspi.ui.ChannelPalette
import se.bofr.dspi.ui.CpuSection
import se.bofr.dspi.ui.CustomSlider
import se.bofr.dspi.ui.DspiIcons
import se.bofr.dspi.ui.DspiRoutes
import se.bofr.dspi.ui.DspiTheme
import se.bofr.dspi.ui.DspiType
import se.bofr.dspi.ui.HorizontalRule
import se.bofr.dspi.ui.MasterVolumeTaper
import se.bofr.dspi.ui.PopUpButton
import se.bofr.dspi.ui.SidebarIconButton
import se.bofr.dspi.ui.UserVolumeTaper
import se.bofr.dspi.ui.VerticalRule
import se.bofr.dspi.ui.rememberSliderDrag
import se.bofr.dspi.ui.settings.AppSettingsStore
import se.bofr.dspi.ui.settings.LocalAppSettings
import se.bofr.dspi.ui.state.LocalAppState
import se.bofr.dspi.ui.writesAllowed

/** Saves a [SidebarSelection] across configuration changes. */
private val SelectionSaver =
    Saver<SidebarSelection, Int>(
        save = {
            when (it) {
                SidebarSelection.Overview -> -1
                is SidebarSelection.Input -> it.ch
                is SidebarSelection.Output -> 1000 + it.index
            }
        },
        restore = {
            when {
                it < 0 -> SidebarSelection.Overview
                it >= 1000 -> SidebarSelection.Output(it - 1000)
                else -> SidebarSelection.Input(it)
            }
        }
    )

/** The macOS main window. See the file comment for the phone/tablet split. */
@Composable
fun ContentView(bridge: DspiBridge, onNavigate: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = DspiTheme.colors
    val prefs = LocalAppSettings.current
    val homeVm: HomeViewModel = viewModel(factory = HomeViewModel.factory(bridge))
    val connected = bridge.observeStatus { it.connected }
    val s = bridge.observeState()
    var selection by rememberSaveable(stateSaver = SelectionSaver) { mutableStateOf<SidebarSelection>(SidebarSelection.Overview) }

    // A disabled output or a lost device falls back to the overview.
    LaunchedEffect(connected, s.outputEnabled) {
        val sel = selection
        if (!connected || (sel is SidebarSelection.Output && !s.outputEnabled.getOrElse(sel.index) { false })) {
            selection = SidebarSelection.Overview
        }
    }

    BoxWithConstraints(modifier.fillMaxSize().background(c.mainContent)) {
        val twoPane = maxWidth >= 840.dp
        // The main window's selection (macOS updateSelection(to:)): a channel selected in place on a tablet,
        // the overview on the phone's home page (channel pages are their own route)
        val appState = LocalAppState.current
        val selectedChannel = if (twoPane) graphChannels(bridge, selection).firstOrNull() else null
        LaunchedEffect(selectedChannel) { appState.updateSelection(selectedChannel) }
        Column(Modifier.fillMaxSize()) {
            FirmwareMismatchBanner(bridge, onUpdate = { onNavigate(DspiRoutes.FIRMWARE) })
            if (twoPane) {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.width(300.dp).fillMaxHeight().background(c.sidebar)) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            SidebarChannels(bridge, selection, onSelect = {
                                selection =
                                    if (it == selection) SidebarSelection.Overview else it
                            })
                        }
                        SidebarBottomPanel(bridge, homeVm, prefs, onNavigate)
                    }
                    VerticalRule()
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        GraphHeader(bridge, onNavigate)
                        val selectedChannel = graphChannels(bridge, selection).firstOrNull()
                        val graphSelection = remember(selectedChannel) { PeqGraphSelection() }
                        val tabState = rememberSaveable(selectedChannel) { mutableStateOf(OutputChannelTab.PEQ) }
                        CompositionLocalProvider(LocalPeqGraphSelection provides graphSelection) {
                            // No on-graph editing while the XO tab lists the crossover bands (macOS crossoverTabShown)
                            val editable = selection is SidebarSelection.Input ||
                                (selection is SidebarSelection.Output && tabState.value != OutputChannelTab.CROSSOVER)
                            GraphView(
                                bridge,
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                graphChannels(bridge, selection),
                                editable = editable
                            )
                            GraphResizeHandle(Modifier.padding(horizontal = 16.dp))
                            when (val sel = selection) {
                                SidebarSelection.Overview -> DashboardOverview(bridge, prefs)
                                is SidebarSelection.Input -> InputChannelDetail(bridge, sel.ch, Modifier.padding(horizontal = 16.dp))
                                is SidebarSelection.Output -> OutputChannelDetail(
                                    bridge,
                                    sel.index,
                                    onNavigate,
                                    Modifier.padding(horizontal = 16.dp),
                                    tabState = tabState
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    GraphHeader(bridge, onNavigate)
                    GraphView(bridge, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    GraphResizeHandle(Modifier.padding(horizontal = 16.dp))
                    Column(Modifier.background(c.sidebar)) {
                        SidebarChannels(bridge, SidebarSelection.Overview, onSelect = {
                            when (it) {
                                is SidebarSelection.Input -> onNavigate(DspiRoutes.channel(it.ch))
                                is SidebarSelection.Output -> onNavigate(DspiRoutes.channel(2 + it.index))
                                SidebarSelection.Overview -> Unit
                            }
                        })
                        SidebarBottomPanel(bridge, homeVm, prefs, onNavigate)
                    }
                    Spacer(Modifier.height(18.dp))
                    DashboardOverview(bridge, prefs)
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

/** The curves the embedded graph shows: all visible ones on the overview, the selected channel (+ linked partner) otherwise. */
private fun graphChannels(bridge: DspiBridge, selection: SidebarSelection): Set<Int> = when (selection) {
    SidebarSelection.Overview -> emptySet()
    is SidebarSelection.Input -> setOf(selection.ch)
    is SidebarSelection.Output -> setOf(2 + selection.index)
}

/** "Filter Response" + pop-out (GraphScreen) on the left, the connection status on the right. */
@Composable
internal fun GraphHeader(bridge: DspiBridge, onNavigate: (String) -> Unit, title: String = "Filter Response") {
    val c = DspiTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = DspiType.headline, color = c.label)
        IconButton(onClick = { onNavigate(DspiRoutes.GRAPH) }, modifier = Modifier.size(36.dp)) {
            Icon(
                DspiIcons.external,
                contentDescription = "Open the graph full screen",
                tint = c.secondaryLabel,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.weight(1f))
        ConnectionStatusIndicator(bridge)
    }
}

/**
 * INPUTS and OUTPUTS: one row per live input and enabled output. Rows fade
 * out without a device (macOS: opacity 0 until the device is ready).
 */
@Composable
private fun SidebarChannels(bridge: DspiBridge, selection: SidebarSelection, onSelect: (SidebarSelection) -> Unit) {
    val c = DspiTheme.colors
    val s = bridge.observeState()
    val connected = bridge.observeStatus { it.connected }
    val active = bridge.observeStatus { it.activeInputChannels }
    val curves by bridge.magnitudesRevision.collectAsStateWithLifecycle()
    val status = bridge.statusState()
    var renaming by rememberSaveable { mutableStateOf<Int?>(null) }
    var menuFor by rememberSaveable { mutableStateOf<Int?>(null) }
    val numInputs = if (active >= 0) bridge.liveInputCount() else 0
    val rowHeight = HomeModel.sidebarRowHeight(numInputs)
    // Curve visibility lives in the bridge; magnitudesChanged announces a toggle.
    val visible = remember(curves) { (0 until ChannelMap.APP_CHANNEL_COUNT).map { bridge.channelVisible(it) } }

    fun commitRename(ch: Int, text: String) {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) bridge.setChannelName(ch, trimmed)
        renaming = null
    }

    // macOS fades the rows until the device is ready (isDeviceReady: connected + fetched).
    val ready = bridge.observeDeviceReady()
    Column(Modifier.alpha(if (ready) 1f else 0f)) {
        SectionHeader("INPUTS")
        for (i in 0 until numInputs) {
            val ch = bridge.inputAppId(i)
            val partner = bridge.linkedPartner(ch)
            val rowSelected = selection == SidebarSelection.Input(ch) || (partner >= 0 && selection == SidebarSelection.Input(partner))
            ChannelRow(
                bridge = bridge,
                status = status,
                ch = ch,
                color = ChannelPalette.input(i),
                descriptor = "IN${i + 1}",
                name = bridge.channelName(ch),
                isSelected = rowSelected,
                isMuted = false,
                isRenaming = renaming == ch,
                rowHeight = rowHeight,
                isCurveVisible = visible.getOrElse(ch) { true },
                onToggleCurve = { bridge.setChannelVisible(ch, !bridge.channelVisible(ch)) },
                onTap = {
                    renaming?.let { r -> if (r != ch) renaming = null }
                    // Either row of a linked pair closes its page.
                    onSelect(if (rowSelected) selection else SidebarSelection.Input(ch))
                },
                onLongPress = { if (connected) menuFor = ch },
                onCommitRename = { commitRename(ch, it) },
                menu = {
                    ChannelContextMenu(bridge, ch, null, menuFor == ch, { menuFor = null }, onRename = { renaming = ch })
                }
            )
        }
        SectionHeader("OUTPUTS")
        val outputs = MatrixOutput.visible(bridge.platformName, s.outputTypes).filter { s.outputEnabled.getOrElse(it.index) { false } }
        for (out in outputs) {
            val ch = out.appCh
            ChannelRow(
                bridge = bridge,
                status = status,
                ch = ch,
                color = ChannelPalette.output(out.index, out.isPdm),
                descriptor = out.descriptor,
                name = bridge.channelName(ch),
                isSelected = selection == SidebarSelection.Output(out.index),
                isMuted = !s.outputEnabled.getOrElse(out.index) { false } || s.outputMuted.getOrElse(out.index) { false },
                isRenaming = renaming == ch,
                rowHeight = rowHeight,
                isCurveVisible = visible.getOrElse(ch) { true },
                onToggleCurve = { bridge.setChannelVisible(ch, !bridge.channelVisible(ch)) },
                onTap = {
                    renaming?.let { r -> if (r != ch) renaming = null }
                    onSelect(SidebarSelection.Output(out.index))
                },
                onLongPress = { if (connected) menuFor = ch },
                onCommitRename = { commitRename(ch, it) },
                menu = {
                    ChannelContextMenu(bridge, ch, out.index, menuFor == ch, { menuFor = null }, onRename = { renaming = ch })
                }
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = DspiType.captionBold,
        color = DspiTheme.colors.secondaryLabel,
        modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp)
    )
}

/** A channel row's context menu: Identify (outputs), Rename, Copy / Paste Parameters. */
@Composable
private fun ChannelContextMenu(
    bridge: DspiBridge,
    ch: Int,
    outputIndex: Int?,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit
) {
    // Observed here and captured by the menu content, which is its own composition: a
    // disconnect or the siggen caps probe landing while the menu is open updates it
    // (review-stalestate #6). macOS siggenSupported = the generator's caps probe.
    val siggenSupported = bridge.siggen.caps.collectAsStateWithLifecycle().value?.supported == true
    val connected = bridge.observeStatus { it.connected }
    // The paste buffer is a plain getter: re-read each time the menu opens (`expanded` flips).
    val canPaste = expanded && bridge.canPaste()
    val canWrite = writesAllowed()
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (outputIndex != null && connected && siggenSupported) {
            DropdownMenuItem(text = { Text("Identify") }, enabled = canWrite, onClick = {
                onDismiss()
                bridge.siggen.identify(outputIndex)
            })
            HorizontalDivider()
        }
        DropdownMenuItem(text = { Text("Rename") }, enabled = canWrite, onClick = {
            onDismiss()
            onRename()
        })
        HorizontalDivider()
        DropdownMenuItem(text = { Text("Copy Parameters") }, onClick = {
            onDismiss()
            bridge.copyChannel(ch)
        })
        DropdownMenuItem(text = { Text("Paste Parameters") }, enabled = canWrite && canPaste, onClick = {
            onDismiss()
            bridge.pasteChannel(ch)
        })
    }
}

/**
 * The sidebar's bottom panel: quick-access icons, Preset / Source / volume,
 * and the CPU meters. A long-press on a tool icon opens its page (macOS:
 * right-click opens the tool window).
 */
@Composable
private fun SidebarBottomPanel(bridge: DspiBridge, vm: HomeViewModel, prefs: AppSettingsStore, onNavigate: (String) -> Unit) {
    val c = DspiTheme.colors
    val s = bridge.observeState()
    val connected = bridge.observeStatus { it.connected }
    Column(Modifier.fillMaxWidth()) {
        HorizontalRule(Modifier.fillMaxWidth())
        // G1: the effect toggles write; navigation (long-press, Matrix, Stats, Settings) stays
        val canWrite = writesAllowed()
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            SidebarIconButton(DspiIcons.sliders, false, "Matrix Mixer", { onNavigate(DspiRoutes.MATRIX) })
            SidebarIconButton(
                DspiIcons.headphones,
                s.crossfeedEnabled,
                "Headphone Crossfeed",
                { bridge.setCrossfeed(!s.crossfeedEnabled) },
                enabled = canWrite,
                onLongClick = { onNavigate(DspiRoutes.TOOLS) }
            )
            SidebarIconButton(
                DspiIcons.loudness,
                s.loudnessEnabled,
                "Loudness Compensation",
                { bridge.setLoudness(!s.loudnessEnabled) },
                enabled = canWrite,
                onLongClick = { onNavigate(DspiRoutes.TOOLS) }
            )
            SidebarIconButton(
                DspiIcons.waveform,
                s.levellerEnabled,
                "Volume Leveller",
                { bridge.setLevellerEnabled(!s.levellerEnabled) },
                enabled = canWrite,
                onLongClick = { onNavigate(DspiRoutes.TOOLS) }
            )
            SidebarIconButton(
                DspiIcons.bassClef,
                s.psybassEnabled,
                "Psychoacoustic Bass",
                { bridge.setPsybassEnabled(!s.psybassEnabled) },
                enabled = canWrite,
                onLongClick = { onNavigate(DspiRoutes.TOOLS) }
            )
            SidebarIconButton(DspiIcons.info, false, "Stats for Nerbs", { onNavigate(DspiRoutes.ANALYSIS) })
            SidebarIconButton(DspiIcons.gear, false, "Settings", { onNavigate(DspiRoutes.SETTINGS) })
            SidebarIconButton(DspiIcons.xmark, s.bypass, "Bypass Master EQ", { bridge.setBypass(!s.bypass) }, enabled = canWrite)
        }
        HorizontalRule(Modifier.fillMaxWidth())
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PresetPickerRow(bridge, vm, Modifier.fillMaxWidth())
            if (bridge.inputSources.isNotEmpty()) {
                SourceRow(bridge, connected)
            }
            when (prefs.sidebarVolumeMode()) {
                SidebarVolumeMode.MASTER -> MasterModeSection(bridge, prefs)
                SidebarVolumeMode.AUTO -> UserVolumeSection(bridge, prefs)
            }
        }
        HorizontalRule(Modifier.fillMaxWidth())
        val status by bridge.statusState()
        CpuSection(status.cpu0, status.cpu1, Modifier.padding(16.dp))
    }
}

/**
 * The "Source" picker. Switching the input source rebuilds the pipeline
 * (HARDWARE_APPLY); the menu choice itself mints the token and the switch
 * applies at once, as on macOS (coordinator E1b).
 */
@Composable
private fun SourceRow(bridge: DspiBridge, connected: Boolean) {
    val c = DspiTheme.colors
    // Observed HERE: called with unchanged args, this row is skipped when the parent recomposes (H1).
    val s = bridge.observeState()
    val sources = bridge.inputSources
    // macOS hides the picker when the firmware reports no usable source.
    val current = sources.firstOrNull { it.id == s.inputSource } ?: return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Source", style = DspiType.caption2, color = c.secondaryLabel)
        Spacer(Modifier.weight(1f))
        PopUpButton(
            items = sources,
            selection = current,
            titleForItem = { it.name },
            enabled = connected,
            onSelect = { src ->
                if (src.id != bridge.inputSource) {
                    bridge.setInputSource(bridge.dangerousOps.confirm(DangerousOp.HARDWARE_APPLY), src.id)
                }
            }
        )
    }
}

/**
 * The user volume (REQ_SET_USER_VOLUME, the same field the host's volume
 * writes): square-root taper over -60..0 dB; drag sends live, release
 * commits; double-tap resets to 0 dB (macOS right-click).
 */
@Composable
fun UserVolumeSection(bridge: DspiBridge, prefs: AppSettingsStore, modifier: Modifier = Modifier) {
    val c = DspiTheme.colors
    val s = bridge.observeState()
    val connected = bridge.observeStatus { it.connected }
    val drag = rememberSliderDrag(UserVolumeTaper.dbToScalar(s.userVolumeDb).toFloat())
    fun db(v: Float) = UserVolumeTaper.scalarToDB(v.toDouble())
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VolumeModeSelector(prefs.sidebarVolumeMode(), { prefs.setSidebarVolumeMode(it) })
            Spacer(Modifier.weight(1f))
            Text(UserVolumeTaper.displayString(db(drag.value)), style = DspiType.bodyMono, color = c.label)
        }
        CustomSlider(
            value = drag.value,
            onValueChange = { drag.change(it) { v -> bridge.setUserVolume(db(v), sendOnly = true) } },
            onEditingChanged = { e -> drag.editing(e) { v -> bridge.setUserVolume(db(v)) } },
            defaultValue = 1f,
            onReset = { drag.set(1f) { bridge.setUserVolume(0f) } },
            enabled = connected,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * The master volume (`vm.masterVolumeDB`) on the firmware's piecewise taper
 * (-128 = mute, "−∞"), red track; drag sends live, release commits;
 * double-tap resets to 0 dB.
 */
@Composable
fun MasterModeSection(bridge: DspiBridge, prefs: AppSettingsStore, modifier: Modifier = Modifier) {
    val c = DspiTheme.colors
    val s = bridge.observeState()
    val connected = bridge.observeStatus { it.connected }
    val drag = rememberSliderDrag(MasterVolumeTaper.dbToSlider(s.masterVolumeDb))
    val db = MasterVolumeTaper.sliderToDB(drag.value)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VolumeModeSelector(prefs.sidebarVolumeMode(), { prefs.setSidebarVolumeMode(it) })
            Spacer(Modifier.weight(1f))
            Text(
                if (db <=
                    MasterVolumeTaper.MUTE_DB
                ) {
                    "−∞"
                } else {
                    MasterVolumeTaper.format(db) + " dB"
                },
                style = DspiType.bodyMono,
                color = c.label
            )
        }
        CustomSlider(
            value = drag.value,
            onValueChange = { drag.change(it) { v -> bridge.setMasterVolume(MasterVolumeTaper.sliderToDB(v), sendOnly = true) } },
            onEditingChanged = { e -> drag.editing(e) { v -> bridge.setMasterVolume(MasterVolumeTaper.sliderToDB(v)) } },
            defaultValue = 1f,
            onReset = { drag.set(1f) { bridge.setMasterVolume(0f) } },
            enabled = connected,
            trackColor = c.redSliderFill,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
