package se.bofr.dspi.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import se.bofr.dspi.bridge.LinkStore

/** The app's one preferences file for bridge-owned persistent state (links). */
val Context.dspiBridgeStore: DataStore<Preferences> by preferencesDataStore(name = "dspi_bridge")

/**
 * Persistent [LinkStore] (decision D9) over a Preferences DataStore. Port of
 * the QSettings half of linux:qt/src/DSPiBridge.cpp `loadLinks`/`saveLinks`:
 * one int per device serial under `inputLinks/<serial>`, bit p = input pair p.
 *
 * The bridge calls [load]/[save] on the main thread and expects them cheap
 * (LinkStore KDoc), so everything is served from an in-memory copy:
 * - the copy is read once, pre-warmed on [Dispatchers.IO] at construction;
 *   a [load] that races the pre-warm blocks on that one small read;
 * - [save] updates the copy and wakes a single writer coroutine that writes
 *   the latest copy (conflated: a burst of link toggles is one write, and
 *   writes can never land out of order).
 * A missing or corrupt file reads as "no links" (Qt: absent key = 0).
 */
class DataStoreLinkStore(private val store: DataStore<Preferences>, scope: CoroutineScope) : LinkStore {
    private val lock = Any()
    private var cache: MutableMap<String, Int>? = null
    private val pending = MutableStateFlow<Map<String, Int>?>(null)

    init {
        scope.launch(Dispatchers.IO) { snapshot() }
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

    override fun load(serial: String): Int = snapshot()[serial] ?: 0

    override fun save(serial: String, links: Int) {
        val copy = synchronized(lock) {
            val c = snapshotLocked()
            if (c[serial] == links) return
            c[serial] = links
            HashMap(c)
        }
        pending.value = copy
    }

    private fun snapshot(): Map<String, Int> = synchronized(lock) { snapshotLocked() }

    private fun snapshotLocked(): MutableMap<String, Int> = cache ?: readAll().also { cache = it }

    private fun readAll(): MutableMap<String, Int> {
        val prefs = runBlocking { store.data.catch { emit(emptyPreferences()) }.first() }
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
