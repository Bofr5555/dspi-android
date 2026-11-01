// Port of DSPi-Console-b5:DSPi Console/DSPi_ConsoleApp.swift `SettingsView`
// (NavigationSplitView sidebar + detail, shared save bar). Phone: a list of
// pages, one page at a time (DSPi-Console-Linux qt/qml/settings/pages split);
// tablet (≥ 840 dp): sidebar and page side by side as on macOS.
package se.bofr.dspi.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.unit.dp
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.ui.DspiRoutes
import se.bofr.dspi.ui.MacColors
import se.bofr.dspi.ui.settings.AppSettingsStore
import se.bofr.dspi.ui.settings.LocalAppSettings

/**
 * Entry point of :feature-settings (route [DspiRoutes.SETTINGS]).
 * The signature is the contract with :app — keep it.
 */
@Composable
fun SettingsScreen(bridge: DspiBridge, onNavigate: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    SettingsView(bridge, LocalAppSettings.current, onNavigate, onBack, modifier)
}

/** The Settings window: page list + page + the shared save bar. */
@Composable
fun SettingsView(
    bridge: DspiBridge,
    appSettings: AppSettingsStore,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialPage: SettingsCategory? = null
) {
    val coordinator = remember(bridge) { SettingsSaveCoordinator.of(bridge) }
    val state by bridge.state.collectAsState()
    val devices by bridge.devices.collectAsState()
    val csJson by bridge.cs.model.collectAsState()
    val csSupported by bridge.cs.supported.collectAsState()
    val csLoaded by bridge.cs.loaded.collectAsState()
    val connected = bridge.status.collectAsState().value.connected
    val prefs by appSettings.settings.collectAsState()
    val cs = remember(csJson) { CsModel.parse(csJson) }

    // Pending edits belong to one device (macOS noteSelectedDevice)
    LaunchedEffect(devices.selectedSerial) { coordinator.noteSelectedDevice(devices.selectedSerial) }
    // Control-surface pages and the Overview read the CS model
    LaunchedEffect(connected, csLoaded) { if (connected && !csLoaded) bridge.cs.refresh() }

    val availability = SettingsAvailability(
        connected = connected,
        platformName = bridge.platformName,
        inputSourceSupported = coordinator.deviceSnapshot(state).inputSourceSupported,
        controlInterfacesSupported = state.ctrlIfaceSupported,
        controlSurfacesSupported = csSupported,
        csGroupsSupported = cs.groupsSupported,
        csMacrosSupported = cs.macrosSupported,
        csAuxSupported = cs.auxSupported
    )

    var openPage by rememberSaveable { mutableStateOf(initialPage?.id) }
    val selected = openPage?.let { SettingsCategory.ofId(it) }?.takeIf { it.isAvailable(availability) }

    val open: (SettingsCategory) -> Unit = { page ->
        openPage = page.id
        appSettings.update { it.copy(settingsSelectedTab = page.id) }
    }

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 840.dp
            val remembered = SettingsCategory.ofId(prefs.settingsSelectedTab)?.takeIf { it.isAvailable(availability) }
            val shown = selected ?: if (wide) remembered ?: SettingsCategory.GENERAL else null
            BackHandler(enabled = !wide && shown != null) { openPage = null }
            Column(Modifier.fillMaxSize()) {
                SettingsHeader(
                    title = if (wide || shown == null) "Settings" else shown.title,
                    onBack = { if (!wide && shown != null) openPage = null else onBack() }
                )
                Box(Modifier.weight(1f)) {
                    when {
                        wide && shown != null -> Row(Modifier.fillMaxSize()) {
                            SettingsSidebar(availability, coordinator, shown, Modifier.width(300.dp).fillMaxHeight(), open)
                            VerticalDivider()
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                SettingsDetail(shown, bridge, coordinator, appSettings, cs, open, onNavigate)
                            }
                        }
                        shown == null -> SettingsSidebar(availability, coordinator, null, Modifier.fillMaxSize(), open)
                        else -> SettingsDetail(shown, bridge, coordinator, appSettings, cs, open, onNavigate)
                    }
                }
                SettingsSaveBar(coordinator)
            }
        }
    }
}

