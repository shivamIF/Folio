package app.folio.core.ink

import app.folio.core.model.ToolType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class CapStyle { ROUND, SQUARE }

/**
 * Per-point darkness (pigment laid down) from pressure and lean:
 * `(min + (1 − min) · p^gamma) · (1 − leanFade · lean)`. Original tunings.
 */
data class Shade(val min: Float, val gamma: Float, val leanFade: Float) {
    companion object {
        /** Graphite: pressing darkens a lot; the lead's side is softer. */
        val GRAPHITE = Shade(0.28f, 0.8f, 0.45f)
        /** Airbrush: pressure opens the valve; a light touch is a faint mist. */
        val SPRAY = Shade(0.2f, 1.3f, 0f)
    }
}

/**
 * How a tool shapes its stroke (engine v2). Colour, grain and blending are the renderer's job;
 * this is geometry plus per-point shading. Original tunings, not taken from another product.
 */
data class BrushSpec(
    val profile: PenProfile,
    val cap: CapStyle = CapStyle.ROUND,
    /** Direction of a flat nib's edge (radians, page axes, y down). Only used when [nibDepth] > 0. */
    val nibAngle: Float = 0f,
    /** Flat-nib strength: moving along the nib edge narrows the line to (1 − depth) of its width. */
    val nibDepth: Float = 0f,
    /**
     * Tilting the pen past the natural writing angle stretches its tip into an ellipse along the
     * pen's azimuth (reported orientation), up to (1 + gain) × the width. The line widens when it
     * moves across that axis and stays narrow along it, like the side of a pencil lead.
     * Without tilt data the footprint stays round: nothing is invented.
     */
    val footprintGain: Float = 0f,
    /** Per-point darkness from pressure and lean, or null for solid ink: see [StrokeMesh.shade]. */
    val shade: Shade? = null,
    /** Edge softness the renderer adds (a blur), in nominal widths. Counted in [reach]. */
    val soften: Float = 0f,
) {
    val shading: Boolean get() = shade != null

    /** How far ink can reach from the centreline, in nominal widths (matches [ToolType.inkReach]). */
    val reach: Float get() =
        0.5f * (1f + footprintGain) * (if (cap == CapStyle.SQUARE) 1.42f else 1f) + soften

    companion object {
        /** Fountain/marker/highlighter nib edge runs from lower left to upper right. */
        private const val NIB = (-PI / 4).toFloat()

        fun of(tool: ToolType): BrushSpec = when (tool) {
            // Clean ink with only a little pressure response.
            ToolType.BALLPOINT -> BrushSpec(PenProfile(0.8f, 1f, 0f, 1f))
            // Thin on light pressure (so pen-down and lift-off taper), with a subtle italic nib.
            ToolType.FOUNTAIN -> BrushSpec(PenProfile(0.3f, 1.1f, 0f, 1f), nibAngle = NIB, nibDepth = 0.32f)
            // Hairline to full width: a steep pressure curve; leaning spreads the tip.
            ToolType.BRUSH -> BrushSpec(PenProfile(0.06f, 1.7f, 0f, 1f), footprintGain = 0.7f)
            // Even, precise line: pressure barely matters.
            ToolType.FINELINER -> BrushSpec(PenProfile(0.92f, 1f, 0f, 1f))
            // Graphite: wider and lighter on its side, darker when pressed.
            ToolType.PENCIL -> BrushSpec(PenProfile(0.55f, 1f, 0f, 1f), footprintGain = 2.2f, shade = Shade.GRAPHITE)
            // Broad chisel tip, square ends, full saturation.
            ToolType.MARKER -> BrushSpec(PenProfile(0.85f, 1f, 0f, 1f), CapStyle.SQUARE, NIB, 0.35f)
            // Wide chisel, constant width (blended by multiply in the renderer).
            ToolType.HIGHLIGHTER -> BrushSpec(PenProfile(1f, 1f, 0f, 1f, pressureSensitive = false), CapStyle.SQUARE, NIB, 0.3f)
            // Spray: even core, edges blurred away by the renderer, density from pressure.
            ToolType.AIRBRUSH -> BrushSpec(PenProfile(0.7f, 1f, 0f, 1f), shade = Shade.SPRAY, soften = 0.5f)
        }
    }
}

