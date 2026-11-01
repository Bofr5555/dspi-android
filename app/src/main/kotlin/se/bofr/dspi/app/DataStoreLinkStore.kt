package se.bofr.dspi.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import se.bofr.dspi.bridge.LinkStore

/** The app's one preferences file for bridge-owned persistent state (links). */
val Context.dspiBridgeStore: DataStore<Preferences> by preferencesDataStore(name = "dspi_bridge")

/**
 * Persistent [LinkStore] (decision D9) over a Preferences DataStore. Port of
 * the QSettings half of linux:qt/src/DSPiBridge.cpp `loadLinks`/`saveLinks`:
 * one int per device serial under `inputLinks/<serial>`, bit p = input pair p.
 *
 * The bridge calls [load]/[save] on the main thread and expects them cheap
 * (LinkStore KDoc), so everything is served from an in-memory copy and the
 * main thread never touches the disk (review A3):
 * - the copy is read once on [Dispatchers.IO] at construction; the app
 *   starts the USB session only after [awaitLoaded] (DspiApplication), so no
 *   device can be selected before the read lands. Should [load] still run
 *   first, it answers 0 (Qt: absent key) instead of blocking;
 * - [save] updates the copy and wakes a single writer coroutine that writes
 *   the latest copy (conflated: a burst of link toggles is one write, and
 *   writes can never land out of order). Values saved before the read lands
 *   win over the file's.
 * A missing or corrupt file reads as "no links".
 */
class DataStoreLinkStore(private val store: DataStore<Preferences>, scope: CoroutineScope) : LinkStore {
    private val lock = Any()
    private val cache = HashMap<String, Int>()
    private val savedBeforeLoad = HashSet<String>()
    private var loaded = false
    private val loadedSignal = CompletableDeferred<Unit>()
    private val pending = MutableStateFlow<Map<String, Int>?>(null)

    init {
        scope.launch(Dispatchers.IO) {
            try {
                markLoaded(readAll())
            } finally {
                // cancelled or failed: never leave awaitLoaded() hanging (no-op once loaded)
                markLoaded(emptyMap())
            }
        }
        scope.launch(Dispatchers.IO) {
            pending.filterNotNull().conflate().collect { links ->
                runCatching {
                    store.edit { prefs ->
                        links.forEach { (serial, bits) -> prefs[key(serial)] = bits }
                    }
                }
            }
        }
    }

    /** Suspends until the file has been read (or failed to read). */
    suspend fun awaitLoaded() = loadedSignal.await()

    override fun load(serial: String): Int = synchronized(lock) { cache[serial] ?: 0 }

    override fun save(serial: String, links: Int) {
        val copy = synchronized(lock) {
            if (!loaded) savedBeforeLoad += serial
            if (cache[serial] == links && loaded) return
            cache[serial] = links
            HashMap(cache)
        }
        pending.value = copy
    }

    private fun markLoaded(disk: Map<String, Int>) {
        synchronized(lock) {
            if (loaded) return
            disk.forEach { (serial, bits) -> if (serial !in savedBeforeLoad) cache[serial] = bits }
            loaded = true
        }
        loadedSignal.complete(Unit)
    }

    private suspend fun readAll(): Map<String, Int> {
        val prefs = store.data.catch { emit(emptyPreferences()) }.first()
        val out = HashMap<String, Int>()
        prefs.asMap().forEach { (k, v) ->
            if (k.name.startsWith(PREFIX) && v is Int) out[k.name.removePrefix(PREFIX)] = v
        }
        return out
    }

    companion object {
        /** QSettings key of DSPiBridge.cpp: `inputLinks/<serial>`. */
        const val PREFIX = "inputLinks/"

        fun key(serial: String): Preferences.Key<Int> = intPreferencesKey(PREFIX + serial)
    }
}
