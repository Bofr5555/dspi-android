// Read-only on firmware mismatch (OWNER decision G1, reviews/decisions-waveB-3.md):
// a deliberate deviation from the official clients. While the bridge's
// `writesAllowed` is false (no device, or firmware ≠ the expected version)
// every control that writes to the device is disabled. :app provides the
// value; the :ui-common controls read it through their `enabled`, so
// features need no per-control code for them. App preferences, navigation
// and the firmware update path are not device writes: they sit under
// [WritesExempt].
package se.bofr.dspi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf

/**
 * True while the device accepts writes (bridge `writesAllowed`). Default true
 * (previews, tests, a screen without a provider).
 */
val LocalWritesAllowed = compositionLocalOf { true }

/** Whether controls that write to the device may be used here. */
@Composable
fun writesAllowed(): Boolean = LocalWritesAllowed.current

/** A device-writing control's effective `enabled`: its own state and [LocalWritesAllowed]. */
@Composable
fun writeEnabled(enabled: Boolean): Boolean = enabled && LocalWritesAllowed.current

/**
 * Content whose controls do not write to the device (app preferences, view
 * choices, the firmware update path): stays usable while read-only.
 */
@Composable
fun WritesExempt(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalWritesAllowed provides true, content = content)
}
