// One device call of a hardware control, and the apply order of several
// (port of DSPi-Console-Windows Settings/IPendingChangeTracker.cs
// `PendingChange` and PendingChangeTracker.cs `TopoSort`; the Windows staging
// queue itself is not used: macOS applies hardware edits live, E1b).
package se.bofr.dspi.feature.settings

import se.bofr.dspi.bridge.ConfirmationToken

/**
 * @param key stable identifier, `"{page-id}.{field}"`, referenced by [dependsOn].
 * @param pageId the [SettingsCategory] id the control lives on.
 * @param fieldLabel e.g. "Output 1 GPIO".
 * @param oldDisplay / [newDisplay] the value before/after, as shown.
 * @param apply the device call with the HARDWARE_APPLY token; 0 on success or
 *   the firmware's `PIN_CONFIG_*` status byte.
 * @param dependsOn keys that must be applied first when several calls make up
 *   one user action.
 * @param statusText the failure sentence (macOS status-row wording).
 */
data class HardwareChange(
    val key: String,
    val pageId: String,
    val fieldLabel: String,
    val oldDisplay: String,
    val newDisplay: String,
    val apply: suspend (ConfirmationToken) -> Int,
    val dependsOn: List<String> = emptyList(),
    val statusText: ((Int) -> String)? = null
)

object HardwareChangeOrder {
    /**
     * Stable topological sort (Windows `TopoSort`): the next change whose
     * dependencies are met (already placed, or not in the batch); ties by
     * given order; a cycle falls back to the given order for the rest.
     */
    fun topoSort(batch: List<HardwareChange>): List<HardwareChange> {
        val keys = batch.mapTo(HashSet()) { it.key }
        val result = ArrayList<HardwareChange>(batch.size)
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
