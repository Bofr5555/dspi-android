package se.bofr.dspi.bridge

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.core.DspiCoreApi
import se.bofr.dspi.core.FilterParams
import se.bofr.dspi.core.FlashStatus
import se.bofr.dspi.core.PinConfigStatus
import se.bofr.dspi.core.PresetStatus
import se.bofr.dspi.fake.FakeDspiCore
import se.bofr.dspi.fake.demoDspState

/**
 * G1 (owner decision): with an incompatible firmware the bridge is read-only.
 * Every public write path is listed in [writePaths]; [everyBridgeMemberIsClassified]
 * fails when a member is added to the contract without being put in that list
 * or in [READ_MEMBERS].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadOnlyTest {
    private fun tooOld() = FakeDspiCore(demoDspState().copy(compat = DspiConstants.COMPAT_FIRMWARE_TOO_OLD))

    private val peq = FilterParams(DspiConstants.FILTER_PEAKING, false, 1000f, 3f, 1f, 0.707f)

    /** Every write / command reachable from the contract, by the name it reports in CommandFailed. */
    private fun TestScope.writePaths(b: DspiBridgeImpl): List<Pair<String, suspend () -> Unit>> {
        val ops = b.dangerousOps
        fun hw() = ops.confirm(DangerousOp.HARDWARE_APPLY)
        val out = 2 // app id of output 1
        return listOf<Pair<String, suspend () -> Unit>>(
            "setPreamp" to { b.setPreamp(-1f) },
            "sendPreampToDevice" to { b.sendPreampToDevice(-1f) },
            "setInputPreamp" to { b.setInputPreamp(0, 1f) },
            "setInputPreamp" to { b.setInputPreamp(0, 1f, sendOnly = true) },
            "setBypass" to { b.setBypass(true) },
            "setMasterVolume" to { b.setMasterVolume(-3f) },
            "setMasterVolume" to { b.setMasterVolume(-3f, sendOnly = true) },
            "setMasterVolumeMode" to { b.setMasterVolumeMode(1) },
            "saveMasterVolume" to { assertEquals(PresetStatus.NO_REPLY, b.saveMasterVolume(ops.confirm(DangerousOp.SAVE_MASTER_VOLUME))) },
            "setUserVolume" to { b.setUserVolume(-3f) },
            "setUserVolume" to { b.setUserVolume(-3f, sendOnly = true) },
            "setUserMute" to { b.setUserMute(true) },
            "setFilter" to { b.setFilter(0, 0, DspiConstants.FILTER_PEAKING, 1000f, 3f, 1f) },
            "setLinkwitzTransform" to { b.setLinkwitzTransform(0, 0, 50f, 0.7f, 30f, 0.5f) },
            "setBandBypass" to { b.setBandBypass(0, 0, true) },
            "setCrossover" to { b.setCrossover(out, 0, 1, 100f) },
            "setCrossoverBypass" to { b.setCrossoverBypass(out, 0, true) },
            "setChannelDelay" to { b.setChannelDelay(0, 1f) },
            "clearPeq" to { b.clearPeq(0) },
            "clearAllBands" to { b.clearAllBands(out, true) },
            "setAllBandsBypass" to { b.setAllBandsBypass(0, true, false) },
            "setLoudness" to { b.setLoudness(true) },
            "setLoudnessRef" to { b.setLoudnessRef(80f) },
            "setLoudnessRef" to { b.setLoudnessRef(80f, sendOnly = true) },
            "setLoudnessIntensity" to { b.setLoudnessIntensity(50f) },
            "setLoudnessIntensity" to { b.setLoudnessIntensity(50f, sendOnly = true) },
            "setLoudnessOutputMask" to { b.setLoudnessOutputMask(3) },
            "setCrossfeed" to { b.setCrossfeed(true) },
            "setCrossfeedPreset" to { b.setCrossfeedPreset(1) },
            "setCrossfeedFreq" to { b.setCrossfeedFreq(700f) },
            "setCrossfeedFreq" to { b.setCrossfeedFreq(700f, sendOnly = true) },
            "setCrossfeedFeed" to { b.setCrossfeedFeed(4f) },
            "setCrossfeedFeed" to { b.setCrossfeedFeed(4f, sendOnly = true) },
            "setCrossfeedITD" to { b.setCrossfeedITD(true) },
            "setCrossfeedOutputPairMask" to { b.setCrossfeedOutputPairMask(1) },
            "setMatrixRoute" to { b.setMatrixRoute(0, 0, true, -3f, false) },
            "setMatrixRoute" to { b.setMatrixRoute(0, 0, true, -3f, false, sendOnly = true) },
            "setOutputEnable" to { assertFalse(b.setOutputEnable(1, true)) },
            "setOutputGain" to { b.setOutputGain(0, -1f) },
            "sendOutputGainToDevice" to { b.sendOutputGainToDevice(0, -1f) },
            "setOutputMute" to { b.setOutputMute(0, true) },
            "setOutputDelay" to { b.setOutputDelay(0, 1f) },
            "sendOutputDelayToDevice" to { b.sendOutputDelayToDevice(0, 1f) },
            "directRouting" to { b.directRouting() },
            "directRouting" to { b.directRouting(2, true) },
            "clearRouting" to { b.clearRouting() },
            "clearRouting" to { b.clearRouting(2) },
            "enableOutputResolvingConflict" to { b.enableOutputResolvingConflict(8) },
            "setOutputPin" to { assertEquals(PinConfigStatus.NO_REPLY, b.setOutputPin(hw(), 0, 20)) },
            "setOutputConfigMode" to { b.setOutputConfigMode(0) },
            "saveOutputConfig" to { b.saveOutputConfig(ops.confirm(DangerousOp.SAVE_OUTPUT_CONFIG)) },
            "setChannelName" to { b.setChannelName(0, "x") },
            "resetChannelNames" to { b.resetChannelNames() },
            "savePreset" to { b.savePreset(ops.confirm(DangerousOp.SAVE_PRESET), 1) },
            "loadPreset" to { assertEquals(PresetStatus.NO_REPLY, b.loadPreset(1)) },
            "deletePreset" to { b.deletePreset(ops.confirm(DangerousOp.DELETE_PRESET), 1) },
            "copyPreset" to { b.copyPreset(ops.confirm(DangerousOp.COPY_PRESET), 0, 2) },
            "setPresetName" to { b.setPresetName(1, "x") },
            "setPresetStartup" to { b.setPresetStartup(0, 1) },
            "saveParams" to { assertEquals(FlashStatus.ERR_WRITE, b.saveParams(ops.confirm(DangerousOp.SAVE_PARAMS))) },
            "loadParams" to { b.loadParams() },
            "factoryReset" to { b.factoryReset(ops.confirm(DangerousOp.FACTORY_RESET)) },
            "clearClips" to { b.clearClips() },
            "setInputSource" to { b.setInputSource(hw(), 1) },
            "setLevellerEnabled" to { b.setLevellerEnabled(true) },
            "setLevellerAmount" to { b.setLevellerAmount(50f) },
            "setLevellerAmount" to { b.setLevellerAmount(50f, sendOnly = true) },
            "setLevellerSpeed" to { b.setLevellerSpeed(1) },
            "setLevellerMaxGain" to { b.setLevellerMaxGain(6f) },
            "setLevellerGate" to { b.setLevellerGate(-60f) },
            "setLevellerLookahead" to { b.setLevellerLookahead(true) },
            "setLevellerMasks" to { b.setLevellerMasks(3, 3) },
            "setPsybassEnabled" to { b.setPsybassEnabled(true) },
            "setPsybassParam" to { b.setPsybassParam(0, 80f) },
            "setPsybassParam" to { b.setPsybassParam(0, 80f, sendOnly = true) },
            "setPsybassOutputMask" to { b.setPsybassOutputMask(3) },
            "setUpmixParam" to { b.setUpmixParam(0, 1f) },
            "setSubharmParam" to { b.setSubharmParam(0, 1f) },
            "setSubharmSolo" to { b.setSubharmSolo(true) },
            "setTubeParam" to { b.setTubeParam(0, 1f) },
            "setLimiterEnabled" to { b.setLimiterEnabled(0, true) },
            "setLimiterThreshold" to { b.setLimiterThreshold(0, -6f) },
            "setLimiterThreshold" to { b.setLimiterThreshold(0, -6f, sendOnly = true) },
            "setLimiterRelease" to { b.setLimiterRelease(0, 200f) },
            "setLimiterRelease" to { b.setLimiterRelease(0, 200f, sendOnly = true) },
            "setLimiterLinkGroup" to { b.setLimiterLinkGroup(0, 1) },
            "copyLimiterToAll" to { b.copyLimiterToAll(0) },
            "linkAllLimiterPairs" to { b.linkAllLimiterPairs() },
            "unlinkAllLimiters" to { b.unlinkAllLimiters() },
            "disableAllLimiters" to { b.disableAllLimiters() },
            "setOutputType" to { b.setOutputType(hw(), 0, 1) },
            "setI2sBckPin" to { b.setI2sBckPin(hw(), 0, 16) },
            "setI2sClockPinMode" to { b.setI2sClockPinMode(hw(), 1) },
            "setMckEnabled" to { b.setMckEnabled(hw(), true) },
            "setMckPin" to { b.setMckPin(hw(), 21) },
            "setMckMultiplier" to { b.setMckMultiplier(hw(), 1) },
            "setAdatOutEnabled" to { b.setAdatOutEnabled(hw(), true) },
            "setAdatOutPin" to { b.setAdatOutPin(hw(), 12) },
            "setSpdifRxPin" to { b.setSpdifRxPin(hw(), 0, 5) },
            "setSpdifInputEnabled" to { b.setSpdifInputEnabled(hw(), 1, true) },
            "setI2sRxPin" to { b.setI2sRxPin(hw(), 0, 1) },
            "setI2sInputChannels" to { b.setI2sInputChannels(hw(), 4) },
            "setAdatInputEnabled" to { b.setAdatInputEnabled(hw(), true) },
            "setAdatInputPin" to { b.setAdatInputPin(hw(), 27) },
            "setAdatInputClockMode" to { b.setAdatInputClockMode(hw(), 1) },
            "setInputRate" to { b.setInputRate(hw(), 2) },
            "setI2sClockMode" to { b.setI2sClockMode(hw(), 1) },
            "setInputRateWithStatus" to { assertEquals(PinConfigStatus.NO_REPLY, b.setInputRateWithStatus(hw(), 2)) },
            "setI2sClockModeWithStatus" to { b.setI2sClockModeWithStatus(hw(), 1) },
            "setLgSoundSync" to { b.setLgSoundSync(true) },
            "setDacMute" to { b.setDacMute(true, true, 11, 5, 0) },
            "testDacMute" to { assertEquals(255, b.testDacMute()) },
            "setUart" to { b.setUart(true, 16, 17, false, 115200) },
            "setI2c" to { b.setI2c(true, 18, 19, 0x42) },
            "setInputLinked" to { b.setInputLinked(0, true, 0) },
            "pasteChannel" to {
                b.copyChannel(0)
                b.pasteChannel(1)
            },
            "sendBandLive" to { b.sendBandLive(0, 0, peq) },
            "commitBands" to { b.commitBands(0, listOf(0), listOf(peq)) },
            "revertOutputConfig" to {
                b.beginOutputEdit()
                assertFalse(b.revertOutputConfig(hw()))
            },
            // Controllers
            "siggen.start" to { assertFalse(b.siggen.start()) },
            "siggen.stop" to { b.siggen.stop() },
            "siggen.stopNow" to { b.siggen.stopNow() },
            "siggen.identify" to { b.siggen.identify(0) },
            "stats.resetWatermarks" to { b.stats.resetWatermarks() },
            "cs.apply" to { assertTrue(b.cs.apply("""{"op":"macroFire","index":0}""").contains("read-only")) },
            "cs.sendOnly" to { b.cs.sendOnly("""{"op":"aux","index":0,"level":10}""") },
            "cs.save" to { assertEquals(0xFF, b.cs.save(ops.confirm(DangerousOp.CS_SAVE))) },
            "cs.revert" to { assertEquals(0xFF, b.cs.revert()) },
            "configFiles.importConfiguration" to {
                b.configFiles.importConfiguration(ops.confirm(DangerousOp.CONFIG_IMPORT), "{}", true, true)
            },
            "configFiles.importFilters" to { b.configFiles.importFilters("{}", listOf(0)) }
        )
    }

    @Test
    fun everyWritePathIsRefusedBeforeAnyCoreWrite() = runTest {
        val core = tooOld()
        val b = newBridge(core)
        attach(b, core)
        assertTrue(b.connected)
        assertFalse(b.writesAllowed.value)
        val events = collectEvents(b)
        val mark = core.calls.size
        val paths = writePaths(b)
        for ((name, call) in paths) {
            val before = events.size
            call()
            runCurrent()
            advanceTimeBy(1_000) // siggen apply, DAC/ctrl read-backs
            val refusals = events.drop(before).filterIsInstance<BridgeEvent.CommandFailed>()
            assertTrue("$name reports the refusal", refusals.any { it.command == name })
            assertTrue(
                "$name says why",
                refusals.all { (it.error as? WritesNotAllowedException)?.message == WritesNotAllowedException.FIRMWARE_MISMATCH }
            )
        }
        val written = core.calls.drop(mark).map { it.substringBefore('(') }.filter { it in WriteGuardedCore.WRITES }
        assertEquals("no core write while read-only", emptyList<String>(), written)
        assertEquals("the bridge refused every path itself", 0, b.guardedCore.trips)
    }

    @Test
    fun gatedOpsDoNotSpendTheTokenButStillValidateIt() = runTest {
        val core = tooOld()
        val b = newBridge(core)
        attach(b, core)
        val ops = b.dangerousOps as DangerousOpsImpl
        val t = ops.confirm(DangerousOp.SAVE_PRESET)
        assertEquals(PresetStatus.NO_REPLY, b.savePreset(t, 1))
        assertEquals(PresetStatus.NO_REPLY, b.savePreset(t, 1)) // not spent: no throw
        assertEquals(1, ops.liveCount)
        val wrong = ops.confirm(DangerousOp.DELETE_PRESET)
        assertTrue(runCatching { b.savePreset(wrong, 1) }.exceptionOrNull() is DangerousOpNotConfirmed)
        assertEquals(0, core.callCount("savePreset"))
    }

    @Test
    fun readsAndTheBootloaderStillWork() = runTest {
        val core = tooOld()
        val b = newBridge(core)
        attach(b, core)
        b.checkCore1Conflict(8)
        b.presetChanges()
        b.cs.apply("""{"op":"refresh"}""")
        b.rta.update(1, 0, 3, false)
        runCurrent()
        assertEquals(1, core.callCount("checkCore1Conflict"))
        assertEquals(1, core.callCount("csApply"))
        assertTrue(core.callCount("rtaSetRequest") >= 1)
        // The firmware-update path
        b.enterBootloader(b.dangerousOps.confirm(DangerousOp.ENTER_BOOTLOADER))
        runCurrent()
        assertEquals(1, core.callCount("enterBootloader"))
        assertEquals(0, b.guardedCore.trips)
    }

    @Test
    fun writesAllowedFollowsConnectionAndCompat() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        assertFalse(b.writesAllowed.value)
        attach(b, core)
        assertTrue(b.writesAllowed.value) // demo: compat OK
        b.setUserMute(true)
        runCurrent()
        assertEquals(1, core.callCount("setUserMute"))
        // The device's image turns incompatible (e.g. re-read after a reflash)
        core.simulateRemote { it.copy(compat = DspiConstants.COMPAT_FIRMWARE_TOO_NEW) }
        advanceTimeBy(100)
        runCurrent()
        assertFalse(b.writesAllowed.value)
        core.simulateRemote { it.copy(compat = DspiConstants.COMPAT_OK) }
        advanceTimeBy(100)
        runCurrent()
        assertTrue(b.writesAllowed.value)
        b.detachDevice(core.serial)
        runCurrent()
        assertFalse(b.writesAllowed.value)
        val events = collectEvents(b)
        b.setUserMute(false)
        runCurrent()
        val e = events.filterIsInstance<BridgeEvent.CommandFailed>().single()
        assertEquals(WritesNotAllowedException.NOT_CONNECTED, e.error.message)
        // An incompatible unit attaches read-only
        val old = tooOld()
        val b2 = newBridge(old)
        attach(b2, old)
        assertTrue(b2.connected)
        assertFalse(b2.writesAllowed.value)
    }

    @Test
    fun theDefaultMemberDerivesFromStatusAndCompat() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        val double =
            object : DspiBridge by b {
                override val writesAllowed: StateFlow<Boolean> get() = super<DspiBridge>.writesAllowed
            }
        assertFalse(double.writesAllowed.value)
        attach(b, core)
        assertTrue(double.writesAllowed.value)
    }

    // ── Classification (fails when the contract or the core grows) ──

    @Test
    fun everyCoreMethodIsClassified() {
        val names = DspiCoreApi::class.java.methods.map { it.name }.toSet() - "close"
        val w = WriteGuardedCore.WRITES
        val r = WriteGuardedCore.PASS_THROUGH
        assertEquals(emptySet<String>(), w intersect r)
        assertEquals("unclassified core methods", emptySet<String>(), names - w - r)
        assertTrue("enterBootloader" in r)
    }

    @Test
    fun everyBridgeMemberIsClassified() = runTest {
        val core = tooOld()
        val b = newBridge(core)
        val tested = writePaths(b).map { it.first }.toSet()
        fun members(c: Class<*>) =
            c.declaredMethods.map { it.name }.filterNot { Regex("get[A-Z].*").matches(it) || it.contains('$') }.toSet()
        val bridge = members(DspiBridge::class.java)
        assertEquals("unclassified DspiBridge members", emptySet<String>(), bridge - tested - READ_MEMBERS)
        val controllers =
            mapOf(
                "siggen" to SiggenController::class.java,
                "stats" to StatsController::class.java,
                "cs" to CsController::class.java,
                "configFiles" to ConfigFiles::class.java,
                "rta" to RtaController::class.java,
                "monitor" to MonitorModel::class.java
            )
        for ((prefix, c) in controllers) {
            val left = members(c).filter { "$prefix.$it" !in tested && "$prefix.$it" !in CONTROLLER_READS }
            assertEquals("unclassified $prefix members", emptyList<String>(), left)
        }
    }

    companion object {
        /** Members that read, are local to the app, manage the connection, or are exempt (enterBootloader). */
        val READ_MEMBERS =
            setOf(
                "attachDevice", "detachDevice", "scanDevices", "selectDevice", "disconnectDevice", "reconnect", "rescan",
                "setUiVisible", "beginOutputEdit", "canPaste", "canSetAllBypass", "copyChannel", "channelBands", "channelColor",
                "channelDelayMS", "channelDescriptor", "channelExists", "channelGainOffset", "channelName", "channelVisible",
                "setChannelVisible", "checkCore1Conflict", "core1ConflictOutputs", "crossoverBypass", "crossoverFreq",
                "crossoverType", "enterBootloader", "fetchActiveSpdifRxPin", "fetchAdatOutStatus", "fetchInputLock",
                "fetchInputRate", "fetchLgStatus", "fetchSpdifChannelStatus", "fetchSpdifRxStatus", "fetchSubharmHeadroom",
                "fetchSubharmMeter", "fetchUpmixStatus", "filterBypass", "filterFreq", "filterGain", "filterQ", "filterQp",
                "filterType", "inputAppId", "inputPairMatches", "inputPreampDB", "isClipping", "isInputChannel", "isInputLinked",
                "isPdmOutput", "isPresetOccupied", "limiterEnabled", "limiterLinkGroup", "limiterReduction", "limiterRelease",
                "limiterThreshold", "linkedPartner", "liveInputCount", "magnitudeCurve", "matrixGain", "matrixInvert",
                "matrixRouting", "numPinOutputs", "outputDelayMS", "outputEnabled", "outputGainDB", "outputMuted", "outputPin",
                "pairAvailable", "pairPartner", "peakLevel", "phaseCurve", "pinOwners", "presetChanges", "presetName",
                "refreshCtrlIfaces", "restoreInputLinks"
            )

        /** Controller members that read or only edit app-side drafts/subscriptions. */
        val CONTROLLER_READS =
            setOf(
                "siggen.selectType", "siggen.setParam", "siggen.setFlag", "siggen.cycleOutput", "siggen.selectAllOutputs",
                "siggen.clearOutputs", "siggen.setLevelDb", "siggen.setDurationMs", "siggen.setRepeat", "siggen.setGapMs",
                "cs.refresh", "cs.statusMessage", "configFiles.exportConfiguration", "configFiles.inspectConfiguration",
                "configFiles.exportFilters", "configFiles.inspectFilters", "rta.update", "rta.release", "rta.appChannel",
                "rta.tapOf", "rta.tapIndex", "rta.availableChannels", "monitor.clear", "monitor.allText",
                // `var watching`: polling while a view is open (reads only)
                "siggen.setWatching", "stats.setWatching", "monitor.setWatching", "monitor.setPaused",
                // analyser request parameters: part of the RTA subscription (a read)
                "rta.setFftOrder", "rta.setAvgMs", "rta.setPeakDecay"
            )
    }
}
