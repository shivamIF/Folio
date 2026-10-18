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

    val policy = InputPolicy(palmTouchMajorPx = 48f * resources.displayMetrics.density)

    /** User setting: once a stylus has been seen, fingers navigate instead of drawing. */
    var penOnlyPreference: Boolean = true
        set(value) { field = value; if (policy.stylusSeen) policy.penOnly = value }

    // ---- page content ----
    private var strokes: List<Stroke> = emptyList()
    /** Finished strokes not yet echoed back through [setPage]; drawn so pen-up never blinks. */
    private val pendingCommits = ArrayList<Stroke>()
    private val pendingSince = HashMap<String, Long>()
    private val pathCache = HashMap<String, Path>()
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
    private val predictor = MotionEventPredictor.newInstance(this)
    private var predicted: MotionEvent? = null
    private var lastErase: FloatArray? = null
    private val erasedPending = LinkedHashSet<String>()
    private var navigating = false
    private var lastPanX = 0f
    private var lastPanY = 0f

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

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pagePaint = Paint().apply { color = Color.WHITE }
    private val deskPaint = Paint().apply { color = 0xFFE8E6E1.toInt() }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC9D6E3.toInt(); strokeWidth = 1.2f }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB0B8C4.toInt() }
    private val marginPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2B8B5.toInt(); strokeWidth = 1.5f }
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

    override fun onDetachedFromWindow() {
        if (attached?.get() === this) attached = null
        super.onDetachedFromWindow()
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
                GestureAction.DRAW -> { predictor.record(e); appendSamples(e); updatePrediction(); invalidate() }
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
            GestureAction.DRAW -> {
                // Deliver every sample as soon as it arrives instead of once per frame.
                requestUnbufferedDispatch(e)
                builder = StrokeBuilder(tool, color, penWidth, stabilization, startedAt = System.currentTimeMillis())
                predictor.record(e)
                appendSamples(e)
            }
            GestureAction.ERASE -> {
                requestUnbufferedDispatch(e)
                erasedPending.clear()
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
            GestureAction.DRAW -> {
                val b = builder
                if (b != null && !canceled) {
                    val idx = e.findPointerIndex(activePointerId).takeIf { it >= 0 } ?: index
                    appendSamples(e)
                    val p = toPage(e.getX(idx), e.getY(idx))
                    b.finishAt(p[0], p[1], effort(e, idx, e.getPressure(idx)), e.getAxisValue(MotionEvent.AXIS_TILT, idx), e.getAxisValue(MotionEvent.AXIS_ORIENTATION, idx), e.eventTime)
                    if (b.size > 0) {
                        val stroke = b.build()
                        pathCache[stroke.id] = pathOf(StrokeGeometry.outline(stroke))
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
                lastErase = null
                hoverX = -1f // pen lifted: hide the eraser ring until the next hover event
            }
            else -> Unit
        }
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

    private fun eraserRadiusPage() = 14f * resources.displayMetrics.density / scale

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
        val candidates = strokes.filter { it.id !in erasedPending }
        erasedPending += StrokeGeometry.hitAlong(candidates, from[0], from[1], x, y, eraserRadiusPage())
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

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        fitIfNeeded()
        // Never paint outside this view (e.g. over the toolbar when zoomed or scrolled).
        canvas.clipRect(0, 0, width, height)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), deskPaint)
        canvas.save()
        canvas.concat(pageToView)
        drawPaper(canvas)
        canvas.clipRect(0f, 0f, pageW, pageH)
        for (s in strokes) {
            if (s.id in erasedPending) continue
            drawStroke(canvas, s.color, s.tool, pathCache.getOrPut(s.id) { pathOf(StrokeGeometry.outline(s)) })
        }
        for (s in pendingCommits) drawStroke(canvas, s.color, s.tool, pathCache.getValue(s.id))
        builder?.let { drawLive(canvas, it) }
        canvas.restore()
        drawCursor(canvas)
    }

    private fun drawStroke(canvas: Canvas, c: Argb, t: ToolType, path: Path) {
        val profile = PenProfile.of(t)
        val argb = c.toInt()
        strokePaint.color = argb
        strokePaint.alpha = (Color.alpha(argb) * profile.opacity).toInt()
        canvas.drawPath(path, strokePaint)
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
        fillPath(livePath, outline)
        drawStroke(canvas, color, tool, livePath)
    }

    private fun drawCursor(canvas: Canvas) {
        if (hoverX < 0) return
        val r = if (hoverErase) eraserRadiusPage() * scale else max(2f, penWidth * scale / 2f)
        cursorPaint.color = if (hoverErase) 0xFF6750A4.toInt() else (color.toInt() and 0x00FFFFFF) or 0x99000000.toInt()
        canvas.drawCircle(hoverX, hoverY, r, cursorPaint)
    }

    private fun drawPaper(canvas: Canvas) {
        canvas.drawRect(0f, 0f, pageW, pageH, pagePaint)
        val step = 40f
        when (paper) {
            PaperTemplate.BLANK -> Unit
            PaperTemplate.RULED -> {
                var y = 120f
                while (y < pageH - 40f) { canvas.drawLine(40f, y, pageW - 40f, y, linePaint); y += step }
                canvas.drawLine(110f, 0f, 110f, pageH, marginPaint)
            }
            PaperTemplate.GRID -> {
                var x = step; while (x < pageW) { canvas.drawLine(x, 0f, x, pageH, linePaint); x += step }
                var y = step; while (y < pageH) { canvas.drawLine(0f, y, pageW, y, linePaint); y += step }
            }
            PaperTemplate.DOTTED -> {
                var y = step
                while (y < pageH) {
                    var x = step
                    while (x < pageW) { canvas.drawCircle(x, y, 1.6f, dotPaint); x += step }
                    y += step
                }
            }
        }
    }

    private fun pathOf(outline: FloatArray): Path = Path().also { fillPath(it, outline) }

    private fun fillPath(path: Path, outline: FloatArray) {
        path.rewind()
        if (outline.size < 6) return
        path.moveTo(outline[0], outline[1])
        var i = 2
        while (i < outline.size) { path.lineTo(outline[i], outline[i + 1]); i += 2 }
        path.close()
        path.fillType = Path.FillType.WINDING
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
        // Samsung-specific MotionEvent actions sent while the S Pen side button is held.
        private const val SPEN_ACTION_DOWN = 211
        private const val SPEN_ACTION_UP = 212
        private const val SPEN_ACTION_MOVE = 213
        private const val SPEN_ACTION_CANCEL = 214
    }
}
