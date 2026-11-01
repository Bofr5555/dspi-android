// Session-only state of the home screen (macOS: per launch). The persistent
// home preferences (sidebarVolumeMode, dashboardCardsPerRow) are the shared
// AppSettings in :ui-common (decision F3).
package se.bofr.dspi.feature.home

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/** Session-only flags (macOS: per launch). */
internal object HomeSession {
    /** `FirmwareMismatchBannerState.hiddenForThisLaunch`. */
    val bannerHidden: MutableState<Boolean> = mutableStateOf(false)
}
