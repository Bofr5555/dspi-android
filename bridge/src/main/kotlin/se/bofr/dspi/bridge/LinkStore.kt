package se.bofr.dspi.bridge

/**
 * Where the input-pair links live, per device serial (DSPiBridge.cpp
 * loadLinks/saveLinks: QSettings `inputLinks/<serial>`). The app supplies a
 * persistent one (DataStore/SharedPreferences); bit p = input pair p linked.
 * Called on the main thread; keep it cheap (cache, write asynchronously).
 */
interface LinkStore {
    /** The links saved for [serial], 0 (none) if unknown. */
    fun load(serial: String): Int

    fun save(serial: String, links: Int)
}

/** A [LinkStore] that forgets on process death (demo mode, tests). */
class InMemoryLinkStore : LinkStore {
    private val links = HashMap<String, Int>()

    override fun load(serial: String): Int = synchronized(links) { links[serial] ?: 0 }

    override fun save(serial: String, links: Int) {
        synchronized(this.links) { this.links[serial] = links }
    }
}