/**
 * A stroke's shape as paired left/right edge points along the centreline, plus caps.
 * Fill [outline] with the non-zero rule, or draw [triangles] (for per-point [shade]).
 */
class StrokeMesh internal constructor(
    /** x,y pairs; left[k] and right[k] are opposite edges at the same place. */
    val left: FloatArray,
    val right: FloatArray,
    /** Darkness 0..1 per pair (1 for tools without shading). */
    val shade: FloatArray,
    val pairs: Int,
    /** End cap points from left[last] round to right[last] (exclusive), and the centre they fan from. */
    val endCap: FloatArray,
    val endX: Float, val endY: Float,
    /** Start cap points from right[0] round to left[0] (exclusive). */
    val startCap: FloatArray,
    val startX: Float, val startY: Float,
) {
    /** Closed polygon x0,y0,x1,y1,… around the stroke. */
    fun outline(): FloatArray {
        val out = FloatArray(4 * pairs + endCap.size + startCap.size)
        var k = 0
        for (i in 0 until pairs) { out[k++] = left[2 * i]; out[k++] = left[2 * i + 1] }
        endCap.copyInto(out, k); k += endCap.size
        for (i in pairs - 1 downTo 0) { out[k++] = right[2 * i]; out[k++] = right[2 * i + 1] }
        startCap.copyInto(out, k)
        return out
    }

    /** Triangle list: [verts] as x,y and a [shadeOf] value per vertex. */
    class Triangles(val verts: FloatArray, val shadeOf: FloatArray) { val count get() = shadeOf.size }

    fun triangles(): Triangles {
        val n = 6 * max(0, pairs - 1) + 3 * (endCap.size / 2 + 1) + 3 * (startCap.size / 2 + 1)
        val v = FloatArray(2 * n); val s = FloatArray(n)
        var k = 0
        fun put(x: Float, y: Float, sh: Float) { v[2 * k] = x; v[2 * k + 1] = y; s[k] = sh; k++ }
        for (i in 0 until pairs - 1) {
            val a = shade[i]; val b = shade[i + 1]
            put(left[2 * i], left[2 * i + 1], a); put(right[2 * i], right[2 * i + 1], a); put(left[2 * i + 2], left[2 * i + 3], b)
            put(right[2 * i], right[2 * i + 1], a); put(right[2 * i + 2], right[2 * i + 3], b); put(left[2 * i + 2], left[2 * i + 3], b)
        }
        fun fan(cx: Float, cy: Float, ax: Float, ay: Float, cap: FloatArray, bx: Float, by: Float, sh: Float) {
            var px = ax; var py = ay
            var j = 0
            while (j <= cap.size) {
                val qx = if (j < cap.size) cap[j] else bx; val qy = if (j < cap.size) cap[j + 1] else by
                put(cx, cy, sh); put(px, py, sh); put(qx, qy, sh)
                px = qx; py = qy; j += 2
            }
        }
        val e = pairs - 1
        fan(endX, endY, left[2 * e], left[2 * e + 1], endCap, right[2 * e], right[2 * e + 1], shade[e])
        fan(startX, startY, right[0], right[1], startCap, left[0], left[1], shade[0])
        return Triangles(v.copyOf(2 * k), s.copyOf(k))
    }
}

/**
 * Engine v2: turns stored stroke samples into a [StrokeMesh].
 *
 * 1. The first and last pressure samples are de-spiked (a pen landing hard leaves a blob).
 * 2. A centripetal Catmull-Rom spline through the samples, subdivided to sub-unit steps, so
 *    loops and bowls are round instead of faceted.
 * 3. Width per point from pressure, flat-nib direction and the tilt footprint, then smoothed
 *    forward *and* backward along the arc, so it changes without lagging behind the pen.
 * 4. Edges offset along the normals; sharp turns get round joins.
 * 5. Round or square caps. Ends taper only where real pressure was light, so pieces cut by
 *    the partial eraser keep the width they had.
 *
 * Pure function of the stored samples: a stroke looks the same after save and reopen.
 */
