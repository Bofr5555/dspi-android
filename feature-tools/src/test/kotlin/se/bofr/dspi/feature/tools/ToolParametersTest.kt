package se.bofr.dspi.feature.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.fake.FakeTables
import se.bofr.dspi.fake.defaultDspState
import se.bofr.dspi.fake.demoDspState

class ToolParametersTest {
    // ── Masks ──

    @Test
    fun outputMasksFollowThePlatform() {
        // RP2350: 9 outputs, PDM = output 9 (bit 8); RP2040: 5 outputs, PDM = bit 4.
        assertEquals(0x1FF, ToolMasks.allOutputs(9))
        assertEquals(0x0FF, ToolMasks.excludeSub(9))
        assertEquals(0x100, ToolMasks.subOnly(9))
        assertEquals(0x1F, ToolMasks.allOutputs(5))
        assertEquals(0x0F, ToolMasks.excludeSub(5))
        assertEquals(0x10, ToolMasks.subOnly(5))
        assertEquals(0xFFFF, ToolMasks.allOutputs(16))
    }

    @Test
    fun crossfeedWorksOnOutputPairs() {
        // Pair p = outputs 2p / 2p+1; the mono PDM sub is never crossfed.
        assertEquals(4, ToolMasks.pairCount(9))
        assertEquals(2, ToolMasks.pairCount(5))
        assertEquals(0x0F, ToolMasks.allPairs(4))
        assertEquals(0x03, ToolMasks.allPairs(2))
        assertEquals(0xFF, ToolMasks.allPairs(8))
    }

    @Test
    fun bitHelpers() {
        assertTrue(ToolMasks.isOn(0b101, 2))
        assertFalse(ToolMasks.isOn(0b101, 1))
        assertEquals(0b111, ToolMasks.toggled(0b101, 1))
        assertEquals(0b001, ToolMasks.toggled(0b101, 2))
        assertEquals(0b101, ToolMasks.with(0b001, 2, true))
        assertEquals(0b001, ToolMasks.with(0b101, 2, false))
    }

    @Test
    fun firmwareGatesMatchDspViewModel() {
        assertFalse(ToolFirmware.supportsLoudnessMask(18))
        assertTrue(ToolFirmware.supportsLoudnessMask(19))
        assertTrue(ToolFirmware.supportsCrossfeedMask(20))
        assertFalse(ToolFirmware.supportsPsybass(22))
        assertTrue(ToolFirmware.supportsSubharm(29))
        assertFalse(ToolFirmware.supportsSubharmExtended(29))
        assertTrue(ToolFirmware.supportsTube(31))
        assertFalse(ToolFirmware.supportsLimiter(31))
        assertTrue(ToolFirmware.supportsUpmixer(32, 1))
        assertFalse(ToolFirmware.supportsUpmixer(32, 0))
        assertFalse(ToolFirmware.supportsUpmixer(25, 1))
    }

    // ── Defaults agree with the core's tables (protocol.rs via FakeTables) ──

    @Test
    fun defaultsMatchTheCore() {
        val s = defaultDspState()
        assertEquals(TubeParameters(outputMask = 65535), TubeParameters.from(s))
        assertEquals(SubharmParameters(outputMask = 65535), SubharmParameters.from(s))
        assertEquals(UpmixParameters(), UpmixParameters.from(s))
        assertEquals(FakeTables.TUBE_DEFAULTS.size, DspiConstants.TUBE_PARAM_COUNT)
    }

    @Test
    fun rangesMatchTheCoreLimits() {
        fun lim(t: List<Pair<Float, Float>>, i: Int) = t[i].first..t[i].second
        val tl = FakeTables.TUBE_LIMITS
        assertEquals(lim(tl, DspiConstants.TUBE_PARAM_DRIVE), TubeParameters.TUBE_DRIVE_MIN..TubeParameters.TUBE_DRIVE_MAX)
        assertEquals(lim(tl, DspiConstants.TUBE_PARAM_BIAS), TubeParameters.TUBE_BIAS_MIN..TubeParameters.TUBE_BIAS_MAX)
        assertEquals(lim(tl, DspiConstants.TUBE_PARAM_ASYM), TubeParameters.TUBE_ASYM_MIN..TubeParameters.TUBE_ASYM_MAX)
        assertEquals(lim(tl, DspiConstants.TUBE_PARAM_DAMPING), TubeParameters.TUBE_XFMR_DAMPING_MIN..TubeParameters.TUBE_XFMR_DAMPING_MAX)
        assertEquals(lim(tl, DspiConstants.TUBE_PARAM_RESONANCE), TubeParameters.TUBE_XFMR_RES_MIN..TubeParameters.TUBE_XFMR_RES_MAX)
        assertEquals(lim(tl, DspiConstants.TUBE_PARAM_TRIM), TubeParameters.TUBE_TRIM_MIN..TubeParameters.TUBE_TRIM_MAX)
        val sl = FakeTables.SUBHARM_LIMITS
        assertEquals(lim(sl, DspiConstants.SUBHARM_PARAM_LOW), SubharmParameters.LEVEL_RANGE)
        assertEquals(lim(sl, DspiConstants.SUBHARM_PARAM_BOOST), SubharmParameters.BOOST_RANGE)
        assertEquals(lim(sl, DspiConstants.SUBHARM_PARAM_HOLD), SubharmParameters.HOLD_RANGE)
        assertEquals(lim(sl, DspiConstants.SUBHARM_PARAM_CEILING), SubharmParameters.CEILING_RANGE)
    }

