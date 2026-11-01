// @Previews of every tool view, driven by a real DspiBridgeImpl over
// FakeDspiCore (demo state seeded from defaults). Debug source set only, so
// :fake-core never reaches a release build.
package se.bofr.dspi.feature.tools

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.bridge.DspiBridgeImpl
import se.bofr.dspi.fake.FakeDspiCore
import se.bofr.dspi.ui.DspiTheme

@Composable
private fun rememberPreviewBridge(): DspiBridge {
    val scope = rememberCoroutineScope()
    return remember { DspiBridgeImpl(FakeDspiCore(), scope) }
}

@Composable
private fun PreviewFrame(content: @Composable (DspiBridge) -> Unit) {
    val bridge = rememberPreviewBridge()
    DspiTheme(darkTheme = true) { Surface { content(bridge) } }
}

@Preview(name = "Tools", widthDp = 380, heightDp = 760)
@Composable
private fun ToolsScreenPreview() = PreviewFrame { ToolsScreen(it, {}, {}) }

@Preview(name = "Loudness", widthDp = 380, heightDp = 900)
@Composable
private fun LoudnessViewPreview() = PreviewFrame { LoudnessView(it) }

@Preview(name = "Loudness (tablet)", widthDp = 900, heightDp = 600)
@Composable
private fun LoudnessViewTabletPreview() = PreviewFrame { LoudnessView(it) }

@Preview(name = "Crossfeed", widthDp = 380, heightDp = 1100)
@Composable
private fun CrossfeedViewPreview() = PreviewFrame { CrossfeedView(it) }

@Preview(name = "Volume Leveller", widthDp = 380, heightDp = 1000)
@Composable
private fun VolumeLevellerViewPreview() = PreviewFrame { VolumeLevellerView(it) }

@Preview(name = "Psychoacoustic Bass", widthDp = 380, heightDp = 1300)
@Composable
private fun PsychoacousticBassViewPreview() = PreviewFrame { PsychoacousticBassView(it) }

@Preview(name = "Subharmonic Synthesizer", widthDp = 380, heightDp = 1600)
@Composable
private fun SubharmonicSynthViewPreview() = PreviewFrame { SubharmonicSynthView(it) }

@Preview(name = "Tube Modeller", widthDp = 380, heightDp = 1200)
@Composable
private fun TubeModellerViewPreview() = PreviewFrame { TubeModellerView(it) }

@Preview(name = "Stereo Upmixer", widthDp = 380, heightDp = 1400)
@Composable
private fun UpmixerViewPreview() = PreviewFrame { UpmixerView(it) }

@Preview(name = "Output Limiter", widthDp = 380, heightDp = 760)
@Composable
private fun OutputLimiterViewPreview() = PreviewFrame { OutputLimiterView(it, 0, {}, {}) }

@Preview(name = "Tube illustrations", widthDp = 600, heightDp = 220)
@Composable
private fun TubeIllustrationPreview() = DspiTheme(darkTheme = true) {
    Surface {
        Row {
            TubeFamily.entries.forEach { f ->
                TubeIllustration(
                    family = f,
                    lit = true,
                    peaks = emptyList(),
                    outputStart = 2,
                    outputCount = 0,
                    outputMask = 0,
                    active = false,
                    modifier = Modifier.weight(1f).height(200.dp)
                )
            }
        }
    }
}
