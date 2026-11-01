// Port of DSPi-Console-Windows:DSPiConsole/Settings/IPendingChangeTracker.cs +
// PendingChangeTracker.cs @ _ref (Windows Console). Logic kept 1:1; the apply
// closure takes the bridge's HARDWARE_APPLY token (D2) and is a suspend fun.
package se.bofr.dspi.feature.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import se.bofr.dspi.bridge.ConfirmationToken

/**
 * One staged-but-not-yet-applied hardware change (Windows `PendingChange`).
 * Pages produce these from their control-change handlers and pass them to
 * [PendingChangeTracker.stage]; the save bar's Save drains them with
 * [PendingChangeTracker.applyAll].
 *
 * @param key stable identifier, `"{page-id}.{field}"`; staging the same key
 *   again replaces the change but keeps the first [oldDisplay].
 * @param pageId the [SettingsCategory] that produced it (sidebar dot).
 * @param fieldLabel e.g. "Output 1 GPIO".
 * @param oldDisplay pre-edit value as shown ("GPIO 6"); kept from the first stage.
 * @param newDisplay pending value as shown ("GPIO 7").
 * @param apply the device call; returns 0 on success or the firmware's
 *   `PIN_CONFIG_*` status byte.
 * @param dependsOn keys that must apply successfully first.
 * @param patch the change applied to the draft view of the hardware
 *   configuration (Android: lets the pages show staged values).
 * @param statusText the failure sentence for a non-zero status (Android: the
 *   macOS status-row wording; Windows reports `firmware status 0xNN`).
 */
data class PendingChange(
    val key: String,
    val pageId: String,
    val fieldLabel: String,
    val oldDisplay: String,
    val newDisplay: String,
    val apply: suspend (ConfirmationToken) -> Int,
    val dependsOn: List<String> = emptyList(),
    val patch: (OutputConfigSnapshot) -> OutputConfigSnapshot = { it },
    val statusText: ((Int) -> String)? = null
)

/** Summary of an [PendingChangeTracker.applyAll] run (Windows `ApplyReport`). */
data class ApplyReport(val applied: Int, val failed: Int, val failures: List<Pair<PendingChange, String>>)

/**
 * In-memory store of pending hardware changes (Windows `PendingChangeTracker`).
 * Stage order is kept; apply order is a stable topological sort on
 * [PendingChange.dependsOn]. Main thread only.
 */
class PendingChangeTracker {
    private val byKey = LinkedHashMap<String, PendingChange>()
    private val order = ArrayList<String>()
    private val _pending = MutableStateFlow<List<PendingChange>>(emptyList())

    /** Staged changes in stage order; emits on every change (Windows `Changed`). */
    val pending: StateFlow<List<PendingChange>> = _pending.asStateFlow()

    val count: Int get() = byKey.size

    fun get(key: String): PendingChange? = byKey[key]

    /**
     * Add or replace a staged change. A restage keeps the first [PendingChange.oldDisplay];
     * a change edited back to its original value is dropped.
     */
    fun stage(change: PendingChange) {
        val existing = byKey[change.key]
        if (existing != null) {
            byKey[change.key] = change.copy(oldDisplay = existing.oldDisplay)
        } else {
            byKey[change.key] = change
            order += change.key
        }
        val stored = byKey.getValue(change.key)
        if (stored.oldDisplay == stored.newDisplay) {
            byKey.remove(change.key)
            order.remove(change.key)
        }
        raiseChanged()
    }

    /** Remove one staged change. */
    fun discard(key: String): Boolean {
        if (byKey.remove(key) == null) return false
        order.remove(key)
        raiseChanged()
        return true
    }

    fun discardAll() {
        if (byKey.isEmpty()) return
        byKey.clear()
        order.clear()
        raiseChanged()
    }

    /**
     * Apply every staged change in dependency order. Successes are removed at
     * once (so a restage during the run behaves); failures stay staged and are
     * reported. Cancellation stops between calls.
     */
    suspend fun applyAll(token: ConfirmationToken): ApplyReport {
        if (byKey.isEmpty()) return ApplyReport(0, 0, emptyList())
        val batch = order.map { byKey.getValue(it) }
        val ordered = topoSort(batch)
        val failures = ArrayList<Pair<PendingChange, String>>()
        var applied = 0
        for (change in ordered) {
            currentCoroutineContext().ensureActive()
            val status = try {
                change.apply(token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += change to (e.message ?: e.toString())
                continue
            }
            if (status == 0) {
                // Only if it is still the change we applied (not restaged meanwhile)
                if (byKey[change.key] === change) {
                    byKey.remove(change.key)
                    order.remove(change.key)
                }
                applied++
            } else {
                failures += change to (change.statusText?.invoke(status) ?: "firmware status 0x%02X".format(status))
            }
        }
        raiseChanged()
        return ApplyReport(applied, failures.size, failures)
    }

    private fun raiseChanged() {
        _pending.value = order.map { byKey.getValue(it) }
    }

    companion object {
        /**
         * Stable topological sort: the next change whose dependencies are met
         * (already placed, or not in the batch); ties by stage order. A cycle
         * falls back to stage order for the rest.
         */
        fun topoSort(batch: List<PendingChange>): List<PendingChange> {
            val keys = batch.mapTo(HashSet()) { it.key }
            val result = ArrayList<PendingChange>(batch.size)
            val seen = HashSet<String>()
            val pending = ArrayList(batch)
            while (pending.isNotEmpty()) {
                val picked = pending.indexOfFirst { c -> c.dependsOn.all { it !in keys || it in seen } }
                if (picked < 0) {
                    result += pending
                    break
                }
                val chosen = pending.removeAt(picked)
                result += chosen
                seen += chosen.key
            }
            return result
        }
    }
}
