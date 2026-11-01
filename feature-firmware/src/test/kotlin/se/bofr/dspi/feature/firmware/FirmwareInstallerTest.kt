// Port of DSPi-Console (macOS) `DSPi ConsoleTests/FirmwareInstallerTests.swift`
// @ v1.1.6-beta5 ("Reboot versus failure", "Which board it will act on",
// "Committing to an update", "A flash only counts when the device comes
// back", "Life after an install"), plus the Android cases: permission
// granted/denied/board gone, wrong board family, invalid image, unplug mid-write,
// verify timeout in virtual time. No device.
package se.bofr.dspi.feature.firmware

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FirmwareInstallerTest {
    private val v117 = FirmwareVersion(1, 1, 7)
    private val rp2350 = BootloaderBoard(BootloaderBoard.Chip.RP2350, "/dev/bus/usb/001/005")
    private val rp2040 = BootloaderBoard(BootloaderBoard.Chip.RP2040, "/dev/bus/usb/001/006")
    private val image = firmwareImage(TestUf2.image(64), v117)

    private fun TestScope.installer(
        boards: List<BootloaderBoard> = emptyList(),
        verifier: FirmwareVerifying = StubVerifier(null),
        writer: FirmwareWriter = FakeWriter(),
        image: FirmwareImage? = this@FirmwareInstallerTest.image,
        locator: FakeBootloaderLocator = FakeBootloaderLocator(boards)
    ) = FirmwareInstaller(
        locator,
        verifier,
        writer,
        { chip -> image ?: throw FirmwareInstallError.ImageMissing(chip.displayName) },
        this,
        StandardTestDispatcher(testScheduler)
    )

    // MARK: Reboot versus failure

    @Test fun lateWriteErrorIsAReboot() = assertTrue(FirmwareInstaller.isRebootSignal(0.99, true))

    @Test fun vanishedVolumeIsARebootAtAnyPoint() = assertTrue(FirmwareInstaller.isRebootSignal(0.1, false))

    @Test fun earlyWriteErrorIsAFailure() = assertFalse(FirmwareInstaller.isRebootSignal(0.4, true))

    @Test fun thresholdBoundary() {
        assertTrue(FirmwareInstaller.isRebootSignal(FirmwareInstaller.REBOOT_THRESHOLD, true))
        assertFalse(FirmwareInstaller.isRebootSignal(FirmwareInstaller.REBOOT_THRESHOLD - 0.01, true))
    }

    @Test fun timingsMatchMacOS() {
        assertEquals(30_000L, FirmwareInstaller.VERIFY_TIMEOUT_MS)
        assertEquals(16 * 1024, FirmwareInstaller.WRITE_CHUNK_SIZE)
        assertEquals(0.95, FirmwareInstaller.REBOOT_THRESHOLD, 0.0)
    }

    // MARK: Which board it will act on

    @Test fun noBoardWaits() = runTest {
        val i = installer()
        i.beginWatching()
        assertEquals(FirmwareInstallState.WaitingForBoard, i.state.value)
    }

    @Test fun oneBoardIsReady() = runTest {
        val i = installer(listOf(rp2350))
        i.beginWatching()
        assertEquals(FirmwareInstallState.Ready(rp2350), i.state.value)
    }

    @Test fun driveWriterWithoutADriveWaitsForTheVolumeAndRecovers() = runTest {
        val locator = FakeBootloaderLocator(listOf(rp2040))
        val writer = FakeWriter(ready = false)
        val i = installer(locator = locator, writer = writer)
        i.beginWatching()
        assertEquals(FirmwareInstallState.WaitingForVolume(BootloaderBoard.Chip.RP2040), i.state.value)
        writer.ready = true
        i.refresh()
        assertEquals(FirmwareInstallState.Ready(rp2040), i.state.value)
    }

    @Test fun removingTheBoardReturnsToWaiting() = runTest {
        val locator = FakeBootloaderLocator(listOf(rp2040))
        val i = installer(locator = locator)
        i.beginWatching()
        locator.boards = emptyList()
        locator.emit()
        assertEquals(FirmwareInstallState.WaitingForBoard, i.state.value)
    }

    @Test fun twoBoardsAreRefused() = runTest {
        val i = installer(listOf(rp2040, rp2350))
        i.beginWatching()
        assertEquals(FirmwareInstallState.Failed(FirmwareInstallError.MultipleBoards(2)), i.state.value)
    }

    /** ...and the refusal is a description of what is plugged in: it clears when that changes. */
    @Test fun multipleBoardsFailureDoesNotLatch() = runTest {
        val locator = FakeBootloaderLocator(listOf(rp2040, rp2350))
        val i = installer(locator = locator)
        i.beginWatching()
        locator.boards = listOf(rp2350)
        locator.emit()
        assertEquals(FirmwareInstallState.Ready(rp2350), i.state.value)
    }

    @Test fun detectionDoesNotDisturbAWriteInProgress() = runTest {
        val locator = FakeBootloaderLocator(listOf(rp2350))
        val i = installer(locator = locator)
        i.beginWatching()
        i.setStateForTesting(FirmwareInstallState.Writing(0.5), installing = true)
        locator.boards = emptyList()
        locator.emit()
        assertEquals(FirmwareInstallState.Writing(0.5), i.state.value)
    }

    // MARK: Committing to an update

    @Test fun committingWithABoardAlreadyReadyStartsTheWrite() = runTest {
        val writer = FakeWriter()
        val i = installer(listOf(rp2350), StubVerifier(v117), writer)
        i.beginWatching()
        i.installWhenReady()
        advanceUntilIdle()
        assertEquals(FirmwareInstallState.Verified(v117), i.state.value)
        assertEquals(listOf(rp2350 to image), writer.written)
    }

    @Test fun committingBeforeTheBoardArrivesStartsTheWriteWhenItDoes() = runTest {
        val locator = FakeBootloaderLocator()
        val i = installer(locator = locator, verifier = StubVerifier(v117))
        i.beginWatching()
        i.installWhenReady()
        assertEquals(FirmwareInstallState.WaitingForBoard, i.state.value)
        locator.boards = listOf(rp2350)
        locator.emit()
        advanceUntilIdle()
        assertEquals(FirmwareInstallState.Verified(v117), i.state.value)
    }

    @Test fun anUncommittedBoardIsNeverWritten() = runTest {
        val writer = FakeWriter()
        val i = installer(listOf(rp2350), writer = writer)
        i.beginWatching()
        advanceUntilIdle()
        assertEquals(FirmwareInstallState.Ready(rp2350), i.state.value)
        assertFalse(i.isArmed)
        assertTrue(writer.written.isEmpty())
    }

    // MARK: A flash only counts when the device comes back

    @Test fun successRequiresTheDeviceToReturnRunningWhatWeWrote() = runTest {
        val i = installer(verifier = StubVerifier(v117))
        i.install(rp2350)
        advanceUntilIdle()
        assertEquals(FirmwareInstallState.Verified(v117), i.state.value)
    }

    @Test fun deviceNeverReturningIsAFailure() = runTest {
        val i = installer(verifier = StubVerifier(null))
        i.install(rp2350)
        advanceUntilIdle()
        assertEquals(FirmwareInstallState.Failed(FirmwareInstallError.DeviceDidNotReturn), i.state.value)
    }

    @Test fun deviceReturningOnTheWrongVersionIsAFailure() = runTest {
        val i = installer(verifier = StubVerifier(FirmwareVersion(1, 1, 6)))
        i.install(rp2350)
        advanceUntilIdle()
        assertEquals(FirmwareInstallState.Failed(FirmwareInstallError.VersionMismatch("1.1.7", "1.1.6")), i.state.value)
    }

    /** A local file names no version: the device coming back at all is the success. */
    @Test fun aLocalImageVerifiesOnReturnWhateverItReports() = runTest {
        val custom = FirmwareVersion(1, 1, 6, 5)
        val i = installer(verifier = StubVerifier(custom), image = firmwareImage(TestUf2.image(8), version = null))
        i.install(rp2350)
        advanceUntilIdle()
        assertEquals(FirmwareInstallState.Verified(custom), i.state.value)
    }

    /** The 30 s wait (virtual time): still waiting at 29.9 s, failed at 30 s, verified when the device shows up in time. */
    @Test fun verifyTimeoutIsThirtySeconds() = runTest {
        var reported: FirmwareVersion? = null
        val i = installer(verifier = BridgeFirmwareVerifier { reported })
        i.install(rp2350)
        runCurrent()
        advanceTimeBy(29_900)
        assertEquals(FirmwareInstallState.WaitingForDevice, i.state.value)
        advanceTimeBy(200)
        assertEquals(FirmwareInstallState.Failed(FirmwareInstallError.DeviceDidNotReturn), i.state.value)

        val j = installer(verifier = BridgeFirmwareVerifier { reported })
        j.install(rp2350)
        runCurrent()
        advanceTimeBy(12_000)
        reported = v117
        advanceTimeBy(300)
        assertEquals(FirmwareInstallState.Verified(v117), j.state.value)
    }

    @Test fun installWithoutADriveFailsBeforeWriting() = runTest {
        val writer = FakeWriter(ready = false)
        val i = installer(writer = writer)
        i.install(rp2040)
        assertEquals(FirmwareInstallState.Failed(FirmwareInstallError.VolumeNotMounted("RPI-RP2")), i.state.value)
        assertTrue(writer.written.isEmpty())
    }

    @Test fun staleBundledImageIsRefused() = runTest {
        val i = FirmwareInstaller(
            FakeBootloaderLocator(),
            StubVerifier(null),
            FakeWriter(),
            { throw FirmwareInstallError.BundledImageStale("1.1.6", "1.1.7") },
            this
        )
        i.install(rp2350)
        assertEquals(FirmwareInstallState.Failed(FirmwareInstallError.BundledImageStale("1.1.6", "1.1.7")), i.state.value)
    }

    // MARK: Android: image checks before anything is written

    @Test fun wrongBoardFamilyIsRefusedBeforeWriting() = runTest {
        val writer = FakeWriter()
        val i = installer(writer = writer, image = firmwareImage(TestUf2.image(4, Uf2.FAMILY_RP2040), version = null))
        i.install(rp2350)
        advanceUntilIdle()
        assertEquals(FirmwareInstallState.Failed(FirmwareInstallError.WrongBoardFamily("RP2040 (Pico)", "RP2350 (Pico 2)")), i.state.value)
        assertTrue(writer.written.isEmpty())
        assertEquals(0, writer.accessRequests)
    }

    @Test fun invalidImageIsRefusedBeforeWriting() = runTest {
        val writer = FakeWriter()
        val i = installer(writer = writer, image = firmwareImage(ByteArray(1000), version = null))
        i.install(rp2350)
        assertEquals(
            FirmwareInstallState.Failed(FirmwareInstallError.InvalidImage("the file is not a whole number of UF2 blocks")),
            i.state.value
        )
        assertTrue(writer.written.isEmpty())
    }

    // MARK: Android: USB permission

    @Test fun permissionIsAskedThenTheWriteRuns() = runTest {
        val writer = FakeWriter(access = false, accessAnswer = BoardAccess.GRANTED)
        val seen = mutableListOf<FirmwareInstallState>()
        val i = installer(writer = writer, verifier = StubVerifier(v117))
        launchCollect(i, seen)
        i.install(rp2350)
        advanceUntilIdle()
        assertEquals(1, writer.accessRequests)
        assertTrue(seen.contains(FirmwareInstallState.WaitingForPermission(BootloaderBoard.Chip.RP2350)))
        assertEquals(FirmwareInstallState.Verified(v117), i.state.value)
    }

    @Test fun permissionDeniedFailsWithoutWriting() = runTest {
        val writer = FakeWriter(access = false, accessAnswer = BoardAccess.DENIED)
        val i = installer(writer = writer)
        i.install(rp2350)
        advanceUntilIdle()
        assertEquals(FirmwareInstallState.Failed(FirmwareInstallError.PermissionDenied), i.state.value)
        assertTrue(writer.written.isEmpty())
        assertTrue(FirmwareInstallError.PermissionDenied.isMundane)
    }

    @Test fun boardLeavingDuringThePermissionDialogIsNoBoard() = runTest {
        val i = installer(writer = FakeWriter(access = false, accessAnswer = BoardAccess.GONE))
        i.install(rp2350)
        advanceUntilIdle()
        assertEquals(FirmwareInstallState.Failed(FirmwareInstallError.NoBoardFound), i.state.value)
    }

    @Test fun resetIsRefusedWhileThePermissionDialogIsUp() = runTest {
        val i = installer()
        i.setStateForTesting(FirmwareInstallState.WaitingForPermission(BootloaderBoard.Chip.RP2350), installing = true)
        i.reset()
        assertEquals(FirmwareInstallState.WaitingForPermission(BootloaderBoard.Chip.RP2350), i.state.value)
    }

    // MARK: Android: unplug mid-write

    @Test fun unplugMidWriteIsAWriteFailureNotAReboot() = runTest {
        val writer = FakeWriter(failure = FirmwareInstallError.WriteFailed("the board was disconnected."))
        val verifier = object : FirmwareVerifying {
            var asked = false
            override suspend fun awaitDeviceVersion(timeoutMs: Long): FirmwareVersion? {
                asked = true
                return v117
            }
        }
        val i = installer(writer = writer, verifier = verifier)
        i.install(rp2350)
        advanceUntilIdle()
        assertEquals(FirmwareInstallState.Failed(FirmwareInstallError.WriteFailed("the board was disconnected.")), i.state.value)
        assertFalse(verifier.asked)
    }

    @Test fun unexpectedWriterExceptionsBecomeWriteFailed() = runTest {
        val i = installer(writer = FakeWriter(failure = IllegalStateException("boom")))
        i.install(rp2350)
        advanceUntilIdle()
        assertEquals(FirmwareInstallState.Failed(FirmwareInstallError.WriteFailed("boom")), i.state.value)
    }

    @Test fun progressMovesInFineSteps() = runTest {
        val seen = mutableListOf<FirmwareInstallState>()
        val i = installer(verifier = StubVerifier(v117), writer = FakeWriter(progressSteps = 20))
        launchCollect(i, seen)
        i.install(rp2350)
        advanceUntilIdle()
        assertTrue(seen.filterIsInstance<FirmwareInstallState.Writing>().map { it.fraction }.toSet().size >= 12)
    }

    // MARK: Life after an install

    @Test fun verifiedStateSurvivesTheBoardDisappearing() = runTest {
        val locator = FakeBootloaderLocator(listOf(rp2350))
        val i = installer(locator = locator, verifier = StubVerifier(v117))
        i.beginWatching()
        i.installWhenReady()
        advanceUntilIdle()
        locator.boards = emptyList()
        locator.emit()
        assertEquals(FirmwareInstallState.Verified(v117), i.state.value)
    }

    @Test fun resetAfterAVerifiedInstallReturnsToDetection() = runTest {
        val locator = FakeBootloaderLocator(listOf(rp2350))
        val i = installer(locator = locator, verifier = StubVerifier(v117))
        i.beginWatching()
        i.installWhenReady()
        advanceUntilIdle()
        locator.boards = emptyList()
        i.reset()
        assertEquals(FirmwareInstallState.WaitingForBoard, i.state.value)
    }

    @Test fun aSecondBoardAfterResetNeedsAFreshCommit() = runTest {
        val locator = FakeBootloaderLocator(listOf(rp2350))
        val writer = FakeWriter()
        val i = installer(locator = locator, verifier = StubVerifier(v117), writer = writer)
        i.beginWatching()
        i.installWhenReady()
        advanceUntilIdle()
        i.reset()
        locator.boards = listOf(rp2040)
        locator.emit()
        advanceUntilIdle()
        assertEquals(FirmwareInstallState.Ready(rp2040), i.state.value)
        assertFalse(i.isArmed)
        assertEquals(1, writer.written.size)
    }

    @Test fun preWriteFailureHoldsOverContinuedDetection() = runTest {
        val locator = FakeBootloaderLocator(listOf(rp2350))
        val i = installer(locator = locator, image = null)
        i.beginWatching()
        i.installWhenReady()
        val missing = FirmwareInstallState.Failed(FirmwareInstallError.ImageMissing("RP2350 (Pico 2)"))
        assertEquals(missing, i.state.value)
        locator.emit()
        assertEquals(missing, i.state.value)
        i.reset()
        assertEquals(FirmwareInstallState.Ready(rp2350), i.state.value)
        assertFalse(i.isArmed)
    }

    @Test fun resetAfterAWriteFailureRecovers() = runTest {
        val locator = FakeBootloaderLocator()
        val i = installer(locator = locator, writer = FakeWriter(failure = FirmwareInstallError.WriteFailed("x")))
        i.beginWatching()
        i.install(rp2350)
        advanceUntilIdle()
        assertTrue((i.state.value as FirmwareInstallState.Failed).error is FirmwareInstallError.WriteFailed)
        locator.boards = listOf(rp2350)
        i.reset()
        assertEquals(FirmwareInstallState.Ready(rp2350), i.state.value)
    }

    @Test fun resetIsRefusedMidInstall() = runTest {
        val locator = FakeBootloaderLocator()
        val i = installer(locator = locator)
        i.beginWatching()
        i.setStateForTesting(FirmwareInstallState.Writing(0.5), installing = true)
        i.reset()
        assertEquals(FirmwareInstallState.Writing(0.5), i.state.value)
        i.setStateForTesting(FirmwareInstallState.WaitingForDevice, installing = true)
        i.reset()
        assertEquals(FirmwareInstallState.WaitingForDevice, i.state.value)
        locator.emit()
        assertEquals(FirmwareInstallState.WaitingForDevice, i.state.value)
    }

    @Test fun abandonFreezesAFailureUntilReset() = runTest {
        val j = installer(locator = FakeBootloaderLocator())
        j.beginWatching()
        j.installWhenReady()
        j.abandon(FirmwareInstallError.NoBoardFound)
        assertEquals(FirmwareInstallState.Failed(FirmwareInstallError.NoBoardFound), j.state.value)
        assertFalse(j.isArmed)
    }

    @Test fun configureIsRefusedWhileInstalling() = runTest {
        val i = installer()
        i.setStateForTesting(FirmwareInstallState.Writing(0.1), installing = true)
        assertFalse(i.configure(writer = FakeWriter()))
        i.setStateForTesting(FirmwareInstallState.WaitingForBoard, installing = false)
        assertTrue(i.configure(writer = FakeWriter()))
    }

    @Test fun stopWatchingStopsTheLocator() = runTest {
        val locator = FakeBootloaderLocator()
        val i = installer(locator = locator)
        i.beginWatching()
        assertTrue(locator.started)
        i.stopWatching()
        assertFalse(locator.started)
        assertEquals(null, locator.onChange)
    }

    @Test fun messagesKeepTheMacOSWording() {
        assertEquals(
            "No board in bootloader mode was found. Hold the BOOTSEL button while plugging the board in.",
            FirmwareInstallError.NoBoardFound.message
        )
        assertEquals(
            "2 boards are in bootloader mode. Disconnect all but the one you want to update.",
            FirmwareInstallError.MultipleBoards(2).message
        )
        assertEquals("Writing the firmware failed: x", FirmwareInstallError.WriteFailed("x").message)
        assertEquals(
            "The device came back running firmware 1.1.6 instead of 1.1.7.",
            FirmwareInstallError.VersionMismatch("1.1.7", "1.1.6").message
        )
        assertEquals(
            "The firmware was written but the device did not reappear. Unplug it, plug it back in, and check whether it works.",
            FirmwareInstallError.DeviceDidNotReturn.message
        )
        assertFalse(FirmwareInstallError.WriteFailed("x").isMundane)
        assertTrue(FirmwareInstallError.MultipleBoards(2).isMundane)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.launchCollect(installer: FirmwareInstaller, into: MutableList<FirmwareInstallState>) =
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { installer.state.collect { into += it } }
