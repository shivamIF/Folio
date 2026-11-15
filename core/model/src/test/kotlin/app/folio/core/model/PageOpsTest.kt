package app.folio.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PageOpsTest {
    private val text = TextBox(x = 10f, y = 20f, width = 200f, height = 30f, text = "hello")
    private val image = PageImage(attachmentId = "pic-1", x = 0f, y = 0f, width = 50f, height = 40f)
    private val full = Page(
        width = 800f, height = 600f, paper = PaperTemplate.GRID,
        strokes = listOf(testStroke("a"), testStroke("b", dx = 5f)), texts = listOf(text), images = listOf(image),
        pdf = PdfBackground("doc-1", 3),
    )

    @Test fun blankLikeKeepsShapeAndPaperButNotContentOrPdf() {
        val blank = PageOps.blankLike(full)
        assertEquals(800f, blank.width)
        assertEquals(600f, blank.height)
        assertEquals(PaperTemplate.GRID, blank.paper)
        assertTrue(blank.strokes.isEmpty() && blank.texts.isEmpty() && blank.images.isEmpty())
        assertNull(blank.pdf)
        assertNotEquals(full.id, blank.id)
    }

    @Test fun duplicateIsIndependentWithFreshIdsAndSameLook() {
        val copy = PageOps.duplicate(full)
        assertNotEquals(full.id, copy.id)
        assertEquals(full.width, copy.width)
        assertEquals(full.paper, copy.paper)
        assertEquals(full.pdf, copy.pdf) // the attachment is shared
        assertEquals(full.strokes.size, copy.strokes.size)
        val oldIds = (full.strokes.map { it.id } + full.texts.map { it.id } + full.images.map { it.id }).toSet()
        val newIds = copy.strokes.map { it.id } + copy.texts.map { it.id } + copy.images.map { it.id }
        assertTrue(newIds.none { it in oldIds }, "every element needs a new id")
        assertEquals(newIds.size, newIds.toSet().size)
        // Same geometry and look.
        for ((a, b) in full.strokes.zip(copy.strokes)) {
            assertTrue(a.x.contentEquals(b.x) && a.y.contentEquals(b.y) && a.pressure.contentEquals(b.pressure))
            assertEquals(a.tool, b.tool); assertEquals(a.color, b.color); assertEquals(a.width, b.width)
        }
        assertEquals(text.text, copy.texts.single().text)
        assertEquals(image.attachmentId, copy.images.single().attachmentId)
        // Survives the codec (it is saved as its own page file).
        assertEquals(copy, DocumentCodec.decodePage(DocumentCodec.encodePage(copy)))
    }

    @Test fun eraseRemovesEverythingInOneUndoableStep() {
        val ed = PageEditor(full)
        val edit = PageOps.erase(ed.currentStrokes, ed.currentTexts, ed.currentImages)!!
        ed.perform(edit)
        assertFalse(PageOps.hasContent(ed.snapshot()))
        assertEquals(full.pdf, ed.snapshot().pdf) // the background stays
        assertTrue(ed.undo())
        val back = ed.snapshot()
        assertEquals(full.strokes.map { it.id }, back.strokes.map { it.id })
        assertEquals(full.texts, back.texts)
        assertEquals(full.images, back.images)
        assertTrue(ed.redo())
        assertFalse(PageOps.hasContent(ed.snapshot()))
    }

    @Test fun eraseKeepsOrderOfSeveralTextsOnUndo() {
        val texts = (0 until 4).map { TextBox(id = "t$it", x = 0f, y = it * 40f, width = 100f, height = 30f, text = "line $it") }
        val ed = PageEditor(Page(texts = texts))
        ed.perform(PageOps.erase(ed.currentStrokes, ed.currentTexts, ed.currentImages)!!)
        ed.undo()
        assertEquals(listOf("t0", "t1", "t2", "t3"), ed.currentTexts.map { it.id })
    }

    @Test fun eraseOfEmptyPageIsNull() {
        assertNull(PageOps.erase(emptyList(), emptyList(), emptyList()))
        assertFalse(PageOps.hasContent(Page()))
    }

    @Test fun movedReordersAndClamps() {
        val ids = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), PageOps.moved(ids, 0, 2))
        assertEquals(listOf("d", "a", "b", "c"), PageOps.moved(ids, 3, 0))
        assertEquals(listOf("a", "c", "d", "b"), PageOps.moved(ids, 1, 99))
        assertSame(ids, PageOps.moved(ids, 2, 2))
        assertSame(ids, PageOps.moved(ids, 7, 0))
    }
}