@Composable
private fun SettingsHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onBack) { Text("‹ Back") }
        Spacer(Modifier.width(4.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

/** The grouped page list (macOS sidebar `List`), with a dot on pages holding unsaved edits. */
@Composable
private fun SettingsSidebar(
    availability: SettingsAvailability,
    coordinator: SettingsSaveCoordinator,
    selected: SettingsCategory?,
    modifier: Modifier,
    onOpen: (SettingsCategory) -> Unit
) {
    val outputDirty by coordinator.bridge.outputConfigDirty.collectAsState()
    val state by coordinator.bridge.state.collectAsState()
    val edited by coordinator.globalUserEdited.collectAsState()
    val hardwarePages = setOf(SettingsCategory.OUTPUT_ASSIGNMENT, SettingsCategory.I2S_CONFIG, SettingsCategory.SPDIF_INPUT)
    LazyColumn(modifier) {
        SettingsCategory.groups.forEach { (group, items) ->
            val visible = items.filter { it.isAvailable(availability) }
            if (visible.isEmpty()) return@forEach
            item(key = "h-$group") {
                Text(
                    group,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp)
                )
            }
            visible.forEach { cat ->
                item(key = cat.id) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                if (cat ==
                                    selected
                                ) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.background
                                }
                            )
                            .clickable { onOpen(cat) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SettingsBadge(cat.tint, cat.title.first().toString())
                        Spacer(Modifier.width(12.dp))
                        Text(cat.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        val dot =
                            (cat in hardwarePages && outputDirty) ||
                                (cat == SettingsCategory.GLOBAL_PARAMS && edited && coordinator.globalDirty(state))
                        if (dot) Text("●", color = MacColors.orange)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsDetail(
    page: SettingsCategory,
    bridge: DspiBridge,
    coordinator: SettingsSaveCoordinator,
    appSettings: AppSettingsStore,
    cs: CsModel,
    onOpen: (SettingsCategory) -> Unit,
    @Suppress("UNUSED_PARAMETER") onNavigate: (String) -> Unit
) {
    when (page) {
        SettingsCategory.OVERVIEW -> PinOverviewTab(bridge, coordinator, onOpen)
        SettingsCategory.GENERAL -> GeneralSettingsTab()
        SettingsCategory.ADVANCED -> AdvancedSettingsTab(bridge, appSettings)
        SettingsCategory.GRAPHING -> GraphingSettingsTab(appSettings)
        SettingsCategory.SPECTRUM -> SpectrumSettingsTab(bridge, appSettings)
        SettingsCategory.GLOBAL_PARAMS -> GlobalSettingsTab(bridge, coordinator)
        SettingsCategory.OUTPUT_ASSIGNMENT -> HardwareSettingsTab(HardwarePage.OUTPUTS, bridge, coordinator, onOpen)
        SettingsCategory.I2S_CONFIG -> HardwareSettingsTab(HardwarePage.I2S, bridge, coordinator, onOpen)
        SettingsCategory.SPDIF_INPUT -> HardwareSettingsTab(HardwarePage.SPDIF, bridge, coordinator, onOpen)
        SettingsCategory.CONTROL_INTERFACES -> ControlInterfacesSettingsTab(bridge, coordinator)
        SettingsCategory.CONTROL_SURFACES -> ControlSurfacesSettingsTab(CsSection.CONTROLS, bridge, coordinator, cs)
        SettingsCategory.CHANNEL_GROUPS -> ControlSurfacesSettingsTab(CsSection.GROUPS, bridge, coordinator, cs)
        SettingsCategory.MACROS -> ControlSurfacesSettingsTab(CsSection.MACROS, bridge, coordinator, cs)
        SettingsCategory.AUX_OUTPUTS -> ControlSurfacesSettingsTab(CsSection.AUX, bridge, coordinator, cs)
    }
}

/** Scrolling column for a page's sections (macOS `Form { }.formStyle(.grouped)`). */
@Composable
internal fun SettingsForm(content: LazyListScope.() -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp), content = content)
}
