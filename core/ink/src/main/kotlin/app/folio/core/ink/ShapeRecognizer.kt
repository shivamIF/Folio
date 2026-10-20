package app.folio.core.ink

import app.folio.core.model.Argb
import app.folio.core.model.Stroke
import app.folio.core.model.ToolType
import app.folio.core.model.newId
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** POLYGON: any other closed straight-edged shape; POLYLINE: open, straight segments (zig-zag, L, V). */
enum class ShapeKind { LINE, ELLIPSE, RECTANGLE, TRIANGLE, POLYGON, POLYLINE }

/** A clean shape as a polyline in page units; closed shapes end where they start. */
class Shape(val kind: ShapeKind, val xs: FloatArray, val ys: FloatArray) {
    val centerX: Float get() = (xs.min() + xs.max()) / 2f
    val centerY: Float get() = (ys.min() + ys.max()) / 2f

    /** Scaled by [f] around ([cx], [cy]). */
    fun scaled(f: Float, cx: Float, cy: Float) = Shape(kind, FloatArray(xs.size) { cx + (xs[it] - cx) * f }, FloatArray(ys.size) { cy + (ys[it] - cy) * f })

    /** As a regular stroke (even pressure) so it can be erased, selected and exported like ink. */
    fun toStroke(tool: ToolType, color: Argb, width: Float, pressure: Float, startedAt: Long = 0): Stroke {
        val n = xs.size
        return Stroke(
            newId(), tool, color, width, xs.copyOf(), ys.copyOf(), FloatArray(n) { pressure },
            FloatArray(n), FloatArray(n), IntArray(n) { it * 8 }, startedAt,
        )
    }
}

/**
 * Recognises a hand-drawn line, ellipse/circle, rectangle, triangle, any other polygon or
 * an open straight-segment polyline, so it can be snapped to a clean shape (hold the pen
 * still at the end of the stroke). Curvy strokes such as handwriting fit neither straight
 * edges nor an ellipse, and return null.
 *
 * Closed shapes are recognised even if the end stops a little short of the start or runs
 * past it ([CLOSE_GAP]). Shapes close to a regular one (square, circle, equilateral or
 * right triangle, regular polygon, level edges) are taken as meant to be exactly that
 * ([Regularize]); clearly irregular ones keep their corners with straightened edges.
 */
object ShapeRecognizer {
    /** Shapes smaller than this (page units, bounding-box diagonal) are left alone. */
    const val MIN_SIZE = 18f
    /** Mean distance from the straightened edges, as a fraction of the shape's size, still counted as straight. */
    private const val STRAIGHT_FIT = 0.03f
    private const val OPEN_STRAIGHT_FIT = 0.012f
    /** The largest gap (fraction of the shape's size) between a stroke's end and start still taken as closed. */
    const val CLOSE_GAP = 0.2f
    private const val LINE_SNAP_DEG = 6.0

    fun recognize(xs: FloatArray, ys: FloatArray): Shape? {
        val n = min(xs.size, ys.size)
        if (n < 3) return null
        var len = 0f
        for (i in 1 until n) len += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val w = maxX - minX; val h = maxY - minY
        val diag = hypot(w, h)
        if (diag < MIN_SIZE) return null

        // Line: everything close to the chord between the ends.
        val chord = hypot(xs[n - 1] - xs[0], ys[n - 1] - ys[0])
        if (chord > 0.7f * diag) {
            var dev = 0f
            for (i in 0 until n) dev = max(dev, StrokeGeometry.distanceToSegment(xs[i], ys[i], xs[0], ys[0], xs[n - 1], ys[n - 1]))
            if (dev <= max(0.05f * chord, 3f) && len < chord * 1.25f) return straightLine(xs[0], ys[0], xs[n - 1], ys[n - 1])
        }

        // Closed shapes: the path goes most of the way round and its end comes back near its
        // start: stopping a little short, or crossing over the start and running on (trimmed).
        val loop = closeLoop(xs, ys, n, len, diag)
        if (loop == null) {
            // Open polyline: a few straight segments (zig-zag, L, V, arrow strokes).
            // Tolerance near hand wobble, not shape size: curves then split into many gentle
            // turns (rejected), while straight runs keep only their real corners.
            val c = openCorners(xs, ys, n, epsilon = max(4f, 0.03f * diag))
            val segs = c.size / 2 - 1
            // Arcs and handwriting chop into short chords that fit loosely and turn gently;
            // a drawn polyline has straight runs and sharp turns.
            if (segs in 2..10 && meanEdgeDistance(xs, ys, n, c, closed = false) <= OPEN_STRAIGHT_FIT * diag && sharpTurns(c)) {
                return polyline(c)
            }
            return null
        }

        return closedShape(xs.copyOfRange(loop.first, loop.second + 1), ys.copyOfRange(loop.first, loop.second + 1))
    }

