package se.bofr.dspi.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
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
        assertEquals(0b11, second.links.load("A"))
        assertEquals(0b1, second.links.load("B"))
        assertEquals(0, second.links.load("C"))
        second.kill()
    }

    @Test
    fun aCorruptFileReadsAsNoLinks() = runBlocking {
        val file = File(tmp.root, "bad.preferences_pb").apply { writeBytes(byteArrayOf(0x7f, 0x01, 0x02)) }
        val p = Process(file)
        assertEquals(0, p.links.load("A"))
        p.kill()
    }
}
