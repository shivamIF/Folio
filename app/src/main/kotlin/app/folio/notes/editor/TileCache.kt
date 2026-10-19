package app.folio.notes.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import app.folio.core.ink.StrokeGeometry
import app.folio.core.ink.StrokeIndex
import app.folio.core.model.PaperTemplate
import app.folio.core.model.Rect
import app.folio.core.model.Stroke
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Tiled raster cache of a page: paper, PDF background and committed ink together, in
 * opaque [TILE_PX] tiles at zoom levels √2 apart.
 *
 * Why (measured on a Galaxy Tab S6 Lite, 7,106-stroke imported page): one whole-page
 * bitmap blurred when zoomed and took 2–2.6 s to re-render all strokes at the new zoom;
 * stacked transparent layers also cost GPU fill on every frame. Tiles fix both:
 * - only the strokes touching a tile are drawn into it, on several threads, nearest
 *   the centre of the view first;
 * - a tile is never coarser than the screen (level scale ≥ view scale ÷ 1.11);
 * - tiles are opaque, so each screen pixel is painted once;
 * - a whole-page base level is kept, so nothing is ever blank while sharper tiles render;
 * - edits patch only the changed area of the tiles they touch (appends are painted on).
 *
 * All bookkeeping is on the main thread. Workers only render new bitmaps; they never
 * touch a tile that's already cached. Stroke outline Paths are built once per stroke
 * ([outline]) and shared read-only by all threads.
 */
internal class TileCache(private val onTilesChanged: () -> Unit) {

    data class Key(val level: Int, val col: Int, val row: Int)

    /** What tiles are rendered from. [gen] changes when the page, paper, size or PDF changes. */
    class Content(
        val gen: Int, val index: StrokeIndex, val excluded: Set<String>,
        val paper: PaperTemplate, val pageW: Float, val pageH: Float, val pdf: Bitmap?,
    ) {
        val strokes: List<Stroke> get() = index.strokes
    }

    class Tile(val key: Key, val bitmap: Bitmap, var strokes: List<Stroke>, var excluded: Set<String>, val gen: Int) {
        val scale = levelScale(key.level)
        val size = TILE_PX / scale
        val left = key.col * size
        val top = key.row * size
        val canvas: Canvas by lazy { Canvas(bitmap).apply { scale(scale, scale); translate(-left, -top) } }
        var lastUsed = 0L
        /** Shown but known to be out of date; a fresh render has been requested. */
        var stale = false
        fun intersects(r: Rect) = r.left < left + size && r.right > left && r.top < top + size && r.bottom > top
    }

    private val tiles = HashMap<Key, Tile>()
    private var content: Content? = null
    private var gen = 0
    private var frame = 0L
    private var released = false

    // Scheduling (main thread only).
    private val wanted = ArrayList<Key>()
    @Volatile private var wantedSet: Set<Key> = emptySet()
    private val inFlight = HashSet<Key>()
    private val main = Handler(Looper.getMainLooper())

    /** Debug: tile render times (ms), drained by the perf probe. */
    val renderMs = Collections.synchronizedList(ArrayList<Float>())

    /** Debug counters (main thread): time in tile sync and diffing, number of patches. */
    var perfSyncMs = 0f
    var perfDiffMs = 0f
    var perfPatches = 0

    /** True if the last [draw] showed every visible tile sharp and current. */
    var lastDrawSharp = false
        private set

    /** Main thread: sets what the page shows. Page/paper/size/PDF changes start over. */
    fun setContent(pageKey: Any?, index: StrokeIndex, excluded: Set<String>, paper: PaperTemplate, pageW: Float, pageH: Float, pdf: Bitmap?) {
        val strokes = index.strokes
        val c = content
        val same = c != null && pageKey == this.pageKey && paper == c.paper && pageW == c.pageW && pageH == c.pageH && pdf === c.pdf
        if (!same) {
            gen++
            tiles.clear()
            this.pageKey = pageKey
        }
        if (c == null || !same || c.strokes !== strokes || c.excluded !== excluded) {
            content = Content(gen, index, excluded, paper, pageW, pageH, pdf)
        }
    }
    private var pageKey: Any? = null

    fun release() {
        released = true
        tiles.clear(); wanted.clear(); wantedSet = emptySet()
    }

    // ------------------------------------------------------------------ drawing

    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val src = android.graphics.Rect()
    private val dst = RectF()
    private val renderer = PageRenderer()

