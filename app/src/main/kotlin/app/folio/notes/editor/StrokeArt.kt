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
import app.folio.core.model.LineStyle
import app.folio.core.model.Stroke
import app.folio.core.model.StrokeLook
import app.folio.core.model.ToolType
import kotlin.random.Random

/**
 * A stroke ready to draw: geometry built once, then drawn by tiles, the live surface,
 * thumbnails and PDF export alike. Never changed after construction (bounds are computed up
 * front), so several threads may draw it at once.
 *
 * [path] is the filled outline (every brush-shaped tool; also the PDF fallback for meshed tools).
 * [verts]/[texs]/[colors] are the pencil's triangle mesh: per-vertex darkness from pressure
 * and lean, modulated by the tool's [grainOf] paper texture.
 * [centerline] is set for lines drawn as a stroked path instead (schema-6 looks): dashed and
 * dotted lines, the round highlighter and tape. Then [lineWidth] is their width.
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
    val look: StrokeLook = StrokeLook.DEFAULT,
    val centerline: Path? = null,
    val lineWidth: Float = width,
) {
    val vertexCount get() = colors?.size ?: 0

    /** The mesh colours with the rgb of [rgb] (dark-mode display); cached for the last one asked. */
    @Volatile private var recolored: Pair<Int, IntArray>? = null

    fun colorsWith(rgb: Int): IntArray? {
        val base = colors ?: return null
        val own = color.toInt() and 0xFFFFFF
        if ((rgb and 0xFFFFFF) == own) return base
        recolored?.let { if (it.first == rgb) return it.second }
        val out = IntArray(base.size) { (base[it] and 0xFF000000.toInt()) or (rgb and 0xFFFFFF) }
        recolored = rgb to out
        return out
    }

    companion object {
        private val boundsTmp = ThreadLocal.withInitial { RectF() }

        /** Pressure used for "Fixed thickness" lines: the width a medium press gives. */
        const val FIXED_PRESSURE = 0.72f

        /** The art for a stored stroke, cached on it (engine v2, or v1 when switched back). */
        fun of(s: Stroke): StrokeArt {
            (s.renderCache as? StrokeArt)?.let { return it }
            val art = build(s.tool, s.color, s.width, s.x, s.y, s.pressure, s.tilt, s.orientation, s.size, s.look, stored = s)
            s.renderCache = art
            return art
        }

        /** Art for samples that aren't a stored stroke yet (the stroke being written). */
        fun live(
            tool: ToolType, color: Argb, width: Float,
            xs: FloatArray, ys: FloatArray, ps: FloatArray, tilts: FloatArray, oris: FloatArray, n: Int,
            look: StrokeLook = StrokeLook.DEFAULT,
        ): StrokeArt = build(tool, color, width, xs, ys, ps, tilts, oris, n, look, stored = null)

        private fun build(
            tool: ToolType, color: Argb, width: Float,
            xs: FloatArray, ys: FloatArray, ps: FloatArray, tilts: FloatArray, oris: FloatArray, n: Int,
            look: StrokeLook, stored: Stroke?,
        ): StrokeArt {
            if (strokedLook(tool, look)) {
                val line = sealed(centerlineOf(xs, ys, n))
                // Dashes and dots keep a pen's feel through its mean pressure unless fixed.
                val w = if (tool == ToolType.TAPE || tool == ToolType.HIGHLIGHTER || look.fixedWidth || n == 0) width
                else PenProfile.of(tool).widthAt(width, ps.take(n).average().toFloat(), 0f)
                return StrokeArt(tool, color, width, line, null, null, null, 1f, look, line, w)
            }
            val pressures = if (look.fixedWidth) FloatArray(ps.size) { FIXED_PRESSURE } else ps
            if (!StrokeGeometry.useV2) {
                val outline = if (stored != null && !look.fixedWidth) StrokeGeometry.outlineV1(stored)
                else StrokeGeometry.outline(xs, ys, pressures, tilts, n, width, PenProfile.of(tool))
                return StrokeArt(tool, color, width, sealed(PageRenderer.pathOf(outline)), null, null, null, 1f, look)
            }
            val mesh = if (stored != null && !look.fixedWidth) StrokeGeometry.mesh(stored)
            else BrushEngine.mesh(xs, ys, pressures, tilts, oris, n, width, BrushSpec.of(tool))
            return fromMesh(tool, color, width, mesh, look)
        }

        /** Looks drawn as a stroked centreline rather than a filled outline. */
        fun strokedLook(tool: ToolType, look: StrokeLook) =
            tool == ToolType.TAPE || (tool == ToolType.HIGHLIGHTER && look.roundTip) || look.line != LineStyle.SOLID

        /** A smooth path through the samples (quadratic curves through the midpoints). */
        private fun centerlineOf(xs: FloatArray, ys: FloatArray, n: Int): Path {
            val p = Path()
            if (n == 0) return p
            p.moveTo(xs[0], ys[0])
            if (n == 1) { p.lineTo(xs[0] + 0.01f, ys[0]); return p }
            if (n == 2) { p.lineTo(xs[1], ys[1]); return p }
            for (i in 1 until n - 1) {
                val mx = (xs[i] + xs[i + 1]) / 2f; val my = (ys[i] + ys[i + 1]) / 2f
                p.quadTo(xs[i], ys[i], mx, my)
            }
            p.lineTo(xs[n - 1], ys[n - 1])
            return p
        }

        fun fromOutline(tool: ToolType, color: Argb, width: Float, outline: FloatArray): StrokeArt =
            StrokeArt(tool, color, width, sealed(PageRenderer.pathOf(outline)), null, null, null, 1f)

        private fun fromMesh(tool: ToolType, color: Argb, width: Float, mesh: StrokeMesh?, look: StrokeLook): StrokeArt {
            if (mesh == null) return StrokeArt(tool, color, width, Path(), null, null, null, 1f, look)
            val path = sealed(PageRenderer.pathOf(mesh.outline()))
            if (!BrushSpec.of(tool).shading) return StrokeArt(tool, color, width, path, null, null, null, 1f, look)
            // The airbrush is drawn as a blurred fill (mask filters don't apply to meshes), so it
            // only needs its mean density.
            if (grainOf(tool) == null) {
                var sum = 0f
                for (i in 0 until mesh.pairs) sum += mesh.shade[i]
                return StrokeArt(tool, color, width, path, null, null, null, if (mesh.pairs > 0) sum / mesh.pairs else 1f, look)
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
            return StrokeArt(tool, color, width, path, t.verts, texs, colors, if (t.count > 0) sum / t.count else 1f, look)
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

/** Tape outlines for previews: a band with lightly torn (zigzag) ends. */
object TapeArt {
    fun torn(out: android.graphics.Path, r: RectF) {
        val teeth = 4
        val h = r.height()
        val d = (h * 0.12f).coerceAtMost(r.width() / 8f)
        out.reset()
        out.moveTo(r.left, r.top)
        out.lineTo(r.right, r.top)
        for (i in 1..teeth) out.lineTo(r.right - if (i % 2 == 1) d else 0f, r.top + h * i / teeth)
        out.lineTo(r.left, r.bottom)
        for (i in 1 until teeth) out.lineTo(r.left + if (i % 2 == 1) d else 0f, r.bottom - h * i / teeth)
        out.close()
    }
}
