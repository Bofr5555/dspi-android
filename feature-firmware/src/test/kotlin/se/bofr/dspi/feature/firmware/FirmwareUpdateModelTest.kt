// macOS FirmwareUpdateView start()/resetRun() and the Android source/write-path setup.
package se.bofr.dspi.feature.firmware

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FirmwareUpdateModelTest {
    private val v = FirmwareVersion(1, 1, 6, 5)
    private val board = BootloaderBoard(BootloaderBoard.Chip.RP2350, "/dev/bus/usb/001/005")
    private val bundledImage = firmwareImage(TestUf2.image(8), v)
    private val picoboot = FakeWriter()

    private fun TestScope.model(locator: FakeBootloaderLocator = FakeBootloaderLocator(), verifier: FirmwareVerifying = StubVerifier(v)) =
        FirmwareUpdateModel(
            FirmwareInstaller(locator, verifier, picoboot, { bundledImage }, this, StandardTestDispatcher(testScheduler)),
            bundled = { bundledImage },
            picobootWriter = picoboot
        )

    @Test fun startRestartsAConnectedDeviceOnceAndArms() = runTest {
        val m = model()
        m.installer.beginWatching()
        var reboots = 0
        m.start(deviceConnected = true) { reboots++ }
        m.start(deviceConnected = true) { reboots++ }
        assertEquals(1, reboots)
        assertTrue(m.confirmed)
        assertTrue(m.installer.isArmed)
    }

    @Test fun startWithABoardInBootselNeverCallsTheDevice() = runTest {
        val m = model(FakeBootloaderLocator(listOf(board)))
        m.installer.beginWatching()
        var reboots = 0
        m.start(deviceConnected = false) { reboots++ }
        advanceUntilIdle()
        assertEquals(0, reboots)
        assertEquals(FirmwareInstallState.Verified(v), m.installer.state.value)
        assertEquals(listOf(board to bundledImage), picoboot.written)
    }

    @Test fun resetRunClearsBothSides() = runTest {
        val m = model(FakeBootloaderLocator(listOf(board)))
        m.installer.beginWatching()
        m.start(deviceConnected = true) {}
        advanceUntilIdle()
        m.resetRun()
        assertFalse(m.confirmed)
        assertFalse(m.rebootRequested)
        assertFalse(m.installer.isArmed)
        assertEquals(FirmwareInstallState.Ready(board), m.installer.state.value)
    }

    @Test fun localFileReplacesTheBundledImage() = runTest {
        val m = model(FakeBootloaderLocator(listOf(board)), StubVerifier(FirmwareVersion(9, 9, 9)))
        m.installer.beginWatching()
        val custom = FirmwareImage(BootloaderBoard.Chip.RP2350, "DSPi-1v20.uf2", null, TestUf2.image(4), isBundled = false)
        m.chooseLocal(custom)
        assertEquals("DSPi-1v20.uf2", m.imageLabel)
        m.start(deviceConnected = false) {}
        advanceUntilIdle()
        assertEquals(listOf(board to custom), picoboot.written)
        // No version to compare: the device returning is the success.
        assertEquals(FirmwareInstallState.Verified(FirmwareVersion(9, 9, 9)), m.installer.state.value)
    }

    @Test fun setupIsFrozenOnceCommitted() = runTest {
        val m = model()
        m.installer.beginWatching()
        m.start(deviceConnected = true) {}
        m.chooseLocal(FirmwareImage(null, "x.uf2", null, TestUf2.image(1), false))
        assertEquals(FirmwareSource.Bundled, m.source)
        m.usePicoboot()
        assertFalse(m.useDrive)
    }

    @Test fun pickingTheDriveIsHowACommittedDriveInstallProceeds() = runTest {
        val locator = FakeBootloaderLocator(listOf(board))
        val m = model(locator)
        m.installer.beginWatching()
        m.useDrive(null)
        assertEquals(FirmwareInstallState.WaitingForVolume(BootloaderBoard.Chip.RP2350), m.installer.state.value)
        m.start(deviceConnected = false) {}
        val dir = kotlin.io.path.createTempDirectory().toFile()
        try {
            m.useDrive(DirVolume(dir, "RP2350"))
            advanceUntilIdle()
            assertEquals(FirmwareInstallState.Verified(v), m.installer.state.value)
            assertTrue(java.io.File(dir, bundledImage.name).exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun observeRemembersTheChip() = runTest {
        val m = model()
        m.observe(FirmwareInstallState.WaitingForPermission(BootloaderBoard.Chip.RP2040))
        assertEquals(BootloaderBoard.Chip.RP2040, m.lastSeenChip)
        m.observe(FirmwareInstallState.Ready(board))
        assertEquals(BootloaderBoard.Chip.RP2350, m.lastSeenChip)
        m.observe(FirmwareInstallState.Writing(0.2))
        assertEquals(BootloaderBoard.Chip.RP2350, m.lastSeenChip)
    }

    // MARK: View logic (wording and hints)

    @Test fun primaryTitleAndConfirmationWording() = runTest {
        val m = model()
        val older = FirmwareDeviceInfo(true, FirmwareVersion(1, 1, 6, 4))
        val newer = FirmwareDeviceInfo(true, FirmwareVersion(1, 1, 7))
        assertEquals("Update Firmware", primaryTitle(m, older))
        assertEquals("Downgrade", primaryTitle(m, newer))
        assertEquals("1.1.6 beta 4", runningNowLabel(older))
        assertEquals("Unknown (no DSPi connected)", runningNowLabel(FirmwareDeviceInfo(false, null)))
        assertEquals("Official 1.1.6 beta 5", willInstallLabel(m))
        assertTrue(confirmationText(m, older).contains("restart into bootloader mode"))
        assertTrue(confirmationText(m, newer).contains("downgrade"))
        assertFalse(confirmationText(m, FirmwareDeviceInfo(false, null)).contains("restart"))
        assertTrue(CUSTOM_BUILD_WARNING.contains("custom DSPi build"))
        assertTrue(CUSTOM_BUILD_WARNING.contains("1.15 V"))

        m.chooseLocal(FirmwareImage(null, "mine.uf2", null, TestUf2.image(1), false))
        assertEquals("Install Firmware", primaryTitle(m, newer))
        assertEquals("mine.uf2", willInstallLabel(m))
        assertFalse(confirmationText(m, newer).contains("downgrade"))
    }

    @Test fun hintsFollowMacOS() = runTest {
        val m = model()
        val connected = FirmwareDeviceInfo(true, v)
        val none = FirmwareDeviceInfo(false, null)
        assertTrue(showBootselHint(m, FirmwareInstallState.WaitingForBoard, none))
        assertFalse(showBootselHint(m, FirmwareInstallState.WaitingForBoard, connected))
        assertTrue(showManualBootloaderHint(m, FirmwareInstallState.WaitingForBoard, connected))
        assertTrue(showBootselHint(m, FirmwareInstallState.Failed(FirmwareInstallError.NoBoardFound), connected))
        m.installer.beginWatching()
        m.start(true) {}
        assertTrue(showBootselHint(m, FirmwareInstallState.WaitingForBoard, connected))
        assertFalse(showManualBootloaderHint(m, FirmwareInstallState.WaitingForBoard, connected))
    }

    @Test fun stepsMapLikeMacOS() {
        assertEquals(UpdateStep.PREPARE, UpdateStep.of(FirmwareInstallState.WaitingForPermission(BootloaderBoard.Chip.RP2350)))
        assertEquals(UpdateStep.WRITE, UpdateStep.of(FirmwareInstallState.Writing(0.3)))
        assertEquals(UpdateStep.VERIFY, UpdateStep.of(FirmwareInstallState.WaitingForDevice))
        assertEquals(UpdateStep.DONE, UpdateStep.of(FirmwareInstallState.Verified(v)))
        assertEquals(UpdateStep.PREPARE, UpdateStep.of(FirmwareInstallState.Failed(FirmwareInstallError.NoBoardFound)))
        assertEquals(listOf("Prepare", "Write", "Verify", "Done"), UpdateStep.entries.map { it.label })
    }
}
