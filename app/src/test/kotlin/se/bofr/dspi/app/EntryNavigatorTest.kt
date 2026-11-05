package se.bofr.dspi.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tester report (0.9.0): "pressing the back button sometimes freezes the app" — an in-app back
 * button. NavController.popBackStack() pops even the start destination, leaving an empty NavHost
 * (a blank page no in-app control can leave). The fake below pops the same way.
 */
class EntryNavigatorTest {
    private class FakeStack(vararg ids: String) : EntryBackStack {
        val entries = ids.toMutableList()
        private var next = 0
        override val currentId get() = entries.lastOrNull()
        override val canPop get() = entries.size > 1

        override fun pop() {
            entries.removeLastOrNull()
        }

        override fun navigate(route: String) {
            entries += "$route#${next++}"
        }
    }

    @Test
    fun unguardedDoubleTapEmptiesTheStack() {
        // What 0.9.0 did: both taps of a double tap on Graph's "‹ Back" called popBackStack()
        val stack = FakeStack("home", "graph")
        repeat(2) { stack.pop() }
        assertEquals(emptyList<String>(), stack.entries)
    }

    @Test
    fun doubleTapOnBackPopsOnlyItsOwnScreen() {
        val stack = FakeStack("home", "graph")
        val graph = EntryNavigator(stack, "graph")
        graph.back()
        graph.back() // the outgoing screen is still tappable during its exit transition
        assertEquals(listOf("home"), stack.entries)
    }

    @Test
    fun doubleTapDoesNotSkipAScreen() {
        val stack = FakeStack("home", "channel", "graph")
        val graph = EntryNavigator(stack, "graph")
        graph.back()
        graph.back()
        assertEquals(listOf("home", "channel"), stack.entries)
    }

    @Test
    fun automaticReturnRacingATapPopsOnce() {
        // ChannelScreen returns home by itself when its channel goes away (device unplugged)
        val stack = FakeStack("home", "channel")
        val channel = EntryNavigator(stack, "channel")
        channel.back() // the tap
        channel.back() // the LaunchedEffect
        assertEquals(listOf("home"), stack.entries)
    }

    @Test
    fun backNeverPopsTheStartDestination() {
        val stack = FakeStack("home")
        EntryNavigator(stack, "home").back()
        assertEquals(listOf("home"), stack.entries)
    }

    @Test
    fun doubleTapOnARowOpensThePageOnce() {
        val stack = FakeStack("home")
        val home = EntryNavigator(stack, "home")
        home.go("graph")
        home.go("graph")
        assertEquals(listOf("home", "graph#0"), stack.entries)
        // ...so one back returns home
        EntryNavigator(stack, "graph#0").back()
        assertEquals(listOf("home"), stack.entries)
    }

    @Test
    fun theCurrentScreenNavigatesAndReturns() {
        val stack = FakeStack("home", "presets")
        EntryNavigator(stack, "presets").go("settings")
        assertEquals("settings#0", stack.currentId)
        EntryNavigator(stack, "settings#0").back()
        assertEquals(listOf("home", "presets"), stack.entries)
    }
}
