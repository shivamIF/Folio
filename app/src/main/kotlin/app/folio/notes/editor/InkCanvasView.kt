package app.folio.notes.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.widget.OverScroller
import app.folio.notes.input.PageViewport
import androidx.input.motionprediction.MotionEventPredictor
import app.folio.core.ink.PenProfile
import app.folio.core.ink.PressureCurve
import app.folio.core.ink.SelectionTransform
import app.folio.core.ink.StrokeBuilder
import app.folio.core.ink.StrokeGeometry
import app.folio.core.model.Argb
import app.folio.core.model.PaperTemplate
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
class InkCanvasView(context: Context) : View(context) {

    interface Listener {
        fun onStrokeFinished(stroke: Stroke)
        fun onStrokesErased(ids: Set<String>)
        /** A lasso loop finished; [ids] is the new selection (empty clears it). */
        fun onLassoSelected(ids: Set<String>)
        /** The selection was moved or resized; commit as one undoable edit. */
        fun onSelectionTransformed(ids: Set<String>, transform: SelectionTransform)
        /** Partial eraser finished: each original stroke id maps to the pieces that remain. */
        fun onStrokesSplit(pieces: Map<String, List<Stroke>>)
    }

    var listener: Listener? = null

    // ---- tool settings (pushed in from the editor UI) ----
    var tool: ToolType = ToolType.FOUNTAIN
    var color: Argb = 0xFF1B1B1F
    var penWidth: Float = 3f
    var stabilization: Float = 0.3f
    var pressureCurve: PressureCurve = PressureCurve()
    var eraserSelected: Boolean = false
        set(value) { field = value; invalidate() }
    /** Partial eraser (cuts strokes) instead of the whole-stroke eraser. Also used by the side button. */
    var eraserPartial: Boolean = false
    /** Eraser radius in dp on screen (the hover ring shows it). */
    var eraserSizeDp: Float = 14f
    /** Only highlighter strokes can be erased; writing is protected. */
    var eraseHighlighterOnly: Boolean = false
    /** Lasso tool active: the pen selects instead of writing. */
    var lassoMode: Boolean = false
        set(value) { field = value; invalidate() }
    /** Rendered PDF page shown under the ink, or null for plain paper. */
    var pdfBackground: android.graphics.Bitmap? = null
        set(value) { if (field !== value) { field = value; invalidate() } }
    /** Rectangle selection instead of a freeform loop. */
    var lassoRectangle: Boolean = false
    /** Currently selected stroke ids (owned by the view model). */
    var selection: Set<String> = emptySet()
        set(value) { if (field != value) { field = value; invalidate() } }

    val policy = InputPolicy(palmTouchMajorPx = 48f * resources.displayMetrics.density)

    /** User setting: once a stylus has been seen, fingers navigate instead of drawing. */
    var penOnlyPreference: Boolean = true
        set(value) { field = value; if (policy.stylusSeen) policy.penOnly = value }

    // ---- page content ----
    private var strokes: List<Stroke> = emptyList()
    /** Finished strokes not yet echoed back through [setPage]; drawn so pen-up never blinks. */
    private val pendingCommits = ArrayList<Stroke>()
    private val pendingSince = HashMap<String, Long>()
    /**
     * Outline paths keyed by stroke id, remembering which stroke object built them.
     * Edits like move/resize keep the id but create a new stroke object, so the
     * identity check makes those strokes redraw at their new geometry.
     */
    private val pathCache = HashMap<String, Pair<Stroke, Path>>()

    private fun pathFor(s: Stroke): Path {
        val cached = pathCache[s.id]
        if (cached != null && cached.first === s) return cached.second
        return PageRenderer.pathOf(StrokeGeometry.outline(s)).also { pathCache[s.id] = s to it }
    }
    private var paper = PaperTemplate.RULED
    private var pageW = 1000f
    private var pageH = 1414f

