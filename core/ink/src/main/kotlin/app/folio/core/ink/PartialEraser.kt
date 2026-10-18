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
    fun erase(stroke: Stroke, x0: Float, y0: Float, x1: Float, y1: Float, radius: Float): List<Stroke>? {
        if (stroke.size == 0) return null
        val reach = radius + stroke.width / 2f
        val b = stroke.bounds().inflate(radius)
        if (maxOf(x0, x1) < b.left || minOf(x0, x1) > b.right || maxOf(y0, y1) < b.top || minOf(y0, y1) > b.bottom) return null

        val dense = densify(stroke, step = maxOf(0.5f, radius / 3f))
        val n = dense.x.size
        val keep = BooleanArray(n) { StrokeGeometry.distanceToSegment(dense.x[it], dense.y[it], x0, y0, x1, y1) > reach }
        if (keep.all { it }) return null

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
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>(); val ps = ArrayList<Float>()
        val ts = ArrayList<Float>(); val os = ArrayList<Float>(); val tm = ArrayList<Int>()
        fun add(k: Int, f: Float) {
            val k2 = minOf(k + 1, s.size - 1)
            fun lerp(a: Float, b: Float) = a + (b - a) * f
            xs += lerp(s.x[k], s.x[k2]); ys += lerp(s.y[k], s.y[k2]); ps += lerp(s.pressure[k], s.pressure[k2])
            ts += lerp(s.tilt[k], s.tilt[k2]); os += lerp(s.orientation[k], s.orientation[k2])
            tm += (s.t[k] + (s.t[k2] - s.t[k]) * f).toInt()
        }
        for (k in 0 until s.size - 1) {
            val len = hypot(s.x[k + 1] - s.x[k], s.y[k + 1] - s.y[k])
            val parts = maxOf(1, ceil(len / step).toInt())
            for (q in 0 until parts) add(k, q.toFloat() / parts)
        }
        add(s.size - 1, 0f)
        return Dense(s, xs.toFloatArray(), ys.toFloatArray(), ps.toFloatArray(), ts.toFloatArray(), os.toFloatArray(), tm.toIntArray())
    }
}
