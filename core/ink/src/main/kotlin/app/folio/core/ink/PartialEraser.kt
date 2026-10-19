package app.folio.core.ink

import app.folio.core.model.Stroke
import app.folio.core.model.newId
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * Erases only the part of a stroke the eraser passes over, like a real
 * eraser: the stroke is cut into the pieces that remain.
 */
object PartialEraser {
    /**
     * Applies an eraser of [radius] swept from (x0,y0) to (x1,y1) to [stroke].
     * Returns null if the stroke is untouched (keep it as is), otherwise the
     * remaining pieces, each a new stroke with its own id (possibly none).
     */
    fun erase(stroke: Stroke, x0: Float, y0: Float, x1: Float, y1: Float, radius: Float): List<Stroke>? =
        erasePath(stroke, floatArrayOf(x0, y0, x1, y1), radius)

    /**
     * Applies a whole eraser path ([path] = x0, y0, x1, y1, …; one point is a dab) to
     * [stroke] in one pass: the stroke is densified once and each point is tested only
     * against the path segments near the stroke. Same result as applying the segments
     * one by one, without re-cutting pieces for every segment (that took ~0.3 s at pen-up
     * on a 7,000-stroke page).
     */
    fun erasePath(stroke: Stroke, path: FloatArray, radius: Float): List<Stroke>? {
        if (stroke.size == 0 || path.size < 2) return null
        val reach = radius + stroke.width * stroke.tool.inkReach
        val b = stroke.bounds().inflate(radius)
        // Segments whose box touches the stroke's box, with their own boxes grown by the
        // reach: a point outside a segment's box can't be within reach of it.
        val segCount = if (path.size == 2) 1 else path.size / 2 - 1
        val seg = FloatArray(segCount * 8) // ax, ay, bx, by, minX, minY, maxX, maxY
        var m = 0
        for (k in 0 until segCount) {
            val ax = path[2 * k]; val ay = path[2 * k + 1]
            val bx = if (path.size == 2) ax else path[2 * k + 2]; val by = if (path.size == 2) ay else path[2 * k + 3]
            if (maxOf(ax, bx) < b.left || minOf(ax, bx) > b.right || maxOf(ay, by) < b.top || minOf(ay, by) > b.bottom) continue
            val o = m * 8
            seg[o] = ax; seg[o + 1] = ay; seg[o + 2] = bx; seg[o + 3] = by
            seg[o + 4] = minOf(ax, bx) - reach; seg[o + 5] = minOf(ay, by) - reach
            seg[o + 6] = maxOf(ax, bx) + reach; seg[o + 7] = maxOf(ay, by) + reach
            m++
        }
        if (m == 0) return null

        val dense = densify(stroke, step = maxOf(0.5f, radius / 3f))
        val n = dense.x.size
        val keep = BooleanArray(n)
        var all = true
        for (i in 0 until n) {
            var k = true
            val px = dense.x[i]; val py = dense.y[i]
            for (q in 0 until m) {
                val o = q * 8
                if (px < seg[o + 4] || py < seg[o + 5] || px > seg[o + 6] || py > seg[o + 7]) continue
                if (StrokeGeometry.distanceToSegment(px, py, seg[o], seg[o + 1], seg[o + 2], seg[o + 3]) <= reach) { k = false; break }
            }
            keep[i] = k
            if (!k) all = false
        }
        if (all) return null

        val pieces = ArrayList<Stroke>()
        var i = 0
        while (i < n) {
            if (!keep[i]) { i++; continue }
            var j = i
            while (j + 1 < n && keep[j + 1]) j++
            if (j > i) pieces += dense.slice(i, j)
            i = j + 1
        }
        return pieces
    }

    private class Dense(
        val src: Stroke, val x: FloatArray, val y: FloatArray, val p: FloatArray, val tilt: FloatArray, val o: FloatArray, val t: IntArray,
    ) {
        fun slice(from: Int, to: Int): Stroke {
            val r = from..to
            return Stroke(
                id = newId(), tool = src.tool, color = src.color, width = src.width,
                x = x.sliceArray(r), y = y.sliceArray(r), pressure = p.sliceArray(r), tilt = tilt.sliceArray(r),
                orientation = o.sliceArray(r), t = t.sliceArray(r), startedAt = src.startedAt,
            )
        }
    }

    /** Inserts interpolated points so no segment is longer than [step]; cuts then land close to the eraser edge. */
    private fun densify(s: Stroke, step: Float): Dense {
        // Count first, then fill primitive arrays (no boxing: this runs for every stroke an eraser touches).
        var total = 1
        for (k in 0 until s.size - 1) total += maxOf(1, ceil(hypot(s.x[k + 1] - s.x[k], s.y[k + 1] - s.y[k]) / step).toInt())
        val xs = FloatArray(total); val ys = FloatArray(total); val ps = FloatArray(total)
        val ts = FloatArray(total); val os = FloatArray(total); val tm = IntArray(total)
        var w = 0
        fun add(k: Int, f: Float) {
            val k2 = minOf(k + 1, s.size - 1)
            xs[w] = s.x[k] + (s.x[k2] - s.x[k]) * f; ys[w] = s.y[k] + (s.y[k2] - s.y[k]) * f
            ps[w] = s.pressure[k] + (s.pressure[k2] - s.pressure[k]) * f
            ts[w] = s.tilt[k] + (s.tilt[k2] - s.tilt[k]) * f; os[w] = s.orientation[k] + (s.orientation[k2] - s.orientation[k]) * f
            tm[w] = (s.t[k] + (s.t[k2] - s.t[k]) * f).toInt()
            w++
        }
        for (k in 0 until s.size - 1) {
            val parts = maxOf(1, ceil(hypot(s.x[k + 1] - s.x[k], s.y[k + 1] - s.y[k]) / step).toInt())
            for (q in 0 until parts) add(k, q.toFloat() / parts)
        }
        add(s.size - 1, 0f)
        return Dense(s, xs, ys, ps, ts, os, tm)
    }
}
