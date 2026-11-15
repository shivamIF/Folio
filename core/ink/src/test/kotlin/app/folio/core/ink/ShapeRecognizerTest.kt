package app.folio.core.ink

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShapeRecognizerTest {
    private val rnd = Random(5)
    private fun jitter(v: Float, amount: Float) = v + (rnd.nextFloat() - 0.5f) * 2 * amount

    /** Hand-drawn-ish polyline through [pts], with wobble and an imperfect close. */
    private fun trace(vararg pts: Float, wobble: Float = 2f, stepLen: Float = 3f): Pair<FloatArray, FloatArray> {
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        for (i in 0 until pts.size / 2 - 1) {
            val ax = pts[2 * i]; val ay = pts[2 * i + 1]; val bx = pts[2 * i + 2]; val by = pts[2 * i + 3]
            val n = maxOf(2, (kotlin.math.hypot(bx - ax, by - ay) / stepLen).toInt())
            for (s in 0 until n) { xs += jitter(ax + (bx - ax) * s / n, wobble); ys += jitter(ay + (by - ay) * s / n, wobble) }
        }
        xs += pts[pts.size - 2]; ys += pts[pts.size - 1]
        return xs.toFloatArray() to ys.toFloatArray()
    }

    private fun ellipseTrace(cx: Float, cy: Float, rx: Float, ry: Float, start: Double = 0.3, sweep: Double = 2 * PI * 0.97): Pair<FloatArray, FloatArray> {
        val n = 120
        val xs = FloatArray(n) { jitter(cx + rx * cos(start + sweep * it / (n - 1)).toFloat(), 2.5f) }
        val ys = FloatArray(n) { jitter(cy + ry * sin(start + sweep * it / (n - 1)).toFloat(), 2.5f) }
        return xs to ys
    }

    @Test fun linesIncludingDiagonals() {
        val (x, y) = trace(100f, 100f, 400f, 180f)
        val s = ShapeRecognizer.recognize(x, y)!!
        assertEquals(ShapeKind.LINE, s.kind)
        assertEquals(400f, s.xs.last(), 3f); assertEquals(180f, s.ys.last(), 3f) // ~15°: kept as drawn
    }

    @Test fun rectanglesStartingMidSideAreLevelled() {
        val (x, y) = trace(250f, 100f, 400f, 103f, 402f, 300f, 99f, 298f, 101f, 101f, 245f, 104f)
        val s = ShapeRecognizer.recognize(x, y)!!
        assertEquals(ShapeKind.RECTANGLE, s.kind)
        assertEquals(100f, s.xs.min(), 6f); assertEquals(402f, s.xs.max(), 6f)
    }

    @Test fun trianglesCirclesAndEllipses() {
        val (tx, ty) = trace(200f, 100f, 320f, 300f, 80f, 300f, 196f, 104f)
        assertEquals(ShapeKind.TRIANGLE, ShapeRecognizer.recognize(tx, ty)!!.kind)
        val (cx, cy) = ellipseTrace(300f, 300f, 100f, 104f)
        val circle = ShapeRecognizer.recognize(cx, cy)!!
        assertEquals(ShapeKind.ELLIPSE, circle.kind)
        assertEquals(circle.xs.max() - circle.xs.min(), circle.ys.max() - circle.ys.min(), 1f) // snapped round
        val (ex, ey) = ellipseTrace(300f, 300f, 180f, 80f)
        val e = ShapeRecognizer.recognize(ex, ey)!!
        assertEquals(ShapeKind.ELLIPSE, e.kind)
        assertTrue(e.xs.max() - e.xs.min() > 2 * (e.ys.max() - e.ys.min()))
    }

    @Test fun handwritingAndTinyMarksAreLeftAlone() {
        // A cursive-like squiggle.
        val n = 200
        val sx = FloatArray(n) { it * 2f }; val sy = FloatArray(n) { 100f + 20f * sin(it / 6f) + 10f * cos(it / 2.3f) }
        assertNull(ShapeRecognizer.recognize(sx, sy)?.kind, "squiggle")
        // An open arc ("C").
        val (ax, ay) = ellipseTrace(300f, 300f, 100f, 100f, start = 0.8, sweep = PI * 1.4)
        assertNull(ShapeRecognizer.recognize(ax, ay)?.kind, "arc")
        // A dot.
        assertNull(ShapeRecognizer.recognize(floatArrayOf(10f, 11f, 12f), floatArrayOf(10f, 11f, 10f)))
    }

    @Test fun customPolygonsKeepTheirCorners() {
        // Pentagon, hexagon and a five-pointed star, drawn with wobble.
        fun regular(k: Int, r: Float, rIn: Float? = null): FloatArray {
            val pts = ArrayList<Float>()
            val m = if (rIn != null) 2 * k else k
            for (i in 0..m) {
                val a = -PI / 2 + 2 * PI * i / m
                val rr = if (rIn != null && i % 2 == 1) rIn else r
                pts += 300f + rr * cos(a).toFloat(); pts += 300f + rr * sin(a).toFloat()
            }
            return pts.toFloatArray()
        }
        for ((k, rIn) in listOf(5 to null, 6 to null, 5 to 60f)) {
            val (x, y) = trace(*regular(k, 150f, rIn))
            val s = ShapeRecognizer.recognize(x, y)
            assertEquals(ShapeKind.POLYGON, s?.kind, "k=$k star=${rIn != null}")
        }
        // An irregular quadrilateral (not right-angled) stays a 4-cornered polygon.
        val (qx, qy) = trace(100f, 100f, 400f, 130f, 300f, 330f, 160f, 270f, 98f, 104f)
        assertEquals(ShapeKind.POLYGON, ShapeRecognizer.recognize(qx, qy)!!.kind)
    }

    @Test fun aSquareThatStopsShortOrOvershootsStillCloses() {
        // Stops 15% of the size short of the start.
        val (sx, sy) = trace(100f, 100f, 300f, 102f, 302f, 300f, 98f, 298f, 100f, 130f)
        val short = ShapeRecognizer.recognize(sx, sy)!!
        assertEquals(ShapeKind.RECTANGLE, short.kind)
        assertEquals(short.xs.max() - short.xs.min(), short.ys.max() - short.ys.min(), 0.5f) // a square
        // Crosses the start and runs on along the first side.
        val (ox, oy) = trace(100f, 100f, 300f, 102f, 302f, 300f, 98f, 298f, 101f, 90f, 104f, 80f, 160f, 82f)
        assertEquals(ShapeKind.RECTANGLE, ShapeRecognizer.recognize(ox, oy)!!.kind)
        // A gap of 40% isn't a slip: an open three-sided bracket, never a rectangle.
        val (gx, gy) = trace(100f, 100f, 300f, 100f, 300f, 300f, 100f, 300f, 100f, 220f, wobble = 0.5f)
        assertNotEquals(ShapeKind.RECTANGLE, ShapeRecognizer.recognize(gx, gy)?.kind)
    }

    @Test fun nearlyLevelLinesBecomeLevel() {
        val (x, y) = trace(100f, 100f, 400f, 115f, wobble = 0.5f) // ~2.9°
        val s = ShapeRecognizer.recognize(x, y)!!
        assertEquals(s.ys.first(), s.ys.last(), 0.01f)
        val (dx, dy) = trace(100f, 100f, 400f, 210f, wobble = 0.5f) // ~20°: deliberate, kept
        val d = ShapeRecognizer.recognize(dx, dy)!!
        assertEquals(110f, d.ys.last() - d.ys.first(), 4f)
    }

    @Test fun openZigZagsBecomeStraightPolylines() {
        val (zx, zy) = trace(100f, 300f, 180f, 150f, 260f, 300f, 340f, 150f, 420f, 300f)
        val s = ShapeRecognizer.recognize(zx, zy)!!
        assertEquals(ShapeKind.POLYLINE, s.kind)
        assertEquals(420f, s.xs.last(), 3f)
        val (lx, ly) = trace(100f, 100f, 100f, 300f, 300f, 300f)
        assertEquals(ShapeKind.POLYLINE, ShapeRecognizer.recognize(lx, ly)!!.kind)
    }

    @Test fun autoFixLeavesHandwritingSizedAndOpenStrokesAlone() {
        // A letter-sized circle and line: recognised on a deliberate hold at default size, not above the on-screen minimum.
        val (ox, oy) = ellipseTrace(100f, 100f, 12f, 14f)
        assertNull(ShapeRecognizer.recognize(ox, oy, minSize = 60f, closeGap = 0.1f, strict = true))
        // A big zig-zag (like a written N or W) is an open polyline: never auto-fixed.
        val (zx, zy) = trace(100f, 300f, 100f, 100f, 250f, 300f, 250f, 100f)
        assertNull(ShapeRecognizer.recognize(zx, zy, minSize = 60f, closeGap = 0.1f, strict = true))
        // A "C": the ends are far apart, so it is not closed into an ellipse.
        val (cx, cy) = ellipseTrace(300f, 300f, 100f, 100f, sweep = 2 * PI * 0.8)
        assertNull(ShapeRecognizer.recognize(cx, cy, minSize = 60f, closeGap = 0.1f, strict = true))
        // A clearly drawn, closed circle and a long clean line still are.
        val (bx, by) = ellipseTrace(300f, 300f, 100f, 100f)
        assertEquals(ShapeKind.ELLIPSE, ShapeRecognizer.recognize(bx, by, minSize = 60f, closeGap = 0.1f, strict = true)!!.kind)
        val (lx, ly) = trace(100f, 100f, 400f, 180f, wobble = 1f)
        assertEquals(ShapeKind.LINE, ShapeRecognizer.recognize(lx, ly, minSize = 60f, closeGap = 0.1f, strict = true)!!.kind)
    }
}
