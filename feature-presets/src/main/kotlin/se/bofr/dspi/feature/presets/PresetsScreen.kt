// Entry point of :feature-presets (route `presets`). Gathers what macOS spreads
// over the sidebar's Preset picker and its right-click menu (ContentView.swift
// `PresetContextMenuItems`), the File menu (Import/Export Filters…,
// Import/Export Device Configuration…, Save Master Volume, Save Output
// Configuration), the Tools menu's Commit / Revert / Factory Reset and the
// AutoEQ menu (Browse Profiles…, Favorite Profiles, Clear Favorites, Update
// Database…), DSPi-Console @ v1.1.6-beta5. Wording follows the macOS menus;
// the phone layout is one scrolling page, tablets get two panes.
package se.bofr.dspi.feature.presets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.feature.presets.autoeq.AutoEQBrowser
import se.bofr.dspi.feature.presets.autoeq.AutoEQManager
import se.bofr.dspi.feature.presets.autoeq.AutoEQManagers
import se.bofr.dspi.feature.presets.autoeq.AutoEQMenuActions
import se.bofr.dspi.feature.presets.autoeq.FavoriteProfile
import se.bofr.dspi.ui.DspiRoutes
import se.bofr.dspi.ui.alert.AlertHost
import se.bofr.dspi.ui.alert.ConsoleAlertHost
import se.bofr.dspi.ui.writesAllowed

/** The menu-action objects of one Presets screen, sharing one alert slot. */
class PresetsController(val bridge: DspiBridge, scope: CoroutineScope, val autoEQ: AutoEQManager, io: CoroutineDispatcher) {
    val host = AlertHost()
    val presetMenu = PresetMenuActions(bridge, scope, host)
    val tools = ToolsMenuActions(bridge, scope, host)
    val files = FileMenuActions(bridge, scope, host)
    val autoEQMenu = AutoEQMenuActions(autoEQ, bridge, scope, host, io)
}

/** The system pickers / share sheet the page asks for (PresetsScreen wires them to SAF). */
class PresetFileRequests(
    val importFilters: () -> Unit = {},
    val exportFilters: () -> Unit = {},
    val importConfiguration: () -> Unit = {},
    val exportConfiguration: () -> Unit = {},
    val shareConfiguration: () -> Unit = {},
    val importAutoEQDatabase: () -> Unit = {}
)

/**
 * Entry point of :feature-presets (route [DspiRoutes.PRESETS]).
 * The signature is the contract with :app — keep it.
 */
@Composable
fun PresetsScreen(bridge: DspiBridge, onNavigate: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // E9: controller, alerts and device sequences live in the ViewModel.
    val vm: PresetsViewModel =
        viewModel(factory = PresetsViewModel.factory(bridge, AutoEQManagers.shared(context), context.applicationContext))
    val controller = vm.controller
    val files = controller.files
    val host = controller.host

    // The pickers start from this screen's Activity; the ViewModel awaits the pick.
    val requests =
        remember(vm, context) {
            PresetFileRequests(
                importFilters = { vm.importFrom(DocumentPicker.startOpen(context, arrayOf("text/*", "*/*")), files::importFilters) },
                exportFilters = {
                    if (bridge.connected) {
                        val picked =
                            DocumentPicker.startCreate(
                                context,
                                PresetDocumentFiles.DEFAULT_FILTERS_FILE_NAME,
                                PresetDocumentFiles.FILTERS_MIME
                            )
                        vm.exportTo(picked, { files.filtersDocument() }) { files.filtersExported() }
                    } else {
                        host.showError("No device connected.")
                    }
                },
                importConfiguration = {
                    if (files.canImportConfiguration()) {
                        vm.importFrom(DocumentPicker.startOpen(context, arrayOf("*/*")), files::importConfiguration)
                    }
                },
                exportConfiguration = {
                    if (files.canExportConfiguration()) {
                        val picked =
                            DocumentPicker.startCreate(
                                context,
                                PresetDocumentFiles.DEFAULT_CONFIGURATION_FILE_NAME,
                                PresetDocumentFiles.CONFIGURATION_MIME
                            )
                        vm.exportTo(picked, { files.configurationDocument(vm.displayName(it)) }, files::configurationExported)
                    }
                },
                shareConfiguration = vm::shareConfiguration,
                importAutoEQDatabase = {
                    vm.importFrom(
                        DocumentPicker.startOpen(context, arrayOf("application/json", "*/*")),
                        controller.autoEQMenu::importDatabase
                    )
                }
            )
        }

    PresetsView(controller = controller, requests = requests, onNavigate = onNavigate, onBack = onBack, modifier = modifier)
}

