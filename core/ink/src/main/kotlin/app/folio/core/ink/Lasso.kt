package app.folio.core.ink

import app.folio.core.model.Rect
import app.folio.core.model.Stroke

/** Lasso selection and selection geometry, platform-neutral. */
object Lasso {
    /**
     * Strokes touched by the lasso, selected whole. A stroke is one continuous
     * pen-down, so a cursive word written in one go is selected together, while
     * separately lifted parts are selected on their own.
     *
     * A stroke counts if any of its points is inside the closed loop, or if the
     * loop's outline passes over its ink (within half its width plus [tolerance]).
     */
    fun select(strokes: List<Stroke>, polygon: FloatArray, tolerance: Float = 2f, partial: Boolean = true): Set<String> {
        if (polygon.size < 6) return emptySet()
        val bounds = polygonBounds(polygon)
        val out = LinkedHashSet<String>()
        for (s in strokes) {
            if (s.size == 0) continue
            val reach = s.width * s.tool.inkReach + tolerance
            if (!s.bounds().intersects(bounds.inflate(reach))) continue
            val hit = if (partial) anyPointInside(s, polygon) || outlineTouches(s, polygon, reach) else allPointsInside(s, polygon)
            if (hit) out += s.id
        }
        return out
    }

    /** "Include partially selected objects" off: only strokes whose every point is inside the loop. */
    private fun allPointsInside(s: Stroke, polygon: FloatArray): Boolean {
        for (i in 0 until s.size) if (!contains(polygon, s.x[i], s.y[i])) return false
        return true
    }

    /** True if [r] lies wholly inside the loop (its four corners are), for boxes in "fully enclosed" mode. */
    fun containsRect(polygon: FloatArray, r: Rect): Boolean =
        contains(polygon, r.left, r.top) && contains(polygon, r.right, r.top) && contains(polygon, r.right, r.bottom) && contains(polygon, r.left, r.bottom)

    /** Closed rectangle polygon from two corners (rectangle selection). */
    fun rectangle(x0: Float, y0: Float, x1: Float, y1: Float): FloatArray =
        floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1)

    private fun anyPointInside(s: Stroke, polygon: FloatArray): Boolean {
        for (i in 0 until s.size) if (contains(polygon, s.x[i], s.y[i])) return true
        return false
    }

    private fun outlineTouches(s: Stroke, polygon: FloatArray, reach: Float): Boolean {
        val n = polygon.size / 2
        for (k in 0 until n) {
            val ax = polygon[2 * k]; val ay = polygon[2 * k + 1]
            val bx = polygon[2 * ((k + 1) % n)]; val by = polygon[2 * ((k + 1) % n) + 1]
            if (s.size == 1) {
                if (StrokeGeometry.distanceToSegment(s.x[0], s.y[0], ax, ay, bx, by) <= reach) return true
                continue
            }
            for (i in 1 until s.size) {
                if (segmentDistance(ax, ay, bx, by, s.x[i - 1], s.y[i - 1], s.x[i], s.y[i]) <= reach) return true
            }
        }
        return false
    }

    /** Shortest distance between two segments (0 when they cross). */
    private fun segmentDistance(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float): Float {
        if (segmentsCross(ax, ay, bx, by, cx, cy, dx, dy)) return 0f
        return minOf(
            minOf(StrokeGeometry.distanceToSegment(ax, ay, cx, cy, dx, dy), StrokeGeometry.distanceToSegment(bx, by, cx, cy, dx, dy)),
            minOf(StrokeGeometry.distanceToSegment(cx, cy, ax, ay, bx, by), StrokeGeometry.distanceToSegment(dx, dy, ax, ay, bx, by)),
        )
    }

    private fun segmentsCross(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float): Boolean {
        fun orient(px: Float, py: Float, qx: Float, qy: Float, rx: Float, ry: Float) = (qx - px) * (ry - py) - (qy - py) * (rx - px)
        val d1 = orient(cx, cy, dx, dy, ax, ay); val d2 = orient(cx, cy, dx, dy, bx, by)
        val d3 = orient(ax, ay, bx, by, cx, cy); val d4 = orient(ax, ay, bx, by, dx, dy)
        return ((d1 > 0) != (d2 > 0)) && ((d3 > 0) != (d4 > 0))
    }

    /** Even-odd ray casting; works for self-intersecting loops too. */
    fun contains(polygon: FloatArray, px: Float, py: Float): Boolean {
        var inside = false
        val n = polygon.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = polygon[2 * i]; val yi = polygon[2 * i + 1]
            val xj = polygon[2 * j]; val yj = polygon[2 * j + 1]
            if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    fun polygonBounds(polygon: FloatArray): Rect {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in polygon.indices step 2) {
            l = minOf(l, polygon[i]); r = maxOf(r, polygon[i])
            t = minOf(t, polygon[i + 1]); b = maxOf(b, polygon[i + 1])
        }
        return Rect(l, t, r, b)
    }

    /** Union of the inked bounds of [strokes], or null when empty. */
    fun bounds(strokes: List<Stroke>): Rect? = strokes.map { it.bounds() }.reduceOrNull(Rect::union)
}

