// @Previews of MatrixMixerView over FakeDspiCore (debug source set only;
// macOS `#Preview { MatrixMixerView(vm: .preview) }`).
package se.bofr.dspi.feature.matrix

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.Dispatchers
import se.bofr.dspi.bridge.DspiBridgeImpl
import se.bofr.dspi.core.DspState
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.fake.FakeDspiCore
import se.bofr.dspi.ui.DspiTheme

/** The V32 fixture (RP2350, 8 in / 9 out) with a few routes and a PDM/EQ-worker conflict to show. */
private fun previewState(upmix: Boolean = false): DspState {
    val s = FakeDspiCore.seeded().getState()
    val routing = s.matrixRouting.mapIndexed { i, r -> r.mapIndexed { o, on -> on || (i == 0 && o == 2) || (i == 1 && o == 3) } }
    val invert = s.matrixInvert.mapIndexed { i, r -> r.mapIndexed { o, inv -> inv || (i == 1 && o == 3) } }
    val gain = s.matrixGain.mapIndexed { i, r -> r.mapIndexed { o, g -> if (i == 0 && o == 2) -3f else g } }
    val enabled = s.outputEnabled.mapIndexed { o, on -> on || o == 2 || o == 3 }
    val up = if (upmix) {
        s.upmix.toMutableList().also {
            it[DspiConstants.UPMIX_PARAM_ENABLED] = 1f
            it[DspiConstants.UPMIX_PARAM_SURROUND_MODE] = 1f
        }
    } else {
        s.upmix
    }
    return s.copy(matrixRouting = routing, matrixInvert = invert, matrixGain = gain, outputEnabled = enabled, upmix = up, inputSource = 0)
}

@Composable
private fun PreviewMatrix(activeInputs: Int, upmix: Boolean = false, forceGrid: Boolean? = null) {
    val scope = rememberCoroutineScope()
    val bridge =
        remember {
            DspiBridgeImpl(FakeDspiCore(previewState(upmix)), scope, ffi = Dispatchers.Unconfined, main = Dispatchers.Unconfined)
                .also { it.scanDevices() } // the fake starts connected: select it (upmixSupported needs a device)
        }
    val actions = remember { MatrixActions(bridge, scope) }
    DspiTheme {
        Surface {
            MatrixMixerContent(
                state = MatrixMixerState.from(bridge, activeInputChannels = activeInputs, connected = true),
                actions = actions,
                siggenSupported = true,
                onBack = {},
                forceGrid = forceGrid
            )
        }
    }
}

@Preview(name = "Phone — stereo", widthDp = 380, heightDp = 800)
@Composable
private fun MatrixMixerPhoneStereoPreview() = PreviewMatrix(activeInputs = 2)

@Preview(name = "Phone — 8 channels", widthDp = 380, heightDp = 800)
@Composable
private fun MatrixMixerPhone8chPreview() = PreviewMatrix(activeInputs = 8)

@Preview(name = "Phone — stereo + upmixer", widthDp = 380, heightDp = 800)
@Composable
private fun MatrixMixerPhoneUpmixPreview() = PreviewMatrix(activeInputs = 2, upmix = true)

@Preview(name = "Tablet — grid, stereo", widthDp = 1000, heightDp = 700)
@Composable
private fun MatrixMixerTabletStereoPreview() = PreviewMatrix(activeInputs = 2)

@Preview(name = "Tablet — grid, 8 channels", widthDp = 1000, heightDp = 1100)
@Composable
private fun MatrixMixerTablet8chPreview() = PreviewMatrix(activeInputs = 8)

@Preview(name = "Compact fields", widthDp = 200, heightDp = 80)
@Composable
private fun CompactFieldsPreview() {
    DspiTheme {
        Surface {
            androidx.compose.foundation.layout.Row {
                CompactGainField(-3.5f, {}, {})
                CompactDelayField(12f, 85f, {}, {})
            }
        }
    }
}