    fun setPage(strokes: List<Stroke>, paper: PaperTemplate, width: Float, height: Float) {
        this.strokes = strokes
        this.paper = paper
        pageW = width; pageH = height
        val live = strokes.mapTo(HashSet()) { it.id }
        val now = SystemClock.uptimeMillis()
        // Normally the echo arrives on the next frame; the timeout covers edits the page never accepts.
        pendingCommits.removeAll { it.id in live || now - (pendingSince[it.id] ?: 0) > 1000 }
        pendingSince.keys.retainAll(pendingCommits.mapTo(HashSet()) { it.id })
        pathCache.keys.retainAll(live + pendingCommits.map { it.id })
        erasedPending.retainAll(live)
        if (partialCommitted) {
            partialPieces.clear(); partialCommitted = false
            inkLayer.adoptAfterWipe(strokes)
            requestLayer() // sharp rebuild in the background; the wiped layer is shown meanwhile
        }
        if (selGesture == SelGesture.NONE) selTransform = SelectionTransform()
        viewport.setPage(width, height)
        syncMatrices()
        invalidate()
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
            invalidate()
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
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            viewport.panBy(scroller.currX - viewport.tx, scroller.currY - viewport.ty)
            syncMatrices()
            postInvalidateOnAnimation()
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
    private var perfLastInputNanos = 0L

    private fun perfReport(kind: String) {
        if (!debugInput || perfDrawMs.isEmpty()) return
        fun pct(v: List<Float>, q: Float) = v.sorted().let { if (it.isEmpty()) -1f else it[((it.size - 1) * q).toInt()] }
        Log.d(
            "FolioPerf",
            "%s frames=%d strokes=%d latency_p50=%.1f latency_p95=%.1f draw_p50=%.1f draw_p95=%.1f draw_max=%.1f".format(
                kind, perfDrawMs.size, strokes.size, pct(perfLatencyMs, .5f), pct(perfLatencyMs, .95f),
                pct(perfDrawMs, .5f), pct(perfDrawMs, .95f), perfDrawMs.maxOrNull() ?: -1f,
            ),
        )
        perfLatencyMs.clear(); perfDrawMs.clear()
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
        if (masked == MotionEvent.ACTION_DOWN) scroller.forceFinished(true)
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
                GestureAction.DRAW -> if (selGesture != SelGesture.NONE) moveSelectionGesture(e) else {
                    predictor.record(e); appendSamples(e); updatePrediction(); invalidate()
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
        val kind = kindOf(e, index)
        val barrel = sideButtonGesture || (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        action = policy.classifyDown(kind, e.getTouchMajor(index), barrel, eraserSelected, e.pointerCount, e.eventTime)
        if (policy.stylusSeen) policy.penOnly = penOnlyPreference
        activePointerId = e.getPointerId(index)
        when (action) {
            GestureAction.DRAW if lassoMode -> {
                requestUnbufferedDispatch(e)
                beginSelectionGesture(e.getX(index), e.getY(index))
            }
            GestureAction.DRAW -> {
                // Deliver every sample as soon as it arrives instead of once per frame.
                requestUnbufferedDispatch(e)
                builder = StrokeBuilder(tool, color, penWidth, stabilization, startedAt = System.currentTimeMillis())
                strokeIsFromPen = kind == PointerKind.STYLUS || kind == PointerKind.STYLUS_ERASER
                predictor.record(e)
                appendSamples(e)
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
        invalidate()
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
    }

    private fun finishGesture(e: MotionEvent, index: Int, canceled: Boolean) {
        when (action) {
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
                        val stroke = b.build()
                        pathFor(stroke)
                        pendingCommits += stroke
                        pendingSince[stroke.id] = SystemClock.uptimeMillis()
                        listener?.onStrokeFinished(stroke)
                    }
                }
                abortInk()
            }
            GestureAction.ERASE -> {
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
        if (navigating && !canceled) fling()
        velocity?.recycle(); velocity = null
        navigating = false
        sideButtonGesture = false
        action = GestureAction.IGNORE
        activePointerId = -1
        invalidate()
    }

    private fun abortInk() {
        builder = null
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
    private var drewWipedLayer = false
    private var wipeLayer: InkLayerCache.Layer? = null
    private val wipePath = ArrayList<Float>()
    private val wipePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    private fun startWipeIfPossible() {
        wiping = false; wipeLayer = null; wipePath.clear()
        if (!eraserPartial || eraseHighlighterOnly) return
        val l = inkLayer.layer ?: return
        if (!inkLayer.syncContent(strokes, emptySet(), renderer, ::pathFor)) return
        wiping = true; wipeLayer = l
    }

    private fun wipeTo(x0: Float, y0: Float, x1: Float, y1: Float) {
        val l = wipeLayer ?: return
        if (wipePath.isEmpty()) { wipePath += x0; wipePath += y0 }
        wipePath += x1; wipePath += y1
        wipePaint.strokeWidth = eraserRadiusPage() * 2f
        l.canvas.drawLine(x0, y0, x1, y1, wipePaint)
        l.dirty = true // its pixels no longer match its stroke list
    }

    /** Pen-up after a wipe: turn the wiped path into exact stroke pieces (one undo step). */
    private fun finishWipe(canceled: Boolean) {
        val path = wipePath.toFloatArray()
        wiping = false; wipeLayer = null; wipePath.clear()
        if (canceled || path.size < 2) { requestLayer(); return }
        if (path.size == 2) partialEraseAlong(path[0], path[1], path[0], path[1])
        var i = 2
        while (i < path.size) { partialEraseAlong(path[i - 2], path[i - 1], path[i], path[i + 1]); i += 2 }
        if (partialPieces.isNotEmpty()) {
            partialCommitted = true
            inkLayer.layer?.awaitingWipeCommit = true
            listener?.onStrokesSplit(LinkedHashMap(partialPieces))
        } else {
            // Wiped only empty paper: the pixels still match the strokes.
            inkLayer.layer?.let { if (it.strokes === strokes) it.dirty = false }
        }
    }

    private fun partialEraseAlong(x0: Float, y0: Float, x1: Float, y1: Float) {
        val r = eraserRadiusPage()
        for (s in strokes) {
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
        invalidate()
    }

    private fun eraseTo(x: Float, y: Float) {
        val from = lastErase ?: floatArrayOf(x, y)
        if (wiping) wipeTo(from[0], from[1], x, y)
        else if (eraserPartial) partialEraseAlong(from[0], from[1], x, y)
        else {
            val candidates = strokes.filter { it.id !in erasedPending && erasable(it) }
            erasedPending += StrokeGeometry.hitAlong(candidates, from[0], from[1], x, y, eraserRadiusPage())
        }
        lastErase = floatArrayOf(x, y)
    }

    private fun startPan(e: MotionEvent, skipIndex: Int = -1) {
        navigating = true
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
        invalidate()
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
        postInvalidateOnAnimation()
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
        invalidate()
        return true
    }

    // ---------------------------------------------------------- lasso select

    private fun handleRadiusPx() = 22f * resources.displayMetrics.density

    /** Selection bounds in page units, including any live transform. */
    private fun currentSelectionBox(): app.folio.core.model.Rect? {
        if (selection.isEmpty()) return null
        val b = app.folio.core.ink.Lasso.bounds(strokes.filter { it.id in selection }) ?: return null
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
        invalidate()
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
        invalidate()
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
                val ids = app.folio.core.ink.Lasso.select(strokes, polygon)
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

    override fun onDraw(canvas: Canvas) {
        val perfStart = if (debugInput) System.nanoTime() else 0L
        drawPageContent(canvas)
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
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), deskPaint)
        canvas.save()
        canvas.concat(pageToView)
        renderer.drawPaper(canvas, paper, pageW, pageH, hasPdf = pdfBackground != null)
        pdfBackground?.let { renderer.drawPdfBackground(canvas, it, pageW, pageH) }
        canvas.clipRect(0f, 0f, pageW, pageH)
        val moving = !selTransform.isIdentity && selection.isNotEmpty()
        drawCommittedStrokes(canvas, moving)
        // A wiped layer already shows the erased result; otherwise draw the remaining pieces.
        if (!drewWipedLayer) for (pieces in partialPieces.values) for (p in pieces) drawStroke(canvas, p.color, p.tool, pathFor(p))
        if (moving) {
            canvas.save()
            canvas.concat(transformMatrix(selTransform))
            for (s in strokes) if (s.id in selection) drawStroke(canvas, s.color, s.tool, pathFor(s))
            canvas.restore()
        }
        drawSelectionOverlay(canvas)
        for (s in pendingCommits) drawStroke(canvas, s.color, s.tool, pathFor(s))
        builder?.let { drawLive(canvas, it) }
        canvas.restore()
        drawCursor(canvas)
    }

    private fun drawStroke(canvas: Canvas, c: Argb, t: ToolType, path: Path) = renderer.drawStroke(canvas, c, t, path)

    // ------------------------------------------------------------ ink layer

    private val inkLayer = InkLayerCache(
        // Never swap layers in the middle of a wipe; pen-up requests a fresh build anyway.
        isCurrent = { spec -> !wiping && spec.strokes === strokes },
        onLayerReady = { invalidate() },
    )
    private val layerPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val layerDst = android.graphics.RectF()
    private val visibleTmp = FloatArray(4)
    private val rebuildAfterZoom = Runnable { requestLayer() }

    /** Strokes deliberately hidden from the cached layer (being erased or moved). */
    private fun excludedFromLayer(moving: Boolean): Set<String> = when {
        erasedPending.isEmpty() && partialPieces.isEmpty() && !moving -> emptySet()
        else -> buildSet {
            addAll(erasedPending); addAll(partialPieces.keys)
            if (moving) addAll(selection)
        }
    }

    /** Visible part of the page, in page units: [l, t, r, b]. */
    private fun visiblePageRect(): FloatArray {
        visibleTmp[0] = 0f; visibleTmp[1] = 0f; visibleTmp[2] = width.toFloat(); visibleTmp[3] = height.toFloat()
        viewToPage.mapPoints(visibleTmp)
        visibleTmp[0] = visibleTmp[0].coerceIn(0f, pageW); visibleTmp[2] = visibleTmp[2].coerceIn(0f, pageW)
        visibleTmp[1] = visibleTmp[1].coerceIn(0f, pageH); visibleTmp[3] = visibleTmp[3].coerceIn(0f, pageH)
        return visibleTmp
    }

    /** What the layer should be right now: whole page if it fits the pixel budget, else the viewport plus a margin. */
    private fun desiredLayerSpec(excluded: Set<String>): InkLayerCache.Spec {
        val sc = scale
        val wholePx = (pageW * sc).toLong() * (pageH * sc).toLong()
        if (wholePx <= InkLayerCache.MAX_LAYER_PIXELS) {
            // Round up: a layer even a fraction of a pixel short of the page edge would be rebuilt forever.
            return InkLayerCache.Spec(sc, 0f, 0f, kotlin.math.ceil(pageW * sc).toInt(), kotlin.math.ceil(pageH * sc).toInt(), strokes, excluded)
        }
        val v = visiblePageRect()
        val mw = (v[2] - v[0]) * 0.5f; val mh = (v[3] - v[1]) * 0.5f
        val l = (v[0] - mw).coerceAtLeast(0f); val t = (v[1] - mh).coerceAtLeast(0f)
        val r = (v[2] + mw).coerceAtMost(pageW); val b = (v[3] + mh).coerceAtMost(pageH)
        return InkLayerCache.Spec(sc, l, t, kotlin.math.ceil((r - l) * sc).toInt(), kotlin.math.ceil((b - t) * sc).toInt(), strokes, excluded)
    }

    private fun requestLayer() {
        if (width == 0 || !viewport.ready) return
        val moving = !selTransform.isIdentity && selection.isNotEmpty()
        inkLayer.request(desiredLayerSpec(excludedFromLayer(moving)))
    }

    private fun drawCommittedStrokes(canvas: Canvas, moving: Boolean) {
        val excluded = excludedFromLayer(moving)
        val layer = inkLayer.layer
        drewWipedLayer = false
        val wiped = wipeLayer
        if (wiped != null) {
            layerDst.set(wiped.left, wiped.top, wiped.right, wiped.bottom)
            canvas.drawBitmap(wiped.bitmap, null, layerDst, layerPaint)
            drewWipedLayer = true
            return
        }
        if (layer != null && inkLayer.syncContent(strokes, excluded, renderer, ::pathFor)) {
            layerDst.set(layer.left, layer.top, layer.right, layer.bottom)
            canvas.drawBitmap(layer.bitmap, null, layerDst, layerPaint)
            val v = visiblePageRect()
            val coversView = layer.covers(v[0], v[1], v[2], v[3])
            if (!coversView) requestLayer()
            else if (kotlin.math.abs(layer.scale - scale) > scale * 0.01f) {
                // Zooming: show the scaled layer now, re-render sharp once the pinch pauses.
                removeCallbacks(rebuildAfterZoom)
                postDelayed(rebuildAfterZoom, 150)
            }
            return
        }
        requestLayer()
        // Dense page: never compute thousands of outlines on the UI thread. Show the previous
        // layer (or just the paper on first open) until the background rebuild lands.
        if (strokes.size > DIRECT_DRAW_LIMIT) {
            if (layer != null) {
                layerDst.set(layer.left, layer.top, layer.right, layer.bottom)
                canvas.drawBitmap(layer.bitmap, null, layerDst, layerPaint)
                drewWipedLayer = layer.dirty
            }
            return
        }
        for (s in strokes) {
            if (s.id in excluded) continue
            drawStroke(canvas, s.color, s.tool, pathFor(s))
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(rebuildAfterZoom)
        inkLayer.release()
        if (attached?.get() === this) attached = null
        super.onDetachedFromWindow()
    }

    private fun drawLive(canvas: Canvas, b: StrokeBuilder) {
        val s = b.build()
        val pred = predicted
        val extra = if (pred != null && action == GestureAction.DRAW) pred.historySize + 1 else 0
        val n = s.size + extra
        val xs = s.x.copyOf(n); val ys = s.y.copyOf(n); val ps = s.pressure.copyOf(n); val ts = s.tilt.copyOf(n)
        if (pred != null && extra > 0) {
            val lastP = if (s.size > 0) s.pressure[s.size - 1] else 0.5f
            for (k in 0 until extra) {
                val vx = if (k < pred.historySize) pred.getHistoricalX(0, k) else pred.x
                val vy = if (k < pred.historySize) pred.getHistoricalY(0, k) else pred.y
                val p = toPage(vx, vy)
                xs[s.size + k] = p[0]; ys[s.size + k] = p[1]; ps[s.size + k] = lastP; ts[s.size + k] = 0f
            }
        }
        val outline = StrokeGeometry.outline(xs, ys, ps, ts, n, penWidth, PenProfile.of(tool))
        PageRenderer.fillPath(livePath, outline)
        drawStroke(canvas, color, tool, livePath)
    }

    private fun drawCursor(canvas: Canvas) {
        if (hoverX < 0) return
        val r = if (hoverErase) eraserRadiusPage() * scale else max(2f, penWidth * scale / 2f)
        cursorPaint.color = if (hoverErase) 0xFF6750A4.toInt() else (color.toInt() and 0x00FFFFFF) or 0x99000000.toInt()
        canvas.drawCircle(hoverX, hoverY, r, cursorPaint)
    }



    companion object {
        /** The canvas currently on screen, for window-level routing of side-button gestures. */
        var attached: java.lang.ref.WeakReference<InkCanvasView>? = null
            private set

        fun isSideButtonGesture(ev: MotionEvent): Boolean =
            ev.actionMasked in SPEN_ACTION_DOWN..SPEN_ACTION_CANCEL ||
                (ev.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS && (ev.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0)

        private const val TAG = "FolioInput"
        private const val NO_PRESSURE_EFFORT = 0.6f
        /** Above this many strokes, never vector-draw the whole page on the UI thread. */
        private const val DIRECT_DRAW_LIMIT = 800
        // Samsung-specific MotionEvent actions sent while the S Pen side button is held.
        private const val SPEN_ACTION_DOWN = 211
        private const val SPEN_ACTION_UP = 212
        private const val SPEN_ACTION_MOVE = 213
        private const val SPEN_ACTION_CANCEL = 214
    }
}
