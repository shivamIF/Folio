package app.folio.core.storage

import app.folio.core.model.CorruptDocumentException
import app.folio.core.model.Cover
import app.folio.core.model.FolderIcon
import app.folio.core.model.Page
import app.folio.core.model.PageImage
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

    @Test fun insertPagePutsAGivenPageAtAnIndexAndSurvivesRestart() {
        val store = FileNotebookStore(root)
        val nb = store.createNotebook("A", null, Cover(), PaperTemplate.RULED)
        val first = nb.pageIds.single()
        val before = Page(width = 700f, height = 500f, paper = PaperTemplate.GRID, strokes = listOf(stroke("s1")))
        val m1 = store.insertPage(nb.id, 0, before)
        assertEquals(listOf(before.id, first), m1.pageIds)
        val after = Page(paper = PaperTemplate.DOTTED)
        val m2 = store.insertPage(nb.id, 99, after) // clamped to the end
        assertEquals(listOf(before.id, first, after.id), m2.pageIds)

        val reopened = FileNotebookStore(root)
        assertEquals(m2.pageIds, reopened.library().notebooks.single().pageIds)
        val back = reopened.loadPage(nb.id, before.id)
        assertEquals(700f, back.width)
        assertEquals(PaperTemplate.GRID, back.paper)
        assertEquals(listOf("s1"), back.strokes.map { it.id })
    }

    @Test fun insertPageRefusesAnIdAlreadyListed() {
        val store = FileNotebookStore(root)
        val nb = store.createNotebook("A", null, Cover(), PaperTemplate.RULED)
        assertFailsWith<IllegalArgumentException> { store.insertPage(nb.id, 0, Page(id = nb.pageIds.single())) }
        assertEquals(1, store.library().notebooks.single().pageIds.size)
    }

    @Test fun deletePageUnlistsAndRemovesTheFileButKeepsTheLastPage() {
        val store = FileNotebookStore(root)
        val nb = store.createNotebook("A", null, Cover(), PaperTemplate.RULED)
        val m = store.addPage(nb.id, 0)
        val doomed = m.pageIds[1]
        store.savePage(nb.id, Page(id = doomed, strokes = listOf(stroke("x"))))
        val after = store.deletePage(nb.id, doomed)
        assertEquals(listOf(nb.pageIds.single()), after.pageIds)
        assertTrue(File(root, "notebooks/${nb.id}").listFiles()!!.none { it.name.startsWith(doomed) }, "page file and backup are gone")
        assertEquals(after.pageIds, FileNotebookStore(root).library().notebooks.single().pageIds)
        // A notebook needs at least one page.
        assertFailsWith<IllegalArgumentException> { store.deletePage(nb.id, after.pageIds.single()) }
        assertFailsWith<IllegalArgumentException> { store.deletePage(nb.id, "no-such-page") }
    }

    @Test fun deletedPageComesBackWithItsContentWhenReinserted() {
        val store = FileNotebookStore(root)
        val nb = store.createNotebook("A", null, Cover(), PaperTemplate.RULED)
        val ids = store.addPage(store.addPage(nb.id, 0).id, 1).pageIds
        val page = store.loadPage(nb.id, ids[1]).copy(strokes = listOf(stroke("keep")))
        store.savePage(nb.id, page)
        store.deletePage(nb.id, page.id)
        val restored = store.insertPage(nb.id, 1, page) // undo
        assertEquals(ids, restored.pageIds)
        assertEquals(listOf("keep"), store.loadPage(nb.id, page.id).strokes.map { it.id })
    }

    @Test fun movePageReordersOnlyTheIndex() {
        val store = FileNotebookStore(root)
        val nb = store.createNotebook("A", null, Cover(), PaperTemplate.RULED)
        val ids = store.addPage(store.addPage(nb.id, 0).id, 1).pageIds // three pages
        store.savePage(nb.id, Page(id = ids[0], strokes = listOf(stroke("first"))))
        val moved = store.movePage(nb.id, ids[0], 2)
        assertEquals(listOf(ids[1], ids[2], ids[0]), moved.pageIds)
        assertEquals(listOf("first"), store.loadPage(nb.id, ids[0]).strokes.map { it.id })
        assertEquals(listOf(ids[0], ids[1], ids[2]), store.movePage(nb.id, ids[0], -5).pageIds) // clamped
        val same = store.movePage(nb.id, ids[1], 1)
        assertEquals(listOf(ids[0], ids[1], ids[2]), same.pageIds)
        assertFailsWith<IllegalArgumentException> { store.movePage(nb.id, "no-such-page", 0) }
    }

    @Test fun pageStampChangesWhenThePageIsSaved() {
        var now = 1_000L
        val store = FileNotebookStore(root) { now }
        val nb = store.createNotebook("A", null, Cover(), PaperTemplate.RULED)
        val pid = nb.pageIds.single()
        val s1 = assertNotNull(store.pageStamp(nb.id, pid))
        val f = File(root, "notebooks/${nb.id}/$pid.page.json")
        store.savePage(nb.id, Page(id = pid, strokes = listOf(stroke("a"), stroke("b"))))
        f.setLastModified(f.lastModified() + 5_000) // filesystems with coarse timestamps
        now += 1
        assertTrue(store.pageStamp(nb.id, pid) != s1)
        assertNull(store.pageStamp(nb.id, "missing-page"))
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

    @Test fun importWithSeveralPdfsAndPlainPages() {
        val store = FileNotebookStore(root)
        val a = store.stagePdf(pdfBytes().inputStream()); val b = store.stagePdf(pdfBytes().inputStream())
        val pages = listOf(
            Page(pdf = app.folio.core.model.PdfBackground(a.id, 0)), Page(paper = PaperTemplate.BLANK),
            Page(pdf = app.folio.core.model.PdfBackground(b.id, 3), strokes = listOf(stroke("s"))),
        )
        val nb = store.createNotebookFromImport("GN", null, Cover(), listOf(a, b), pages)
        val back = FileNotebookStore(root)
        assertEquals(3, back.library().notebooks.single().pageIds.size)
        assertTrue(back.attachmentFile(nb.id, a.id).exists() && back.attachmentFile(nb.id, b.id).exists())
        assertEquals(listOf("s"), back.loadPage(nb.id, pages[2].id).strokes.map { it.id })
    }

    @Test fun importRejectsPagesPointingAtUnstagedFiles() {
        val store = FileNotebookStore(root)
        val a = store.stagePdf(pdfBytes().inputStream())
        assertFailsWith<IllegalArgumentException> {
            store.createNotebookFromImport("x", null, Cover(), listOf(a), listOf(Page(pdf = app.folio.core.model.PdfBackground("other", 0))))
        }
    }

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(500)

    @Test fun picturesAreStagedByContentAndMovedIntoTheNotebook() {
        val store = FileNotebookStore(root)
        assertFailsWith<InvalidImportException> { store.stageImage("just text, not a picture".toByteArray().inputStream()) }
        assertFailsWith<InvalidImportException> { store.stageImage(pdfBytes().inputStream()) }
        val nb = store.createNotebook("Pics", null, Cover(), PaperTemplate.BLANK)
        val st = store.stageImage(png.inputStream())
        val id = store.addImage(nb.id, st)
        assertTrue(store.imageFile(nb.id, id).readBytes().contentEquals(png))
        assertTrue(!st.file.exists())
        // Pages keep their pictures across a restart.
        val page = store.loadPage(nb.id, nb.pageIds.first())
        val withImage = page.copy(images = listOf(PageImage(attachmentId = id, x = 10f, y = 20f, width = 300f, height = 200f)))
        store.savePage(nb.id, withImage)
        assertEquals(withImage.images, FileNotebookStore(root).loadPage(nb.id, nb.pageIds.first()).images)
    }

    @Test fun importedPagesMayOnlyUseStagedPictures() {
        val store = FileNotebookStore(root)
        val pic = store.stageImage(png.inputStream())
        val ok = Page(images = listOf(PageImage(attachmentId = pic.id, x = 0f, y = 0f, width = 10f, height = 10f)))
        val meta = store.createNotebookFromImport("Imported", null, Cover(), listOf(pic), listOf(ok))
        assertTrue(store.imageFile(meta.id, pic.id).exists())
        val bad = Page(images = listOf(PageImage(attachmentId = "not-staged", x = 0f, y = 0f, width = 10f, height = 10f)))
        assertFailsWith<IllegalArgumentException> { store.createNotebookFromImport("Bad", null, Cover(), emptyList(), listOf(bad)) }
    }

    // ------------------------------------------------------------ folders (library redesign)

    @Test fun folderCustomizationPersists() {
        val store = FileNotebookStore(root)
        val f = store.createFolder("UPSC", null, 0xFF000001)
        store.updateFolder(f.id, color = 0xFF4FB6E3, icon = FolderIcon.INSTITUTION)
        store.updateFolder(f.id, name = "  ")
        val back = FileNotebookStore(root).library().folders.single()
        assertEquals("UPSC", back.name, "a blank name keeps the old one")
        assertEquals(0xFF4FB6E3, back.color)
        assertEquals(FolderIcon.INSTITUTION, back.icon)
    }

    @Test fun favouritesAreSavedAndCountAsChangesForSync() {
        var now = 100L
        val store = FileNotebookStore(root) { now }
        val nb = store.createNotebook("A", null, Cover(), PaperTemplate.BLANK)
        val f = store.createFolder("F", null, 0)
        now = 500L
        store.setFavorite(listOf(nb.id), listOf(f.id), true)
        val idx = FileNotebookStore(root).library()
        assertTrue(idx.notebooks.single().favorite && idx.folders.single().favorite)
        // Sync keeps the newer copy of a folder or notebook, so the star must move modifiedAt on.
        assertEquals(500L, idx.folders.single().modifiedAt)
    }

    @Test fun moveIntoFolderAndRefuseCycles() {
        val store = FileNotebookStore(root)
        val a = store.createFolder("A", null, 0)
        val b = store.createFolder("B", a.id, 0)
        val nb = store.createNotebook("N", null, Cover(), PaperTemplate.BLANK)
        store.moveItems(listOf(nb.id), emptyList(), b.id)
        assertEquals(b.id, store.library().notebooks.single().folderId)
        assertFailsWith<IllegalArgumentException> { store.moveItems(emptyList(), listOf(a.id), b.id) }
        assertFailsWith<IllegalArgumentException> { store.moveItems(emptyList(), listOf(a.id), a.id) }
        store.moveItems(emptyList(), listOf(b.id), null)
        assertNull(store.library().folders.single { it.id == b.id }.parentId)
    }

    @Test fun duplicateCopiesPagesAndAttachments() {
        val store = FileNotebookStore(root)
        val nb = store.createNotebook("Notes", null, Cover(), PaperTemplate.GRID)
        store.savePage(nb.id, store.loadPage(nb.id, nb.pageIds.first()).copy(strokes = listOf(stroke("s1"))))
        store.addPage(nb.id, 0)
        File(root, "notebooks/${nb.id}/attachments").mkdirs()
        File(root, "notebooks/${nb.id}/attachments/abc.pdf").writeText("%PDF-1.4 test")
        val copy = store.duplicateNotebook(nb.id)
        assertEquals("Notes (copy)", copy.title)
        assertEquals(2, copy.pageIds.size)
        assertTrue(copy.pageIds.none { it in nb.pageIds }, "pages get new ids")
        val reopened = FileNotebookStore(root)
        assertEquals(listOf("s1"), reopened.loadPage(copy.id, copy.pageIds.first()).strokes.map { it.id })
        assertEquals("%PDF-1.4 test", reopened.attachmentFile(copy.id, "abc").readText())
        assertEquals("Notes (copy 2)", reopened.duplicateNotebook(nb.id).title)
    }

    @Test fun folderTrashRestoreAndDeleteForever() {
        val store = FileNotebookStore(root)
        val f = store.createFolder("F", null, 0)
        val sub = store.createFolder("Sub", f.id, 0)
        val inside = store.createNotebook("Inside", sub.id, Cover(), PaperTemplate.BLANK)
        val loose = store.createNotebook("Loose", null, Cover(), PaperTemplate.BLANK)
        store.trashFolder(f.id)
        assertEquals(setOf(f.id, sub.id), store.library().trashedFolderIds())
        assertTrue(File(root, "notebooks/${inside.id}").exists(), "trashing keeps files")

        store.restoreFolder(f.id)
        assertTrue(store.library().trashedFolderIds().isEmpty())

        store.trashFolder(f.id)
        store.deleteFolderForever(f.id)
        val after = FileNotebookStore(root).library()
        assertTrue(after.folders.isEmpty())
        assertEquals(listOf(loose.id), after.notebooks.map { it.id }, "the folder took its notebooks with it")
        assertTrue(!File(root, "notebooks/${inside.id}").exists())
    }

    @Test fun restoringIntoATrashedFolderGoesToTheTopLevel() {
        val store = FileNotebookStore(root)
        val f = store.createFolder("F", null, 0)
        val child = store.createFolder("Child", f.id, 0)
        val nb = store.createNotebook("N", f.id, Cover(), PaperTemplate.BLANK)
        store.trashNotebook(nb.id)
        store.trashFolder(child.id)
        store.trashFolder(f.id)
        store.restoreNotebook(nb.id)
        store.restoreFolder(child.id)
        val idx = store.library()
        assertNull(idx.notebooks.single().folderId)
        assertNull(idx.folders.single { it.id == child.id }.parentId)
        assertTrue(child.id !in idx.trashedFolderIds())
    }

    @Test fun purgeTrashRemovesExpiredFoldersWithTheirContents() {
        var now = 1_000L
        val store = FileNotebookStore(root) { now }
        val old = store.createFolder("Old", null, 0)
        val inOld = store.createNotebook("In old", old.id, Cover(), PaperTemplate.BLANK)
        val keep = store.createNotebook("Keep", null, Cover(), PaperTemplate.BLANK)
        store.trashFolder(old.id)
        now = 5_000L
        assertEquals(0, store.purgeTrash(cutoff = 500L), "nothing expired yet")
        assertEquals(1, store.purgeTrash(cutoff = 2_000L))
        val idx = store.library()
        assertTrue(idx.folders.isEmpty())
        assertEquals(listOf(keep.id), idx.notebooks.map { it.id })
        assertTrue(!File(root, "notebooks/${inOld.id}").exists())
    }

    @Test fun pageHeaderGivesSizeAndPaperWithoutLoadingStrokes() {
        val store = FileNotebookStore(root)
        val nb = store.createNotebook("Mixed", null, Cover(), PaperTemplate.DOTTED)
        val first = nb.pageIds.first()
        assertEquals(app.folio.core.model.PageHeader(1000f, 1414f, PaperTemplate.DOTTED), store.pageHeader(nb.id, first))
        val wide = Page(width = 1414f, height = 1000f, paper = PaperTemplate.BLANK, strokes = List(400) { stroke("w$it") })
        store.insertPage(nb.id, 1, wide)
        assertEquals(app.folio.core.model.PageHeader(1414f, 1000f, PaperTemplate.BLANK), store.pageHeader(nb.id, wide.id))
        assertNull(store.pageHeader(nb.id, "never-written"))
    }

    @Test fun loadPageStillFallsBackToTheBackup() {
        val store = FileNotebookStore(root)
        val nb = store.createNotebook("Backup", null, Cover(), PaperTemplate.RULED)
        val pid = nb.pageIds.first()
        val page = store.loadPage(nb.id, pid)
        store.savePage(nb.id, page.copy(strokes = listOf(stroke("s1"))))
        store.savePage(nb.id, page.copy(strokes = listOf(stroke("s1"), stroke("s2"))))
        File(File(File(root, "notebooks"), nb.id), "$pid.page.json").writeText("{ not json")
        assertEquals(listOf("s1"), store.loadPage(nb.id, pid).strokes.map { it.id })
        assertNotNull(store.lastRecovery)
        assertEquals(Page(id = "missing"), store.loadPage(nb.id, "missing"))
    }
}
