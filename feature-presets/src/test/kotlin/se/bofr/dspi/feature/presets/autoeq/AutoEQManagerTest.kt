package se.bofr.dspi.feature.presets.autoeq

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.feature.presets.attached
import se.bofr.dspi.feature.presets.detached
import se.bofr.dspi.feature.presets.newBridge
import se.bofr.dspi.feature.presets.presetFake
import se.bofr.dspi.ui.alert.AlertHost
import se.bofr.dspi.ui.alert.AlertResponse

private const val DB = """{"version":1,"generatedAt":"2026-01-18T05:41:39.649194+00:00","entryCount":3,"entries":[
{"id":"oratory1990/Sennheiser HD 650","manufacturer":"Sennheiser","model":"HD 650","source":"oratory1990","formFactor":"over-ear","preamp":-6.4,
 "filters":[{"type":"lowShelf","freq":105.0,"q":0.7,"gain":6.5},{"type":"peaking","freq":2000.0,"q":1.2,"gain":-2.0},
            {"type":"highShelf","freq":10000.0,"q":0.7,"gain":-1.1},{"type":"lowPass","freq":18000.0,"q":0.71,"gain":0.0},
            {"type":"highPass","freq":20.0,"q":0.71,"gain":0.0},{"type":"bandPass","freq":500.0,"q":1.0,"gain":1.0}]},
{"id":"crinacle/Moondrop Blessing 2","manufacturer":"Moondrop","model":"Blessing 2","source":"crinacle","formFactor":"in-ear","preamp":-3.1,
 "filters":[{"type":"peaking","freq":4500,"q":2,"gain":-3}]},
{"id":"rtings/Koss","manufacturer":"Koss","model":"","source":"headphone.com","formFactor":"over-ear","preamp":0,"filters":[]}]}"""

class AutoEQManagerTest {
    private fun manager(store: FavoritesStore = InMemoryFavoritesStore(), user: File? = null) =
        AutoEQManager({ DB }, user, store).also { it.loadDatabase() }

