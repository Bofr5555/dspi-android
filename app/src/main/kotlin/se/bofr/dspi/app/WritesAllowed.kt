// Read-only on firmware mismatch (decision G1): what :app provides as
// :ui-common's LocalWritesAllowed.
//
// TEMPORARY SHIM — remove when `DspiBridge.writesAllowed` (BRIDGE, G1) is on
// main: replace [writesAllowedFlow] by `bridge.writesAllowed` and
// [writesAllowedNow] by `bridge.writesAllowed.value`. Same definition:
// connected && compat == COMPAT_OK.
package se.bofr.dspi.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.core.DspiConstants

/** G1: the device accepts writes (shim for `bridge.writesAllowed.value`). */
fun DspiBridge.writesAllowedNow(): Boolean = status.value.connected && compat == DspiConstants.COMPAT_OK

/** G1: [writesAllowedNow] as a flow, re-evaluated on every status and state change (shim for `bridge.writesAllowed`). */
fun DspiBridge.writesAllowedFlow(): Flow<Boolean> =
    combine(status, stateRevision) { s, _ -> s.connected && compat == DspiConstants.COMPAT_OK }.distinctUntilChanged()
