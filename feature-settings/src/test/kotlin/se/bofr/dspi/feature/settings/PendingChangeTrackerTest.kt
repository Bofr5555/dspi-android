// Tests for the port of DSPi-Console-Windows Settings/PendingChangeTracker.cs.
// The Windows repo ships no tests for it (DSPiConsole.Core.Tests covers Core
// only), so these are written from the IPendingChangeTracker contract
// documented in IPendingChangeTracker.cs, one test per documented rule.
package se.bofr.dspi.feature.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.bridge.ConfirmationToken
import se.bofr.dspi.bridge.DangerousOp

@OptIn(ExperimentalCoroutinesApi::class)
class PendingChangeTrackerTest {
    private val token = ConfirmationToken(DangerousOp.HARDWARE_APPLY, 1)
    private val log = mutableListOf<String>()

    private fun change(key: String, old: String = "GPIO 6", new: String = "GPIO 7", status: Int = 0, deps: List<String> = emptyList()) =
        PendingChange(key, "page", key, old, new, apply = {
            log += key
            status
        }, dependsOn = deps)

    @Test
    fun stageAddsInStageOrder() {
        val t = PendingChangeTracker()
        t.stage(change("b"))
        t.stage(change("a"))
        assertEquals(listOf("b", "a"), t.pending.value.map { it.key })
        assertEquals(2, t.count)
    }

    @Test
    fun restageKeepsFirstOldDisplayAndPosition() {
        val t = PendingChangeTracker()
        t.stage(change("a", old = "GPIO 6", new = "GPIO 7"))
        t.stage(change("b"))
        t.stage(change("a", old = "GPIO 7", new = "GPIO 8"))
        val a = t.get("a")!!
        assertEquals("GPIO 6", a.oldDisplay)
        assertEquals("GPIO 8", a.newDisplay)
        assertEquals(listOf("a", "b"), t.pending.value.map { it.key })
    }

    @Test
    fun editingBackToOriginalDropsTheChange() {
        val t = PendingChangeTracker()
        t.stage(change("a", old = "GPIO 6", new = "GPIO 7"))
        t.stage(change("a", old = "GPIO 7", new = "GPIO 6"))
        assertEquals(0, t.count)
        assertNull(t.get("a"))
    }

    @Test
    fun stagingANoOpChangeStagesNothing() {
        val t = PendingChangeTracker()
        t.stage(change("a", old = "On", new = "On"))
        assertEquals(0, t.count)
    }

    @Test
    fun discardRemovesOneAndReportsWhetherItExisted() {
        val t = PendingChangeTracker()
        t.stage(change("a"))
        t.stage(change("b"))
        assertTrue(t.discard("a"))
        assertFalse(t.discard("a"))
        assertEquals(listOf("b"), t.pending.value.map { it.key })
    }

    @Test
    fun discardAllEmpties() {
        val t = PendingChangeTracker()
        t.stage(change("a"))
        t.stage(change("b"))
        t.discardAll()
        assertEquals(0, t.count)
        assertTrue(t.pending.value.isEmpty())
    }

    @Test
    fun applyAllOnEmptyIsANoOp() = runTest {
        val r = PendingChangeTracker().applyAll(token)
        assertEquals(ApplyReport(0, 0, emptyList()), r)
    }

    @Test
    fun successesAreRemovedFailuresStayAndAreReported() = runTest {
        val t = PendingChangeTracker()
        t.stage(change("ok1"))
        t.stage(change("bad", status = 2))
        t.stage(change("ok2"))
        val r = t.applyAll(token)
        assertEquals(2, r.applied)
        assertEquals(1, r.failed)
        assertEquals("bad", r.failures.single().first.key)
        assertEquals("firmware status 0x02", r.failures.single().second)
        assertEquals(listOf("bad"), t.pending.value.map { it.key })
    }

    @Test
    fun failureUsesTheChangesStatusText() = runTest {
        val t = PendingChangeTracker()
        t.stage(change("bad", status = 4).copy(statusText = { "status $it" }))
        assertEquals("status 4", t.applyAll(token).failures.single().second)
    }

    @Test
    fun anExceptionIsAFailureAndTheBatchContinues() = runTest {
        val t = PendingChangeTracker()
        t.stage(PendingChange("boom", "p", "Boom", "a", "b", apply = { throw IllegalStateException("no reply") }))
        t.stage(change("next"))
        val r = t.applyAll(token)
        assertEquals(1, r.applied)
        assertEquals("no reply", r.failures.single().second)
        assertEquals(listOf("next"), log)
    }

    @Test
    fun applyOrderFollowsDependencies() = runTest {
        val t = PendingChangeTracker()
        t.stage(change("pin", deps = listOf("type")))
        t.stage(change("bck", deps = listOf("type", "pin")))
        t.stage(change("type"))
        t.applyAll(token)
        assertEquals(listOf("type", "pin", "bck"), log)
    }

    @Test
    fun dependenciesOutsideTheBatchCountAsMet() = runTest {
        val t = PendingChangeTracker()
        t.stage(change("a", deps = listOf("not-staged")))
        t.stage(change("b"))
        t.applyAll(token)
        assertEquals(listOf("a", "b"), log)
    }

    @Test
    fun tiesKeepStageOrder() {
        val batch = listOf(change("c"), change("a"), change("b", deps = listOf("c")))
        assertEquals(listOf("c", "a", "b"), PendingChangeTracker.topoSort(batch).map { it.key })
    }

    @Test
    fun aCycleFallsBackToStageOrderForTheRest() {
        val batch = listOf(change("free"), change("x", deps = listOf("y")), change("y", deps = listOf("x")))
        assertEquals(listOf("free", "x", "y"), PendingChangeTracker.topoSort(batch).map { it.key })
    }

    @Test
    fun aFailedDependencyDoesNotBlockItsDependent() = runTest {
        // Windows attempts everything in order; the dependent reports its own outcome
        val t = PendingChangeTracker()
        t.stage(change("type", status = 4))
        t.stage(change("pin", deps = listOf("type")))
        val r = t.applyAll(token)
        assertEquals(listOf("type", "pin"), log)
        assertEquals(1, r.applied)
    }

    @Test
    fun aRestageDuringApplySurvives() = runTest {
        val t = PendingChangeTracker()
        t.stage(
            PendingChange("a", "p", "A", "GPIO 6", "GPIO 7", apply = {
                // The user edits the same field again while the batch runs
                t.stage(PendingChange("a", "p", "A", "GPIO 7", "GPIO 9", apply = { 0 }))
                0
            })
        )
        t.applyAll(token)
        assertEquals("GPIO 9", t.get("a")?.newDisplay)
    }

    @Test
    fun theTokenIsHandedToEveryApply() = runTest {
        val seen = mutableListOf<ConfirmationToken>()
        val t = PendingChangeTracker()
        t.stage(
            PendingChange("a", "p", "A", "1", "2", apply = { tk ->
                seen += tk
                0
            })
        )
        t.stage(
            PendingChange("b", "p", "B", "1", "2", apply = { tk ->
                seen += tk
                0
            })
        )
        t.applyAll(token)
        assertEquals(listOf(token, token), seen)
    }
}
