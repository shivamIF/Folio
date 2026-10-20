package app.folio.core.ink

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin

/**
 * Turns a hand-drawn polygon's corners into the shape the user most likely meant.
 *
 * The rule is "small error = intent, big difference = design": if a shape is within a hand's
 * typical error of a regular one (equal sides, right or equal angles, level edges), it
 * becomes exactly that; if it's clearly different, the user's corners are kept. All
 * functions take and return corners as x0, y0, x1, y1, … in drawing order.
 */
object Regularize {
    /** Sides this close (fraction of their mean) count as meant to be equal. */
    private const val EQUAL_SIDES = 0.12f
    /** Angles this close (degrees) count as meant to be equal / right. */
    private const val EQUAL_ANGLE = 12f
    private const val RIGHT_ANGLE = 10f
    /** Shapes this close (degrees) to level or vertical are straightened. */
    private const val LEVEL_DEG = 10.0
    /** Single edges this close to level or vertical are straightened. */
    private const val EDGE_LEVEL_DEG = 6.0

    /** [angle] moved to the nearest multiple of [step] if it's within [tolerance] of it (radians); otherwise unchanged. */
    fun snapAngle(angle: Double, step: Double, tolerance: Double): Double {
        val nearest = round(angle / step) * step
        return if (abs(angle - nearest) <= tolerance) nearest else angle
    }

    /** A true rectangle (square if the sides are nearly equal), level if it's nearly level. */
    fun rectangle(c: FloatArray): FloatArray {
        val cx = (c[0] + c[2] + c[4] + c[6]) / 4f; val cy = (c[1] + c[3] + c[5] + c[7]) / 4f
        // Orientation: the mean edge direction, folded to 90° (angles ×4 on the unit circle).
        var sx = 0.0; var sy = 0.0
        for (i in 0 until 4) {
            val a = edgeAngle(c, i, 4)
            val l = edgeLength(c, i, 4).toDouble()
            sx += l * cos(4 * a); sy += l * sin(4 * a)
        }
        val theta = snapAngle(atan2(sy, sx) / 4, PI / 2, Math.toRadians(LEVEL_DEG + 2))
        val ux = cos(theta).toFloat(); val uy = sin(theta).toFloat()
        val vx = -uy; val vy = ux
        // Side lengths: each edge's extent along the two axes (opposite sides averaged).
        var a = 0f; var b = 0f
        for (i in 0 until 4) {
            val ex = c[2 * ((i + 1) % 4)] - c[2 * i]; val ey = c[2 * ((i + 1) % 4) + 1] - c[2 * i + 1]
            a += abs(ex * ux + ey * uy); b += abs(ex * vx + ey * vy)
        }
        a /= 2f; b /= 2f
        if (abs(a - b) <= EQUAL_SIDES * (a + b) / 2f) { val m = (a + b) / 2f; a = m; b = m }
        // Keep the drawing direction (clockwise or not) and start at the corner nearest the first one.
        val sign = if (area(c, 4) >= 0) 1f else -1f
        val out = FloatArray(8)
        val us = floatArrayOf(-1f, 1f, 1f, -1f); val vs = floatArrayOf(-1f, -1f, 1f, 1f)
        for (i in 0 until 4) {
            out[2 * i] = cx + us[i] * a / 2 * ux + vs[i] * sign * b / 2 * vx
            out[2 * i + 1] = cy + us[i] * a / 2 * uy + vs[i] * sign * b / 2 * vy
        }
        return rotateToNearest(out, c[0], c[1])
    }

    /** Equilateral, right or isosceles when nearly so; edges near level/vertical straightened. */
    fun triangle(c: FloatArray): FloatArray {
        val l = FloatArray(3) { edgeLength(c, it, 3) }
        val ang = FloatArray(3) { interiorAngle(c, it, 3) }
        val mean = l.average().toFloat()
        if (l.all { abs(it - mean) <= EQUAL_SIDES * mean } && ang.all { abs(it - 60f) <= EQUAL_ANGLE }) return regular(c, 3)
        val right = (0 until 3).firstOrNull { abs(ang[it] - 90f) <= RIGHT_ANGLE }
        if (right != null) return rightTriangle(c, right)
        // Isosceles: the two sides meeting at the apex nearly equal.
        for (apex in 0 until 3) {
            val s1 = l[(apex + 2) % 3]; val s2 = l[apex] // edges into and out of the apex
            if (abs(s1 - s2) <= 0.1f * (s1 + s2) / 2f) return isosceles(c, apex)
        }
        return levelEdges(c, 3)
    }

