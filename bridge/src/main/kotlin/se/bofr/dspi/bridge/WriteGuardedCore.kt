// G1 (owner decision): the bridge's last line of defence for read-only mode.
package se.bofr.dspi.bridge

import se.bofr.dspi.core.DspiCoreApi
import se.bofr.dspi.core.DspiException
import se.bofr.dspi.core.FilterParams
import se.bofr.dspi.core.I2cConfig
import se.bofr.dspi.core.SiggenConfig
import se.bofr.dspi.core.UartConfig

/**
 * G1: wraps the core so that no device WRITE / command can reach USB while
 * [allowed] is false, whatever path calls it. Every [DspiBridgeImpl] member
 * refuses up front (with its event and failure value); this layer only
 * catches a path that forgot to (then [trips] counts it and the call throws
 * [DspiException.Unsupported], which every caller already maps to its
 * failure value). Reads, local computations, listeners, RTA subscriptions,
 * control-surface `refresh`/`refreshStatus` and [enterBootloader] (the
 * firmware-update path) pass through.
 *
 * [WRITES] is the explicit classification of [DspiCoreApi]; a test fails
 * when a core method is in neither [WRITES] nor [PASS_THROUGH].
 */
internal class WriteGuardedCore(private val inner: DspiCoreApi, private val allowed: () -> Boolean) : DspiCoreApi by inner {
    /** Writes this layer had to stop (the bridge's own guard missed them). */
    @Volatile var trips = 0
        private set

    private fun guard(name: String) {
        if (!allowed()) {
            trips++
            throw DspiException.Unsupported()
        }
    }

    override fun applyAllParams() {
        guard("applyAllParams")
        return inner.applyAllParams()
    }

    override fun setFilter(ch: Int, band: Int, params: FilterParams) {
        guard("setFilter")
        return inner.setFilter(ch, band, params)
    }

    override fun setCrossover(ch: Int, xband: Int, params: FilterParams) {
        guard("setCrossover")
        return inner.setCrossover(ch, xband, params)
    }

    override fun setBandBypass(ch: Int, band: Int, bypass: Boolean) {
        guard("setBandBypass")
        return inner.setBandBypass(ch, band, bypass)
    }

    override fun setPreamp(db: Float) {
        guard("setPreamp")
        return inner.setPreamp(db)
    }

    override fun setInputPreamp(input: Int, db: Float) {
        guard("setInputPreamp")
        return inner.setInputPreamp(input, db)
    }

    override fun setBypass(enabled: Boolean) {
        guard("setBypass")
        return inner.setBypass(enabled)
    }

    override fun setMasterVolume(db: Float) {
        guard("setMasterVolume")
        return inner.setMasterVolume(db)
    }

    override fun setMasterVolumeMode(mode: Int) {
        guard("setMasterVolumeMode")
        return inner.setMasterVolumeMode(mode)
    }

    override fun saveMasterVolume() {
        guard("saveMasterVolume")
        return inner.saveMasterVolume()
    }

    override fun setUserVolume(db: Float) {
        guard("setUserVolume")
        return inner.setUserVolume(db)
    }

    override fun setUserMute(muted: Boolean) {
        guard("setUserMute")
        return inner.setUserMute(muted)
    }

    override fun setDelay(ch: Int, ms: Float) {
        guard("setDelay")
        return inner.setDelay(ch, ms)
    }

    override fun setLoudness(enabled: Boolean) {
        guard("setLoudness")
        return inner.setLoudness(enabled)
    }

    override fun setLoudnessRef(spl: Float) {
        guard("setLoudnessRef")
        return inner.setLoudnessRef(spl)
    }

    override fun setLoudnessIntensity(pct: Float) {
        guard("setLoudnessIntensity")
        return inner.setLoudnessIntensity(pct)
    }

    override fun setLoudnessMask(mask: Int) {
        guard("setLoudnessMask")
        return inner.setLoudnessMask(mask)
    }

    override fun setCrossfeed(enabled: Boolean) {
        guard("setCrossfeed")
        return inner.setCrossfeed(enabled)
    }

    override fun setCrossfeedPreset(preset: Int) {
        guard("setCrossfeedPreset")
        return inner.setCrossfeedPreset(preset)
    }

    override fun setCrossfeedFreq(freq: Float) {
        guard("setCrossfeedFreq")
        return inner.setCrossfeedFreq(freq)
    }

    override fun setCrossfeedFeed(feed: Float) {
        guard("setCrossfeedFeed")
        return inner.setCrossfeedFeed(feed)
    }

    override fun setCrossfeedItd(enabled: Boolean) {
        guard("setCrossfeedItd")
        return inner.setCrossfeedItd(enabled)
    }

