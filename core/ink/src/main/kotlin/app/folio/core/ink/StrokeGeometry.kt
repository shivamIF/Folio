package app.folio.core.ink

import app.folio.core.model.Stroke
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** Platform-neutral stroke geometry: outlines for filling and hit-testing for erasing. */
object StrokeGeometry {
    /**
     * Engine v2 ([BrushEngine]) draws strokes; false falls back to v1 everywhere (rollback
     * switch). Stored strokes are the same either way: only their rendering changes.
     */
    @Volatile var useV2 = true

    /** Outline of a stroke with the current engine. */
    fun outline(stroke: Stroke): FloatArray =
        if (useV2) mesh(stroke)?.outline() ?: FloatArray(0) else outlineV1(stroke)

    /** Engine v2 geometry of a stored stroke. */
    fun mesh(stroke: Stroke): StrokeMesh? = BrushEngine.mesh(
        stroke.x, stroke.y, stroke.pressure, stroke.tilt, stroke.orientation, stroke.size, stroke.width, BrushSpec.of(stroke.tool),
    )

    fun outlineV1(stroke: Stroke): FloatArray = outline(
        stroke.x, stroke.y, stroke.pressure, stroke.tilt, stroke.size, stroke.width, PenProfile.of(stroke.tool),
    )

    /**
     * Engine v1: builds a closed polygon (x0,y0,x1,y1,…) around a variable-width stroke,
     * with round caps at both ends. Fill it with the non-zero winding rule.
     * Also accepts raw arrays, so live (unfinished) strokes and predicted
     * points use the same code path.
     */
    fun outline(
        xs: FloatArray, ys: FloatArray, ps: FloatArray, tilts: FloatArray, count: Int,
        nominalWidth: Float, profile: PenProfile,
    ): FloatArray {
        if (count == 0) return FloatArray(0)
        val radii = FloatArray(count) { max(profile.widthAt(nominalWidth, ps[it], tilts[it]) / 2f, 0.3f) }
        if (count == 1 || pathLength(xs, ys, count) < 0.5f) return circle(xs[0], ys[0], radii.max())

        // Smooth the radius so a sudden pressure change doesn't leave a notch.
        for (i in 1 until count) radii[i] = 0.6f * radii[i] + 0.4f * radii[i - 1]

        val left = FloatArray(count * 2)
        val right = FloatArray(count * 2)
        for (i in 0 until count) {
            val a = if (i == 0) 0 else i - 1
            val b = if (i == count - 1) count - 1 else i + 1
            var dx = xs[b] - xs[a]; var dy = ys[b] - ys[a]
            val len = hypot(dx, dy)
            if (len < 1e-4f) { dx = 1f; dy = 0f } else { dx /= len; dy /= len }
            val nx = -dy * radii[i]; val ny = dx * radii[i]
            left[2 * i] = xs[i] + nx; left[2 * i + 1] = ys[i] + ny
            right[2 * i] = xs[i] - nx; right[2 * i + 1] = ys[i] - ny
        }

        val out = ArrayList<Float>(count * 4 + 40)
        for (i in 0 until count) { out += left[2 * i]; out += left[2 * i + 1] }
        val endAngle = atan2(ys[count - 1] - ys[max(0, count - 2)], xs[count - 1] - xs[max(0, count - 2)])
        cap(out, xs[count - 1], ys[count - 1], radii[count - 1], endAngle + PI.toFloat() / 2f)
        for (i in count - 1 downTo 0) { out += right[2 * i]; out += right[2 * i + 1] }
        val startAngle = atan2(ys[1] - ys[0], xs[1] - xs[0])
        cap(out, xs[0], ys[0], radii[0], startAngle - PI.toFloat() / 2f)
        return out.toFloatArray()
    }

    /** Half circle from [startAngle] sweeping clockwise by π around (cx, cy). */
    private fun cap(out: MutableList<Float>, cx: Float, cy: Float, r: Float, startAngle: Float) {
        val steps = 8
        for (s in 1 until steps) {
            val a = startAngle - PI.toFloat() * s / steps
            out += cx + r * cos(a); out += cy + r * sin(a)
        }
    }

    private fun circle(cx: Float, cy: Float, r: Float): FloatArray {
        val steps = 16
        return FloatArray(steps * 2) { i ->
            val a = 2 * PI * (i / 2) / steps
            if (i % 2 == 0) cx + r * cos(a).toFloat() else cy + r * sin(a).toFloat()
        }
    }

    private fun pathLength(xs: FloatArray, ys: FloatArray, count: Int): Float {
        var l = 0f
        for (i in 1 until count) l += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        return l
    }

    /** True if a circle of [radius] at (px, py) touches the stroke's inked area. */
    fun hits(stroke: Stroke, px: Float, py: Float, radius: Float): Boolean {
        val reach = radius + stroke.width * stroke.tool.inkReach
        if (!stroke.bounds().inflate(radius).contains(px, py)) return false
        if (stroke.size == 1) return hypot(stroke.x[0] - px, stroke.y[0] - py) <= reach
        for (i in 1 until stroke.size) {
            if (distanceToSegment(px, py, stroke.x[i - 1], stroke.y[i - 1], stroke.x[i], stroke.y[i]) <= reach) return true
        }
        return false
    }

    /** Ids of strokes touched by an eraser moving from (x0,y0) to (x1,y1). */
    fun hitAlong(strokes: List<Stroke>, x0: Float, y0: Float, x1: Float, y1: Float, radius: Float): Set<String> {
        val steps = max(1, (hypot(x1 - x0, y1 - y0) / max(radius, 1f)).toInt())
        val hit = LinkedHashSet<String>()
        for (s in 0..steps) {
            val f = s.toFloat() / steps
            val px = x0 + (x1 - x0) * f; val py = y0 + (y1 - y0) * f
            for (stroke in strokes) if (stroke.id !in hit && hits(stroke, px, py, radius)) hit += stroke.id
        }
        return hit
    }

    fun distanceToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }
}
