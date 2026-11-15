package app.folio.desktop

import app.folio.core.model.Cover
import app.folio.core.storage.FileNotebookStore
import app.folio.core.storage.StoreSync
import app.folio.core.sync.MemoryRemote
import app.folio.core.sync.SyncEngine
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Mac wiring of the shared sync engine, against an in-memory remote (no Google needed). */
class MacSyncEngineTest {
    @Test fun aNotebookMadeOnOneDeviceArrivesOnAnEmptyMac() {
        val tablet = FileNotebookStore(Files.createTempDirectory("folio-tablet").toFile())
        val mac = FileNotebookStore(Files.createTempDirectory("folio-mac").toFile())
        val nb = tablet.library().let { tablet.createNotebook("History notes", null, Cover(), app.folio.core.model.PaperTemplate.RULED) }
        val remote = MemoryRemote()
        val t = StoreSync(tablet); val m = StoreSync(mac)
        assertTrue(SyncEngine(t, remote, t).sync().uploaded > 0)
        val r = SyncEngine(m, remote, m).sync()
        assertTrue(r.downloaded > 0)
        val arrived = mac.library().notebooks.single()
        assertEquals("History notes", arrived.title)
        assertEquals(nb.pageIds, arrived.pageIds)
        assertEquals(nb.pageIds.size, arrived.pageIds.count { runCatching { mac.loadPage(arrived.id, it) }.isSuccess })
        // A second run finds nothing to do.
        assertEquals(0, SyncEngine(m, remote, m).sync().changes)
    }
}