object BrushEngine {
    /** Upper bound on spline subdivisions per input segment. */
    private const val MAX_SUB = 12
    /** Curve smoothness: at most this much turn (radians) between dense points. */
    private const val MAX_TURN_PER_STEP = 0.07f

    fun mesh(
        xs: FloatArray, ys: FloatArray, ps: FloatArray, tilts: FloatArray, oris: FloatArray, count: Int,
        width: Float, spec: BrushSpec,
    ): StrokeMesh? {
        if (count <= 0) return null
        // ---- 1. de-spike the end pressures
        val p = ps.copyOf(count)
        if (count >= 3) { p[0] = min(p[0], p[1]); p[count - 1] = min(p[count - 1], p[count - 2]) }

        // ---- 2. dense centreline
        var len = 0f
        for (i in 1 until count) len += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        val step = max(0.35f, width * 0.2f)
        val d = Dense(count * 2 + 8)
        if (count == 1 || len < max(0.5f, width * 0.15f)) {
            var pm = 0f; for (i in 0 until count) pm = max(pm, p[i])
            d.add(xs[0], ys[0], pm, tilts[0], oris[0])
            return dot(d, width, spec)
        }
        for (i in 0 until count - 1) {
            val x1 = xs[i]; val y1 = ys[i]; val x2 = xs[i + 1]; val y2 = ys[i + 1]
            val chord = hypot(x2 - x1, y2 - y1)
            if (chord < 1e-4f) continue
            val x0 = if (i > 0) xs[i - 1] else 2 * x1 - x2; val y0 = if (i > 0) ys[i - 1] else 2 * y1 - y2
            val x3 = if (i + 2 < count) xs[i + 2] else 2 * x2 - x1; val y3 = if (i + 2 < count) ys[i + 2] else 2 * y2 - y1
            // Subdivide only where the path bends (about one point per 4° of turn) and the chord
            // is long enough to show it: straight runs of handwriting stay one segment.
            val turn = max(turnAngle(x0, y0, x1, y1, x2, y2), turnAngle(x1, y1, x2, y2, x3, y3))
            val steps = min(MAX_SUB, max(1, min(ceil(chord / step).toInt(), ceil(turn / MAX_TURN_PER_STEP).toInt())))
            for (s in 0 until steps) {
                val t = s.toFloat() / steps
                catmullRom(x0, y0, x1, y1, x2, y2, x3, y3, t, d)
                d.attach(lerp(p[i], p[i + 1], t), lerp(tilts[i], tilts[i + 1], t), lerpAngle(oris[i], oris[i + 1], t))
            }
        }
        d.add(xs[count - 1], ys[count - 1], p[count - 1], tilts[count - 1], oris[count - 1])
        val m = d.n
        if (m < 2) return dot(d, width, spec)

        // ---- 3. half-widths and shading
        val r = FloatArray(m); val shade = FloatArray(m)
        for (i in 0 until m) {
            val a = if (i == 0) 0 else i - 1; val b = if (i == m - 1) m - 1 else i + 1
            var tx = d.x[b] - d.x[a]; var ty = d.y[b] - d.y[a]
            val tl = hypot(tx, ty); if (tl < 1e-6f) { tx = 1f; ty = 0f } else { tx /= tl; ty /= tl }
            r[i] = halfWidth(width, d.p[i], d.tilt[i], d.ori[i], tx, ty, spec)
            shade[i] = shadeOf(d.p[i], d.tilt[i], spec)
        }
        // Zero-phase smoothing over arc length: forward then backward.
        val smoothLen = max(0.6f, width * 0.6f)
        for (pass in 0..1) {
            val range = if (pass == 0) 1 until m else (m - 2 downTo 0)
            for (i in range) {
                val j = if (pass == 0) i - 1 else i + 1
                val a = 1f - exp(-hypot(d.x[i] - d.x[j], d.y[i] - d.y[j]) / smoothLen)
                r[i] = r[j] + a * (r[i] - r[j])
                shade[i] = shade[j] + a * (shade[i] - shade[j])
            }
        }

        // ---- 4. edges with joins
        val e = Edges(m * 2 + 16)
        val cosSmooth = cos(25.0 * PI / 180).toFloat()
        val v = FloatArray(2)
        for (i in 0 until m) {
            dir(d, i - 1, i, v); var dpx = v[0]; var dpy = v[1]
            dir(d, i, i + 1, v); var dnx = v[0]; var dny = v[1]
            if (i == 0) { dpx = dnx; dpy = dny }
            if (i == m - 1) { dnx = dpx; dny = dpy }
            val c = dpx * dnx + dpy * dny
            val ri = r[i]
            if (c >= cosSmooth) {
                // Gentle bend: offset along the bisector of the two normals (mitre, capped).
                var bx = -(dpy + dny); var by = dpx + dnx
                val bl = hypot(bx, by); if (bl < 1e-6f) { bx = -dny; by = dnx } else { bx /= bl; by /= bl }
                val k = ri / max(0.5f, bx * -dny + by * dnx)
                e.add(d.x[i] + bx * k, d.y[i] + by * k, d.x[i] - bx * k, d.y[i] - by * k, shade[i])
            } else {
                // Sharp turn: a round join on the outer side; the inner side folds to the centre
                // (or a capped mitre for moderate turns), and the non-zero fill covers the overlap.
                val cross = dpx * dny - dpy * dnx
                val leftOuter = cross < 0f // the +normal (left) side is outside when turning right
                val a0 = atan2(dpx, -dpy) // angle of the left normal (−dy, dx) of the incoming direction
                var sweep = acos(c.coerceIn(-1f, 1f))
                if (!leftOuter) sweep = -sweep
                val steps = max(2, ceil(abs(sweep) / (PI / 10)).toInt())
                var bx = -(dpy + dny); var by = dpx + dnx
                val bl = hypot(bx, by)
                val inner = if (c > 0f && bl > 1e-6f) min(2f * ri, ri / (bl / 2f)) else 0f
                if (bl > 1e-6f) { bx /= bl; by /= bl }
                val sign = if (leftOuter) -1f else 1f // inner side direction along the bisector
                val ix = d.x[i] + sign * bx * inner; val iy = d.y[i] + sign * by * inner
                for (s in 0..steps) {
                    val ang = a0 - sweep * s / steps // rotate the normal from incoming to outgoing
                    val sgn = if (leftOuter) 1f else -1f
                    val ox = d.x[i] + sgn * ri * cos(ang); val oy = d.y[i] + sgn * ri * sin(ang)
                    if (leftOuter) e.add(ox, oy, ix, iy, shade[i]) else e.add(ix, iy, ox, oy, shade[i])
                }
            }
        }

        // ---- 5. caps
        dir(d, m - 2, m - 1, v)
        val endCap = cap(d.x[m - 1], d.y[m - 1], r[m - 1], v[0], v[1], spec.cap)
        dir(d, 0, 1, v)
        val startCap = cap(d.x[0], d.y[0], r[0], -v[0], -v[1], spec.cap)
        return StrokeMesh(
            e.l.copyOf(2 * e.n), e.r.copyOf(2 * e.n), e.s.copyOf(e.n), e.n,
            endCap, d.x[m - 1], d.y[m - 1], startCap, d.x[0], d.y[0],
        )
    }