    override fun setCrossfeedOutputs(pairMask: Int) {
        guard("setCrossfeedOutputs")
        return inner.setCrossfeedOutputs(pairMask)
    }

    override fun setLevellerEnabled(enabled: Boolean) {
        guard("setLevellerEnabled")
        return inner.setLevellerEnabled(enabled)
    }

    override fun setLevellerAmount(pct: Float) {
        guard("setLevellerAmount")
        return inner.setLevellerAmount(pct)
    }

    override fun setLevellerSpeed(speed: Int) {
        guard("setLevellerSpeed")
        return inner.setLevellerSpeed(speed)
    }

    override fun setLevellerMaxGain(db: Float) {
        guard("setLevellerMaxGain")
        return inner.setLevellerMaxGain(db)
    }

    override fun setLevellerLookahead(enabled: Boolean) {
        guard("setLevellerLookahead")
        return inner.setLevellerLookahead(enabled)
    }

    override fun setLevellerGate(db: Float) {
        guard("setLevellerGate")
        return inner.setLevellerGate(db)
    }

    override fun setLevellerMasks(detector: Int, apply: Int) {
        guard("setLevellerMasks")
        return inner.setLevellerMasks(detector, apply)
    }

    override fun setInputSource(source: Int) {
        guard("setInputSource")
        return inner.setInputSource(source)
    }

    override fun setPsybassParam(param: Int, value: Float) {
        guard("setPsybassParam")
        return inner.setPsybassParam(param, value)
    }

    override fun setPsybassMask(mask: Int) {
        guard("setPsybassMask")
        return inner.setPsybassMask(mask)
    }

    override fun setPsybassEnabled(enabled: Boolean) {
        guard("setPsybassEnabled")
        return inner.setPsybassEnabled(enabled)
    }

    override fun setMatrixRoute(input: Int, output: Int, enabled: Boolean, gain: Float, invert: Boolean) {
        guard("setMatrixRoute")
        return inner.setMatrixRoute(input, output, enabled, gain, invert)
    }

    override fun setOutputEnable(output: Int, enabled: Boolean): Boolean {
        guard("setOutputEnable")
        return inner.setOutputEnable(output, enabled)
    }

    override fun setOutputGain(output: Int, db: Float) {
        guard("setOutputGain")
        return inner.setOutputGain(output, db)
    }

    override fun setOutputMute(output: Int, muted: Boolean) {
        guard("setOutputMute")
        return inner.setOutputMute(output, muted)
    }

    override fun setOutputDelay(output: Int, ms: Float) {
        guard("setOutputDelay")
        return inner.setOutputDelay(output, ms)
    }

    override fun setLimiterParam(output: Int, param: Int, value: Float) {
        guard("setLimiterParam")
        return inner.setLimiterParam(output, param, value)
    }

    override fun setOutputPin(slot: Int, pin: Int) {
        guard("setOutputPin")
        return inner.setOutputPin(slot, pin)
    }

    override fun setOutputConfigMode(mode: Int) {
        guard("setOutputConfigMode")
        return inner.setOutputConfigMode(mode)
    }

    override fun saveOutputConfig() {
        guard("saveOutputConfig")
        return inner.saveOutputConfig()
    }

    override fun setChannelName(ch: Int, name: String) {
        guard("setChannelName")
        return inner.setChannelName(ch, name)
    }

    override fun savePreset(slot: Int) {
        guard("savePreset")
        return inner.savePreset(slot)
    }

    override fun loadPreset(slot: Int) {
        guard("loadPreset")
        return inner.loadPreset(slot)
    }

    override fun copyPreset(source: Int, dest: Int) {
        guard("copyPreset")
        return inner.copyPreset(source, dest)
    }

    override fun deletePreset(slot: Int) {
        guard("deletePreset")
        return inner.deletePreset(slot)
    }

    override fun setPresetName(slot: Int, name: String) {
        guard("setPresetName")
        return inner.setPresetName(slot, name)
    }

    override fun setPresetStartup(mode: Int, defaultSlot: Int) {
        guard("setPresetStartup")
        return inner.setPresetStartup(mode, defaultSlot)
    }

    override fun saveParams() {
        guard("saveParams")
        return inner.saveParams()
    }

    override fun loadParams() {
        guard("loadParams")
        return inner.loadParams()
    }

    override fun factoryReset() {
        guard("factoryReset")
        return inner.factoryReset()
    }

    override fun clearClips() {
        guard("clearClips")
        return inner.clearClips()
    }

    override fun setUpmixParam(id: Int, value: Float) {
        guard("setUpmixParam")
        return inner.setUpmixParam(id, value)
    }

