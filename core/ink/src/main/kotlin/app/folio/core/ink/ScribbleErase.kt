package app.folio.core.ink

import app.folio.core.model.Rect
import app.folio.core.model.Stroke
import kotlin.math.hypot

/**
 * Scribble to erase: a quick back-and-forth scrawl over existing ink removes that ink instead
 * of adding the scrawl. Two questions, both free of Android types:
 * - [isScribble]: is this stroke a scrawl (several passes back and forth across its own width)?
 * - [targets]: which strokes does it cover? If none, the scrawl is ordinary ink and is kept.
 */
object ScribbleErase {
    /** Fewest changes of direction: four reversals are five passes across. */
    const val MIN_REVERSALS = 4
    /** Each pass must span at least this share of the scrawl's extent, so letters (m, w, cursive loops) don't count. */
    private const val MIN_SWING = 0.45f
    /** A stroke goes when at least this share of its points lie under the scrawl. */
    const val MIN_COVERED = 0.6f

    /** A scrawl's passes sit close together: it drifts sideways at most this share of a pass's length per pass. */
    private const val MAX_DRIFT = 0.35f
    /** Directions a scrawl may run in: level, both diagonals, upright (unit vectors). */
    private val DIRECTIONS = floatArrayOf(1f, 0f, 0.7071f, 0.7071f, 0f, 1f, -0.7071f, 0.7071f)

    /**
     * True for a scrawl at least [minSize] across (page units), in any direction: level, upright
     * or diagonal. Along that direction its path runs back and forth at least [MIN_REVERSALS]
     * times, each pass crossing most of the scrawl, with the passes packed close together.
     * (Writing such as "mmm" also goes up and down many times, but travels far sideways each time.)
     */
    fun isScribble(xs: FloatArray, ys: FloatArray, minSize: Float): Boolean {
        val n = minOf(xs.size, ys.size)
        if (n < 10) return false
        if (hypot(xs.max() - xs.min(), ys.max() - ys.min()) < minSize) return false
        var len = 0f
        for (i in 1 until n) len += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        for (d in 0 until DIRECTIONS.size / 2) {
            val ux = DIRECTIONS[2 * d]; val uy = DIRECTIONS[2 * d + 1]
            // Along the direction (v) and across it (p).
            var vMin = Float.MAX_VALUE; var vMax = -Float.MAX_VALUE; var pMin = Float.MAX_VALUE; var pMax = -Float.MAX_VALUE
            for (i in 0 until n) {
                val v = xs[i] * ux + ys[i] * uy; val p = -xs[i] * uy + ys[i] * ux
                if (v < vMin) vMin = v; if (v > vMax) vMax = v; if (p < pMin) pMin = p; if (p > pMax) pMax = p
            }
            val extent = vMax - vMin
            if (extent < minSize * 0.7f || len < 3f * extent) continue
            // Reversals along the direction, counting a turn only after a real swing the other way.
            val swing = MIN_SWING * extent
            var reversals = 0; var dir = 0
            var lo = xs[0] * ux + ys[0] * uy; var hi = lo
            for (i in 1 until n) {
                val c = xs[i] * ux + ys[i] * uy
                if (c > hi) hi = c
                if (c < lo) lo = c
                when {
                    dir >= 0 && hi - c >= swing -> { if (dir > 0) reversals++; dir = -1; lo = c; hi = c }
                    dir <= 0 && c - lo >= swing -> { if (dir < 0) reversals++; dir = 1; lo = c; hi = c }
                }
            }
            if (reversals >= MIN_REVERSALS && (pMax - pMin) / (reversals + 1) <= MAX_DRIFT * extent) return true
        }
        return false
    }

    /**
     * Ids of the [strokes] the scrawl ([xs], [ys]) covers: touched by its path (within [reach],
     * page units) with at least [MIN_COVERED] of their points inside the scrawl's box. A long
     * line the scrawl merely crosses stays.
     */
    fun targets(xs: FloatArray, ys: FloatArray, reach: Float, strokes: List<Stroke>): Set<String> {
        val n = minOf(xs.size, ys.size)
        if (n == 0) return emptySet()
        val box = Rect(xs.min(), ys.min(), xs.max(), ys.max()).inflate(reach)
        val out = LinkedHashSet<String>()
        for (s in strokes) {
            if (s.size == 0 || !s.bounds().intersects(box)) continue
            var inside = 0
            for (i in 0 until s.size) if (box.contains(s.x[i], s.y[i])) inside++
            if (inside < MIN_COVERED * s.size) continue
            var touched = false
            var i = 0
            while (i < n && !touched) {
                if (StrokeGeometry.hits(s, xs[i], ys[i], reach)) touched = true
                // Also the midpoint to the next sample, so fast, sparse scrawls don't step over thin ink.
                else if (i + 1 < n && StrokeGeometry.hits(s, (xs[i] + xs[i + 1]) / 2f, (ys[i] + ys[i + 1]) / 2f, reach)) touched = true
                i++
            }
            if (touched) out += s.id
        }
        return out
    }
}
