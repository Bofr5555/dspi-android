package se.bofr.dspi.fake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.core.DspiException
import se.bofr.dspi.core.FilterParams
import se.bofr.dspi.core.NotifyListener

class FakeDspiCoreTest {
    @Test
    fun demoStateIsCompatibleV32Rp2350() {
        val s = FakeDspiCore().getState()
        assertEquals(DspiConstants.COMPAT_OK, s.compat)
        assertEquals(32, s.formatVersion)
        assertEquals(1, s.platformId)
        assertEquals(8, s.numInputChannels)
        assertEquals(9, s.numOutputChannels)
        assertEquals(17, s.numChannels)
        assertEquals(DspiConstants.MAX_CHANNELS, s.filters.size)
    }

    @Test
    fun volumeSetterUpdatesStateAndRecordsTheCall() {
        val core = FakeDspiCore()
        core.setMasterVolume(-12.3f)
        assertEquals(-12.5f, core.getState().masterVolumeDb) // 0.5 dB steps
        assertEquals(listOf("setMasterVolume(-12.3)"), core.calls)
        core.setMasterVolume(-200f)
        assertEquals(DspiConstants.MASTER_VOL_MUTE_DB, core.getState().masterVolumeDb)
    }

    @Test
    fun settersClampAndQuantiseLikeTheCore() {
        val core = FakeDspiCore()
        core.setInputPreamp(1, 40f)
        assertEquals(24f, core.getState().inputPreampDb[1]) // RP2350 ceiling
        core.setFilter(0, 3, FilterParams(DspiConstants.FILTER_PEAKING, false, 1000f, 1f, 3.14f, 0.707f))
        assertEquals(3.1f, core.getState().filters[0][3].gain)
        core.setOutputDelay(2, 100f)
        assertEquals(42f, core.getState().outputDelayMs[2])
        assertEquals(42f, core.getState().channelDelays[8 + 2])
        core.setCrossfeedPreset(2)
        assertEquals(650f, core.getState().crossfeedFreq)
        core.setUpmixParam(DspiConstants.UPMIX_PARAM_PRESENCE, 3.3f)
        assertEquals(3.5f, core.getState().upmix[DspiConstants.UPMIX_PARAM_PRESENCE])
        core.setTubeParam(DspiConstants.TUBE_PARAM_TYPE, 16f)
        assertEquals(listOf(12f, 6f, 10f, 12f), core.getState().tube.subList(4, 8))
        core.setTubeParam(DspiConstants.TUBE_PARAM_SAG, 40f)
        assertEquals(0f, core.getState().tube[DspiConstants.TUBE_PARAM_TYPE])
        core.setLimiterParam(DspiConstants.LIMITER_ALL_OUTPUTS, DspiConstants.LIMITER_PARAM_THRESHOLD_DB, -50f)
        assertTrue(core.getState().limiterThresholdDb.take(9).all { it == -30f })
    }

    @Test
    fun core1ConflictRefusesThePdmOutput() {
        val core = FakeDspiCore()
        // Demo: outputs 1-4 on, so outputs 3-4 hold the EQ worker
        assertFalse(core.setOutputEnable(8, true))
        assertTrue(core.checkCore1Conflict(8))
        core.setOutputEnable(2, false)
        core.setOutputEnable(3, false)
        assertTrue(core.setOutputEnable(8, true))
        assertEquals(1, core.getState().core1Mode)
    }

    @Test
    fun pinsAreRefusedWhenInvalidOrInUse() {
        val core = FakeDspiCore()
        val e1 = runCatching { core.setOutputPin(0, 24) }.exceptionOrNull() as DspiException.Refused
        assertEquals(DspiConstants.PIN_CONFIG_INVALID_PIN, e1.status)
        val e2 = runCatching { core.setOutputPin(0, 7) }.exceptionOrNull() as DspiException.Refused
        assertEquals(DspiConstants.PIN_CONFIG_PIN_IN_USE, e2.status)
        core.setOutputPin(0, 20)
        assertEquals(20, core.getState().outputPins[0])
    }

    @Test
    fun presetsSaveLoadDeleteAndDirtyTracking() {
        val core = FakeDspiCore()
        core.captureBaseline()
        assertFalse(core.presetDirty())
        core.setBypass(true)
        assertTrue(core.presetDirty())
        assertEquals(listOf("Master EQ bypass: off → on"), core.presetChanges())
        core.savePreset(4)
        core.captureBaseline()
        assertEquals(4, core.getState().activePresetSlot)
        assertTrue((core.getState().presetOccupied shr 4) and 1 == 1)
        core.setBypass(false)
        core.loadPreset(4)
        assertTrue(core.getState().bypass)
        core.deletePreset(4)
        assertEquals(0, (core.getState().presetOccupied shr 4) and 1)
        assertFalse(core.getState().bypass) // active slot deleted: factory values
    }

