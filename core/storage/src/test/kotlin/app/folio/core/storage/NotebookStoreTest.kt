package app.folio.core.storage

import app.folio.core.model.CorruptDocumentException
import app.folio.core.model.Cover
import app.folio.core.model.Page
import app.folio.core.model.PaperTemplate
import app.folio.core.model.Stroke
import app.folio.core.model.ToolType
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotebookStoreTest {
    private val root: File = Files.createTempDirectory("folio-test").toFile()
    @AfterTest fun cleanup() { root.deleteRecursively() }

    private fun stroke(id: String) = Stroke(
        id = id, tool = ToolType.FOUNTAIN, color = 0xFF112233, width = 3f,
        x = floatArrayOf(1f, 2f, 3f), y = floatArrayOf(1f, 2f, 3f), pressure = floatArrayOf(.2f, .5f, .9f),
        tilt = floatArrayOf(0f, .1f, .2f), orientation = floatArrayOf(0f, 0f, 0f), t = intArrayOf(0, 5, 10),
    )

    @Test fun notebookSurvivesReopen() {
        val store = FileNotebookStore(root)
        val nb = store.createNotebook("Physics", null, Cover(color = 0xFFAA0000), PaperTemplate.GRID)
        val page = store.loadPage(nb.id, nb.pageIds.first())
        store.savePage(nb.id, page.copy(strokes = listOf(stroke("s1"), stroke("s2"))))

        val reopened = FileNotebookStore(root) // simulates an app restart
        val meta = reopened.library().notebooks.single()
        assertEquals("Physics", meta.title)
        assertEquals(0xFFAA0000, meta.cover.color)
        assertEquals(PaperTemplate.GRID, meta.paper)
        val back = reopened.loadPage(meta.id, meta.pageIds.first())
        assertEquals(listOf("s1", "s2"), back.strokes.map { it.id })
        assertEquals(stroke("s1"), back.strokes.first())
    }

    @Test fun corruptPageFallsBackToPreviousSave() {
        val store = FileNotebookStore(root)
        val nb = store.createNotebook("A", null, Cover(), PaperTemplate.BLANK)
        val pid = nb.pageIds.first()
        store.savePage(nb.id, Page(id = pid, strokes = listOf(stroke("old"))))
        store.savePage(nb.id, Page(id = pid, strokes = listOf(stroke("old"), stroke("new"))))
        // Simulate a torn write / disk corruption on the main file.
        File(root, "notebooks/${nb.id}/$pid.page.json").writeText("{\"strokes\": [")
        val fresh = FileNotebookStore(root)
        assertEquals(listOf("old"), fresh.loadPage(nb.id, pid).strokes.map { it.id })
        assertNotNull(fresh.lastRecovery)
    }

    @Test fun corruptWithoutBackupIsAnErrorNotSilentLoss() {
        val store = FileNotebookStore(root)
        val nb = store.createNotebook("A", null, Cover(), PaperTemplate.BLANK)
        val pid = nb.pageIds.first()
        File(root, "notebooks/${nb.id}/$pid.page.json").writeText("garbage")
        assertFailsWith<CorruptDocumentException> { FileNotebookStore(root).loadPage(nb.id, pid) }
    }

    @Test fun leftoverTempFileFromCrashIsIgnored() {
        val store = FileNotebookStore(root)
        val nb = store.createNotebook("A", null, Cover(), PaperTemplate.BLANK)
        val pid = nb.pageIds.first()
        store.savePage(nb.id, Page(id = pid, strokes = listOf(stroke("kept"))))
        File(root, "notebooks/${nb.id}/$pid.page.json.tmp").writeText("half written")
        assertEquals(listOf("kept"), FileNotebookStore(root).loadPage(nb.id, pid).strokes.map { it.id })
    }

    @Test fun foldersNestAndDeletingKeepsNotebooks() {
        val store = FileNotebookStore(root)
        val school = store.createFolder("School", null, 0xFF00FF00)
        val math = store.createFolder("Math", school.id, 0xFF0000FF)
        val nb = store.createNotebook("Algebra", math.id, Cover(), PaperTemplate.RULED)
        store.deleteFolder(school.id)
        val idx = FileNotebookStore(root).library()
        assertTrue(idx.folders.isEmpty())
        assertNull(idx.notebooks.single { it.id == nb.id }.folderId, "notebook moved to the root, not deleted")
    }

    @Test fun createFolderRejectsUnknownParent() {
        assertFailsWith<IllegalArgumentException> { FileNotebookStore(root).createFolder("x", "nope", 0) }
    }

    @Test fun addPageInsertsAtPosition() {
        val store = FileNotebookStore(root)
        val nb = store.createNotebook("A", null, Cover(), PaperTemplate.DOTTED)
        val m1 = store.addPage(nb.id, 0)
        val m2 = store.addPage(nb.id, -1)
        assertEquals(3, m2.pageIds.size)
        assertEquals(nb.pageIds.first(), m2.pageIds[1])
        assertEquals(PaperTemplate.DOTTED, store.loadPage(nb.id, m1.pageIds[1]).paper)
    }

    @Test fun deleteNotebookRemovesFiles() {
        val store = FileNotebookStore(root)
        val nb = store.createNotebook("A", null, Cover(), PaperTemplate.BLANK)
        store.deleteNotebook(nb.id)
        assertTrue(store.library().notebooks.isEmpty())
        assertTrue(!File(root, "notebooks/${nb.id}").exists())
    }

    @Test fun pathTraversalIdsAreRejected() {
        val store = FileNotebookStore(root)
        assertFailsWith<IllegalArgumentException> { store.loadPage("../../etc", "passwd") }
        assertFailsWith<IllegalArgumentException> { store.savePage("ok", Page(id = "../x")) }
    }

    @Test fun renameIgnoresBlankNames() {
        val store = FileNotebookStore(root)
        val f = store.createFolder("  ", null, 0)
        assertEquals("Untitled folder", f.name)
        store.renameFolder(f.id, "   ")
        assertEquals("Untitled folder", store.library().folders.single().name)
    }

    @Test fun pageSizeIsStoredForNotebookAndNewPages() {
        val store = FileNotebookStore(root)
        val nb = store.createNotebook("Letter", null, Cover(), PaperTemplate.GRID, 1028f, 1330f)
        assertEquals(1028f, store.loadPage(nb.id, nb.pageIds.first()).width)
        val m = store.addPage(nb.id, 0)
        assertEquals(1330f, store.loadPage(nb.id, m.pageIds[1]).height)
        assertEquals(1028f, FileNotebookStore(root).library().notebooks.single().pageWidth)
    }

    @Test fun absurdPageSizeIsRejected() {
        assertFailsWith<IllegalArgumentException> { FileNotebookStore(root).createNotebook("x", null, Cover(), PaperTemplate.BLANK, 0f, 1e9f) }
    }
}