    @Test
    fun tubeRowsMatchTheFirmwareTable() {
        for (t in 1..TubeParameters.TUBE_TYPE_MAX) {
            val row = TubeParameters.tubeTypeRow(t)!!
            assertEquals("type $t", FakeTables.TUBE_ROWS[t - 1], listOf(row.biasPct, row.asymDB, row.hardnessPct, row.sagPct))
        }
        assertNull(TubeParameters.tubeTypeRow(0))
        assertEquals("Custom", TubeParameters.tubeTypeName(0))
        assertEquals("12AX7 / ECC83", TubeParameters.tubeTypeName(1))
        assertEquals("12AX7", TubeParameters.tubeTypeRow(1)!!.shortName)
        assertEquals("Type 17", TubeParameters.tubeTypeName(17))
        assertEquals("GZ34 / 5AR4", TubeParameters.tubeRectifierName(1))
    }

    @Test
    fun tubeCaptionsAndLift() {
        val el34 = TubeParameters(type = 12, xfmrEnabled = false)
        assertEquals("Push-pull power, mid crunch, deep sag. Meant for use with the output stage, in Advanced.", el34.showcaseCaption)
        assertEquals("Push-pull power, mid crunch, deep sag. Meant for use with the output stage on.", el34.tubeTypeCaption)
        assertEquals("Character controls as set, no tube row applied.", TubeParameters(type = 0).tubeTypeCaption)
        assertEquals("Sag depth x0.6, 5 ms attack, 120 ms release.", TubeParameters(rectifier = 1).rectifierSummary)
        assertEquals("No sag: the supply holds up however hard the stage is driven.", TubeParameters(rectifier = 0).rectifierSummary)
        // df = 2: bump 4*3/9 = 1.333 (+2.5 dB), top 2*3/5 = 1.2 (+1.6 dB)
        val (bell, top) = TubeParameters(xfmrDamping = 2f).xfmrLift
        assertEquals(2.499f, bell, 0.01f)
        assertEquals(1.584f, top, 0.01f)
    }

    // ── State projections ──

    @Test
    fun projectionsReadTheCoreState() {
        val s = demoDspState()
        val l = LoudnessParameters.from(s)
        assertEquals(s.loudnessRefSpl, l.refSPL)
        assertEquals(s.loudnessOutputMask, l.outputMask)
        val c = CrossfeedParameters.from(s)
        assertEquals(s.crossfeedOutputPairMask, c.outputMask)
        assertEquals(s.crossfeedPreset == 3, c.isCustom)
        val p = PsybassParameters.from(s)
        assertEquals(s.psybassCutoffHz, p.cutoffHz)
        val lv = LevellerParameters.from(s)
        assertEquals(s.levellerDetectorMask, lv.detectorMask)
        val lim = LimiterParameters.from(s)
        assertEquals(s.limiterEnabled.size, lim.outputs.size)
    }

    @Test
    fun levellerChannelCount() {
        assertEquals(2, LevellerParameters.channelCount(2, 8))
        assertEquals(6, LevellerParameters.channelCount(8, 6))
        assertEquals(2, LevellerParameters.channelCount(8, 0))
        assertFalse(LevellerParameters.showMasks(2, 2))
        assertFalse(LevellerParameters.showMasks(8, 2))
        assertTrue(LevellerParameters.showMasks(8, 6))
        assertEquals("Slow — Gentle response for music and wide dynamic range content.", LevellerParameters.speedDescription(0))
        assertEquals("Medium — Balanced response for general purpose use.", LevellerParameters.speedDescription(1))
    }

    @Test
    fun subharmTexts() {
        assertEquals("+4.2 dB", SubharmParameters.headroomText(4.2f))
        assertEquals("none", SubharmParameters.headroomText(0f))
        assertTrue(SubharmParameters(ceilingDB = 0f).ceilingOff)
        assertFalse(SubharmParameters(ceilingDB = -6f).ceilingOff)
        assertFalse(SubharmParameters(selectMode = 0).selectivityActive)
        assertTrue(SubharmParameters(selectMode = 2).selectivityActive)
    }