    /** Regular polygon when sides and angles are nearly equal; otherwise edges near level/vertical straightened. */
    fun polygon(c: FloatArray): FloatArray {
        val k = c.size / 2
        if (k >= 5) {
            val l = FloatArray(k) { edgeLength(c, it, k) }
            val mean = l.average().toFloat()
            val expected = 180f * (k - 2) / k
            val sidesEqual = l.all { abs(it - mean) <= 0.25f * mean }
            val anglesEqual = (0 until k).all { abs(interiorAngle(c, it, k) - expected) <= 15f }
            if (sidesEqual && anglesEqual && area(c, k).let { abs(it) } > 0f && isConvex(c, k)) return regular(c, k)
        }
        return levelEdges(c, k)
    }

    // ------------------------------------------------------------------ shapes

    /** A regular k-gon matching [c]'s centre, size, rotation and drawing direction; level if nearly so. */
    private fun regular(c: FloatArray, k: Int): FloatArray {
        var cx = 0f; var cy = 0f
        for (i in 0 until k) { cx += c[2 * i]; cy += c[2 * i + 1] }
        cx /= k; cy /= k
        var r = 0f
        for (i in 0 until k) r += hypot(c[2 * i] - cx, c[2 * i + 1] - cy)
        r /= k
        val dir = if (area(c, k) >= 0) 1.0 else -1.0
        val step = 2 * PI / k
        // Phase: circular mean of each corner's angle minus its ideal offset.
        var sx = 0.0; var sy = 0.0
        for (i in 0 until k) {
            val a = atan2((c[2 * i + 1] - cy).toDouble(), (c[2 * i] - cx).toDouble()) - dir * step * i
            sx += cos(a); sy += sin(a)
        }
        var phase = atan2(sy, sx)
        // Level: if some edge is within a few degrees of level or vertical, rotate so it's
        // exact (the smallest such turn). Edge i points along phase + dir·step·(i + ½) + 90°.
        var turnBy = Double.MAX_VALUE
        for (i in 0 until k) {
            val e = phase + dir * step * (i + 0.5) + PI / 2
            val t = snapAngle(e, PI / 2, Math.toRadians(LEVEL_DEG))
            if (t != e && abs(t - e) < abs(turnBy)) turnBy = t - e
            if (t == e && abs(e - round(e / (PI / 2)) * (PI / 2)) < 1e-9) { turnBy = 0.0; break }
        }
        if (turnBy != Double.MAX_VALUE) phase += turnBy
        val out = FloatArray(2 * k)
        for (i in 0 until k) {
            val a = phase + dir * step * i
            out[2 * i] = cx + (r * cos(a)).toFloat(); out[2 * i + 1] = cy + (r * sin(a)).toFloat()
        }
        return out
    }

    /** Exactly 90° at corner [v]; legs equal if nearly so; level if nearly so. */
    private fun rightTriangle(c: FloatArray, v: Int): FloatArray {
        val p = (v + 2) % 3; val q = (v + 1) % 3
        val vx = c[2 * v]; val vy = c[2 * v + 1]
        var l1 = hypot(c[2 * p] - vx, c[2 * p + 1] - vy); var l2 = hypot(c[2 * q] - vx, c[2 * q + 1] - vy)
        if (abs(l1 - l2) <= EQUAL_SIDES * (l1 + l2) / 2f) { val m = (l1 + l2) / 2f; l1 = m; l2 = m }
        val a1 = atan2((c[2 * p + 1] - vy).toDouble(), (c[2 * p] - vx).toDouble())
        val a2 = atan2((c[2 * q + 1] - vy).toDouble(), (c[2 * q] - vx).toDouble())
        // Legs at ±45° from the bisector, so each moves by half the error.
        var d = a2 - a1; while (d > PI) d -= 2 * PI; while (d < -PI) d += 2 * PI
        val turn = if (d >= 0) 1.0 else -1.0
        var leg1 = a1 + d / 2 - turn * PI / 4
        leg1 = snapAngle(leg1, PI / 2, Math.toRadians(LEVEL_DEG))
        val leg2 = leg1 + turn * PI / 2
        val out = c.copyOf()
        out[2 * p] = vx + (l1 * cos(leg1)).toFloat(); out[2 * p + 1] = vy + (l1 * sin(leg1)).toFloat()
        out[2 * q] = vx + (l2 * cos(leg2)).toFloat(); out[2 * q + 1] = vy + (l2 * sin(leg2)).toFloat()
        return out
    }

