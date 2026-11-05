// The macOS Tools menu (DSPi_ConsoleApp.swift `ToolsMenuActions`, README "Tool
// Windows") on a phone: one list, each tool window an internal sub-route of
// this screen. Tools owned by other packages (Matrix Mixer; Signal Generator,
// Spectrum Analyser, Stats, Interrupt Monitor) navigate to their routes.
package se.bofr.dspi.feature.tools

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.core.DspState
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.core.WireCompat
import se.bofr.dspi.ui.Caption
import se.bofr.dspi.ui.DspiRoutes

/**
 * The tool windows of this package, as sub-routes of [DspiRoutes.TOOLS]
 * (internal; `:app` only knows `tools` and `tools/limiter/{out}`). Order and
 * titles are the macOS Tools menu's.
 */
enum class ToolRoute(val route: String, val title: String, val subtitle: String) {
    LOUDNESS("tools/loudness", "Loudness Compensation", "ISO 226:2003 Fletcher-Munson"),
    CROSSFEED("tools/crossfeed", "Headphone Crossfeed", "BS2B Bauer Stereophonic-to-Binaural"),
    PSYBASS("tools/psybass", "Psychoacoustic Bass", "Phantom fundamental bass enhancement"),
    SUBHARM("tools/subharm", "Subharmonic Synthesizer", "Generates a subharmonic at half the frequency of the source's bass"),
    TUBE("tools/tube", "Tube Modeller", "Valve-style harmonic colour, supply sag and a tube amplifier's output stage"),
    UPMIX("tools/upmix", "Stereo Upmixer", "Derive Centre and Surround from stereo"),
    LEVELLER("tools/leveller", "Volume Leveller", "Upward Dynamic Range Compression");

    /** The effect's master switch as the state holds it (the sidebar pill). */
    fun isOn(s: DspState): Boolean = when (this) {
        LOUDNESS -> s.loudnessEnabled
        CROSSFEED -> s.crossfeedEnabled
        PSYBASS -> s.psybassEnabled
        SUBHARM -> s.subharm.getOrElse(DspiConstants.SUBHARM_PARAM_ENABLED) { 0f } != 0f
        TUBE -> s.tube.getOrElse(DspiConstants.TUBE_PARAM_ENABLED) { 0f } != 0f
        UPMIX -> s.upmix.getOrElse(DspiConstants.UPMIX_PARAM_ENABLED) { 0f } != 0f
        LEVELLER -> s.levellerEnabled
    }

    /**
     * Whether the connected firmware has this tool (core patch 0003: only an
     * older firmware, decoded for display, hides anything; macOS gates the
     * same tools on the same wire versions, `firmwareSupports*`).
     */
    fun supported(s: DspState): Boolean = when (this) {
        PSYBASS -> WireCompat.has(s, WireCompat.PSYBASS)
        SUBHARM -> WireCompat.has(s, WireCompat.SUBHARM)
        TUBE -> WireCompat.has(s, WireCompat.TUBE)
        UPMIX -> WireCompat.has(s, WireCompat.UPMIX_PRESENCE)
        LOUDNESS, CROSSFEED, LEVELLER -> true
    }

    companion object {
        fun of(route: String?): ToolRoute? = entries.firstOrNull { it.route == route }
    }
}

/**
 * Entry point of :feature-tools (route [DspiRoutes.TOOLS]). The signature is
 * the contract with :app — keep it. System back closes an open tool first.
 */
@Composable
fun ToolsScreen(bridge: DspiBridge, onNavigate: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var current by rememberSaveable { mutableStateOf<String?>(null) }
    val tool = ToolRoute.of(current)
    // System back while a tool is open closes the tool first (macOS: closing its window).
    BackHandler(enabled = tool != null) { current = null }

    if (tool != null) {
        Column(modifier.fillMaxSize()) {
            TextButton(onClick = { current = null }, modifier = Modifier.padding(start = 4.dp)) { Text("‹ Tools") }
            ToolContent(bridge, tool, Modifier.fillMaxSize())
        }
    } else {
        ToolList(bridge, onOpen = { current = it.route }, onNavigate = onNavigate, modifier = modifier)
    }
}

/** One tool window's content. */
@Composable
internal fun ToolContent(bridge: DspiBridge, tool: ToolRoute, modifier: Modifier = Modifier) {
    when (tool) {
        ToolRoute.LOUDNESS -> LoudnessView(bridge, modifier)
        ToolRoute.CROSSFEED -> CrossfeedView(bridge, modifier)
        ToolRoute.PSYBASS -> PsychoacousticBassView(bridge, modifier)
        ToolRoute.SUBHARM -> SubharmonicSynthView(bridge, modifier)
        ToolRoute.TUBE -> TubeModellerView(bridge, modifier)
        ToolRoute.UPMIX -> UpmixerView(bridge, modifier)
        ToolRoute.LEVELLER -> VolumeLevellerView(bridge, modifier)
    }
}

@Composable
private fun ToolList(bridge: DspiBridge, onOpen: (ToolRoute) -> Unit, onNavigate: (String) -> Unit, modifier: Modifier = Modifier) {
    val st by bridge.state.collectAsState()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Tools", Modifier.padding(16.dp), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        ListRow("Matrix Mixer", "Routing patch bay", null) { onNavigate(DspiRoutes.MATRIX) }
        HorizontalDivider()
        ToolRoute.entries.filter { it.supported(st) }.forEach { t ->
            ListRow(t.title, t.subtitle, t.isOn(st)) { onOpen(t) }
            HorizontalDivider()
        }
        // The macOS menu titles (incl. "Stats for nerbs"); all four live in :feature-analysis.
        ANALYSIS_ITEMS.forEachIndexed { i, (title, subtitle) ->
            ListRow(title, subtitle, null) { onNavigate(DspiRoutes.ANALYSIS) }
            if (i < ANALYSIS_ITEMS.lastIndex) HorizontalDivider()
        }
    }
}

/** The diagnostic entries of the macOS Tools menu, in menu order. */
internal val ANALYSIS_ITEMS =
    listOf(
        "Signal Generator" to "Test tones, sweeps and noise",
        "Spectrum Analyser" to "Real-time spectrum of inputs and outputs",
        "Stats for nerbs" to "System statistics",
        "Interrupt Monitor" to "Interrupt and buffer timing"
    )

@Composable
private fun ListRow(title: String, subtitle: String, on: Boolean?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Caption(subtitle)
        }
        Spacer(Modifier.padding(4.dp))
        if (on != null) Text(if (on) "On" else "Off", fontSize = 14.sp, color = if (on) accentColor() else secondaryColor())
        Text("  ›", fontSize = 18.sp, color = secondaryColor())
    }
}