    /**
     * Main thread. Draws the page into [canvas] (view pixels) for the given view transform.
     * [baseScale] is the whole-page scale; that level is kept as the fallback.
     * [frozen]: don't apply edits to tiles (an eraser wipe already shows the result).
     */
    fun draw(canvas: Canvas, scale: Float, tx: Float, ty: Float, viewW: Float, viewH: Float, baseScale: Float, frozen: Boolean) {
        val c = content ?: return
        frame++
        val k = levelFor(scale)
        val kBase = min(levelFor(baseScale), k)
        val s = levelScale(k)
        // Visible page rectangle, in page units.
        val l = max(0f, -tx / scale); val t = max(0f, -ty / scale)
        val r = min(c.pageW, (viewW - tx) / scale); val b = min(c.pageH, (viewH - ty) / scale)
        if (r <= l || b <= t) return
        val c0 = floor(l * s / TILE_PX).toInt(); val c1 = floor((r * s - 0.01f) / TILE_PX).toInt()
        val r0 = floor(t * s / TILE_PX).toInt(); val r1 = floor((b * s - 0.01f) / TILE_PX).toInt()

        canvas.save()
        canvas.clipRect(tx, ty, tx + c.pageW * scale, ty + c.pageH * scale)
        var sharp = true
        val want = ArrayList<Pair<Key, Float>>()
        val cx = (l + r) / 2f; val cy = (t + b) / 2f
        for (row in r0..r1) for (col in c0..c1) {
            val key = Key(k, col, row)
            val tile = tiles[key]
            viewRect(key, scale, tx, ty, dst)
            if (tile != null && tile.gen == c.gen) {
                if (!frozen) { val t0 = System.nanoTime(); sync(tile, c); perfSyncMs += (System.nanoTime() - t0) / 1e6f }
                if (tile.stale) sharp = false
                tile.lastUsed = frame
                drawTile(canvas, tile, dst)
            } else {
                sharp = false
                drawFallback(canvas, c, k, kBase, scale, tx, ty, dst)
            }
            if (tile == null || tile.stale) {
                val size = TILE_PX / s
                val dx = (col + 0.5f) * size - cx; val dy = (row + 0.5f) * size - cy
                want += key to (dx * dx + dy * dy)
            }
        }
        canvas.restore()
        lastDrawSharp = sharp

        // Render order: visible (centre-out), then the whole-page base level, then a ring
        // around the view so panning finds tiles ready.
        val order = ArrayList<Key>()
        want.sortedBy { it.second }.mapTo(order) { it.first }
        addLevel(order, kBase, 0f, 0f, c.pageW, c.pageH)
        val ring = TILE_PX / s
        addLevel(order, k, l - ring, t - ring, r + ring, b + ring)
        setWanted(order)
        evict(k, kBase)
    }

    private fun addLevel(out: MutableList<Key>, level: Int, l: Float, t: Float, r: Float, b: Float) {
        val s = levelScale(level)
        val c = content ?: return
        val c0 = max(0, floor(l * s / TILE_PX).toInt()); val c1 = floor((min(r, c.pageW) * s - 0.01f) / TILE_PX).toInt()
        val r0 = max(0, floor(t * s / TILE_PX).toInt()); val r1 = floor((min(b, c.pageH) * s - 0.01f) / TILE_PX).toInt()
        for (row in r0..r1) for (col in c0..c1) {
            val key = Key(level, col, row)
            val tile = tiles[key]
            if ((tile == null || tile.stale) && key !in out) out += key
            tile?.lastUsed = frame
        }
    }

    /** Tile bounds in view pixels, rounded the same way for neighbours so there are no seams. */
    private fun viewRect(key: Key, scale: Float, tx: Float, ty: Float, out: RectF) {
        val size = TILE_PX / levelScale(key.level)
        out.set(
            (key.col * size * scale + tx).roundToInt().toFloat(), (key.row * size * scale + ty).roundToInt().toFloat(),
            ((key.col + 1) * size * scale + tx).roundToInt().toFloat(), ((key.row + 1) * size * scale + ty).roundToInt().toFloat(),
        )
    }

    private fun drawTile(canvas: Canvas, tile: Tile, d: RectF) {
        src.set(0, 0, TILE_PX, TILE_PX)
        canvas.drawBitmap(tile.bitmap, src, d, tilePaint)
    }