    override fun setSubharmParam(id: Int, value: Float) {
        guard("setSubharmParam")
        return inner.setSubharmParam(id, value)
    }

    override fun setSubharmSolo(solo: Boolean) {
        guard("setSubharmSolo")
        return inner.setSubharmSolo(solo)
    }

    override fun setTubeParam(idx: Int, value: Float) {
        guard("setTubeParam")
        return inner.setTubeParam(idx, value)
    }

    override fun setOutputType(slot: Int, kind: Int) {
        guard("setOutputType")
        return inner.setOutputType(slot, kind)
    }

    override fun setI2sBckPin(role: Int, pin: Int) {
        guard("setI2sBckPin")
        return inner.setI2sBckPin(role, pin)
    }

    override fun setI2sClockPinMode(mode: Int) {
        guard("setI2sClockPinMode")
        return inner.setI2sClockPinMode(mode)
    }

    override fun setMckEnabled(enabled: Boolean) {
        guard("setMckEnabled")
        return inner.setMckEnabled(enabled)
    }

    override fun setMckPin(pin: Int) {
        guard("setMckPin")
        return inner.setMckPin(pin)
    }

    override fun setMckMultiplier(mult: Int) {
        guard("setMckMultiplier")
        return inner.setMckMultiplier(mult)
    }

    override fun setAdatOutEnabled(enabled: Boolean) {
        guard("setAdatOutEnabled")
        return inner.setAdatOutEnabled(enabled)
    }

    override fun setAdatOutPin(pin: Int) {
        guard("setAdatOutPin")
        return inner.setAdatOutPin(pin)
    }

    override fun setSpdifRxPin(index: Int, pin: Int) {
        guard("setSpdifRxPin")
        return inner.setSpdifRxPin(index, pin)
    }

    override fun setSpdifInputEnabled(index: Int, enabled: Boolean) {
        guard("setSpdifInputEnabled")
        return inner.setSpdifInputEnabled(index, enabled)
    }

    override fun setI2sRxPin(pair: Int, pin: Int) {
        guard("setI2sRxPin")
        return inner.setI2sRxPin(pair, pin)
    }

    override fun setI2sInputChannels(channels: Int) {
        guard("setI2sInputChannels")
        return inner.setI2sInputChannels(channels)
    }

    override fun setAdatInputEnabled(enabled: Boolean) {
        guard("setAdatInputEnabled")
        return inner.setAdatInputEnabled(enabled)
    }

    override fun setAdatInputPin(pin: Int) {
        guard("setAdatInputPin")
        return inner.setAdatInputPin(pin)
    }

    override fun setAdatInputClockMode(mode: Int) {
        guard("setAdatInputClockMode")
        return inner.setAdatInputClockMode(mode)
    }

    override fun setInputRate(index: Int) {
        guard("setInputRate")
        return inner.setInputRate(index)
    }

    override fun setI2sClockMode(mode: Int) {
        guard("setI2sClockMode")
        return inner.setI2sClockMode(mode)
    }

    override fun setDacMute(enabled: Boolean, activeLow: Boolean, pin: Int, holdMs: Int, releaseMs: Int) {
        guard("setDacMute")
        return inner.setDacMute(enabled, activeLow, pin, holdMs, releaseMs)
    }

    override fun testDacMute() {
        guard("testDacMute")
        return inner.testDacMute()
    }

    override fun setLgSoundSync(enabled: Boolean) {
        guard("setLgSoundSync")
        return inner.setLgSoundSync(enabled)
    }

    override fun setUart(config: UartConfig) {
        guard("setUart")
        return inner.setUart(config)
    }

    override fun setI2c(config: I2cConfig) {
        guard("setI2c")
        return inner.setI2c(config)
    }

    override fun siggenSetConfig(config: SiggenConfig) {
        guard("siggenSetConfig")
        return inner.siggenSetConfig(config)
    }

    override fun siggenControl(action: Int) {
        guard("siggenControl")
        return inner.siggenControl(action)
    }

    override fun resetBufferStats() {
        guard("resetBufferStats")
        return inner.resetBufferStats()
    }

    override fun configImport(text: String, options: Long): String {
        guard("configImport")
        return inner.configImport(text, options)
    }

    override fun filtersImport(text: String, wires: List<Int>): String {
        guard("filtersImport")
        return inner.filtersImport(text, wires)
    }

    override fun csApply(op: String): String {
        if (!isCsRead(op)) guard("csApply")
        return inner.csApply(op)
    }

