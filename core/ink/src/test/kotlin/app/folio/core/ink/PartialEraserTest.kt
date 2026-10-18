package app.folio.core.ink

import app.folio.core.model.Page
import app.folio.core.model.PageEdit
import app.folio.core.model.PageEditor
import app.folio.core.model.Stroke
import app.folio.core.model.ToolType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PartialEraserTest {
    private fun line(id: String, x0: Float = 0f, x1: Float = 100f, y: Float = 0f, tool: ToolType = ToolType.BALLPOINT) = Stroke(
        id = id, tool = tool, color = 0xFF000000, width = 2f,
        x = floatArrayOf(x0, x1), y = floatArrayOf(y, y), pressure = floatArrayOf(0.4f, 0.8f),
        tilt = floatArrayOf(0f, 0f), orientation = floatArrayOf(0f, 0f), t = intArrayOf(0, 100),
    )

    @Test fun erasingTheMiddleLeavesTwoPieces() {
        // A vertical eraser pass across a horizontal line, even with only two input points.
        val pieces = PartialEraser.erase(line("a"), 50f, -20f, 50f, 20f, radius = 5f)!!
        assertEquals(2, pieces.size)
        val (left, right) = pieces
        assertTrue(left.x.max() < 50f - 5f && left.x.max() > 40f, "left piece ends at the eraser edge: ${left.x.max()}")
        assertTrue(right.x.min() > 50f + 5f && right.x.min() < 60f, "right piece starts at the eraser edge: ${right.x.min()}")
        assertEquals(0f, left.x.first()); assertEquals(100f, right.x.last())
        assertTrue(pieces.none { it.id == "a" }, "pieces get new ids")
        // Channels are interpolated, not copied blindly.
        assertTrue(right.pressure.first() > 0.4f && right.pressure.first() < 0.8f)
    }

    @Test fun erasingAnEndLeavesOnePiece() {
        val pieces = PartialEraser.erase(line("a"), 100f, -10f, 100f, 10f, radius = 10f)!!
        assertEquals(1, pieces.size)
        assertTrue(pieces[0].x.max() < 90f)
    }

    @Test fun erasingEverythingLeavesNothing() {
        assertEquals(0, PartialEraser.erase(line("a", 0f, 10f), 5f, 0f, 5f, 0f, radius = 30f)!!.size)
    }

    @Test fun untouchedStrokeIsReturnedAsNull() {
        assertNull(PartialEraser.erase(line("a"), 50f, 40f, 60f, 40f, radius = 5f))
    }

    @Test fun splitIsOneUndoStepAndKeepsZOrder() {
        val a = line("a", y = 0f); val b = line("b", y = 50f); val c = line("c", y = 100f)
        val ed = PageEditor(Page(strokes = listOf(a, b, c)))
        val pieces = PartialEraser.erase(b, 50f, 30f, 50f, 70f, radius = 5f)!!
        ed.perform(PageEdit.SplitStrokes.of(ed.currentStrokes, mapOf("b" to pieces)))
        val ids = ed.currentStrokes.map { it.id }
        assertEquals("a", ids.first()); assertEquals("c", ids.last()); assertEquals(4, ids.size)
        ed.undo()
        assertEquals(listOf("a", "b", "c"), ed.currentStrokes.map { it.id })
        ed.redo()
        assertEquals(4, ed.currentStrokes.size)
    }

    @Test fun fullyErasedStrokeDisappearsAndUndoBringsItBack() {
        val a = line("a", 0f, 10f)
        val ed = PageEditor(Page(strokes = listOf(a)))
        ed.perform(PageEdit.SplitStrokes.of(ed.currentStrokes, mapOf("a" to emptyList())))
        assertTrue(ed.currentStrokes.isEmpty())
        ed.undo()
        assertEquals(listOf(a), ed.currentStrokes)
    }
}