    @Test
    fun decodesTheDatabase() {
        val db = AutoEQDatabase.decode(DB)
        assertEquals(3, db.entryCount)
        assertEquals(3, db.entries.size)
        val hd650 = db.entries[0]
        assertEquals("Sennheiser HD 650", hd650.displayName)
        assertEquals("oratory1990", hd650.sourceDisplayName)
        assertEquals(EmbeddedFilter("lowShelf", 105.0, 0.7, 6.5), hd650.filters[0])
        assertEquals(4500.0, db.entries[1].filters[0].freq, 0.0)
        assertEquals("Koss", db.entries[2].displayName)
        assertEquals("Headphone.com", db.entries[2].sourceDisplayName)
        assertEquals("Crinacle", db.entries[1].sourceDisplayName)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSomethingElse() {
        AutoEQDatabase.decode("""{"version":1}""")
    }

    @Test
    fun databaseDate() {
        assertEquals(
            "Jan 18, 2026",
            AutoEQDatabase.formatDatabaseDate("2026-01-18T05:41:39.649194+00:00", Locale.US, java.time.ZoneId.of("UTC"))
        )
        assertEquals("garbage", AutoEQDatabase.formatDatabaseDate("garbage"))
    }

    @Test
    fun theBundledDatabaseIsTheOfficialOne() {
        // src/main/assets/autoeq_database.json: DSPi-Console v1.1.6-beta5 (docs/SUPPLY-CHAIN.md)
        val file = File("src/main/assets/autoeq_database.json")
        val bytes = Files.readAllBytes(file.toPath())
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("816593868b796b9af0ea567df9c49330b481834b1e2ca7808c808e131caef37f", sha)
        val db = AutoEQDatabase.decode(String(bytes, Charsets.UTF_8))
        assertEquals(3828, db.entryCount)
        assertEquals(db.entryCount, db.entries.size)
        assertTrue(db.entries.all { it.filters.isNotEmpty() })
    }

    @Test
    fun searchMatchesManufacturerModelAndDisplayName() {
        val m = manager()
        assertEquals(3, m.search("").size)
        assertEquals(listOf("Sennheiser HD 650"), m.search("hd 6").map { it.displayName })
        assertEquals(listOf("Sennheiser HD 650"), m.search("SENNHEISER HD").map { it.displayName })
        assertEquals(listOf("Moondrop Blessing 2"), m.search("blessing").map { it.displayName })
        assertTrue(m.search("nothing like this").isEmpty())
    }

    @Test
    fun applyPlanMapsTypesAndClearsTheRest() {
        val entry = AutoEQDatabase.decode(DB).entries[0]
        val plan = AutoEQManager.applyPlan(entry)
        assertEquals(-6.4f, plan.preamp, 1e-6f)
        assertEquals(listOf(0, 1), plan.inputs)
        assertEquals(listOf(0, 1), plan.channels)
        assertEquals(10, plan.bands.size)
        assertEquals(
            listOf(
                DspiConstants.FILTER_LOWSHELF,
                DspiConstants.FILTER_PEAKING,
                DspiConstants.FILTER_HIGHSHELF,
                DspiConstants.FILTER_LOWPASS,
                DspiConstants.FILTER_HIGHPASS,
                DspiConstants.FILTER_FLAT
            ),
            plan.bands.take(6).map { it.type }
        )
        assertEquals(AutoEQBand(0, DspiConstants.FILTER_LOWSHELF, 105f, 6.5f, 0.7f), plan.bands[0])
        for (b in plan.bands.drop(6)) assertEquals(AutoEQBand(b.band, DspiConstants.FILTER_FLAT, 1000f, 0f, 0.707f), b)
    }

    @Test
    fun applyPlanTakesTheFirstTenFilters() {
        val many = EmbeddedFilter("peaking", 100.0, 1.0, 1.0)
        val entry = HeadphoneEntry("x", "X", "Y", "crinacle", "in-ear", -1.0, List(12) { many.copy(freq = 100.0 * (it + 1)) })
        val plan = AutoEQManager.applyPlan(entry)
        assertEquals(10, plan.bands.size)
        assertEquals(1000f, plan.bands[9].freq, 0f)
        assertTrue(plan.bands.all { it.type == DspiConstants.FILTER_PEAKING })
    }

    @Test
    fun favoritesPersistInOrderWithTheirMenuTitles() {
        val store = InMemoryFavoritesStore()
        val m = manager(store)
        val (hd650, blessing) = m.entries.value
        m.toggleFavorite(blessing)
        m.toggleFavorite(hd650)
        assertEquals(
            listOf(
                FavoriteProfile("crinacle/Moondrop Blessing 2", "Moondrop Blessing 2"),
                FavoriteProfile("oratory1990/Sennheiser HD 650", "Sennheiser HD 650")
            ),
            store.load()
        )
        assertTrue(m.isFavorite(hd650))
        m.toggleFavorite(blessing)
        assertEquals(listOf(hd650.id), m.favoriteProfiles.value.map { it.id })
        // A new manager reads them back; once the database is loaded unknown ids are dropped
        store.save(listOf(FavoriteProfile("gone/Old", "Old"), FavoriteProfile(hd650.id, "Sennheiser HD 650")))
        assertEquals(listOf(hd650.id), manager(store).favoriteProfiles.value.map { it.id })
        m.clearFavorites()
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun favoritesNeedNoDatabaseParse() {
        var parses = 0
        val store = InMemoryFavoritesStore(listOf(FavoriteProfile("crinacle/Moondrop Blessing 2", "Moondrop Blessing 2")))
        val m = AutoEQManager({
            parses++
            DB
        }, null, store)
        assertEquals(listOf("Moondrop Blessing 2"), m.favoriteProfiles.value.map { it.name })
        assertEquals(0, parses)
        assertFalse(m.isLoaded.value)
        m.ensureLoaded()
        m.ensureLoaded()
        assertEquals(1, parses)
    }

    @Test
    fun userDatabaseOverridesTheBundledOneUntilReset() {
        val dir = Files.createTempDirectory("autoeq").toFile()
        val user = File(dir, "autoeq/autoeq_database.json")
        val m = manager(user = user)
        assertFalse(m.hasUserDatabase)
        val one = """{"version":1,"generatedAt":"x","entryCount":1,"entries":[{"id":"a/B","manufacturer":"A","model":"B","source":"a",""" +
            """"formFactor":"in-ear","preamp":0,"filters":[]}]}"""
        m.updateDatabaseFromText(one)
        assertTrue(m.hasUserDatabase)
        assertEquals(1, m.entries.value.size)
        assertEquals(1, AutoEQManager({ DB }, user, InMemoryFavoritesStore()).also { it.loadDatabase() }.entries.value.size)
        m.resetToBuiltInDatabase()
        assertFalse(m.hasUserDatabase)
        assertEquals(3, m.entries.value.size)
        dir.deleteRecursively()
    }

    @Test
    fun aBrokenDatabaseReportsAnError() {
        val m = AutoEQManager({ "nope" }, null, InMemoryFavoritesStore()).also { it.loadDatabase() }
        assertTrue(m.errorMessage.value!!.startsWith("Failed to load database"))
        assertTrue(m.entries.value.isEmpty())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AutoEQApplyTest {
    @Test
    fun applyFollowsTheMacOSOrder() = runTest {
        val core = presetFake()
        val b = attached(core)
        val m = AutoEQManager({ DB }, null, InMemoryFavoritesStore()).also { it.loadDatabase() }
        synchronized(core.calls) { core.calls.clear() }
        assertTrue(m.applyProfile(m.entries.value[0], b))
        runCurrent()
        val calls = synchronized(core.calls) { core.calls.filter { it.startsWith("setInputPreamp(") || it.startsWith("setFilter(") } }
        // Preamp L + bands L, then preamp R + bands R (AutoEQManager.swift applyProfile)
        assertTrue(calls.first().startsWith("setInputPreamp(0"))
        val secondPreamp = calls.indexOfFirst { it.startsWith("setInputPreamp(1") }
        assertEquals(11, secondPreamp)
        assertTrue(calls.subList(1, 11).all { it.startsWith("setFilter(") })
        assertEquals(22, calls.size)
    }

    @Test
    fun aFavoriteLoadsTheDatabaseThenApplies() = runTest {
        val core = presetFake()
        val b = attached(core)
        val host = AlertHost()
        val fav = FavoriteProfile("crinacle/Moondrop Blessing 2", "Moondrop Blessing 2")
        val m = AutoEQManager({ DB }, null, InMemoryFavoritesStore(listOf(fav)))
        AutoEQMenuActions(m, b, this, host, kotlinx.coroutines.Dispatchers.Unconfined).applyFavorite(fav)
        runCurrent()
        assertTrue(m.isLoaded.value)
        assertNull(host.alert.value)
        assertEquals(-3.1f, b.inputPreampDB(0), 0.05f)
    }

    @Test
    fun applyWritesInputsOneAndTwo() = runTest {
        val core = presetFake()
        val b = attached(core)
        val host = AlertHost()
        val m = AutoEQManager({ DB }, null, InMemoryFavoritesStore()).also { it.loadDatabase() }
        val actions = AutoEQMenuActions(m, b, this, host, kotlinx.coroutines.Dispatchers.Unconfined)
        assertTrue(actions.applyProfile(m.entries.value[1]))
        runCurrent()
        assertNull(host.alert.value)
        assertEquals(-3.1f, b.inputPreampDB(0), 0.05f)
        assertEquals(-3.1f, b.inputPreampDB(1), 0.05f)
        for (ch in 0..1) {
            assertEquals(DspiConstants.FILTER_PEAKING, b.filterType(ch, 0))
            assertEquals(4500f, b.filterFreq(ch, 0), 0.5f)
            assertEquals(-3f, b.filterGain(ch, 0), 0.05f)
            for (band in 1 until 10) assertEquals(DspiConstants.FILTER_FLAT, b.filterType(ch, band))
        }
        assertTrue(b.presetDirty.value)
    }

    @Test
    fun applyNeedsADevice() = runTest {
        val b = detached()
        val host = AlertHost()
        val m = AutoEQManager({ DB }, null, InMemoryFavoritesStore()).also { it.loadDatabase() }
        assertFalse(AutoEQMenuActions(m, b, this, host, kotlinx.coroutines.Dispatchers.Unconfined).applyProfile(m.entries.value[0]))
        assertEquals("No device connected.", host.alert.value!!.informativeText)
    }

    @Test
    fun updateDatabaseOffersResetOnlyWithAUserCopy() = runTest {
        val b = newBridge(presetFake())
        val host = AlertHost()
        val m = AutoEQManager({ DB }, null, InMemoryFavoritesStore()).also { it.loadDatabase() }
        var picked = false
        AutoEQMenuActions(m, b, this, host, kotlinx.coroutines.Dispatchers.Unconfined).updateDatabase { picked = true }
        runCurrent()
        val a = host.alert.value!!
        assertEquals("Update AutoEQ Database", a.messageText)
        assertTrue(a.informativeText.contains("Entries: 3"))
        assertEquals(listOf("Import File...", "Cancel"), a.buttons.map { it.title })
        a.button("Import File...").action(AlertResponse())
        assertTrue(picked)
    }
}
