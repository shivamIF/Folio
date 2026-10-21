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
import app.folio.core.model.PageAudio
import app.folio.core.model.PageImage
import app.folio.core.model.PageTable
import app.folio.core.model.PaperDensity
import app.folio.core.model.StickyNote
import app.folio.core.model.ToolType
import app.folio.core.model.PaperTemplate
import app.folio.core.model.PdfBackground
import app.folio.core.model.TextBox
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
 * touch a tile that's already cached. Stroke art (outline Paths, pencil meshes) is built
 * once per stroke ([art]) and shared read-only by all threads.
 *
 * With continuous scrolling each page on screen has its own cache; they share a [TileBudget]
 * (one LRU clock and tile limit, one cap on renders in flight).
 */
internal class TileCache(private val budget: TileBudget, private val onTilesChanged: () -> Unit) {

    data class Key(val level: Int, val col: Int, val row: Int)

    /**
     * What tiles are rendered from. [gen] changes when the page, paper, size or PDF changes.
     * [pdfBitmap] is a low-resolution render of the PDF page, used only where a tile can't
     * render the PDF itself (fallbacks, eraser wipes); tiles render it sharp via [assets].
     */
    class Content(
        val gen: Int, val index: StrokeIndex, val excluded: Set<String>,
        val paper: PaperTemplate, val pageW: Float, val pageH: Float,
        val pdf: PdfBackground?, val pdfBitmap: Bitmap?,
        val texts: List<TextBox>, val images: List<PageImage>, val assets: PageAssets?,
        val objects: PageObjects = PageObjects.NONE, val look: PageLook = PageLook.LIGHT,
    ) {
        val strokes: List<Stroke> get() = index.strokes
        /** Bounds of tape and sticky notes: ink added under them needs a repaint, not an append. */
        val overlays: List<Rect> by lazy {
            val out = ArrayList<Rect>()
            for (st in strokes) if (st.tool == ToolType.TAPE) out += st.bounds()
            for (n in objects.stickies) out += n.bounds
            out
        }
    }

