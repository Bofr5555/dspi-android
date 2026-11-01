// DataStore persistence for AppSettings (macOS @AppStorage → Preferences
// DataStore, one key per macOS UserDefaults key).
package se.bofr.dspi.feature.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

/**
 * [AppSettingsStore] over Preferences DataStore. [settings] updates
 * immediately on [update] (UI stays responsive); the changed keys are written
 * in the background. The stored values are loaded once at start; edits made
 * before that are replayed onto them, not lost.
 */
class DataStoreAppSettingsStore(
    private val store: DataStore<Preferences>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : AppSettingsStore {
    private val state = MutableStateFlow(AppSettings())
    override val settings: StateFlow<AppSettings> = state.asStateFlow()
    private val lock = Any()
    private var loaded = false

    /** Edits made before the stored values arrived; replayed onto them (review S14). */
    private val early = ArrayList<(AppSettings) -> AppSettings>()

    init {
        scope.launch {
            val stored = AppSettingsFields.read(store.data.first())
            val merged = synchronized(lock) {
                loaded = true
                early.fold(stored) { s, f -> f(s) }.also {
                    early.clear()
                    state.value = it
                }
            }
            if (merged != stored) store.edit { AppSettingsFields.write(it, stored, merged) }
        }
    }

    override fun update(transform: (AppSettings) -> AppSettings) {
        val (old, new) = synchronized(lock) {
            val old = state.value
            val new = transform(old)
            if (new == old) return
            state.value = new
            if (!loaded) {
                early += transform
                return
            }
            old to new
        }
        scope.launch { store.edit { AppSettingsFields.write(it, old, new) } }
    }

    companion object {
        @Volatile private var instance: DataStoreAppSettingsStore? = null

        /** The app's one store (DataStore requires a single instance per file). */
        fun of(context: Context): AppSettingsStore = instance ?: synchronized(this) {
            instance ?: DataStoreAppSettingsStore(context.applicationContext.appSettingsDataStore).also { instance = it }
        }
    }
}

/** The AppSettings ↔ Preferences mapping, keyed by the macOS names. */
internal object AppSettingsFields {
    private class Field<T : Any>(val key: Preferences.Key<T>, val get: (AppSettings) -> T, val set: AppSettings.(T) -> AppSettings) {
        fun load(p: Preferences, s: AppSettings): AppSettings = p[key]?.let { s.set(it) } ?: s

        fun store(m: MutablePreferences, old: AppSettings?, new: AppSettings) {
            val v = get(new)
            if (old == null || get(old) != v) m[key] = v
        }
    }

    private fun b(name: String, get: (AppSettings) -> Boolean, set: AppSettings.(Boolean) -> AppSettings) =
        Field(booleanPreferencesKey(name), get, set)

    private fun d(name: String, get: (AppSettings) -> Double, set: AppSettings.(Double) -> AppSettings) =
        Field(doublePreferencesKey(name), get, set)

    private fun i(name: String, get: (AppSettings) -> Int, set: AppSettings.(Int) -> AppSettings) = Field(intPreferencesKey(name), get, set)

    private fun s(name: String, get: (AppSettings) -> String, set: AppSettings.(String) -> AppSettings) =
        Field(stringPreferencesKey(name), get, set)

    private val fields: List<Field<*>> = listOf(
        b("showGraphGlow", { it.showGraphGlow }, { copy(showGraphGlow = it) }),
        d("graphLineWidth", { it.graphLineWidth }, { copy(graphLineWidth = it) }),
        d("graphAnimationSpeed", { it.graphAnimationSpeed }, { copy(graphAnimationSpeed = it) }),
        b("showPhase", { it.showPhase }, { copy(showPhase = it) }),
        b("phaseUnwrapped", { it.phaseUnwrapped }, { copy(phaseUnwrapped = it) }),
        b("showFrequencyLabels", { it.showFrequencyLabels }, { copy(showFrequencyLabels = it) }),
        b("showDBLabels", { it.showDBLabels }, { copy(showDBLabels = it) }),
        b("showFrequencyReadout", { it.showFrequencyReadout }, { copy(showFrequencyReadout = it) }),
        b("showLevelReadout", { it.showLevelReadout }, { copy(showLevelReadout = it) }),
        b("showFrequencyGrid", { it.showFrequencyGrid }, { copy(showFrequencyGrid = it) }),
        b("showDBGrid", { it.showDBGrid }, { copy(showDBGrid = it) }),
        d("graphGridOpacity", { it.graphGridOpacity }, { copy(graphGridOpacity = it) }),
        d("graphDBRange", { it.graphDBRange }, { copy(graphDBRange = it) }),
        d("graphDBCenter", { it.graphDBCenter }, { copy(graphDBCenter = it) }),
        d("graphHeight", { it.graphHeight }, { copy(graphHeight = it) }),
        d("graphMinFreq", { it.graphMinFreq }, { copy(graphMinFreq = it) }),
        d("graphMaxFreq", { it.graphMaxFreq }, { copy(graphMaxFreq = it) }),
        i("dashboardCardsPerRow", { it.dashboardCardsPerRow }, { copy(dashboardCardsPerRow = it) }),
        b("popoutGraphFollowsSelection", { it.popoutGraphFollowsSelection }, { copy(popoutGraphFollowsSelection = it) }),
        b("showDebugInfo", { it.showDebugInfo }, { copy(showDebugInfo = it) }),
        b("rtaDashboardShowGraph", { it.rtaDashboardShowGraph }, { copy(rtaDashboardShowGraph = it) }),
        b("rtaDashboardShowBars", { it.rtaDashboardShowBars }, { copy(rtaDashboardShowBars = it) }),
        b("rtaChannelShowGraph", { it.rtaChannelShowGraph }, { copy(rtaChannelShowGraph = it) }),
        b("rtaChannelShowBars", { it.rtaChannelShowBars }, { copy(rtaChannelShowBars = it) }),
        s("rtaDashboardSource", { it.rtaDashboardSelectionKey }, { copy(rtaDashboardSelectionKey = it) }),
        s("rtaDashboardOtherSide", { it.rtaDashboardOtherSideKey }, { copy(rtaDashboardOtherSideKey = it) }),
        i("rtaBarColumns", { it.rtaBarColumns }, { copy(rtaBarColumns = it) }),
        d("rtaBarHeight", { it.rtaBarHeight }, { copy(rtaBarHeight = it) }),
        b("rtaChannelPagesShowSpectrum", { it.rtaChannelPagesShowSpectrum }, { copy(rtaChannelPagesShowSpectrum = it) }),
        d("rtaGraphOpacity", { it.rtaGraphOpacity }, { copy(rtaGraphOpacity = it) }),
        d("rtaFloorDB", { it.rtaFloorDB }, { copy(rtaFloorDB = it) }),
        d("rtaCeilingDB", { it.rtaCeilingDB }, { copy(rtaCeilingDB = it) }),
        b("rtaShowPeakHold", { it.rtaShowPeakHold }, { copy(rtaShowPeakHold = it) }),
        b("rtaSmoothingOn", { it.rtaSmoothingOn }, { copy(rtaSmoothingOn = it) }),
        i("rtaFftOrder", { it.rtaFftOrder }, { copy(rtaFftOrder = it) }),
        i("rtaAvgMs", { it.rtaAvgMs }, { copy(rtaAvgMs = it) }),
        i("rtaPeakDecayDBs", { it.rtaPeakDecayDBs }, { copy(rtaPeakDecayDBs = it) }),
        s("sidebarVolumeMode", { it.sidebarVolumeMode }, { copy(sidebarVolumeMode = it) }),
        s("settingsSelectedTab", { it.settingsSelectedTab }, { copy(settingsSelectedTab = it) })
    )

    /** The macOS key names, in declaration order (for tests). */
    val keyNames: List<String> get() = fields.map { it.key.name }

    fun read(p: Preferences): AppSettings = fields.fold(AppSettings()) { s, f -> f.load(p, s) }

    /** Write the fields that differ between [old] and [new] (all of them when [old] is null). */
    fun write(m: MutablePreferences, old: AppSettings?, new: AppSettings) = fields.forEach { it.store(m, old, new) }
}

/** The app's persistent [AppSettingsStore] (`AppSettingsStore.of(context)`). */
fun AppSettingsStore.Companion.of(context: Context): AppSettingsStore = DataStoreAppSettingsStore.of(context)
