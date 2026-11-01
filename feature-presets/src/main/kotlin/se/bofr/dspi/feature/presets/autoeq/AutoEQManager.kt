// Port of DSPi-Console (macOS) `DSPi Console/AutoEQ/AutoEQManager.swift` @ v1.1.6-beta5
// (data models, database loading with a user copy over the bundled one,
// search, applyProfile, favourites, updateDatabaseFromFile,
// resetToBuiltInDatabase) and Linux `qt/src/AutoEqLibrary.cpp` @ 4683a2c
// (apply through the bridge: setInputPreamp on inputs 0/1, setFilter on app
// channels 0/1, the remaining bands Off at 1000 Hz / 0 dB / Q 0.707).
// "Rebuild from GitHub" is not ported (PRESETS deviation 2).
package se.bofr.dspi.feature.presets.autoeq

import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.ui.json.Json
import se.bofr.dspi.ui.json.double
import se.bofr.dspi.ui.json.int
import se.bofr.dspi.ui.json.list
import se.bofr.dspi.ui.json.str

/** `EmbeddedFilter`: type is "peaking", "lowShelf", "highShelf", "lowPass", "highPass". */
data class EmbeddedFilter(val type: String, val freq: Double, val q: Double, val gain: Double)

/** `HeadphoneEntry`. */
data class HeadphoneEntry(
    val id: String,
    val manufacturer: String,
    val model: String,
    val source: String,
    val formFactor: String,
    val preamp: Double,
    val filters: List<EmbeddedFilter>
) {
    val displayName: String get() = if (model.isEmpty()) manufacturer else "$manufacturer $model"

    val sourceDisplayName: String
        get() =
            when (source) {
                "oratory1990" -> "oratory1990"
                "crinacle" -> "Crinacle"
                "rtings" -> "Rtings"
                "innerfidelity" -> "InnerFidelity"
                "headphone.com" -> "Headphone.com"
                else -> source
            }
}

/** `AutoEQDatabase`. */
data class AutoEQDatabase(val version: Int, val generatedAt: String, val entryCount: Int, val entries: List<HeadphoneEntry>) {
    companion object {
        /** Decodes `autoeq_database.json`; throws [IllegalArgumentException] on anything else. */
        fun decode(text: String): AutoEQDatabase {
            val root = Json.parseObject(text) ?: throw IllegalArgumentException("Invalid database content")
            val list = root.list("entries") ?: throw IllegalArgumentException("Invalid database content")
            val entries =
                list.map { e ->
                    @Suppress("UNCHECKED_CAST")
                    val m = e as? Map<String, Any?> ?: throw IllegalArgumentException("Invalid database content")
                    HeadphoneEntry(
                        id = m.str("id") ?: throw IllegalArgumentException("Invalid database content"),
                        manufacturer = m.str("manufacturer").orEmpty(),
                        model = m.str("model").orEmpty(),
                        source = m.str("source").orEmpty(),
                        formFactor = m.str("formFactor").orEmpty(),
                        preamp = m.double("preamp") ?: 0.0,
                        filters =
                        m.list("filters").orEmpty().map { f ->
                            @Suppress("UNCHECKED_CAST")
                            val fm = f as? Map<String, Any?> ?: throw IllegalArgumentException("Invalid database content")
                            EmbeddedFilter(
                                fm.str("type").orEmpty(),
                                fm.double("freq") ?: 1000.0,
                                fm.double("q") ?: 0.707,
                                fm.double("gain") ?: 0.0
                            )
                        }
                    )
                }
            return AutoEQDatabase(
                root.int("version") ?: 1,
                root.str("generatedAt").orEmpty(),
                root.int("entryCount") ?: entries.size,
                entries
            )
        }

        /** `formatDatabaseDate`: medium date style, or the raw string when unparsable. */
        fun formatDatabaseDate(isoDate: String, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String {
            val instant =
                runCatching { Instant.parse(isoDate) }.getOrNull()
                    ?: runCatching { OffsetDateTime.parse(isoDate).toInstant() }.getOrNull()
                    ?: return isoDate
            return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).withZone(zone).format(instant)
        }
    }
}

