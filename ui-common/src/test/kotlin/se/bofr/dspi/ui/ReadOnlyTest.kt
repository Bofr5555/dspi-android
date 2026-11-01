package se.bofr.dspi.ui

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

/** Decision G1: the controls' effective `enabled` follows LocalWritesAllowed; WritesExempt lifts it. */
class ReadOnlyTest {
    private object NoNodes : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit

        override fun insertBottomUp(index: Int, instance: Unit) = Unit

        override fun remove(index: Int, count: Int) = Unit

        override fun move(from: Int, to: Int, count: Int) = Unit

        override fun onClear() = Unit
    }

    /** Runs [block] once in a node-less composition and returns what it read. */
    private fun <T> read(block: @Composable () -> T): T {
        val results = mutableListOf<T>()
        val composition = Composition(NoNodes, Recomposer(Dispatchers.Unconfined))
        composition.setContent { results += block() }
        composition.dispose()
        return results.single()
    }

    @Test
    fun withoutAProviderWritesAreAllowed() {
        assertEquals(true, read { writesAllowed() })
        assertEquals(true, read { writeEnabled(true) })
        assertEquals(false, read { writeEnabled(false) })
    }

    @Test
    fun readOnlyDisablesEveryWritingControl() {
        val r = read {
            var v: Pair<Boolean, Boolean>? = null
            CompositionLocalProvider(LocalWritesAllowed provides false) { v = writeEnabled(true) to writesAllowed() }
            v!!
        }
        assertEquals(false to false, r)
    }

    @Test
    fun exemptContentStaysUsable() {
        val r = read {
            var v: Boolean? = null
            CompositionLocalProvider(LocalWritesAllowed provides false) { WritesExempt { v = writeEnabled(true) } }
            v!!
        }
        assertEquals(true, r)
    }
}
