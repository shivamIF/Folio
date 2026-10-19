package app.folio.core.ink

import app.folio.core.model.Argb
import app.folio.core.model.Stroke
import app.folio.core.model.ToolType
import kotlin.math.exp
import kotlin.math.hypot

/**
 * Collects input samples for one stroke in page coordinates and applies light,
 * speed-adaptive stabilisation (1€-filter style). Slow, careful movement is
 * smoothed more; fast movement is smoothed less, so lag stays small.
 *
 * Speed is judged on screen ([pxPerUnit] = current zoom), so smoothing and point merging
 * behave the same at every zoom. Before, at 5× zoom the same hand movement looked 5× slower
 * in page units: the filter smoothed harder and the line's end stuck up to ~4 px behind.
 *
 * @param stabilization 0 = raw input, 1 = strongest smoothing.
 * @param minDistance samples closer than this (page units) to the previous kept point are merged.
 * @param pxPerUnit screen pixels per page unit while drawing.
 */
class StrokeBuilder(
    private val tool: ToolType,
    private val color: Argb,
    private val width: Float,
    stabilization: Float = 0.3f,
    private val minDistance: Float = 0.4f,
    private val startedAt: Long = 0,
    private val pxPerUnit: Float = REFERENCE_PX_PER_UNIT,
) {
    private val strength = stabilization.coerceIn(0f, 1f)
    private var xs = FloatArray(64); private var ys = FloatArray(64); private var ps = FloatArray(64)
    private var tilts = FloatArray(64); private var oris = FloatArray(64); private var ts = IntArray(64)
    var size = 0
        private set

    private var fx = 0f; private var fy = 0f; private var lastT = 0L; private var startT = -1L
    private var speed = 0f

    fun add(x: Float, y: Float, pressure: Float, tilt: Float, orientation: Float, timeMs: Long) {
        if (startT < 0) {
            startT = timeMs; fx = x; fy = y; lastT = timeMs
            push(x, y, pressure, tilt, orientation, 0)
            return
        }
        val dt = (timeMs - lastT).coerceAtLeast(1).toFloat()
        // Screen speed, expressed at the reference zoom the tuning was made at.
        val dist = hypot(x - fx, y - fy) * pxPerUnit / REFERENCE_PX_PER_UNIT
        speed = 0.7f * speed + 0.3f * (dist / dt)
        // The cutoff rises with speed: alpha → 1 (no smoothing) when moving fast.
        val minCutoff = 1f + (1f - strength) * 30f
        val cutoff = minCutoff + 4f * speed * 1000f / 60f
        val tau = 1f / (2f * Math.PI.toFloat() * cutoff)
        val alpha = if (strength == 0f) 1f else 1f - exp(-(dt / 1000f) / tau)
        fx += alpha * (x - fx); fy += alpha * (y - fy)
        lastT = timeMs
        val t = (timeMs - startT).toInt()
        if (size > 0 && hypot(fx - xs[size - 1], fy - ys[size - 1]) < minDistance) {
            // Keep the newest pressure/tilt for a merged point so the tip still responds.
            ps[size - 1] = maxOf(ps[size - 1], pressure); tilts[size - 1] = tilt; oris[size - 1] = orientation
            return
        }
        push(fx, fy, pressure, tilt, orientation, t)
    }

    /** Snaps the final point to the true pen-up location so stabilisation never shortens a stroke. */
    fun finishAt(x: Float, y: Float, pressure: Float, tilt: Float, orientation: Float, timeMs: Long) {
        if (startT < 0) { add(x, y, pressure, tilt, orientation, timeMs); return }
        val t = (timeMs - startT).toInt().coerceAtLeast(if (size > 0) ts[size - 1] else 0)
        if (size > 1 && hypot(x - xs[size - 1], y - ys[size - 1]) < minDistance) {
            xs[size - 1] = x; ys[size - 1] = y
        } else push(x, y, pressure, tilt, orientation, t)
    }

    private fun push(x: Float, y: Float, p: Float, tilt: Float, o: Float, t: Int) {
        if (size == xs.size) grow()
        xs[size] = x; ys[size] = y; ps[size] = p; tilts[size] = tilt; oris[size] = o; ts[size] = t
        size++
    }

    private fun grow() {
        val n = xs.size * 2
        xs = xs.copyOf(n); ys = ys.copyOf(n); ps = ps.copyOf(n)
        tilts = tilts.copyOf(n); oris = oris.copyOf(n); ts = ts.copyOf(n)
    }

    companion object {
        /** Screen pixels per page unit the filter was tuned at (fit width on a 10" tablet). */
        const val REFERENCE_PX_PER_UNIT = 1.6f

        /** Point merging threshold for a zoom: about 0.65 screen pixels. */
        fun minDistanceAt(pxPerUnit: Float) = 0.4f * REFERENCE_PX_PER_UNIT / pxPerUnit.coerceAtLeast(0.05f)
    }

    fun build(): Stroke = Stroke(
        tool = tool, color = color, width = width,
        x = xs.copyOf(size), y = ys.copyOf(size), pressure = ps.copyOf(size),
        tilt = tilts.copyOf(size), orientation = oris.copyOf(size), t = ts.copyOf(size),
        startedAt = startedAt,
    )
}
