package se.bofr.dspi.feature.home

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * A real (node-less) Compose composition with a running Recomposer, on the
 * JVM: recomposition, strong skipping and collectAsStateWithLifecycle behave
 * as on a device, so a test can tell whether a composable that reads plain
 * bridge getters is recomposed when they change (reviews/stale-state-audit.md).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class CompositionHarness : AutoCloseable {
    private object NoNodes : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit

        override fun insertBottomUp(index: Int, instance: Unit) = Unit

        override fun remove(index: Int, count: Int) = Unit

        override fun move(from: Int, to: Int, count: Int) = Unit

        override fun onClear() = Unit
    }

    private val owner =
        object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            override val lifecycle: Lifecycle get() = registry
        }
    private val clock = BroadcastFrameClock()
    private val scope = CoroutineScope(Dispatchers.Unconfined + clock + Job())
    private val recomposer = Recomposer(scope.coroutineContext)
    private val composition = Composition(NoNodes, recomposer)

    init {
        // repeatOnLifecycle switches to Dispatchers.Main.immediate.
        Dispatchers.setMain(Dispatchers.Unconfined)
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
    }

    fun setContent(content: @Composable () -> Unit) {
        composition.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner, content = content) }
        frame()
    }

    /** Deliver snapshot writes and run one frame of recomposition. */
    fun frame() {
        repeat(3) {
            Snapshot.sendApplyNotifications()
            clock.sendFrame(0L)
        }
    }

    override fun close() {
        composition.dispose()
        recomposer.cancel()
        scope.cancel()
        Dispatchers.resetMain()
    }
}
