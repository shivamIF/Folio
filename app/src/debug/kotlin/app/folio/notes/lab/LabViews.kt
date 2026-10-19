package app.folio.notes.lab

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import app.folio.core.ink.PressureCurve
import app.folio.core.model.Rect
import app.folio.core.model.Stroke
import app.folio.notes.editor.PageRenderer

/** Page width in units, as in the editor: the pad maps it across its own width. */
private const val PAGE_WIDTH = 1000f

/** Printed text under the strokes, so highlighters can be judged on what they cover. */
private val underlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF202124.toInt(); textSize = 22f }
private val rulePaint = Paint().apply { color = 0xFFDDE3EA.toInt(); strokeWidth = 1f }

private fun drawUnderlay(canvas: Canvas, preset: LabPreset, right: Float, bottom: Float) {
    var y = 40f
    while (y < bottom) { canvas.drawLine(0f, y, right, y, rulePaint); y += 40f }
    if (preset.ink == InkFamily.HIGHLIGHTER) {
        canvas.drawText("Highlight this printed line to check the text stays crisp.", 20f, 75f, underlayPaint)
        canvas.drawText("Overlaps should not turn grey or darken the letters.", 20f, 172f, underlayPaint)
    }
}

/**
 * Records stylus (or finger, for emulators) strokes in page units and draws them live
 * with v1, like the editor. Side-button gestures are ignored.
 */
@SuppressLint("ClickableViewAccessibility", "ViewConstructor")
class CapturePad(context: Context, private val onStroke: (RawStroke) -> Unit) : View(context) {
    var preset: LabPreset = LAB_PRESETS.first()
    var widthScale = 1f
    var strokes: List<Stroke> = emptyList()
        set(value) { field = value; invalidate() }

    private val curve = PressureCurve()
    private val renderer = PageRenderer()
    private val samples = ArrayList<RawSample>()
    private val toView = Matrix()
    private val bg = Paint().apply { color = Color.WHITE }

    private val scale get() = width / PAGE_WIDTH

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestUnbufferedDispatch(e)
                samples.clear(); add(e)
            }
            MotionEvent.ACTION_MOVE -> if (samples.isNotEmpty()) add(e)
            MotionEvent.ACTION_UP -> if (samples.isNotEmpty()) {
                add(e)
                onStroke(RawStroke(samples.toList()))
                samples.clear()
            }
            // A second finger or a cancel drops the stroke in progress.
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> samples.clear()
            else -> return false
        }
        invalidate()
        return true
    }

    private fun add(e: MotionEvent) {
        val s = scale
        for (h in 0 until e.historySize) {
            samples += RawSample(
                e.getHistoricalX(0, h) / s, e.getHistoricalY(0, h) / s, effort(e, e.getHistoricalPressure(0, h)),
                e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, 0, h),
                e.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, 0, h), e.getHistoricalEventTime(h),
            )
        }
        samples += RawSample(
            e.x / s, e.y / s, effort(e, e.pressure),
            e.getAxisValue(MotionEvent.AXIS_TILT), e.getAxisValue(MotionEvent.AXIS_ORIENTATION), e.eventTime,
        )
    }

    /** Same rule as the editor: only a stylus has real pressure. */
    private fun effort(e: MotionEvent, raw: Float) = when (e.getToolType(0)) {
        MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> curve.map(raw)
        else -> 0.6f
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        val s = scale
        toView.setScale(s, s)
        canvas.save()
        canvas.concat(toView)
        drawUnderlay(canvas, preset, PAGE_WIDTH, height / s)
        for (stroke in strokes) renderer.drawStroke(canvas, stroke)
        if (samples.isNotEmpty()) renderer.drawStroke(canvas, RawStroke(samples).toStroke(preset.tool, preset.color, preset.width * widthScale))
        canvas.restore()
    }
}