/** One band write of an applied profile: `setFilter(ch, band, type, freq, gain, q)`. */
data class AutoEQBand(val band: Int, val type: Int, val freq: Float, val gain: Float, val q: Float)

/** What applying a profile writes: the preamp of inputs 1–2 and all ten PEQ bands of app channels 0–1. */
data class AutoEQApplyPlan(val preamp: Float, val inputs: List<Int>, val channels: List<Int>, val bands: List<AutoEQBand>)

/**
 * A favourite as the AutoEQ menu lists it: id plus "manufacturer model", so
 * the menu needs no database parse (Linux AutoEqLibrary keeps `id\tname` too).
 */
data class FavoriteProfile(val id: String, val name: String)

/** Favourites, in the order they were added (`UserDefaults "AutoEQ.FavoriteProfiles"`). */
interface FavoritesStore {
    fun load(): List<FavoriteProfile>

    fun save(favorites: List<FavoriteProfile>)
}

class InMemoryFavoritesStore(initial: List<FavoriteProfile> = emptyList()) : FavoritesStore {
    private var favorites = initial

    override fun load() = favorites

    override fun save(favorites: List<FavoriteProfile>) {
        this.favorites = favorites
    }
}

/**
 * `AutoEQManager`. [bundledDatabase] reads the bundled asset; [userDatabaseFile]
 * is the updated copy (Application Support on macOS, the app's files dir here).
 * Loading is blocking and lazy (the 5.4 MB database is parsed only when the
 * browser, a favourite or Update Database needs it): call [ensureLoaded] off
 * the main thread. Favourites are available without loading.
 */
