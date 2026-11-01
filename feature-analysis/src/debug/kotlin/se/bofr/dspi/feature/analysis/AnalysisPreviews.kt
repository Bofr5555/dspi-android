// @Previews of :feature-analysis over the real bridge and FakeDspiCore
// (PACKAGES.md Wave B DoD, decision E2). Debug source set only: :fake-core is
// a debugImplementation dependency, never part of a release build. The bridge
// runs on Unconfined dispatchers so the fake's answers are there for the
// first (static) frame.
package se.bofr.dspi.feature.analysis

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import se.bofr.dspi.bridge.DspiBridgeImpl
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.fake.FakeDspiCore
import se.bofr.dspi.ui.ChannelPalette
import se.bofr.dspi.ui.DspiTheme
import se.bofr.dspi.ui.settings.InMemoryAppSettingsStore

private class PreviewDevice(val core: FakeDspiCore, val bridge: DspiBridgeImpl)

@Composable
private fun rememberPreviewDevice(setup: PreviewDevice.() -> Unit = {}): PreviewDevice {
    val scope = rememberCoroutineScope()
    return remember {
        val core = FakeDspiCore.seeded()
        val bridge = DspiBridgeImpl(core, scope, ffi = Dispatchers.Unconfined, main = Dispatchers.Unconfined)
        bridge.scanDevices() // the fake starts connected: select it
        PreviewDevice(core, bridge).apply(setup)
    }
}

@Composable
private fun Themed(content: @Composable () -> Unit) = DspiTheme { Surface { content() } }

@Preview(name = "Analysis - phone", widthDp = 400, heightDp = 1100)
@Composable
private fun AnalysisPhonePreview() {
    val d = rememberPreviewDevice()
    Themed { AnalysisScreen(d.bridge, onNavigate = {}, onBack = {}) }
}

@Preview(name = "Analysis - tablet", widthDp = 1280, heightDp = 800)
@Composable
private fun AnalysisTabletPreview() {
    val d = rememberPreviewDevice()
    Themed { AnalysisScreen(d.bridge, onNavigate = {}, onBack = {}) }
}

@Preview(name = "Signal Generator - tablet, running sweep", widthDp = 1000, heightDp = 720)
@Composable
private fun TestSignalsRunningPreview() {
    val d =
        rememberPreviewDevice {
            bridge.siggen.selectType(SIGGEN_SWEEP_LOG)
            runBlocking { bridge.siggen.start() }
        }
    Themed { TestSignalsView(d.bridge) }
}

/** The analyser as the fake serves it, drawn without waiting for the frame loop. */
@Composable
private fun SpectrumPreview(mode: RtaDisplayMode, channels: List<Int>) {
    val tap = DspiConstants.RTA_TAP_OUTPUT
    val sel = RtaChannelSelection.of(tap, channels)
    val d =
        rememberPreviewDevice {
            bridge.rta.update(1, tap, sel.mask, false)
            core.emitRtaFrame()
        }
    val render =
        remember {
            RtaRenderState().also {
                it.take(d.bridge.rta.snapshot.value, d.bridge.rta.caps.value, tap, channels.toIntArray(), mode != RtaDisplayMode.BARS, 0.0)
            }
        }
    val tick = remember { mutableLongStateOf(0L) }
    Themed {
        SpectrumAnalyserContent(
            state =
            SpectrumUiState(
                connected = true,
                caps = d.bridge.rta.caps.value,
                selection = sel,
                available = d.bridge.rta.availableChannels(tap),
                mode = mode,
                prefs = RtaPreferences()
            ),
            render = render,
            tick = tick,
            snapshot = d.bridge.rta.snapshot,
            channelName = { t, ch -> d.bridge.channelName(d.bridge.rta.appChannel(t, ch)) },
            channelColor = { t, ch ->
                val app = d.bridge.rta.appChannel(t, ch)
                parseHexColor(d.bridge.channelColor(app)) ?: ChannelPalette.color(app)
            },
            onSide = {},
            onToggle = {},
            onMode = {}
        )
    }
}

@Preview(name = "Spectrum - phone, bars", widthDp = 400, heightDp = 800)
@Composable
private fun SpectrumPhonePreview() = SpectrumPreview(RtaDisplayMode.BARS, listOf(0, 1))

@Preview(name = "Spectrum - tablet, both", widthDp = 1100, heightDp = 720)
@Composable
private fun SpectrumTabletPreview() = SpectrumPreview(RtaDisplayMode.BOTH, listOf(0, 1, 2, 3))

@Preview(name = "Spectrum - live page", widthDp = 1100, heightDp = 720)
@Composable
private fun SpectrumLivePreview() {
    val d = rememberPreviewDevice()
    Themed { SpectrumAnalyserView(d.bridge, store = InMemoryAppSettingsStore()) }
}

@Preview(name = "Statistics - phone", widthDp = 400, heightDp = 1800)
@Composable
private fun StatsPhonePreview() {
    val d = rememberPreviewDevice { bridge.stats.watching = true }
    Themed { StatsView(d.bridge) }
}

@Preview(name = "Statistics - tablet", widthDp = 1280, heightDp = 900)
@Composable
private fun StatsTabletPreview() {
    val d = rememberPreviewDevice { bridge.stats.watching = true }
    Themed { StatsView(d.bridge) }
}

@Preview(name = "Interrupt Monitor - phone", widthDp = 400, heightDp = 600)
@Composable
private fun InterruptMonitorPreview() {
    val d =
        rememberPreviewDevice {
            core.simulateUserVolume(-20.5f)
            core.simulatePresetLoaded(2)
            bridge.monitor.watching = true
        }
    Themed { InterruptMonitorView(d.bridge) }
}
