package app.folio.core.ink

import app.folio.core.model.Rect
import app.folio.core.model.Stroke
import app.folio.core.model.ToolType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SelectionRotateTest {
    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, id: String) = Stroke(
        id, ToolType.FINELINER, 0xFF000000, 2f, floatArrayOf(x0, x1), floatArrayOf(y0, y1), floatArrayOf(0.5f, 0.5f),
        FloatArray(2), FloatArray(2), intArrayOf(0, 10),
    )

    @Test fun dragFromTheTopHandleToTheRightRotatesAQuarterTurn() {
        val box = Rect(0f, 0f, 100f, 100f)
        // Handle above the centre, dragged level with the centre on the right: +90° (clockwise).
        val t = SelectionTransform.rotate(box, 50f, -30f, 130f, 50f)
        assertEquals(90f, t.rotation, 1e-3f)
        val (x, y) = t.map(50f, 0f)
        assertEquals(100f, x, 1e-3f)
        assertEquals(50f, y, 1e-3f)
    }

    @Test fun nearRightAnglesSnap() {
        val box = Rect(0f, 0f, 100f, 100f)
        val t = SelectionTransform.rotate(box, 50f, -30f, 131f, 54f) // ~92.8°
        assertEquals(90f, t.rotation, 1e-3f)
    }

    @Test fun rotationAppliesToStrokesAndBounds() {
        val s = line(0f, 50f, 100f, 50f, "h")
        val t = SelectionTransform(rotation = 90f, pivotX = 50f, pivotY = 50f)
        val r = t.apply(s)
        assertEquals(50f, r.x[0], 1e-3f); assertEquals(0f, r.y[0], 1e-3f)
        assertEquals(50f, r.x[1], 1e-3f); assertEquals(100f, r.y[1], 1e-3f)
        val b = t.mapRect(Rect(0f, 40f, 100f, 60f))
        assertEquals(40f, b.left, 1e-3f); assertEquals(0f, b.top, 1e-3f)
        assertEquals(60f, b.right, 1e-3f); assertEquals(100f, b.bottom, 1e-3f)
        assertTrue(!t.isIdentity)
    }

    @Test fun fullyEnclosedModeSkipsStrokesTheLoopOnlyCrosses() {
        val inside = line(20f, 20f, 40f, 40f, "in")
        val crossing = line(50f, 50f, 150f, 50f, "cross")
        val loop = Lasso.rectangle(0f, 0f, 100f, 100f)
        assertEquals(setOf("in", "cross"), Lasso.select(listOf(inside, crossing), loop))
        assertEquals(setOf("in"), Lasso.select(listOf(inside, crossing), loop, partial = false))
        assertTrue(Lasso.containsRect(loop, Rect(10f, 10f, 90f, 90f)))
        assertTrue(!Lasso.containsRect(loop, Rect(10f, 10f, 190f, 90f)))
    }
}
