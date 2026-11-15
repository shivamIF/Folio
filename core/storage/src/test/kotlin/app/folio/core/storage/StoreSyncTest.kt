package app.folio.core.storage

import app.folio.core.model.Cover
import app.folio.core.model.PaperTemplate
import app.folio.core.model.Stroke
import app.folio.core.model.ToolType
import app.folio.core.sync.MemoryRemote
import app.folio.core.sync.SyncEngine
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreSyncTest {
    private val dirs = ArrayList<File>()
    private fun tempDir() = Files.createTempDirectory("folio-sync").toFile().also { dirs += it }
    @AfterTest fun cleanup() { dirs.forEach { it.deleteRecursively() } }

    private val drive = MemoryRemote()
    private var t = 1_000L
    private val a = FileNotebookStore(tempDir()) { ++t }
    private val b = FileNotebookStore(tempDir()) { ++t }
    private var busyB: String? = null
    private fun syncA() = StoreSync(a).let { SyncEngine(it, drive, it).sync() }
    private fun syncB() = StoreSync(b, { busyB }).let { SyncEngine(it, drive, it).sync() }

    private fun stroke(id: String, x: Float = 1f) = Stroke(
        id = id, tool = ToolType.FOUNTAIN, color = 0xFF112233, width = 3f,
        x = floatArrayOf(x, x + 1), y = floatArrayOf(1f, 2f), pressure = floatArrayOf(.5f, .5f),
        tilt = FloatArray(2), orientation = FloatArray(2), t = intArrayOf(0, 5),
    )

    private fun write(store: FileNotebookStore, nb: String, pageId: String, vararg strokeIds: String) {
        val p = store.loadPage(nb, pageId)
        store.savePage(nb, p.copy(strokes = strokeIds.map { stroke(it) }))
    }

    private fun strokeIds(store: FileNotebookStore, nb: String, pageId: String) = store.loadPage(nb, pageId).strokes.map { it.id }

    @Test fun theOpenNotebookTakesPagesThroughTheEditor() {
        val nb = a.createNotebook("Live", null, Cover(), PaperTemplate.RULED)
        val page = nb.pageIds[0]
        write(a, nb.id, page, "s1")
        syncA(); syncB()
        write(a, nb.id, page, "s1", "s2")
        syncA()
        val arrived = ArrayList<List<String>>()
        // The editor had drawn "mine" (unsaved) and merges it into what arrives.
        fun syncLive() = StoreSync(b, { nb.id }) { _, p, store ->
            arrived += p.strokes.map { it.id }
            store(p.copy(strokes = p.strokes + stroke("mine")))
        }.let { SyncEngine(it, drive, it).sync() }
        syncLive()
        assertEquals(listOf("s1", "s2"), arrived.first(), "the editor was handed the new page")
        assertEquals(listOf("s1", "s2", "mine"), strokeIds(b, nb.id, page), "the merged page is stored at once")
        syncA()
        assertEquals(listOf("s1", "s2", "mine"), strokeIds(a, nb.id, page), "and goes back out, never the stale file")
    }

    @Test fun aNotebookWithPagesAndAPdfArrivesIntact() {
        val st = a.stagePdf(("%PDF-1.4\n" + "x".repeat(3000)).toByteArray().inputStream())
        val meta = a.createNotebookFromPdf("Lecture", null, Cover(), st, listOf(app.folio.core.model.Page(pdf = app.folio.core.model.PdfBackground(st.id, 0))))
        write(a, meta.id, meta.pageIds[0], "s1", "s2")
        syncA(); syncB()
        val got = b.library().notebooks.single()
        assertEquals("Lecture", got.title)
        assertEquals(listOf("s1", "s2"), strokeIds(b, got.id, got.pageIds[0]))
        assertTrue(b.attachmentFile(got.id, st.id).readBytes().contentEquals(a.attachmentFile(meta.id, st.id).readBytes()))
        assertEquals(0, syncA().changes); assertEquals(0, syncB().changes) // settled
    }

    @Test fun editsAndNewPagesFromBothDevicesMerge() {
        val meta = a.createNotebook("Shared", null, Cover(), PaperTemplate.BLANK)
        syncA(); syncB()
        write(a, meta.id, meta.pageIds[0], "fromA")
        val withPage = b.addPage(meta.id, 0)
        write(b, meta.id, withPage.pageIds[1], "fromB")
        syncA(); syncB(); syncA()
        for (s in listOf(a, b)) {
            val m = s.library().notebooks.single()
            assertEquals(2, m.pageIds.size, "B's new page survives the merge")
            assertEquals(listOf("fromA"), strokeIds(s, m.id, m.pageIds[0]))
            assertEquals(listOf("fromB"), strokeIds(s, m.id, m.pageIds[1]))
        }
    }

    @Test fun samePageEditedOnBothKeepsBothAsPages() {
        val meta = a.createNotebook("Clash", null, Cover(), PaperTemplate.BLANK)
        syncA(); syncB()
        val pid = meta.pageIds[0]
        write(a, meta.id, pid, "A1")
        write(b, meta.id, pid, "B1")
        syncA(); syncB(); syncA(); syncB()
        for (s in listOf(a, b)) {
            val m = s.library().notebooks.single()
            assertEquals(2, m.pageIds.size, "a conflict copy is added")
            assertEquals(pid, m.pageIds[0])
            assertEquals(setOf(listOf("A1"), listOf("B1")), m.pageIds.map { strokeIds(s, m.id, it) }.toSet())
        }
        assertEquals(a.library().notebooks.single().pageIds, b.library().notebooks.single().pageIds)
    }

    @Test fun trashAndPermanentDeleteSpread() {
        val keep = a.createNotebook("Keep", null, Cover(), PaperTemplate.BLANK)
        val gone = a.createNotebook("Gone", null, Cover(), PaperTemplate.BLANK)
        a.createFolder("Physics", null, 0xFF98C1D9)
        syncA(); syncB()
        assertEquals(setOf("Keep", "Gone"), b.library().notebooks.map { it.title }.toSet())
        assertEquals(listOf("Physics"), b.library().folders.map { it.name })
        a.trashNotebook(keep.id)
        a.deleteNotebook(gone.id)
        syncA(); syncB()
        assertNotNull(b.library().notebooks.single { it.id == keep.id }.deletedAt, "trash spreads")
        assertNull(b.library().notebooks.firstOrNull { it.id == gone.id }, "permanent delete spreads")
    }

    @Test fun theOpenNotebookWaits() {
        val meta = a.createNotebook("Open", null, Cover(), PaperTemplate.BLANK)
        syncA(); syncB()
        write(a, meta.id, meta.pageIds[0], "new")
        syncA()
        busyB = meta.id
        syncB()
        assertEquals(emptyList(), strokeIds(b, meta.id, meta.pageIds[0]))
        busyB = null
        syncB()
        assertEquals(listOf("new"), strokeIds(b, meta.id, meta.pageIds[0]))
    }

    @Test fun hostileRemoteContentIsRefused() {
        val side = StoreSync(b)
        assertFailsWith<IllegalArgumentException> { side.write("n/../../etc/meta.json", ByteArray(1)) }
        assertFailsWith<IllegalArgumentException> { side.write("n/abc/a/../../x.pdf", ByteArray(1)) }
        // A page whose content claims a different id than its key.
        val meta = a.createNotebook("X", null, Cover(), PaperTemplate.BLANK)
        val pageBytes = File(File(File(a.root, "notebooks"), meta.id), "${meta.pageIds[0]}.page.json").readBytes()
        assertFailsWith<IllegalArgumentException> { side.write("n/${meta.id}/p/not-this-page.json", pageBytes) }
        assertTrue(b.library().notebooks.isEmpty(), "nothing was written")
    }
}
