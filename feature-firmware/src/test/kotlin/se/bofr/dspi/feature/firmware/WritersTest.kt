package se.bofr.dspi.feature.firmware

import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import se.bofr.dspi.usb.BootloaderDevice
import se.bofr.dspi.usb.BootloaderPermissionResult

/** Drive (SAF) write path — macOS writeImage / Linux FirmwareUpdater copy loop — and the :usb permission bridge. */
@OptIn(ExperimentalCoroutinesApi::class)
class WritersTest {
    @get:Rule val tmp = TemporaryFolder()

    private val rp2350 = BootloaderBoard(BootloaderBoard.Chip.RP2350, "/dev/bus/usb/001/005")
    private val bytes = TestUf2.image(512) // 256 KB of UF2: 16 chunks

    private fun volume(name: String = "RP2350") = DirVolume(tmp.newFolder("vol"), name)

    @Test fun copiesTheWholeFileInChunksWithProgress() = runTest {
        val vol = volume()
        val fractions = mutableListOf<Double>()
        DriveFirmwareWriter(vol).write(rp2350, firmwareImage(bytes), Uf2Image.parse(bytes)) { fractions += it }
        val out = File(vol.dir, "DSPi-RP2350-v1.1.7.uf2")
        assertArrayEquals(bytes, out.readBytes())
        assertEquals(bytes.size / FirmwareInstaller.WRITE_CHUNK_SIZE, fractions.size)
        assertEquals(1.0, fractions.last(), 0.0)
        assertTrue(fractions.toSet().size >= 12)
    }

    @Test fun lateErrorIsTheBootRomRebooting() {
        val vol = volume().apply { failAtChunk = 16 } // 15/16 = 0.9375 written... below threshold
        val e = runCatching { DriveFirmwareWriter.writeImage(bytes, "a.uf2", vol) {} }.exceptionOrNull()
        assertEquals(FirmwareInstallError.WriteFailed("Input/output error"), e)

        val big = TestUf2.image(2048) // 64 chunks; fail at the last one: 63/64 >= 0.95
        val vol2 = DirVolume(tmp.newFolder("vol2")).apply { failAtChunk = 64 }
        DriveFirmwareWriter.writeImage(big, "a.uf2", vol2) {}
    }

    @Test fun vanishedDriveIsARebootEvenEarly() {
        val vol = volume().apply {
            failAtChunk = 2
            vanishAtFailure = true
        }
        DriveFirmwareWriter.writeImage(bytes, "a.uf2", vol) {}
    }

    @Test fun earlyErrorWithTheDriveStillThereIsAFailure() {
        val vol = volume().apply { failAtChunk = 3 }
        val e = runCatching { DriveFirmwareWriter.writeImage(bytes, "a.uf2", vol) {} }.exceptionOrNull()
        assertEquals(FirmwareInstallError.WriteFailed("Input/output error"), e)
    }

    @Test fun missingDriveCannotBeOpened() {
        val vol = DirVolume(File(tmp.root, "nonexistent"))
        val e = runCatching { DriveFirmwareWriter.writeImage(bytes, "a.uf2", vol) {} }.exceptionOrNull()
        assertEquals(FirmwareInstallError.WriteFailed("could not open RP2350 for writing"), e)
    }

    @Test fun refusesADriveThatIsNotTheBoards() = runTest {
        val e = runCatching {
            DriveFirmwareWriter(volume("Photos")).write(rp2350, firmwareImage(bytes), Uf2Image.parse(bytes)) {}
        }.exceptionOrNull()
        assertEquals(FirmwareInstallError.WrongVolume("RP2350"), e)
    }

    @Test fun readinessFollowsTheChosenDrive() {
        assertEquals(false, DriveFirmwareWriter(null).isReady(rp2350))
        assertEquals(true, DriveFirmwareWriter(volume()).isReady(rp2350))
    }

    // MARK: :usb permission bridge

    private val device = BootloaderDevice(rp2350.deviceName, 0x000F)

    @Test fun permissionGrantedSubscribesBeforeAsking() = runTest {
        val usb = FakeUsbLocator(listOf(device)).apply { answer = true }
        val perms = LocatorBoardPermissions(usb)
        assertEquals(BoardAccess.GRANTED, perms.requestPermission(rp2350))
        assertEquals(listOf(rp2350.deviceName), usb.requested)
        assertTrue("collector must be active before requestPermission", usb.subscribersAtRequest >= 1)
    }

    @Test fun permissionDenied() = runTest {
        val usb = FakeUsbLocator(listOf(device)).apply { answer = false }
        assertEquals(BoardAccess.DENIED, LocatorBoardPermissions(usb).requestPermission(rp2350))
    }

    @Test fun answersForOtherBoardsAreIgnoredAndUnplugEndsTheWait() = runTest {
        val usb = FakeUsbLocator(listOf(device))
        val perms = LocatorBoardPermissions(usb)
        val result = async { perms.requestPermission(rp2350) }
        runCurrent()
        usb.results.emit(BootloaderPermissionResult("/dev/bus/usb/001/099", true))
        runCurrent()
        assertTrue(result.isActive)
        usb.deviceFlow.value = emptyList()
        runCurrent()
        assertEquals(BoardAccess.GONE, result.await())
    }

    @Test fun absentBoardIsGoneWithoutADialog() = runTest {
        val usb = FakeUsbLocator()
        assertEquals(BoardAccess.GONE, LocatorBoardPermissions(usb).requestPermission(rp2350))
        assertTrue(usb.requested.isEmpty())
    }

    @Test fun picobootWriterSkipsTheDialogWhenAlreadyAllowed() = runTest {
        val usb = FakeUsbLocator(listOf(device)).apply { granted += device.deviceName }
        val writer = PicobootFirmwareWriter(LocatorBoardPermissions(usb)) { null }
        assertTrue(writer.hasAccess(rp2350))
        assertEquals(BoardAccess.GRANTED, writer.obtainAccess(rp2350))
        assertTrue(usb.requested.isEmpty())
    }
}
