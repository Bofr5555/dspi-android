package se.bofr.dspi.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Port of qt/qml/components/Throttle.qml: the first value goes out at once,
 * then at most the latest value every [intervalMs]; [cancel] drops a pending
 * value (call it before the final commit on release).
 */
class Throttle<T>(private val scope: CoroutineScope, private val intervalMs: Long = 30, private val send: (T) -> Unit) {
    private var pending: T? = null
    private var hasPending = false
    private var job: Job? = null

    fun submit(value: T) {
        if (job?.isActive == true) {
            pending = value
            hasPending = true
            return
        }
        send(value)
        job =
            scope.launch {
                while (true) {
                    delay(intervalMs)
                    if (!hasPending) break
                    hasPending = false
                    @Suppress("UNCHECKED_CAST")
                    send(pending as T)
                }
            }
    }

    fun cancel() {
        hasPending = false
        pending = null
        job?.cancel()
        job = null
    }
}