    /** Recognises a closed stroke (already trimmed to one loop). */
    private fun closedShape(xs: FloatArray, ys: FloatArray): Shape? {
        val n = xs.size
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val w = maxX - minX; val h = maxY - minY
        val diag = hypot(w, h)
        val corners = corners(xs, ys, n, epsilon = max(5f, 0.04f * diag))
        val k = corners.size / 2
        val polyErr = if (k >= 3) meanEdgeDistance(xs, ys, n, corners, closed = true) / diag else Float.MAX_VALUE
        // Ellipse: points at a nearly constant normalised radius from the box centre.
        val cx = (minX + maxX) / 2f; val cy = (minY + maxY) / 2f
        val rx = w / 2f; val ry = h / 2f
        var ellErr = Float.MAX_VALUE
        if (rx > 0f && ry > 0f) {
            var err = 0f
            for (i in 0 until n) {
                val dx = (xs[i] - cx) / rx; val dy = (ys[i] - cy) / ry
                err += abs(sqrt(dx * dx + dy * dy) - 1f)
            }
            ellErr = err / n * ((rx + ry) / 2f) / diag
        }
        // Whichever describes the stroke better: straight edges between corners, or an ellipse.
        val polygonFits = k in 3..12 && polyErr <= STRAIGHT_FIT
        if (polygonFits && polyErr <= ellErr) {
            return when {
                k == 3 -> polygon(ShapeKind.TRIANGLE, Regularize.triangle(corners))
                k == 4 && rightAngled(corners) -> polygon(ShapeKind.RECTANGLE, Regularize.rectangle(corners))
                else -> polygon(ShapeKind.POLYGON, Regularize.polygon(corners))
            }
        }
        if (ellErr <= 0.045f) {
            val circle = abs(rx - ry) / max(rx, ry) < 0.15f
            val r = (rx + ry) / 2f
            return ellipse(cx, cy, if (circle) r else rx, if (circle) r else ry)
        }
        return null
    }

    /** The closed part of a stroke that comes back to its start, as (first, last) indices; null if it doesn't. */
    private fun closeLoop(xs: FloatArray, ys: FloatArray, n: Int, len: Float, diag: Float): Pair<Int, Int>? {
        if (len < 1.7f * diag) return null
        // A slip of the hand, not an open shape: at most a fifth of the shape's size.
        val tolerance = max(12f, CLOSE_GAP * diag)
        // Where the first and last quarter of the path come closest (a crossing counts as 0).
        val cum = FloatArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        var headEnd = 0; while (headEnd < n - 1 && cum[headEnd + 1] <= 0.25f * len) headEnd++
        var tailStart = n - 1; while (tailStart > 0 && cum[tailStart - 1] >= 0.75f * len) tailStart--
        var best = Float.MAX_VALUE; var bi = 0; var bj = n - 1
        for (i in 0..headEnd) for (j in tailStart until n) {
            // Segment-to-point distance on the tail, so a fast stroke crossing between samples still counts.
            val d = if (j + 1 < n) StrokeGeometry.distanceToSegment(xs[i], ys[i], xs[j], ys[j], xs[j + 1], ys[j + 1]) else hypot(xs[i] - xs[j], ys[i] - ys[j])
            if (d < best - 1e-3f) { best = d; bi = i; bj = j }
        }
        if (best > tolerance) return null
        // Keep the whole stroke when it simply stopped short; trim only real overshoot.
        val gapEnds = hypot(xs[n - 1] - xs[0], ys[n - 1] - ys[0])
        return if (gapEnds <= best + 2f) 0 to n - 1 else bi to max(bj, bi + 2)
    }

