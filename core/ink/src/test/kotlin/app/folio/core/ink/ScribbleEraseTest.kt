package app.folio.core.ink

import app.folio.core.model.Stroke
import app.folio.core.model.ToolType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScribbleEraseTest {
    /** A zig-zag of [passes] passes across [x0]..[x1], drifting down from [y0] by [dy] a pass. */
    private fun zigzag(x0: Float, x1: Float, y0: Float, dy: Float, passes: Int): Pair<FloatArray, FloatArray> {
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        for (p in 0 until passes) for (k in 0..20) {
            val f = k / 20f
            xs += if (p % 2 == 0) x0 + (x1 - x0) * f else x1 - (x1 - x0) * f
            ys += y0 + dy * (p + f)
        }
        return xs.toFloatArray() to ys.toFloatArray()
    }

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, id: String) = Stroke(
        id, ToolType.BALLPOINT, 0xFF000000, 2f,
        FloatArray(21) { x0 + (x1 - x0) * it / 20f }, FloatArray(21) { y0 + (y1 - y0) * it / 20f },
        FloatArray(21) { 0.5f }, FloatArray(21), FloatArray(21), IntArray(21) { it * 8 }, 0,
    )

    @Test fun backAndForthIsAScribble() {
        val (x, y) = zigzag(100f, 220f, 100f, 6f, 6)
        assertTrue(ScribbleErase.isScribble(x, y, 20f))
        // Upright too.
        assertTrue(ScribbleErase.isScribble(y, x, 20f))
    }

    @Test fun anyDirectionCounts() {
        val (x, y) = zigzag(100f, 220f, 100f, 6f, 6)
        // The same scrawl turned to each diagonal and to in-between angles.
        for (deg in listOf(45, 135, 30, 60, 110, 160)) {
            val a = deg * PI.toFloat() / 180f
            val rx = FloatArray(x.size) { 300f + (x[it] - 160f) * cos(a) - (y[it] - 118f) * sin(a) }
            val ry = FloatArray(x.size) { 300f + (x[it] - 160f) * sin(a) + (y[it] - 118f) * cos(a) }
            assertTrue(ScribbleErase.isScribble(rx, ry, 20f), "scrawl at $deg degrees")
        }
        // A tall block of level passes (it drifts further down than it is wide).
        val (bx, by) = zigzag(100f, 200f, 100f, 28f, 8)
        assertTrue(ScribbleErase.isScribble(bx, by, 20f))
    }

    @Test fun writingAndShapesAreNot() {
        // A straight line, and a single there-and-back.
        val (lx, ly) = zigzag(100f, 220f, 100f, 0f, 1)
        assertFalse(ScribbleErase.isScribble(lx, ly, 20f))
        val (ux, uy) = zigzag(100f, 220f, 100f, 6f, 2)
        assertFalse(ScribbleErase.isScribble(ux, uy, 20f))
        // "mmmm": many small ups and downs while travelling along its longer side.
        val mx = FloatArray(200) { 100f + it * 1.2f }; val my = FloatArray(200) { 100f + 15f * sin(it * PI.toFloat() / 12f) }
        assertFalse(ScribbleErase.isScribble(mx, my, 20f))
        // A circle drawn three times round: only two turns back along either side per lap.
        val cx = FloatArray(60) { 100f + 50f * cos(it * 2 * PI.toFloat() / 60f) }; val cy = FloatArray(60) { 100f + 50f * sin(it * 2 * PI.toFloat() / 60f) }
        assertFalse(ScribbleErase.isScribble(cx, cy, 20f))
        // Too small to mean anything.
        val (sx, sy) = zigzag(100f, 106f, 100f, 0.5f, 6)
        assertFalse(ScribbleErase.isScribble(sx, sy, 20f))
    }

    @Test fun takesWhatItCoversAndLeavesWhatItOnlyCrosses() {
        val (x, y) = zigzag(100f, 220f, 100f, 6f, 6)
        val word = line(110f, 115f, 210f, 118f, "covered")
        val crossing = line(160f, 0f, 160f, 600f, "long line through")
        val elsewhere = line(400f, 400f, 500f, 400f, "elsewhere")
        assertEquals(setOf("covered"), ScribbleErase.targets(x, y, 3f, listOf(word, crossing, elsewhere)))
        assertTrue(ScribbleErase.targets(x, y, 3f, listOf(elsewhere)).isEmpty())
    }
}
