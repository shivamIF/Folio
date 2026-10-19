package app.folio.core.ink

import app.folio.core.model.PageEdit
import app.folio.core.model.Page
import app.folio.core.model.PageEditor
import app.folio.core.model.Rect
import app.folio.core.model.Stroke
import app.folio.core.model.ToolType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LassoTest {
    private fun line(id: String, x0: Float, y0: Float, x1: Float, y1: Float, n: Int = 11) = Stroke(
        id = id, tool = ToolType.BALLPOINT, color = 0xFF000000, width = 4f,
        x = FloatArray(n) { x0 + (x1 - x0) * it / (n - 1) }, y = FloatArray(n) { y0 + (y1 - y0) * it / (n - 1) },
        pressure = FloatArray(n) { 0.5f }, tilt = FloatArray(n), orientation = FloatArray(n), t = IntArray(n) { it * 4 },
    )

    private val square = floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f)

    @Test fun anyTouchSelectsTheWholeStroke() {
        val inside = line("in", 10f, 50f, 90f, 50f)
        val outside = line("out", 200f, 50f, 300f, 50f)
        val tipInside = line("tip", 95f, 20f, 400f, 20f) // only its first point is inside the loop
        assertEquals(setOf("in", "tip"), Lasso.select(listOf(inside, outside, tipInside), square))
    }

    @Test fun loopOutlineBrushingInkSelectsIt() {
        // No point of this stroke is inside, but the loop's right edge (x = 100) crosses it.
        // Endpoints (80,-10) and (120,30) are both outside; the segment cuts the loop's top-right corner.
        val crossing = line("cross", 80f, -10f, 120f, 30f, n = 2)
        assertEquals(setOf("cross"), Lasso.select(listOf(crossing), square))
        val nearMiss = line("near", 130f, 50f, 200f, 50f)
        assertEquals(emptySet(), Lasso.select(listOf(nearMiss), square))
    }

    @Test fun cursiveWordWrittenInOneGoIsSelectedTogether() {
        // "A BOY": "A" separate; "BOY" one continuous stroke. Loop around A just touches B's start.
        val a = line("A", 10f, 50f, 40f, 50f)
        val boy = line("BOY", 85f, 50f, 300f, 50f)
        val selected = Lasso.select(listOf(a, boy), square)
        assertEquals(setOf("A", "BOY"), selected)
    }

    @Test fun rectangleSelection() {
        val r = Lasso.rectangle(0f, 0f, 100f, 100f)
        assertEquals(setOf("in"), Lasso.select(listOf(line("in", 10f, 10f, 20f, 20f), line("out", 300f, 0f, 400f, 0f)), r))
    }

    @Test fun degenerateLassoSelectsNothing() {
        assertTrue(Lasso.select(listOf(line("a", 0f, 0f, 1f, 1f)), floatArrayOf(0f, 0f, 5f, 5f)).isEmpty())
    }

    @Test fun pointInPolygonHandlesConcaveShapes() {
        // U shape: the notch between the arms is outside.
        val u = floatArrayOf(0f, 0f, 30f, 0f, 30f, 70f, 70f, 70f, 70f, 0f, 100f, 0f, 100f, 100f, 0f, 100f)
        assertTrue(Lasso.contains(u, 15f, 20f))
        assertTrue(!Lasso.contains(u, 50f, 20f))
        assertTrue(Lasso.contains(u, 50f, 90f))
    }

    @Test fun moveIsOneUndoableStep() {
        val a = line("a", 10f, 10f, 50f, 10f)
        val ed = PageEditor(Page(strokes = listOf(a, line("b", 0f, 200f, 50f, 200f))))
        val t = SelectionTransform(dx = 30f, dy = 40f)
        ed.perform(PageEdit.ReplaceStrokes(listOf(a), listOf(t.apply(a))))
        assertEquals(40f, ed.currentStrokes[0].x[0]); assertEquals(50f, ed.currentStrokes[0].y[0])
        assertEquals("b", ed.currentStrokes[1].id, "z-order unchanged")
        ed.undo()
        assertEquals(a, ed.currentStrokes[0])
    }

    @Test fun resizeScalesGeometryAndWidthFromTopLeft() {
        val a = line("a", 10f, 10f, 50f, 10f)
        val box = Rect(10f, 10f, 50f, 30f)
        val t = SelectionTransform.resize(box, 90f, 30f) // drag right edge to double the width
        assertEquals(2f, t.scale, 1e-4f)
        val r = t.apply(a)
        assertEquals(10f, r.x.first()); assertEquals(90f, r.x.last())
        assertEquals(8f, r.width)
    }

    @Test fun resizeIsClamped() {
        val box = Rect(0f, 0f, 100f, 100f)
        assertEquals(SelectionTransform.MIN_SCALE, SelectionTransform.resize(box, -500f, -500f).scale)
        assertEquals(SelectionTransform.MAX_SCALE, SelectionTransform.resize(box, 1e6f, 0f).scale)
    }

    @Test fun duplicateGetsNewIdsAndUndoesTogether() {
        val a = line("a", 0f, 0f, 10f, 0f); val b = line("b", 0f, 20f, 10f, 20f)
        val ed = PageEditor(Page(strokes = listOf(a, b)))
        val copies = listOf(a, b).map { it.transformed(1f, 0f, 0f, 20f, 20f, newId = true) }
        assertTrue(copies.none { it.id == "a" || it.id == "b" })
        ed.perform(PageEdit.AddStrokes(copies))
        assertEquals(4, ed.currentStrokes.size)
        ed.undo()
        assertEquals(listOf("a", "b"), ed.currentStrokes.map { it.id })
    }

    @Test fun recolourKeepsGeometry() {
        val a = line("a", 0f, 0f, 10f, 0f)
        val r = a.recolored(0xFFC62828)
        assertNotEquals(a.color, r.color)
        assertTrue(a.x.contentEquals(r.x)); assertEquals(a.id, r.id)
    }

    @Test fun boundsCoverAllSelected() {
        val b = Lasso.bounds(listOf(line("a", 0f, 0f, 10f, 0f), line("b", 50f, 60f, 70f, 80f)))!!
        assertTrue(b.left <= 0f && b.right >= 70f && b.top <= 0f && b.bottom >= 80f)
    }
}