    class Tile(
        val key: Key, val bitmap: Bitmap, var strokes: List<Stroke>, var excluded: Set<String>, val gen: Int,
        var texts: List<TextBox>, var images: List<PageImage>, var objects: PageObjects,
    ) {
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
    private val frame get() = budget.frame
    private var released = false
    /** The whole-page level last drawn or prefetched: never evicted while this page is loaded. */
    private var baseLevel = Int.MIN_VALUE

    /** A picture of the page (its sidebar thumbnail) shown where no tile is ready yet. */
    var preview: Bitmap? = null

    init { budget.register(this) }

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

    /** Main thread: sets what the page shows. Page/paper/size/PDF/asset changes start over. */
    fun setContent(
        pageKey: Any?, index: StrokeIndex, excluded: Set<String>, paper: PaperTemplate, pageW: Float, pageH: Float,
        pdf: PdfBackground?, pdfBitmap: Bitmap?, texts: List<TextBox>, images: List<PageImage>, assets: PageAssets?,
        objects: PageObjects = PageObjects.NONE, look: PageLook = PageLook.LIGHT,
    ) {
        val strokes = index.strokes
        val c = content
        val same = c != null && pageKey == this.pageKey && paper == c.paper && pageW == c.pageW && pageH == c.pageH &&
            pdf == c.pdf && assets === c.assets && look == c.look
        if (!same) {
            gen++
            tiles.clear()
            this.pageKey = pageKey
        }
        if (c == null || !same || c.strokes !== strokes || c.excluded !== excluded || c.texts !== texts ||
            c.images !== images || c.pdfBitmap !== pdfBitmap || c.objects != objects
        ) {
            content = Content(gen, index, excluded, paper, pageW, pageH, pdf, pdfBitmap, texts, images, assets, objects, look)
        }
    }
    private var pageKey: Any? = null

    fun release() {
        released = true
        tiles.clear(); wanted.clear(); wantedSet = emptySet()
        budget.unregister(this)
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
        val k = levelFor(scale)
        val kBase = min(levelFor(baseScale), k)
        baseLevel = kBase
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
    }

    /**
     * The page isn't on screen but is next to it: render its whole-page level ahead, so it
     * scrolls in showing its ink. Runs after the visible pages in [TileBudget.order].
     */
    fun prefetch(scale: Float, baseScale: Float) {
        val c = content ?: return
        val kBase = min(levelFor(baseScale), levelFor(scale))
        baseLevel = kBase
        val order = ArrayList<Key>()
        addLevel(order, kBase, 0f, 0f, c.pageW, c.pageH)
        setWanted(order)
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
        c.look.applyTo(renderer, c.pdf != null)
        renderer.drawPaper(canvas, c.paper, c.pageW, c.pageH, hasPdf = c.pdf != null)
        (if (c.look.dark && c.pdf == null) null else preview ?: c.pdfBitmap)?.let { renderer.drawPdfBackground(canvas, it, c.pageW, c.pageH) }
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
        val sameElements = tile.texts === c.texts && tile.images === c.images && tile.objects == c.objects
        if (sameElements && tile.strokes === c.strokes && (tile.excluded === c.excluded || tile.excluded == c.excluded)) {
            tile.excluded = c.excluded
            return
        }
        if (tile.stale) return // a fresh render is on its way
        val elementRects = if (sameElements && (tile.excluded === c.excluded || tile.excluded == c.excluded)) emptyList()
        else elementChanges(tile, c)
        val d = diff(tile.strokes, tile.excluded, c)
        val underOverlay = d is Diff.Append && c.overlays.isNotEmpty() && d.added.any { a -> a.tool != ToolType.TAPE && c.overlays.any { it.intersects(a.bounds()) } }
        if (d is Diff.Append && elementRects.isEmpty() && !underOverlay) {
            threadRenderer.get()!!.let { c.look.applyTo(it, c.pdf != null) }
            for (s in d.added) if (s.id !in c.excluded && tile.intersects(s.bounds())) {
                threadRenderer.get()!!.drawStroke(tile.canvas, StrokeArt.of(s))
            }
            // Text sits above ink: repaint any text the new strokes went under.
            for (t in c.texts) if (t.id !in c.excluded && tile.intersects(t.bounds) && d.added.any { it.bounds().intersects(t.bounds) }) {
                threadRenderer.get()!!.drawText(tile.canvas, t)
            }
            adopt(tile, c)
            return
        }
        val rects = when (d) { is Diff.Changed -> d.rects + elementRects; is Diff.Append -> d.added.map { it.bounds() } + elementRects }
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (x in rects) if (tile.intersects(x)) {
            l = min(l, x.left); t = min(t, x.top); r = max(r, x.right); b = max(b, x.bottom)
        }
        if (l > r) { adopt(tile, c); return }
        val region = alignToPixels(tile, l, t, r, b)
        if (c.index.query(region).size > PATCH_LIMIT || !patch(tile, region, c)) {
            tile.stale = true // keep showing it; re-render with priority (see draw)
            return
        }
        perfPatches++
        adopt(tile, c)
    }

    private fun adopt(tile: Tile, c: Content) {
        tile.strokes = c.strokes; tile.excluded = c.excluded; tile.texts = c.texts; tile.images = c.images; tile.objects = c.objects
    }

    /** Bounds of text boxes and pictures that were added, removed, changed, or hidden/shown since [tile] was drawn. */
    private fun elementChanges(tile: Tile, c: Content): List<Rect> {
        val out = ArrayList<Rect>()
        fun <T : Any> compare(old: List<T>, new: List<T>, id: (T) -> String, bounds: (T) -> Rect) {
            val oldSet = Collections.newSetFromMap(IdentityHashMap<T, Boolean>()).apply { addAll(old) }
            val newSet = Collections.newSetFromMap(IdentityHashMap<T, Boolean>()).apply { addAll(new) }
            for (e in old) if (e !in newSet || ((id(e) in tile.excluded) != (id(e) in c.excluded))) out += bounds(e)
            for (e in new) if (e !in oldSet) out += bounds(e)
        }
        compare(tile.texts, c.texts, TextBox::id, TextBox::bounds)
        compare(tile.images, c.images, PageImage::id, PageImage::bounds)
        compare(tile.objects.stickies, c.objects.stickies, StickyNote::id) { it.bounds.inflate(it.width * 0.05f) }
        compare(tile.objects.tables, c.objects.tables, PageTable::id, PageTable::bounds)
        compare(tile.objects.audio, c.objects.audio, PageAudio::id, PageAudio::bounds)
        return out
    }

    /**
     * Repaints [region] (page units) of [tile] from scratch: paper, PDF, pictures, strokes and
     * text there, in order. False if a picture isn't decoded yet (the tile is re-rendered instead).
     */
    private fun patch(tile: Tile, region: Rect, c: Content): Boolean {
        val cv = tile.canvas
        cv.save()
        cv.clipRect(region.left, region.top, region.right, region.bottom)
        val ok = paint(cv, c, region, threadRenderer.get()!!, tile = tile, decode = false)
        cv.restore()
        return ok
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
            cv.save(); cv.clipPath(wipeClip); paint(cv, c, box, rnd, inkToo = false, decode = false); cv.restore()
            val dx = x1 - x0; val dy = y1 - y0
            val len = kotlin.math.hypot(dx, dy)
            if (len > 0f) {
                val nx = -dy / len * radius; val ny = dx / len * radius
                wipeClip.rewind()
                wipeClip.moveTo(x0 + nx, y0 + ny); wipeClip.lineTo(x1 + nx, y1 + ny)
                wipeClip.lineTo(x1 - nx, y1 - ny); wipeClip.lineTo(x0 - nx, y0 - ny); wipeClip.close()
                cv.save(); cv.clipPath(wipeClip); paint(cv, c, box, rnd, inkToo = false, decode = false); cv.restore()
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

    /** Starts renders while the shared budget has a free worker. */
    fun schedule() {
        if (released) return
        val c = content ?: return
        while (budget.inFlight < budget.workers) {
            val key = wanted.firstOrNull { it !in inFlight } ?: return
            wanted.remove(key)
            inFlight += key
            budget.inFlight++
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
            budget.inFlight--
            if (!released && tile != null && tile.gen == gen) {
                // The page may have changed while rendering; sync() catches the tile up.
                tiles[key] = tile
                tile.lastUsed = frame
                onTilesChanged()
            }
            budget.scheduleAll()
        }
    }

    /** Worker thread: a brand-new tile; never touches cached tiles. */
    private fun render(key: Key, c: Content): Tile {
        val bmp = Bitmap.createBitmap(TILE_PX, TILE_PX, Bitmap.Config.ARGB_8888)
        val tile = Tile(key, bmp, c.strokes, c.excluded, c.gen, c.texts, c.images, c.objects)
        paint(tile.canvas, c, Rect(tile.left, tile.top, tile.left + tile.size, tile.top + tile.size), threadRenderer.get()!!, tile = tile)
        bmp.setHasAlpha(false)
        bmp.prepareToDraw() // starts the GPU upload now, not in the frame that first shows it
        return tile
    }

    /**
     * Paper, PDF, pictures, strokes and text intersecting [area], in that order (only the
     * background unless [inkToo]). With a [tile] and a rectangular area, the PDF is rendered
     * sharp at the tile's scale; otherwise the low-resolution page bitmap is used.
     * Returns false if a picture wasn't decoded and [decode] is false.
     */
    private fun paint(
        cv: Canvas, c: Content, area: Rect, rnd: PageRenderer,
        inkToo: Boolean = true, tile: Tile? = null, decode: Boolean = true,
    ): Boolean {
        cv.drawColor(c.look.desk)
        c.look.applyTo(rnd, c.pdf != null)
        rnd.drawPaper(cv, c.paper, c.pageW, c.pageH, hasPdf = c.pdf != null)
        val pdf = c.pdf
        if (pdf != null) {
            val sharp = tile != null && c.assets != null && c.assets.renderPdf(
                tile.bitmap, pdf, c.pageW, c.pageH, tile.scale, tile.left, tile.top, pixelRect(tile, area),
            )
            if (!sharp) c.pdfBitmap?.let { rnd.drawPdfBackground(cv, it, c.pageW, c.pageH) }
        }
        if (!inkToo) return true
        var complete = true
        for (img in c.images) {
            if (img.id in c.excluded || !img.bounds.intersects(area)) continue
            val bmp = c.assets?.image(img.attachmentId, decode)
            if (bmp == null) { if (!decode) complete = false; rnd.drawMissingImage(cv, img); continue }
            rnd.drawImage(cv, bmp, img)
        }
        for (t in c.objects.tables) if (t.id !in c.excluded && t.bounds.intersects(area)) rnd.drawTable(cv, t)
        // Tape goes over everything else, so it's drawn after the other ink and the text.
        var tape: ArrayList<Stroke>? = null
        for (i in c.index.query(area)) {
            val s = c.strokes[i]
            if (s.id in c.excluded) continue
            if (s.tool == ToolType.TAPE) { (tape ?: ArrayList<Stroke>().also { tape = it }) += s; continue }
            rnd.drawStroke(cv, StrokeArt.of(s))
        }
        for (t in c.texts) if (t.id !in c.excluded && t.bounds.intersects(area)) rnd.drawText(cv, t)
        for (a in c.objects.audio) if (a.id !in c.excluded && a.bounds.intersects(area)) rnd.drawAudio(cv, a, a.id == c.objects.playing)
        tape?.forEach { rnd.drawStroke(cv, StrokeArt.of(it)) }
        for (n in c.objects.stickies) if (n.id !in c.excluded && n.bounds.inflate(n.width * 0.05f).intersects(area)) rnd.drawSticky(cv, n)
        return complete
    }

    /** [area] (page units) as a pixel rectangle inside [tile]'s bitmap. */
    private fun pixelRect(tile: Tile, area: Rect) = android.graphics.Rect(
        max(0, floor((area.left - tile.left) * tile.scale).toInt()), max(0, floor((area.top - tile.top) * tile.scale).toInt()),
        min(TILE_PX, ceil((area.right - tile.left) * tile.scale).toInt()), min(TILE_PX, ceil((area.bottom - tile.top) * tile.scale).toInt()),
    )

    /** Tiles the budget may evict (everything but the whole-page level). */
    internal fun evictableCount(): Int { var n = 0; for (k in tiles.keys) if (k.level != baseLevel) n++; return n }

    internal fun collectEvictable(out: MutableList<Pair<TileCache, Tile>>) {
        for (t in tiles.values) if (t.lastUsed != frame && t.key.level != baseLevel) out += this to t
    }

    internal fun drop(key: Key) { tiles.remove(key) }

    companion object {
        const val TILE_PX = 512
        /** Most tiles kept across all pages (~1 MB each), besides each loaded page's base level. */
        const val MAX_TILES = 64
        /** Above this many strokes in an edited area, re-render the tile in the background instead of patching on the UI thread. */
        const val PATCH_LIMIT = 300
        const val WORKERS = 3

        /** Level k renders at 2^(k/2) pixels per page unit. */
        fun levelScale(k: Int): Float = Math.pow(2.0, k / 2.0).toFloat()

        /** Sharpest-needed level for a view scale; at most 11 % upscaling (2^0.15). */
        fun levelFor(scale: Float): Int = ceil(2 * log2(scale) - 0.3f).toInt()

        private val executor = Executors.newFixedThreadPool(WORKERS) { r ->
            Thread(r, "folio-tiles").apply { priority = Thread.NORM_PRIORITY - 1 }
        }
        private val threadRenderer = ThreadLocal.withInitial { PageRenderer() }
        /** The stroke's drawable art (outline Path or pencil mesh), built once and cached on the stroke. */
        fun art(s: Stroke): StrokeArt = StrokeArt.of(s)
    }
}

/**
 * Shared by the [TileCache]s of the pages on screen (continuous scrolling, ADR-10): one frame
 * clock for least-recently-used eviction, one tile limit across all pages, and one cap on tiles
 * rendering at once. Free workers go to the caches in [order]: the visible pages first (most
 * visible first), then neighbours being prefetched. Main thread only.
 */
internal class TileBudget(private val maxTiles: Int = TileCache.MAX_TILES, val workers: Int = TileCache.WORKERS) {
    /** Advanced once per drawn frame by the canvas. */
    var frame = 0L
        private set
    private val caches = ArrayList<TileCache>()
    var order: List<TileCache> = emptyList()
    internal var inFlight = 0

    fun nextFrame() { frame++ }

    internal fun register(c: TileCache) { caches += c }
    internal fun unregister(c: TileCache) { caches.remove(c) }

    /** A render finished: hand the free worker to the most important page that wants one. */
    fun scheduleAll() {
        for (c in order) { if (inFlight >= workers) return; c.schedule() }
    }

    /** End of a frame: evict the least recently used tiles above the limit (none drawn this frame). */
    fun trim() {
        var n = 0
        for (c in caches) n += c.evictableCount()
        if (n <= maxTiles) return
        val victims = ArrayList<Pair<TileCache, TileCache.Tile>>()
        for (c in caches) c.collectEvictable(victims)
        victims.sortBy { it.second.lastUsed }
        for ((c, t) in victims.take(n - maxTiles)) c.drop(t.key)
    }
}

/** Sticky notes, tables and recordings of a page (schema 6), and which recording is playing. */
data class PageObjects(
    val stickies: List<StickyNote> = emptyList(),
    val tables: List<PageTable> = emptyList(),
    val audio: List<PageAudio> = emptyList(),
    val playing: String? = null,
) {
    val isEmpty: Boolean get() = stickies.isEmpty() && tables.isEmpty() && audio.isEmpty()

    companion object { val NONE = PageObjects() }
}

/** How pages are shown on screen: the editor theme and the notebook's page settings. */
data class PageLook(
    val dark: Boolean = false,
    val pageColor: Long? = null,
    val density: PaperDensity = PaperDensity.NORMAL,
    val hideTape: Boolean = false,
    val desk: Int = 0xFFE8E6E1.toInt(),
) {
    /** Sets [r] up to draw a page; a page with a PDF background is never darkened (its print is dark on white). */
    fun applyTo(r: PageRenderer, hasPdf: Boolean) {
        r.dark = dark && !hasPdf
        r.pageColor = if (hasPdf) null else pageColor
        r.density = density
        r.hideTape = hideTape
        r.drawControls = true
    }

    companion object { val LIGHT = PageLook() }
}