    /** A line, exactly level, vertical or diagonal (45°) when it's within a few degrees of one. */
    private fun straightLine(x0: Float, y0: Float, x1: Float, y1: Float): Shape {
        val l = hypot(x1 - x0, y1 - y0)
        val a = atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())
        val snapped = Regularize.snapAngle(a, Math.PI / 4, Math.toRadians(LINE_SNAP_DEG))
        if (snapped == a) return line(x0, y0, x1, y1)
        val mx = (x0 + x1) / 2f; val my = (y0 + y1) / 2f
        val dx = (cos(snapped) * l / 2).toFloat(); val dy = (sin(snapped) * l / 2).toFloat()
        return line(mx - dx, my - dy, mx + dx, my + dy)
    }

    /** How far, on average, the stroke's points are from the straight edges through [c]. */
    private fun meanEdgeDistance(xs: FloatArray, ys: FloatArray, n: Int, c: FloatArray, closed: Boolean): Float {
        val k = c.size / 2
        val edges = if (closed) k else k - 1
        var total = 0f
        for (i in 0 until n) {
            var best = Float.MAX_VALUE
            for (e in 0 until edges) {
                val a = e; val b = (e + 1) % k
                best = min(best, StrokeGeometry.distanceToSegment(xs[i], ys[i], c[2 * a], c[2 * a + 1], c[2 * b], c[2 * b + 1]))
            }
            total += best
        }
        return total / n
    }

    /** Corners of an open stroke, ends included, minus near-straight vertices. */
    private fun openCorners(xs: FloatArray, ys: FloatArray, n: Int, epsilon: Float): FloatArray {
        val keep = BooleanArray(n)
        keep[0] = true; keep[n - 1] = true
        rdp(xs, ys, 0, n - 1, epsilon, keep, splitFirst = false)
        val pts = ArrayList<Float>()
        for (i in 0 until n) if (keep[i]) { pts += xs[i]; pts += ys[i] }
        var changed = true
        while (changed && pts.size / 2 > 2) {
            changed = false
            val c = pts.toFloatArray(); val k = c.size / 2
            for (i in 1 until k - 1) {
                val ax = c[2 * i - 2] - c[2 * i]; val ay = c[2 * i - 1] - c[2 * i + 1]
                val bx = c[2 * i + 2] - c[2 * i]; val by = c[2 * i + 3] - c[2 * i + 1]
                val d = hypot(ax, ay) * hypot(bx, by)
                val ang = if (d == 0f) 180.0 else Math.toDegrees(acos(((ax * bx + ay * by) / d).coerceIn(-1f, 1f).toDouble()))
                if (ang > 160.0) { pts.removeAt(2 * i); pts.removeAt(2 * i); changed = true; break }
            }
        }
        return pts.toFloatArray()
    }

    /** Every interior vertex of the open polyline [c] turns by at least 30°. */
    private fun sharpTurns(c: FloatArray): Boolean {
        val k = c.size / 2
        for (i in 1 until k - 1) {
            val ax = c[2 * i - 2] - c[2 * i]; val ay = c[2 * i - 1] - c[2 * i + 1]
            val bx = c[2 * i + 2] - c[2 * i]; val by = c[2 * i + 3] - c[2 * i + 1]
            val d = hypot(ax, ay) * hypot(bx, by)
            if (d == 0f) return false
            val ang = Math.toDegrees(acos(((ax * bx + ay * by) / d).coerceIn(-1f, 1f).toDouble()))
            if (ang > 150.0) return false
        }
        return true
    }

    /** Open polyline through [c], densified along each segment. */
    fun polyline(c: FloatArray): Shape {
        val k = c.size / 2
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        for (i in 0 until k - 1) {
            val ax = c[2 * i]; val ay = c[2 * i + 1]; val bx = c[2 * i + 2]; val by = c[2 * i + 3]
            val steps = max(1, (hypot(bx - ax, by - ay) / 4f).toInt())
            for (q in 0 until steps) { xs += ax + (bx - ax) * q / steps; ys += ay + (by - ay) * q / steps }
        }
        xs += c[2 * k - 2]; ys += c[2 * k - 1]
        return Shape(ShapeKind.POLYLINE, xs.toFloatArray(), ys.toFloatArray())
    }

    fun line(x0: Float, y0: Float, x1: Float, y1: Float): Shape {
        val steps = max(2, (hypot(x1 - x0, y1 - y0) / 4f).toInt())
        return Shape(ShapeKind.LINE, FloatArray(steps + 1) { x0 + (x1 - x0) * it / steps }, FloatArray(steps + 1) { y0 + (y1 - y0) * it / steps })
    }

    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, steps: Int = 96): Shape = Shape(
        ShapeKind.ELLIPSE,
        FloatArray(steps + 1) { cx + rx * cos(2 * Math.PI * it / steps).toFloat() },
        FloatArray(steps + 1) { cy + ry * sin(2 * Math.PI * it / steps).toFloat() },
    )

    /** Closed polygon through [corners] (x0, y0, x1, y1, …), densified along each edge. */
    fun polygon(kind: ShapeKind, corners: FloatArray): Shape {
        val k = corners.size / 2
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        for (i in 0 until k) {
            val ax = corners[2 * i]; val ay = corners[2 * i + 1]
            val bx = corners[2 * ((i + 1) % k)]; val by = corners[2 * ((i + 1) % k) + 1]
            val steps = max(1, (hypot(bx - ax, by - ay) / 4f).toInt())
            for (s in 0 until steps) { xs += ax + (bx - ax) * s / steps; ys += ay + (by - ay) * s / steps }
        }
        xs += corners[0]; ys += corners[1]
        return Shape(kind, xs.toFloatArray(), ys.toFloatArray())
    }

    private fun rightAngled(c: FloatArray): Boolean {
        for (i in 0 until 4) if (abs(angleAt(c, i, 4) - 90f) > 22f) return false
        return true
    }

    /** Interior angle (degrees) at corner [i] of a closed polygon with [k] corners. */
    private fun angleAt(c: FloatArray, i: Int, k: Int): Float {
        val p = (i + k - 1) % k; val q = (i + 1) % k
        val ax = c[2 * p] - c[2 * i]; val ay = c[2 * p + 1] - c[2 * i + 1]
        val bx = c[2 * q] - c[2 * i]; val by = c[2 * q + 1] - c[2 * i + 1]
        val d = hypot(ax, ay) * hypot(bx, by)
        if (d == 0f) return 180f
        return Math.toDegrees(acos(((ax * bx + ay * by) / d).coerceIn(-1f, 1f).toDouble())).toFloat()
    }

    /** Corners of a closed stroke: simplified outline, minus near-straight vertices. */
    private fun corners(xs: FloatArray, ys: FloatArray, n: Int, epsilon: Float): FloatArray {
        val keep = BooleanArray(n)
        keep[0] = true; keep[n - 1] = true
        rdp(xs, ys, 0, n - 1, epsilon, keep, splitFirst = true)
        val pts = ArrayList<Float>()
        for (i in 0 until n - 1) if (keep[i]) { pts += xs[i]; pts += ys[i] } // the last point closes the loop
        // Drop vertices where the outline barely turns (including where the pen started).
        var changed = true
        while (changed && pts.size / 2 > 3) {
            changed = false
            val c = pts.toFloatArray(); val k = c.size / 2
            for (i in 0 until k) if (angleAt(c, i, k) > 150f) {
                pts.removeAt(2 * i); pts.removeAt(2 * i); changed = true; break
            }
        }
        return pts.toFloatArray()
    }

    private fun rdp(xs: FloatArray, ys: FloatArray, a: Int, b: Int, eps: Float, keep: BooleanArray, splitFirst: Boolean) {
        if (b <= a + 1) return
        var far = -1; var dMax = 0f
        for (i in a + 1 until b) {
            val d = StrokeGeometry.distanceToSegment(xs[i], ys[i], xs[a], ys[a], xs[b], ys[b])
            if (d > dMax) { dMax = d; far = i }
        }
        // A closed loop's chord is tiny, so always split the first span at its farthest point.
        if (far >= 0 && (dMax > eps || (splitFirst && a == 0 && b == xs.size - 1))) {
            keep[far] = true
            rdp(xs, ys, a, far, eps, keep, false); rdp(xs, ys, far, b, eps, keep, false)
        }
    }
}