    /** A sharp tile isn't ready: show the best coarser/finer tiles we have, over plain paper. */
    private fun drawFallback(canvas: Canvas, c: Content, k: Int, kBase: Int, scale: Float, tx: Float, ty: Float, d: RectF) {
        canvas.save()
        canvas.clipRect(d)
        // Paper first, in case no tile at any level covers this spot yet.
        canvas.save()
        canvas.translate(tx, ty); canvas.scale(scale, scale)
        renderer.drawPaper(canvas, c.paper, c.pageW, c.pageH, hasPdf = c.pdf != null)
        c.pdf?.let { renderer.drawPdfBackground(canvas, it, c.pageW, c.pageH) }
        canvas.restore()
        // Coarse to fine, so the best available tile ends up on top.
        val pageL = (d.left - tx) / scale; val pageT = (d.top - ty) / scale
        val pageR = (d.right - tx) / scale; val pageB = (d.bottom - ty) / scale
        for (level in intArrayOf(kBase, k - 2, k - 1, k + 1).distinct()) {
            if (level == k) continue
            val s = levelScale(level)
            val c0 = floor(pageL * s / TILE_PX).toInt(); val c1 = floor((pageR * s - 0.01f) / TILE_PX).toInt()
            val r0 = floor(pageT * s / TILE_PX).toInt(); val r1 = floor((pageB * s - 0.01f) / TILE_PX).toInt()
            for (row in r0..r1) for (col in c0..c1) {
                val t = tiles[Key(level, col, row)] ?: continue
                if (t.gen != c.gen) continue
                t.lastUsed = frame
                viewRect(t.key, scale, tx, ty, fallbackDst)
                drawTile(canvas, t, fallbackDst)
            }
        }
        canvas.restore()
    }
    private val fallbackDst = RectF()

    // ------------------------------------------------------------- edits → tiles

    private sealed class Diff {
        class Append(val added: List<Stroke>) : Diff()
        class Changed(val rects: List<Rect>) : Diff()
    }

    // The last diff is reused: after an edit, every tile asks about the same pair of lists.
    private var memoOld: List<Stroke>? = null
    private var memoOldEx: Set<String>? = null
    private var memoNew: List<Stroke>? = null
    private var memoNewEx: Set<String>? = null
    private var memo: Diff? = null

    private fun diff(oS: List<Stroke>, oE: Set<String>, c: Content): Diff {
        val nS = c.strokes; val nE = c.excluded
        if (oS === memoOld && oE === memoOldEx && nS === memoNew && nE === memoNewEx) memo?.let { return it }
        val t0 = System.nanoTime()
        val d = computeDiff(oS, oE, c)
        perfDiffMs += (System.nanoTime() - t0) / 1e6f
        memoOld = oS; memoOldEx = oE; memoNew = nS; memoNewEx = nE; memo = d
        return d
    }

    private fun computeDiff(oS: List<Stroke>, oE: Set<String>, c: Content): Diff {
        val nS = c.strokes; val nE = c.excluded
        val sameEx = oE === nE || oE == nE
        if (sameEx && nS.size >= oS.size) {
            var prefix = true
            for (i in oS.indices) if (oS[i] !== nS[i]) { prefix = false; break }
            if (prefix) return Diff.Append(nS.subList(oS.size, nS.size))
        }
        val rects = ArrayList<Rect>()
        if (oS === nS) {
            // Same strokes, only what's hidden changed (erasing, moving): look the flipped ids up.
            for (id in oE) if (id !in nE) c.index.indexOf(id).takeIf { it >= 0 }?.let { rects += nS[it].bounds() }
            for (id in nE) if (id !in oE) c.index.indexOf(id).takeIf { it >= 0 }?.let { rects += nS[it].bounds() }
            return Diff.Changed(rects)
        }
        val newSet = Collections.newSetFromMap(IdentityHashMap<Stroke, Boolean>(nS.size * 2)).apply { addAll(nS) }
        val oldSet = Collections.newSetFromMap(IdentityHashMap<Stroke, Boolean>(oS.size * 2)).apply { addAll(oS) }
        for (s in oS) if (s !in newSet) rects += s.bounds()
        for (s in nS) if (s !in oldSet) rects += s.bounds()
        if (!sameEx) {
            val flipped = HashSet<String>()
            for (id in oE) if (id !in nE) flipped += id
            for (id in nE) if (id !in oE) flipped += id
            if (flipped.isNotEmpty()) for (s in nS) if (s.id in flipped) rects += s.bounds()
        }
        return Diff.Changed(rects)
    }

