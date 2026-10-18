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

    private fun pdfBytes(size: Int = 2000) = ("%PDF-1.4\n" + "x".repeat(size)).toByteArray()

    @Test fun importedPdfNotebookKeepsBackgroundsAcrossRestart() {
        val store = FileNotebookStore(root)
        val staged = store.stagePdf(pdfBytes().inputStream())
        val pages = (0 until 3).map { Page(width = 1000f, height = 1294f, paper = PaperTemplate.BLANK, pdf = app.folio.core.model.PdfBackground(staged.id, it)) }
        val nb = store.createNotebookFromPdf("Lecture 1", null, Cover(), staged, pages)
        val reopened = FileNotebookStore(root)
        val meta = reopened.library().notebooks.single()
        assertEquals(3, meta.pageIds.size)
        assertEquals(2, reopened.loadPage(meta.id, meta.pageIds[2]).pdf!!.pageIndex)
        assertTrue(reopened.attachmentFile(nb.id, staged.id).exists())
        assertTrue(!staged.file.exists(), "staged copy moved, not duplicated")
    }

    @Test fun nonPdfIsRejectedAndLeavesNothingBehind() {
        val store = FileNotebookStore(root)
        assertFailsWith<InvalidImportException> { store.stagePdf("hello world".toByteArray().inputStream()) }
        assertFailsWith<InvalidImportException> { store.stagePdf(ByteArray(0).inputStream()) }
        assertTrue(File(root, "staging").listFiles().orEmpty().isEmpty())
    }

    @Test fun oversizedImportIsRejected() {
        val store = FileNotebookStore(root)
        assertFailsWith<InvalidImportException> { store.stagePdf(pdfBytes(5000).inputStream(), maxBytes = 1000) }
        assertTrue(File(root, "staging").listFiles().orEmpty().isEmpty())
    }

    @Test fun deletingImportedNotebookRemovesThePdf() {
        val store = FileNotebookStore(root)
        val staged = store.stagePdf(pdfBytes().inputStream())
        val nb = store.createNotebookFromPdf("x", null, Cover(), staged, listOf(Page(pdf = app.folio.core.model.PdfBackground(staged.id, 0))))
        val f = store.attachmentFile(nb.id, staged.id)
        store.deleteNotebook(nb.id)
        assertTrue(!f.exists())
    }

    @Test fun pagesMustReferenceTheStagedFile() {
        val store = FileNotebookStore(root)
        val staged = store.stagePdf(pdfBytes().inputStream())
        assertFailsWith<IllegalArgumentException> { store.createNotebookFromPdf("x", null, Cover(), staged, listOf(Page())) }
    }

    @Test fun trashHidesThenRestoreBringsBackWithContent() {
        var now = 1_000L
        val store = FileNotebookStore(root) { now }
        val nb = store.createNotebook("Bio", null, Cover(), PaperTemplate.RULED)
        store.savePage(nb.id, Page(id = nb.pageIds.first(), strokes = listOf(stroke("s"))))
        now = 2_000L
        store.trashNotebook(nb.id)
        val trashed = FileNotebookStore(root).library().notebooks.single()
        assertEquals(2_000L, trashed.deletedAt)
        store.restoreNotebook(nb.id)
        val back = FileNotebookStore(root)
        assertNull(back.library().notebooks.single().deletedAt)
        assertEquals(listOf("s"), back.loadPage(nb.id, nb.pageIds.first()).strokes.map { it.id })
    }

    @Test fun restoreGoesToRootWhenFolderIsGone() {
        val store = FileNotebookStore(root)
        val f = store.createFolder("Old", null, 0)
        val nb = store.createNotebook("x", f.id, Cover(), PaperTemplate.BLANK)
        store.trashNotebook(nb.id)
        store.deleteFolder(f.id)
        store.restoreNotebook(nb.id)
        assertNull(store.library().notebooks.single().folderId)
    }

    @Test fun purgeRemovesOnlyOldTrash() {
        var now = 0L
        val store = FileNotebookStore(root) { now }
        val old = store.createNotebook("old", null, Cover(), PaperTemplate.BLANK)
        val recent = store.createNotebook("recent", null, Cover(), PaperTemplate.BLANK)
        val live = store.createNotebook("live", null, Cover(), PaperTemplate.BLANK)
        now = 100L; store.trashNotebook(old.id)
        now = 900L; store.trashNotebook(recent.id)
        assertEquals(1, store.purgeTrash(cutoff = 500L))
        val titles = FileNotebookStore(root).library().notebooks.map { it.title }.toSet()
        assertEquals(setOf("recent", "live"), titles)
        assertTrue(!File(root, "notebooks/${old.id}").exists())
        assertTrue(File(root, "notebooks/${live.id}").exists())
    }

    @Test fun libraryFromBeforeTrashExistedStillLoads() {
        val store = FileNotebookStore(root)
        store.createNotebook("legacy", null, Cover(), PaperTemplate.BLANK)
        val f = File(root, "library.json")
        f.writeText(f.readText().replace(Regex(",?\"deletedAt\":null"), ""))
        assertNull(FileNotebookStore(root).library().notebooks.single().deletedAt)
    }
}