    /** Half-width at one point for a pen moving along (tx, ty). */
    internal fun halfWidth(width: Float, p: Float, tilt: Float, ori: Float, tx: Float, ty: Float, spec: BrushSpec): Float {
        var w = spec.profile.widthAt(width, p, 0f) / 2f
        if (spec.nibDepth > 0f) {
            // Moving along the nib edge draws its thin side.
            val along = abs(tx * cos(spec.nibAngle) + ty * sin(spec.nibAngle))
            w *= 1f - spec.nibDepth * along
        }
        if (spec.footprintGain > 0f) {
            val stretch = 1f + spec.footprintGain * lean(tilt)
            if (stretch > 1f) {
                // Ellipse with its long axis along the azimuth m; its extent across the motion.
                val mx = sin(ori); val my = -cos(ori)
                val nx = -ty; val ny = tx
                val along = nx * mx + ny * my
                val across = nx * -my + ny * mx
                w *= sqrt(stretch * stretch * along * along + across * across)
            }
        }
        return max(w, 0.15f)
    }

    /** 0 (upright to normal writing angle) … 1 (pen nearly flat), as in [PenProfile]. */
    fun lean(tilt: Float): Float {
        val l = ((tilt - PenProfile.SHADING_START_RAD) / (PenProfile.MAX_TILT_RAD - PenProfile.SHADING_START_RAD)).coerceIn(0f, 1f)
        return sin(l * PI.toFloat() / 2f)
    }