    /** Brings a cached tile up to date with [c]: paint appended strokes, patch changed areas, or re-render. */
    private fun sync(tile: Tile, c: Content) {
        if (tile.strokes === c.strokes && (tile.excluded === c.excluded || tile.excluded == c.excluded)) {
            tile.excluded = c.excluded
            return
        }
        if (tile.stale) return // a fresh render is on its way
        when (val d = diff(tile.strokes, tile.excluded, c)) {
            is Diff.Append -> {
                for (s in d.added) if (s.id !in c.excluded && tile.intersects(s.bounds())) {
                    threadRenderer.get()!!.drawStroke(tile.canvas, s.color, s.tool, outline(s))
                }
                tile.strokes = c.strokes; tile.excluded = c.excluded
            }
            is Diff.Changed -> {
                var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
                for (x in d.rects) if (tile.intersects(x)) {
                    l = min(l, x.left); t = min(t, x.top); r = max(r, x.right); b = max(b, x.bottom)
                }
                if (l > r) { tile.strokes = c.strokes; tile.excluded = c.excluded; return }
                val region = alignToPixels(tile, l, t, r, b)
                if (c.index.query(region).size > PATCH_LIMIT) {
                    tile.stale = true // keep showing it; re-render with priority (see draw)
                    return
                }
                patch(tile, region, c)
                perfPatches++
                tile.strokes = c.strokes; tile.excluded = c.excluded
            }
        }
    }

    /** Repaints [region] (page units) of [tile] from scratch: paper, PDF, then the strokes there, in order. */
    private fun patch(tile: Tile, region: Rect, c: Content) {
        val cv = tile.canvas
        cv.save()
        cv.clipRect(region.left, region.top, region.right, region.bottom)
        paint(cv, c, region, threadRenderer.get()!!)
        cv.restore()
    }

    /** Region grown to whole tile pixels (plus one for anti-aliasing), so a patch has no partial-pixel seam. */
    private fun alignToPixels(tile: Tile, l: Float, t: Float, r: Float, b: Float): Rect {
        val s = tile.scale
        val px0 = floor((l - tile.left) * s) - 1; val py0 = floor((t - tile.top) * s) - 1
        val px1 = ceil((r - tile.left) * s) + 1; val py1 = ceil((b - tile.top) * s) + 1
        return Rect(
            max(tile.left, tile.left + px0 / s), max(tile.top, tile.top + py0 / s),
            min(tile.left + tile.size, tile.left + px1 / s), min(tile.top + tile.size, tile.top + py1 / s),
        )
    }

    // ------------------------------------------------------------- eraser wipe

    private val wiped = HashSet<Key>()
    private val wipeClip = Path()

    /**
     * Partial eraser fast path: repaint plain paper along the eraser's path on the tiles
     * at [level] and the base level, so ink disappears under the pen at once. The exact
     * stroke pieces arrive later through the normal edit path and patch these areas.
     */
    fun wipe(x0: Float, y0: Float, x1: Float, y1: Float, radius: Float, level: Int, baseLevel: Int) {
        val c = content ?: return
        val box = Rect(min(x0, x1) - radius, min(y0, y1) - radius, max(x0, x1) + radius, max(y0, y1) + radius)
        val rnd = threadRenderer.get()!!
        for (tile in tiles.values) {
            if (tile.key.level != level && tile.key.level != baseLevel) continue
            if (!tile.intersects(box)) continue
            val cv = tile.canvas
            // Circle at the new pen position, plus the band joining it to the previous one.
            wipeClip.rewind()
            wipeClip.addCircle(x1, y1, radius, Path.Direction.CW)
            cv.save(); cv.clipPath(wipeClip); paint(cv, c, box, rnd, inkToo = false); cv.restore()
            val dx = x1 - x0; val dy = y1 - y0
            val len = kotlin.math.hypot(dx, dy)
            if (len > 0f) {
                val nx = -dy / len * radius; val ny = dx / len * radius
                wipeClip.rewind()
                wipeClip.moveTo(x0 + nx, y0 + ny); wipeClip.lineTo(x1 + nx, y1 + ny)
                wipeClip.lineTo(x1 - nx, y1 - ny); wipeClip.lineTo(x0 - nx, y0 - ny); wipeClip.close()
                cv.save(); cv.clipPath(wipeClip); paint(cv, c, box, rnd, inkToo = false); cv.restore()
            }
            wiped += tile.key
        }
    }

    /** The wipe was cancelled: those tiles no longer match the strokes, so drop them. */
    fun discardWiped() {
        for (k in wiped) tiles.remove(k)
        wiped.clear()
    }