class AutoEQManager(
    private val bundledDatabase: () -> String,
    private val userDatabaseFile: File?,
    private val favoritesStore: FavoritesStore
) {
    private val _entries = MutableStateFlow<List<HeadphoneEntry>>(emptyList())
    val entries: StateFlow<List<HeadphoneEntry>> = _entries.asStateFlow()

    private val _favoriteProfiles = MutableStateFlow(favoritesStore.load())
    val favoriteProfiles: StateFlow<List<FavoriteProfile>> = _favoriteProfiles.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _databaseDate = MutableStateFlow<String?>(null)
    val databaseDate: StateFlow<String?> = _databaseDate.asStateFlow()

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    val hasUserDatabase: Boolean get() = userDatabaseFile?.exists() == true

    /** Tries the user-updated database first, then the bundled one; then the favourites. */
    @Synchronized
    fun loadDatabase() {
        _errorMessage.value = null
        try {
            val text = if (hasUserDatabase) userDatabaseFile!!.readText() else bundledDatabase()
            val db = AutoEQDatabase.decode(text)
            _entries.value = db.entries
            _databaseDate.value = AutoEQDatabase.formatDatabaseDate(db.generatedAt)
        } catch (e: Exception) {
            _errorMessage.value = "Failed to load database: ${e.message ?: e.javaClass.simpleName}"
        }
        loadFavorites()
        _isLoaded.value = true
    }

    /** Loads once (the browser and the menu share one manager). */
    fun ensureLoaded() {
        if (!_isLoaded.value) loadDatabase()
    }

    // ═══ Search ═══════════════════════════════════════════════════════════

    fun search(query: String): List<HeadphoneEntry> = search(_entries.value, query)

    /** The loaded entry with [id], null when absent (or not loaded yet). */
    fun entry(id: String): HeadphoneEntry? = _entries.value.firstOrNull { it.id == id }

    // ═══ Apply Profile ════════════════════════════════════════════════════

    /** Applies [entry] to inputs 1–2. Returns false when no device is connected (Linux `apply`). */
    fun applyProfile(entry: HeadphoneEntry, bridge: DspiBridge): Boolean {
        if (!bridge.connected) return false
        val plan = applyPlan(entry)
        // macOS order: per channel, its preamp then its bands. Per-input, not the
        // legacy all-input preamp: only the two inputs the profile EQs.
        for ((input, ch) in plan.inputs.zip(plan.channels)) {
            bridge.setInputPreamp(input, plan.preamp)
            for (b in plan.bands) bridge.setFilter(ch, b.band, b.type, b.freq, b.gain, b.q)
        }
        return true
    }

    // ═══ Favorite Profiles ════════════════════════════════════════════════

    fun isFavorite(entry: HeadphoneEntry) = _favoriteProfiles.value.any { it.id == entry.id }

    fun toggleFavorite(entry: HeadphoneEntry) {
        _favoriteProfiles.value =
            if (isFavorite(entry)) {
                _favoriteProfiles.value.filterNot { it.id == entry.id }
            } else {
                // The AutoEQ menu's title: "\(manufacturer) \(model)"
                _favoriteProfiles.value + FavoriteProfile(entry.id, "${entry.manufacturer} ${entry.model}")
            }
        favoritesStore.save(_favoriteProfiles.value)
    }

    fun clearFavorites() {
        _favoriteProfiles.value = emptyList()
        favoritesStore.save(emptyList())
    }

    /** `loadFavorites`: once a database is loaded, favourites it doesn't contain are dropped (macOS compactMap). */
    private fun loadFavorites() {
        if (_entries.value.isEmpty()) return
        val ids = _entries.value.mapTo(HashSet()) { it.id }
        _favoriteProfiles.value = favoritesStore.load().filter { it.id in ids }
    }

    // ═══ Database Update ══════════════════════════════════════════════════

    /** `updateDatabaseFromFile`: validate, store as the user copy, reload. Throws on invalid content. */
    @Synchronized
    fun updateDatabaseFromText(text: String) {
        val db = AutoEQDatabase.decode(text)
        val file = userDatabaseFile ?: throw IllegalStateException("No storage for a user database")
        file.parentFile?.mkdirs()
        file.writeText(text)
        _entries.value = db.entries
        _databaseDate.value = AutoEQDatabase.formatDatabaseDate(db.generatedAt)
        _errorMessage.value = null
        loadFavorites()
    }

    /** `resetToBuiltInDatabase`. */
    @Synchronized
    fun resetToBuiltInDatabase() {
        userDatabaseFile?.takeIf { it.exists() }?.delete()
        loadDatabase()
    }

    companion object {
        /** `Channel.masterLeft.bandCount`. */
        const val MAX_BANDS = 10

        /** `FilterType.from(string:)` to the firmware filter type. */
        fun filterType(type: String): Int = when (type) {
            "peaking" -> DspiConstants.FILTER_PEAKING
            "lowShelf" -> DspiConstants.FILTER_LOWSHELF
            "highShelf" -> DspiConstants.FILTER_HIGHSHELF
            "lowPass" -> DspiConstants.FILTER_LOWPASS
            "highPass" -> DspiConstants.FILTER_HIGHPASS
            else -> DspiConstants.FILTER_FLAT
        }

        /** `search(query:)`: manufacturer, model or display name contains the query (case-insensitive). */
        fun search(entries: List<HeadphoneEntry>, query: String): List<HeadphoneEntry> {
            if (query.isEmpty()) return entries
            val q = query.lowercase()
            return entries.filter {
                it.manufacturer.lowercase().contains(q) || it.model.lowercase().contains(q) || it.displayName.lowercase().contains(q)
            }
        }

        /**
         * `applyProfile`: the profile's preamp on inputs 0 and 1, its first ten
         * filters into bands 0.., the remaining bands Off (1000 Hz, 0 dB, Q 0.707),
         * on app channels 0 and 1 (Master L/R = IN1/IN2).
         */
        fun applyPlan(entry: HeadphoneEntry): AutoEQApplyPlan {
            val bands =
                (0 until MAX_BANDS).map { i ->
                    val f = entry.filters.getOrNull(i)
                    if (f != null) {
                        AutoEQBand(i, filterType(f.type), f.freq.toFloat(), f.gain.toFloat(), f.q.toFloat())
                    } else {
                        AutoEQBand(i, DspiConstants.FILTER_FLAT, 1000f, 0f, 0.707f)
                    }
                }
            return AutoEQApplyPlan(entry.preamp.toFloat(), inputs = listOf(0, 1), channels = listOf(0, 1), bands = bands)
        }
    }
}
