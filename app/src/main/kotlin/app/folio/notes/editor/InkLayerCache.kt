package app.folio.notes.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import app.folio.core.model.Stroke
import java.util.concurrent.Executors

/**
 * Raster cache of a page's committed strokes, so a frame draws one bitmap
 * plus the stroke being written instead of re-rendering every stroke.
 * Measured on a Galaxy Tab S6 Lite: with 600 strokes the old vector path made
 * frames 25–36 ms (below 60 fps); at 3,000 strokes 70–120 ms.
 *
 * Rules:
 * - A layer covers a page rectangle at one scale (page units → bitmap pixels).
 * - Appending strokes (normal writing) paints them onto the existing bitmap.
 * - Any other change (erase, undo, move), a zoom change or panning outside the
 *   covered area triggers a rebuild on a background thread. Until it's ready
 *   the canvas keeps drawing the old layer (scaled) or falls back to vector drawing.
 */
internal class InkLayerCache(
    /** Main thread: whether a finished build still matches what the page shows now. */
    private val isCurrent: (Spec) -> Boolean,
    private val onLayerReady: () -> Unit,
) {

    class Layer(
        val bitmap: Bitmap,
        val scale: Float,
        val left: Float,
        val top: Float,
        /** Exactly the strokes painted into [bitmap], in order. */
        var strokes: List<Stroke>,
        /** Stroke ids deliberately left out (being erased or moved). */
        val excluded: Set<String>,
    ) {
        val right get() = left + bitmap.width / scale
        val bottom get() = top + bitmap.height / scale
        val canvas: Canvas = Canvas(bitmap).apply { scale(scale, scale); translate(-left, -top) }

        /** Pixels were changed directly (eraser wipe); the layer must be rebuilt before it's trusted again. */
        var dirty = false

        /** A wipe was committed: the next stroke list from the page is what these pixels show. */
        var awaitingWipeCommit = false

        /** Coverage check with one bitmap pixel of tolerance (sizes are whole pixels). */
        fun covers(l: Float, t: Float, r: Float, b: Float): Boolean {
            val tol = 1f / scale
            return l >= left - tol && t >= top - tol && r <= right + tol && b <= bottom + tol
        }
    }

    data class Spec(
        val scale: Float, val left: Float, val top: Float, val widthPx: Int, val heightPx: Int,
        val strokes: List<Stroke>, val excluded: Set<String>,
    )

    var layer: Layer? = null
        private set

    private var building = false
    private var pending: Spec? = null
    private var released = false
    private val main = Handler(Looper.getMainLooper())

    /** Main thread. Builds [spec] in the background; if a build is running, only the latest request is kept. */
    fun request(spec: Spec) {
        if (released) return
        if (building) { pending = spec; return }
        building = true
        executor.execute {
            val t0 = System.nanoTime()
            val built = try { build(spec) } catch (_: OutOfMemoryError) { null }
            android.util.Log.d("FolioPerf", "layer build ms=%.1f strokes=%d px=%dx%d".format((System.nanoTime() - t0) / 1e6, spec.strokes.size, spec.widthPx, spec.heightPx))
            main.post {
                building = false
                if (released) { built?.bitmap?.recycle(); return@post }
                // A build that started before later edits is out of date: drop it, the newest request follows.
                if (built != null && isCurrent(spec)) layer = built else built?.bitmap?.recycle()
                onLayerReady()
                pending?.let { pending = null; request(it) }
            }
        }
    }

    /**
     * Main thread: true if the layer shows exactly [strokes] minus [excluded].
     * Strokes appended since the layer was built are painted onto it here.
     */
    fun syncContent(strokes: List<Stroke>, excluded: Set<String>, renderer: PageRenderer, pathFor: (Stroke) -> android.graphics.Path): Boolean {
        val l = layer ?: return false
        if (l.dirty || l.excluded != excluded) return false
        if (l.strokes === strokes) return true
        val old = l.strokes
        if (strokes.size < old.size) return false
        for (i in old.indices) if (strokes[i] !== old[i]) return false
        for (i in old.size until strokes.size) {
            val s = strokes[i]
            if (s.id !in excluded) renderer.drawStroke(l.canvas, s.color, s.tool, pathFor(s))
        }
        l.strokes = strokes
        return true
    }

    /**
     * Main thread, from setPage: after a wipe was committed, adopt the edited stroke list
     * for the wiped pixels so the next wipe can start at once (a sharp rebuild follows).
     */
    fun adoptAfterWipe(strokes: List<Stroke>) {
        val l = layer ?: return
        if (!l.awaitingWipeCommit) return
        l.strokes = strokes
        l.dirty = false
        l.awaitingWipeCommit = false
    }

    fun release() {
        released = true
        layer = null
        pending = null
    }

    private fun build(spec: Spec): Layer {
        val bmp = Bitmap.createBitmap(spec.widthPx.coerceAtLeast(1), spec.heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val layer = Layer(bmp, spec.scale, spec.left, spec.top, spec.strokes, spec.excluded)
        val renderer = PageRenderer() // own Paint objects: this runs off the main thread
        for (s in spec.strokes) if (s.id !in spec.excluded) renderer.drawStroke(layer.canvas, s)
        return layer
    }

    companion object {
        /** Largest layer: about 32 MB. Bigger pages (deep zoom) cache only the area around the viewport. */
        const val MAX_LAYER_PIXELS = 8_000_000L
        private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "folio-ink-layer").apply { priority = Thread.NORM_PRIORITY - 1 } }
    }
}
