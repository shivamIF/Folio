package app.folio.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Schema6Test {
    private fun stroke(tool: ToolType = ToolType.FOUNTAIN, look: StrokeLook = StrokeLook.DEFAULT, x0: Float = 10f, id: String = newId()) = Stroke(
        id, tool, 0xFF112233, 4f,
        floatArrayOf(x0, x0 + 10f, x0 + 20f), floatArrayOf(5f, 6f, 7f), floatArrayOf(0.4f, 0.5f, 0.6f),
        FloatArray(3), FloatArray(3), intArrayOf(0, 8, 16), 42, look,
    )

    @Test fun everyLookFieldRoundTrips() {
        val looks = listOf(
            StrokeLook(line = LineStyle.DASHED), StrokeLook(line = LineStyle.DOTTED, fixedWidth = true),
            StrokeLook(roundTip = true), StrokeLook(pattern = 7), StrokeLook(pattern = 3, peeled = true),
        )
        val page = Page(strokes = looks.map { stroke(look = it) } + stroke(ToolType.TAPE))
        val back = DocumentCodec.decodePage(DocumentCodec.encodePage(page))
        assertEquals(page.strokes, back.strokes)
        assertEquals(looks + StrokeLook.DEFAULT, back.strokes.map { it.look })
    }

    @Test fun plainPagesAreWrittenAsSchema5WithNoLookFields() {
        val text = DocumentCodec.encodePage(Page(strokes = listOf(stroke(ToolType.MARKER))))
        assertTrue("\"schemaVersion\":5" in text, text)
        for (key in listOf("\"line\"", "\"fixed\"", "\"round\"", "\"pattern\"", "\"peeled\"", "\"stickies\"", "\"tables\"", "\"audio\"")) assertFalse(key in text, key)
    }

    @Test fun pagesUsingSchema6FeaturesAreStampedSix() {
        val cases = listOf(
            Page(strokes = listOf(stroke(ToolType.TAPE))),
            Page(strokes = listOf(stroke(look = StrokeLook(line = LineStyle.DASHED)))),
            Page(stickies = listOf(StickyNote(x = 1f, y = 2f, width = 300f, height = 300f))),
            Page(tables = listOf(PageTable(x = 0f, y = 0f, width = 300f, height = 100f, rows = 2, cols = 3))),
            Page(audio = listOf(PageAudio(attachmentId = "a", x = 0f, y = 0f, title = "Memo"))),
        )
        for (p in cases) {
            assertEquals(6, p.requiredSchema())
            assertTrue("\"schemaVersion\":6" in DocumentCodec.encodePage(p))
            assertEquals(6, DocumentCodec.decodePage(DocumentCodec.encodePage(p)).schemaVersion)
        }
    }

    @Test fun libraryStaysSchema5WithTheNewNotebookFields() {
        val meta = NotebookMeta(title = "N", createdAt = 1, bookmarks = listOf("p1"), pageColor = 0xFFF2EAD8, darkAdjust = false, density = PaperDensity.NARROW)
        val text = DocumentCodec.encodeLibrary(LibraryIndex(notebooks = listOf(meta)))
        assertTrue("\"schemaVersion\":5" in text, text)
        assertEquals(meta, DocumentCodec.decodeLibrary(text).notebooks.single())
    }

    @Test fun aSchema5LibraryLoadsWithDefaultsForTheNewFields() {
        val v5 = """{"schemaVersion":5,"folders":[],"notebooks":[{"id":"n","title":"Old","createdAt":3}]}"""
        val m = DocumentCodec.decodeLibrary(v5).notebooks.single()
        assertEquals(emptyList(), m.bookmarks)
        assertEquals(null, m.pageColor)
        assertTrue(m.darkAdjust)
        assertEquals(PaperDensity.NORMAL, m.density)
    }

    @Test fun unknownNamesFromNewerBuildsFallBack() {
        val lib = """{"schemaVersion":5,"folders":[],"notebooks":[{"id":"n","title":"T","createdAt":3,"density":"HYPER"}]}"""
        assertEquals(PaperDensity.NORMAL, DocumentCodec.decodeLibrary(lib).notebooks.single().density)
        val page = Page(strokes = listOf(stroke(look = StrokeLook(line = LineStyle.DASHED))))
        val text = DocumentCodec.encodePage(page).replace("\"DASHED\"", "\"WAVY\"")
        assertEquals(LineStyle.SOLID, DocumentCodec.decodePage(text).strokes.single().look.line)
    }

    @Test fun transformsKeepTheLook() {
        val look = StrokeLook(line = LineStyle.DOTTED, fixedWidth = true)
        val s = stroke(look = look)
        assertEquals(look, s.transformed(2f, 0f, 0f, 3f, 4f).look)
        assertEquals(look, s.translated(1f, 1f).look)
        assertEquals(look, s.recolored(0xFF000000).look)
        assertEquals(look, s.rotated(30f, 0f, 0f).look)
        assertEquals(look, s.restyled(0xFF00FF00, 9f).look)
        assertTrue(s.withLook(look.copy(peeled = true)).look.peeled)
    }

    @Test fun restyleKeepsAHighlightersTranslucency() {
        val hl = Stroke(newId(), ToolType.HIGHLIGHTER, 0x80FFEE00, 10f, floatArrayOf(0f), floatArrayOf(0f), floatArrayOf(1f), FloatArray(1), FloatArray(1), IntArray(1))
        val r = hl.restyled(0xFF0000FF, 20f)
        assertEquals(0x800000FF, r.color)
        assertEquals(20f, r.width)
    }

    @Test fun rotationTurnsPointsAndPenAzimuth() {
        val s = stroke().rotated(90f, 0f, 0f)
        assertEquals(-5f, s.x[0], 1e-4f)
        assertEquals(10f, s.y[0], 1e-4f)
        assertEquals((Math.PI / 2).toFloat(), s.orientation[0], 1e-4f)
    }

    @Test fun stickyTableAndAudioEditsUndo() {
        val ed = PageEditor(Page())
        val note = StickyNote(x = 0f, y = 0f, width = 200f, height = 200f)
        ed.perform(PageEdit.PutSticky(null, note))
        val written = note.copy(strokes = listOf(stroke()))
        ed.perform(PageEdit.PutSticky(note, written))
        val table = PageTable(x = 0f, y = 300f, width = 300f, height = 80f, rows = 2, cols = 2)
        ed.perform(PageEdit.PutTable(null, table))
        ed.perform(PageEdit.PutTable(table, table.withCell(1, 1, "x")))
        ed.perform(PageEdit.PutAudio(null, PageAudio(attachmentId = "a", x = 0f, y = 0f, title = "Memo")))
        val snap = ed.snapshot()
        assertEquals(1, snap.stickies.single().strokes.size)
        assertEquals("x", snap.tables.single().cell(1, 1))
        assertEquals(1, snap.audio.size)
        repeat(5) { ed.undo() }
        val empty = ed.snapshot()
        assertTrue(empty.stickies.isEmpty() && empty.tables.isEmpty() && empty.audio.isEmpty())
        repeat(5) { ed.redo() }
        assertEquals(snap, ed.snapshot())
    }

    @Test fun eraseRemovesTheNewObjectsInOneStep() {
        val page = Page(
            strokes = listOf(stroke()), stickies = listOf(StickyNote(x = 0f, y = 0f, width = 10f, height = 10f)),
            tables = listOf(PageTable(x = 0f, y = 0f, width = 10f, height = 10f, rows = 1, cols = 1)),
            audio = listOf(PageAudio(attachmentId = "a", x = 0f, y = 0f, title = "t")),
        )
        val ed = PageEditor(page)
        ed.perform(PageOps.erase(ed.currentStrokes, ed.currentTexts, ed.currentImages, ed.currentStickies, ed.currentTables, ed.currentAudio)!!)
        assertFalse(PageOps.hasContent(ed.snapshot()))
        ed.undo()
        assertEquals(page, ed.snapshot())
    }

    @Test fun duplicateCopiesStickiesWithNewIds() {
        val page = Page(stickies = listOf(StickyNote(x = 0f, y = 0f, width = 10f, height = 10f, strokes = listOf(stroke()))))
        val copy = PageOps.duplicate(page)
        assertTrue(copy.stickies.single().id != page.stickies.single().id)
        assertTrue(copy.stickies.single().strokes.single().id != page.stickies.single().strokes.single().id)
        assertEquals(6, copy.schemaVersion)
    }

    @Test fun moveForwardsPassesTheNextOverlappingStroke() {
        val a = stroke(id = "a"); val b = stroke(id = "b"); val far = stroke(x0 = 900f, id = "far"); val c = stroke(id = "c")
        val ed = PageEditor(Page(strokes = listOf(a, b, far, c)))
        ed.perform(PageEdit.Reorder.step(ed.currentStrokes, setOf("a"), forward = true)!!)
        assertEquals(listOf("b", "a", "far", "c"), ed.currentStrokes.map { it.id })
        ed.perform(PageEdit.Reorder.step(ed.currentStrokes, setOf("a"), forward = true)!!)
        assertEquals(listOf("b", "far", "c", "a"), ed.currentStrokes.map { it.id })
        assertEquals(null, PageEdit.Reorder.step(ed.currentStrokes, setOf("a"), forward = true))
        ed.perform(PageEdit.Reorder.step(ed.currentStrokes, setOf("a"), forward = false)!!)
        assertEquals(listOf("b", "far", "a", "c"), ed.currentStrokes.map { it.id })
        ed.undo(); ed.undo(); ed.undo()
        assertEquals(listOf("a", "b", "far", "c"), ed.currentStrokes.map { it.id })
    }

    @Test fun mergeKeepsStickiesFromBothSides() {
        val base = Page(id = "p")
        val mine = base.copy(stickies = listOf(StickyNote(id = "m", x = 0f, y = 0f, width = 1f, height = 1f)))
        val theirs = base.copy(tables = listOf(PageTable(id = "t", x = 0f, y = 0f, width = 1f, height = 1f, rows = 1, cols = 1)))
        val merged = PageMerge.merge(base, mine, theirs)
        assertEquals(listOf("m"), merged.stickies.map { it.id })
        assertEquals(listOf("t"), merged.tables.map { it.id })
    }
}
