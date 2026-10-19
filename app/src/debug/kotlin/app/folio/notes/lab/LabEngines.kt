package app.folio.notes.lab

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import app.folio.core.ink.PenProfile
import app.folio.core.ink.StrokeGeometry
import app.folio.core.model.Stroke
import app.folio.notes.editor.PageRenderer
import java.util.IdentityHashMap
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke as InkStroke
import kotlin.math.PI

/** A way of drawing Folio strokes, shown as one column of the Lab. */
interface LabEngine {
    val name: String

    /** Why this engine can't draw yet, shown instead of strokes; null when it can. */
    val unavailableReason: String? get() = null

    /** Time spent building geometry for the strokes last drawn (each stroke is built once, then cached). */
    val buildMs: Double get() = 0.0

    /** Draws [strokes] in page units; [pageToView] is already applied to [canvas]. */
    fun draw(canvas: Canvas, strokes: List<Stroke>, preset: LabPreset, pageToView: Matrix)
}

/** Per-engine geometry cache keyed by stroke instance, rebuilt when the preset changes. */
private class GeometryCache<G> {
    private class Entry<G>(val preset: LabPreset, val geometry: G, val buildNanos: Long)
    private var entries = IdentityHashMap<Stroke, Entry<G>>()
    var buildMs = 0.0
        private set

    fun forEach(strokes: List<Stroke>, preset: LabPreset, build: (Stroke) -> G, use: (Stroke, G) -> Unit) {
        val next = IdentityHashMap<Stroke, Entry<G>>(strokes.size)
        for (s in strokes) {
            val e = entries[s]?.takeIf { it.preset == preset } ?: run {
                val t0 = System.nanoTime()
                val g = build(s)
                Entry(preset, g, System.nanoTime() - t0)
            }
            next[s] = e
            use(s, e.geometry)
        }
        entries = next
        buildMs = next.values.sumOf { it.buildNanos } / 1e6
    }
}

/** Today's renderer: the same path as tiles, thumbnails and PDF export. */
class V1Engine : LabEngine {
    override val name = "v1 (current)"
    private val renderer = PageRenderer()
    private val cache = GeometryCache<Path>()
    override val buildMs get() = cache.buildMs

    override fun draw(canvas: Canvas, strokes: List<Stroke>, preset: LabPreset, pageToView: Matrix) =
        cache.forEach(strokes, preset, { PageRenderer.pathOf(StrokeGeometry.outline(it)) }) { s, path ->
            renderer.drawStroke(canvas, s.color, s.tool, path)
        }
}

/** Slot for engine v2 (BRUSH_ENGINE_PLAN, proposed change 1). Swap in its renderer when it lands. */
class V2Engine : LabEngine {
    override val name = "v2 (proposed)"
    override val unavailableReason = "Engine v2 isn't built yet.\nThis column will draw it."

    override fun draw(canvas: Canvas, strokes: List<Stroke>, preset: LabPreset, pageToView: Matrix) = Unit
}

/**
 * AndroidX Ink stock brushes, fed the same points the Folio engines draw (after
 * Folio's stabilisation and pressure curve), so only the rendering differs.
 */
class InkEngine : LabEngine {
    override val name = "AndroidX Ink 1.0"
    private val renderer = CanvasStrokeRenderer.create()

    private class Converted(val stroke: InkStroke, val rejected: Int)
    private val cache = GeometryCache<Converted>()
    override val buildMs get() = cache.buildMs

    /** Input samples Ink rejected across the drawn strokes (out-of-order time and the like). */
    var rejectedSamples = 0
        private set

    override fun draw(canvas: Canvas, strokes: List<Stroke>, preset: LabPreset, pageToView: Matrix) {
        var rejected = 0
        cache.forEach(strokes, preset, { convert(it, preset) }) { _, c ->
            rejected += c.rejected
            renderer.draw(canvas, c.stroke, pageToView)
        }
        rejectedSamples = rejected
    }

    /** Builds the Ink stroke; the mesh is generated here, so this is Ink's geometry cost. */
    private fun convert(s: Stroke, preset: LabPreset): Converted {
        val family = when (preset.ink) {
            InkFamily.PRESSURE_PEN -> StockBrushes.pressurePen()
            InkFamily.MARKER -> StockBrushes.marker()
            InkFamily.HIGHLIGHTER -> StockBrushes.highlighter()
        }
        // v1 multiplies the colour's alpha by the tool's opacity; give Ink the same final colour.
        val argb = s.color.toInt()
        val alpha = ((argb ushr 24) * PenProfile.of(s.tool).opacity).toInt()
        val color = (alpha shl 24) or (argb and 0xFFFFFF)
        val brush = Brush.createWithColorIntArgb(family, color, s.width, EPSILON)

        val batch = MutableStrokeInputBatch()
        var lastT = 0L
        var rejected = 0
        for (i in 0 until s.size) {
            val t = maxOf(s.t[i].toLong(), lastT)
            try {
                batch.add(
                    type = InputToolType.STYLUS,
                    x = s.x[i], y = s.y[i],
                    elapsedTimeMillis = t,
                    pressure = s.pressure[i].coerceIn(0f, 1f),
                    tiltRadians = s.tilt[i].coerceIn(0f, (PI / 2).toFloat()),
                    orientationRadians = normalizeOrientation(s.orientation[i]),
                )
                lastT = t
            } catch (_: IllegalArgumentException) {
                rejected++
            }
        }
        return Converted(InkStroke(brush, batch), rejected)
    }

    private companion object {
        /** Mesh tolerance in page units; well under a pixel at the Lab's zoom levels. */
        const val EPSILON = 0.05f
    }
}
