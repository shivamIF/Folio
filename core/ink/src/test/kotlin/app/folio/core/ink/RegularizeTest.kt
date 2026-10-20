package app.folio.core.ink

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RegularizeTest {
    private fun sides(c: FloatArray): List<Float> { val k = c.size / 2; return List(k) { i -> val j = (i + 1) % k; hypot(c[2 * j] - c[2 * i], c[2 * j + 1] - c[2 * i + 1]) } }
    private fun angles(c: FloatArray): List<Float> {
        val k = c.size / 2
        return List(k) { i ->
            val p = (i + k - 1) % k; val q = (i + 1) % k
            val ax = c[2 * p] - c[2 * i]; val ay = c[2 * p + 1] - c[2 * i + 1]; val bx = c[2 * q] - c[2 * i]; val by = c[2 * q + 1] - c[2 * i + 1]
            Math.toDegrees(acos(((ax * bx + ay * by) / (hypot(ax, ay) * hypot(bx, by))).toDouble())).toFloat()
        }
    }
    private fun edgeDeg(c: FloatArray, i: Int): Double { val k = c.size / 2; val j = (i + 1) % k; return Math.toDegrees(atan2((c[2 * j + 1] - c[2 * i + 1]).toDouble(), (c[2 * j] - c[2 * i]).toDouble())) }
    private fun hasLevelEdge(c: FloatArray) = (0 until c.size / 2).any { val d = ((edgeDeg(c, it) % 90) + 90) % 90; d < 0.01 || d > 89.99 }

    @Test fun aRoughSquareBecomesAPerfectSquare() {
        val c = Regularize.rectangle(floatArrayOf(100f, 102f, 297f, 98f, 304f, 290f, 97f, 306f))
        sides(c).forEach { assertEquals(sides(c)[0], it, 0.01f) }
        angles(c).forEach { assertEquals(90f, it, 0.01f) }
        assertTrue(hasLevelEdge(c), "nearly level becomes level")
    }

    @Test fun aClearlyLongRectangleStaysLongAndATiltedOneKeepsItsTilt() {
        val long = Regularize.rectangle(floatArrayOf(100f, 100f, 400f, 104f, 398f, 250f, 102f, 247f))
        val s = sides(long)
        assertTrue(s[0] > 1.8f * s[1], "a 2:1 rectangle isn't squared")
        angles(long).forEach { assertEquals(90f, it, 0.01f) }
        // 30° tilt, slightly crooked: right angles, tilt kept.
        val t = 30 * PI / 180
        fun p(x: Double, y: Double) = floatArrayOf((300 + x * cos(t) - y * sin(t)).toFloat(), (300 + x * sin(t) + y * cos(t)).toFloat())
        val tilted = Regularize.rectangle(p(-150.0, -80.0) + p(150.0, -84.0) + p(147.0, 80.0) + p(-152.0, 77.0))
        angles(tilted).forEach { assertEquals(90f, it, 0.01f) }
        val tilt = ((edgeDeg(tilted, 0) % 90) + 90) % 90
        assertEquals(30.0, tilt, 2.0)
    }

    @Test fun trianglesBecomeEquilateralRightOrIsoscelesWhenClose() {
        val eq = Regularize.triangle(floatArrayOf(200f, 100f, 290f, 262f, 104f, 255f))
        sides(eq).forEach { assertEquals(sides(eq)[0], it, 0.01f) }
        assertTrue(hasLevelEdge(eq), "base levelled")
        val right = Regularize.triangle(floatArrayOf(100f, 100f, 104f, 300f, 330f, 296f))
        assertEquals(90f, angles(right).max(), 0.01f)
        assertTrue(hasLevelEdge(right))
        val iso = Regularize.triangle(floatArrayOf(210f, 60f, 330f, 300f, 80f, 296f))
        val si = sides(iso)
        assertEquals(si[0], si[2], 0.01f) // the two sides at the apex
    }

    @Test fun aClearlyIrregularTriangleKeepsItsCorners() {
        val c = floatArrayOf(100f, 100f, 400f, 160f, 180f, 300f)
        val out = Regularize.triangle(c)
        for (i in c.indices) assertEquals(c[i], out[i], 0.01f)
    }

    @Test fun nearRegularPolygonsBecomeRegularIrregularOnesDont() {
        val rnd = Random(3)
        for (k in 5..8) {
            val c = FloatArray(2 * k) { j -> val i = j / 2; val a = -PI / 2 + 2 * PI * i / k + (rnd.nextDouble() - 0.5) * 0.12
                val r = 150 * (1 + (rnd.nextDouble() - 0.5) * 0.12); if (j % 2 == 0) (300 + r * cos(a)).toFloat() else (300 + r * sin(a)).toFloat() }
            val out = Regularize.polygon(c)
            sides(out).forEach { assertEquals(sides(out)[0], it, 0.05f, "k=$k sides") }
            angles(out).forEach { assertEquals(180f * (k - 2) / k, it, 0.05f, "k=$k angles") }
        }
        // An arrow-like pentagon: clearly not regular, corners kept (only near-level edges straightened).
        val arrow = floatArrayOf(100f, 200f, 300f, 200f, 300f, 140f, 420f, 250f, 300f, 360f)
        val out = Regularize.polygon(arrow)
        assertNotEquals(sides(out)[0], sides(out)[1], 1f)
        assertEquals(420f, out[6], 0.01f)
    }

    @Test fun snapAngleOnlyWithinTolerance() {
        assertEquals(0.0, Regularize.snapAngle(0.05, PI / 2, 0.1))
        assertEquals(0.3, Regularize.snapAngle(0.3, PI / 2, 0.1))
        assertEquals(abs(PI / 2), Regularize.snapAngle(PI / 2 - 0.05, PI / 2, 0.1), 1e-12)
    }
}