/** The Presets page for [controller]; previewable (no Android services). */
@Composable
fun PresetsView(
    controller: PresetsController,
    requests: PresetFileRequests,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialShowBrowser: Boolean = false
) {
    val bridge = controller.bridge
    val state by bridge.state.collectAsState()
    val status by bridge.status.collectAsState()
    val dirty by bridge.presetDirty.collectAsState()
    val inFlight by controller.presetMenu.presetSwitchInFlight.collectAsState()
    val favorites by controller.autoEQ.favoriteProfiles.collectAsState()
    var showAutoEQBrowser by rememberSaveable { mutableStateOf(initialShowBrowser) }

    val connected = status.connected
    val names = state.presetNames
    fun occupied(slot: Int) = (state.presetOccupied shr slot) and 1 == 1
    fun name(slot: Int) = names.getOrElse(slot) { "" }
    val active = state.activePresetSlot
    val labels = (0 until PRESET_SLOT_COUNT).map { presetLabel(it, occupied(it), name(it)) }

    Surface(modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val twoPane = maxWidth >= 840.dp
            val presetPane: @Composable (Modifier) -> Unit = { m ->
                Column(m) {
                    PresetSection(
                        connected = connected,
                        inFlight = inFlight,
                        active = active,
                        dirty = dirty,
                        occupied = ::occupied,
                        name = ::name,
                        startupMode = state.presetStartupMode,
                        defaultSlot = state.presetDefaultSlot,
                        onSelect = controller.presetMenu::selectPreset
                    )
                    PresetMenuSection(controller.presetMenu, connected, occupied(active), state.presetOccupied != 0, active, labels)
                }
            }
            val otherPane: @Composable (Modifier) -> Unit = { m ->
                Column(m) {
                    ToolsSection(controller.tools, connected)
                    FileSection(controller, requests, connected)
                    AutoEQSection(controller, favorites, onBrowse = {
                        showAutoEQBrowser = true
                    }, onImportFile = requests.importAutoEQDatabase)
                    TextButton(onClick = { onNavigate(DspiRoutes.SETTINGS) }, modifier = Modifier.padding(8.dp)) {
                        Text("Startup preset and save modes: Settings › Global Parameters")
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text("‹ Back") }
                    Text("Presets & Files", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (!connected) {
                        Text("No device", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp))
                    }
                }
                if (twoPane) {
                    Row(Modifier.fillMaxSize()) {
                        presetPane(Modifier.weight(1f).verticalScroll(rememberScrollState()))
                        otherPane(Modifier.weight(1f).verticalScroll(rememberScrollState()))
                    }
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        presetPane(Modifier)
                        otherPane(Modifier)
                    }
                }
            }
        }
    }

    // AutoEQ > Browse Profiles... (a window on macOS): a full-screen dialog; back closes it.
    if (showAutoEQBrowser) {
        // Review P5: the database is parsed only when it is needed.
        LaunchedEffect(Unit) { controller.autoEQMenu.loadDatabase() }
        Dialog(onDismissRequest = { showAutoEQBrowser = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                AutoEQBrowser(
                    manager = controller.autoEQ,
                    onApply = { controller.autoEQMenu.applyProfile(it) },
                    onDismiss = { showAutoEQBrowser = false }
                )
            }
        }
    }

    ConsoleAlertHost(controller.host)
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

/** A menu item as a full-width row. */
@Composable
private fun MenuRow(title: String, enabled: Boolean = true, destructive: Boolean = false, detail: String? = null, onClick: () -> Unit) {
    val color =
        when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            destructive -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurface
        }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = color, modifier = Modifier.weight(1f))
        if (detail != null) Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