    @Test
    fun upmixStatusAndSegments() {
        assertEquals("No device connected", UpmixParameters.statusText(false, true, 0))
        assertEquals("Active - processing audio", UpmixParameters.statusText(true, true, 0))
        assertEquals("Idle: upmixer disabled", UpmixParameters.statusText(true, false, 1))
        assertEquals("Idle: input is not stereo", UpmixParameters.statusText(true, false, 2))
        assertEquals("Idle: sample rate above 48 kHz", UpmixParameters.statusText(true, false, 3))
        // Off | Sinner | Logician: centre Off is wire 2, surround Off is wire 0.
        assertEquals(listOf(2, 0, 1), UpmixParameters.CENTER_SEGMENTS)
        assertEquals(listOf(0, 1, 2), UpmixParameters.SURROUND_SEGMENTS)
        assertTrue(UpmixParameters(centerMode = 2).centreOff)
        assertFalse(UpmixParameters(surroundMode = 0).surroundOn)
    }

    // ── Limiter ganging ──

    private fun outs(vararg groups: Int) = groups.mapIndexed { i, g ->
        LimiterOutputSettings(enabled = i == 0, thresholdDB = -i.toFloat(), linkGroup = g)
    }

    @Test
    fun limiterGangPeerAndEdit() {
        val o = outs(1, 1, 0, 2, 2)
        assertEquals(1, LimiterGang.peer(0, 1, o, 5))
        assertEquals(0, LimiterGang.peer(1, 1, o, 5))
        assertNull(LimiterGang.peer(2, 0, o, 5))
        val edited = LimiterGang.edit(1, o, 5) { it.copy(thresholdDB = -6f) }
        assertEquals(listOf(-6f, -6f, -2f, -3f, -4f), edited.map { it.thresholdDB })
    }

    @Test
    fun limiterJoinAdoptsTheGroupAndGangAllCopiesTheLead() {
        val o = outs(1, 0, 0)
        val joined = LimiterGang.setGroup(2, 1, o, 3)
        assertEquals(1, joined[2].linkGroup)
        assertTrue(joined[2].enabled)
        assertEquals(0f, joined[2].thresholdDB, 0f)
        val ganged = LimiterGang.gangAll(outs(2, 0, 2), 3)
        assertEquals(0f, ganged[2].thresholdDB, 0f)
        assertTrue(ganged[2].enabled)
        assertEquals(0, LimiterGang.storedGroup(7))
        assertEquals(3, LimiterGang.storedGroup(3))
    }

    @Test
    fun limiterStereoPairsAndSummary() {
        assertEquals(listOf(1, 1, 2, 2, 3, 3, 4, 4, 0), LimiterParameters.stereoPairGroups(9))
        assertEquals(listOf(1, 1, 2, 2, 0), LimiterParameters.stereoPairGroups(5))
        val o = outs(1, 1, 1, 0)
        val name = { k: Int -> "Out ${k + 1}" }
        assertEquals("Not linked.", LimiterParameters.linkSummary(3, 0, o, 4, name))
        assertEquals("Linked with Out 2 and Out 3.", LimiterParameters.linkSummary(0, 1, o, 4, name))
        assertEquals("Linked with Out 1, Out 2 and Out 3.", LimiterParameters.linkSummary(3, 1, o, 4, name))
        assertEquals("No other outputs in group 2.", LimiterParameters.linkSummary(0, 2, o, 4, name))
        assertTrue(LimiterParameters.isLimiting(true, 0.05f))
        assertFalse(LimiterParameters.isLimiting(true, 0.04f))
        assertFalse(LimiterParameters.isLimiting(false, 3f))
    }

    @Test
    fun tubeFamiliesAndAudioPulse() {
        assertEquals(TubeFamily.NOVAL_TRIODE, TubeFamily.of(0))
        assertEquals(TubeFamily.OCTAL_GLASS, TubeFamily.of(7))
        assertEquals(TubeFamily.DIRECTLY_HEATED, TubeFamily.of(16))
        // App ids: output k = 2 + k. Only routed outputs count; sqrt envelope, 8-bit.
        val peaks = List(17) { 0f }.toMutableList().apply {
            this[2] = 0.25f
            this[3] = 1.5f
        }
        assertEquals(128f / 255f, TubeAudioPulse.opacity(peaks, 2, 9, 0b001), 1e-6f)
        assertEquals(1f, TubeAudioPulse.opacity(peaks, 2, 9, 0b011), 1e-6f)
        assertEquals(0f, TubeAudioPulse.opacity(peaks, 2, 9, 0), 1e-6f)
    }

    @Test
    fun toolRoutes() {
        assertEquals(ToolRoute.TUBE, ToolRoute.of("tools/tube"))
        assertNull(ToolRoute.of("tools/limiter/0"))
        assertTrue(ToolRoute.entries.all { it.route.startsWith("tools/") })
        val s = defaultDspState()
        assertFalse(ToolRoute.SUBHARM.isOn(s))
        assertTrue(ToolRoute.TUBE.isOn(s.copy(tube = s.tube.toMutableList().apply { this[0] = 1f })))
    }
}
