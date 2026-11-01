package se.bofr.dspi.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Holds back everything that can open a device (the session's start/scan and
 * the Activity's `USB_DEVICE_ATTACHED` hand-over) until [ready] returns: the
 * persistent links must be in memory before the bridge selects a device and
 * calls `LinkStore.load` on the main thread (review A3). Blocks run on
 * [scope] (main) in submission order; once ready they run at once.
 */
class UsbStartGate(private val scope: CoroutineScope, ready: suspend () -> Unit) {
    private val readyJob: Job = scope.launch { ready() }
    private var last: Job = readyJob

    fun run(block: () -> Unit) {
        if (readyJob.isCompleted && last.isCompleted) {
            block()
            return
        }
        val previous = last
        last = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            previous.join()
            block()
        }
    }
}
