package app.folio.core.ink

import app.folio.core.model.Rect
import app.folio.core.model.Stroke
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Uniform-grid spatial index over a page's stroke list: which strokes' bounds touch an area.
 *
 * Erasing, patching tiles and rendering tiles used to test every stroke on the page; on a
 * 7,000-stroke imported page that was 400–700 ms of UI-thread time per eraser gesture on a
 * Galaxy Tab S6 Lite. With the grid a query only looks at strokes near the area.
 *
 * Immutable once built, so it can be queried from several threads. Results are stroke
 * indices in page (z) order, so drawing them in that order keeps the original stacking.
 *
 * Writing only appends strokes, so [appending] reuses the grid and keeps new strokes in a
 * short tail that queries scan directly. Rebuilding the grid for every stroke took ~70 ms
 * of UI-thread time on a 7,000-stroke page (Tab S6 Lite), right as the next stroke began.
 */
class StrokeIndex private constructor(val strokes: List<Stroke>, private val grid: Grid) {
    constructor(strokes: List<Stroke>, cellSize: Float = DEFAULT_CELL) : this(strokes, Grid(strokes, cellSize))

    /** Strokes [grid] covers: the first [Grid.count]; the rest are the appended tail. */
    private val tailStart = grid.count

    /**
     * An index for [newStrokes] that reuses this one's grid, if [newStrokes] is this page with
     * strokes added at the end (same objects in front) and the tail is still short;
     * otherwise a fresh index.
     */
    fun appending(newStrokes: List<Stroke>): StrokeIndex {
        val n = strokes.size
        if (newStrokes.size <= n || newStrokes.size - grid.count > MAX_TAIL) return StrokeIndex(newStrokes)
        for (i in 0 until n) if (newStrokes[i] !== strokes[i]) return StrokeIndex(newStrokes)
        return StrokeIndex(newStrokes, grid)
    }

    /** Index of the stroke with [id] in [strokes], or -1. */
    fun indexOf(id: String): Int {
        grid.ids[id]?.let { return it }
        for (i in tailStart until strokes.size) if (strokes[i].id == id) return i
        return -1
    }

    /** Indices of strokes whose bounds intersect the rectangle, ascending (page order). */
    fun query(l: Float, t: Float, r: Float, b: Float): IntArray {
        if (strokes.isEmpty() || r < l || b < t) return EMPTY
        val base = grid.query(l, t, r, b)
        if (tailStart == strokes.size) return base
        var out = base.copyOf(base.size + 8)
        var n = base.size
        for (i in tailStart until strokes.size) {
            val bb = strokes[i].bounds()
            if (bb.right < l || bb.left > r || bb.bottom < t || bb.top > b) continue
            if (n == out.size) out = out.copyOf(n * 2)
            out[n++] = i // tail indices are above every grid index, so order is kept
        }
        return out.copyOf(n)
    }

    fun query(area: Rect): IntArray = query(area.left, area.top, area.right, area.bottom)

    /** The grid over the first [count] strokes of a page. Never changed after it's built. */
    private class Grid(strokes: List<Stroke>, cellSize: Float) {
    val count = strokes.size
    private val strokes = strokes
    private val ox: Float
    private val oy: Float
    private val cell: Float
    private val cols: Int
    private val rows: Int
    /** CSR layout: the strokes in cell c are items[start[c] until start[c + 1]]. */
    private val start: IntArray
    private val items: IntArray
    /** Strokes spanning more than [MAX_CELLS_PER_STROKE] cells; checked on every query. */
    private val big: IntArray
    /** Built on first use: most indexes never need it. */
    val ids: HashMap<String, Int> by lazy {
        HashMap<String, Int>(strokes.size * 2).also { m -> for (i in strokes.indices) m[strokes[i].id] = i }
    }

    init {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in strokes.indices) {
            val bb = strokes[i].bounds()
            l = min(l, bb.left); t = min(t, bb.top); r = max(r, bb.right); b = max(b, bb.bottom)
        }
        if (strokes.isEmpty() || !(r >= l && b >= t)) { l = 0f; t = 0f; r = 1f; b = 1f }
        // Keep the grid at most MAX_DIM cells per side, whatever the page size.
        var c = cellSize
        while ((r - l) / c > MAX_DIM || (b - t) / c > MAX_DIM) c *= 2f
        ox = l; oy = t; cell = c
        cols = max(1, ceil((r - l) / c).toInt() + 1)
        rows = max(1, ceil((b - t) / c).toInt() + 1)

        val counts = IntArray(cols * rows + 1)
        val isBig = BooleanArray(strokes.size)
        var bigCount = 0
        for (i in strokes.indices) {
            val bb = strokes[i].bounds()
            val c0 = col(bb.left); val c1 = col(bb.right); val r0 = row(bb.top); val r1 = row(bb.bottom)
            if ((c1 - c0 + 1).toLong() * (r1 - r0 + 1) > MAX_CELLS_PER_STROKE) { isBig[i] = true; bigCount++; continue }
            for (y in r0..r1) for (x in c0..c1) counts[y * cols + x]++
        }
        start = IntArray(cols * rows + 1)
        for (k in 0 until cols * rows) start[k + 1] = start[k] + counts[k]
        items = IntArray(start[cols * rows])
        val fill = start.copyOf(cols * rows)
        big = IntArray(bigCount)
        var nb = 0
        for (i in strokes.indices) {
            if (isBig[i]) { big[nb++] = i; continue }
            val bb = strokes[i].bounds()
            val c0 = col(bb.left); val c1 = col(bb.right); val r0 = row(bb.top); val r1 = row(bb.bottom)
            for (y in r0..r1) for (x in c0..c1) items[fill[y * cols + x]++] = i
        }
    }

    private fun col(x: Float) = floor((x - ox) / cell).toInt().coerceIn(0, cols - 1)
    private fun row(y: Float) = floor((y - oy) / cell).toInt().coerceIn(0, rows - 1)

    fun query(l: Float, t: Float, r: Float, b: Float): IntArray {
        if (strokes.isEmpty()) return EMPTY
        var out = IntArray(16)
        var n = 0
        val qc0 = col(l); val qc1 = col(r); val qr0 = row(t); val qr1 = row(b)
        for (y in qr0..qr1) for (x in qc0..qc1) {
            val k = y * cols + x
            for (p in start[k] until start[k + 1]) {
                val i = items[p]
                val bb = strokes[i].bounds()
                // A stroke sits in several cells: report it only from the first cell it
                // shares with the query, so no de-duplication set is needed.
                if (x != max(col(bb.left), qc0) || y != max(row(bb.top), qr0)) continue
                if (bb.right < l || bb.left > r || bb.bottom < t || bb.top > b) continue
                if (n == out.size) out = out.copyOf(n * 2)
                out[n++] = i
            }
        }
        for (i in big) {
            val bb = strokes[i].bounds()
            if (bb.right < l || bb.left > r || bb.bottom < t || bb.top > b) continue
            if (n == out.size) out = out.copyOf(n * 2)
            out[n++] = i
        }
        val res = out.copyOf(n)
        res.sort()
        return res
    }
    }

    companion object {
        /** Appended strokes kept outside the grid before it is rebuilt. */
        const val MAX_TAIL = 256
        /** Page units per cell (A4 page = 1000 wide): about the size of a written word. */
        const val DEFAULT_CELL = 48f
        private const val MAX_DIM = 512
        private const val MAX_CELLS_PER_STROKE = 1024
        private val EMPTY = IntArray(0)
    }
}