    @Test
    fun notificationsQueueAndWakeTheListener() {
        val core = FakeDspiCore()
        var wakes = 0
        core.setNotifyListener(NotifyListener { wakes++ })
        core.simulateUserVolume(-20f)
        core.simulateFilterChange(3, 0, FilterParams(1, false, 500f, 1f, 2f, 0.707f))
        assertEquals(2, wakes)
        val r = core.processNotifications()
        assertEquals(DspiConstants.NOTIFY_STATE or DspiConstants.NOTIFY_CURVES, r.flags.toInt())
        assertEquals(1L shl 3, r.filterChannels)
        assertEquals(0L, core.processNotifications().flags)
        assertEquals(-20f, core.getState().userVolumeDb)
        assertEquals(2, core.monitorRead(0).entries.size)
    }

    @Test
    fun faultsAreInjectedPerMethod() {
        val core = FakeDspiCore()
        core.failNext("fetchStatus", 2)
        assertTrue(runCatching { core.fetchStatus() }.exceptionOrNull() is DspiException.Transfer)
        assertTrue(runCatching { core.fetchStatus() }.exceptionOrNull() is DspiException.Transfer)
        core.fetchStatus()
        assertEquals(3, core.callCount("fetchStatus"))
    }

    @Test
    fun disconnectedCoreThrows() {
        val core = FakeDspiCore()
        core.closeFd()
        assertTrue(runCatching { core.setBypass(true) }.exceptionOrNull() is DspiException.Disconnected)
    }

    @Test
    fun configurationRoundTripsThroughExportImport() {
        val core = FakeDspiCore()
        val doc = core.configExport("Test", "0.1", 0x3)
        assertTrue(core.configInspect(doc).contains("\"ok\":true"))
        core.setBypass(true)
        val r = MiniJson.parseObject(core.configImport(doc, 0))!!
        assertEquals(true, r["ok"])
        assertEquals(3L, r["links"])
        assertFalse(core.getState().bypass)
    }

    @Test
    fun filterFilesRoundTrip() {
        val core = FakeDspiCore()
        val text = core.filtersExport(2)
        val inspect = MiniJson.parseObject(core.filtersInspect(text, 2))!!
        assertEquals("native", inspect["kind"])
        core.setFilter(0, 0, defaultFilterParams())
        core.filtersImport(text, listOf(0))
        assertEquals(DspiConstants.FILTER_LOWSHELF, core.getState().filters[0][0].filterType)
    }

    @Test
    fun analyserFramesFollowTheRequest() {
        val core = FakeDspiCore()
        core.rtaStart()
        assertTrue(core.rtaGetCaps().supported)
        core.rtaSetRequest(se.bofr.dspi.core.RtaRequest(0, 0x3, false, 10, 300, 12))
        core.emitRtaFrame()
        val snap = core.rtaGetSnapshot()
        assertEquals(1L, snap.version)
        assertEquals(2, snap.count)
        assertFalse(snap.frames[0].silent)
        assertTrue(snap.frames[2].silent)
    }

    /** As core rta.rs: frames packed first-`count`, `populated` a BAND mask (populated_bands). */
    @Test
    fun analyserFramesArePackedAndPopulatedIsABandMask() {
        val core = FakeDspiCore()
        core.rtaStart()
        core.rtaSetRequest(se.bofr.dspi.core.RtaRequest(1, 0b1010_0000, false, 10, 300, 12))
        val snap = core.rtaGetSnapshot()
        assertEquals(2, snap.count)
        assertEquals(listOf(5, 7), snap.frames.take(2).map { it.channel })
        assertTrue(snap.frames.drop(2).all { it.silent && it.nBands == 0 })
        // 1024 points at 48 kHz: bass bank plus every FFT band up to 20 kHz (rta.rs test)
        assertEquals((1L shl 34) - 1, snap.populated)
        assertEquals((1L shl 34) - 1, FakeDspiCore.populatedBands(34, 0, 48000, 10))
        // 256 points at 44.1 kHz: band 16 (400 Hz) holds no bin; bass bands always populated
        val m = FakeDspiCore.populatedBands(34, 0, 44100, 8)
        assertEquals(0L, m and (1L shl 16))
        assertTrue(m and (1L shl 13) != 0L)
        assertEquals(0L, FakeDspiCore.populatedBands(34, 0xFF, 48000, 10))
        assertEquals(10, core.rtaGetCaps().centres[0])
        assertEquals(19953, core.rtaGetCaps().centres[33]) // 1000 · 10^1.3
    }

    /** Firmware commands.md §15.1: loading an unconfigured slot applies factory defaults. */
    @Test
    fun loadingAnEmptySlotAppliesFactoryDefaults() {
        val core = FakeDspiCore()
        core.setBypass(true)
        core.setOutputGain(0, -6f)
        core.loadPreset(7)
        val s = core.getState()
        assertEquals(7, s.activePresetSlot)
        assertFalse(s.bypass)
        assertEquals(0f, s.outputGainDb[0])
        assertEquals(DspiConstants.FILTER_FLAT, s.filters[0][0].filterType)
        assertEquals(9, s.numOutputChannels) // the device's identity stays
    }
}
