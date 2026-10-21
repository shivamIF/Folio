package app.folio.core.ink

import app.folio.core.model.Rect
import app.folio.core.model.Stroke
import app.folio.core.model.TextBox
import app.folio.core.model.ToolType
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/** Pen-up reshaping of a finished stroke: "Straighten lines" and "Adjust thickness to text". */
object InkShaping {
    /**
     * [s] as a straight line from its first point to its last. The pressure, tilt and azimuth
     * profiles are resampled along it (by share of length), so a pen's tapered ends stay.
     */
    fun straight(s: Stroke): Stroke {
        if (s.size < 2) return s
        val x0 = s.x[0]; val y0 = s.y[0]; val x1 = s.x[s.size - 1]; val y1 = s.y[s.size - 1]
        val len = hypot(x1 - x0, y1 - y0)
        val n = max(2, ceil(len / STEP).toInt() + 1)
        // Arc length of the original, for resampling its channels.
        val acc = FloatArray(s.size)
        for (i in 1 until s.size) acc[i] = acc[i - 1] + hypot(s.x[i] - s.x[i - 1], s.y[i] - s.y[i - 1])
        val total = acc.last().coerceAtLeast(1e-3f)
        fun sample(ch: FloatArray, f: Float): Float {
            val d = f * total
            var k = 1
            while (k < s.size - 1 && acc[k] < d) k++
            val a = acc[k - 1]; val b = acc[k]
            val t = if (b > a) ((d - a) / (b - a)).coerceIn(0f, 1f) else 0f
            return ch[k - 1] + (ch[k] - ch[k - 1]) * t
        }
        val f = FloatArray(n) { it / (n - 1f) }
        return Stroke(
            s.id, s.tool, s.color, s.width,
            FloatArray(n) { x0 + (x1 - x0) * f[it] }, FloatArray(n) { y0 + (y1 - y0) * f[it] },
            FloatArray(n) { sample(s.pressure, f[it]) }, FloatArray(n) { sample(s.tilt, f[it]) },
            FloatArray(n) { sample(s.orientation, f[it]) }, IntArray(n) { (s.t.last() * f[it]).toInt() },
            s.startedAt, s.look,
        )
    }

    /**
     * The writing a highlighter or tape stroke [s] covers: the union of the ink (and text boxes)
     * whose middle lies inside its band. Null when it covers nothing.
     */
    fun covered(s: Stroke, strokes: List<Stroke>, index: StrokeIndex, texts: List<TextBox>): Rect? {
        if (s.size == 0) return null
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        for (i in 0 until s.size) { minY = minOf(minY, s.y[i]); maxY = maxOf(maxY, s.y[i]); minX = minOf(minX, s.x[i]); maxX = maxOf(maxX, s.x[i]) }
        val half = s.width / 2f
        val band = Rect(minX, minY - half, maxX, maxY + half)
        var out: Rect? = null
        for (i in index.query(band)) {
            val o = strokes[i]
            if (o.id == s.id || o.tool == ToolType.HIGHLIGHTER || o.tool == ToolType.TAPE) continue
            val b = inkBounds(o)
            val cy = (b.top + b.bottom) / 2
            // Its middle inside the band, and not a tall stroke the band merely crosses.
            if (cy < band.top || cy > band.bottom || b.height > band.height * 3f + 40f) continue
            if (b.right < band.left || b.left > band.right) continue
            out = out?.union(b) ?: b
        }
        for (t in texts) {
            val cy = (t.bounds.top + t.bounds.bottom) / 2
            if (t.bounds.intersects(band) && cy in band.top..band.bottom) {
                val line = Rect(t.x, t.y, t.x + t.width, t.y + minOf(t.height, t.fontSize * 1.25f))
                out = out?.union(line) ?: line
            }
        }
        return out
    }

    /** [s] resized to cover [target]'s height (a little more), and moved to its middle when it runs sideways. */
    fun fitTo(s: Stroke, target: Rect): Stroke {
        val w = (target.height * 1.12f).coerceIn(MIN_FIT, MAX_FIT)
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        for (i in 0 until s.size) { minY = minOf(minY, s.y[i]); maxY = maxOf(maxY, s.y[i]); minX = minOf(minX, s.x[i]); maxX = maxOf(maxX, s.x[i]) }
        val horizontal = (maxX - minX) > 2f * (maxY - minY)
        val dy = if (horizontal) (target.top + target.bottom) / 2 - (minY + maxY) / 2 else 0f
        return Stroke(
            s.id, s.tool, s.color, w, s.x.copyOf(), FloatArray(s.size) { s.y[it] + dy },
            s.pressure.copyOf(), s.tilt.copyOf(), s.orientation.copyOf(), s.t.copyOf(), s.startedAt, s.look,
        )
    }

    /** The extent of a stroke's points plus half its width (tighter than [Stroke.bounds]). */
    private fun inkBounds(s: Stroke): Rect {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in 0 until s.size) { l = minOf(l, s.x[i]); r = maxOf(r, s.x[i]); t = minOf(t, s.y[i]); b = maxOf(b, s.y[i]) }
        val h = s.width / 2f
        return Rect(l - h, t - h, r + h, b + h)
    }

    private const val STEP = 3f
    private const val MIN_FIT = 4f
    private const val MAX_FIT = 140f

    @Suppress("unused") private fun near(a: Float, b: Float) = abs(a - b) < 1e-3f
}
