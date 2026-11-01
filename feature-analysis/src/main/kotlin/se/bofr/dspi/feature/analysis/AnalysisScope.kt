// Where the analysis views run their device sequences. "While on screen" is
// :ui-common's WhileVisible (decision E6).
package se.bofr.dspi.feature.analysis

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope

/**
 * Decision E9: multi-call device sequences run outside the composition so a
 * configuration change cannot cut them in half. The bridge is main-thread
 * safe, so this is a process-lifetime main scope.
 */
internal object AnalysisScope : CoroutineScope by MainScope()
