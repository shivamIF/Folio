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

/** A page exactly as schema 1 wrote it (number arrays per channel). */
private const val SCHEMA1_PAGE = """{"schemaVersion":1,"id":"p","width":1000.0,"height":1414.0,"paper":"BLANK","strokes":[{"id":"old-1","tool":"FOUNTAIN","color":4278190080,"width":1.5,"x":[10.0,20.5],"y":[5.0,6.0],"pressure":[0.5,0.6],"tilt":[0.0,0.0],"orientation":[0.0,0.0],"t":[0,16],"startedAt":5}],"pdf":null}"""

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
        // Schema 1 channels of unequal length.
        val bad = SCHEMA1_PAGE.replace("\"pressure\":[0.5,0.6]", "\"pressure\":[0.5]")
        assertFailsWith<CorruptDocumentException> { DocumentCodec.decodePage(bad) }
        // Packed points cut short, or not base64 at all.
        val pts = Regex("\"pts\":\"([^\"]*)\"").find(full)!!.groupValues[1]
        assertFailsWith<CorruptDocumentException> { DocumentCodec.decodePage(full.replace(pts, pts.dropLast(8))) }
        assertFailsWith<CorruptDocumentException> { DocumentCodec.decodePage(full.replace(pts, "*not base64*")) }
    }

    @Test fun strokesAreWrittenPackedAndLosslessly() {
        val s = Stroke(
            "p1", ToolType.PENCIL, 0x80123456, 2.5f, floatArrayOf(-1.5f, 1e-7f, 999.8059f), floatArrayOf(0f, Float.MIN_VALUE, 1414.17f),
            floatArrayOf(0f, 0.5f, 1f), floatArrayOf(0.1f, 0.2f, 0.3f), floatArrayOf(-3.1f, 0f, 3.1f), intArrayOf(0, 8, Int.MAX_VALUE), 77,
        )
        val text = DocumentCodec.encodePage(Page(strokes = listOf(s)))
        assertTrue("\"pts\":" in text && "\"x\":" !in text, text)
        assertEquals(s, DocumentCodec.decodePage(text).strokes.single())
        assertTrue("\"schemaVersion\":$SCHEMA_VERSION" in text)
    }

    @Test fun schema1PagesStillLoad() {
        val page = DocumentCodec.decodePage(SCHEMA1_PAGE)
        val s = page.strokes.single()
        assertEquals("old-1", s.id)
        assertTrue(s.x.contentEquals(floatArrayOf(10f, 20.5f)) && s.pressure.contentEquals(floatArrayOf(0.5f, 0.6f)))
        assertTrue(s.t.contentEquals(intArrayOf(0, 16)))
        // Saving upgrades it to the packed form.
        assertTrue("\"pts\":" in DocumentCodec.encodePage(page))
    }

    @Test fun pageWithPdfBackgroundRoundTripsAndOldPagesStillLoad() {
        val page = Page(pdf = PdfBackground("abc-123", 4))
        assertEquals(page, DocumentCodec.decodePage(DocumentCodec.encodePage(page)))
        // A page saved before PDF support has no "pdf" key at all.
        val old = DocumentCodec.encodePage(Page()).replace(Regex(",?\"pdf\":null"), "")
        assertEquals(null, DocumentCodec.decodePage(old).pdf)
    }

    @Test fun unknownFieldsFromNewerMinorVersionsAreTolerated() {
        val text = DocumentCodec.encodePage(Page()).replaceFirst("{", "{\"futureField\":42,")
        DocumentCodec.decodePage(text)
    }

    @Test fun schemaVersionIsReadWithoutParsingTheWholeDocument() {
        assertEquals(SCHEMA_VERSION, DocumentCodec.schemaVersionOf("""{"id":"a"}"""))
        assertEquals(7, DocumentCodec.schemaVersionOf("""{ "schemaVersion" :  7 , "id":"a"}"""))
        assertEquals(Int.MAX_VALUE, DocumentCodec.schemaVersionOf("""{"schemaVersion":123456789012}"""))
        assertFailsWith<CorruptDocumentException> { DocumentCodec.schemaVersionOf("""{"schemaVersion":"x"}""") }
        assertFailsWith<UnsupportedSchemaException> { DocumentCodec.decodePage("""{"id":"a","schemaVersion":2000000000000}""") }
    }

    /**
     * An imported Samsung Notes page (7,000+ strokes, 28 MB of JSON) crashed the app with
     * OutOfMemoryError on open. This page is about the same size and must decode within the
     * 256 MB heap this test task runs with (see build.gradle.kts).
     */
    @Test fun denseImportedPageDecodesWithinADeviceSizedHeap() {
        val strokes = List(7_500) { k -> testStroke(id = "s$k", dx = k % 900f, n = 170) }
        val text = DocumentCodec.encodePage(Page(id = "dense", strokes = strokes))
        assertTrue(text.length > 20_000_000, "test page should be as large as a real import: ${text.length}")
        val back = DocumentCodec.decodePage(text)
        assertEquals(7_500, back.strokes.size)
        assertEquals(strokes.last(), back.strokes.last())
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

class BulkEditTest {
    private fun page(n: Int) = List(n) { testStroke(id = "s$it", dx = it.toFloat()) }

    /** Large removals and splits must undo back to exactly the original order (z-order). */
    @Test fun randomRemovalsAndSplitsUndoExactly() {
        val rnd = kotlin.random.Random(11)
        repeat(30) {
            val original = page(400)
            val ed = PageEditor(Page(strokes = original))
            val gone = original.filter { rnd.nextInt(4) == 0 }.mapTo(HashSet()) { it.id }
            ed.perform(PageEdit.RemoveStrokes.of(ed.currentStrokes, gone))
            assertEquals(original.filter { it.id !in gone }, ed.currentStrokes)
            val split = ed.currentStrokes.filter { rnd.nextInt(3) == 0 }
                .associate { s -> s.id to List(rnd.nextInt(3)) { k -> testStroke(id = "${s.id}-p$k") } }
            val before = ed.currentStrokes.toList()
            ed.perform(PageEdit.SplitStrokes.of(ed.currentStrokes, split))
            assertEquals(before.flatMap { split[it.id] ?: listOf(it) }, ed.currentStrokes)
            ed.undo(); assertEquals(before, ed.currentStrokes)
            ed.undo(); assertEquals(original, ed.currentStrokes)
            ed.redo(); ed.redo(); ed.undo(); ed.undo(); assertEquals(original, ed.currentStrokes)
        }
    }
}
