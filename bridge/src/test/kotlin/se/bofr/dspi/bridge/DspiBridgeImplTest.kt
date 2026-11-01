package se.bofr.dspi.bridge

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.core.FilterParams
import se.bofr.dspi.fake.FakeDspiCore

@OptIn(ExperimentalCoroutinesApi::class)
class DspiBridgeImplTest {
    private fun peq(freq: Float, gain: Float) = FilterParams(DspiConstants.FILTER_PEAKING, false, freq, 1f, gain, 0.707f)

    // ── Connection, identity, mapping ──

    @Test
    fun attachReadsTheDeviceAndCapturesTheBaseline() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        assertTrue(attach(b, core))
        assertTrue(b.connected)
        assertEquals(core.serial, b.selectedSerial)
        assertEquals(listOf(core.serial), b.availableSerials)
        assertEquals("1.1.6-beta5", b.firmwareVersion)
        assertEquals("RP2350", b.platformName)
        assertEquals(24f, b.preampMaxDB)
        assertEquals(17, b.numChannels)
        assertTrue(
            core.calls.containsAll(
                listOf("openFd(42, ${core.serial}, ${DspiConstants.VENDOR_ID})", "fetchAll", "captureBaseline", "rtaStart")
            )
        )
        assertFalse(b.presetDirty.value)
        // Visible on the graph: the first input pair and the enabled outputs (1-4)
        assertEquals((0..5).toList(), (0 until 17).filter { b.channelVisible(it) })
    }

    @Test
    fun demoModeSelectsTheFakeWithoutAnAttach() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        runCurrent()
        assertTrue(b.connected)
        assertEquals(core.serial, b.selectedSerial)
        assertEquals(DspiConstants.COMPAT_OK, b.compat)
        assertTrue(core.calls.contains("selectDevice(${core.serial})"))
    }

    @Test
    fun readsGoThroughTheAppIdMap() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        // App id 2 = output 1 = wire 8 (RP2350: 8 inputs); 11 = input 3 = wire 2
        assertEquals("Woofer L", b.channelName(2))
        assertEquals("USB 3", b.channelName(11))
        assertEquals("PDM", b.channelName(10))
        assertEquals("OUT1", b.channelDescriptor(2))
        assertEquals("IN3", b.channelDescriptor(11))
        assertEquals("#45C2A3", b.channelColor(2))
        assertEquals("#BA87F2", b.channelColor(10))
        assertEquals("#73C78C", b.channelColor(11))
        assertEquals(DspiConstants.FILTER_LOWSHELF, b.filterType(0, 0))
        assertEquals(DspiConstants.FILTER_XOVER_FIRST + 2, b.crossoverType(2, 0))
        assertEquals(2200f, b.crossoverFreq(2, 0))
        assertEquals(1000f, b.filterFreq(99, 0)) // absent channel: Qt's fallback
        assertEquals(13, b.inputAppId(4))
        assertTrue(b.isInputChannel(16))
        assertTrue(b.isPdmOutput(8))
        assertEquals(-3f, b.channelGainOffset(4)) // output 3 gain
    }

    @Test
    fun statusPeaksAreKeyedByAppIdAndClipsLatchForTenSeconds() = runTest {
        val core = FakeDspiCore()
        core.peaksOverride = List(17) { w -> w / 100f }
        val b = newBridge(core)
        attach(b, core)
        advanceTimeBy(61)
        assertEquals(0.08f, b.peakLevel(2)) // output 1 = wire 8
        assertEquals(0.02f, b.peakLevel(11)) // input 3 = wire 2
        assertEquals(0.01f, b.peakLevel(1))
        assertTrue(b.cpu0 in 22..24) // the fake's CPU load, polled
        core.simulateClip(9) // wire 9 = output 2 = app id 3
        advanceTimeBy(60)
        assertTrue(b.isClipping(3))
        assertEquals(1L shl 3, b.clipFlags) // re-keyed by app id
        assertEquals(1L shl 9, b.status.value.clipFlags) // BridgeStatus: per wire
        assertTrue(core.calls.contains("clearClips")) // read-then-clear
        advanceTimeBy(9_000)
        assertTrue(b.isClipping(3))
        advanceTimeBy(1_100)
        assertFalse(b.isClipping(3))
    }

    // ── Setters and sendOnly ──

    @Test
    fun setterRunsOnTheCoreThenEmitsOnce() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        val rev = b.stateRevision.value
        b.setMasterVolume(-6f)
        runCurrent()
        assertEquals(-6f, b.masterVolumeDB)
        assertEquals(-6f, b.state.value.masterVolumeDb)
        assertEquals(rev + 1, b.stateRevision.value)
    }

    @Test
    fun sendOnlyUpdatesTheDeviceWithoutStateChanged() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        val rev = b.stateRevision.value
        for (v in listOf(-10f, -11f, -12f)) b.setUserVolume(v, sendOnly = true)
        b.setLevellerAmount(70f, sendOnly = true)
        runCurrent()
        assertEquals(-12f, core.getState().userVolumeDb)
        assertEquals(rev, b.stateRevision.value)
        b.setUserVolume(-12f)
        runCurrent()
        assertEquals(rev + 1, b.stateRevision.value)
        assertEquals(-12f, b.userVolumeDB)
        assertEquals(70f, b.levellerAmount)
    }

    @Test
    fun outputGainPreviewShiftsTheCurveWithoutStateChanged() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        val events = collectEvents(b)
        val before = b.magnitudeCurve(2)
        val rev = b.stateRevision.value
        b.sendOutputGainToDevice(0, -6f)
        runCurrent()
        assertEquals(rev, b.stateRevision.value)
        assertTrue(BridgeEvent.PreviewChanged in events)
        val after = b.magnitudeCurve(2)
        assertEquals(before[100] - 6.0, after[100], 1e-6)
    }

    @Test
    fun setFilterKeepsBypassAndStartsALinkwitzTransformFlat() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        b.setBandBypass(0, 1, true)
        b.setFilter(0, 1, DspiConstants.FILTER_PEAKING, 3000f, -4f, 2f)
        b.setFilter(0, 4, DspiConstants.FILTER_LINKWITZ_TRANSFORM, 50f, 0f, 0.9f)
        runCurrent()
        assertTrue(b.filterBypass(0, 1))
        assertEquals(3000f, b.filterFreq(0, 1))
        assertEquals(50f, b.filterGain(0, 4)) // fp = f0
        assertEquals(0.9f, b.filterQp(0, 4)) // Qp = Q0
    }

    @Test
    fun everySetterPathReachesTheFake() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        b.setPreamp(3f)
        b.setInputPreamp(2, -4f)
        b.setBypass(true)
        b.setMasterVolumeMode(1)
        b.setUserMute(true)
        b.setLinkwitzTransform(11, 0, 40f, 0.8f, 25f, 0.6f)
        b.setCrossover(3, 2, DspiConstants.FILTER_XOVER_FIRST + 5, 120f)
        b.setCrossoverBypass(3, 0, true)
        b.setChannelDelay(0, 1.5f)
        b.setChannelDelay(2, 2.5f) // an output: the output delay
        b.setLoudness(true)
        b.setLoudnessRef(80f)
        b.setLoudnessIntensity(50f)
        b.setLoudnessOutputMask(0x3)
        b.setCrossfeed(true)
        b.setCrossfeedPreset(1)
        b.setCrossfeedITD(false)
        b.setCrossfeedOutputPairMask(1)
        b.setMatrixRoute(2, 5, true, -3.04f, true)
        b.setOutputGain(1, -2f)
        b.setOutputMute(1, true)
        b.setOutputDelay(1, 3f)
        b.setChannelName(2, "Bass")
        b.setPresetName(1, "Night")
        b.setPresetStartup(1, 1)
        b.setInputSource(b.dangerousOps.confirm(DangerousOp.HARDWARE_APPLY), 2)
        b.setLevellerEnabled(true)
        b.setLevellerSpeed(2)
        b.setLevellerMaxGain(9f)
        b.setLevellerGate(-50f)
        b.setLevellerLookahead(true)
        b.setLevellerMasks(1, 2)
        b.setPsybassEnabled(true)
        b.setPsybassParam(DspiConstants.PSYBASS_PARAM_CUTOFF, 500f)
        b.setPsybassOutputMask(0xF)
        b.setUpmixParam(DspiConstants.UPMIX_PARAM_ENABLED, 1f)
        b.setSubharmParam(DspiConstants.SUBHARM_PARAM_HOLD, 10f)
        b.setSubharmSolo(true)
        b.setTubeParam(DspiConstants.TUBE_PARAM_TYPE, 2f)
        b.setLimiterEnabled(0, true)
        b.setLimiterThreshold(0, -6f)
        b.setLimiterRelease(0, 200f)
        b.setLimiterLinkGroup(0, 2)
        b.setLgSoundSync(true)
        runCurrent()
        val s = b.state.value
        assertEquals(-4f, b.inputPreampDB(2))
        assertEquals(3f, b.inputPreampDB(0))
        assertTrue(b.bypass && b.userMute && s.masterVolumeMode == 1)
        assertEquals(25f, b.filterGain(11, 0))
        assertEquals(120f, b.crossoverFreq(3, 2))
        assertTrue(b.crossoverBypass(3, 0))
        assertEquals(1.5f, b.channelDelayMS(0))
        assertEquals(2.5f, b.outputDelayMS(0))
        assertTrue(b.loudnessEnabled && b.loudnessRefSPL == 80f && b.loudnessIntensity == 50f && b.loudnessOutputMask == 3)
        assertTrue(
            b.crossfeedEnabled && b.crossfeedPreset == 1 && b.crossfeedFeed == 6f && !b.crossfeedITD && b.crossfeedOutputPairMask == 1
        )
        assertTrue(b.matrixRouting(2, 5) && b.matrixInvert(2, 5))
        assertEquals(-3f, b.matrixGain(2, 5))
        assertTrue(b.outputMuted(1) && b.outputGainDB(1) == -2f && b.outputDelayMS(1) == 3f)
        assertEquals("Bass", b.channelName(2))
        assertEquals("Night", b.presetName(1))
        assertTrue(b.presetStartupMode == 1 && b.presetDefaultSlot == 1)
        assertEquals(2, b.inputSource)
        assertTrue(b.levellerEnabled && b.levellerSpeed == 2 && b.levellerMaxGain == 9f && b.levellerGate == -50f && b.levellerLookahead)
        assertTrue(b.levellerDetectorMask == 1 && b.levellerApplyMask == 2)
        assertTrue(b.psybassEnabled && b.psybassCutoff == 300f && b.psybassOutputMask == 0xF)
        assertEquals(1f, b.upmixParams[0])
        assertEquals(50f, b.subharmParams[DspiConstants.SUBHARM_PARAM_HOLD])
        assertTrue(b.subharmSolo)
        assertEquals(8f, b.tubeParams[DspiConstants.TUBE_PARAM_BIAS]) // 12AT7's row
        assertTrue(b.limiterEnabled(0) && b.limiterThreshold(0) == -6f && b.limiterRelease(0) == 200f && b.limiterLinkGroup(0) == 2)
        assertTrue(s.lgSoundSyncEnabled)
    }

    @Test
    fun bandListHelpers() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        assertTrue(b.canSetAllBypass(0, true, false))
        b.setAllBandsBypass(0, true, false)
        runCurrent()
        assertTrue((0 until 3).all { b.filterBypass(0, it) })
        assertFalse(b.canSetAllBypass(0, true, false))
        b.clearPeq(0)
        b.clearAllBands(2, crossover = true)
        runCurrent()
        assertTrue((0 until 10).all { b.filterType(0, it) == DspiConstants.FILTER_FLAT })
        assertEquals(DspiConstants.FILTER_FLAT, b.crossoverType(2, 0))
    }

    @Test
    fun routingAndCore1Helpers() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        // PDM (output 9) conflicts with the EQ worker outputs 3-4
        assertEquals(listOf(2, 3), b.core1ConflictOutputs(8))
        assertFalse(b.setOutputEnable(8, true))
        assertTrue(b.checkCore1Conflict(8))
        b.enableOutputResolvingConflict(8)
        runCurrent()
        assertTrue(b.outputEnabled(8))
        assertFalse(b.outputEnabled(2))
        b.directRouting()
        runCurrent()
        assertFalse(b.outputEnabled(8)) // a 1:1 layout turns PDM off
        assertTrue((0 until 8).all { b.matrixRouting(it, it) && b.outputEnabled(it) })
        b.clearRouting()
        runCurrent()
        assertTrue((0 until 8).none { b.matrixRouting(it, it) })
        assertEquals(0f, b.matrixGain(0, 0))
    }

    @Test
    fun limiterHelpers() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        b.setLimiterThreshold(1, -9f)
        b.setLimiterEnabled(1, true)
        runCurrent()
        b.copyLimiterToAll(1)
        b.linkAllLimiterPairs()
        runCurrent()
        assertTrue((0 until 9).all { b.limiterEnabled(it) && b.limiterThreshold(it) == -9f })
        assertEquals(listOf(1, 1, 2, 2, 3, 3, 4, 4, 0), (0 until 9).map { b.limiterLinkGroup(it) })
        b.unlinkAllLimiters()
        b.disableAllLimiters()
        runCurrent()
        assertTrue((0 until 9).none { b.limiterEnabled(it) || b.limiterLinkGroup(it) != 0 })
    }

    @Test
    fun clipboardCopiesAChannel() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        assertFalse(b.canPaste())
        b.copyChannel(2) // Woofer L
        b.pasteChannel(6) // output 5
        runCurrent()
        assertEquals(b.crossoverType(2, 0), b.crossoverType(6, 0))
        assertEquals(b.outputDelayMS(0), b.outputDelayMS(4))
        assertTrue(b.canPaste())
    }

    @Test
    fun resetChannelNamesAndPinOwners() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        b.resetChannelNames()
        runCurrent()
        assertEquals("USB 1", b.channelName(0))
        assertEquals("SPDIF 1 L", b.channelName(2))
        assertEquals("SPDIF 2 R", b.channelName(5))
        assertEquals("PDM", b.channelName(10))
        val owners = b.pinOwners()
        assertTrue(PinOwner(6, "Output 1/2", "output") in owners)
        assertTrue(PinOwner(10, "Subwoofer", "output") in owners)
        assertTrue(PinOwner(14, "I2S BCK", "clock") in owners)
    }

    @Test
    fun inputSourcesFollowTheEnabledInputs() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        assertEquals(listOf("USB", "S/PDIF", "I2S"), b.inputSources.map { it.name })
    }

    // ── Curves ──

    @Test
    fun curvesAreCachedAndIncludeTheGainOffset() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        val n = core.callCount("computeMagnitudeCurve")
        val c1 = b.magnitudeCurve(4) // output 3: tweeter, gain -3 dB
        val c2 = b.magnitudeCurve(4)
        assertEquals(n, core.callCount("computeMagnitudeCurve")) // all computed at attach
        assertEquals(c1, c2)
        assertEquals(201, c1.size)
        assertTrue(c1[200] < -2.5 && c1[200] > -3.5) // flat top at -3 dB
        assertTrue(c1[0] < -40) // high-pass
        assertEquals(201, b.curveFrequencies.size)
        assertEquals(10.0, b.curveFrequencies[0], 1e-9)
        assertEquals(201, b.phaseCurve(4, true).size)
    }

    @Test
    fun aFilterEditRecomputesOnlyThatChannelAndItsLinkedPartner() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        val mrev = b.magnitudesRevision.value
        val n = core.callCount("computeMagnitudeCurve")
        b.setFilter(3, 5, DspiConstants.FILTER_PEAKING, 1000f, 6f, 1f)
        runCurrent()
        assertEquals(n + 1, core.callCount("computeMagnitudeCurve"))
        assertTrue(b.magnitudesRevision.value > mrev)
        assertTrue(b.magnitudeCurve(3).maxOrNull()!! > 5.0) // the new +6 dB bell under the crossover
    }

    // ── Links ──

    @Test
    fun linkedPairsMirrorEditsAndPersistPerSerial() = runTest {
        val core = FakeDspiCore()
        val store = InMemoryLinkStore()
        val b = newBridge(core, store)
        attach(b, core)
        advanceTimeBy(61) // a status poll: 8 live inputs
        assertEquals(8, b.liveInputCount())
        assertEquals(12, b.pairPartner(11))
        assertTrue(b.pairAvailable(11))
        assertFalse(b.isInputLinked(11))
        b.setFilter(11, 0, DspiConstants.FILTER_PEAKING, 100f, 4f, 1f)
        runCurrent()
        assertFalse(b.inputPairMatches(11))
        b.setInputLinked(11, true, keepCh = 11)
        runCurrent()
        assertTrue(b.isInputLinked(12))
        assertEquals(11, b.linkedPartner(12))
        assertTrue(b.inputPairMatches(11))
        assertEquals(0b10, b.inputLinks)
        assertEquals(0b10, store.load(core.serial))
        // Edits on one side reach the other
        b.setFilter(12, 2, DspiConstants.FILTER_PEAKING, 5000f, -2f, 3f)
        b.setInputPreamp(2, -5f)
        runCurrent()
        assertEquals(5000f, b.filterFreq(11, 2))
        assertEquals(-5f, b.inputPreampDB(3))
        // Restored on the next attach
        b.detachDevice(core.serial)
        runCurrent()
        assertFalse(b.connected)
        b.restoreInputLinks(0) // nothing selected: not saved, so the store keeps 0b10
        attach(b, core)
        assertEquals(0b10, b.inputLinks)
    }

    // ── Unsaved changes ──

    @Test
    fun dirtyTrackingFollowsSettersPresetsAndRevert() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        assertFalse(b.presetDirty.value)
        b.setBypass(true)
        b.setOutputGain(0, -1f)
        runCurrent()
        assertTrue(b.presetDirty.value)
        assertEquals(listOf("Master EQ bypass: off → on", "Woofer L gain: 0.0 dB → -1.0 dB"), b.presetChanges())
        // Saving to the active slot is the new baseline
        val ops = b.dangerousOps
        assertEquals(se.bofr.dspi.core.PresetStatus.OK, b.savePreset(ops.confirm(DangerousOp.SAVE_PRESET), 0))
        assertFalse(b.presetDirty.value)
        b.setBypass(false)
        runCurrent()
        assertTrue(b.presetDirty.value)
        // Revert to saved
        assertEquals(se.bofr.dspi.core.FlashStatus.OK, b.loadParams())
        assertFalse(b.presetDirty.value)
        assertTrue(b.bypass)
        // Loading another preset
        assertEquals(se.bofr.dspi.core.PresetStatus.OK, b.loadPreset(1))
        assertEquals(1, b.activePresetSlot)
        assertFalse(b.presetDirty.value)
    }

    @Test
    fun hardwareEditsInIndependentModeAreUnsavedUntilSaveOutputConfig() = runTest {
        val core = FakeDspiCore(se.bofr.dspi.fake.demoDspState().copy(outputConfigMode = 0))
        val b = newBridge(core)
        attach(b, core)
        val ops = b.dangerousOps
        assertEquals(se.bofr.dspi.core.PinConfigStatus.SUCCESS, b.setOutputPin(ops.confirm(DangerousOp.HARDWARE_APPLY), 0, 20))
        assertEquals(20, b.outputPin(0))
        assertTrue(b.hardwareUnsaved.value)
        assertEquals(
            se.bofr.dspi.core.PinConfigStatus.PIN_IN_USE,
            b.setOutputPin(ops.confirm(DangerousOp.HARDWARE_APPLY), 1, 20)
        )
        assertEquals(se.bofr.dspi.core.PresetStatus.OK, b.saveOutputConfig(ops.confirm(DangerousOp.SAVE_OUTPUT_CONFIG)))
        assertFalse(b.hardwareUnsaved.value)
    }

    @Test
    fun outputConfigDirtyFollowsBeginOutputEditAndSave() = runTest {
        val core = FakeDspiCore(se.bofr.dspi.fake.demoDspState().copy(outputConfigMode = 0))
        val b = newBridge(core)
        attach(b, core)
        assertFalse(b.outputConfigDirty.value)
        b.beginOutputEdit()
        assertTrue(b.outputConfigDirty.value)
        b.setLimiterEnabled(0, true)
        assertEquals(se.bofr.dspi.core.PresetStatus.OK, b.saveOutputConfig(b.dangerousOps.confirm(DangerousOp.SAVE_OUTPUT_CONFIG)))
        assertFalse(b.outputConfigDirty.value)
    }

    @Test
    fun beginOutputEditIsIgnoredOutsideIndependentMode() = runTest {
        val core = FakeDspiCore(se.bofr.dspi.fake.demoDspState().copy(outputConfigMode = 1))
        val b = newBridge(core)
        attach(b, core)
        b.beginOutputEdit()
        assertFalse(b.outputConfigDirty.value)
    }

    // ── Devices ──

    @Test
    fun detachAndBootloaderDisconnect() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        val events = collectEvents(b)
        b.enterBootloader(b.dangerousOps.confirm(DangerousOp.ENTER_BOOTLOADER))
        runCurrent()
        assertFalse(b.connected)
        assertEquals("", b.selectedSerial)
        assertTrue(BridgeEvent.DeviceDeparted(core.serial) in events)
        assertEquals("", b.firmwareVersion)
        assertEquals(DspiConstants.COMPAT_UNKNOWN, b.compat)
    }

    @Test
    fun reconnectReopensTheSameFd() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        b.reconnect()
        runCurrent()
        assertTrue(b.connected)
        assertEquals(2, core.callCount("openFd"))
        assertEquals(1, core.callCount("closeFd"))
    }

    @Test
    fun attachFailureReportsAConnectionError() = runTest {
        val core = FakeDspiCore()
        core.failNext("openFd")
        val b = newBridge(core)
        assertFalse(b.attachDevice(1, core.serial, DspiConstants.VENDOR_ID))
        assertTrue(b.connectionError.startsWith("Could not open DSPi"))
    }

    @Test
    fun dacMuteAndControlInterfacesReportWhatTheDeviceKept() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        val events = collectEvents(b)
        b.setDacMute(true, true, 11, 5, 10)
        advanceTimeBy(151)
        runCurrent()
        assertTrue(BridgeEvent.DacMuteApplied(true) in events)
        b.setDacMute(true, true, 24, 5, 10) // invalid pin: not kept
        advanceTimeBy(151)
        runCurrent()
        assertTrue(BridgeEvent.DacMuteApplied(false) in events)
        b.setUart(true, 16, 16, false, 115200)
        advanceTimeBy(251)
        runCurrent()
        assertTrue(BridgeEvent.CtrlIfaceApplied(0, DspiConstants.PIN_CONFIG_INVALID_PIN) in events)
        b.setI2c(true, 18, 19, 0x42)
        advanceTimeBy(251)
        runCurrent()
        assertTrue(BridgeEvent.CtrlIfaceApplied(1, 0) in events)
    }

    @Test
    fun liveReadingsAndFailuresAreReported() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        assertEquals(48_000, b.fetchInputRate())
        assertTrue(b.fetchUpmixStatus() != null)
        assertEquals(9, b.fetchSubharmMeter().size)
        assertEquals(0f, b.fetchSubharmHeadroom())
        assertTrue(b.fetchLgStatus() != null)
        assertTrue(b.fetchInputLock(0) != null)
        assertTrue(b.fetchAdatOutStatus() != null)
        val events = collectEvents(b)
        core.failNext("setBypass")
        b.setBypass(true)
        runCurrent()
        assertTrue(events.any { it is BridgeEvent.CommandFailed && it.command == "setBypass" })
        assertNotEquals(null, b.state.value)
    }

    @Test
    fun limiterReductionIsPartOfTheStatus() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        b.setLimiterEnabled(0, true)
        runCurrent()
        val seen = mutableSetOf<List<Float>>()
        repeat(40) {
            advanceTimeBy(60)
            seen += b.status.value.limiterReduction
        }
        // The fake's meter moves while the peaks don't: the status still changes (D4, review B4)
        assertTrue("seen $seen", seen.size > 2)
        assertEquals(b.status.value.limiterReduction[0], b.limiterReduction(0))
        b.disableAllLimiters()
        runCurrent()
        advanceTimeBy(61)
        assertTrue(b.status.value.limiterReduction.isEmpty())
        assertEquals(0f, b.limiterReduction(0))
    }

    @Test
    fun aLiveBandDragMovesTheCachedCurveWithoutStateChanged() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        val rev = b.stateRevision.value
        val mrev = b.magnitudesRevision.value
        val before = b.magnitudeCurve(11)[140]
        b.sendBandLive(11, 5, FilterParams(DspiConstants.FILTER_PEAKING, false, b.curveFrequencies[140].toFloat(), 1f, 9f, 0.707f))
        runCurrent()
        assertEquals(rev, b.stateRevision.value)
        assertEquals(mrev, b.magnitudesRevision.value)
        assertEquals(before + 9.0, b.magnitudeCurve(11)[140], 0.2) // review B6
        assertEquals(9f, b.filterGain(11, 5))
    }

    @Test
    fun limitsComeFromTheCore() = runTest {
        val core =
            object : FakeDspiCore() {
                override fun preampMaxDb() = 12.5f

                override fun maxDelayMs() = 10f
            }
        val b = newBridge(core)
        attach(b, core)
        assertEquals(12.5f, b.preampMaxDB) // review B8
        assertEquals(10f, b.maxDelayMs)
    }

    @Test
    fun unlinkingStillRedrawsTheCurves() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        advanceTimeBy(61)
        b.setInputLinked(0, true, -1)
        runCurrent()
        val mrev = b.magnitudesRevision.value
        b.setInputLinked(0, false, -1)
        runCurrent()
        assertTrue(b.magnitudesRevision.value > mrev) // Qt always emits magnitudesChanged (review B9b)
    }
}
