package se.bofr.dspi.feature.presets

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import se.bofr.dspi.bridge.DspiBridgeImpl
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.fake.FakeDspiCore
import se.bofr.dspi.fake.demoDspState

/** Slots 0 ("Living Room") and 1 (unnamed) stored, slot 0 active, startup = specified default 0. */
fun presetFake(): FakeDspiCore {
    val s = demoDspState()
    return FakeDspiCore(
        s.copy(
            presetOccupied = 0b11,
            activePresetSlot = 0,
            presetStartupMode = 0,
            presetDefaultSlot = 0,
            presetNames = List(s.presetNames.size) { if (it == 0) "Living Room" else "" }
        )
    )
}

/** A bridge over [core] on the test scheduler (as :bridge's own tests build it). */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.newBridge(core: FakeDspiCore): DspiBridgeImpl {
    val d = StandardTestDispatcher(testScheduler)
    return DspiBridgeImpl(core, backgroundScope, ffi = d, main = d, clock = { testScheduler.currentTime })
}

@OptIn(ExperimentalCoroutinesApi::class)
suspend fun TestScope.attached(core: FakeDspiCore = presetFake()): DspiBridgeImpl {
    val b = newBridge(core)
    check(b.attachDevice(42, core.serial, DspiConstants.VENDOR_ID))
    runCurrent()
    return b
}

private val PRESET_METHODS =
    listOf(
        "savePreset", "loadPreset", "copyPreset", "deletePreset", "setPresetName", "loadParams", "factoryReset",
        "saveMasterVolume", "saveOutputConfig", "configImport", "filtersImport", "setPresetStartup"
    )

/** Calls the fake saw that matter for preset flows, in order. */
fun FakeDspiCore.presetCalls(): List<String> = synchronized(calls) {
    calls.filter { c -> PRESET_METHODS.any { c.startsWith("$it(") || c == it } }
}

/** Press [title] on the alert currently shown. */
fun AlertHost.press(title: String, response: AlertResponse = AlertResponse()) {
    val a = checkNotNull(alert.value) { "no alert shown" }
    respond(a.button(title), response)
}

/** A bridge whose device went away (the fake auto-connects on scan, so attach and detach it). */
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun TestScope.detached(core: FakeDspiCore = presetFake()): DspiBridgeImpl {
    val b = attached(core)
    b.detachDevice(core.serial)
    runCurrent()
    check(!b.connected)
    synchronized(core.calls) { core.calls.clear() }
    return b
}