/** Pan and zoom shared by the comparison panels, so all three always show the same spot. */
class LabViewport {
    var scale = 1f
    var tx = 0f
    var ty = 0f
    private val views = ArrayList<View>()

    fun attach(v: View) { if (v !in views) views += v }
    fun changed() = views.forEach { it.invalidate() }

    /** Fits [bounds] (page units) into a [w]×[h] view with a margin. */
    fun fit(bounds: Rect, w: Int, h: Int) {
        if (w == 0 || h == 0) return
        val b = if (bounds.width < 1f || bounds.height < 1f) bounds.inflate(50f) else bounds.inflate(10f)
        scale = minOf(w / b.width, h / b.height)
        tx = (w - b.width * scale) / 2f - b.left * scale
        ty = (h - b.height * scale) / 2f - b.top * scale
        changed()
    }

    fun zoomBy(f: Float, fx: Float, fy: Float) {
        val next = (scale * f).coerceIn(0.05f, 200f)
        val k = next / scale
        tx = fx - (fx - tx) * k; ty = fy - (fy - ty) * k
        scale = next
        changed()
    }

    fun panBy(dx: Float, dy: Float) { tx += dx; ty += dy; changed() }
}

/**
 * One comparison column: the shared strokes drawn by [engine], with draw time.
 * Drag pans, pinch zooms (all panels together), double tap fits the strokes.
 */
@SuppressLint("ClickableViewAccessibility", "ViewConstructor")
class ComparePanel(context: Context, private val engine: LabEngine, private val viewport: LabViewport) : View(context) {
    var preset: LabPreset = LAB_PRESETS.first()
        set(value) { field = value; invalidate() }
    var strokes: List<Stroke> = emptyList()
        set(value) { field = value; invalidate() }

    private val toView = Matrix()
    private val bg = Paint().apply { color = Color.WHITE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF44474E.toInt(); textSize = 30f }
    private val note = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8A8D93.toInt(); textSize = 28f; textAlign = Paint.Align.CENTER }
    private val divider = Paint().apply { color = 0xFFC4C7CF.toInt(); strokeWidth = 2f }
    private var lastMs = 0.0

    init { viewport.attach(this) }

    fun fit() {
        val b = strokes.map { it.bounds() }.reduceOrNull { a, c -> a.union(c) } ?: Rect(0f, 0f, PAGE_WIDTH, 200f)
        viewport.fit(b, width, height)
    }

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { viewport.zoomBy(d.scaleFactor, d.focusX, d.focusY); return true }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!scaler.isInProgress) viewport.panBy(-dx, -dy)
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean { fit(); return true }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        gestures.onTouchEvent(e)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        // Compose doesn't clip AndroidViews: keep zoomed strokes inside this column.
        canvas.clipRect(0, 0, width, height)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        val reason = engine.unavailableReason
        if (reason != null) {
            reason.lines().forEachIndexed { i, line -> canvas.drawText(line, width / 2f, height / 2f + i * 38f, note) }
        } else {
            toView.setScale(viewport.scale, viewport.scale)
            toView.postTranslate(viewport.tx, viewport.ty)
            canvas.save()
            canvas.concat(toView)
            drawUnderlay(canvas, preset, PAGE_WIDTH, 2000f)
            val t0 = SystemClock.elapsedRealtimeNanos()
            engine.draw(canvas, strokes, preset, toView)
            // Includes any geometry built this frame; [LabEngine.buildMs] separates it out.
            lastMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
            canvas.restore()
        }
        val extra = (engine as? InkEngine)?.rejectedSamples?.takeIf { it > 0 }?.let { "  ·  $it samples rejected" } ?: ""
        val timing = if (reason == null) "  ·  build %.1f ms  ·  draw %.1f ms  ·  %.1f×".format(engine.buildMs, lastMs, viewport.scale) else ""
        canvas.drawText(engine.name + timing + extra, 16f, 38f, label)
        canvas.drawLine(width - 1f, 0f, width - 1f, height.toFloat(), divider)
    }
}
