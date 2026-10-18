package app.folio.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

fun testStroke(id: String = newId(), dx: Float = 0f, n: Int = 5) = Stroke(
    id = id, tool = ToolType.FOUNTAIN, color = 0xFF000000, width = 4f,
    x = FloatArray(n) { it * 10f + dx }, y = FloatArray(n) { it * 5f },
    pressure = FloatArray(n) { 0.5f }, tilt = FloatArray(n) { 0.2f }, orientation = FloatArray(n) { 1f },
    t = IntArray(n) { it * 8 }, startedAt = 1234,
)

class CodecTest {
    @Test fun pageRoundTripPreservesEveryChannel() {
        val page = Page(paper = PaperTemplate.GRID, strokes = listOf(testStroke(), testStroke(dx = 3f)))
        val back = DocumentCodec.decodePage(DocumentCodec.encodePage(page))
        assertEquals(page, back)
    }

    @Test fun unicodeTitlesSurvive() {
        val idx = LibraryIndex(notebooks = listOf(NotebookMeta(title = "भारत 历史 📒", createdAt = 1)))
        assertEquals(idx, DocumentCodec.decodeLibrary(DocumentCodec.encodeLibrary(idx)))
    }

    @Test fun newerSchemaIsRejected() {
        assertFailsWith<UnsupportedSchemaException> { DocumentCodec.decodePage("""{"schemaVersion": 99, "id":"a"}""") }
    }

    @Test fun malformedInputIsReportedAsCorrupt() {
        assertFailsWith<CorruptDocumentException> { DocumentCodec.decodePage("{not json") }
        assertFailsWith<CorruptDocumentException> { DocumentCodec.decodePage("") }
        // Truncated file.
        val full = DocumentCodec.encodePage(Page(strokes = listOf(testStroke())))
        assertFailsWith<CorruptDocumentException> { DocumentCodec.decodePage(full.substring(0, full.length / 2)) }
        // Channels of unequal length.
        val bad = full.replace(Regex("\"pressure\":\\[[^]]*]"), "\"pressure\":[0.5]")
        assertFailsWith<CorruptDocumentException> { DocumentCodec.decodePage(bad) }
    }

    @Test fun unknownFieldsFromNewerMinorVersionsAreTolerated() {
        val text = DocumentCodec.encodePage(Page()).replaceFirst("{", "{\"futureField\":42,")
        DocumentCodec.decodePage(text)
    }
}

class PageEditorTest {
    @Test fun addEraseUndoRedo() {
        val ed = PageEditor(Page())
        val a = testStroke("a"); val b = testStroke("b")
        ed.perform(PageEdit.AddStroke(a))
        ed.perform(PageEdit.AddStroke(b))
        ed.perform(PageEdit.RemoveStrokes.of(ed.currentStrokes, setOf("a")))
        assertEquals(listOf("b"), ed.currentStrokes.map { it.id })

        assertTrue(ed.undo())
        assertEquals(listOf("a", "b"), ed.currentStrokes.map { it.id }, "undo restores original z-order")
        assertTrue(ed.redo())
        assertEquals(listOf("b"), ed.currentStrokes.map { it.id })
        assertTrue(ed.undo()); assertTrue(ed.undo()); assertTrue(ed.undo())
        assertTrue(ed.currentStrokes.isEmpty())
        assertFalse(ed.undo())
    }

    @Test fun newEditClearsRedo() {
        val ed = PageEditor(Page())
        ed.perform(PageEdit.AddStroke(testStroke("a")))
        ed.undo()
        ed.perform(PageEdit.AddStroke(testStroke("b")))
        assertFalse(ed.canRedo)
    }

    @Test fun replaceKeepsPosition() {
        val a = testStroke("a"); val b = testStroke("b")
        val ed = PageEditor(Page(strokes = listOf(a, b)))
        ed.perform(PageEdit.ReplaceStrokes(listOf(a), listOf(a.translated(5f, 5f))))
        assertEquals(15f, ed.currentStrokes[0].x[1])
        ed.undo()
        assertEquals(a, ed.currentStrokes[0])
    }

    @Test fun snapshotIsDetachedFromLaterEdits() {
        val ed = PageEditor(Page())
        ed.perform(PageEdit.AddStroke(testStroke("a")))
        val snap = ed.snapshot()
        ed.perform(PageEdit.AddStroke(testStroke("b")))
        assertEquals(1, snap.strokes.size)
    }

    @Test fun historyIsBounded() {
        val ed = PageEditor(Page(), maxHistory = 3)
        repeat(5) { ed.perform(PageEdit.AddStroke(testStroke())) }
        var undos = 0
        while (ed.undo()) undos++
        assertEquals(3, undos)
        assertEquals(2, ed.currentStrokes.size)
    }
}

class PageFormatTest {
    @Test fun a4MatchesLegacyDefaultSize() {
        val (w, h) = PageFormat.A4.size(landscape = false)
        assertEquals(NotebookMeta.PAGE_WIDTH, w, 0.01f)
        assertEquals(NotebookMeta.PAGE_HEIGHT, h, 0.5f)
    }

    @Test fun sizesShareOnePhysicalScale() {
        val (a5w, _) = PageFormat.A5.size(false)
        assertEquals(148f * PageFormat.UNITS_PER_MM, a5w, 0.01f)
        val (lw, lh) = PageFormat.LETTER.size(true)
        assertTrue(lw > lh, "landscape is wider than tall")
    }

    @Test fun storedSizeIsRecognised() {
        for (f in PageFormat.entries) for (land in listOf(false, true)) {
            val (w, h) = f.size(land)
            assertEquals(f to land, PageFormat.match(w, h))
        }
        assertEquals(null, PageFormat.match(123f, 456f))
    }
}
