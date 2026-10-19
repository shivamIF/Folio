package app.folio.notes.editor

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import app.folio.core.ink.BrushEngine
import app.folio.core.ink.BrushSpec
import app.folio.core.ink.PenProfile
import app.folio.core.ink.StrokeGeometry
import app.folio.core.ink.StrokeMesh
import app.folio.core.model.Argb
import app.folio.core.model.Stroke
import app.folio.core.model.ToolType
import kotlin.random.Random

/**
 * A stroke ready to draw: geometry built once, then drawn by tiles, the live surface,
 * thumbnails and PDF export alike. Never changed after construction (bounds are computed up
 * front), so several threads may draw it at once.
 *
 * [path] is the filled outline (every tool; also the PDF fallback for meshed tools).
 * [verts]/[texs]/[colors] are the pencil's triangle mesh: per-vertex darkness from pressure
 * and lean, modulated by the tool's [grainOf] paper texture.
 */
class StrokeArt private constructor(
    val tool: ToolType,
    val color: Argb,
    /** Nominal width (page units), for edge effects sized by the stroke (blur, rim). */
    val width: Float,
    val path: Path,
    val verts: FloatArray?,
    val texs: FloatArray?,
    val colors: IntArray?,
    /** Average darkness, for drawing a meshed tool as a flat fill (PDF) and the airbrush. */
    val meanShade: Float,
) {
    val vertexCount get() = colors?.size ?: 0

    companion object {
        private val boundsTmp = ThreadLocal.withInitial { RectF() }

        /** The art for a stored stroke, cached on it (engine v2, or v1 when switched back). */
        fun of(s: Stroke): StrokeArt {
            (s.renderCache as? StrokeArt)?.let { return it }
            val art = if (StrokeGeometry.useV2) build(s.tool, s.color, s.width, StrokeGeometry.mesh(s))
            else fromOutline(s.tool, s.color, s.width, StrokeGeometry.outlineV1(s))
            s.renderCache = art
            return art
        }

        /** Art for samples that aren't a stored stroke yet (the stroke being written). */
        fun live(
            tool: ToolType, color: Argb, width: Float,
            xs: FloatArray, ys: FloatArray, ps: FloatArray, tilts: FloatArray, oris: FloatArray, n: Int,
        ): StrokeArt = if (StrokeGeometry.useV2) build(tool, color, width, BrushEngine.mesh(xs, ys, ps, tilts, oris, n, width, BrushSpec.of(tool)))
        else fromOutline(tool, color, width, StrokeGeometry.outline(xs, ys, ps, tilts, n, width, PenProfile.of(tool)))

        fun fromOutline(tool: ToolType, color: Argb, width: Float, outline: FloatArray): StrokeArt =
            StrokeArt(tool, color, width, sealed(PageRenderer.pathOf(outline)), null, null, null, 1f)

        private fun build(tool: ToolType, color: Argb, width: Float, mesh: StrokeMesh?): StrokeArt {
            if (mesh == null) return StrokeArt(tool, color, width, Path(), null, null, null, 1f)
            val path = sealed(PageRenderer.pathOf(mesh.outline()))
            if (!BrushSpec.of(tool).shading) return StrokeArt(tool, color, width, path, null, null, null, 1f)
            // The airbrush is drawn as a blurred fill (mask filters don't apply to meshes), so it
            // only needs its mean density.
            if (grainOf(tool) == null) {
                var sum = 0f
                for (i in 0 until mesh.pairs) sum += mesh.shade[i]
                return StrokeArt(tool, color, width, path, null, null, null, if (mesh.pairs > 0) sum / mesh.pairs else 1f)
            }
            val t = mesh.triangles()
            val texs = FloatArray(t.verts.size) { t.verts[it] * GRAIN_PER_UNIT }
            val rgb = color.toInt() and 0xFFFFFF
            val baseA = ((color.toInt() ushr 24) and 0xFF) / 255f
            var sum = 0f
            val colors = IntArray(t.count) {
                sum += t.shadeOf[it]
                val a = (t.shadeOf[it] * baseA * 255f + 0.5f).toInt().coerceIn(0, 255)
                (a shl 24) or rgb
            }
            return StrokeArt(tool, color, width, path, t.verts, texs, colors, if (t.count > 0) sum / t.count else 1f)
        }

        /** Computing bounds once makes later concurrent read-only drawing safe. */
        private fun sealed(p: Path): Path { p.computeBounds(boundsTmp.get()!!, true); return p }

        /** Grain texels per page unit: about one texel per screen pixel at fit-width on a tablet. */
        const val GRAIN_PER_UNIT = 1.4f

        /** The paper texture a meshed tool's pigment sits in, or null for tools drawn as fills. */
        fun grainOf(tool: ToolType): BitmapShader? = when (tool) {
            ToolType.PENCIL -> grain
            else -> null
        }

        /**
         * Graphite on paper: white with patchy alpha (paper tooth catches more or less lead).
         * Procedural and seeded, so every render is identical. Tiles seamlessly.
         */
        val grain: BitmapShader by lazy {
            texture(seed = 1234, period = 8) { v, speck -> 0.42f + 0.38f * v + 0.2f * speck }
        }

        /**
         * A seamless 128² alpha texture: smooth value noise with cells [period] texels wide
         * (a divisor of 128, so it wraps), plus per-texel speckle, shaped by [alpha].
         */
        private fun texture(seed: Int, period: Int, alpha: (v: Float, speck: Float) -> Float): BitmapShader {
            val n = 128
            val rnd = Random(seed)
            val cells = n / period
            val coarse = FloatArray(cells * cells) { rnd.nextFloat() }
            val px = IntArray(n * n)
            for (y in 0 until n) for (x in 0 until n) {
                val fx = x / period.toFloat(); val fy = y / period.toFloat()
                val x0 = fx.toInt(); val y0 = fy.toInt(); val tx = fx - x0; val ty = fy - y0
                fun c(i: Int, j: Int) = coarse[(j % cells) * cells + (i % cells)]
                val v = (c(x0, y0) * (1 - tx) + c(x0 + 1, y0) * tx) * (1 - ty) + (c(x0, y0 + 1) * (1 - tx) + c(x0 + 1, y0 + 1) * tx) * ty
                val a = alpha(v, rnd.nextFloat()).coerceIn(0f, 1f)
                px[y * n + x] = ((a * 255).toInt() shl 24) or 0xFFFFFF
            }
            return BitmapShader(Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
    }
}