    /** Apex moved onto the base's perpendicular bisector (same height); base level if nearly so. */
    private fun isosceles(c: FloatArray, apex: Int): FloatArray {
        val b1 = (apex + 1) % 3; val b2 = (apex + 2) % 3
        var bx1 = c[2 * b1]; var by1 = c[2 * b1 + 1]; var bx2 = c[2 * b2]; var by2 = c[2 * b2 + 1]
        val mx = (bx1 + bx2) / 2f; val my = (by1 + by2) / 2f
        val half = hypot(bx2 - bx1, by2 - by1) / 2f
        val base = snapAngle(atan2((by2 - by1).toDouble(), (bx2 - bx1).toDouble()), PI / 2, Math.toRadians(LEVEL_DEG))
        val ux = cos(base).toFloat(); val uy = sin(base).toFloat()
        bx1 = mx - ux * half; by1 = my - uy * half; bx2 = mx + ux * half; by2 = my + uy * half
        // Height: the apex's signed distance from the base line.
        val ax = c[2 * apex] - mx; val ay = c[2 * apex + 1] - my
        val height = ax * -uy + ay * ux
        val out = c.copyOf()
        out[2 * apex] = mx - uy * height; out[2 * apex + 1] = my + ux * height
        out[2 * b1] = bx1; out[2 * b1 + 1] = by1; out[2 * b2] = bx2; out[2 * b2 + 1] = by2
        return out
    }

    /** Edges within a few degrees of level get both ends at their mean height (vertical: mean x). */
    private fun levelEdges(c: FloatArray, k: Int): FloatArray {
        val out = c.copyOf()
        val tol = Math.toRadians(EDGE_LEVEL_DEG)
        for (i in 0 until k) {
            val j = (i + 1) % k
            val a = atan2((out[2 * j + 1] - out[2 * i + 1]).toDouble(), (out[2 * j] - out[2 * i]).toDouble())
            val snapped = snapAngle(a, PI / 2, tol)
            if (snapped == a) continue
            if (abs(sin(snapped)) < 0.5) { val m = (out[2 * i + 1] + out[2 * j + 1]) / 2f; out[2 * i + 1] = m; out[2 * j + 1] = m }
            else { val m = (out[2 * i] + out[2 * j]) / 2f; out[2 * i] = m; out[2 * j] = m }
        }
        return out
    }

    // ------------------------------------------------------------------ geometry

    private fun edgeLength(c: FloatArray, i: Int, k: Int): Float {
        val j = (i + 1) % k
        return hypot(c[2 * j] - c[2 * i], c[2 * j + 1] - c[2 * i + 1])
    }

    private fun edgeAngle(c: FloatArray, i: Int, k: Int): Double {
        val j = (i + 1) % k
        return atan2((c[2 * j + 1] - c[2 * i + 1]).toDouble(), (c[2 * j] - c[2 * i]).toDouble())
    }

    /** Interior angle (degrees) at corner [i]. */
    private fun interiorAngle(c: FloatArray, i: Int, k: Int): Float {
        val p = (i + k - 1) % k; val q = (i + 1) % k
        val ax = c[2 * p] - c[2 * i]; val ay = c[2 * p + 1] - c[2 * i + 1]
        val bx = c[2 * q] - c[2 * i]; val by = c[2 * q + 1] - c[2 * i + 1]
        val d = hypot(ax, ay) * hypot(bx, by)
        if (d == 0f) return 180f
        return Math.toDegrees(acos(((ax * bx + ay * by) / d).coerceIn(-1f, 1f).toDouble())).toFloat()
    }

    /** Signed area (shoelace); the sign gives the drawing direction. */
    private fun area(c: FloatArray, k: Int): Float {
        var s = 0f
        for (i in 0 until k) { val j = (i + 1) % k; s += c[2 * i] * c[2 * j + 1] - c[2 * j] * c[2 * i + 1] }
        return s / 2f
    }

    private fun isConvex(c: FloatArray, k: Int): Boolean {
        var sign = 0
        for (i in 0 until k) {
            val a = (i + k - 1) % k; val b = (i + 1) % k
            val cross = (c[2 * i] - c[2 * a]) * (c[2 * b + 1] - c[2 * i + 1]) - (c[2 * i + 1] - c[2 * a + 1]) * (c[2 * b] - c[2 * i])
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s != 0) { if (sign == 0) sign = s else if (s != sign) return false }
        }
        return true
    }

    /** The same corners, starting from the one nearest ([x], [y]). */
    private fun rotateToNearest(c: FloatArray, x: Float, y: Float): FloatArray {
        val k = c.size / 2
        var best = 0; var bd = Float.MAX_VALUE
        for (i in 0 until k) { val d = hypot(c[2 * i] - x, c[2 * i + 1] - y); if (d < bd) { bd = d; best = i } }
        return FloatArray(2 * k) { c[(2 * best + it) % (2 * k)] }
    }
}
