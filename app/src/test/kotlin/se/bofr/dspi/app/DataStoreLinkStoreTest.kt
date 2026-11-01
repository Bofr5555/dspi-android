package se.bofr.dspi.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreLinkStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    /** One "process": a DataStore over [file] (one instance per file, as in the app) and the LinkStore on it. */
    private class Process(file: File) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }
        val links = DataStoreLinkStore(store, scope)

        suspend fun onDisk(): Map<String, Int> = store.data.first().asMap().entries
            .filter { it.key.name.startsWith(DataStoreLinkStore.PREFIX) }
            .associate { it.key.name.removePrefix(DataStoreLinkStore.PREFIX) to it.value as Int }

        suspend fun kill() = scope.coroutineContext.job.cancelAndJoin()
    }

    @Test
    fun linksSurviveAProcessRestartPerSerial() = runBlocking {
        val file = File(tmp.root, "dspi_bridge.preferences_pb")
        val first = Process(file)
        withTimeout(5_000) { first.links.awaitLoaded() }
        assertEquals(0, first.links.load("A")) // unknown serial: no links (Qt default)
        first.links.save("A", 0b101)
        first.links.save("B", 0b1)
        first.links.save("A", 0b11) // last write wins, served from memory at once
        assertEquals(0b11, first.links.load("A"))
        withTimeout(5_000) {
            while (first.onDisk() != mapOf("A" to 0b11, "B" to 0b1)) delay(20)
        }
        first.kill()

        val second = Process(file)
        withTimeout(5_000) { second.links.awaitLoaded() }
        assertEquals(0b11, second.links.load("A"))
        assertEquals(0b1, second.links.load("B"))
        assertEquals(0, second.links.load("C"))
        second.kill()
    }

    @Test
    fun aCorruptFileReadsAsNoLinks() = runBlocking {
        val file = File(tmp.root, "bad.preferences_pb").apply { writeBytes(byteArrayOf(0x7f, 0x01, 0x02)) }
        val p = Process(file)
        withTimeout(5_000) { p.links.awaitLoaded() } // completes even though the read failed
        assertEquals(0, p.links.load("A"))
        p.kill()
    }

    /** A DataStore whose first read is held until [release] (the cold-start race of review A3). */
    private class GatedStore(initial: Preferences) : DataStore<Preferences> {
        val release = CompletableDeferred<Unit>()
        private val state = MutableStateFlow(initial)
        override val data: Flow<Preferences> = flow {
            release.await()
            emitAll(state)
        }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            release.await()
            return transform(state.value).also { state.value = it }
        }
    }

    @Test
    fun loadNeverBlocksBeforeTheReadAndEarlySavesWin() = runBlocking {
        val disk = mutablePreferencesOf(DataStoreLinkStore.key("A") to 0b1, DataStoreLinkStore.key("B") to 0b10)
        val store = GatedStore(disk)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val links = DataStoreLinkStore(store, scope)
        // read not landed: answers at once (no runBlocking on the caller), Qt's absent-key value
        assertEquals(0, links.load("A"))
        links.save("B", 0b11) // a save before the read lands wins over the file
        assertNull(withTimeoutOrNull(100) { links.awaitLoaded() })
        store.release.complete(Unit)
        withTimeout(5_000) { links.awaitLoaded() }
        assertEquals(0b1, links.load("A"))
        assertEquals(0b11, links.load("B"))
        withTimeout(5_000) {
            while (store.data.first()[DataStoreLinkStore.key("B")] != 0b11) delay(20)
        }
        scope.coroutineContext.job.cancelAndJoin()
    }
}
