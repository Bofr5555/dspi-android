// @Previews (debug only) on fakes: FakeBootloaderLocator, a no-op writer and
// the FakeDspiCore demo state (fake-core) for the connected device.
package se.bofr.dspi.feature.firmware

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import se.bofr.dspi.fake.demoDspState
import se.bofr.dspi.ui.DspiTheme

private object PreviewWriter : FirmwareWriter {
    override fun isReady(board: BootloaderBoard) = true
    override fun hasAccess(board: BootloaderBoard) = true
    override suspend fun obtainAccess(board: BootloaderBoard) = BoardAccess.GRANTED
    override suspend fun write(board: BootloaderBoard, image: FirmwareImage, uf2: Uf2Image, progress: (Double) -> Unit) = Unit
}

private val previewBoard = BootloaderBoard(BootloaderBoard.Chip.RP2350, "/dev/bus/usb/001/005")

/** The FakeDspiCore demo device (RP2350), as the bridge would report it. */
private val demoDevice: FirmwareDeviceInfo
    get() = FirmwareDeviceInfo(true, FirmwareVersion.of(demoDspState()))

@Composable
private fun PreviewScreen(
    state: FirmwareInstallState,
    device: FirmwareDeviceInfo = demoDevice,
    setup: FirmwareUpdateModel.() -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val model = remember {
        val image = FirmwareImage(BootloaderBoard.Chip.RP2350, "DSPi-RP2350-v1.1.6-beta5.uf2", FirmwareVersion.expected, ByteArray(0), true)
        FirmwareUpdateModel(
            FirmwareInstaller(FakeBootloaderLocator(listOf(previewBoard)), StubPreviewVerifier, PreviewWriter, { image }, scope),
            bundled = { image },
            picobootWriter = PreviewWriter
        ).apply {
            setup()
            installer.setStateForTesting(
                state,
                installing =
                state !is FirmwareInstallState.Ready && state != FirmwareInstallState.WaitingForBoard
            )
            observe(state)
        }
    }
    DspiTheme {
        Surface {
            FirmwareUpdateView(model, device, enterBootloader = {}, onNavigate = {}, onBack = {})
        }
    }
}

private object StubPreviewVerifier : FirmwareVerifying {
    override suspend fun awaitDeviceVersion(timeoutMs: Long): FirmwareVersion? = null
}

@Preview(name = "Connected, ready when you are", widthDp = 380, heightDp = 820)
@Composable
private fun PreviewConnected() = PreviewScreen(FirmwareInstallState.WaitingForBoard)

@Preview(name = "No device", widthDp = 380, heightDp = 820)
@Composable
private fun PreviewNoDevice() = PreviewScreen(FirmwareInstallState.WaitingForBoard, FirmwareDeviceInfo(false, null))

@Preview(name = "Board ready", widthDp = 380, heightDp = 820)
@Composable
private fun PreviewReady() = PreviewScreen(FirmwareInstallState.Ready(previewBoard), FirmwareDeviceInfo(false, null))

@Preview(name = "Writing", widthDp = 380, heightDp = 820)
@Composable
private fun PreviewWriting() = PreviewScreen(FirmwareInstallState.Writing(0.42), FirmwareDeviceInfo(false, null))

@Preview(name = "Verified", widthDp = 380, heightDp = 820)
@Composable
private fun PreviewVerified() = PreviewScreen(FirmwareInstallState.Verified(FirmwareVersion(1, 1, 6, 5)))

@Preview(name = "Wrong board family", widthDp = 380, heightDp = 820)
@Composable
private fun PreviewWrongFamily() = PreviewScreen(
    FirmwareInstallState.Failed(FirmwareInstallError.WrongBoardFamily("RP2040 (Pico)", "RP2350 (Pico 2)")),
    FirmwareDeviceInfo(false, null)
)

@Preview(name = "Cards", widthDp = 380)
@Composable
private fun PreviewCards() = DspiTheme {
    Surface {
        Column(Modifier.padding(16.dp)) {
            StepDotStrip(UpdateStep.entries.map { it.label }, current = 1)
            InstallWritingCard(0.7, "RP2350 (Pico 2)", "1.1.6 beta 5")
            InstallFailureCard(FirmwareInstallError.PermissionDenied)
            BootselHint()
        }
    }
}