    /** Pigment darkness from the tool's [Shade]: pressure darkens, leaning lightens. Solid tools: 1. */
    internal fun shadeOf(p: Float, tilt: Float, spec: BrushSpec): Float {
        val sh = spec.shade ?: return 1f
        val press = sh.min + (1f - sh.min) * p.coerceIn(0f, 1f).toDouble().pow(sh.gamma.toDouble()).toFloat()
        return press * (1f - sh.leanFade * lean(tilt))
    }

    private fun dot(d: Dense, width: Float, spec: BrushSpec): StrokeMesh {
        val cx = d.x[0]; val cy = d.y[0]
        val rr = halfWidth(width, d.p[0], d.tilt[0], d.ori[0], 1f, 0f, spec)
        val sh = shadeOf(d.p[0], d.tilt[0], spec)
        val l = floatArrayOf(cx, cy - rr); val r = floatArrayOf(cx, cy + rr)
        val end = cap(cx, cy, rr, 1f, 0f, spec.cap)
        val start = cap(cx, cy, rr, -1f, 0f, spec.cap)
        return StrokeMesh(l, r, floatArrayOf(sh), 1, end, cx, cy, start, cx, cy)
    }

    /**
     * Cap points beyond (cx, cy) in direction (dx, dy), going from the left edge (−dy, dx)
     * round to the right edge, both edges excluded.
     */
    private fun cap(cx: Float, cy: Float, r: Float, dx: Float, dy: Float, style: CapStyle): FloatArray = when (style) {
        CapStyle.SQUARE -> floatArrayOf(cx - dy * r + dx * r, cy + dx * r + dy * r, cx + dy * r + dx * r, cy - dx * r + dy * r)
        CapStyle.ROUND -> {
            val steps = if (r < 1.5f) 6 else 10
            val a0 = atan2(dx, -dy) // angle of the left normal
            FloatArray(2 * (steps - 1)) { k ->
                val s = k / 2 + 1
                val a = a0 - PI.toFloat() * s / steps
                if (k % 2 == 0) cx + r * cos(a) else cy + r * sin(a)
            }
        }
    }

