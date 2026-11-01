// Android storage for AutoEQManager: the bundled database asset
// (`assets/autoeq_database.json`, the macOS bundle's copy; provenance in
// docs/SUPPLY-CHAIN.md), the user copy in the app's files dir (macOS:
// Application Support), favourites in SharedPreferences (macOS: UserDefaults
// "AutoEQ.FavoriteProfiles").
package se.bofr.dspi.feature.presets.autoeq

import android.content.Context
import java.io.File

internal class SharedPreferencesFavoritesStore(context: Context) : FavoritesStore {
    private val prefs = context.getSharedPreferences("autoeq", Context.MODE_PRIVATE)

    override fun load(): List<String> = prefs.getString(KEY, null)?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()

    override fun save(ids: List<String>) {
        prefs.edit().apply { if (ids.isEmpty()) remove(KEY) else putString(KEY, ids.joinToString("\n")) }.apply()
    }

    private companion object {
        const val KEY = "AutoEQ.FavoriteProfiles"
    }
}

/** `AutoEQManager.shared`: one manager per process. */
internal object AutoEQManagers {
    const val ASSET = "autoeq_database.json"

    @Volatile private var shared: AutoEQManager? = null

    fun shared(context: Context): AutoEQManager = shared ?: synchronized(this) {
        shared ?: run {
            val app = context.applicationContext
            AutoEQManager(
                bundledDatabase = { app.assets.open(ASSET).use { it.readBytes().toString(Charsets.UTF_8) } },
                userDatabaseFile = File(File(app.filesDir, "autoeq"), ASSET),
                favoritesStore = SharedPreferencesFavoritesStore(app)
            ).also { shared = it }
        }
    }
}
