// Entry point of :feature-analysis (route DspiRoutes.ANALYSIS). The macOS
// Console opens these as four Tools-menu windows (DSPi_ConsoleApp.swift @
// b5fdeac: Signal Generator, Spectrum Analyser, "Stats for nerbs",
// Interrupt Monitor); here they are sub-routes of one screen: tabs on a
// phone, a side list on a tablet. Only the shown page talks to the device.
package se.bofr.dspi.feature.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.ui.DspiRoutes

/** The feature's internal sub-routes, titled as the macOS windows. */
enum class AnalysisRoute(val route: String, val title: String) {
    TEST_SIGNALS("analysis/test-signals", "Signal Generator"),
    SPECTRUM_ANALYSER("analysis/spectrum", "Spectrum Analyser"),
    STATS("analysis/stats", "System Statistics"),
    INTERRUPT_MONITOR("analysis/interrupt-monitor", "Interrupt Monitor");

    companion object {
        fun fromRoute(route: String?): AnalysisRoute = entries.firstOrNull { it.route == route } ?: TEST_SIGNALS
    }
}

/**
 * Entry point of :feature-analysis (route [DspiRoutes.ANALYSIS]).
 * The signature is the contract with :app — keep it. [onNavigate] is unused:
 * every page of this feature is an internal sub-route.
 */
@Composable
fun AnalysisScreen(bridge: DspiBridge, onNavigate: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var route by rememberSaveable { mutableStateOf(AnalysisRoute.TEST_SIGNALS.route) }
    AnalysisScaffold(
        selected = AnalysisRoute.fromRoute(route),
        onSelect = { route = it.route },
        onBack = onBack,
        modifier = modifier
    ) { page ->
        when (page) {
            AnalysisRoute.TEST_SIGNALS -> TestSignalsView(bridge)
            AnalysisRoute.SPECTRUM_ANALYSER -> SpectrumAnalyserView(bridge)
            AnalysisRoute.STATS -> StatsView(bridge)
            AnalysisRoute.INTERRUPT_MONITOR -> InterruptMonitorView(bridge)
        }
    }
}

@Composable
internal fun AnalysisScaffold(
    selected: AnalysisRoute,
    onSelect: (AnalysisRoute) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    page: @Composable (AnalysisRoute) -> Unit
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth >= 840.dp) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(220.dp).fillMaxHeight().padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    BackRow(onBack)
                    for (r in AnalysisRoute.entries) {
                        val on = r == selected
                        val accent = MaterialTheme.colorScheme.primary
                        Text(
                            r.title,
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (on) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface)
                                .clickable { onSelect(r) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            fontSize = 14.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                VerticalDivider()
                Box(Modifier.weight(1f).fillMaxHeight()) { page(selected) }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                BackRow(onBack)
                ScrollableTabRow(selectedTabIndex = selected.ordinal, edgePadding = 8.dp) {
                    for (r in AnalysisRoute.entries) {
                        Tab(selected = r == selected, onClick = { onSelect(r) }, text = { Text(r.title, maxLines = 1) })
                    }
                }
                HorizontalDivider()
                Box(Modifier.weight(1f).fillMaxWidth()) { page(selected) }
            }
        }
    }
}

@Composable
private fun BackRow(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        PlainButton("‹ Back") { onBack() }
    }
}
