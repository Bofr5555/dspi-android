// The two AppSettings keys the home screen owns on macOS
// (DSPi_ConsoleApp.swift AppSettings: sidebarVolumeMode,
// dashboardCardsPerRow), until :app exposes AppSettings (see
// UPSTREAM-DEVIATIONS HOME). SharedPreferences, no new dependency.
package se.bofr.dspi.feature.home

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import se.bofr.dspi.feature.home.components.SidebarVolumeMode

/** Persistent home-screen preferences; observable through Compose state. */
class HomePreferences private constructor(private val context: Context?) {
    private val prefs get() = context?.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val volumeModeState = mutableStateOf(SidebarVolumeMode.from(prefs?.getString(KEY_VOLUME_MODE, null)))
    private val cardsPerRowState = mutableIntStateOf(prefs?.getInt(KEY_CARDS_PER_ROW, 0) ?: 0)

    /** `AppSettings.sidebarVolumeMode` ("auto" = User Volume, "master"). */
    var sidebarVolumeMode: SidebarVolumeMode
        get() = volumeModeState.value
        set(v) {
            volumeModeState.value = v
            prefs?.edit()?.putString(KEY_VOLUME_MODE, v.raw)?.apply()
        }

    /** `AppSettings.dashboardCardsPerRow`: 0 = Auto, else 1…3. */
    var dashboardCardsPerRow: Int
        get() = cardsPerRowState.intValue
        set(v) {
            cardsPerRowState.intValue = v.coerceIn(0, 3)
            prefs?.edit()?.putInt(KEY_CARDS_PER_ROW, cardsPerRowState.intValue)?.apply()
        }

    companion object {
        private const val FILE = "dspi_home"
        private const val KEY_VOLUME_MODE = "sidebarVolumeMode"
        private const val KEY_CARDS_PER_ROW = "dashboardCardsPerRow"
        private var shared: HomePreferences? = null

        /** In-memory instance (previews, tests). */
        fun inMemory(): HomePreferences = HomePreferences(null)

        fun get(context: Context): HomePreferences = shared ?: HomePreferences(context.applicationContext).also { shared = it }
    }
}

/** The process-wide [HomePreferences] (in memory under the preview renderer). */
@Composable
fun rememberHomePreferences(): HomePreferences {
    val context = LocalContext.current
    val preview = LocalInspectionMode.current
    return remember { if (preview) HomePreferences.inMemory() else HomePreferences.get(context) }
}

/** Session-only flags (macOS: per launch). */
internal object HomeSession {
    /** `FirmwareMismatchBannerState.hiddenForThisLaunch`. */
    val bannerHidden: MutableState<Boolean> = mutableStateOf(false)
}
