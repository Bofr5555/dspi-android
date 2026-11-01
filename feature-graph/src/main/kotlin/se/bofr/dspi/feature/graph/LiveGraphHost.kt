// Port of DSPi-Console-b5:DSPi Console/LiveGraphHost.swift @ b5fdeac.
// LiveGraphHost itself (an NSHostingView boundary so a live graph's updates do
// not re-lay out the whole window) has no Compose counterpart: Compose already
// scopes recomposition and the graphs draw in Canvas. GraphLive, the live
// overrides a tool window's graph reads while one of its parameters is
// dragged, is ported for the tool views (TOOLS) to share.
package se.bofr.dspi.feature.graph

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier

/**
 * Live overrides for a graph while one of its parameters is being dragged: the
 * dragged value by key, empty between drags. The pane resolves each parameter
 * as override-or-committed, and clears the overrides when the committed values
 * change (the commit on release arriving), so nothing visibly moves.
 */
class GraphLive<K> {
    private val overrides = mutableStateMapOf<K, Float>()

    val isEmpty: Boolean get() = overrides.isEmpty()

    fun set(key: K, value: Float) {
        overrides[key] = value
    }

    fun clear() {
        if (overrides.isNotEmpty()) overrides.clear()
    }

    operator fun get(key: K, base: Float): Float = overrides[key] ?: base
}

/**
 * macOS `LiveGraphHost`: in Compose a plain container, since a graph's
 * updates already stay inside its own recompose scope and Canvas draw.
 * Kept so tool views port their `LiveGraphHost { … }` call sites 1:1.
 */
@Composable
fun LiveGraphHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier) { content() }
}
