package se.bofr.dspi.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.feature.analysis.AnalysisScreen
import se.bofr.dspi.feature.firmware.FirmwareUpdateView
import se.bofr.dspi.feature.graph.GraphScreen
import se.bofr.dspi.feature.home.ChannelScreen
import se.bofr.dspi.feature.home.HomeScreen
import se.bofr.dspi.feature.matrix.MatrixMixerView
import se.bofr.dspi.feature.presets.PresetsScreen
import se.bofr.dspi.feature.settings.SettingsScreen
import se.bofr.dspi.feature.tools.OutputLimiterView
import se.bofr.dspi.feature.tools.ToolsScreen
import se.bofr.dspi.ui.DspiRoutes
import se.bofr.dspi.ui.LocalWritesAllowed
import se.bofr.dspi.ui.WritesExempt
import se.bofr.dspi.usb.BootloaderLocator

/**
 * macOS window/menu structure -> one NavHost (PLAN §3.3 `ui/nav/AppScaffold.kt`).
 * Every route of [DspiRoutes] maps to exactly one feature entry composable.
 * The NavHost is keyed on [bridge]: entering or leaving demo mode starts the
 * navigation over, so no screen holds state from the other bridge.
 *
 * [bootloaderLocator]: the app's one locator, given to FirmwareUpdateView.
 *
 * Compat banner: the contract exposes it on the bridge (`compat`,
 * `compatMessage`, refreshed with `state`), HOME draws it; the app adds
 * nothing but this strip for the USB/demo states the bridge cannot know.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    bridge: DspiBridge,
    strip: ConnectionStrip,
    onEnterDemo: () -> Unit,
    onExitDemo: () -> Unit,
    onRequestPermission: () -> Unit,
    bootloaderLocator: BootloaderLocator? = null
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(if (strip is ConnectionStrip.Demo) "DSPi — Demo (no device)" else "DSPi") }) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ConnectionStripBar(strip, onEnterDemo, onExitDemo, onRequestPermission)
            key(bridge) { DspiNavHost(bridge, bootloaderLocator) }
        }
    }
}

@Composable
private fun DspiNavHost(bridge: DspiBridge, bootloaderLocator: BootloaderLocator?) {
    // G1: every device-writing control is disabled while the bridge refuses writes
    val writes by bridge.writesAllowed.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalWritesAllowed provides writes) { DspiRoutesHost(bridge, bootloaderLocator) }
}

@Composable
private fun DspiRoutesHost(bridge: DspiBridge, bootloaderLocator: BootloaderLocator?) {
    val nav = rememberNavController()
    val go: (String) -> Unit = { nav.navigate(it) }
    val back: () -> Unit = { nav.popBackStack() }
    NavHost(navController = nav, startDestination = DspiRoutes.HOME) {
        composable(DspiRoutes.HOME) { HomeScreen(bridge, go, back) }
        composable(
            DspiRoutes.CHANNEL,
            arguments = listOf(navArgument("ch") { type = NavType.IntType })
        ) { entry ->
            ChannelScreen(bridge, entry.arguments?.getInt("ch") ?: 0, go, back)
        }
        composable(DspiRoutes.GRAPH) { GraphScreen(bridge, go, back) }
        composable(DspiRoutes.MATRIX) { MatrixMixerView(bridge, go, back) }
        composable(DspiRoutes.TOOLS) { ToolsScreen(bridge, go, back) }
        composable(
            DspiRoutes.LIMITER,
            arguments = listOf(navArgument("out") { type = NavType.IntType })
        ) { entry ->
            OutputLimiterView(bridge, entry.arguments?.getInt("out") ?: 0, go, back)
        }
        composable(DspiRoutes.PRESETS) { PresetsScreen(bridge, go, back) }
        composable(DspiRoutes.SETTINGS) { SettingsScreen(bridge, go, back) }
        composable(DspiRoutes.ANALYSIS) { AnalysisScreen(bridge, go, back) }
        composable(DspiRoutes.FIRMWARE) {
            // The firmware update path stays usable read-only (G1: enterBootloader is the one write the bridge allows)
            WritesExempt { FirmwareUpdateView(bridge, go, back, bootloaderLocator = bootloaderLocator) }
        }
    }
}

@Composable
private fun ConnectionStripBar(strip: ConnectionStrip, onEnterDemo: () -> Unit, onExitDemo: () -> Unit, onRequestPermission: () -> Unit) {
    if (strip is ConnectionStrip.Hidden) return
    val demo = strip is ConnectionStrip.Demo
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (demo) colors.tertiaryContainer else colors.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            strip.text,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (demo) colors.onTertiaryContainer else colors.onSurfaceVariant
        )
        when (strip) {
            ConnectionStrip.Demo -> TextButton(onClick = onExitDemo) { Text("Exit demo") }
            is ConnectionStrip.NoDevice -> if (strip.demoAvailable) TextButton(onClick = onEnterDemo) { Text("Demo (no device)") }
            is ConnectionStrip.NeedsPermission -> TextButton(onClick = onRequestPermission) { Text("Allow USB access") }
            is ConnectionStrip.Info, ConnectionStrip.Hidden -> Unit
        }
    }
}
