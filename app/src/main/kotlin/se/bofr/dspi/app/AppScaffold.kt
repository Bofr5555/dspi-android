package se.bofr.dspi.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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

/** macOS window/menu structure -> one NavHost (PLAN §3.3 `ui/nav/AppScaffold.kt`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(bridge: DspiBridge, demoMode: Boolean) {
    val nav = rememberNavController()
    val go: (String) -> Unit = { nav.navigate(it) }
    val back: () -> Unit = { nav.popBackStack() }
    Scaffold(
        topBar = { TopAppBar(title = { Text(if (demoMode) "DSPi (demo)" else "DSPi") }) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
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
                composable(DspiRoutes.FIRMWARE) { FirmwareUpdateView(bridge, go, back) }
            }
        }
    }
}
