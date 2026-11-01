package se.bofr.dspi.feature.settings

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver

/**
 * Reads [block] the way a composition does (Compose tracks snapshot-state
 * reads), and re-reads it when one of those reads changes: a stand-in for a
 * Dialog's own composition, which recomposes only on state it itself read
 * (reviews/stale-state-audit.md). A block reading a StateFlow.value or a
 * plain field never invalidates, so [value] stays stale and [invalidations]
 * stays 0 — exactly the HIL bug.
 */
internal class ObservedRead<T>(private val block: () -> T) : AutoCloseable {
    private val observer = SnapshotStateObserver { it() }
    private var dirty = false
    private var current: Result<T>? = null

    var invalidations = 0
        private set

    val value: T
        get() {
            settle()
            return current!!.getOrThrow()
        }

    init {
        observer.start()
        read()
    }

    private fun read() {
        observer.observeReads(this, { _: ObservedRead<T> ->
            invalidations++
            dirty = true
        }) { current = runCatching(block) }
    }

    /** Deliver pending snapshot writes (a frame) and recompose if invalidated. */
    fun settle() {
        Snapshot.sendApplyNotifications()
        if (dirty) {
            dirty = false
            read()
        }
    }

    override fun close() {
        observer.stop()
        observer.clear()
    }
}
