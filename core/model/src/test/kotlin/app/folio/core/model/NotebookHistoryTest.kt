package app.folio.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotebookHistoryTest {
    private val editors = HashMap<String, PageEditor>()
    private val history = NotebookHistory()
    private val of: (String) -> PageEditor? = { editors[it] }

    private fun page(id: String) = PageEditor(Page(id = id)).also { editors[id] = it }

    private fun write(pageId: String, stroke: Stroke = testStroke()): PageEdit {
        val e = PageEdit.AddStroke(stroke)
        editors.getValue(pageId).perform(e)
        history.record(pageId, e)
        return e
    }

    private fun ids(pageId: String) = editors.getValue(pageId).currentStrokes.map { it.id }

    @Test fun undoFollowsTheOrderEditsWereMadeAcrossPages() {
        page("a"); page("b")
        write("a", testStroke("a1")); write("b", testStroke("b1")); write("a", testStroke("a2"))
        assertEquals("a", history.undo(of)); assertEquals(listOf("a1"), ids("a"))
        assertEquals("b", history.undo(of)); assertEquals(emptyList(), ids("b"))
        assertEquals("a", history.undo(of)); assertEquals(emptyList(), ids("a"))
        assertNull(history.undo(of))
        assertFalse(history.canUndo(of))
    }

    @Test fun redoReappliesInReverseOrderAndANewEditClearsIt() {
        page("a"); page("b")
        write("a", testStroke("a1")); write("b", testStroke("b1"))
        history.undo(of); history.undo(of)
        assertTrue(history.canRedo(of))
        assertEquals("a", history.redo(of)); assertEquals(listOf("a1"), ids("a"))
        write("b", testStroke("b2"))
        assertFalse(history.canRedo(of), "a new edit anywhere ends Redo")
        assertNull(history.redo(of))
        assertEquals(listOf("b2"), ids("b"))
    }

    @Test fun entriesForReloadedOrEvictedPagesAreSkipped() {
        page("a"); page("b")
        write("a", testStroke("a1")); write("b", testStroke("b1"))
        editors["b"] = PageEditor(editors.getValue("b").snapshot()) // merged from another device: fresh history
        assertEquals("a", history.undo(of), "b's entry no longer matches its page and is skipped")
        write("a", testStroke("a3"))
        editors.remove("a") // dropped from memory
        assertFalse(history.canUndo(of))
    }

    @Test fun aWaitingPageHoldsUndoUntilItLoads() {
        page("a")
        write("a", testStroke("a1"))
        val queued = PageEdit.AddStroke(testStroke("q1"))
        history.record("q", queued) // ink on a page that's still loading
        var loading = true
        val waiting: (String) -> Boolean = { it == "q" && loading }
        assertTrue(history.canUndo(of, waiting))
        assertNull(history.undo(of, waiting), "nothing undone while the page loads")
        assertEquals(listOf("a1"), ids("a"))
        page("q").perform(queued); loading = false
        assertEquals("q", history.undo(of, waiting))
        assertEquals(emptyList(), ids("q"))
    }

    @Test fun snackbarUndoOfAnOlderEditRevertsOnlyThatPage() {
        page("a"); page("b")
        val erase = write("a", testStroke("a1"))
        write("b", testStroke("b1"))
        assertTrue(history.undoEdit("a", erase, of))
        assertEquals(emptyList(), ids("a"))
        assertEquals("b", history.undo(of), "the newer edit is still next")
        assertNull(history.undo(of), "the snackbar's edit isn't undone twice")
        assertFalse(history.undoEdit("a", erase, of))
    }

    @Test fun snackbarUndoOfTheLatestEditIsANormalUndo() {
        page("a")
        val e = write("a", testStroke("a1"))
        assertTrue(history.undoEdit("a", e, of))
        assertEquals("a", history.redo(of))
        assertEquals(listOf("a1"), ids("a"))
    }

    @Test fun pageHistoryTrimmingMakesOldEntriesStale() {
        val ed = PageEditor(Page(id = "a"), maxHistory = 2).also { editors["a"] = it }
        repeat(3) { val e = PageEdit.AddStroke(testStroke()); ed.perform(e); history.record("a", e) }
        assertEquals("a", history.undo(of)); assertEquals("a", history.undo(of))
        assertNull(history.undo(of), "the third edit fell out of the page's own history")
        assertEquals(1, ed.currentStrokes.size)
    }
}

class PageHeaderTest {
    @Test fun readsSizeAndPaperFromAnEncodedPage() {
        val page = Page(width = 612f, height = 792f, paper = PaperTemplate.GRID, strokes = List(50) { testStroke() })
        val text = DocumentCodec.encodePage(page)
        assertEquals(PageHeader(612f, 792f, PaperTemplate.GRID), DocumentCodec.pageHeaderOf(text.take(1024)))
    }

    @Test fun readsSchema1Pages() {
        val old = """{"schemaVersion":1,"id":"p","width":1000.0,"height":1414.0,"paper":"BLANK","strokes":[{"id":"s","width":9.5}]}"""
        assertEquals(PageHeader(1000f, 1414f, PaperTemplate.BLANK), DocumentCodec.pageHeaderOf(old))
    }

    @Test fun ignoresAStrokeWidthAndRefusesWhatItCantTrust() {
        // Keys after "strokes" belong to strokes, not the page.
        assertNull(DocumentCodec.pageHeaderOf("""{"id":"p","strokes":[{"width":3.0,"height":2.0}],"paper":"RULED"}"""))
        assertNull(DocumentCodec.pageHeaderOf("""{"schemaVersion":99,"width":1.0,"height":1.0,"paper":"RULED","strokes":[]}"""))
        assertNull(DocumentCodec.pageHeaderOf("""{"width":0,"height":5,"paper":"RULED","strokes":[]}"""))
        assertNull(DocumentCodec.pageHeaderOf("""{"width":10,"height":5,"paper":"WAVY","strokes":[]}"""))
        assertNull(DocumentCodec.pageHeaderOf("""{"width":10,"height":5,"paper":"RULED","str"""), "cut off before strokes")
    }
}
