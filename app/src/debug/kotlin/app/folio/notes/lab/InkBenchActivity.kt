package app.folio.notes.lab

import android.os.Bundle
import androidx.activity.ComponentActivity
import app.folio.core.model.PaperTemplate
import app.folio.core.ink.SelectionTransform
import app.folio.core.model.Stroke
import app.folio.core.model.ToolType
import app.folio.notes.editor.InkCanvasView

/**
 * Debug builds only: the real writing surface on a blank in-memory page (nothing is saved,
 * no notebook is touched), for latency and brush checks on a device. Used by
 * scripts/latency_bench.py:
 * `adb shell am start -n app.folio.notes/.lab.InkBenchActivity --es tool FOUNTAIN --ef stab 0.1`
 */
class InkBenchActivity : ComponentActivity() {
    private val strokes = ArrayList<Stroke>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = InkCanvasView(this)
        view.tool = intent.getStringExtra("tool")?.let { runCatching { ToolType.valueOf(it) }.getOrNull() } ?: ToolType.FOUNTAIN
        view.stabilization = intent.getFloatExtra("stab", 0.1f)
        view.penWidth = intent.getFloatExtra("width", 3.5f)
        view.penOnlyPreference = false
        fun publish() = view.setPage("bench", ArrayList(strokes), PaperTemplate.RULED, 1000f, 1414f)
        view.listener = object : InkCanvasView.Listener {
            override fun onStrokeFinished(stroke: Stroke) { strokes += stroke; publish() }
            override fun onStrokesErased(ids: Set<String>) { strokes.removeAll { it.id in ids }; publish() }
            override fun onLassoSelected(ids: Set<String>) = Unit
            override fun onSelectionTransformed(ids: Set<String>, transform: SelectionTransform) = Unit
            override fun onStrokesSplit(pieces: Map<String, List<Stroke>>) = Unit
        }
        // `--ei dense 6000`: start from a synthetic handwritten page with that many strokes.
        strokes += benchPage(intent.getIntExtra("dense", 0))
        publish()
        // `--ez wet true`: force the front-buffered wet-ink layer on (even where unsupported) to measure it.
        val wet = intent.getBooleanExtra("wet", app.folio.notes.editor.WetInkLayer.supported)
        setContentView(app.folio.notes.editor.InkCanvasHost(this, view, wet))
        // `--ef zoom 5`: zoom to that multiple of fit width (5 = the editor's maximum).
        intent.getFloatExtra("zoom", 1f).takeIf { it != 1f }?.let { view.benchZoom(it) }
    }
}

/** Squiggly "words" along ruled lines (like scripts/perf_tab.py); dense pages overprint. */
internal fun benchPage(n: Int): List<Stroke> {
    val rnd = java.util.Random(7)
    var line = 160f; var x = 130f
    return List(n) { i ->
        val pts = 30 + rnd.nextInt(51)
        val w = 25f + rnd.nextFloat() * 45f
        val waves = 2f + rnd.nextFloat() * 3f
        val xs = FloatArray(pts); val ys = FloatArray(pts); val ps = FloatArray(pts)
        for (k in 0 until pts) {
            val t = k / (pts - 1f)
            xs[k] = x + t * w
            ys[k] = line - 12f + 12f * kotlin.math.sin(t * Math.PI.toFloat() * waves) + rnd.nextFloat() * 2f - 1f
            ps[k] = 0.3f + 0.4f * kotlin.math.sin(t * Math.PI.toFloat())
        }
        x += w + 8f + rnd.nextFloat() * 12f
        if (x > 900f) { x = 130f; line += 40f; if (line > 1380f) line = 160f }
        Stroke(
            id = "bench-$i", tool = ToolType.FOUNTAIN, color = 0xFF1B1B1F, width = 3.5f, x = xs, y = ys, pressure = ps,
            tilt = FloatArray(pts) { 0.7f }, orientation = FloatArray(pts), t = IntArray(pts) { it * 4 },
        )
    }
}