    /** The wipe was committed (or touched only paper): keep the tiles as they are. */
    fun keepWiped() = wiped.clear()

    // ------------------------------------------------------------- scheduling

    private fun setWanted(order: List<Key>) {
        wanted.clear(); wanted.addAll(order)
        wantedSet = HashSet(order)
        schedule()
    }

    private fun schedule() {
        if (released) return
        val c = content ?: return
        while (inFlight.size < WORKERS) {
            val key = wanted.firstOrNull { it !in inFlight } ?: return
            wanted.remove(key)
            inFlight += key
            executor.execute { work(key, c) }
        }
    }

    /** Worker thread. */
    private fun work(key: Key, c: Content) {
        val tile = if (key in wantedSet) {
            val t0 = System.nanoTime()
            try { render(key, c) } catch (_: OutOfMemoryError) { null }.also { renderMs += (System.nanoTime() - t0) / 1e6f }
        } else null
        main.post {
            inFlight -= key
            if (!released && tile != null && tile.gen == gen) {
                // The page may have changed while rendering; sync() catches the tile up.
                tiles[key] = tile
                tile.lastUsed = frame
                onTilesChanged()
            }
            schedule()
        }
    }

    /** Worker thread: a brand-new tile; never touches cached tiles. */
    private fun render(key: Key, c: Content): Tile {
        val bmp = Bitmap.createBitmap(TILE_PX, TILE_PX, Bitmap.Config.ARGB_8888)
        val tile = Tile(key, bmp, c.strokes, c.excluded, c.gen)
        paint(tile.canvas, c, Rect(tile.left, tile.top, tile.left + tile.size, tile.top + tile.size), threadRenderer.get()!!)
        bmp.setHasAlpha(false)
        bmp.prepareToDraw() // starts the GPU upload now, not in the frame that first shows it
        return tile
    }

    /** Paper, PDF and (unless [inkToo] is false) the strokes intersecting [area], in page order. */
    private fun paint(cv: Canvas, c: Content, area: Rect, rnd: PageRenderer, inkToo: Boolean = true) {
        cv.drawColor(DESK)
        rnd.drawPaper(cv, c.paper, c.pageW, c.pageH, hasPdf = c.pdf != null)
        c.pdf?.let { rnd.drawPdfBackground(cv, it, c.pageW, c.pageH) }
        if (!inkToo) return
        for (i in c.index.query(area)) {
            val s = c.strokes[i]
            if (s.id in c.excluded) continue
            rnd.drawStroke(cv, s.color, s.tool, outline(s))
        }
    }

    private fun evict(level: Int, baseLevel: Int) {
        if (tiles.size <= MAX_TILES) return
        val victims = tiles.values
            .filter { it.lastUsed != frame && it.key.level != baseLevel }
            .sortedBy { it.lastUsed }
        for (t in victims.take(tiles.size - MAX_TILES)) tiles.remove(t.key)
    }

    companion object {
        const val TILE_PX = 512
        /** Most tiles kept (~1 MB each). The base level is never evicted. */
        const val MAX_TILES = 64
        /** Above this many strokes in an edited area, re-render the tile in the background instead of patching on the UI thread. */
        const val PATCH_LIMIT = 300
        private const val WORKERS = 3
        private const val DESK = 0xFFE8E6E1.toInt()

        /** Level k renders at 2^(k/2) pixels per page unit. */
        fun levelScale(k: Int): Float = Math.pow(2.0, k / 2.0).toFloat()

        /** Sharpest-needed level for a view scale; at most 11 % upscaling (2^0.15). */
        fun levelFor(scale: Float): Int = ceil(2 * log2(scale) - 0.3f).toInt()

        private val executor = Executors.newFixedThreadPool(WORKERS) { r ->
            Thread(r, "folio-tiles").apply { priority = Thread.NORM_PRIORITY - 1 }
        }
        private val threadRenderer = ThreadLocal.withInitial { PageRenderer() }
        private val boundsTmp = ThreadLocal.withInitial { RectF() }

        /**
         * The stroke's outline as a Path, built once and cached on the stroke. Bounds are
         * computed before it's shared, so concurrent read-only drawing is safe.
         */
        fun outline(s: Stroke): Path {
            (s.renderCache as? Path)?.let { return it }
            val p = PageRenderer.pathOf(StrokeGeometry.outline(s))
            p.computeBounds(boundsTmp.get()!!, true)
            s.renderCache = p
            return p
        }
    }
}
