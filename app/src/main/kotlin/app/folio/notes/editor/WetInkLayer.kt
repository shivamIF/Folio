package app.folio.notes.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RectF
import android.util.Log
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer

/**
 * What the wet-ink layer shows: strokes in page units, the page→view transform, and the
 * view-pixel area to repaint ([clip] null = clear and repaint everything).
 */
internal class WetFrame(
    val pageToView: FloatArray, val arts: List<StrokeArt>, val clip: RectF?, val inputNanos: Long,
    /** The page's size: ink is clipped to it, as on the page surface (a stroke stays on its page). */
    val pageW: Float, val pageH: Float,
    /** The page is shown dark (ink lightened the same way the page surface does). */
    val dark: Boolean = false,
)

/**
 * Low-latency "wet ink": the stroke being written is drawn straight into a front buffer
 * (androidx.graphics CanvasFrontBufferedRenderer) on a transparent surface above the page,
 * instead of waiting its turn in the page surface's buffer queue.
 *
 * Why: with the page surface a pen sample takes ~5 ms to draw but then waits ~25 ms
 * (1.5 vsyncs) in the buffer queue before it's shown (Galaxy Tab S6 Lite, scripts/latency_bench.py).
 *
 * Used only where the hardware has real front buffers ([supported]). The Tab S6 Lite
 * (Android 13, Mali-G72) doesn't: the library then falls back to a per-update SurfaceFlinger
 * transaction that is GPU-composited, measured at ~35–40 ms event → screen, no better than the
 * page path, so there the page surface keeps drawing live ink. Unverified on hardware with
 * front-buffer support; `InkBenchActivity --ez wet true` forces it on for measuring.
 *
 * Only the stroke in progress (and finished strokes until the page surface shows them) lives
 * here; the page stays the source of truth. Each update repaints only the area around the
 * pen tip, so the single buffer is never visibly half-drawn.
 */
@SuppressLint("ViewConstructor")
internal class WetInkLayer(context: Context) : SurfaceView(context) {
    private var renderer: CanvasFrontBufferedRenderer<WetFrame>? = null
    private val debug = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Debug: event-to-front-buffer-drawn times (ms), drained by InkCanvasView's perf probe. */
    val drawnMs: MutableList<Float> = java.util.Collections.synchronizedList(ArrayList())

    init {
        setZOrderMediaOverlay(true) // above the page surface, still under the app window
        holder.setFormat(PixelFormat.TRANSLUCENT)
        isClickable = false
        isFocusable = false
    }

    val ready: Boolean get() = renderer?.isValid() == true

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        renderer = try {
            CanvasFrontBufferedRenderer(this, Callback())
        } catch (e: Exception) {
            // Unsupported device or OS: the page surface draws live ink as before.
            Log.w("FolioInput", "front-buffered ink unavailable", e); null
        }
    }

    override fun onDetachedFromWindow() {
        renderer?.release(true)
        renderer = null
        super.onDetachedFromWindow()
    }

    fun show(frame: WetFrame) { renderer?.renderFrontBufferedLayer(frame) }

    /** Clears the front buffer (nothing wet is left to show). */
    fun clear() { renderer?.cancel() }

    // Never takes touches: the page view underneath handles all input.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent?) = false

    private inner class Callback : CanvasFrontBufferedRenderer.Callback<WetFrame> {
        // Used only on the renderer's own thread.
        private val pageRenderer = PageRenderer()
        private val m = Matrix()

        override fun onDrawFrontBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, param: WetFrame) {
            canvas.save()
            param.clip?.let { canvas.clipRect(it) }
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            m.setValues(param.pageToView)
            canvas.concat(m)
            canvas.clipRect(0f, 0f, param.pageW, param.pageH)
            pageRenderer.dark = param.dark
            for (art in param.arts) pageRenderer.drawStroke(canvas, art)
            canvas.restore()
            lastInputNanos = param.inputNanos
        }

        @Volatile private var lastInputNanos = 0L

        // The front buffer's GPU work has been submitted: from here it shows as the display scans.
        override fun onFrontBufferedLayerRenderComplete(
            frontBufferedLayerSurfaceControl: androidx.graphics.surface.SurfaceControlCompat,
            transaction: androidx.graphics.surface.SurfaceControlCompat.Transaction,
        ) {
            val t = lastInputNanos
            if (debug && t > 0) drawnMs += (System.nanoTime() - t) / 1e6f
        }

        // Nothing is ever committed to the multi-buffered layer: finished ink goes to the page.
        override fun onDrawMultiBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, params: Collection<WetFrame>) {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        }
    }

    companion object {
        /** True front-buffer rendering: the display scans out the buffer being drawn (Android 13+). */
        val supported: Boolean by lazy {
            android.os.Build.VERSION.SDK_INT >= 33 && runCatching {
                android.hardware.HardwareBuffer.isSupported(
                    256, 256, android.hardware.HardwareBuffer.RGBA_8888, 1,
                    android.hardware.HardwareBuffer.USAGE_FRONT_BUFFER or android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or
                        android.hardware.HardwareBuffer.USAGE_COMPOSER_OVERLAY,
                )
            }.getOrDefault(false).also { Log.i("FolioInput", "front-buffer support: $it") }
        }
    }
}

/**
 * The editor's page area: the page surface with the wet-ink layer above it. Touches reach
 * [ink] (the layer never takes them).
 */
@SuppressLint("ViewConstructor")
class InkCanvasHost(context: Context, val ink: InkCanvasView, wetInk: Boolean = WetInkLayer.supported) : FrameLayout(context) {
    init {
        addView(ink, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        if (wetInk) {
            val wet = WetInkLayer(context)
            addView(wet, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            ink.wet = wet
        }
    }
}
