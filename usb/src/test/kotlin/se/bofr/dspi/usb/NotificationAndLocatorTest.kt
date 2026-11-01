package se.bofr.dspi.usb

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTest {
    private class RecordingControls : NotificationControls {
        val calls = mutableListOf<String>()
        override val model: Flow<NotificationModel> = emptyFlow()

        override fun current() = NotificationModel()

        override fun setUserVolume(db: Float) {
            calls += "vol($db)"
        }

        override fun setUserMute(muted: Boolean) {
            calls += "mute($muted)"
        }

        override fun loadPreset(slot: Int) {
            calls += "preset($slot)"
        }
    }

    private val connected =
        NotificationModel(connected = true, serial = "E6613852", userVolumeDb = -20f, presetSlot = 1, presetOccupied = 0b1011)

    @Test
    fun volumeStepsAndClamps() {
        val c = RecordingControls()
        assertTrue(NotificationActions.apply(NotificationAction.VOLUME_UP, connected, c))
        assertTrue(NotificationActions.apply(NotificationAction.VOLUME_DOWN, connected, c))
        assertTrue(NotificationActions.apply(NotificationAction.VOLUME_UP, connected.copy(userVolumeDb = -1f), c))
        assertFalse(NotificationActions.apply(NotificationAction.VOLUME_UP, connected.copy(userVolumeDb = 0f), c))
        assertTrue(NotificationActions.apply(NotificationAction.VOLUME_DOWN, connected.copy(userVolumeDb = -59f), c))
        assertFalse(NotificationActions.apply(NotificationAction.VOLUME_DOWN, connected.copy(userVolumeDb = -60f), c))
        assertEquals(listOf("vol(-18.0)", "vol(-22.0)", "vol(0.0)", "vol(-60.0)"), c.calls)
    }

    @Test
    fun muteToggles() {
        val c = RecordingControls()
        NotificationActions.apply(NotificationAction.TOGGLE_MUTE, connected, c)
        NotificationActions.apply(NotificationAction.TOGGLE_MUTE, connected.copy(userMute = true), c)
        assertEquals(listOf("mute(true)", "mute(false)"), c.calls)
    }

    @Test
    fun nextPresetCyclesOccupiedSlotsAndRefusesWhenDirty() {
        assertEquals(3, connected.nextPresetSlot)
        assertEquals(0, connected.copy(presetSlot = 3).nextPresetSlot)
        assertEquals(0, connected.copy(presetSlot = -1).nextPresetSlot)
        assertNull(connected.copy(presetOccupied = 0b10).nextPresetSlot)

        val c = RecordingControls()
        assertTrue(NotificationActions.apply(NotificationAction.NEXT_PRESET, connected, c))
        assertFalse(NotificationActions.apply(NotificationAction.NEXT_PRESET, connected.copy(presetDirty = true), c))
        assertEquals(listOf("preset(3)"), c.calls)
    }

    @Test
    fun nothingWhenDisconnected() {
        val c = RecordingControls()
        NotificationAction.entries.forEach { assertFalse(NotificationActions.apply(it, NotificationModel(), c)) }
        assertTrue(c.calls.isEmpty())
    }

    @Test
    fun text() {
        assertEquals(
            NotificationText("DSPi E6613852", "Volume -20.0 dB · Preset 2: Living room"),
            NotificationText.of(connected.copy(presetName = "Living room"))
        )
        assertEquals(
            NotificationText("DSPi E6613852", "Muted · Preset 2 · unsaved changes"),
            NotificationText.of(connected.copy(userMute = true, presetDirty = true))
        )
        assertEquals("Connecting…", NotificationText.of(NotificationModel()).body)
        val sv = NotificationStrings(volume = "Lautstärke %1\$s", presetNamed = "Voreinstellung %1\$d: %2\$s")
        assertEquals("Lautstärke -20.0 dB · Voreinstellung 2: X", NotificationText.of(connected.copy(presetName = "X"), sv).body)
    }

    @Test
    fun serviceActionNamesRoundTrip() {
        NotificationAction.entries.forEach {
            assertEquals(it, DspiConnectionService.actionOf(DspiConnectionService.ACTION_PREFIX + it.name))
        }
        assertNull(DspiConnectionService.actionOf(null))
        assertNull(DspiConnectionService.actionOf("android.intent.action.MAIN"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class UiVisibilityTrackerTest {
    @Test
    fun publishesEdgesAndDebouncesHide() = runTest {
        val seen = mutableListOf<Boolean>()
        val t = UiVisibilityTracker(backgroundScope, hideDebounceMs = 300) { seen += it }
        t.activityStarted()
        t.activityStarted() // second activity (e.g. permission dialog host)
        t.activityStopped()
        t.activityStopped()
        // Rotation: stop -> start within the debounce window: no flap.
        advanceTimeBy(100)
        runCurrent()
        t.activityStarted()
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(listOf(true), seen)

        t.activityStopped()
        t.activityStopped() // spurious
        advanceTimeBy(301)
        runCurrent()
        assertEquals(listOf(true, false), seen)
        t.activityStarted()
        assertEquals(listOf(true, false, true), seen)
    }
}

class VendorInterfaceTest {
    @Test
    fun picksOnlyInterfaceTwoVendorClass() {
        val dspi = listOf(
            UsbInterfaceDesc(0, 0, 0x01), // UAC AudioControl
            UsbInterfaceDesc(1, 0, 0x01), // AudioStreaming alt 0
            UsbInterfaceDesc(1, 1, 0x01), // AudioStreaming alt 1
            UsbInterfaceDesc(2, 0, 0xFF) // vendor
        )
        assertEquals(UsbInterfaceDesc(2, 0, 0xFF), findVendorInterface(dspi))
        // Never falls back to the audio interfaces.
        assertNull(findVendorInterface(dspi.take(3)))
        assertNull(findVendorInterface(listOf(UsbInterfaceDesc(2, 0, 0x01))))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BootloaderScannerTest {
    private class ScannerLocator(val s: BootloaderScanner) : BootloaderLocator {
        override val devices get() = s.devices
        override val permissionResults = kotlinx.coroutines.flow.MutableSharedFlow<BootloaderPermissionResult>()

        override fun start(context: android.content.Context) = Unit

        override fun stop(context: android.content.Context) = Unit

        override fun hasPermission(device: BootloaderDevice) = false

        override fun requestPermission(device: BootloaderDevice) = Unit

        override fun usbDevice(device: BootloaderDevice): android.hardware.usb.UsbDevice? = null
    }

    @Test
    fun listsBootselBoardsAndCounts() = runTest {
        val host = FakeUsbHost(CallLog())
        val s = BootloaderScanner(host, backgroundScope, pollMs = 1000)
        val locator = ScannerLocator(s)
        host.plug("/dev/bus/usb/001/004", "D", permission = true) // a DSPi: not a bootloader
        s.start()
        assertTrue(s.devices.value.isEmpty())

        val rp2350 = host.plug("/dev/bus/usb/001/010", "", vid = UsbIds.RPI_VID, pid = UsbIds.RP2350_BOOTSEL_PID)
        s.rescan()
        assertEquals(listOf(BootloaderDevice("/dev/bus/usb/001/010", UsbIds.RP2350_BOOTSEL_PID)), s.devices.value)
        assertTrue(s.devices.value.single().isRp2350)
        assertEquals(0 to 1, locator.counts())

        // Picked up by the poll, no broadcast needed.
        host.plug("/dev/bus/usb/001/011", "", vid = UsbIds.RPI_VID, pid = UsbIds.RP2040_BOOTSEL_PID)
        advanceTimeBy(1001)
        runCurrent()
        assertEquals(1 to 1, locator.counts())

        // Detach broadcast: the device may still be listed for a moment.
        s.rescan(gone = rp2350)
        assertEquals(1 to 0, locator.counts())
        assertEquals(rp2350, s.ref(BootloaderDevice(rp2350.deviceName, rp2350.pid)))

        s.stop()
    }
}
