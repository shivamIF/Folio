package app.folio.notes.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.OverScroller
import app.folio.notes.input.PageStack
import app.folio.notes.input.PageViewport
import androidx.input.motionprediction.MotionEventPredictor
import app.folio.core.ink.PenProfile
import app.folio.core.ink.PressureCurve
import app.folio.core.ink.SelectionTransform
import app.folio.core.ink.StrokeBuilder
import app.folio.core.ink.StrokeGeometry
import app.folio.core.ink.StrokeIndex
import app.folio.core.ink.Shape
import app.folio.core.ink.ShapeKind
import app.folio.core.ink.ScribbleErase
import app.folio.core.ink.ShapeRecognizer
import app.folio.core.ink.InkShaping
import app.folio.core.model.Argb
import app.folio.core.model.PageImage
import app.folio.core.model.PaperTemplate
import app.folio.core.model.PdfBackground
import app.folio.core.model.TextBox
import app.folio.core.model.Stroke
import app.folio.core.model.StrokeLook
import app.folio.core.model.ToolType
import app.folio.notes.editor.oneui.ColorMath
import app.folio.notes.input.GestureAction
import app.folio.notes.input.InputPolicy
import app.folio.notes.input.PointerKind
import kotlin.math.max


/**
 * The writing surface: the notebook's pages in one scrolling column (ADR-10). It owns only
 * transient input/render state. The pages and their content come from [setDocument], and
 * finished edits go out through [listener], each with the id of the page it belongs to.
 *
 * Input path: MotionEvent → [InputPolicy] decides draw/erase/navigate/ignore → at pen-down
 * the page under the pen becomes the *active* page for the whole gesture → every historical
 * sample is mapped to that page's coordinates (even past its edge) → [StrokeBuilder]
 * stabilises it → outline from [StrokeGeometry] → Path → canvas.
 * Predicted samples (androidx motion prediction) are drawn ahead of the pen
 * tip but are never stored.
 */
@SuppressLint("ViewConstructor")
class InkCanvasView(context: Context) : SurfaceView(context), SurfaceHolder.Callback2, Choreographer.FrameCallback {

    // ---- rendering surface ----
    // The page is drawn on its own surface rather than in the app window. Measured on a
    // Galaxy Tab S6 Lite: as a plain View, every page frame (pen, pinch, pan) made Android
    // redraw the whole window as well (toolbar included: ~5.6 ms of render-thread work)
    // on a GPU that is already fill-rate bound. Now a page frame touches only this surface,
    // which the display hardware composites under the window.
    private var surfaceReady = false
    private var frameScheduled = false

    /**
     * Schedules one surface frame on the next vsync. Every redraw in this class goes
     * through here. Don't override invalidate(): SurfaceView relies on it to punch the
     * window hole the page shows through.
     */
    private fun requestRender() {
        if (frameScheduled || !surfaceReady) return
        frameScheduled = true
        Choreographer.getInstance().postFrameCallback(this)
    }


    override fun doFrame(frameTimeNanos: Long) {
        frameScheduled = false
        if (!surfaceReady) return
        stepFling()
        val c = try { holder.lockHardwareCanvas() } catch (e: Exception) { Log.w(TAG, "lockHardwareCanvas failed", e); null } ?: return
        try { renderFrame(c) } finally { holder.unlockCanvasAndPost(c) }
        // Debug: pair each posted ink frame with its newest pen sample (scripts/latency_bench.py
        // matches the post time to SurfaceFlinger's present time).
        if (debugInput && builder != null && !wetLive && perfLastInputNanos > 0) perfFrames += "%d:%d".format(System.nanoTime(), perfLastInputNanos)
        // Finished strokes now in a posted page frame: let the wet layer drop them shortly.
        val now = System.nanoTime()
        var marked = false
        for (h in wetHeld) if (h.postedAt == 0L && shownOnPage(h)) { h.postedAt = now; marked = true }
        if (marked) Choreographer.getInstance().postFrameCallback(wetTick)
    }

    // ---- wet ink (front-buffered live stroke, see WetInkLayer) ----
    internal var wet: WetInkLayer? = null
    /** The stroke in progress is drawn on the wet layer instead of the page surface. */
    private var wetLive = false
    /** Finished strokes still shown wet until the page surface has displayed them. */
    private class WetHeld(val slot: PageSlot, val stroke: Stroke, val art: StrokeArt) { var postedAt = 0L }
    private val wetHeld = ArrayList<WetHeld>()
    private var wetTail: RectF? = null
    private val wetMatrix = FloatArray(9)
    private val wetRect = RectF()

    /** The page surface draws this held stroke (as a pending commit or from its page's list). */
    private fun shownOnPage(h: WetHeld) = h.stroke in h.slot.pendingCommits || h.slot.strokeIndex.indexOf(h.stroke.id) >= 0

    private fun canUseWet() = wet?.ready == true && tool != ToolType.HIGHLIGHTER && !lassoMode && !textMode && !laserMode && !restyleMode

    /** Page→view transform and an art's bounds, in view pixels. */
    private fun viewBounds(art: StrokeArt, out: RectF): RectF {
        art.path.computeBounds(out, false)
        pageToView.mapRect(out)
        out.inset(-3f, -3f)
        return out
    }

    /**
     * Shows the live stroke (and held ones) on the wet layer. Only the area around the pen tip is
     * repainted ([full] repaints everything: a new stroke or a snapped shape).
     */
    private fun pushWet(full: Boolean, extraClip: RectF? = null) {
        val w = wet ?: return
        val b = builder
        val arts = ArrayList<StrokeArt>(wetHeld.size + 1)
        for (h in wetHeld) arts += h.art
        var tail: RectF? = null
        if (b != null && wetLive) {
            val live = liveSamples(b)
            arts += live.art
            // Tail: the last samples (geometry there can still settle) plus prediction.
            val from = maxOf(0, live.real - 12)
            var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var bt = -Float.MAX_VALUE
            for (i in from until live.n) { l = minOf(l, live.xs[i]); r = maxOf(r, live.xs[i]); t = minOf(t, live.ys[i]); bt = maxOf(bt, live.ys[i]) }
            if (live.n > 0) {
                val pad = penWidth * tool.inkReach + 1f
                tail = RectF(l - pad, t - pad, r + pad, bt + pad).also { pageToView.mapRect(it); it.inset(-3f, -3f) }
            }
        }
        val prev = wetTail
        val clip = when {
            full || snapped != null -> null
            tail == null -> extraClip?.let { RectF(it) } // nothing live: repaint just that area
            prev == null -> null
            else -> RectF(tail).apply { union(prev); extraClip?.let { union(it) } }
        }
        wetTail = tail
        pageToView.getValues(wetMatrix)
        w.show(WetFrame(wetMatrix.copyOf(), arts, clip, if (debugInput) perfLastInputNanos else 0L, active.width, active.height, pageLook.dark && active.pdf == null))
    }

    /** Drops held strokes the page surface has shown for a few frames (by then on screen). */
    private val wetTick = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val now = System.nanoTime()
            var removed: RectF? = null
            val it = wetHeld.iterator()
            while (it.hasNext()) {
                val h = it.next()
                if (h.postedAt != 0L && now - h.postedAt >= WET_HOLD_NANOS) {
                    val r = viewBounds(h.art, RectF())
                    removed = removed?.apply { union(r) } ?: r
                    it.remove()
                }
            }
            if (removed != null) {
                if (wetLive) pushWet(full = false, extraClip = removed)
                else if (wetHeld.isEmpty()) { wet?.clear(); wetTail = null }
                else pushWet(full = false, extraClip = removed)
            }
            if (wetHeld.any { it.postedAt != 0L }) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Pan/zoom etc.: the page shows everything; drop the wet copies at once. */
    private fun dropWet() {
        if (wetHeld.isEmpty() && !wetLive) return
        wetHeld.clear(); wetLive = false; wetTail = null
        wet?.clear()
    }

    override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true; requestRender() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { requestRender() }

    /**
     * The surface was resized (sidebar opened or closed, rotation, split screen): draw at the
     * new size before returning. Otherwise the last frame, drawn at the old size, stays up
     * stretched to the new frame (seen on the Tab S6 Lite as a black strip beside the page).
     */
    override fun surfaceRedrawNeeded(holder: SurfaceHolder) {
        if (!surfaceReady) return
        val c = try { holder.lockHardwareCanvas() } catch (e: Exception) { Log.w(TAG, "lockHardwareCanvas failed", e); null } ?: return
        try { renderFrame(c) } finally { holder.unlockCanvasAndPost(c) }
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        if (frameScheduled) { Choreographer.getInstance().removeFrameCallback(this); frameScheduled = false }
    }

    /** Every edit names its page ([pageId]); coordinates are in that page's units. */
    interface Listener {
        fun onStrokeFinished(pageId: String, stroke: Stroke)
        fun onStrokesErased(pageId: String, ids: Set<String>)
        /** A lasso loop finished; [ids] is the new selection on [pageId] (empty clears it). */
        fun onLassoSelected(pageId: String, ids: Set<String>)
        /** The selection was moved or resized; commit as one undoable edit. */
        fun onSelectionTransformed(pageId: String, ids: Set<String>, transform: SelectionTransform)
        /** Partial eraser finished: each original stroke id maps to the pieces that remain. */
        fun onStrokesSplit(pageId: String, pieces: Map<String, List<Stroke>>)
        /** Text tool: the page was tapped at ([x], [y]); [textId] is the text box there, if any. */
        fun onTextTap(pageId: String, x: Float, y: Float, textId: String?) {}
        /** Zoom or scroll changed (only reported while [InkCanvasView.reportViewport] is on). */
        fun onViewportChanged() {}
        /**
         * Scrolling changed which pages are on screen ([visible], top to bottom, never empty) or
         * which one is in view ([current], under the reading line). Reported only on change.
         */
        fun onPagesInView(visible: List<String>, current: String) {}
        /** Strokes replaced in place by id (Change style, tape peeled or covered); one undo step. */
        fun onStrokesReplaced(pageId: String, after: List<Stroke>) {}
        /** Pinch zoom changed: [percent] of fit-width. */
        fun onZoomChanged(percent: Int) {}
        /** Eyedropper: the colour under the pen. */
        fun onColorPicked(color: Long) {}
        /** Ink written on sticky note [stickyId] (in the note's own coordinates). */
        fun onStickyStroke(pageId: String, stickyId: String, stroke: Stroke) {}
        /** A tap on a page object (a recording, a table, a minimised sticky note); true if handled. */
        fun onObjectTap(pageId: String, objectId: String, x: Float, y: Float): Boolean = false
        /** Open the pen wheel at view point ([vx], [vy]): the pen was held still, or its side button clicked in the air. */
        fun onPenWheel(vx: Float, vy: Float) {}
        /** The pen that opened the wheel by holding is still down and moved. */
        fun onPenWheelDrag(vx: Float, vy: Float) {}
        /** That pen lifted. */
        fun onPenWheelRelease(vx: Float, vy: Float) {}
        /** A writing stroke began (S Pen to text doesn't read words while the pen is still writing). */
        fun onPenDown() {}
        /** A writing stroke was committed (S Pen to text waits for a pause after this). */
        fun onPenLifted() {}
        /** The tape strip on top of sticky note [stickyId] was tapped: collapse the note. */
        fun onStickyTapeTap(pageId: String, stickyId: String) {}
        /** The easy writing pad's window was moved or resized on the page. */
        fun onPadWindowChanged(pageId: String, rect: app.folio.core.model.Rect) {}
    }

    var listener: Listener? = null

    // ---- tool settings (pushed in from the editor UI) ----
    var tool: ToolType = ToolType.FOUNTAIN
    var color: Argb = 0xFF1B1B1F
    var penWidth: Float = 3f
    var stabilization: Float = 0.3f
    var pressureCurve: PressureCurve = PressureCurve()
    var eraserSelected: Boolean = false
        set(value) { field = value; requestRender() }
    /** Partial eraser (cuts strokes) instead of the whole-stroke eraser. Also used by the side button. */
    var eraserPartial: Boolean = false
    /** Eraser radius in dp on screen (the hover ring shows it). */
    var eraserSizeDp: Float = 14f
    /** Only highlighter strokes can be erased; writing is protected. */
    var eraseHighlighterOnly: Boolean = false
    /** Lasso tool active: the pen selects instead of writing. */
    var lassoMode: Boolean = false
        set(value) { field = value; requestRender() }
    /** Text tool active: a tap places or edits a text box. */
    var textMode: Boolean = false
    /** How new strokes look (line style, fixed width, round highlighter, tape pattern). */
    var look: StrokeLook = StrokeLook.DEFAULT
    /** Pen-up replaces the stroke with a straight line from its start to its end. */
    var straighten: Boolean = false
    /** Highlighter/tape: pen-up fits the width to the writing it covers. */
    var adjustToText: Boolean = false
    /** Every pen stroke that looks like a shape snaps to it on lift (no hold needed). */
    var autoFixShapes: Boolean = false
    /** Fingers can't zoom or pan sideways (vertical scrolling still works). */
    var lockCanvas: Boolean = false
    /** Lasso takes strokes it only crosses (off: only strokes wholly inside). */
    var selectPartial: Boolean = true
    /** The eraser only takes tape. */
    var eraseTapeOnly: Boolean = false
    /** Reading mode: the pen scrolls instead of writing (unless the laser is on). */
    var readOnly: Boolean = false
    /** Laser pointer: a glowing trail that fades, never saved. */
    var laserMode: Boolean = false
        set(value) { field = value; if (!value) laser.clear() }
    var laserStyle: LaserStyle = LaserStyle.SOLID
    var laserRainbow: Boolean = true
    var laserColor: Long = 0xFFE8473F
    /** Change style: sweeping restyles touched strokes to [restyleColor] and [restyleWidth]. */
    var restyleMode: Boolean = false
    var restyleColor: Long = 0xFF2E7DD7
    var restyleWidth: Float = 3.5f
    /** The next pen or finger tap picks a colour from the page (see [Listener.onColorPicked]). */
    var eyedropperActive: Boolean = false
    /** Theme and page settings of the notebook (dark pages, colour, line spacing, hidden tape). */
    var pageLook: PageLook = PageLook.LIGHT
        set(value) { if (field != value) { field = value; deskPaint.color = value.desk; requestRender() } }
    /** "3/12" at each page's bottom-right corner. */
    var showPageNumbers: Boolean = true
    /** The easy writing pad's window, drawn on its page (null while the pad is closed). */
    var padWindow: PadWindow? = null
        set(value) { if (field != value) { field = value; requestRender() } }
    /** Elements hidden from the page while being edited elsewhere (the text box being typed). */
    var hiddenIds: Set<String> = emptySet()
        set(value) { if (field != value) { field = value; requestRender() } }
    /** Report zoom/scroll changes to the listener (e.g. to keep a text field over its box). */
    var reportViewport: Boolean = false

    /** Current zoom: view pixels per page unit (the same on every page). */
    val pageScale: Float get() = viewport.scale

    /** Where page [pageId]'s top-left corner is in view pixels, or null if it isn't laid out. */
    fun pageOrigin(pageId: String): Pair<Float, Float>? {
        val s = stack ?: return null
        val i = s.indexOf(pageId).takeIf { it >= 0 } ?: return null
        return viewport.tx + s.left(i) * scale to viewport.ty + s.top(i) * scale
    }