    companion object {
        /** Core methods that write to the device or command it (refused while read-only). */
        val WRITES: Set<String> = setOf(
            "applyAllParams",
            "setFilter",
            "setCrossover",
            "setBandBypass",
            "setPreamp",
            "setInputPreamp",
            "setBypass",
            "setMasterVolume",
            "setMasterVolumeMode",
            "saveMasterVolume",
            "setUserVolume",
            "setUserMute",
            "setDelay",
            "setLoudness",
            "setLoudnessRef",
            "setLoudnessIntensity",
            "setLoudnessMask",
            "setCrossfeed",
            "setCrossfeedPreset",
            "setCrossfeedFreq",
            "setCrossfeedFeed",
            "setCrossfeedItd",
            "setCrossfeedOutputs",
            "setLevellerEnabled",
            "setLevellerAmount",
            "setLevellerSpeed",
            "setLevellerMaxGain",
            "setLevellerLookahead",
            "setLevellerGate",
            "setLevellerMasks",
            "setInputSource",
            "setPsybassParam",
            "setPsybassMask",
            "setPsybassEnabled",
            "setMatrixRoute",
            "setOutputEnable",
            "setOutputGain",
            "setOutputMute",
            "setOutputDelay",
            "setLimiterParam",
            "setOutputPin",
            "setOutputConfigMode",
            "saveOutputConfig",
            "setChannelName",
            "savePreset",
            "loadPreset",
            "copyPreset",
            "deletePreset",
            "setPresetName",
            "setPresetStartup",
            "saveParams",
            "loadParams",
            "factoryReset",
            "clearClips",
            "setUpmixParam",
            "setSubharmParam",
            "setSubharmSolo",
            "setTubeParam",
            "setOutputType",
            "setI2sBckPin",
            "setI2sClockPinMode",
            "setMckEnabled",
            "setMckPin",
            "setMckMultiplier",
            "setAdatOutEnabled",
            "setAdatOutPin",
            "setSpdifRxPin",
            "setSpdifInputEnabled",
            "setI2sRxPin",
            "setI2sInputChannels",
            "setAdatInputEnabled",
            "setAdatInputPin",
            "setAdatInputClockMode",
            "setInputRate",
            "setI2sClockMode",
            "setDacMute",
            "testDacMute",
            "setLgSoundSync",
            "setUart",
            "setI2c",
            "siggenSetConfig",
            "siggenControl",
            "resetBufferStats",
            "configImport",
            "filtersImport",
            "csApply"
        )

        /** Core methods that only read, compute locally, manage listeners or the connection, or reboot into the bootloader. */
        val PASS_THROUGH: Set<String> = setOf(
            "bootloaderCounts",
            "captureBaseline",
            "checkCore1Conflict",
            "closeFd",
            "computeMagnitudeCurve",
            "computePhaseCurve",
            "computeResponse",
            "configExport",
            "configInspect",
            "coreInfo",
            "csFetch",
            "csPinUses",
            "csSnapshot",
            "curveFreq",
            "disconnect",
            "enterBootloader",
            "fetchAdatOutStatus",
            "fetchAll",
            "fetchBufferStats",
            "fetchCore1Mode",
            "fetchCtrlIfaces",
            "fetchDacMute",
            "fetchInputLock",
            "fetchInputRate",
            "fetchLgStatus",
            "fetchLimiterMeter",
            "fetchOutputPin",
            "fetchSpdifRxChannelStatus",
            "fetchSpdifRxPin",
            "fetchSpdifRxStatus",
            "fetchStat",
            "fetchStatus",
            "fetchSubharmHeadroom",
            "fetchSubharmMeter",
            "fetchUpmixStatus",
            "filtersExport",
            "filtersInspect",
            "getChannelName",
            "getPresetDirectory",
            "getPresetName",
            "getState",
            "inaccessibleDevices",
            "isConnected",
            "maxDelayMs",
            "monitorClear",
            "monitorRead",
            "openFd",
            "pollHotplug",
            "preampMaxDb",
            "presetChanges",
            "presetDirty",
            "processNotifications",
            "refreshParams",
            "rtaGetCaps",
            "rtaGetSnapshot",
            "rtaSetListener",
            "rtaSetRequest",
            "rtaStart",
            "scanDevices",
            "selectDevice",
            "setHotplugCallback",
            "setNotifyListener",
            "siggenFetchCaps",
            "siggenGetConfig",
            "siggenGetStatus"
        )

        /** Control-surface operations that only read the device (`cs.rs` "refresh", "refreshStatus"). */
        private val CS_READ_OPS = setOf("refresh", "refreshStatus")

        private val OP = Regex("\"op\"\\s*:\\s*\"(\\w+)\"")

        /** True only for a JSON op whose single "op" field names a read. */
        fun isCsRead(opJson: String): Boolean {
            val ops = OP.findAll(opJson).map { it.groupValues[1] }.toList()
            return ops.size == 1 && ops[0] in CS_READ_OPS
        }
    }
}