    /** Unit direction from point a to b into [out] (0,0 outside the line). No allocation: hot loop. */
    private fun dir(d: Dense, a: Int, b: Int, out: FloatArray) {
        if (a < 0 || b >= d.n) { out[0] = 0f; out[1] = 0f; return }
        val dx = d.x[b] - d.x[a]; val dy = d.y[b] - d.y[a]
        val l = hypot(dx, dy)
        if (l < 1e-6f) { out[0] = 1f; out[1] = 0f } else { out[0] = dx / l; out[1] = dy / l }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun lerpAngle(a: Float, b: Float, t: Float): Float {
        var dlt = b - a
        val twoPi = (2 * PI).toFloat()
        while (dlt > PI) dlt -= twoPi
        while (dlt < -PI) dlt += twoPi
        return a + dlt * t
    }

    /** Angle (radians) between segment a→b and segment b→c. */
    private fun turnAngle(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Float {
        val ux = bx - ax; val uy = by - ay; val vx = cx - bx; val vy = cy - by
        val l = hypot(ux, uy) * hypot(vx, vy)
        return if (l < 1e-9f) 0f else acos(((ux * vx + uy * vy) / l).coerceIn(-1f, 1f))
    }

    private fun knot(ax: Float, ay: Float, bx: Float, by: Float) = max(1e-4f, sqrt(hypot(bx - ax, by - ay)))

    /** Linear blend of a (at knot ta) and b (at knot tb), evaluated at knot u. */
    private fun mix(a: Float, b: Float, ta: Float, tb: Float, u: Float) = ((tb - u) * a + (u - ta) * b) / (tb - ta)

    /** Centripetal Catmull-Rom (Barry–Goldman form): no cusps or self-loops within a segment. */
    private fun catmullRom(
        x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, t: Float, out: Dense,
    ) {
        val t1 = knot(x0, y0, x1, y1); val t2 = t1 + knot(x1, y1, x2, y2); val t3 = t2 + knot(x2, y2, x3, y3)
        val u = t1 + (t2 - t1) * t
        val a1x = mix(x0, x1, 0f, t1, u); val a1y = mix(y0, y1, 0f, t1, u)
        val a2x = mix(x1, x2, t1, t2, u); val a2y = mix(y1, y2, t1, t2, u)
        val a3x = mix(x2, x3, t2, t3, u); val a3y = mix(y2, y3, t2, t3, u)
        val b1x = mix(a1x, a2x, 0f, t2, u); val b1y = mix(a1y, a2y, 0f, t2, u)
        val b2x = mix(a2x, a3x, t1, t3, u); val b2y = mix(a2y, a3y, t1, t3, u)
        out.addXY(mix(b1x, b2x, t1, t2, u), mix(b1y, b2y, t1, t2, u))
    }

    /** Growable dense centreline. */
    private class Dense(cap: Int) {
        var x = FloatArray(cap); var y = FloatArray(cap); var p = FloatArray(cap); var tilt = FloatArray(cap); var ori = FloatArray(cap)
        var n = 0
        private var pendingXY = false

        fun addXY(px: Float, py: Float) {
            if (n == x.size) grow()
            // Skip points that would add a zero-length step.
            if (n > 0 && abs(px - x[n - 1]) < 1e-3f && abs(py - y[n - 1]) < 1e-3f) { pendingXY = false; return }
            x[n] = px; y[n] = py; pendingXY = true
        }

        fun attach(pp: Float, t: Float, o: Float) {
            if (!pendingXY) return
            p[n] = pp; tilt[n] = t; ori[n] = o; n++; pendingXY = false
        }

        fun add(px: Float, py: Float, pp: Float, t: Float, o: Float) { addXY(px, py); attach(pp, t, o) }

        private fun grow() {
            val c = x.size * 2
            x = x.copyOf(c); y = y.copyOf(c); p = p.copyOf(c); tilt = tilt.copyOf(c); ori = ori.copyOf(c)
        }
    }

    private class Edges(cap: Int) {
        var l = FloatArray(cap * 2); var r = FloatArray(cap * 2); var s = FloatArray(cap)
        var n = 0
        fun add(lx: Float, ly: Float, rx: Float, ry: Float, sh: Float) {
            if (n == s.size) { val c = s.size * 2; l = l.copyOf(2 * c); r = r.copyOf(2 * c); s = s.copyOf(c) }
            l[2 * n] = lx; l[2 * n + 1] = ly; r[2 * n] = rx; r[2 * n + 1] = ry; s[n] = sh; n++
        }
    }
}