/**
 * A pending move/resize/rotation of a selection: rotate by [rotation] degrees (clockwise) around
 * ([pivotX], [pivotY]), then scale around (anchorX, anchorY), then translate. Identity by
 * default. Applied live while dragging, committed once.
 */
data class SelectionTransform(
    val scale: Float = 1f,
    val anchorX: Float = 0f,
    val anchorY: Float = 0f,
    val dx: Float = 0f,
    val dy: Float = 0f,
    val rotation: Float = 0f,
    val pivotX: Float = 0f,
    val pivotY: Float = 0f,
) {
    val isIdentity get() = scale == 1f && dx == 0f && dy == 0f && rotation == 0f

    fun apply(stroke: Stroke): Stroke = stroke.rotated(rotation, pivotX, pivotY).transformed(scale, anchorX, anchorY, dx, dy)

    /** Where page point ([x], [y]) ends up. */
    fun map(x: Float, y: Float): Pair<Float, Float> {
        var px = x; var py = y
        if (rotation != 0f) {
            val a = Math.toRadians(rotation.toDouble())
            val c = kotlin.math.cos(a).toFloat(); val s = kotlin.math.sin(a).toFloat()
            val rx = pivotX + (x - pivotX) * c - (y - pivotY) * s
            val ry = pivotY + (x - pivotX) * s + (y - pivotY) * c
            px = rx; py = ry
        }
        return (anchorX + (px - anchorX) * scale + dx) to (anchorY + (py - anchorY) * scale + dy)
    }

    /** Bounds of [r] after the transform (the box around its moved corners). */
    fun mapRect(r: Rect): Rect {
        val pts = listOf(map(r.left, r.top), map(r.right, r.top), map(r.right, r.bottom), map(r.left, r.bottom))
        return Rect(pts.minOf { it.first }, pts.minOf { it.second }, pts.maxOf { it.first }, pts.maxOf { it.second })
    }

    fun mapX(x: Float) = anchorX + (x - anchorX) * scale + dx
    fun mapY(y: Float) = anchorY + (y - anchorY) * scale + dy

    companion object {
        /** Rotation about [box]'s centre from the rotate handle's drag: the angle swept from ([fromX], [fromY]) to ([x], [y]), snapped to 0/90/180/270 within 4°. */
        fun rotate(box: Rect, fromX: Float, fromY: Float, x: Float, y: Float): SelectionTransform {
            val cx = (box.left + box.right) / 2; val cy = (box.top + box.bottom) / 2
            val a0 = kotlin.math.atan2((fromY - cy).toDouble(), (fromX - cx).toDouble())
            val a1 = kotlin.math.atan2((y - cy).toDouble(), (x - cx).toDouble())
            var deg = Math.toDegrees(a1 - a0).toFloat()
            while (deg > 180f) deg -= 360f
            while (deg < -180f) deg += 360f
            val snap = Math.round(deg / 90f) * 90f
            if (kotlin.math.abs(deg - snap) < 4f) deg = snap
            return SelectionTransform(rotation = deg, pivotX = cx, pivotY = cy)
        }

        const val MIN_SCALE = 0.1f
        const val MAX_SCALE = 10f

        /**
         * Uniform resize from dragging the bottom-right handle of [box] to
         * (px, py), anchored at the top-left corner.
         */
        fun resize(box: Rect, px: Float, py: Float): SelectionTransform {
            val sx = (px - box.left) / box.width.coerceAtLeast(1f)
            val sy = (py - box.top) / box.height.coerceAtLeast(1f)
            val s = maxOf(sx, sy).coerceIn(MIN_SCALE, MAX_SCALE)
            return SelectionTransform(scale = s, anchorX = box.left, anchorY = box.top)
        }
    }
}