    /** The page in view and its visible part, in its page units; null before layout. */
    fun visiblePageArea(): Pair<String, app.folio.core.model.Rect>? {
        val s = stack ?: return null
        if (s.count == 0 || !viewport.ready) return null
        val i = currentIndex(s)
        val ox = s.left(i); val oy = s.top(i)
        val (l, t) = viewport.toPage(0f, 0f)
        val (r, b) = viewport.toPage(width.toFloat(), height.toFloat())
        return s.ids[i] to app.folio.core.model.Rect(
            maxOf(0f, l - ox), maxOf(0f, t - oy), minOf(s.pageWidth(i), r - ox), minOf(s.pageHeight(i), b - oy),
        )
    }
    /** Rectangle selection instead of a freeform loop. */
    var lassoRectangle: Boolean = false
    /** Currently selected stroke ids, on [selectionPage] (owned by the view model; see [setSelection]). */
    private var selection: Set<String> = emptySet()
        set(value) { if (field != value) { field = value; maybeSnapshotSelection(); requestRender() } }
    private var selectionPage: String? = null

    /**
     * The lasso selection, on [pageId]. A selection always lives on the active page: one
     * made elsewhere (e.g. a picture just inserted on the page in view) makes that page active.
     */
    fun setSelection(pageId: String?, ids: Set<String>) {
        selectionPage = pageId
        if (ids.isNotEmpty() && pageId != null && pageId != active.pageId && action == GestureAction.IGNORE) {
            slots[pageId]?.takeIf { it.loaded }?.let { activate(it) }
        }
        selection = ids
    }

    val policy = InputPolicy(palmTouchMajorPx = 48f * resources.displayMetrics.density, stylusKnown = deviceHasStylus())

    /** True when a pen digitizer (S Pen) is among the input devices; public InputDevice API only. */
    private fun deviceHasStylus(): Boolean = android.view.InputDevice.getDeviceIds().any { id ->
        android.view.InputDevice.getDevice(id)?.let { !it.isVirtual && it.supportsSource(android.view.InputDevice.SOURCE_STYLUS) } == true
    }

    /** User setting: once a stylus has been seen, fingers navigate instead of drawing. */
    var penOnlyPreference: Boolean = true
        set(value) { field = value; if (policy.stylusSeen) policy.penOnly = value }

    // ---- the notebook's pages (ADR-10) ----
    /** Pictures and PDF files of the open notebook. */
    internal var assets: PageAssets? = null
        set(value) { if (field !== value) { field = value; requestRender() } }
    /** Outline path of a committed stroke, built once and shared with the tile renderer. */
    private fun artFor(s: Stroke): StrokeArt = StrokeArt.of(s)

    /** Paper of a page that isn't loaded yet (drawn as its placeholder). */
    var paperOf: (String) -> PaperTemplate = { PaperTemplate.RULED }
    /** A picture of a page (its sidebar thumbnail), shown while it loads or its tiles render. */
    var previewOf: (String) -> android.graphics.Bitmap? = { null }

    private val budget = TileBudget()
    private var stack: PageStack? = null
    /** A new layout that arrived mid-gesture; applied when the gesture ends. */
    private var pendingStack: PageStack? = null
    private var shown: Map<String, LoadedPage> = emptyMap()
    private val slots = HashMap<String, PageSlot>()
    /** Pages kept around the screen (from one above to one below the visible ones), by position. */
    private var window = IntRange.EMPTY
    /** Stands in as the active page until the pen first touches one. */
    private val noPage = PageSlot("", budget) { requestRender() }
    /** The page the current (or last) gesture works on; every per-page field below is its. */
    private var active: PageSlot = noPage

    private val strokes: List<Stroke> get() = active.strokes
    private val texts: List<TextBox> get() = active.texts
    private val images: List<PageImage> get() = active.images
    private val index: StrokeIndex get() = active.strokeIndex
    private val pendingCommits get() = active.pendingCommits
    private val pendingSince get() = active.pendingSince
    private val erasedPending get() = active.erasedPending
    private val partialPieces get() = active.partialPieces
    private var partialCommitted: Boolean
        get() = active.partialCommitted
        set(v) { active.partialCommitted = v }
    private var wipeAwaitingEcho: Boolean
        get() = active.wipeAwaitingEcho
        set(v) { active.wipeAwaitingEcho = v }
    private var prebuiltIndex: StrokeIndex?
        get() = active.prebuiltIndex
        set(v) { active.prebuiltIndex = v }
    private val tiles: TileCache get() = active.tiles

    /**
     * The notebook's pages ([stack], every page) and the content of those loaded near the
     * screen ([pages], by id). Cheap when nothing changed: everything is compared by identity.
     * A new layout keeps the page under the reading line where it is on screen.
     */
    fun setDocument(stack: PageStack, pages: Map<String, LoadedPage>) {
        if (!stack.sameLayout(pendingStack ?: this.stack)) {
            if (action != GestureAction.IGNORE && this.stack != null) pendingStack = stack else relayout(stack)
        }
        if (pages !== shown) {
            shown = pages
            for ((id, p) in pages) {
                val slot = slotFor(id) ?: continue
                if (slot.source !== p) applyContent(slot, p)
            }
            pruneSlots()
        }
        // Pending strokes whose edit never came back (the page was deleted meanwhile) stop showing.
        val now = SystemClock.uptimeMillis()
        for (slot in slots.values) if (slot.loaded && slot.pendingCommits.isNotEmpty()) {
            slot.pendingCommits.removeAll { slot.strokeIndex.indexOf(it.id) >= 0 || now - (slot.pendingSince[it.id] ?: 0) > 1000 }
            slot.pendingSince.keys.retainAll(slot.pendingCommits.mapTo(HashSet()) { it.id })
        }
        // A committed move or resize is drawn from the edited strokes now.
        if (selGesture == SelGesture.NONE) selTransform = SelectionTransform()
        requestRender()
    }

    /** One page on its own, for the debug ink bench (InkBenchActivity). */
    fun setPage(pageId: String, strokes: List<Stroke>, paper: PaperTemplate, width: Float, height: Float) {
        val s = this.stack?.takeIf { it.ids == listOf(pageId) && it.pageWidth(0) == width && it.pageHeight(0) == height }
            ?: PageStack(listOf(pageId), floatArrayOf(width), floatArrayOf(height))
        setDocument(s, mapOf(pageId to LoadedPage(pageId, width, height, paper, null, strokes, emptyList(), emptyList())))
    }

    /** The slot of a page in the column (created, not yet loaded, if there's none). */
    private fun slotFor(pageId: String): PageSlot? {
        slots[pageId]?.let { return it }
        val s = stack ?: return null
        val i = s.indexOf(pageId).takeIf { it >= 0 } ?: return null
        return PageSlot(pageId, budget) { requestRender() }.also { place(it, s, i); slots[pageId] = it }
    }

    private fun place(slot: PageSlot, s: PageStack, i: Int) {
        slot.pos = i
        slot.left = s.left(i); slot.top = s.top(i)
        if (!slot.loaded) { slot.width = s.pageWidth(i); slot.height = s.pageHeight(i); slot.paper = paperOf(slot.pageId) }
    }

    /** Content from the view model for one page (its first load, or an edit echoed back). */
    private fun applyContent(slot: PageSlot, p: LoadedPage) {
        val strokes = p.strokes
        val t0 = System.nanoTime()
        slot.source = p
        if (slot.loaded && p.objects.stickies !== slot.objects.stickies) {
            val before = slot.objects.stickies.associateBy { it.id }
            p.objects.stickies.firstOrNull { n -> before[n.id]?.let { it.minimized != n.minimized } == true }?.let { n ->
                fold = StickyFold(slot.pageId, n.copy(minimized = false), collapsing = n.minimized, start = SystemClock.uptimeMillis())
            }
        }
        slot.texts = p.texts; slot.images = p.images; slot.objects = p.objects
        if (slot === active && sweepCommitted && p.strokes !== slot.strokes) { sweepPreview.clear(); sweepCommitted = false }
        slot.pdf = p.pdf; slot.pdfBitmap = p.background
        slot.paper = p.paper
        slot.width = p.width; slot.height = p.height
        // Any new stroke list is the echo of the last edit, wipe included.
        if (strokes !== slot.strokes) {
            slot.wipeAwaitingEcho = false
            if (perfEraseUpNanos != 0L && slot === active) {
                Log.d("FolioPerf", "erase commit ms=%.1f (pen-up → edited page back)".format((System.nanoTime() - perfEraseUpNanos) / 1e6))
                perfEraseUpNanos = 0L
            }
            // A prebuilt index may hold its own copy of the same strokes; only a new list needs a new index.
            val pre = slot.prebuiltIndex ?: p.index
            slot.prebuiltIndex = null
            // A page index built while loading may lack ink queued meanwhile: it still serves as the base.
            slot.strokeIndex = if (pre != null && sameStrokes(pre.strokes, strokes)) pre else (pre ?: slot.strokeIndex).appending(strokes)
            if (debugInput && strokes.size > 1000) Log.d("FolioPerf", "index build ms=%.1f strokes=%d".format((System.nanoTime() - t0) / 1e6, strokes.size))
        }
        slot.strokes = strokes
        if (slot.pendingCommits.isNotEmpty() || slot.erasedPending.isNotEmpty()) {
            // Look ids up in the index rather than building a set of the whole page.
            val now = SystemClock.uptimeMillis()
            // Normally the echo arrives on the next frame; the timeout covers edits the page never accepts.
            slot.pendingCommits.removeAll { slot.strokeIndex.indexOf(it.id) >= 0 || now - (slot.pendingSince[it.id] ?: 0) > 1000 }
            slot.pendingSince.keys.retainAll(slot.pendingCommits.mapTo(HashSet()) { it.id })
            slot.erasedPending.retainAll { slot.strokeIndex.indexOf(it) >= 0 }
        }
        if (slot.partialCommitted) {
            slot.partialPieces.clear(); slot.partialCommitted = false
            slot.tiles.keepWiped() // the wiped tiles now match; the edit patches them exactly
        }
        if (slot === active && selection.isNotEmpty()) maybeSnapshotSelection()
        if (debugInput && strokes.size > 1000) Log.d("FolioPerf", "setPage ms=%.1f".format((System.nanoTime() - t0) / 1e6))
    }

    /** Lays the column out again, keeping the page under the reading line in place on screen. */
    private fun relayout(s: PageStack) {
        val old = stack
        var anchorId: String? = null; var anchorPos = 0; var anchorOffset = 0f
        if (old != null && old.count > 0 && viewport.ready) {
            val y = readingY()
            anchorPos = old.nearest(y); anchorId = old.ids[anchorPos]; anchorOffset = y - old.top(anchorPos)
        }
        stack = s
        pendingStack = null
        for (slot in slots.values) {
            val i = s.indexOf(slot.pageId)
            if (i >= 0) place(slot, s, i) else slot.pos = -1
        }
        if (s.count > 0) {
            viewport.setContent(s.width, s.height, s.pageHeight(0), s.lastTop)
            if (old == null) viewport.fitWidth()
            else if (anchorId != null) {
                val j = s.indexOf(anchorId).takeIf { it >= 0 } ?: anchorPos.coerceIn(0, s.count - 1)
                viewport.anchor(s.top(j) + anchorOffset.coerceIn(-s.gap, s.pageHeight(j)), height * PageStack.READING_LINE)
            }
        }
        reported = null // positions changed: report the pages in view again
        syncMatrices()
        applyPendingJump()
        pruneSlots()
        requestRender()
    }

    /** Releases pages that left the screen's neighbourhood (or the notebook) and wait for nothing. */
    private fun pruneSlots() {
        var lostActive = false
        val it = slots.values.iterator()
        while (it.hasNext()) {
            val slot = it.next()
            val keep = (slot === active && (action != GestureAction.IGNORE || slot.pos >= 0)) || slot.busy ||
                (slot.pos >= 0 && (slot.pageId in shown || slot.pos in window))
            if (!keep) {
                slot.release(); it.remove()
                if (slot === active) { active = noPage; lostActive = true }
            }
        }
        if (lostActive) syncMatrices()
    }

    // ---- going to a page, and which pages are in view ----
    private var jumpSeq = Long.MIN_VALUE
    private var pendingJump: String? = null

    /** Scrolls page [pageId] to the top of the view (once per [seq]; waits for layout). */
    fun jumpTo(seq: Long, pageId: String) {
        if (seq == jumpSeq) return
        jumpSeq = seq
        pendingJump = pageId
        applyPendingJump()
    }

    /**
     * A new canvas for an open notebook (rotation, returning to the editor): start at the page
     * that was in view, and treat the last jump ([seq]) as done.
     */
    fun restoreTo(pageId: String, seq: Long?) {
        if (seq != null) jumpSeq = seq
        pendingJump = pageId
        applyPendingJump()
    }

    private fun applyPendingJump() {
        val id = pendingJump ?: return
        val s = stack ?: return
        if (!viewport.ready) return
        val i = s.indexOf(id)
        if (i < 0) return // not in the layout yet: the next layout brings it
        pendingJump = null
        scroller.forceFinished(true)
        dropWet()
        viewport.anchor(s.top(i), 0f)
        syncMatrices()
        requestRender()
    }

    /** Document height of the reading line (the page in view is read there). */
    private fun readingY() = (height * PageStack.READING_LINE - viewport.ty) / scale

    private fun currentIndex(s: PageStack) = s.nearest(readingY())

    private var reported: Pair<IntRange, Int>? = null

    /** Tells the listener which pages are on screen when that changes; keeps their neighbours' slots. */
    private fun reportPages() {
        val s = stack ?: return
        if (s.count == 0 || !viewport.ready) return
        val y0 = -viewport.ty / scale; val y1 = (height - viewport.ty) / scale
        val cur = currentIndex(s)
        val vis = s.overlapping(y0, y1).takeIf { !it.isEmpty() } ?: cur..cur
        val now = vis to cur
        if (now == reported) return
        reported = now
        window = (vis.first - 1).coerceAtLeast(0)..(vis.last + 1).coerceAtMost(s.count - 1)
        pruneSlots()
        listener?.onPagesInView(s.ids.subList(vis.first, vis.last + 1), s.ids[cur])
    }

    /** The page under view point ([x], [y]), or the nearer one across a gap; null without pages. */
    private fun slotAt(x: Float, y: Float): PageSlot? {
        val s = stack ?: return null
        val i = s.nearest((y - viewport.ty) / scale).takeIf { it >= 0 } ?: return null
        return slotFor(s.ids[i])
    }

    /** Makes [slot] the page gestures work on (only between gestures). */
    private fun activate(slot: PageSlot) {
        if (slot === active) return
        dropWet() // held wet strokes are drawn with the old page's transform
        selSnapshot = null
        selTransform = SelectionTransform()
        active = slot
        syncMatrices()
        if (slot.loaded && selection.isNotEmpty()) maybeSnapshotSelection()
    }

    /** Same stroke objects in the same order (the view model publishes its own copy of the list). */
    private fun sameStrokes(a: List<Stroke>, b: List<Stroke>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if (a[i] !== b[i]) return false
        return true
    }

    // ---- view transform: page units → view pixels ----
    private val viewport = PageViewport()
    private val pageToView = Matrix()
    private val viewToPage = Matrix()
    private val scale get() = viewport.scale
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private val tmpPt = FloatArray(2)

