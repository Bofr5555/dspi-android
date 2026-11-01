// Apply order of several hardware calls from one user action: the port of
// DSPi-Console-Windows PendingChangeTracker.cs `TopoSort` (the Windows repo
// ships no tests for it; these follow its documented contract).
package se.bofr.dspi.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class HardwareChangeOrderTest {
    private fun change(key: String, deps: List<String> = emptyList()) =
        HardwareChange(key, "page", key, "a", "b", apply = { 0 }, dependsOn = deps)

    private fun order(vararg c: HardwareChange) = HardwareChangeOrder.topoSort(c.toList()).map { it.key }

    @Test
    fun dependenciesGoFirst() {
        assertEquals(
            listOf("type", "pin", "bck"),
            order(change("pin", listOf("type")), change("bck", listOf("type", "pin")), change("type"))
        )
    }

    @Test
    fun dependenciesOutsideTheBatchCountAsMet() {
        assertEquals(listOf("a", "b"), order(change("a", listOf("not-here")), change("b")))
    }

    @Test
    fun tiesKeepTheGivenOrder() {
        assertEquals(listOf("c", "a", "b"), order(change("c"), change("a"), change("b", listOf("c"))))
    }

    @Test
    fun aCycleFallsBackToTheGivenOrderForTheRest() {
        assertEquals(listOf("free", "x", "y"), order(change("free"), change("x", listOf("y")), change("y", listOf("x"))))
    }

    @Test
    fun anEmptyBatchIsEmpty() {
        assertEquals(emptyList<String>(), order())
    }
}
