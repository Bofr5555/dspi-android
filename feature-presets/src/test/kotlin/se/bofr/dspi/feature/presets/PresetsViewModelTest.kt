package se.bofr.dspi.feature.presets

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.feature.presets.autoeq.AutoEQManager
import se.bofr.dspi.feature.presets.autoeq.InMemoryFavoritesStore

/**
 * Decision E9 / review P4: the controller lives in a ViewModel. A simulated
 * Activity recreation (a new ViewModelProvider over the same ViewModelStore,
 * as the framework does on rotation) keeps the open alert and the running
 * device sequence; leaving the screen (store cleared) does not cut a sequence
 * between its flash writes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PresetsViewModelTest {
    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.provider(store: ViewModelStore, bridge: DspiBridge): ViewModelProvider {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val manager = AutoEQManager({ "{}" }, null, InMemoryFavoritesStore())
        return ViewModelProvider.create(store, PresetsViewModel.factory(bridge, manager, null))
    }

    @Test
    fun recreationKeepsTheOpenAlertAndFinishesTheCopy() = runTest {
        val core = presetFake()
        val b = attached(core)
        val store = ViewModelStore()
        val vm1 = provider(store, b)[PresetsViewModel::class.java]
        b.setBypass(true)
        runCurrent()
        vm1.controller.presetMenu.copyActivePreset(1)
        runCurrent()
        assertEquals("Unsaved Changes", vm1.controller.host.alert.value!!.messageText)

        // Rotation: the Activity (and its composition scope) is gone; the ViewModel is not.
        val vm2 = provider(store, b)[PresetsViewModel::class.java]
        assertSame(vm1, vm2)
        assertEquals("Unsaved Changes", vm2.controller.host.alert.value!!.messageText)
        vm2.controller.host.press("Save")
        runCurrent()
        assertEquals(listOf("savePreset(0)", "setPresetName(1, Preset 2)", "copyPreset(0, 1)"), core.presetCalls())
        store.clear()
    }

    @Test
    fun leavingTheScreenMidSequenceDoesNotStopIt() = runTest {
        val core = presetFake()
        val b = attached(core)
        val store = ViewModelStore()
        val vm = provider(store, b)[PresetsViewModel::class.java]
        vm.controller.presetMenu.clearAllPresets()
        vm.controller.host.press("Clear All")
        // The ViewModel is cleared before the sequence ran (screen popped / Activity finished)
        store.clear()
        runCurrent()
        assertEquals(listOf("deletePreset(0)", "deletePreset(1)", "loadPreset(0)"), core.presetCalls())
        assertEquals(0, b.presetOccupied)
    }

    @Test
    fun aSwitchSurvivesRecreationBetweenSaveAndLoad() = runTest {
        val core = presetFake()
        val b = attached(core)
        val store = ViewModelStore()
        val vm = provider(store, b)[PresetsViewModel::class.java]
        b.setBypass(true)
        runCurrent()
        vm.controller.presetMenu.selectPreset(1)
        runCurrent()
        vm.controller.host.press("Save")
        store.clear()
        runCurrent()
        assertEquals(listOf("savePreset(0)", "loadPreset(1)"), core.presetCalls())
        assertEquals(1, b.activePresetSlot)
        assertNull(vm.controller.host.alert.value)
        assertTrue(!vm.controller.presetMenu.presetSwitchInFlight.value)
    }
}
