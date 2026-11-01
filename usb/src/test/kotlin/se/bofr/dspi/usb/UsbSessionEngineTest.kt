package se.bofr.dspi.usb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UsbSessionEngineTest {
    private val log = CallLog()
    private val host = FakeUsbHost(log)
    private val core = FakeCore(log)
    private val service = FakeService(log)
    private var visible = true

    private fun TestScope.engine() = UsbSessionEngine(
        host = host,
        core = core,
        service = service,
        scope = backgroundScope
    ).also {
        it.start()
        it.setUiVisible(visible)
    }

    /**
     * advanceUntilIdle() does not run backgroundScope work (where the engine's
     * event loop lives), so advance virtual time well past every retry/grace delay.
     */
    private fun TestScope.settle() {
        advanceTimeBy(60_000)
        runCurrent()
    }

    @Test
    fun coldStartWithPermissionOpensClaimsHandsFdAndStartsService() = runTest {
        host.plug("/dev/bus/usb/001/004", "E66138", permission = true)
        val e = engine()
        e.scan(askPermission = true)
        settle()

        assertEquals(UsbSessionState.Open("E66138", UsbIds.DSPI_VID), e.state.value)
        assertEquals(
            listOf(
                "open(/dev/bus/usb/001/004)",
                "claim(itf=2,fd=40)",
                "core.attach(fd=40,E66138,vid=0x2e8b)",
                "service.start"
            ),
            log.calls
        )
        assertTrue(host.links.single().claimed)
        assertFalse(host.links.single().closed)
        assertEquals("E66138", e.lastSerial)
    }

    @Test
    fun coldStartWithoutPermissionRequestsItThenConnectsOnGrant() = runTest {
        val d = host.plug("/dev/bus/usb/001/004", "E66138")
        val e = engine()
        e.scan(askPermission = true)
        settle()
        assertEquals(UsbSessionState.PermissionPending("", dialogShown = true), e.state.value)
        assertEquals(listOf("requestPermission(/dev/bus/usb/001/004)"), log.calls)

        host.grant(d)
        e.onPermissionResult(d, granted = true)
        settle()
        assertEquals(UsbSessionState.Open("E66138", UsbIds.DSPI_VID), e.state.value)
        assertTrue(service.running)
    }

    @Test
    fun permissionDeniedThenExplicitRequestAsksAgain() = runTest {
        val d = host.plug("/dev/bus/usb/001/004", "E66138")
        val e = engine()
        e.scan(askPermission = true)
        settle()
        e.onPermissionResult(d, granted = false)
        settle()
        assertEquals(UsbSessionState.PermissionDenied(""), e.state.value)
        assertEquals(listOf(DspiUsbDevice(d.deviceName, UsbIds.DSPI_VID, null, false)), e.devices.value)

        log.clear()
        e.requestPermission()
        settle()
        assertEquals(listOf("requestPermission(/dev/bus/usb/001/004)"), log.calls)
    }

    @Test
    fun duplicateScansAskOnlyOnce() = runTest {
        host.plug("/dev/bus/usb/001/004", "E66138")
        val e = engine()
        e.scan(askPermission = true)
        e.scan(askPermission = true)
        settle()
        assertEquals(1, log.calls.count { it.startsWith("requestPermission") })
    }

    @Test
    fun detachTearsDownCoreFirstThenConnectionThenService() = runTest {
        val d = host.plug("/dev/bus/usb/001/004", "E66138", permission = true)
        val e = engine()
        e.scan(askPermission = true)
        settle()
        log.clear()

        host.unplug(d)
        e.onDetached(d)
        settle()
        assertEquals(listOf("core.detach(E66138)", "release(fd=40)", "close(fd=40)", "service.stop"), log.calls)
        assertEquals(UsbSessionState.NoDevice, e.state.value)
        assertTrue(host.links.single().closed)
        assertEquals("E66138", e.lastSerial) // kept for re-plug
    }

    @Test
    fun reattachWithImplicitPermissionReconnects() = runTest {
        val d = host.plug("/dev/bus/usb/001/004", "E66138", permission = true)
        val e = engine()
        e.scan(askPermission = true)
        settle()
        host.unplug(d)
        e.onDetached(d)
        settle()
        log.clear()

        // Re-plug: new usbfs name; the activity intent carries implicit permission.
        val d2 = host.plug("/dev/bus/usb/001/005", "E66138", permission = true)
        e.onAttached(d2, fromActivityIntent = false) // broadcast
        e.onAttached(d2, fromActivityIntent = true) // activity intent: duplicate, ignored
        settle()
        assertEquals(UsbSessionState.Open("E66138", UsbIds.DSPI_VID), e.state.value)
        assertEquals(1, log.calls.count { it.startsWith("core.attach") })
    }

    @Test
    fun attachBroadcastWithoutPermissionWaitsForActivityIntent() = runTest {
        val e = engine()
        settle()
        val d = host.plug("/dev/bus/usb/001/004", "E66138")
        e.onAttached(d, fromActivityIntent = false)
        runCurrent()
        assertEquals(UsbSessionState.PermissionRequired(""), e.state.value)

        // The activity intent lands inside the grace window with implicit permission.
        advanceTimeBy(500)
        host.grant(d)
        e.onAttached(d, fromActivityIntent = true)
        settle()
        assertEquals(UsbSessionState.Open("E66138", UsbIds.DSPI_VID), e.state.value)
        assertFalse(log.calls.any { it.startsWith("requestPermission") })
    }

    @Test
    fun attachBroadcastWithoutPermissionAsksAfterGraceWhenVisible() = runTest {
        val e = engine()
        settle()
        val d = host.plug("/dev/bus/usb/001/004", "E66138")
        e.onAttached(d, fromActivityIntent = false)
        advanceTimeBy(UsbSessionEngine.ATTACH_INTENT_GRACE_MS - 1)
        runCurrent()
        assertFalse(log.calls.any { it.startsWith("requestPermission") })
        settle()
        assertEquals(listOf("requestPermission(/dev/bus/usb/001/004)"), log.calls)
    }

    @Test
    fun noAutomaticPromptWhileNoUiIsVisible() = runTest {
        visible = false
        val e = engine()
        val d = host.plug("/dev/bus/usb/001/004", "E66138")
        e.onAttached(d, fromActivityIntent = false)
        settle()
        assertTrue(log.calls.isEmpty())
        assertEquals(UsbSessionState.PermissionRequired(""), e.state.value)

        // The UI comes back: now the dialog is shown.
        e.setUiVisible(true)
        settle()
        assertEquals(listOf("requestPermission(/dev/bus/usb/001/004)"), log.calls)
        assertEquals(UsbSessionState.PermissionPending("", dialogShown = true), e.state.value)
    }

    @Test
    fun legacyVidConnectsAndPassesVidToCore() = runTest {
        host.plug("/dev/bus/usb/001/004", "OLD1", permission = true, vid = UsbIds.DSPI_LEGACY_VID)
        val e = engine()
        e.scan(askPermission = true)
        settle()
        assertEquals(UsbSessionState.Open("OLD1", UsbIds.DSPI_LEGACY_VID), e.state.value)
        assertTrue(log.calls.contains("core.attach(fd=40,OLD1,vid=0x2e8a)"))
        assertTrue(e.devices.value.single().legacyVid)
    }

    @Test
    fun bootloaderAndForeignDevicesAreIgnored() = runTest {
        val e = engine()
        val boot = host.plug("/dev/bus/usb/001/007", "B", permission = true, vid = UsbIds.RPI_VID, pid = UsbIds.RP2350_BOOTSEL_PID)
        val other = host.plug("/dev/bus/usb/001/008", "X", permission = true, vid = 0x1234, pid = 0x5678)
        e.onAttached(boot, fromActivityIntent = true)
        e.onAttached(other, fromActivityIntent = false)
        e.scan(askPermission = true)
        settle()
        assertTrue(log.calls.isEmpty())
        assertEquals(UsbSessionState.NoDevice, e.state.value)
        assertTrue(e.devices.value.isEmpty())
    }

    @Test
    fun secondUnitDoesNotStealButSameSerialNewInstanceReplacesStaleOne() = runTest {
        val a = host.plug("/dev/bus/usb/001/004", "AAA", permission = true)
        val e = engine()
        e.scan(askPermission = true)
        settle()
        log.clear()

        val b = host.plug("/dev/bus/usb/001/005", "BBB", permission = true)
        e.onAttached(b, fromActivityIntent = false)
        settle()
        assertTrue(log.calls.isEmpty())
        assertEquals(UsbSessionState.Open("AAA", UsbIds.DSPI_VID), e.state.value)
        assertEquals(listOf("AAA", "BBB"), e.devices.value.map { it.serial })

        // AAA re-enumerated before its detach broadcast arrived.
        host.unplug(a)
        val a2 = host.plug("/dev/bus/usb/001/006", "AAA", permission = true)
        e.onAttached(a2, fromActivityIntent = false)
        settle()
        assertEquals(
            listOf(
                "core.detach(AAA)",
                "release(fd=40)",
                "close(fd=40)",
                "service.stop",
                "open(/dev/bus/usb/001/006)",
                "claim(itf=2,fd=41)",
                "core.attach(fd=41,AAA,vid=0x2e8b)",
                "service.start"
            ),
            log.calls
        )
        // The late detach of the old instance is a no-op.
        log.clear()
        e.onDetached(a)
        settle()
        assertTrue(log.calls.isEmpty())
    }

    @Test
    fun detachOfOpenUnitSwitchesToSurvivor() = runTest {
        val a = host.plug("/dev/bus/usb/001/004", "AAA", permission = true)
        host.plug("/dev/bus/usb/001/005", "BBB", permission = true)
        val e = engine()
        e.scan(askPermission = true)
        settle()
        assertEquals(UsbSessionState.Open("AAA", UsbIds.DSPI_VID), e.state.value)

        host.unplug(a)
        e.onDetached(a)
        settle()
        assertEquals(UsbSessionState.Open("BBB", UsbIds.DSPI_VID), e.state.value)
    }

    @Test
    fun rescanPrefersPreviousSerial() = runTest {
        host.plug("/dev/bus/usb/001/004", "AAA", permission = true)
        val b = host.plug("/dev/bus/usb/001/005", "BBB", permission = true)
        val e = engine()
        e.scan(askPermission = true)
        settle()
        e.select("BBB")
        settle()
        assertEquals(UsbSessionState.Open("BBB", UsbIds.DSPI_VID), e.state.value)

        backgroundScope.launch { e.close() }
        settle()
        e.scan(askPermission = true)
        settle()
        assertEquals(UsbSessionState.Open("BBB", UsbIds.DSPI_VID), e.state.value)
        assertEquals(b.deviceName, e.devices.value.first { it.serial == "BBB" }.deviceName)
    }

    @Test
    fun openRetriesWithBackoffLikeMacOs() = runTest {
        host.plug("/dev/bus/usb/001/004", "E66138", permission = true)
        host.failOpens = 2
        val e = engine()
        e.scan(askPermission = true)
        runCurrent()
        assertEquals(1, log.calls.count { it.startsWith("open") })
        advanceTimeBy(100)
        runCurrent()
        assertEquals(2, log.calls.count { it.startsWith("open") })
        advanceTimeBy(200)
        runCurrent()
        assertEquals(3, log.calls.count { it.startsWith("open") })
        assertEquals(UsbSessionState.Open("E66138", UsbIds.DSPI_VID), e.state.value)
    }

    @Test
    fun openGivesUpAfterRetriesAndReportsBusy() = runTest {
        host.plug("/dev/bus/usb/001/004", "E66138", permission = true)
        host.failOpens = 100
        val e = engine()
        // setUiVisible(true) in engine() already scanned (Linux pollHotplug: retry while disconnected).
        settle()
        assertEquals(1 + UsbSessionEngine.OPEN_RETRY_DELAYS_MS.size, log.calls.count { it.startsWith("open") })
        assertEquals(UsbSessionState.Failed("E66138", "Device busy (another app has it open)"), e.state.value)
        assertFalse(service.running)
    }

    @Test
    fun claimFailureClosesConnectionAndNeverReachesCore() = runTest {
        host.plug("/dev/bus/usb/001/004", "E66138", permission = true)
        host.failClaims = 100
        val e = engine()
        e.scan(askPermission = true)
        settle()
        assertFalse(log.calls.any { it.startsWith("core.attach") })
        assertTrue(host.links.all { it.closed })
        assertTrue(e.state.value is UsbSessionState.Failed)
    }

    @Test
    fun coreAttachFailureReleasesAndCloses() = runTest {
        host.plug("/dev/bus/usb/001/004", "E66138", permission = true)
        core.result = AttachResult(false, "bulk read failed")
        val e = engine()
        // setUiVisible(true) in engine() already scanned (Linux pollHotplug: retry while disconnected).
        settle()
        assertEquals(
            listOf(
                "open(/dev/bus/usb/001/004)",
                "claim(itf=2,fd=40)",
                "core.attach(fd=40,E66138,vid=0x2e8b)",
                "release(fd=40)",
                "close(fd=40)"
            ),
            log.calls
        )
        assertEquals(UsbSessionState.Failed("E66138", "bulk read failed"), e.state.value)
        assertFalse(service.running)
    }

    @Test
    fun closeTearsDownAndSuppressesAutoConnectUntilAsked() = runTest {
        val d = host.plug("/dev/bus/usb/001/004", "E66138", permission = true)
        val e = engine()
        e.scan(askPermission = true)
        settle()
        log.clear()

        backgroundScope.launch { e.close() }
        settle()
        assertEquals(listOf("core.detach(E66138)", "release(fd=40)", "close(fd=40)", "service.stop"), log.calls)
        assertEquals(UsbSessionState.NoDevice, e.state.value)

        log.clear()
        e.onAttached(d.copy(deviceName = "/dev/bus/usb/001/009"), fromActivityIntent = false)
        settle()
        assertTrue(log.calls.isEmpty())

        e.onAttached(d, fromActivityIntent = true)
        settle()
        assertEquals(UsbSessionState.Open("E66138", UsbIds.DSPI_VID), e.state.value)
    }

    @Test
    fun serviceRunsExactlyWhileOpen() = runTest {
        val d = host.plug("/dev/bus/usb/001/004", "E66138", permission = true)
        val e = engine()
        assertFalse(service.running)
        e.scan(askPermission = true)
        settle()
        assertTrue(service.running)
        host.unplug(d)
        e.onDetached(d)
        settle()
        assertFalse(service.running)
        assertEquals(1, log.calls.count { it == "service.start" })
        assertEquals(1, log.calls.count { it == "service.stop" })
    }

    // ── Review wave 0A fixes ────────────────────────────────────────────

    @Test
    fun coldStartWithoutUiQueuesThePromptUntilVisible() = runTest {
        visible = false
        host.plug("/dev/bus/usb/001/004", "E66138")
        val e = engine()
        e.scan(askPermission = true)
        settle()
        assertEquals(UsbSessionState.PermissionPending("", dialogShown = false), e.state.value)
        assertTrue(log.calls.isEmpty())

        e.setUiVisible(true)
        settle()
        assertEquals(UsbSessionState.PermissionPending("", dialogShown = true), e.state.value)
        assertEquals(listOf("requestPermission(/dev/bus/usb/001/004)"), log.calls)
    }

    @Test
    fun denialIsRememberedUntilExplicitRequestOrDetach() = runTest {
        val d = host.plug("/dev/bus/usb/001/004", "E66138")
        val e = engine()
        e.scan(askPermission = true)
        settle()
        e.onPermissionResult(d, granted = false)
        settle()
        log.clear()

        // Rotation / Application restart / UI return: no nagging.
        e.scan(askPermission = true)
        e.setUiVisible(false)
        e.setUiVisible(true)
        e.onAttached(d, fromActivityIntent = true)
        e.scan(askPermission = true)
        settle()
        assertTrue(log.calls.isEmpty())
        assertEquals(UsbSessionState.PermissionDenied(""), e.state.value)

        // Explicit request asks again.
        e.requestPermission()
        settle()
        assertEquals(listOf("requestPermission(/dev/bus/usb/001/004)"), log.calls)
        e.onPermissionResult(d, granted = false)
        settle()

        // Re-plug forgets the denial.
        log.clear()
        host.unplug(d)
        e.onDetached(d)
        val d2 = host.plug("/dev/bus/usb/001/005", "E66138")
        e.onAttached(d2, fromActivityIntent = true)
        settle()
        assertEquals(listOf("requestPermission(/dev/bus/usb/001/005)"), log.calls)
    }

    @Test
    fun staleActivityIntentForAGoneDeviceIsIgnored() = runTest {
        val e = engine()
        settle()
        e.onAttached(UsbDeviceRef("/dev/bus/usb/001/099", UsbIds.DSPI_VID, UsbIds.DSPI_PID), fromActivityIntent = true)
        settle()
        assertTrue(log.calls.isEmpty())
        assertEquals(UsbSessionState.NoDevice, e.state.value)
    }

    @Test
    fun grantForVanishedDeviceDoesNotLeavePending() = runTest {
        val d = host.plug("/dev/bus/usb/001/004", "E66138")
        val e = engine()
        e.scan(askPermission = true)
        settle()
        host.unplug(d)
        e.onPermissionResult(d, granted = true) // answer raced the unplug
        settle()
        assertEquals(UsbSessionState.NoDevice, e.state.value)
    }

    @Test
    fun detachOfIdleUnitRefreshesStaleFailedState() = runTest {
        val a = host.plug("/dev/bus/usb/001/004", "AAA", permission = true)
        host.plug("/dev/bus/usb/001/005", "BBB")
        host.failClaims = 100
        val e = engine()
        settle()
        assertTrue(e.state.value is UsbSessionState.Failed)
        host.unplug(a)
        e.onDetached(a)
        settle()
        assertEquals(UsbSessionState.PermissionRequired(""), e.state.value)
    }

    @Test
    fun closeWithoutStartReturnsAtOnceAndIsIdempotent() = runTest {
        val e = UsbSessionEngine(host, core, service, backgroundScope)
        e.close() // never started: must not hang
        e.start()
        e.setUiVisible(true)
        host.plug("/dev/bus/usb/001/004", "E66138", permission = true)
        e.scan(askPermission = true)
        settle()
        val closer = backgroundScope.launch {
            e.close()
            e.close()
        }
        settle()
        assertTrue(closer.isCompleted)
        assertEquals(1, log.calls.count { it == "close(fd=40)" })
        assertEquals(UsbSessionState.NoDevice, e.state.value)
    }

    @Test
    fun teardownCompletesWhenTheEngineScopeIsCancelledMidDetach() = runTest {
        val engineScope = CoroutineScope(backgroundScope.coroutineContext + Job())
        val e = UsbSessionEngine(host, core, service, engineScope).also {
            it.start()
            it.setUiVisible(true)
        }
        host.plug("/dev/bus/usb/001/004", "E66138", permission = true)
        e.scan(askPermission = true)
        settle()
        log.clear()

        val gate = CompletableDeferred<Unit>()
        core.detachGate = gate
        var closed = false
        backgroundScope.launch {
            e.close()
            closed = true
        }
        runCurrent()
        assertEquals(listOf("core.detach(E66138)"), log.calls)

        engineScope.cancel() // app scope dies while the bridge is still detaching
        runCurrent()
        gate.complete(Unit)
        settle()
        assertEquals(listOf("core.detach(E66138)", "release(fd=40)", "close(fd=40)", "service.stop"), log.calls)
        assertTrue(closed)
        assertFalse(service.running)
        e.close() // loop gone: returns at once
    }

    @Test
    fun cancelledCallerDoesNotAbortTeardown() = runTest {
        host.plug("/dev/bus/usb/001/004", "E66138", permission = true)
        val e = engine()
        e.scan(askPermission = true)
        settle()
        log.clear()
        val gate = CompletableDeferred<Unit>()
        core.detachGate = gate
        val caller = backgroundScope.launch { e.close() }
        runCurrent()
        caller.cancel()
        gate.complete(Unit)
        settle()
        assertEquals(listOf("core.detach(E66138)", "release(fd=40)", "close(fd=40)", "service.stop"), log.calls)
    }

    @Test
    fun cancellationDuringAttachUndoesTheHandOff() = runTest {
        val engineScope = CoroutineScope(backgroundScope.coroutineContext + Job())
        val e = UsbSessionEngine(host, core, service, engineScope).also {
            it.start()
            it.setUiVisible(true)
        }
        host.plug("/dev/bus/usb/001/004", "E66138", permission = true)
        core.attachGate = CompletableDeferred()
        e.scan(askPermission = true)
        runCurrent()
        engineScope.cancel()
        settle()
        assertEquals(
            listOf(
                "open(/dev/bus/usb/001/004)",
                "claim(itf=2,fd=40)",
                "core.attach(fd=40,E66138,vid=0x2e8b)",
                "core.detach(E66138)",
                "release(fd=40)",
                "close(fd=40)"
            ),
            log.calls
        )
        assertFalse(service.running)
    }

    @Test
    fun detachPostedDuringSuspendedAttachIsHandledAfterIt() = runTest {
        val d = host.plug("/dev/bus/usb/001/004", "E66138", permission = true)
        val gate = CompletableDeferred<Unit>()
        core.attachGate = gate
        val e = engine()
        settle()
        assertEquals("core.attach(fd=40,E66138,vid=0x2e8b)", log.calls.last())

        host.unplug(d)
        e.onDetached(d)
        settle()
        assertEquals("core.attach(fd=40,E66138,vid=0x2e8b)", log.calls.last()) // still waiting for the bridge

        gate.complete(Unit)
        settle()
        assertEquals(
            listOf(
                "core.attach(fd=40,E66138,vid=0x2e8b)",
                "service.start",
                "core.detach(E66138)",
                "release(fd=40)",
                "close(fd=40)",
                "service.stop"
            ),
            log.calls.drop(2)
        )
        assertEquals(1, log.calls.count { it == "close(fd=40)" })
        assertEquals(UsbSessionState.NoDevice, e.state.value)
    }

    @Test
    fun refusedServiceStartIsRetriedWhenUiReturns() = runTest {
        service.refuseStarts = 1
        host.plug("/dev/bus/usb/001/004", "E66138", permission = true)
        val e = engine()
        settle()
        assertTrue(e.state.value is UsbSessionState.Open)
        assertFalse(service.running)
        e.setUiVisible(false)
        e.setUiVisible(true)
        settle()
        assertTrue(service.running)
        e.setUiVisible(true)
        settle()
        assertEquals(1, log.calls.count { it == "service.start" })
    }
}
