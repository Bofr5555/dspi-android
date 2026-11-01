// The analyser's app preferences under the macOS @AppStorage key names
// (DSPi_ConsoleApp.swift AppSettings). Decision F3: a local store until the
// consolidation package moves AppSettings into :ui-common; reading the same
// keys then is a swap of this store's implementation.
package se.bofr.dspi.feature.analysis

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Reads the analyser preferences and keeps the dashboard selection keys. */
interface RtaSettingsStore {
    /** Blocking read (call off the main thread). */
    fun load(): RtaPreferences

    /** Blocking read of a string key (call off the main thread). */
    fun string(key: String): String?

    fun putString(key: String, value: String?)
}

/** Values by macOS key, defaults as macOS. Pure, so it is JVM-tested. */
fun rtaPreferencesFrom(get: (key: String) -> Any?): RtaPreferences {
    val d = RtaPreferences()
    fun dbl(k: String, def: Double) = (get(k) as? Number)?.toDouble() ?: def
    fun int(k: String, def: Int) = (get(k) as? Number)?.toInt() ?: def
    fun bool(k: String, def: Boolean) = get(k) as? Boolean ?: def
    return RtaPreferences(
        floorDb = dbl(RtaSettingsKeys.FLOOR_DB, d.floorDb),
        ceilingDb = dbl(RtaSettingsKeys.CEILING_DB, d.ceilingDb),
        showPeakHold = bool(RtaSettingsKeys.SHOW_PEAK_HOLD, d.showPeakHold),
        smoothing = if (bool(RtaSettingsKeys.SMOOTHING_ON, true)) 0.35 else 0.0,
        barColumns = int(RtaSettingsKeys.BAR_COLUMNS, d.barColumns),
        graphMinFreq = dbl(RtaSettingsKeys.GRAPH_MIN_FREQ, d.graphMinFreq),
        graphMaxFreq = dbl(RtaSettingsKeys.GRAPH_MAX_FREQ, d.graphMaxFreq),
        fftOrder = int(RtaSettingsKeys.FFT_ORDER, d.fftOrder),
        avgMs = int(RtaSettingsKeys.AVG_MS, d.avgMs),
        peakDecayDbs = int(RtaSettingsKeys.PEAK_DECAY_DBS, d.peakDecayDbs),
        dashboardShowGraph = bool(RtaSettingsKeys.DASHBOARD_SHOW_GRAPH, d.dashboardShowGraph),
        dashboardShowBars = bool(RtaSettingsKeys.DASHBOARD_SHOW_BARS, d.dashboardShowBars)
    )
}

/** SharedPreferences file `analysis_settings` under the macOS key names. */
class SharedPreferencesRtaSettingsStore(private val prefs: SharedPreferences) : RtaSettingsStore {
    override fun load(): RtaPreferences {
        val all = prefs.all
        return rtaPreferencesFrom { all[it] }
    }

    override fun string(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }

    companion object {
        const val FILE = "analysis_settings"

        fun of(context: Context) = SharedPreferencesRtaSettingsStore(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}

/** For tests and previews. */
class InMemoryRtaSettingsStore(private val values: MutableMap<String, Any?> = mutableMapOf()) : RtaSettingsStore {
    override fun load(): RtaPreferences = rtaPreferencesFrom { values[it] }

    override fun string(key: String): String? = values[key] as? String

    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

@Composable
internal fun rememberRtaSettingsStore(): RtaSettingsStore {
    val context = LocalContext.current.applicationContext
    return remember(context) { SharedPreferencesRtaSettingsStore.of(context) }
}
