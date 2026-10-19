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
import app.folio.core.ink.ShapeRecognizer
import app.folio.core.model.Argb
import app.folio.core.model.PageImage
import app.folio.core.model.PaperTemplate
import app.folio.core.model.PdfBackground
import app.folio.core.model.TextBox
import app.folio.core.model.Stroke
import app.folio.core.model.ToolType
import app.folio.notes.input.GestureAction
import app.folio.notes.input.InputPolicy
import app.folio.notes.input.PointerKind
import kotlin.math.max


/**
 * The writing surface. It owns only transient input/render state. The page's
 * stroke list comes from [setStrokes], and finished edits go out through [listener].
 *
 * Input path: MotionEvent → [InputPolicy] decides draw/erase/navigate/ignore →
 * every historical sample is mapped to page coordinates → [StrokeBuilder]
 * stabilises it → outline from [StrokeGeometry] → Path → canvas.
 * Predicted samples (androidx motion prediction) are drawn ahead of the pen
 * tip but are never stored.
 */
@SuppressLint("ViewConstructor")
class InkCanvasView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Choreographer.FrameCallback {

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
        for (h in wetHeld) if (h.postedAt == 0L && h.stroke in pendingCommitsOrTiles()) { h.postedAt = now; marked = true }
        if (marked) Choreographer.getInstance().postFrameCallback(wetTick)
    }

    // ---- wet ink (front-buffered live stroke, see WetInkLayer) ----
    internal var wet: WetInkLayer? = null
    /** The stroke in progress is drawn on the wet layer instead of the page surface. */
    private var wetLive = false
    /** Finished strokes still shown wet until the page surface has displayed them. */
    private class WetHeld(val stroke: Stroke, val art: StrokeArt) { var postedAt = 0L }
    private val wetHeld = ArrayList<WetHeld>()
    private var wetTail: RectF? = null
    private val wetMatrix = FloatArray(9)
    private val wetRect = RectF()

    /** Strokes the page surface draws: pending commits and everything in the page list. */
    private fun pendingCommitsOrTiles(): Collection<Stroke> = object : AbstractCollection<Stroke>() {
        override val size get() = pendingCommits.size
        override fun iterator() = pendingCommits.iterator()
        override fun contains(element: Stroke) = element in pendingCommits || index.indexOf(element.id) >= 0
    }

    private fun canUseWet() = wet?.ready == true && tool != ToolType.HIGHLIGHTER && !lassoMode && !textMode

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
        w.show(WetFrame(wetMatrix.copyOf(), arts, clip, if (debugInput) perfLastInputNanos else 0L))
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
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        if (frameScheduled) { Choreographer.getInstance().removeFrameCallback(this); frameScheduled = false }
    }

    interface Listener {
        fun onStrokeFinished(stroke: Stroke)
        fun onStrokesErased(ids: Set<String>)
        /** A lasso loop finished; [ids] is the new selection (empty clears it). */
        fun onLassoSelected(ids: Set<String>)
        /** The selection was moved or resized; commit as one undoable edit. */
        fun onSelectionTransformed(ids: Set<String>, transform: SelectionTransform)
        /** Partial eraser finished: each original stroke id maps to the pieces that remain. */
        fun onStrokesSplit(pieces: Map<String, List<Stroke>>)
        /** Text tool: the page was tapped at ([x], [y]); [textId] is the text box there, if any. */
        fun onTextTap(x: Float, y: Float, textId: String?) {}
        /** Zoom or scroll changed (only reported while [InkCanvasView.reportViewport] is on). */
        fun onViewportChanged() {}
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
    /** Elements hidden from the page while being edited elsewhere (the text box being typed). */
    var hiddenIds: Set<String> = emptySet()
        set(value) { if (field != value) { field = value; requestRender() } }
    /** Report zoom/scroll changes to the listener (e.g. to keep a text field over its box). */
    var reportViewport: Boolean = false

    /** Current view transform: view = page × scale + (tx, ty). */
    val pageScale: Float get() = viewport.scale
    val pageTx: Float get() = viewport.tx
    val pageTy: Float get() = viewport.ty

    /** The visible part of the page, in page units. */
    fun visiblePageArea(): app.folio.core.model.Rect {
        val (l, t) = viewport.toPage(0f, 0f)
        val (r, b) = viewport.toPage(width.toFloat(), height.toFloat())
        return app.folio.core.model.Rect(maxOf(0f, l), maxOf(0f, t), minOf(pageW, r), minOf(pageH, b))
    }
    /** Rendered PDF page shown under the ink, or null for plain paper. */
    var pdfBackground: android.graphics.Bitmap? = null
        set(value) { if (field !== value) { field = value; requestRender() } }
    /** Rectangle selection instead of a freeform loop. */
    var lassoRectangle: Boolean = false
    /** Currently selected stroke ids (owned by the view model). */
    var selection: Set<String> = emptySet()
        set(value) { if (field != value) { field = value; maybeSnapshotSelection(); requestRender() } }

    val policy = InputPolicy(palmTouchMajorPx = 48f * resources.displayMetrics.density)

    /** User setting: once a stylus has been seen, fingers navigate instead of drawing. */
    var penOnlyPreference: Boolean = true
        set(value) { field = value; if (policy.stylusSeen) policy.penOnly = value }

    // ---- page content ----
    private var strokes: List<Stroke> = emptyList()
    /** Text boxes and pictures on the page (owned by the view model). */
    private var texts: List<TextBox> = emptyList()
    private var images: List<PageImage> = emptyList()
    /** The PDF page under the ink, rendered sharp per tile through [assets]. */
    private var pdfPage: PdfBackground? = null
    /** Pictures and PDF files of the open notebook. */
    internal var assets: PageAssets? = null
        set(value) { if (field !== value) { field = value; requestRender() } }
    /** Spatial index of [strokes], rebuilt whenever the list changes. */
    private var index = StrokeIndex(emptyList())
    /** Finished strokes not yet echoed back through [setPage]; drawn so pen-up never blinks. */
    private val pendingCommits = ArrayList<Stroke>()
    private val pendingSince = HashMap<String, Long>()
    /** Outline path of a committed stroke, built once and shared with the tile renderer. */
    private fun artFor(s: Stroke): StrokeArt = StrokeArt.of(s)
    private var pageKey: String? = null
    private var paper = PaperTemplate.RULED
    private var pageW = 1000f
    private var pageH = 1414f

    fun setPage(
        pageId: String, strokes: List<Stroke>, paper: PaperTemplate, width: Float, height: Float,
        texts: List<TextBox> = emptyList(), images: List<PageImage> = emptyList(), pdf: PdfBackground? = null,
    ) {
        if (texts !== this.texts || images !== this.images) { this.texts = texts; this.images = images; requestRender() }
        this.pdfPage = pdf
        // Any new stroke list is the echo of the last edit, wipe included.
        if (strokes !== this.strokes) wipeAwaitingEcho = false
        val setPageStart = System.nanoTime()
        if (perfEraseUpNanos != 0L && strokes !== this.strokes) {
            Log.d("FolioPerf", "erase commit ms=%.1f (pen-up → edited page back)".format((System.nanoTime() - perfEraseUpNanos) / 1e6))
            perfEraseUpNanos = 0L
        }
        pageKey = pageId
        // A prebuilt index may hold its own copy of the same strokes; only a new list needs a new index.
        if (strokes !== this.strokes) {
            val t0 = System.nanoTime()
            val pre = prebuiltIndex
            prebuiltIndex = null
            index = if (pre != null && sameStrokes(pre.strokes, strokes)) pre else index.appending(strokes)
            if (debugInput && strokes.size > 1000) Log.d("FolioPerf", "index build ms=%.1f strokes=%d".format((System.nanoTime() - t0) / 1e6, strokes.size))
        }
        this.strokes = strokes
        this.paper = paper
        pageW = width; pageH = height
        if (pendingCommits.isNotEmpty() || erasedPending.isNotEmpty()) {
            // Look ids up in the index rather than building a set of the whole page.
            val now = SystemClock.uptimeMillis()
            // Normally the echo arrives on the next frame; the timeout covers edits the page never accepts.
            pendingCommits.removeAll { index.indexOf(it.id) >= 0 || now - (pendingSince[it.id] ?: 0) > 1000 }
            pendingSince.keys.retainAll(pendingCommits.mapTo(HashSet()) { it.id })
            erasedPending.retainAll { index.indexOf(it) >= 0 }
        }
        if (partialCommitted) {
            partialPieces.clear(); partialCommitted = false
            tiles.keepWiped() // the wiped tiles now match; the edit patches them exactly
        }
        if (selGesture == SelGesture.NONE) selTransform = SelectionTransform()
        if (selection.isNotEmpty()) maybeSnapshotSelection()
        if (debugInput && strokes.size > 1000) Log.d("FolioPerf", "setPage ms=%.1f".format((System.nanoTime() - setPageStart) / 1e6))
        viewport.setPage(width, height)
        syncMatrices()
        requestRender()
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
    private val erasedPending = LinkedHashSet<String>()
    private var navigating = false
    private var lastPanX = 0f
    private var lastPanY = 0f

    // ---- lasso / selection gesture state ----
    private enum class SelGesture { NONE, LASSO, MOVE, RESIZE }
    private var selGesture = SelGesture.NONE
    private val lassoPoints = ArrayList<Float>()
    /** Live (and just-committed) move/resize, drawn on the selected strokes. */
    private var selTransform = SelectionTransform()
    private var selDownX = 0f
    private var selDownY = 0f
    private var selBox: app.folio.core.model.Rect? = null
    private val selMatrix = Matrix()
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = 0xFF3D5A80.toInt()
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3D5A80.toInt() }
    private val lassoPath = Path()

    // ---- hover state ----
    private var hoverX = -1f
    private var hoverY = -1f
    private var hoverErase = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            viewport.zoomBy(d.scaleFactor, d.focusX, d.focusY)
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
    }

    private fun fitIfNeeded() {
        if (!viewport.ready && width > 0) { viewport.resize(width.toFloat(), height.toFloat()); syncMatrices() }
    }

    private fun syncMatrices() {
        pageToView.setScale(viewport.scale, viewport.scale)
        pageToView.postTranslate(viewport.tx, viewport.ty)
        pageToView.invert(viewToPage)
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
                GestureAction.DRAW -> if (textMode) Unit else if (selGesture != SelGesture.NONE) moveSelectionGesture(e) else {
                    predictor.record(e); appendSamples(e); updatePrediction()
                    e.findPointerIndex(activePointerId).takeIf { it >= 0 }?.let { i ->
                        if (snapped != null) adjustSnap(e.getX(i), e.getY(i)) else watchHold(e.getX(i), e.getY(i))
                    }
                    renderLive()
                }
                GestureAction.ERASE -> eraseAlong(e)
                GestureAction.NAVIGATE -> navigate(e)
                GestureAction.IGNORE -> Unit
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
        if (action == GestureAction.NAVIGATE) scaleDetector.onTouchEvent(e)
        return true
    }

    private fun beginGesture(e: MotionEvent, index: Int) {
        awaitPendingWipe()
        val kind = kindOf(e, index)
        val barrel = sideButtonGesture || (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        action = policy.classifyDown(kind, e.getTouchMajor(index), barrel, eraserSelected, e.pointerCount, e.eventTime)
        if (policy.stylusSeen) policy.penOnly = penOnlyPreference
        activePointerId = e.getPointerId(index)
        when (action) {
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
            }
            GestureAction.ERASE -> {
                requestUnbufferedDispatch(e)
                erasedPending.clear()
                partialPieces.clear(); partialCommitted = false
                startWipeIfPossible()
                val p = toPage(e.getX(index), e.getY(index))
                lastErase = floatArrayOf(p[0], p[1])
                eraseTo(p[0], p[1])
            }
            GestureAction.NAVIGATE -> startPan(e)
            GestureAction.IGNORE -> Unit
        }
        hoverX = -1f
        requestRender()
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
        when (action) {
            GestureAction.DRAW if textMode -> {
                val idx = e.findPointerIndex(activePointerId).takeIf { it >= 0 } ?: index
                val slop = 12f * resources.displayMetrics.density
                if (!canceled && kotlin.math.abs(e.getX(idx) - textDownVX) < slop && kotlin.math.abs(e.getY(idx) - textDownVY) < slop) {
                    listener?.onTextTap(textDownX, textDownY, textAt(textDownX, textDownY))
                }
            }
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
                    if (b.size > 0) {
                        val shape = snapped
                        val stroke = if (shape != null && !canceled) shape.toStroke(tool, color, penWidth, snapPressure, b.build().startedAt) else b.build()
                        val art = artFor(stroke) // build its geometry now, before the next frame needs it
                        if (wetLive) {
                            // Keep it wet until the page surface shows it, so it never blinks out.
                            wetHeld += WetHeld(stroke, art)
                            val tail = wetTail
                            wetLive = false
                            pushWet(full = false, extraClip = viewBounds(art, RectF()).apply { tail?.let { union(it) } })
                            wetTail = null
                        }
                        pendingCommits += stroke
                        pendingSince[stroke.id] = SystemClock.uptimeMillis()
                        listener?.onStrokeFinished(stroke)
                    }
                }
                abortInk()
            }
            GestureAction.ERASE -> {
                if (debugInput) perfEraseUpNanos = System.nanoTime()
                if (!canceled && erasedPending.isNotEmpty()) listener?.onStrokesErased(erasedPending.toSet())
                else erasedPending.clear()
                if (wiping) finishWipe(canceled)
                else if (!canceled && partialPieces.isNotEmpty()) {
                    // Keep drawing the pieces until the edited page comes back through setPage.
                    partialCommitted = true
                    listener?.onStrokesSplit(LinkedHashMap(partialPieces))
                } else partialPieces.clear()
                lastErase = null
                hoverX = -1f // pen lifted: hide the eraser ring until the next hover event
            }
            else -> Unit
        }
        perfReport(when { navigating -> "pan"; action == GestureAction.DRAW -> "ink"; action == GestureAction.ERASE -> "erase"; else -> "other" })
        // Text tool: a quick finger tap places or edits text too (fingers otherwise only scroll).
        if (textMode && action == GestureAction.NAVIGATE && !canceled && !tapMultiTouch && isQuickTap(e)) {
            val p = toPage(tapDownX, tapDownY)
            listener?.onTextTap(p[0], p[1], textAt(p[0], p[1]))
        } else if (navigating && !canceled) fling()
        velocity?.recycle(); velocity = null
        navigating = false
        sideButtonGesture = false
        action = GestureAction.IGNORE
        activePointerId = -1
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
    private fun elementAt(px: Float, py: Float): String? =
        textAt(px, py) ?: images.lastOrNull { it.bounds.contains(px, py) }?.id

    private fun selectedElementBounds(): app.folio.core.model.Rect? {
        var r: app.folio.core.model.Rect? = null
        for (t in texts) if (t.id in selection) r = r?.union(t.bounds) ?: t.bounds
        for (i in images) if (i.id in selection) r = r?.union(i.bounds) ?: i.bounds
        return r
    }

    /** Selected pictures and text drawn live while the selection is being moved or resized. */
    private fun drawMovingElements(canvas: Canvas) {
        for (img in images) if (img.id in selection) {
            val bmp = assets?.image(img.attachmentId, decode = false)
            if (bmp != null) renderer.drawImage(canvas, bmp, img) else renderer.drawMissingImage(canvas, img)
        }
        for (t in texts) if (t.id in selection) renderer.drawText(canvas, t)
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
        val shape = ShapeRecognizer.recognize(s.x, s.y) ?: return
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

    private fun erasable(s: Stroke) = !eraseHighlighterOnly || s.tool == ToolType.HIGHLIGHTER

    // Partial eraser: original stroke id → what remains of it so far in this gesture.
    private val partialPieces = LinkedHashMap<String, List<Stroke>>()
    private var partialCommitted = false

    /**
     * Fast path for the partial eraser: wipe pixels off the cached ink layer as the pen
     * moves (no per-sample geometry), then compute the exact stroke pieces once at pen-up.
     * Not used for "highlighter only" (a pixel wipe would also remove ink) or when no
     * up-to-date layer exists.
     */
    private var wiping = false
    /** A wipe was committed; tiles already show it, so edits wait for the page's echo. */
    private var wipeAwaitingEcho = false
    private val wipePath = ArrayList<Float>()

    private fun startWipeIfPossible() {
        wiping = false; wipePath.clear()
        if (!eraserPartial || eraseHighlighterOnly) return
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
        val job = WipeJob(base)
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

    private class WipeJob(val base: List<Stroke>) {
        @Volatile var pieces: Map<String, List<Stroke>> = emptyMap()
        @Volatile var prebuilt: StrokeIndex? = null
        var future: java.util.concurrent.Future<*>? = null
    }
    private var pendingWipe: WipeJob? = null
    /** Index built in the background for the page an edit is about to produce. */
    private var prebuiltIndex: StrokeIndex? = null

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

    /** UI thread: commits a finished wipe (one undo step). Safe to call twice; the second does nothing. */
    private fun completeWipe(job: WipeJob) {
        if (pendingWipe !== job) return
        pendingWipe = null
        try { job.future?.get() } catch (e: Exception) { Log.w(TAG, "wipe failed", e) }
        val pieces = job.pieces
        if (pieces.isEmpty()) {
            // Wiped only empty paper: the tiles still match the strokes.
            wipeAwaitingEcho = false
            tiles.keepWiped()
            requestRender()
            return
        }
        partialPieces.clear(); partialPieces.putAll(pieces)
        partialCommitted = true
        prebuiltIndex = job.prebuilt
        listener?.onStrokesSplit(LinkedHashMap(pieces))
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
        viewport.panBy(cx - lastPanX, cy - lastPanY)
        lastPanX = cx; lastPanY = cy
        syncMatrices()
        requestRender()
    }

    /** Momentum scroll after a finger swipe, stopping at the page edges. */
    private fun fling() {
        val vt = velocity ?: return
        vt.computeCurrentVelocity(1000)
        val vx = vt.xVelocity.toInt(); val vy = vt.yVelocity.toInt()
        if (kotlin.math.abs(vx) < 300 && kotlin.math.abs(vy) < 300) return
        scroller.fling(
            viewport.tx.toInt(), viewport.ty.toInt(), vx, vy,
            viewport.minTx().toInt(), 0, viewport.minTy().toInt(), 0,
        )
        requestRender()
    }

    override fun onHoverEvent(e: MotionEvent): Boolean {
        val kind = kindOf(e, 0)
        if (kind != PointerKind.STYLUS && kind != PointerKind.STYLUS_ERASER) return super.onHoverEvent(e)
        policy.onStylusActivity(SystemClock.uptimeMillis())
        policy.penOnly = penOnlyPreference
        if (debugInput && (e.actionMasked != MotionEvent.ACTION_HOVER_MOVE || e.buttonState != lastHoverButtons)) {
            Log.d(TAG, "hover action=${e.actionMasked} tool=${e.getToolType(0)} buttons=${e.buttonState}")
        }
        lastHoverButtons = e.buttonState
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

    // ---------------------------------------------------------- lasso select

    private fun handleRadiusPx() = 22f * resources.displayMetrics.density

    /** Selection bounds in page units, including any live transform. */
    private fun currentSelectionBox(): app.folio.core.model.Rect? {
        if (selection.isEmpty()) return null
        val sb = app.folio.core.ink.Lasso.bounds(strokes.filter { it.id in selection })
        val eb = selectedElementBounds()
        val b = when { sb == null -> eb; eb == null -> sb; else -> sb.union(eb) } ?: return null
        val t = selTransform
        return app.folio.core.model.Rect(t.mapX(b.left), t.mapY(b.top), t.mapX(b.right), t.mapY(b.bottom))
    }

    private fun beginSelectionGesture(vx: Float, vy: Float) {
        val p = toPage(vx, vy); val px = p[0]; val py = p[1]
        selDownX = px; selDownY = py
        val box = currentSelectionBox()
        val pad = handleRadiusPx() / scale
        selGesture = when {
            box != null && kotlin.math.hypot(px - box.right, py - box.bottom) <= pad * 1.5f -> SelGesture.RESIZE
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
                selTransform = SelectionTransform.resize(box, p[0], p[1])
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
                val ids = LinkedHashSet(app.folio.core.ink.Lasso.select(strokes, polygon))
                // Text boxes and pictures whose centre is inside the loop.
                for (t in texts) if (app.folio.core.ink.Lasso.contains(polygon, t.x + t.width / 2, t.y + t.height / 2)) ids += t.id
                for (i in images) if (app.folio.core.ink.Lasso.contains(polygon, i.x + i.width / 2, i.y + i.height / 2)) ids += i.id
                // A tap (no real loop) selects the text box or picture under the pen.
                val loop = app.folio.core.ink.Lasso.polygonBounds(polygon)
                if (ids.isEmpty() && maxOf(loop.width, loop.height) * scale < 24f * resources.displayMetrics.density) {
                    elementAt(lassoPoints.getOrElse(0) { 0f }, lassoPoints.getOrElse(1) { 0f })?.let { ids += it }
                }
                lassoPoints.clear()
                listener?.onLassoSelected(ids)
            }
            SelGesture.MOVE, SelGesture.RESIZE -> {
                val t = selTransform
                // Keep drawing the transform until the edited strokes come back through setPage.
                if (!t.isIdentity && selection.isNotEmpty()) listener?.onSelectionTransformed(selection, t)
                else selTransform = SelectionTransform()
            }
            SelGesture.NONE -> Unit
        }
    }

    private fun transformMatrix(t: SelectionTransform): Matrix = selMatrix.apply {
        setTranslate(-t.anchorX, -t.anchorY)
        postScale(t.scale, t.scale)
        postTranslate(t.anchorX + t.dx, t.anchorY + t.dy)
    }

    private fun drawSelectionOverlay(canvas: Canvas) {
        val px = 1f / scale
        selPaint.strokeWidth = 2f * px
        selPaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f * px, 10f * px), 0f)
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
        canvas.drawRect(box.left, box.top, box.right, box.bottom, selPaint)
        canvas.drawCircle(box.right, box.bottom, handleRadiusPx() * 0.45f * px, handlePaint)
    }

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
        val pl = viewport.tx; val pt = viewport.ty
        val pr = pl + pageW * scale; val pb = pt + pageH * scale
        // Desk only around the page: tiles are opaque, so each pixel is painted once.
        canvas.save()
        canvas.clipOutRect(pl, pt, pr, pb)
        canvas.drawPaint(deskPaint)
        canvas.restore()
        val moving = !selTransform.isIdentity && selection.isNotEmpty()
        val frozen = wiping || wipeAwaitingEcho
        tiles.setContent(pageKey, index, excludedFromLayer(moving), paper, pageW, pageH, pdfPage, pdfBackground, texts, images, assets)
        if (perfSkip?.contains("ink") != true) {
            tiles.draw(canvas, scale, viewport.tx, viewport.ty, width.toFloat(), height.toFloat(), viewport.fitPageScale, frozen)
        }
        canvas.save()
        canvas.concat(pageToView)
        canvas.clipRect(0f, 0f, pageW, pageH)
        // Wiped tiles already show what's left; otherwise draw the remaining pieces.
        if (!frozen) for (pieces in partialPieces.values) for (p in pieces) renderer.drawStroke(canvas, artFor(p))
        if (moving) {
            canvas.save()
            canvas.concat(transformMatrix(selTransform))
            val snap = selSnapshot
            if (snap != null && snap.selection === selection && snap.strokes === strokes) {
                canvas.drawBitmap(snap.bitmap, null, snap.dst, snapshotPaint)
            } else {
                for (s in strokes) if (s.id in selection) renderer.drawStroke(canvas, artFor(s))
                drawMovingElements(canvas)
            }
            canvas.restore()
        }
        drawSelectionOverlay(canvas)
        for (s in pendingCommits) renderer.drawStroke(canvas, artFor(s))
        if (!wetLive) builder?.let { drawLive(canvas, it) }
        canvas.restore()
        drawCursor(canvas)
    }

    // ------------------------------------------------------------ page tiles

    private val tiles = TileCache(onTilesChanged = { requestRender() })
    private var lastExcluded: Set<String> = emptySet()

    /**
     * Strokes deliberately hidden from the tiles (being erased or moved). The same set
     * instance is returned while its contents don't change, so tiles can compare cheaply.
     */
    private fun excludedFromLayer(moving: Boolean): Set<String> {
        val e = when {
            erasedPending.isEmpty() && partialPieces.isEmpty() && !moving && hiddenIds.isEmpty() -> emptySet()
            else -> buildSet {
                addAll(erasedPending); addAll(partialPieces.keys); addAll(hiddenIds)
                if (moving) addAll(selection)
            }
        }
        if (e == lastExcluded) return lastExcluded
        lastExcluded = e
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
        tiles.release()
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
        return LiveSamples(xs, ys, n, s.size, StrokeArt.live(tool, color, penWidth, xs, ys, ps, ts, os, n))
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
        private const val NO_PRESSURE_EFFORT = 0.6f
        /** Pen held this still (dp) for this long (ms) at the end of a stroke snaps it to a shape. */
        private const val HOLD_SLOP_DP = 4f
        private const val HOLD_TO_SNAP_MS = 450L
        /** A finished stroke stays on the wet layer this long after the page surface posts it (3 vsyncs at 60 Hz). */
        private const val WET_HOLD_NANOS = 50_000_000L
        // Samsung-specific MotionEvent actions sent while the S Pen side button is held.
        private const val SPEN_ACTION_DOWN = 211
        private const val SPEN_ACTION_UP = 212
        private const val SPEN_ACTION_MOVE = 213
        private const val SPEN_ACTION_CANCEL = 214
    }
}