    // ---- active gesture state ----
    private var action = GestureAction.IGNORE
    private var activePointerId = -1
    private var builder: StrokeBuilder? = null
    private var strokeIsFromPen = false
    private val predictor = MotionEventPredictor.newInstance(this)
    private var predicted: MotionEvent? = null
    private var lastErase: FloatArray? = null
    private var navigating = false
    private var lastPanX = 0f
    private var lastPanY = 0f

    // ---- lasso / selection gesture state ----
    private enum class SelGesture { NONE, LASSO, MOVE, RESIZE, ROTATE }
    /** The corner being dragged (0 top-left, 1 top-right, 2 bottom-right, 3 bottom-left). */
    private var resizeCorner = 2
    private var selGesture = SelGesture.NONE
    private val lassoPoints = ArrayList<Float>()
    /** Live (and just-committed) move/resize, drawn on the selected strokes. */
    private var selTransform = SelectionTransform()
    private var selDownX = 0f
    private var selDownY = 0f
    private var selBox: app.folio.core.model.Rect? = null
    private val selMatrix = Matrix()
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = SELECTION_BLUE
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    private val handleRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x66000000 }
    private val lassoPath = Path()

    // ---- hover state ----
    private var hoverX = -1f
    private var hoverY = -1f
    private var hoverErase = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            viewport.zoomBy(d.scaleFactor, d.focusX, d.focusY)
            listener?.onZoomChanged((viewport.zoom * 100f).toInt())
            syncMatrices()
            requestRender()
            return true
        }
    })

    private val renderer = PageRenderer()
    private val deskPaint = Paint().apply { color = 0xFFE8E6E1.toInt() }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private val livePath = Path()

    init {
        isFocusable = true
        contentDescription = "Handwriting canvas"
        holder.setFormat(android.graphics.PixelFormat.OPAQUE)
        holder.addCallback(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = java.lang.ref.WeakReference(this)
    }


    /** True if the window-relative point lies on this canvas. */
    fun containsWindowPoint(x: Float, y: Float): Boolean {
        if (!isShown) return false
        val loc = IntArray(2)
        getLocationInWindow(loc)
        return x >= loc[0] && y >= loc[1] && x < loc[0] + width && y < loc[1] + height
    }

    /**
     * Delivers a window-level event straight to this canvas. Used for S Pen
     * side-button gestures, which the Compose host was observed to drop
     * (Galaxy Tab S6 Lite, One UI 5.1.1).
     */
    fun dispatchFromWindow(ev: MotionEvent): Boolean {
        val loc = IntArray(2)
        getLocationInWindow(loc)
        val local = MotionEvent.obtain(ev)
        local.offsetLocation(-loc[0].toFloat(), -loc[1].toFloat())
        try { return onTouchEvent(local) } finally { local.recycle() }
    }

    /** Rotation / split-screen keeps zoom and reading position (see [PageViewport.resize]). */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        viewport.resize(w.toFloat(), h.toFloat())
        syncMatrices()
        applyPendingJump()
        requestRender()
    }

    private fun fitIfNeeded() {
        if (!viewport.ready && width > 0) { viewport.resize(width.toFloat(), height.toFloat()); syncMatrices(); applyPendingJump() }
    }

    /** The active page's transform: view = (page + its place in the column) × scale + (tx, ty). */
    private fun syncMatrices() {
        val sc = viewport.scale
        pageToView.setScale(sc, sc)
        pageToView.postTranslate(viewport.tx + active.left * sc, viewport.ty + active.top * sc)
        pageToView.invert(viewToPage)
        reportPages()
        if (reportViewport) listener?.onViewportChanged()
    }

    /** Momentum scroll, advanced once per surface frame. */
    private fun stepFling() {
        if (scroller.computeScrollOffset()) {
            viewport.panBy(scroller.currX - viewport.tx, scroller.currY - viewport.ty)
            syncMatrices()
            requestRender()
        }
    }

    private fun toPage(x: Float, y: Float): FloatArray {
        tmpPt[0] = x; tmpPt[1] = y
        viewToPage.mapPoints(tmpPt)
        return tmpPt
    }

    // ------------------------------------------------------------------ input

    private fun kindOf(e: MotionEvent, index: Int) = when (e.getToolType(index)) {
        MotionEvent.TOOL_TYPE_STYLUS -> PointerKind.STYLUS
        MotionEvent.TOOL_TYPE_ERASER -> PointerKind.STYLUS_ERASER
        MotionEvent.TOOL_TYPE_FINGER -> PointerKind.FINGER
        MotionEvent.TOOL_TYPE_MOUSE -> PointerKind.MOUSE
        else -> PointerKind.UNKNOWN
    }

    private val debugInput = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** True while the current gesture was started with the side button held (Samsung codes or buttonState). */
    private var sideButtonGesture = false

    // ---- debug-only performance probes (logged under tag FolioPerf) ----
    private val perfLatencyMs = ArrayList<Float>()
    private val perfDrawMs = ArrayList<Float>()
    private val perfFrames = ArrayList<String>()
    private var perfLastInputNanos = 0L
    private var perfEraseUpNanos = 0L
    private var perfEraseHitMs = 0f

    private fun perfReport(kind: String) {
        if (!debugInput || perfDrawMs.isEmpty()) return
        fun pct(v: List<Float>, q: Float) = v.sorted().let { if (it.isEmpty()) -1f else it[((it.size - 1) * q).toInt()] }
        Log.d(
            "FolioPerf",
            "%s frames=%d strokes=%d latency_p50=%.1f latency_p95=%.1f draw_p50=%.1f draw_p95=%.1f draw_max=%.1f".format(
                kind + (if (kind == "erase") " hit_ms=%.0f sync_ms=%.0f diff_ms=%.0f patches=%d".format(perfEraseHitMs, tiles.perfSyncMs, tiles.perfDiffMs, tiles.perfPatches) else ""), perfDrawMs.size, strokes.size, pct(perfLatencyMs, .5f), pct(perfLatencyMs, .95f),
                pct(perfDrawMs, .5f), pct(perfDrawMs, .95f), perfDrawMs.maxOrNull() ?: -1f,
            ),
        )
        if (perfFrames.isNotEmpty()) Log.d("FolioLatency", "frames " + perfFrames.joinToString(","))
        if (perfPredictMs.isNotEmpty()) Log.d("FolioLatency", "predict " + perfPredictMs.joinToString(",") { "%.0f".format(it) })
        perfPredictMs.clear()
        wet?.drawnMs?.let { d ->
            val v = synchronized(d) { ArrayList(d).also { d.clear() } }
            if (v.isNotEmpty()) Log.d("FolioLatency", "wet " + v.joinToString(",") { "%.1f".format(it) })
        }
        perfFrames.clear()
        perfLatencyMs.clear(); perfDrawMs.clear()
        perfEraseHitMs = 0f; tiles.perfSyncMs = 0f; tiles.perfDiffMs = 0f; tiles.perfPatches = 0
    }
    private var lastHoverButtons = 0

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        fitIfNeeded()
        val raw = e.actionMasked
        if (debugInput && raw != MotionEvent.ACTION_MOVE && raw != SPEN_ACTION_MOVE) {
            Log.d(TAG, "touch action=$raw tool=${e.getToolType(0)} buttons=${e.buttonState} pointers=${e.pointerCount}")
        }
        // Samsung devices deliver touches made with the S Pen side button held as
        // actions 211–214 instead of DOWN/UP/MOVE/CANCEL. Map them back and erase.
        val masked = when (raw) {
            SPEN_ACTION_DOWN -> { sideButtonGesture = true; MotionEvent.ACTION_DOWN }
            SPEN_ACTION_UP -> MotionEvent.ACTION_UP
            SPEN_ACTION_MOVE -> MotionEvent.ACTION_MOVE
            SPEN_ACTION_CANCEL -> MotionEvent.ACTION_CANCEL
            MotionEvent.ACTION_DOWN -> { sideButtonGesture = (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0; raw }
            else -> raw
        }
        if (masked == MotionEvent.ACTION_DOWN) hoverButtonTouched = true
        if (handlePadWindow(e, masked)) return true
        if (masked == MotionEvent.ACTION_DOWN) {
            scroller.forceFinished(true)
            tapDownX = e.x; tapDownY = e.y; tapDownTime = e.eventTime; tapMultiTouch = false
        }
        if (masked == MotionEvent.ACTION_POINTER_DOWN) tapMultiTouch = true
        if (debugInput) perfLastInputNanos = e.eventTime * 1_000_000L
        when (masked) {
            MotionEvent.ACTION_DOWN -> beginGesture(e, 0)
            MotionEvent.ACTION_POINTER_DOWN -> {
                val newKind = kindOf(e, e.actionIndex)
                val isPen = newKind == PointerKind.STYLUS || newKind == PointerKind.STYLUS_ERASER
                // Palm already resting (ignored or panning) when the pen touches: the pen takes over.
                if (isPen && (action == GestureAction.IGNORE || action == GestureAction.NAVIGATE)) {
                    navigating = false
                    beginGesture(e, e.actionIndex)
                    return true
                }
                // A second finger during finger-drawing turns the gesture into navigation.
                if (action == GestureAction.DRAW && kindOf(e, 0) == PointerKind.FINGER) {
                    abortInk(); action = GestureAction.NAVIGATE
                }
                if (action == GestureAction.NAVIGATE) startPan(e)
            }
            MotionEvent.ACTION_MOVE -> when (action) {
                GestureAction.DRAW -> if (textMode) Unit else if (laserActive) laserMove(e) else if (sweepActive) sweepMove(e)
                else if (selGesture != SelGesture.NONE) moveSelectionGesture(e) else {
                    predictor.record(e); appendSamples(e); updatePrediction()
                    e.findPointerIndex(activePointerId).takeIf { it >= 0 }?.let { i ->
                        if (snapped != null) adjustSnap(e.getX(i), e.getY(i)) else watchHold(e.getX(i), e.getY(i))
                    }
                    renderLive()
                }
                GestureAction.ERASE -> {
                    eraseAlong(e)
                    e.findPointerIndex(activePointerId).takeIf { it >= 0 }?.let { i ->
                        val slop = HOLD_SLOP_DP * resources.displayMetrics.density
                        if (kotlin.math.abs(e.getX(i) - holdX) > slop || kotlin.math.abs(e.getY(i) - holdY) > slop) removeCallbacks(eraseHoldRunnable)
                    }
                }
                GestureAction.NAVIGATE -> navigate(e)
                GestureAction.IGNORE -> if (wheelDrag) e.findPointerIndex(activePointerId).takeIf { it >= 0 }?.let { listener?.onPenWheelDrag(e.getX(it), e.getY(it)) }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val idx = e.actionIndex
                if (e.getPointerId(idx) == activePointerId && action != GestureAction.NAVIGATE) {
                    finishGesture(e, idx, canceled = (e.flags and MotionEvent.FLAG_CANCELED) != 0)
                } else if (action == GestureAction.NAVIGATE) startPan(e, skipIndex = idx)
            }
            MotionEvent.ACTION_UP -> finishGesture(e, 0, canceled = (e.flags and MotionEvent.FLAG_CANCELED) != 0)
            // The system cancels the gesture when it decides the touch was a palm.
            MotionEvent.ACTION_CANCEL -> finishGesture(e, 0, canceled = true)
        }
        if (action == GestureAction.NAVIGATE && !lockCanvas) scaleDetector.onTouchEvent(e)
        return true
    }

    private fun beginGesture(e: MotionEvent, index: Int) {
        awaitPendingWipe()
        val kind = kindOf(e, index)
        val barrel = sideButtonGesture || (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        action = policy.classifyDown(kind, e.getTouchMajor(index), barrel, eraserSelected, e.pointerCount, e.eventTime, readOnly = readOnly && !laserMode)
        // Eyedropper: the first touch picks a colour and does nothing else.
        if (eyedropperActive && action != GestureAction.IGNORE && e.pointerCount == 1) {
            pickColorAt(e.getX(index), e.getY(index))
            action = GestureAction.IGNORE
            return
        }
        if (policy.stylusSeen) policy.penOnly = penOnlyPreference
        activePointerId = e.getPointerId(index)
        if (action == GestureAction.DRAW && laserMode) {
            requestUnbufferedDispatch(e)
            laserActive = true
            laser.down(e.getX(index), e.getY(index))
            hoverX = -1f
            requestRender()
            return
        }
        if (action == GestureAction.DRAW || action == GestureAction.ERASE) routeToPage(e.getX(index), e.getY(index))
        stickyTarget = null
        when (action) {
            GestureAction.DRAW if restyleMode -> {
                requestUnbufferedDispatch(e)
                sweepActive = true
                sweepPreview.clear(); sweepCommitted = false
                val p = toPage(e.getX(index), e.getY(index))
                lastSweep = floatArrayOf(p[0], p[1])
                sweepTo(p[0], p[1])
            }
            GestureAction.DRAW if textMode -> {
                val p = toPage(e.getX(index), e.getY(index))
                textDownX = p[0]; textDownY = p[1]; textDownVX = e.getX(index); textDownVY = e.getY(index)
            }
            GestureAction.DRAW if lassoMode -> {
                requestUnbufferedDispatch(e)
                beginSelectionGesture(e.getX(index), e.getY(index))
            }
            GestureAction.DRAW -> {
                // Deliver every sample as soon as it arrives instead of once per frame.
                requestUnbufferedDispatch(e)
                val p0 = toPage(e.getX(index), e.getY(index))
                stickyTarget = if (tool == ToolType.TAPE) null
                else active.objects.stickies.lastOrNull { !it.minimized && it.bounds.contains(p0[0], p0[1]) }?.id
                strokeDownTime = e.eventTime; strokeDownVX = e.getX(index); strokeDownVY = e.getY(index)
                builder = StrokeBuilder(
                    tool, color, penWidth, stabilization, minDistance = StrokeBuilder.minDistanceAt(scale),
                    startedAt = System.currentTimeMillis(), pxPerUnit = scale,
                )
                strokeIsFromPen = kind == PointerKind.STYLUS || kind == PointerKind.STYLUS_ERASER
                predictor.record(e)
                appendSamples(e)
                snapped = null; snapBase = null
                wetLive = canUseWet()
                if (wetLive) pushWet(full = wetHeld.isEmpty())
                holdX = e.getX(index); holdY = e.getY(index)
                removeCallbacks(holdRunnable); postDelayed(holdRunnable, HOLD_TO_SNAP_MS)
                listener?.onPenDown()
            }
            GestureAction.ERASE -> {
                requestUnbufferedDispatch(e)
                erasedPending.clear()
                partialPieces.clear(); partialCommitted = false
                startWipeIfPossible()
                val p = toPage(e.getX(index), e.getY(index))
                lastErase = floatArrayOf(p[0], p[1])
                eraseTo(p[0], p[1])
                // The eraser tool held still (pen tip, no side button) opens the pen palette too.
                removeCallbacks(eraseHoldRunnable)
                if (penWheelEnabled && kind == PointerKind.STYLUS && !barrel) {
                    holdX = e.getX(index); holdY = e.getY(index)
                    postDelayed(eraseHoldRunnable, HOLD_TO_SNAP_MS)
                }
            }
            GestureAction.NAVIGATE -> startPan(e)
            GestureAction.IGNORE -> Unit
        }
        hoverX = -1f
        requestRender()
    }

    /**
     * Pen-down: the page under the pen (or the nearer one across a gap) is the page this whole
     * gesture works on, wherever the pen goes after. Ink may start on a page that's still
     * loading (the view model queues it); erasing, lasso and text wait for its content.
     */
    private fun routeToPage(x: Float, y: Float) {
        val slot = slotAt(x, y)
        val inkOnly = action == GestureAction.DRAW && !lassoMode && !textMode
        if (slot == null || (!slot.loaded && !inkOnly)) { action = GestureAction.IGNORE; return }
        if (slot === active) return
        // A selection belongs to one page: working on another page drops it.
        if (selection.isNotEmpty()) {
            val page = selectionPage ?: active.pageId
            selection = emptySet()
            listener?.onLassoSelected(page, emptySet())
        }
        activate(slot)
    }

    private fun appendSamples(e: MotionEvent) {
        val b = builder ?: return
        val idx = e.findPointerIndex(activePointerId).takeIf { it >= 0 } ?: return
        for (h in 0 until e.historySize) {
            val p = toPage(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h))
            b.add(
                p[0], p[1], effort(e, idx, e.getHistoricalPressure(idx, h)),
                e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, idx, h),
                e.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, idx, h),
                e.getHistoricalEventTime(h),
            )
        }
        val p = toPage(e.getX(idx), e.getY(idx))
        b.add(p[0], p[1], effort(e, idx, e.getPressure(idx)), e.getAxisValue(MotionEvent.AXIS_TILT, idx), e.getAxisValue(MotionEvent.AXIS_ORIENTATION, idx), e.eventTime)
    }

    /** Raw device pressure → stored writing effort. Fingers and mice have no real pressure. */
    private fun effort(e: MotionEvent, idx: Int, raw: Float): Float = when (e.getToolType(idx)) {
        MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> pressureCurve.map(raw)
        else -> NO_PRESSURE_EFFORT
    }

    private fun updatePrediction() {
        predicted?.recycle()
        predicted = predictor.predict()
        if (debugInput) predicted?.let { perfPredictMs += (it.eventTime - perfLastInputNanos / 1_000_000L).toFloat() }
    }
    /** Debug: how far ahead (ms) each prediction reaches. */
    private val perfPredictMs = ArrayList<Float>()

    private fun finishGesture(e: MotionEvent, index: Int, canceled: Boolean) {
        // Side button held + a quick tap of the pen: not an erase, the pen wheel. (On the Galaxy
        // Tab the button alone sends nothing useful in the air; button + tap is the natural press.)
        if (action == GestureAction.ERASE && sideButtonGesture && !canceled && penWheelEnabled && !readOnly && !tapMultiTouch && isQuickTap(e)) {
            finishGesture(e, index, canceled = true) // put back whatever the tap's first touch erased
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            listener?.onPenWheel(tapDownX, tapDownY)
            return
        }
        removeCallbacks(eraseHoldRunnable)
        if (wheelDrag) {
            wheelDrag = false
            val i = e.findPointerIndex(activePointerId).takeIf { it >= 0 } ?: index
            if (!canceled) listener?.onPenWheelRelease(e.getX(i), e.getY(i))
        }
        when (action) {
            GestureAction.DRAW if textMode -> {
                val idx = e.findPointerIndex(activePointerId).takeIf { it >= 0 } ?: index
                val slop = 12f * resources.displayMetrics.density
                if (!canceled && kotlin.math.abs(e.getX(idx) - textDownVX) < slop && kotlin.math.abs(e.getY(idx) - textDownVY) < slop) {
                    listener?.onTextTap(active.pageId, textDownX, textDownY, textAt(textDownX, textDownY))
                }
            }
            GestureAction.DRAW if laserActive -> { laserActive = false; laser.up(); requestRender() }
            GestureAction.DRAW if sweepActive -> finishSweep(canceled)
            GestureAction.DRAW if selGesture != SelGesture.NONE -> finishSelectionGesture(canceled)
            GestureAction.DRAW -> {
                val b = builder
                // On Android 13 a finger or palm touching mid-stroke makes the system cancel the
                // pen stream. Keep what the pen already wrote rather than losing it (observed on
                // Galaxy Tab S6 Lite). A cancelled finger stroke is still discarded as accidental.
                val keepCanceledPenStroke = canceled && strokeIsFromPen && b != null && b.size >= 2
                if (b != null && (!canceled || keepCanceledPenStroke)) {
                    if (!canceled) {
                        val idx = e.findPointerIndex(activePointerId).takeIf { it >= 0 } ?: index
                        appendSamples(e)
                        val p = toPage(e.getX(idx), e.getY(idx))
                        b.finishAt(p[0], p[1], effort(e, idx, e.getPressure(idx)), e.getAxisValue(MotionEvent.AXIS_TILT, idx), e.getAxisValue(MotionEvent.AXIS_ORIENTATION, idx), e.eventTime)
                    }
                    val idx0 = e.findPointerIndex(activePointerId).takeIf { it >= 0 } ?: index
                    val tapped = !canceled && b.size > 0 && isPenTap(e, idx0) && handlePenTap(b.build())
                    if (tapped) abortInk()
                    else if (!canceled && snapped == null && scribbleErase(b)) Unit // the scrawl took ink away instead of adding any
                    else if (b.size > 0) {
                        val shape = snapped
                        val raw = if (shape != null && !canceled) shape.toStroke(tool, color, penWidth, snapPressure, b.build().startedAt) else b.build()
                        val stroke = finishedStroke(raw, snappedAlready = shape != null)
                        val art = artFor(stroke) // build its geometry now, before the next frame needs it
                        if (wetLive) {
                            // Keep it wet until the page surface shows it, so it never blinks out.
                            wetHeld += WetHeld(active, stroke, art)
                            val tail = wetTail
                            wetLive = false
                            pushWet(full = false, extraClip = viewBounds(art, RectF()).apply { tail?.let { union(it) } })
                            wetTail = null
                        }
                        pendingCommits += stroke
                        pendingSince[stroke.id] = SystemClock.uptimeMillis()
                        val sticky = stickyTarget?.let { id -> active.objects.stickies.firstOrNull { it.id == id } }
                        if (sticky != null) listener?.onStickyStroke(active.pageId, sticky.id, stroke.translated(-sticky.x, -sticky.y))
                        else listener?.onStrokeFinished(active.pageId, stroke)
                        listener?.onPenLifted()
                    }
                }
                abortInk()
            }
            GestureAction.ERASE -> {
                if (debugInput) perfEraseUpNanos = System.nanoTime()
                if (!canceled && erasedPending.isNotEmpty()) listener?.onStrokesErased(active.pageId, erasedPending.toSet())
                else erasedPending.clear()
                if (wiping) finishWipe(canceled)
                else if (!canceled && partialPieces.isNotEmpty()) {
                    // Keep drawing the pieces until the edited page comes back through setPage.
                    partialCommitted = true
                    listener?.onStrokesSplit(active.pageId, LinkedHashMap(partialPieces))
                } else partialPieces.clear()
                lastErase = null
                hoverX = -1f // pen lifted: hide the eraser ring until the next hover event
            }
            else -> Unit
        }
        perfReport(when { navigating -> "pan"; action == GestureAction.DRAW -> "ink"; action == GestureAction.ERASE -> "erase"; else -> "other" })
        val fingerTap = action == GestureAction.NAVIGATE && !canceled && !tapMultiTouch && isQuickTap(e)
        if (fingerTap && padWindow != null) {
            // A finger tap puts the easy writing pad's window there.
            slotAt(tapDownX, tapDownY)?.let { slot ->
                val w = padWindow!!.rect
                val px = (tapDownX - viewport.tx) / scale - slot.left; val py = (tapDownY - viewport.ty) / scale - slot.top
                val l = px.coerceIn(0f, maxOf(0f, slot.width - w.width)); val t = py.coerceIn(0f, maxOf(0f, slot.height - w.height))
                listener?.onPadWindowChanged(slot.pageId, app.folio.core.model.Rect(l, t, l + w.width, t + w.height))
            }
        } else if (fingerTap && !textMode && fingerTapOnPage(tapDownX, tapDownY)) {
            // handled: a tape peeled or covered, or a page object opened
        } else if (textMode && fingerTap) {
            slotAt(tapDownX, tapDownY)?.takeIf { it.loaded }?.let { slot ->
                if (selection.isNotEmpty() && slot !== active) { selection = emptySet(); listener?.onLassoSelected(selectionPage ?: active.pageId, emptySet()) }
                activate(slot)
                val p = toPage(tapDownX, tapDownY)
                listener?.onTextTap(slot.pageId, p[0], p[1], textAt(p[0], p[1]))
            }
        } else if (navigating && !canceled) fling()
        velocity?.recycle(); velocity = null
        navigating = false
        sideButtonGesture = false
        action = GestureAction.IGNORE
        activePointerId = -1
        pendingStack?.let { relayout(it) }
        pruneSlots()
        requestRender()
    }

    // ---- text boxes and pictures ----
    private var tapDownX = 0f
    private var tapDownY = 0f
    private var tapDownTime = 0L
    private var tapMultiTouch = false

    private fun isQuickTap(e: MotionEvent): Boolean {
        val slop = 12f * resources.displayMetrics.density
        return e.eventTime - tapDownTime < 350 && kotlin.math.abs(e.x - tapDownX) < slop && kotlin.math.abs(e.y - tapDownY) < slop
    }
    private var textDownX = 0f
    private var textDownY = 0f
    private var textDownVX = 0f
    private var textDownVY = 0f

    /** The topmost text box at a page point (a little slack around it), or null. */
    private fun textAt(px: Float, py: Float): String? {
        val slack = 8f / scale
        return texts.lastOrNull { it.bounds.inflate(slack).contains(px, py) }?.id
    }

    /** The topmost element (text above pictures) at a page point, or null. */
    private fun elementAt(px: Float, py: Float): String? {
        val o = active.objects
        return o.stickies.lastOrNull { !it.minimized && it.bounds.contains(px, py) }?.id ?: textAt(px, py)
            ?: o.audio.lastOrNull { it.bounds.contains(px, py) }?.id ?: o.tables.lastOrNull { it.bounds.contains(px, py) }?.id
            ?: images.lastOrNull { it.bounds.contains(px, py) }?.id
    }

    private fun selectedElementBounds(): app.folio.core.model.Rect? {
        var r: app.folio.core.model.Rect? = null
        for (t in texts) if (t.id in selection) r = r?.union(t.bounds) ?: t.bounds
        for (i in images) if (i.id in selection) r = r?.union(i.bounds) ?: i.bounds
        val o = active.objects
        for (n in o.stickies) if (n.id in selection) r = r?.union(n.bounds) ?: n.bounds
        for (t in o.tables) if (t.id in selection) r = r?.union(t.bounds) ?: t.bounds
        for (a in o.audio) if (a.id in selection) r = r?.union(a.bounds) ?: a.bounds
        return r
    }

    /** Selected pictures and text drawn live while the selection is being moved or resized. */
    private fun drawMovingElements(canvas: Canvas) {
        for (img in images) if (img.id in selection) {
            val bmp = assets?.image(img.attachmentId, decode = false)
            if (bmp != null) renderer.drawImage(canvas, bmp, img) else renderer.drawMissingImage(canvas, img)
        }
        for (t in texts) if (t.id in selection) renderer.drawText(canvas, t)
        val o = active.objects
        for (t in o.tables) if (t.id in selection) renderer.drawTable(canvas, t)
        for (a in o.audio) if (a.id in selection) renderer.drawAudio(canvas, a)
        for (n in o.stickies) if (n.id in selection) renderer.drawSticky(canvas, n)
    }

    // ---- big selections move as one picture ----
    private class SelectionSnapshot(val selection: Set<String>, val strokes: List<Stroke>, val bitmap: android.graphics.Bitmap, val dst: android.graphics.RectF)
    @Volatile private var selSnapshot: SelectionSnapshot? = null
    private val snapshotPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /**
     * Above [SNAPSHOT_MIN_STROKES] selected strokes, drawing them as vectors every frame of a
     * drag lags; render the selection once in the background and move that bitmap instead.
     */
    private fun maybeSnapshotSelection() {
        val sel = selection; val list = strokes
        val cur = selSnapshot
        if (cur != null && cur.selection === sel && cur.strokes === list) return
        selSnapshot = null
        if (sel.size < SNAPSHOT_MIN_STROKES) return
        val chosen = list.filter { it.id in sel }
        if (chosen.size < SNAPSHOT_MIN_STROKES) return
        val chosenTexts = texts.filter { it.id in sel }; val chosenImages = images.filter { it.id in sel }
        val box = currentSelectionBox() ?: return
        val viewScale = scale
        val a = assets
        selectionExecutor.execute {
            val w = box.width; val h = box.height
            if (w <= 0f || h <= 0f) return@execute
            val sc = minOf(viewScale, kotlin.math.sqrt(SNAPSHOT_MAX_PX / (w * h)))
            val bw = kotlin.math.ceil(w * sc).toInt().coerceAtLeast(1); val bh = kotlin.math.ceil(h * sc).toInt().coerceAtLeast(1)
            val bmp = try { android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888) } catch (_: OutOfMemoryError) { return@execute }
            val c = Canvas(bmp)
            c.scale(sc, sc); c.translate(-box.left, -box.top)
            val r = PageRenderer()
            for (img in chosenImages) a?.image(img.attachmentId, decode = true)?.let { r.drawImage(c, it, img) }
            for (s in chosen) r.drawStroke(c, StrokeArt.of(s))
            for (t in chosenTexts) r.drawText(c, t)
            bmp.prepareToDraw()
            val snap = SelectionSnapshot(sel, list, bmp, android.graphics.RectF(box.left, box.top, box.right, box.bottom))
            post { if (selection === sel && strokes === list) { selSnapshot = snap; requestRender() } }
        }
    }

    // ---- hold the pen still at the end of a stroke to snap it to a clean shape ----
    private var snapped: Shape? = null
    private var snapBase: Shape? = null
    private var snapRefX = 0f
    private var snapRefY = 0f
    private var snapPressure = 0.5f
    private var holdX = 0f
    private var holdY = 0f
    private val holdRunnable = Runnable { trySnap() }
    /** Eraser tool pressed and held still: undo what the first touch erased and open the pen palette. */
    private val eraseHoldRunnable = Runnable {
        if (action == GestureAction.ERASE) {
            erasedPending.clear()
            if (wiping) finishWipe(canceled = true) else partialPieces.clear()
            lastErase = null
            action = GestureAction.IGNORE; wheelDrag = true
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            listener?.onPenWheel(holdX, holdY)
            requestRender()
        }
    }
    /** Pen wheel: opens on a still press-and-hold of the pen, or a side-button click while hovering. */
    var penWheelEnabled: Boolean = true
    /** The pen that opened the wheel by holding is still down: its moves and lift go to the wheel. */
    private var wheelDrag = false
    private var hoverButtonDownAt = 0L
    /** The pen touched the page while the button was held: that press was an erase or a button-tap, not an air click. */
    private var hoverButtonTouched = false

    /** Restarts the hold timer whenever the pen moves more than a few pixels. */
    private fun watchHold(vx: Float, vy: Float) {
        val slop = HOLD_SLOP_DP * resources.displayMetrics.density
        if (kotlin.math.abs(vx - holdX) <= slop && kotlin.math.abs(vy - holdY) <= slop) return
        holdX = vx; holdY = vy
        removeCallbacks(holdRunnable)
        postDelayed(holdRunnable, HOLD_TO_SNAP_MS)
    }

    private fun trySnap() {
        val b = builder ?: return
        if (action != GestureAction.DRAW || selGesture != SelGesture.NONE || snapped != null) return
        val s = b.build()
        // Pressed and held without moving: not a stroke at all, the pen wheel.
        if (penWheelEnabled && strokeIsFromPen && InkShaping.asDot(s, DOT_SLOP_DP * resources.displayMetrics.density / scale) != null) {
            abortInk()
            action = GestureAction.IGNORE; wheelDrag = true
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            listener?.onPenWheel(holdX, holdY)
            requestRender()
            return
        }
        val shape = ShapeRecognizer.recognize(s.x, s.y, minSize = HOLD_SHAPE_MIN_DP * resources.displayMetrics.density / scale) ?: return
        snapped = shape; snapBase = shape
        val p = toPage(holdX, holdY); snapRefX = p[0]; snapRefY = p[1]
        snapPressure = if (s.size > 0) s.pressure.average().toFloat().coerceIn(0.15f, 1f) else 0.5f
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        renderLive()
    }

    /** After snapping, moving the pen drags a line's end, or resizes other shapes about their centre. */
    private fun adjustSnap(vx: Float, vy: Float) {
        val base = snapBase ?: return
        val p = toPage(vx, vy)
        snapped = if (base.kind == ShapeKind.LINE) ShapeRecognizer.line(base.xs[0], base.ys[0], p[0], p[1]) else {
            val cx = base.centerX; val cy = base.centerY
            val d0 = kotlin.math.hypot(snapRefX - cx, snapRefY - cy)
            val f = if (d0 > 1f) (kotlin.math.hypot(p[0] - cx, p[1] - cy) / d0).coerceIn(0.2f, 5f) else 1f
            base.scaled(f, cx, cy)
        }
    }

    /**
     * Scribble to erase (always on): a pen scrawl back and forth over existing ink removes the
     * strokes it covers, as one undoable edit, and leaves no mark itself. False if this stroke
     * isn't a scrawl or covers nothing; then it is ordinary ink.
     */
    private fun scribbleErase(b: StrokeBuilder): Boolean {
        if (!strokeIsFromPen || tool == ToolType.HIGHLIGHTER || tool == ToolType.TAPE || stickyTarget != null || !active.loaded) return false
        val s = b.build()
        val px = resources.displayMetrics.density / scale
        if (!ScribbleErase.isScribble(s.x, s.y, minSize = SCRIBBLE_MIN_DP * px)) return false
        val near = index.query(s.bounds())
        if (near.isEmpty()) return false
        val ids = ScribbleErase.targets(s.x, s.y, reach = penWidth / 2f + 2f * px, strokes = near.map { strokes[it] })
        if (ids.isEmpty()) return false
        performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
        listener?.onStrokesErased(active.pageId, ids)
        listener?.onPenLifted()
        return true
    }

    private fun abortInk() {
        if (wetLive) {
            // Stroke dropped (second finger, cancel): wipe it from the wet layer.
            wetLive = false
            val tailOnly = wetTail
            if (wetHeld.isEmpty()) wet?.clear() else pushWet(full = tailOnly == null, extraClip = tailOnly)
            wetTail = null
        }
        builder = null
        removeCallbacks(holdRunnable)
        snapped = null; snapBase = null
        predicted?.recycle(); predicted = null
    }

    private fun eraserRadiusPage() = eraserSizeDp * resources.displayMetrics.density / scale

    private fun erasable(s: Stroke) = when {
        s.tool == ToolType.TAPE && pageLook.hideTape -> false
        eraseHighlighterOnly -> s.tool == ToolType.HIGHLIGHTER
        eraseTapeOnly -> s.tool == ToolType.TAPE
        else -> true
    }

    /**
     * Fast path for the partial eraser: wipe pixels off the cached ink layer as the pen
     * moves (no per-sample geometry), then compute the exact stroke pieces once at pen-up.
     * Not used for "highlighter only" (a pixel wipe would also remove ink) or when no
     * up-to-date layer exists.
     */
    private var wiping = false
    private val wipePath = ArrayList<Float>()

    private fun startWipeIfPossible() {
        wiping = false; wipePath.clear()
        if (!eraserPartial || eraseHighlighterOnly || eraseTapeOnly || pageLook.hideTape) return
        wiping = true
    }

    private fun wipeTo(x0: Float, y0: Float, x1: Float, y1: Float) {
        if (wipePath.isEmpty()) { wipePath += x0; wipePath += y0 }
        wipePath += x1; wipePath += y1
        val k = TileCache.levelFor(scale)
        tiles.wipe(x0, y0, x1, y1, eraserRadiusPage(), k, minOf(TileCache.levelFor(viewport.fitPageScale), k))
    }

    /** Pen-up after a wipe: turn the wiped path into exact stroke pieces (one undo step). */
    private fun finishWipe(canceled: Boolean) {
        val path = wipePath.toFloatArray()
        wiping = false; wipePath.clear()
        if (canceled || path.size < 2) { tiles.discardWiped(); return }
        // The wiped tiles already show the result. Compute the exact pieces off the UI thread
        // (it took 100–300 ms on a 7,000-stroke page) from an immutable snapshot, then commit.
        wipeAwaitingEcho = true
        val base = strokes; val idx = index; val r = eraserRadiusPage()
        val job = WipeJob(active, base)
        pendingWipe = job
        job.future = wipeExecutor.submit {
            val t0 = System.nanoTime()
            job.pieces = wipePieces(base, idx, path, r)
            if (job.pieces.isNotEmpty()) {
                // The page after this edit, and its index, so the UI thread needn't rebuild it.
                val after = ArrayList<Stroke>(base.size + job.pieces.size)
                for (s in base) { val p = job.pieces[s.id]; if (p == null) after += s else after.addAll(p) }
                job.prebuilt = StrokeIndex(after)
            }
            if (debugInput) Log.d("FolioPerf", "wipe pieces ms=%.1f (background) path_pts=%d touched=%d".format((System.nanoTime() - t0) / 1e6, path.size / 2, job.pieces.size))
            post { completeWipe(job) }
        }
    }

    private class WipeJob(val slot: PageSlot, val base: List<Stroke>) {
        @Volatile var pieces: Map<String, List<Stroke>> = emptyMap()
        @Volatile var prebuilt: StrokeIndex? = null
        var future: java.util.concurrent.Future<*>? = null
    }
    private var pendingWipe: WipeJob? = null

    /** Background: the pieces left of every stroke the eraser path crossed (one pass per stroke). */
    private fun wipePieces(base: List<Stroke>, idx: StrokeIndex, path: FloatArray, r: Float): Map<String, List<Stroke>> {
        val out = LinkedHashMap<String, List<Stroke>>()
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var rt = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (k in path.indices step 2) { l = minOf(l, path[k]); rt = maxOf(rt, path[k]); t = minOf(t, path[k + 1]); b = maxOf(b, path[k + 1]) }
        for (i in idx.query(l - r, t - r, rt + r, b + r)) {
            val s = base[i]
            app.folio.core.ink.PartialEraser.erasePath(s, path, r)?.let { out[s.id] = it }
        }
        return out
    }

    /** UI thread: commits a finished wipe (one undo step) on its page. Safe to call twice; the second does nothing. */
    private fun completeWipe(job: WipeJob) {
        if (pendingWipe !== job) return
        pendingWipe = null
        try { job.future?.get() } catch (e: Exception) { Log.w(TAG, "wipe failed", e) }
        val slot = job.slot
        val pieces = job.pieces
        if (pieces.isEmpty()) {
            // Wiped only empty paper: the tiles still match the strokes.
            slot.wipeAwaitingEcho = false
            slot.tiles.keepWiped()
            requestRender()
            return
        }
        slot.partialPieces.clear(); slot.partialPieces.putAll(pieces)
        slot.partialCommitted = true
        slot.prebuiltIndex = job.prebuilt
        listener?.onStrokesSplit(slot.pageId, LinkedHashMap(pieces))
    }

    /** A new gesture waits for the previous wipe's commit, so edits stay in order. */
    private fun awaitPendingWipe() {
        pendingWipe?.let { completeWipe(it) }
    }

    private fun partialEraseAlong(x0: Float, y0: Float, x1: Float, y1: Float) {
        val r = eraserRadiusPage()
        // Pieces never extend beyond their original stroke, so the index finds them too.
        for (i in index.query(minOf(x0, x1) - r, minOf(y0, y1) - r, maxOf(x0, x1) + r, maxOf(y0, y1) + r)) {
            val s = strokes[i]
            if (!erasable(s)) continue
            val current = partialPieces[s.id]
            if (current == null) {
                app.folio.core.ink.PartialEraser.erase(s, x0, y0, x1, y1, r)?.let { partialPieces[s.id] = it }
            } else {
                var changed = false
                val next = current.flatMap { piece ->
                    app.folio.core.ink.PartialEraser.erase(piece, x0, y0, x1, y1, r)?.also { changed = true } ?: listOf(piece)
                }
                if (changed) partialPieces[s.id] = next
            }
        }
    }

    private fun eraseAlong(e: MotionEvent) {
        val idx = e.findPointerIndex(activePointerId).takeIf { it >= 0 } ?: return
        for (h in 0 until e.historySize) {
            val p = toPage(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h)); eraseTo(p[0], p[1])
        }
        val p = toPage(e.getX(idx), e.getY(idx)); eraseTo(p[0], p[1])
        hoverX = e.getX(idx); hoverY = e.getY(idx); hoverErase = true
        requestRender()
    }

    private fun eraseTo(x: Float, y: Float) {
        val t0 = if (debugInput) System.nanoTime() else 0L
        eraseToImpl(x, y)
        if (debugInput) perfEraseHitMs += (System.nanoTime() - t0) / 1e6f
    }

    private fun eraseToImpl(x: Float, y: Float) {
        val from = lastErase ?: floatArrayOf(x, y)
        if (wiping) wipeTo(from[0], from[1], x, y)
        else if (eraserPartial) partialEraseAlong(from[0], from[1], x, y)
        else {
            val r = eraserRadiusPage()
            val near = index.query(minOf(from[0], x) - r, minOf(from[1], y) - r, maxOf(from[0], x) + r, maxOf(from[1], y) + r)
            if (near.isNotEmpty()) {
                val candidates = ArrayList<Stroke>(near.size)
                for (i in near) { val s = strokes[i]; if (s.id !in erasedPending && erasable(s)) candidates += s }
                if (candidates.isNotEmpty()) erasedPending += StrokeGeometry.hitAlong(candidates, from[0], from[1], x, y, r)
            }
        }
        lastErase = floatArrayOf(x, y)
    }

    private fun startPan(e: MotionEvent, skipIndex: Int = -1) {
        navigating = true
        dropWet()
        scroller.forceFinished(true)
        if (velocity == null) velocity = VelocityTracker.obtain()
        var sx = 0f; var sy = 0f; var n = 0
        for (i in 0 until e.pointerCount) if (i != skipIndex) { sx += e.getX(i); sy += e.getY(i); n++ }
        if (n > 0) { lastPanX = sx / n; lastPanY = sy / n }
    }

    private fun navigate(e: MotionEvent) {
        var sx = 0f; var sy = 0f
        for (i in 0 until e.pointerCount) { sx += e.getX(i); sy += e.getY(i) }
        val cx = sx / e.pointerCount; val cy = sy / e.pointerCount
        velocity?.addMovement(e)
        viewport.panBy(if (lockCanvas) 0f else cx - lastPanX, cy - lastPanY)
        lastPanX = cx; lastPanY = cy
        syncMatrices()
        requestRender()
    }

    /** Momentum scroll after a finger swipe, stopping at the page edges. */
    private fun fling() {
        val vt = velocity ?: return
        vt.computeCurrentVelocity(1000)
        val vx = if (lockCanvas) 0 else vt.xVelocity.toInt(); val vy = vt.yVelocity.toInt()
        if (kotlin.math.abs(vx) < 300 && kotlin.math.abs(vy) < 300) return
        scroller.fling(
            viewport.tx.toInt(), viewport.ty.toInt(), vx, vy,
            viewport.minTx().toInt(), 0, viewport.minTy().toInt(), 0,
        )
        requestRender()
    }

    /** The pen palette is showing (its overlay takes the pointer, so hover events come via the window instead). */
    var penWheelOpen = false

    /**
     * A click of the side button in the air (pressed and let go without touching) opens the pen
     * palette, or closes it. Fed by [onHoverEvent], and by the activity while the palette's
     * overlay covers the canvas.
     */
    fun trackHoverButton(e: MotionEvent) {
        val wasDown = (lastHoverButtons and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        val isDown = (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        if (isDown && !wasDown) { hoverButtonDownAt = e.eventTime; hoverButtonTouched = false }
        if (!isDown && wasDown && penWheelEnabled && !readOnly && e.actionMasked != MotionEvent.ACTION_HOVER_EXIT &&
            !hoverButtonTouched && e.eventTime - hoverButtonDownAt < HOVER_CLICK_MS) listener?.onPenWheel(e.x, e.y)
        lastHoverButtons = e.buttonState
    }

    override fun onHoverEvent(e: MotionEvent): Boolean {
        val kind = kindOf(e, 0)
        if (kind != PointerKind.STYLUS && kind != PointerKind.STYLUS_ERASER) return super.onHoverEvent(e)
        policy.onStylusActivity(SystemClock.uptimeMillis())
        policy.penOnly = penOnlyPreference
        if (debugInput && (e.actionMasked != MotionEvent.ACTION_HOVER_MOVE || e.buttonState != lastHoverButtons)) {
            Log.d(TAG, "hover action=${e.actionMasked} tool=${e.getToolType(0)} buttons=${e.buttonState}")
        }
        trackHoverButton(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                hoverX = e.x; hoverY = e.y
                hoverErase = eraserSelected || kind == PointerKind.STYLUS_ERASER ||
                    (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
            }
            MotionEvent.ACTION_HOVER_EXIT -> hoverX = -1f
        }
        requestRender()
        return true
    }

    // ---------------------------------------------------------- laser pointer

    /** Laser trails in view pixels; all fade together a moment after the last lift. Never saved. */
    private class LaserTrails {
        val trails = ArrayList<FloatArray>()
        private var cur = ArrayList<Float>()
        var liftedAt = 0L
        var down = false
        fun down(x: Float, y: Float) { cur = arrayListOf(x, y); down = true; trails += cur.toFloatArray() }
        fun move(x: Float, y: Float) { cur += x; cur += y; trails[trails.lastIndex] = cur.toFloatArray() }
        fun up() { down = false; liftedAt = SystemClock.uptimeMillis() }
        fun clear() { trails.clear(); cur = ArrayList(); down = false }
        fun isEmpty() = trails.isEmpty()
    }
    private val laser = LaserTrails()
    private var laserActive = false
    private val laserGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val laserCore = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val laserPath = Path()

    private fun laserMove(e: MotionEvent) {
        val idx = e.findPointerIndex(activePointerId).takeIf { it >= 0 } ?: return
        for (h in 0 until e.historySize) laser.move(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h))
        laser.move(e.getX(idx), e.getY(idx))
        requestRender()
    }

    private fun drawLaser(canvas: Canvas) {
        if (laser.isEmpty()) return
        val d = resources.displayMetrics.density
        val alpha = if (laser.down) 1f else {
            val t = SystemClock.uptimeMillis() - laser.liftedAt - LASER_HOLD_MS
            1f - (t / LASER_FADE_MS.toFloat()).coerceIn(0f, 1f)
        }
        if (alpha <= 0f) { laser.clear(); return }
        val solid = (laserColor or 0xFF000000).toInt()
        val rainbow = if (laserRainbow) android.graphics.LinearGradient(
            0f, 0f, 260f * d, 160f * d, LASER_RAINBOW, null, android.graphics.Shader.TileMode.MIRROR,
        ) else null
        laserGlow.shader = rainbow; laserCore.shader = null
        laserGlow.color = solid
        laserGlow.maskFilter = android.graphics.BlurMaskFilter(7f * d, android.graphics.BlurMaskFilter.Blur.NORMAL)
        when (laserStyle) {
            LaserStyle.DOT -> {
                val t = laser.trails.last()
                if (t.size >= 2 && laser.down) {
                    val x = t[t.size - 2]; val y = t[t.size - 1]
                    laserGlow.style = Paint.Style.FILL; laserGlow.alpha = (110 * alpha).toInt()
                    canvas.drawCircle(x, y, 14f * d, laserGlow)
                    laserCore.style = Paint.Style.FILL; laserCore.shader = rainbow; laserCore.color = solid; laserCore.alpha = (255 * alpha).toInt()
                    canvas.drawCircle(x, y, 5f * d, laserCore)
                    laserGlow.style = Paint.Style.STROKE; laserCore.style = Paint.Style.STROKE
                }
                if (!laser.down) laser.clear()
            }
            else -> for (t in laser.trails) {
                if (t.size < 2) continue
                laserPath.rewind(); laserPath.moveTo(t[0], t[1])
                var i = 2
                while (i + 3 < t.size) { laserPath.quadTo(t[i], t[i + 1], (t[i] + t[i + 2]) / 2, (t[i + 1] + t[i + 3]) / 2); i += 2 }
                laserPath.lineTo(t[t.size - 2], t[t.size - 1])
                laserGlow.strokeWidth = 12f * d; laserGlow.alpha = ((if (laserStyle == LaserStyle.OUTLINED) 170 else 110) * alpha).toInt()
                canvas.drawPath(laserPath, laserGlow)
                laserCore.strokeWidth = (if (laserStyle == LaserStyle.OUTLINED) 2.4f else 3.4f) * d
                if (laserStyle == LaserStyle.OUTLINED) { laserCore.shader = null; laserCore.color = android.graphics.Color.WHITE }
                else { laserCore.shader = rainbow; laserCore.color = solid }
                laserCore.alpha = (255 * alpha).toInt()
                canvas.drawPath(laserPath, laserCore)
            }
        }
        if (!laser.down) requestRender() // keep fading
    }

    // ---------------------------------------------------------- change style sweep

    private var sweepActive = false
    /** Strokes the change-style sweep has touched, restyled (drawn instead of the originals). */
    private val sweepPreview = LinkedHashMap<String, Stroke>()
    private var sweepCommitted = false
    private var lastSweep: FloatArray? = null

    private fun sweepMove(e: MotionEvent) {
        val idx = e.findPointerIndex(activePointerId).takeIf { it >= 0 } ?: return
        for (h in 0 until e.historySize) { val p = toPage(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h)); sweepTo(p[0], p[1]) }
        val p = toPage(e.getX(idx), e.getY(idx)); sweepTo(p[0], p[1])
        hoverX = e.getX(idx); hoverY = e.getY(idx); hoverErase = false
        requestRender()
    }

    private fun sweepTo(x: Float, y: Float) {
        val from = lastSweep ?: floatArrayOf(x, y)
        val r = maxOf(restyleWidth / 2f, 6f * resources.displayMetrics.density / scale)
        val near = index.query(minOf(from[0], x) - r, minOf(from[1], y) - r, maxOf(from[0], x) + r, maxOf(from[1], y) + r)
        if (near.isNotEmpty()) {
            val candidates = ArrayList<Stroke>(near.size)
            for (i in near) { val st = strokes[i]; if (st.id !in sweepPreview && !(st.tool == ToolType.TAPE && pageLook.hideTape)) candidates += st }
            for (id in StrokeGeometry.hitAlong(candidates, from[0], from[1], x, y, r)) {
                val st = candidates.first { it.id == id }
                // Pens take the colour and the width; highlighters and tape keep their own width.
                sweepPreview[id] = if (st.tool == ToolType.HIGHLIGHTER || st.tool == ToolType.TAPE) st.restyled(restyleColor, st.width)
                else st.restyled(restyleColor, restyleWidth)
            }
        }
        lastSweep = floatArrayOf(x, y)
    }

    private fun finishSweep(canceled: Boolean) {
        sweepActive = false
        lastSweep = null
        hoverX = -1f
        if (canceled || sweepPreview.isEmpty()) { sweepPreview.clear(); return }
        sweepCommitted = true // keep drawing the preview until the edited page comes back
        listener?.onStrokesReplaced(active.pageId, sweepPreview.values.toList())
    }

    // ---------------------------------------------------------- pen-up shaping and taps

    private var stickyTarget: String? = null
    private var strokeDownTime = 0L
    private var strokeDownVX = 0f
    private var strokeDownVY = 0f

    /** A pen tap: short and nearly still (a dot, not a stroke). */
    private fun isPenTap(e: MotionEvent, idx: Int): Boolean {
        val slop = 8f * resources.displayMetrics.density
        return e.eventTime - strokeDownTime < 300 && kotlin.math.abs(e.getX(idx) - strokeDownVX) < slop && kotlin.math.abs(e.getY(idx) - strokeDownVY) < slop
    }

    /**
     * A pen tap that isn't meant as a dot: with the tape tool, peels or covers the tape under it;
     * on a recording, a table or a minimised sticky note, opens it. True if it was handled.
     */
    private fun handlePenTap(dot: Stroke): Boolean {
        if (dot.size == 0) return false
        val x = dot.x[0]; val y = dot.y[0]
        stickyTapeAt(x, y)?.let { listener?.onStickyTapeTap(active.pageId, it); return true }
        if (tool == ToolType.TAPE && togglePeelAt(x, y)) return true
        return objectAt(x, y)?.let { listener?.onObjectTap(active.pageId, it, x, y) } == true
    }

    /** A finger tap on the page (not in text mode): tape peels, objects open. */
    private fun fingerTapOnPage(vx: Float, vy: Float): Boolean {
        val slot = slotAt(vx, vy)?.takeIf { it.loaded } ?: return false
        if (slot !== active) {
            if (selection.isNotEmpty()) { val pg = selectionPage ?: active.pageId; selection = emptySet(); listener?.onLassoSelected(pg, emptySet()) }
            activate(slot)
        }
        val p = toPage(vx, vy)
        stickyTapeAt(p[0], p[1])?.let { listener?.onStickyTapeTap(active.pageId, it); return true }
        if (togglePeelAt(p[0], p[1])) return true
        val id = objectAt(p[0], p[1]) ?: return false
        return listener?.onObjectTap(active.pageId, id, p[0], p[1]) == true
    }

    private fun togglePeelAt(x: Float, y: Float): Boolean {
        if (pageLook.hideTape) return false
        val slack = 4f / scale
        val tape = index.query(x - slack, y - slack, x + slack, y + slack).map { strokes[it] }
            .lastOrNull { it.tool == ToolType.TAPE && StrokeGeometry.hits(it, x, y, slack) } ?: return false
        listener?.onStrokesReplaced(active.pageId, listOf(tape.withLook(tape.look.copy(peeled = !tape.look.peeled))))
        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        return true
    }

    /**
     * The open sticky note whose tape strip (the band across its top edge, see
     * [PageRenderer.drawSticky]) is under a page point. The target is at least 28 dp tall.
     */
    private fun stickyTapeAt(x: Float, y: Float): String? {
        val minH = 28f * resources.displayMetrics.density / scale
        return active.objects.stickies.lastOrNull { n ->
            !n.minimized && (StickyGeometry.tapeTarget(n, minH).contains(x, y) || StickyGeometry.minimise(n).inflate(4f / scale).contains(x, y))
        }?.id
    }

    // ---- easy writing pad window ----

    private enum class PadDrag { MOVE, RESIZE }
    private var padDrag: PadDrag? = null
    private var padDragFrom = FloatArray(2)
    private var padDragStart: app.folio.core.model.Rect? = null
    private var padDragPage: PageSlot? = null
    private val padPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = SELECTION_BLUE }
    private val padFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SELECTION_BLUE }

    private fun drawPadWindow(canvas: Canvas, r: app.folio.core.model.Rect) {
        val d = resources.displayMetrics.density
        padPaint.strokeWidth = 2.5f * d / scale
        canvas.drawRect(r.left, r.top, r.right, r.bottom, padPaint)
        val t = 14f * d / scale
        canvas.drawPath(Path().apply { moveTo(r.right - 3 * d / scale, r.bottom - t); lineTo(r.right - 3 * d / scale, r.bottom - 3 * d / scale); lineTo(r.right - t, r.bottom - 3 * d / scale); close() }, padFill)
    }

    /**
     * Touches on the pad window: drag its corner to resize (pen or finger), drag inside it with a
     * finger to move it. Everything else is handled as usual. True if the touch was the window's.
     */
    private fun handlePadWindow(e: MotionEvent, masked: Int): Boolean {
        val win = padWindow ?: return false
        if (masked == MotionEvent.ACTION_DOWN) {
            val slot = slots[win.pageId]?.takeIf { it.loaded } ?: return false
            val px = (e.x - viewport.tx) / scale - slot.left; val py = (e.y - viewport.ty) / scale - slot.top
            val r = win.rect
            val grab = 24f * resources.displayMetrics.density / scale
            val finger = e.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
            padDrag = when {
                kotlin.math.hypot(px - r.right, py - r.bottom) <= grab -> PadDrag.RESIZE
                finger && r.contains(px, py) -> PadDrag.MOVE
                else -> null
            }
            if (padDrag == null) return false
            padDragFrom[0] = px; padDragFrom[1] = py; padDragStart = r; padDragPage = slot
            return true
        }
        val drag = padDrag ?: return false
        val slot = padDragPage ?: return false
        val start = padDragStart ?: return false
        val px = (e.x - viewport.tx) / scale - slot.left; val py = (e.y - viewport.ty) / scale - slot.top
        val r = when (drag) {
            PadDrag.RESIZE -> PadMoves.resize(start, px, py, start.width / start.height, slot.width, slot.height)
            PadDrag.MOVE -> {
                val dx = (px - padDragFrom[0]).coerceIn(-start.left, slot.width - start.right)
                val dy = (py - padDragFrom[1]).coerceIn(-start.top, slot.height - start.bottom)
                app.folio.core.model.Rect(start.left + dx, start.top + dy, start.right + dx, start.bottom + dy)
            }
        }
        padWindow = PadWindow(win.pageId, r)
        if (masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL) {
            padDrag = null
            listener?.onPadWindowChanged(win.pageId, r)
        }
        return true
    }

    /** A sticky note folding into its tab or unfolding from it (about 300 ms). */
    private class StickyFold(val pageId: String, val note: app.folio.core.model.StickyNote, val collapsing: Boolean, val start: Long)
    private var fold: StickyFold? = null

    /** The id of a note still unfolding on [slot] (kept off its tiles until it's fully open). */
    private fun unfolding(slot: PageSlot): String? = fold?.takeIf { !it.collapsing && it.pageId == slot.pageId }?.note?.id

    /** The note drawn scaled between its full size and its tab; the tiles show the end state. */
    private fun drawFold(canvas: Canvas, f: StickyFold) {
        val t = ((SystemClock.uptimeMillis() - f.start) / FOLD_MS.toFloat()).coerceIn(0f, 1f)
        if (t >= 1f) {
            val wasUnfolding = !f.collapsing
            fold = null
            if (wasUnfolding) requestRender() // the tiles show the open note again
            return
        }
        val e = t * t * (3f - 2f * t)
        val k = if (f.collapsing) e else 1f - e // 0 = open note, 1 = tab
        val n = f.note
        val tab = StickyGeometry.tab(n)
        val l = n.x + (tab.left - n.x) * k; val tp = n.y + (tab.top - n.y) * k
        val r = n.x + n.width + (tab.right - n.x - n.width) * k; val b = n.y + n.height + (tab.bottom - n.y - n.height) * k
        canvas.save()
        canvas.translate(l, tp)
        canvas.scale((r - l) / n.width, (b - tp) / n.height)
        canvas.translate(-n.x, -n.y)
        renderer.drawSticky(canvas, n)
        canvas.restore()
        requestRender()
    }

    /** A recording, table or minimised sticky note under a page point (topmost first). */
    private fun objectAt(x: Float, y: Float): String? {
        val o = active.objects
        o.stickies.lastOrNull { it.minimized && StickyGeometry.tab(it).inflate(6f / scale).contains(x, y) }?.let { return it.id }
        o.audio.lastOrNull { it.bounds.contains(x, y) }?.let { return it.id }
        o.tables.lastOrNull { it.bounds.contains(x, y) }?.let { return it.id }
        return null
    }

    /** The finished stroke after the pen's options: auto-fixed shape, straightened, fitted to text, styled. */
    private fun finishedStroke(raw: Stroke, snappedAlready: Boolean): Stroke {
        var s = raw
        if (!snappedAlready && tool != ToolType.TAPE) InkShaping.asDot(s, DOT_SLOP_DP * resources.displayMetrics.density / scale)?.let { s = it }
        if (autoFixShapes && !snappedAlready && tool != ToolType.HIGHLIGHTER && tool != ToolType.TAPE && s.size >= 3) {
            // No deliberate hold here, so only what is clearly a drawn shape: big on screen, properly closed.
            ShapeRecognizer.recognize(s.x, s.y, minSize = AUTO_SHAPE_MIN_DP * resources.displayMetrics.density / scale, closeGap = 0.1f, strict = true)?.let { shape ->
                s = shape.toStroke(tool, color, penWidth, s.pressure.average().toFloat().coerceIn(0.15f, 1f), s.startedAt)
            }
        }
        if (straighten && s.size >= 2) s = InkShaping.straight(s)
        if (adjustToText && (tool == ToolType.HIGHLIGHTER || tool == ToolType.TAPE)) {
            val nearby = InkShaping.covered(s, strokes, index, texts)
            if (nearby != null) s = InkShaping.fitTo(s, nearby)
        }
        if (!look.isDefault) s = s.withLook(look)
        return s
    }

    // ---------------------------------------------------------- eyedropper

    /** Picks the colour under a view point: the topmost ink, text or picture there, else the paper. */
    private fun pickColorAt(vx: Float, vy: Float) {
        eyedropperActive = false
        val slot = slotAt(vx, vy)?.takeIf { it.loaded } ?: return
        val px = (vx - viewport.tx) / scale - slot.left; val py = (vy - viewport.ty) / scale - slot.top
        val slack = 6f * resources.displayMetrics.density / scale
        val hit = slot.strokeIndex.query(px - slack, py - slack, px + slack, py + slack).map { slot.strokes[it] }
            .lastOrNull { StrokeGeometry.hits(it, px, py, slack) }
        val color = hit?.color?.let { it or 0xFF000000 }
            ?: slot.texts.lastOrNull { it.bounds.contains(px, py) }?.color
            ?: slot.images.lastOrNull { it.bounds.contains(px, py) }?.let { img ->
                assets?.image(img.attachmentId, decode = true)?.let { bmp ->
                    val bx = ((px - img.x) / img.width * bmp.width).toInt().coerceIn(0, bmp.width - 1)
                    val by = ((py - img.y) / img.height * bmp.height).toInt().coerceIn(0, bmp.height - 1)
                    (bmp.getPixel(bx, by).toLong() and 0xFFFFFFFFL) or 0xFF000000
                }
            }
            ?: slot.objects.stickies.lastOrNull { it.bounds.contains(px, py) }?.color
            ?: pageLook.pageColor ?: 0xFFFFFFFF
        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        listener?.onColorPicked(color)
    }

    /** [px] view pixels at the bottom are covered (the keyboard): the page can scroll that much further. */
    fun setBottomInset(px: Float) {
        if (viewport.bottomInset == px) return
        viewport.bottomInset = px
        viewport.clamp()
        syncMatrices()
        requestRender()
    }

    /**
     * Scrolls the least needed for rows [top]..[bottom] of page [pageId] (page units) to show
     * above the covered bottom, with a small margin (the text box being typed).
     */
    fun revealRows(pageId: String, top: Float, bottom: Float) {
        val (_, oy) = pageOrigin(pageId) ?: return
        val margin = 16f * resources.displayMetrics.density
        val visibleBottom = height - viewport.bottomInset - margin
        val vt = oy + top * scale; val vb = oy + bottom * scale
        val dy = when {
            vb > visibleBottom -> maxOf(visibleBottom - vb, margin - vt) // never push its top off the view
            vt < margin -> margin - vt
            else -> 0f
        }
        if (dy == 0f) return
        viewport.panBy(0f, dy)
        syncMatrices()
        requestRender()
    }

    /** Fit the page width to the view (the zoom pill's button). */
    fun fitToWidth() {
        viewport.fitWidth()
        syncMatrices()
        listener?.onZoomChanged(100)
        requestRender()
    }

    // ---------------------------------------------------------- lasso select

    private fun handleRadiusPx() = 22f * resources.displayMetrics.density

    /** Selection bounds in page units, including any live transform. */
    private fun currentSelectionBox(): app.folio.core.model.Rect? {
        if (selection.isEmpty()) return null
        val sb = app.folio.core.ink.Lasso.bounds(strokes.filter { it.id in selection })
        val eb = selectedElementBounds()
        val b = when { sb == null -> eb; eb == null -> sb; else -> sb.union(eb) } ?: return null
        return selTransform.mapRect(b)
    }

    /** The selection box in view pixels (for the floating menu above it), or null. */
    fun selectionViewRect(): RectF? {
        val b = currentSelectionBox() ?: return null
        val r = RectF(b.left, b.top, b.right, b.bottom)
        pageToView.mapRect(r)
        return r
    }

    private fun beginSelectionGesture(vx: Float, vy: Float) {
        val p = toPage(vx, vy); val px = p[0]; val py = p[1]
        selDownX = px; selDownY = py
        val box = currentSelectionBox()
        val pad = handleRadiusPx() / scale
        val corners = box?.let { listOf(it.left to it.top, it.right to it.top, it.right to it.bottom, it.left to it.bottom) }
        val corner = corners?.indexOfFirst { (cx, cy) -> kotlin.math.hypot(px - cx, py - cy) <= pad * 1.4f } ?: -1
        val rotHandle = box?.let { ((it.left + it.right) / 2) to (it.top - ROTATE_STEM_DP * resources.displayMetrics.density / scale) }
        selGesture = when {
            rotHandle != null && kotlin.math.hypot(px - rotHandle.first, py - rotHandle.second) <= pad * 1.4f -> SelGesture.ROTATE
            corner >= 0 -> { resizeCorner = corner; SelGesture.RESIZE }
            box != null && box.inflate(pad / 2f).contains(px, py) -> SelGesture.MOVE
            else -> SelGesture.LASSO
        }
        selBox = box
        lassoPoints.clear()
        if (selGesture == SelGesture.LASSO) { lassoPoints += px; lassoPoints += py }
        requestRender()
    }

    private fun moveSelectionGesture(e: MotionEvent) {
        val idx = e.findPointerIndex(activePointerId).takeIf { it >= 0 } ?: return
        when (selGesture) {
            SelGesture.LASSO -> if (lassoRectangle) {
                // Rectangle: keep the start corner and the current corner only.
                val p = toPage(e.getX(idx), e.getY(idx))
                while (lassoPoints.size > 2) lassoPoints.removeAt(lassoPoints.size - 1)
                lassoPoints += p[0]; lassoPoints += p[1]
            } else {
                for (h in 0 until e.historySize) {
                    val p = toPage(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h)); lassoPoints += p[0]; lassoPoints += p[1]
                }
                val p = toPage(e.getX(idx), e.getY(idx)); lassoPoints += p[0]; lassoPoints += p[1]
            }
            SelGesture.MOVE -> {
                val p = toPage(e.getX(idx), e.getY(idx))
                selTransform = SelectionTransform(dx = p[0] - selDownX, dy = p[1] - selDownY)
            }
            SelGesture.RESIZE -> {
                val box = selBox ?: return
                val p = toPage(e.getX(idx), e.getY(idx))
                // Anchored at the opposite corner; uniform scale.
                val ax = if (resizeCorner == 0 || resizeCorner == 3) box.right else box.left
                val ay = if (resizeCorner == 0 || resizeCorner == 1) box.bottom else box.top
                val sx = kotlin.math.abs(p[0] - ax) / box.width.coerceAtLeast(1f)
                val sy = kotlin.math.abs(p[1] - ay) / box.height.coerceAtLeast(1f)
                val sc = maxOf(sx, sy).coerceIn(SelectionTransform.MIN_SCALE, SelectionTransform.MAX_SCALE)
                selTransform = SelectionTransform(scale = sc, anchorX = ax, anchorY = ay)
            }
            SelGesture.ROTATE -> {
                val box = selBox ?: return
                val p = toPage(e.getX(idx), e.getY(idx))
                selTransform = SelectionTransform.rotate(box, selDownX, selDownY, p[0], p[1])
            }
            SelGesture.NONE -> Unit
        }
        requestRender()
    }

    private fun finishSelectionGesture(canceled: Boolean) {
        val g = selGesture
        selGesture = SelGesture.NONE
        if (canceled) { selTransform = SelectionTransform(); lassoPoints.clear(); return }
        when (g) {
            SelGesture.LASSO -> {
                val polygon = if (lassoRectangle && lassoPoints.size >= 4) {
                    app.folio.core.ink.Lasso.rectangle(lassoPoints[0], lassoPoints[1], lassoPoints[lassoPoints.size - 2], lassoPoints[lassoPoints.size - 1])
                } else lassoPoints.toFloatArray()
                val visible = if (pageLook.hideTape) strokes.filter { it.tool != ToolType.TAPE } else strokes
                val ids = LinkedHashSet(app.folio.core.ink.Lasso.select(visible, polygon, partial = selectPartial))
                // Text boxes, pictures and other objects: their centre inside the loop (or the whole box when partial is off).
                fun takes(r: app.folio.core.model.Rect) = if (selectPartial) app.folio.core.ink.Lasso.contains(polygon, (r.left + r.right) / 2, (r.top + r.bottom) / 2)
                else app.folio.core.ink.Lasso.containsRect(polygon, r)
                for (t in texts) if (takes(t.bounds)) ids += t.id
                for (i in images) if (takes(i.bounds)) ids += i.id
                for (n in active.objects.stickies) if (takes(n.bounds)) ids += n.id
                for (t in active.objects.tables) if (takes(t.bounds)) ids += t.id
                for (a in active.objects.audio) if (takes(a.bounds)) ids += a.id
                // A tap (no real loop) selects the text box or picture under the pen.
                val loop = app.folio.core.ink.Lasso.polygonBounds(polygon)
                if (ids.isEmpty() && maxOf(loop.width, loop.height) * scale < 24f * resources.displayMetrics.density) {
                    val tx = lassoPoints.getOrElse(0) { 0f }; val ty = lassoPoints.getOrElse(1) { 0f }
                    val taped = stickyTapeAt(tx, ty)
                    if (taped != null) { lassoPoints.clear(); listener?.onStickyTapeTap(active.pageId, taped); return }
                    elementAt(tx, ty)?.let { ids += it }
                }
                lassoPoints.clear()
                selectionPage = active.pageId
                listener?.onLassoSelected(active.pageId, ids)
            }
            SelGesture.MOVE, SelGesture.RESIZE, SelGesture.ROTATE -> {
                val t = selTransform
                // Keep drawing the transform until the edited strokes come back through setPage.
                if (!t.isIdentity && selection.isNotEmpty()) listener?.onSelectionTransformed(active.pageId, selection, t)
                else selTransform = SelectionTransform()
            }
            SelGesture.NONE -> Unit
        }
    }

    private fun transformMatrix(t: SelectionTransform): Matrix = selMatrix.apply {
        setRotate(t.rotation, t.pivotX, t.pivotY)
        postTranslate(-t.anchorX, -t.anchorY)
        postScale(t.scale, t.scale)
        postTranslate(t.anchorX + t.dx, t.anchorY + t.dy)
    }

    private fun drawSelectionOverlay(canvas: Canvas) {
        val px = 1f / scale
        selPaint.strokeWidth = 2f * px
        selPaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f * px, 7f * px), 0f)
        if (selGesture == SelGesture.LASSO && lassoRectangle && lassoPoints.size >= 4) {
            val n = lassoPoints.size
            canvas.drawRect(
                minOf(lassoPoints[0], lassoPoints[n - 2]), minOf(lassoPoints[1], lassoPoints[n - 1]),
                maxOf(lassoPoints[0], lassoPoints[n - 2]), maxOf(lassoPoints[1], lassoPoints[n - 1]), selPaint,
            )
        } else if (selGesture == SelGesture.LASSO && lassoPoints.size >= 4) {
            lassoPath.rewind()
            lassoPath.moveTo(lassoPoints[0], lassoPoints[1])
            var i = 2
            while (i < lassoPoints.size) { lassoPath.lineTo(lassoPoints[i], lassoPoints[i + 1]); i += 2 }
            canvas.drawPath(lassoPath, selPaint)
        }
        val box = currentSelectionBox() ?: return
        // A thin blue box, white round handles at the corners and a rotate handle on a stem above.
        selPaint.pathEffect = null
        selPaint.strokeWidth = 1.6f * px
        canvas.drawRect(box.left, box.top, box.right, box.bottom, selPaint)
        if (selGesture == SelGesture.LASSO) return
        val d = resources.displayMetrics.density
        val hr = 7f * d * px
        handleRim.strokeWidth = 1f * px
        for ((x, y) in listOf(box.left to box.top, box.right to box.top, box.right to box.bottom, box.left to box.bottom)) {
            canvas.drawCircle(x, y, hr, handlePaint); canvas.drawCircle(x, y, hr, handleRim)
        }
        val cx = (box.left + box.right) / 2; val stemTop = box.top - ROTATE_STEM_DP * d * px
        canvas.drawLine(cx, box.top, cx, stemTop + 11f * d * px, selPaint)
        canvas.drawCircle(cx, stemTop, 11f * d * px, handlePaint); canvas.drawCircle(cx, stemTop, 11f * d * px, handleRim)
        // A small curved arrow inside the rotate handle.
        val r = 5.5f * d * px
        rotateArc.set(cx - r, stemTop - r, cx + r, stemTop + r)
        rotatePaint.strokeWidth = 1.6f * d * px
        canvas.drawArc(rotateArc, -60f, 280f, false, rotatePaint)
        val a = Math.toRadians(-60.0)
        val tipX = cx + r * kotlin.math.cos(a).toFloat(); val tipY = stemTop + r * kotlin.math.sin(a).toFloat()
        canvas.drawLine(tipX, tipY, tipX - 3.2f * d * px, tipY - 0.4f * d * px, rotatePaint)
        canvas.drawLine(tipX, tipY, tipX + 0.6f * d * px, tipY - 3.2f * d * px, rotatePaint)
    }
    private val rotateArc = RectF()
    private val rotatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = SELECTION_BLUE; strokeCap = Paint.Cap.ROUND }

    // ---------------------------------------------------------------- drawing

    private fun renderFrame(canvas: Canvas) {
        val perfStart = if (debugInput) System.nanoTime() else 0L
        drawPageContent(canvas)
        perfScript?.drawMs?.add((System.nanoTime() - perfStart) / 1e6f)
        val erasing = action == GestureAction.ERASE
        if (debugInput && (builder != null || navigating || erasing)) {
            val end = System.nanoTime()
            perfDrawMs += (end - perfStart) / 1e6f
            if ((builder != null || erasing) && perfLastInputNanos > 0) perfLatencyMs += (end - perfLastInputNanos) / 1e6f
        }
    }

    private fun drawPageContent(canvas: Canvas) {
        fitIfNeeded()
        // Never paint outside this view (e.g. over the toolbar when zoomed or scrolled).
        canvas.clipRect(0, 0, width, height)
        val s = stack
        if (s == null || s.count == 0 || !viewport.ready) { canvas.drawPaint(deskPaint); return }
        budget.nextFrame()
        val sc = scale; val tx = viewport.tx; val ty = viewport.ty
        val vis = s.overlapping(-ty / sc, (height - ty) / sc)
        // Desk only around the pages: tiles are opaque, so each pixel is painted once.
        canvas.save()
        for (i in vis) canvas.clipOutRect(tx + s.left(i) * sc, ty + s.top(i) * sc, tx + (s.left(i) + s.pageWidth(i)) * sc, ty + s.bottom(i) * sc)
        canvas.drawPaint(deskPaint)
        canvas.restore()
        // Tiles render for the page in view first, then the other visible pages, then neighbours.
        val cur = currentIndex(s)
        val order = ArrayList<TileCache>(vis.count() + 2)
        for (i in vis) {
            val slot = slots[s.ids[i]]
            val pl = tx + s.left(i) * sc; val pt = ty + s.top(i) * sc
            if (slot == null || !slot.loaded) drawPlaceholder(canvas, s, i, pl, pt)
            else {
                val moving = slot === active && !selTransform.isIdentity && selection.isNotEmpty()
                val frozen = (slot === active && wiping) || slot.wipeAwaitingEcho
                slot.tiles.preview = previewOf(slot.pageId)
                slot.tiles.setContent(slot.pageId, slot.strokeIndex, excludedFromLayer(slot, moving), slot.paper, slot.width, slot.height, slot.pdf, slot.pdfBitmap, slot.texts, slot.images, assets, slot.objects, pageLook)
                if (perfSkip?.contains("ink") != true) {
                    slot.tiles.draw(canvas, sc, pl, pt, width.toFloat(), height.toFloat(), baseScale(slot), frozen)
                }
                if (i == cur) order.add(0, slot.tiles) else order += slot.tiles
            }
            if (slot != null) drawOverlays(canvas, slot, pl, pt)
            if (showPageNumbers && s.count > 1) drawPageNumber(canvas, i, s.count, pl + s.pageWidth(i) * sc, pt + s.pageHeight(i) * sc)
        }
        for (i in window) if (i !in vis) slots[s.ids[i]]?.takeIf { it.loaded }?.let { slot ->
            slot.tiles.setContent(slot.pageId, slot.strokeIndex, excludedFromLayer(slot, false), slot.paper, slot.width, slot.height, slot.pdf, slot.pdfBitmap, slot.texts, slot.images, assets, slot.objects, pageLook)
            slot.tiles.preview = previewOf(slot.pageId)
            slot.tiles.prefetch(sc, baseScale(slot))
            order += slot.tiles
        }
        budget.order = order
        budget.trim()
        drawCursor(canvas)
        drawLaser(canvas)
    }

    private val pageNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }

    /** "3/12" in small grey text at the page's bottom-right corner (inside the page). */
    private fun drawPageNumber(canvas: Canvas, i: Int, n: Int, right: Float, bottom: Float) {
        val d = resources.displayMetrics.density
        pageNumberPaint.textSize = 12f * d
        pageNumberPaint.color = if (pageLook.dark) 0xFF77777C.toInt() else 0xFF9A9AA0.toInt()
        canvas.drawText("${i + 1}/$n", right - 12f * d, bottom - 10f * d, pageNumberPaint)
    }

    /** Scale at which a whole page is visible: its tiles' base level. */
    private fun baseScale(slot: PageSlot) = if (height > 0) minOf(width / slot.width, height / slot.height) else viewport.fitPageScale

    /** Ink not yet in a page's tiles, in its page units and clipped to it; the live stroke on the active page. */
    private fun drawOverlays(canvas: Canvas, slot: PageSlot, pl: Float, pt: Float) {
        val isActive = slot === active
        val moving = isActive && !selTransform.isIdentity && selection.isNotEmpty()
        val live = isActive && !wetLive && builder != null
        val folding = fold?.takeIf { it.pageId == slot.pageId }
        val pad = padWindow?.takeIf { it.pageId == slot.pageId }
        if (pad == null && folding == null && slot.partialPieces.isEmpty() && slot.pendingCommits.isEmpty() && !live && !(isActive && (moving || selection.isNotEmpty() || selGesture != SelGesture.NONE || sweepPreview.isNotEmpty()))) return
        canvas.save()
        canvas.translate(pl, pt); canvas.scale(scale, scale)
        canvas.clipRect(0f, 0f, slot.width, slot.height)
        pageLook.applyTo(renderer, slot.pdf != null)
        // Wiped tiles already show what's left; otherwise draw the remaining pieces.
        val frozen = (isActive && wiping) || slot.wipeAwaitingEcho
        if (!frozen) for (pieces in slot.partialPieces.values) for (p in pieces) renderer.drawStroke(canvas, artFor(p))
        if (moving) {
            canvas.save()
            canvas.concat(transformMatrix(selTransform))
            val snap = selSnapshot
            if (snap != null && snap.selection === selection && snap.strokes === strokes) {
                canvas.drawBitmap(snap.bitmap, null, snap.dst, snapshotPaint)
            } else {
                for (st in strokes) if (st.id in selection) renderer.drawStroke(canvas, artFor(st))
                drawMovingElements(canvas)
            }
            canvas.restore()
        }
        if (folding != null) drawFold(canvas, folding)
        if (pad != null) drawPadWindow(canvas, pad.rect)
        if (isActive) for (st in sweepPreview.values) renderer.drawStroke(canvas, artFor(st))
        if (isActive) drawSelectionOverlay(canvas)
        for (st in slot.pendingCommits) renderer.drawStroke(canvas, artFor(st))
        if (live) builder?.let { drawLive(canvas, it) }
        canvas.restore()
    }

    /** A page whose content hasn't arrived: its paper, and its thumbnail when one is cached. */
    private fun drawPlaceholder(canvas: Canvas, s: PageStack, i: Int, pl: Float, pt: Float) {
        val w = s.pageWidth(i); val h = s.pageHeight(i)
        canvas.save()
        canvas.translate(pl, pt); canvas.scale(scale, scale)
        canvas.clipRect(0f, 0f, w, h)
        pageLook.applyTo(renderer, false)
        renderer.drawPaper(canvas, paperOf(s.ids[i]), w, h)
        if (!pageLook.dark) previewOf(s.ids[i])?.let { renderer.drawPdfBackground(canvas, it, w, h) }
        canvas.restore()
    }

    // ------------------------------------------------------------ page tiles

    /**
     * Strokes deliberately hidden from a page's tiles (being erased or moved, or the text box
     * being typed). The same set instance is returned while its contents don't change, so
     * tiles can compare cheaply.
     */
    private fun excludedFromLayer(slot: PageSlot, moving: Boolean): Set<String> {
        val e = when {
            slot.erasedPending.isEmpty() && slot.partialPieces.isEmpty() && !moving && hiddenIds.isEmpty() &&
                (slot !== active || sweepPreview.isEmpty()) && unfolding(slot) == null -> emptySet()
            else -> buildSet {
                addAll(slot.erasedPending); addAll(slot.partialPieces.keys); addAll(hiddenIds)
                unfolding(slot)?.let { add(it) }
                if (slot === active) addAll(sweepPreview.keys)
                if (moving) addAll(selection)
            }
        }
        if (e == slot.lastExcluded) return slot.lastExcluded
        slot.lastExcluded = e
        return e
    }

    // ---- debug-only scripted zoom/pan benchmark (scripts/perf_zoom.py) ----
    private var perfScript: PerfScript? = null

    private inner class PerfScript : android.view.Choreographer.FrameCallback {
        // Phases: zoom in to 3× fit width, hold, pan down, zoom back out, hold. Times in ms.
        private val phases = listOf("zoom_in" to 1200L, "hold" to 1500L, "pan" to 1500L, "zoom_out" to 1200L, "settle" to 2500L)
        private var phase = 0
        private var phaseStart = 0L
        private var lastFrame = 0L
        private val intervals = ArrayList<Float>()
        private var sharpAt = -1L
        private var zoomEnd = 0L
        val drawMs = ArrayList<Float>()

        fun start() {
            viewport.fitWidth(); syncMatrices(); requestRender()
            android.view.Choreographer.getInstance().postFrameCallback(this)
        }

        override fun doFrame(now: Long) {
            val t = now / 1_000_000
            if (phaseStart == 0L) phaseStart = t
            if (lastFrame != 0L && phase < 4) intervals += (now - lastFrame) / 1e6f
            lastFrame = now
            val (name, len) = phases[phase]
            val f = ((t - phaseStart).toFloat() / len).coerceIn(0f, 1f)
            val cx = width / 2f; val cy = height / 3f
            when (name) {
                "zoom_in", "zoom_out" -> {
                    val from = if (name == "zoom_in") 1f else 3f; val to = if (name == "zoom_in") 3f else 1f
                    val target = viewport.fitWidthScale * (from + (to - from) * f)
                    viewport.zoomBy(target / viewport.scale, cx, cy)
                }
                "pan" -> viewport.panBy(0f, -height * 1.2f * (1f / 90f))
                "hold" -> if (sharpAt < 0 && isSharp()) sharpAt = t
            }
            syncMatrices(); requestRender()
            if (name == "zoom_in" && f >= 1f) zoomEnd = t
            if (f >= 1f) { phase++; phaseStart = t }
            if (phase < phases.size) android.view.Choreographer.getInstance().postFrameCallback(this)
            else finish()
        }

        private fun finish() {
            perfScript = null
            fun pct(v: List<Float>, q: Float) = v.sorted().let { if (it.isEmpty()) -1f else it[((it.size - 1) * q).toInt()] }
            val tileMs = synchronized(tiles.renderMs) { ArrayList(tiles.renderMs).also { tiles.renderMs.clear() } }
            Log.d("FolioPerf", "tiles rendered=%d p50=%.1f p95=%.1f max=%.1f".format(tileMs.size, pct(tileMs, .5f), pct(tileMs, .95f), tileMs.maxOrNull() ?: -1f))
            Log.d(
                "FolioPerf",
                "zoomscript skip=$perfSkip strokes=%d frames=%d interval_p50=%.1f interval_p95=%.1f interval_max=%.1f slow_frames=%d draw_p95=%.1f draw_max=%.1f sharp_after_zoom_ms=%d".format(
                    strokes.size, intervals.size, pct(intervals, .5f), pct(intervals, .95f), intervals.maxOrNull() ?: -1f,
                    intervals.count { it > 25f }, pct(drawMs, .95f), drawMs.maxOrNull() ?: -1f, if (sharpAt < 0) -1 else sharpAt - zoomEnd,
                ),
            )
            perfSkip = null
        }
    }

    /** True when everything on screen is rendered sharp at the current zoom. */
    private fun isSharp(): Boolean = tiles.lastDrawSharp

    /** Debug only: a layer to leave out while benchmarking ("ink", "paper", "desk"), to find what costs GPU time. */
    private var perfSkip: String? = null

    /** Debug benches: zoom to [z] × fit width around the centre of the view, once laid out. */
    internal fun benchZoom(z: Float) = post {
        fitIfNeeded()
        viewport.zoomBy(viewport.fitWidthScale * z / viewport.scale, width / 2f, height / 2f)
        syncMatrices(); requestRender()
    }

    /** Debug builds only: runs the scripted zoom/pan benchmark on the open page. */
    fun runPerfScript(skip: String? = null) {
        if (!debugInput || perfScript != null) return
        perfSkip = skip
        perfScript = PerfScript().also { it.start() }
    }

    override fun onDetachedFromWindow() {
        for (slot in slots.values) slot.release()
        slots.clear()
        shown = emptyMap() // re-attached: the next setDocument fills the slots again
        active = noPage
        if (attached?.get() === this) attached = null
        super.onDetachedFromWindow()
    }

    private fun drawLive(canvas: Canvas, b: StrokeBuilder) {
        renderer.drawStroke(canvas, liveArt(b))
    }

    private fun liveArt(b: StrokeBuilder): StrokeArt = liveSamples(b).art

    /** The stroke being written: samples (real ones, then predicted) and its art. */
    private class LiveSamples(val xs: FloatArray, val ys: FloatArray, val n: Int, val real: Int, val art: StrokeArt)

    /** Live ink goes to the wet layer when it's in use, else the page surface draws it. */
    private fun renderLive() { if (wetLive) pushWet(full = false) else requestRender() }

    /** The stroke being written (with predicted points ahead of the pen) as drawable art. */
    private fun liveSamples(b: StrokeBuilder): LiveSamples {
        snapped?.let { shape ->
            val st = shape.toStroke(tool, color, penWidth, snapPressure)
            return LiveSamples(st.x, st.y, st.size, st.size, StrokeArt.of(st))
        }
        val s = b.build()
        val pred = predicted
        val extra = if (pred != null && action == GestureAction.DRAW && s.size > 0) pred.historySize + 1 else 0
        val n = s.size + extra
        val xs = s.x.copyOf(n); val ys = s.y.copyOf(n); val ps = s.pressure.copyOf(n)
        val ts = s.tilt.copyOf(n); val os = s.orientation.copyOf(n)
        if (pred != null && extra > 0) {
            // Predicted points carry the pen's latest pressure, tilt and azimuth, so the tip
            // doesn't change shape (a tilted pencil's tip used to flick thin).
            val last = s.size - 1
            for (k in 0 until extra) {
                val vx = if (k < pred.historySize) pred.getHistoricalX(0, k) else pred.x
                val vy = if (k < pred.historySize) pred.getHistoricalY(0, k) else pred.y
                val p = toPage(vx, vy)
                xs[s.size + k] = p[0]; ys[s.size + k] = p[1]
                ps[s.size + k] = s.pressure[last]; ts[s.size + k] = s.tilt[last]; os[s.size + k] = s.orientation[last]
            }
        }
        return LiveSamples(xs, ys, n, s.size, StrokeArt.live(tool, color, penWidth, xs, ys, ps, ts, os, n, look))
    }

    private fun drawCursor(canvas: Canvas) {
        if (hoverX < 0) return
        val r = if (hoverErase) eraserRadiusPage() * scale else max(2f, penWidth * scale / 2f)
        cursorPaint.color = if (hoverErase) 0xFF6750A4.toInt() else (color.toInt() and 0x00FFFFFF) or 0x99000000.toInt()
        canvas.drawCircle(hoverX, hoverY, r, cursorPaint)
    }



    companion object {
        /** Computes partial-eraser pieces at pen-up, one wipe at a time, in order. */
        private val wipeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "folio-erase") }
        /** Renders big selections into a bitmap for smooth dragging. */
        private val selectionExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "folio-selection") }
        private const val SNAPSHOT_MIN_STROKES = 150
        /** Largest selection bitmap (pixels): about 24 MB. */
        private const val SNAPSHOT_MAX_PX = 6_000_000f

        /** The canvas currently on screen, for window-level routing of side-button gestures. */
        var attached: java.lang.ref.WeakReference<InkCanvasView>? = null
            private set

        fun isSideButtonGesture(ev: MotionEvent): Boolean =
            ev.actionMasked in SPEN_ACTION_DOWN..SPEN_ACTION_CANCEL ||
                (ev.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS && (ev.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0)

        private const val TAG = "FolioInput"
        private const val SELECTION_BLUE = 0xFF3E91FF.toInt()
        /** A sticky note folds into its tab (or opens) over this long. */
        private const val FOLD_MS = 300L
        /** Rotate handle: this far above the selection box (dp). */
        private const val ROTATE_STEM_DP = 30f
        /** The laser trail stays this long after the last lift, then fades over [LASER_FADE_MS]. */
        private const val LASER_HOLD_MS = 600L
        private const val LASER_FADE_MS = 400L
        private val LASER_RAINBOW = intArrayOf(
            0xFFFF4D4D.toInt(), 0xFFFFA63D.toInt(), 0xFFFFE24D.toInt(), 0xFF5BD66B.toInt(), 0xFF3DD6D0.toInt(), 0xFF4D8BFF.toInt(), 0xFFA35BFF.toInt(), 0xFFFF5BC8.toInt(),
        )
        private const val NO_PRESSURE_EFFORT = 0.6f
        /** Pen held this still (dp) for this long (ms) at the end of a stroke snaps it to a shape. */
        private const val HOLD_SLOP_DP = 4f
        private const val HOLD_TO_SNAP_MS = 600L
        /** Smallest scrawl (dp on screen, diagonal) taken as scribble-to-erase. */
        private const val SCRIBBLE_MIN_DP = 14f
        /** Longest side-button press in the air still taken as a click (longer is hold-to-erase). */
        private const val HOVER_CLICK_MS = 800L
        /** Smallest stroke (dp on screen, diagonal) hold-to-snap turns into a shape: above handwriting size. */
        private const val HOLD_SHAPE_MIN_DP = 32f
        /** Smallest stroke (dp on screen) "Auto-fix shapes" converts on pen-up without a hold. */
        private const val AUTO_SHAPE_MIN_DP = 56f
        /** A pen mark whose every point stays within this of pen-down (dp on screen) is a dot. */
        private const val DOT_SLOP_DP = 2.5f
        /** A finished stroke stays on the wet layer this long after the page surface posts it (3 vsyncs at 60 Hz). */
        private const val WET_HOLD_NANOS = 50_000_000L
        // Samsung-specific MotionEvent actions sent while the S Pen side button is held.
        private const val SPEN_ACTION_DOWN = 211
        private const val SPEN_ACTION_UP = 212
        private const val SPEN_ACTION_MOVE = 213
        private const val SPEN_ACTION_CANCEL = 214
    }
}