/** The Preset picker: ten slots, "*" on the active slot while dirty, the startup default marked. */
@Composable
private fun PresetSection(
    connected: Boolean,
    inFlight: Boolean,
    active: Int,
    dirty: Boolean,
    occupied: (Int) -> Boolean,
    name: (Int) -> String,
    startupMode: Int,
    defaultSlot: Int,
    onSelect: (Int) -> Unit
) {
    SectionHeader("Preset")
    val enabled = connected && !inFlight && writesAllowed() // loading a preset writes (G1)
    for (slot in 0 until PRESET_SLOT_COUNT) {
        val isActive = slot == active
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (isActive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                .clickable(enabled = enabled) { onSelect(slot) }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = isActive, onClick = { onSelect(slot) }, enabled = enabled)
            Text(
                "${slot + 1}: " + presetPickerTitle(slot, occupied(slot), name(slot), isActive, dirty),
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.weight(1f)
            )
            if (startupMode == PRESET_STARTUP_SPECIFIED && slot == defaultSlot) {
                Text(
                    "Default",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }
    }
    Text(
        "Startup Preset: ${presetStartupModeName(startupMode)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

/** PresetContextMenuItems: Save, Rename…, Set as Default, Copy to…, Clear "N: name"…, Clear All Slots…. */
@Composable
private fun PresetMenuSection(
    actions: PresetMenuActions,
    connected: Boolean,
    isCurrentOccupied: Boolean,
    anySlotOccupied: Boolean,
    activeSlot: Int,
    slotLabels: List<String>
) {
    var copyMenuOpen by remember { mutableStateOf(false) }

    // G1: every preset action writes to the device
    @Suppress("NAME_SHADOWING")
    val connected = connected && writesAllowed()
    SectionHeader("Preset Menu")
    MenuRow("Save", enabled = connected, onClick = actions::saveActivePreset)
    MenuRow("Rename…", enabled = connected, onClick = actions::renameActivePreset)
    MenuRow("Set as Default", enabled = connected && !actions.isActiveDefault, onClick = actions::setActiveAsDefault)
    Box {
        MenuRow("Copy to…", enabled = connected, detail = "›") { copyMenuOpen = true }
        DropdownMenu(expanded = copyMenuOpen, onDismissRequest = { copyMenuOpen = false }) {
            for (dest in 0 until PRESET_SLOT_COUNT) {
                if (dest == activeSlot) continue
                DropdownMenuItem(text = { Text(slotLabels[dest]) }, onClick = {
                    copyMenuOpen = false
                    actions.copyActivePreset(dest)
                })
            }
        }
    }
    if (isCurrentOccupied) {
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        MenuRow("Clear \"${slotLabels[activeSlot]}\"…", enabled = connected, destructive = true) { actions.clearPreset(activeSlot) }
    }
    if (anySlotOccupied) {
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        MenuRow("Clear All Slots…", enabled = connected, destructive = true, onClick = actions::clearAllPresets)
    }
}

/** Tools menu: Commit Parameters..., Revert to Saved..., Factory Reset.... */
@Composable
private fun ToolsSection(tools: ToolsMenuActions, connected: Boolean) {
    // G1: Commit / Revert / Factory Reset write to the device
    @Suppress("NAME_SHADOWING")
    val connected = connected && writesAllowed()
    SectionHeader("Tools")
    MenuRow("Commit Parameters...", enabled = connected, onClick = tools::commitParameters)
    MenuRow("Revert to Saved...", enabled = connected, onClick = tools::revertToSaved)
    MenuRow("Factory Reset...", enabled = connected, destructive = true, onClick = tools::factoryReset)
}

/** File menu: filters, device configuration, master volume, output configuration. */
@Composable
private fun FileSection(controller: PresetsController, requests: PresetFileRequests, connected: Boolean) {
    // G1: imports and the two saves write; export and share only read
    val canWrite = writesAllowed()
    SectionHeader("File")
    MenuRow("Import Filters...", enabled = connected && canWrite, onClick = requests.importFilters)
    MenuRow("Export Filters...", enabled = connected, onClick = requests.exportFilters)
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
    MenuRow("Import Device Configuration...", enabled = connected && canWrite, onClick = requests.importConfiguration)
    MenuRow("Export Device Configuration...", enabled = connected, onClick = requests.exportConfiguration)
    MenuRow("Share Device Configuration...", enabled = connected, onClick = requests.shareConfiguration)
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
    MenuRow("Save Master Volume", enabled = connected && canWrite, onClick = controller.tools::saveMasterVolume)
    MenuRow("Save Output Configuration", enabled = connected && canWrite, onClick = controller.tools::saveOutputConfig)
}

/** AutoEQ menu: Browse Profiles..., Favorite Profiles, Clear Favorites, Update Database.... */
@Composable
private fun AutoEQSection(controller: PresetsController, favorites: List<FavoriteProfile>, onBrowse: () -> Unit, onImportFile: () -> Unit) {
    SectionHeader("AutoEQ")
    MenuRow("Browse Profiles...", onClick = onBrowse)
    Text(
        "Favorite Profiles",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
    if (favorites.isEmpty()) {
        Text(
            "No favorites yet",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)
        )
    } else {
        Column(Modifier.padding(start = 16.dp)) {
            for (favorite in favorites) {
                MenuRow(favorite.name, enabled = writesAllowed()) { controller.autoEQMenu.applyFavorite(favorite) }
            }
        }
    }
    MenuRow("Clear Favorites", enabled = favorites.isNotEmpty(), onClick = controller.autoEQ::clearFavorites)
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
    MenuRow("Update Database...") { controller.autoEQMenu.updateDatabase(onImportFile) }
}
