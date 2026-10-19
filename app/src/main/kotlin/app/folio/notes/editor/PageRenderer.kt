package app.folio.notes.editor

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import app.folio.core.ink.BrushSpec
import app.folio.core.ink.PenProfile
import app.folio.core.ink.StrokeGeometry
import app.folio.core.model.Argb
import app.folio.core.model.PaperTemplate
import app.folio.core.model.Stroke
import app.folio.core.model.ToolType

/**
 * Draws a page in page units onto any Canvas: the screen, a bitmap, or a PDF
 * export, so they always match. Holds Paint objects: one instance per thread.
 *
 * [forPdf]: the PDF canvas can't draw vertex meshes or blurs, so the pencil is drawn as a flat
 * fill at its mean darkness and the airbrush without its blur.
 */
class PageRenderer(private val forPdf: Boolean = false) {
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val meshPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; blendMode = android.graphics.BlendMode.MULTIPLY }
    private val pagePaint = Paint().apply { color = Color.WHITE }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC9D6E3.toInt(); strokeWidth = 1.2f }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB0B8C4.toInt() }
    private val marginPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2B8B5.toInt(); strokeWidth = 1.5f }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()

    /** White sheet plus the ruling. With a PDF background the ruling is skipped. */
    fun drawPaper(canvas: Canvas, paper: PaperTemplate, w: Float, h: Float, hasPdf: Boolean = false) {
        canvas.drawRect(0f, 0f, w, h, pagePaint)
        if (hasPdf) return
        val step = 40f
        when (paper) {
            PaperTemplate.BLANK -> Unit
            PaperTemplate.RULED -> {
                var y = 120f
                while (y < h - 40f) { canvas.drawLine(40f, y, w - 40f, y, linePaint); y += step }
                canvas.drawLine(110f, 0f, 110f, h, marginPaint)
            }
            PaperTemplate.GRID -> {
                var x = step; while (x < w) { canvas.drawLine(x, 0f, x, h, linePaint); x += step }
                var y = step; while (y < h) { canvas.drawLine(0f, y, w, y, linePaint); y += step }
            }
            PaperTemplate.DOTTED -> {
                var y = step
                while (y < h) {
                    var x = step
                    while (x < w) { canvas.drawCircle(x, y, 1.6f, dotPaint); x += step }
                    y += step
                }
            }
        }
    }

    /** Stretches a rendered PDF page over the whole page. */
    fun drawPdfBackground(canvas: Canvas, bitmap: Bitmap, w: Float, h: Float) {
        dst.set(0f, 0f, w, h)
        canvas.drawBitmap(bitmap, null, dst, bitmapPaint)
    }

    /** Engine v1 look: a flat fill at the tool's opacity (kept for the v1 switch and the Lab). */
    fun drawStroke(canvas: Canvas, color: Argb, tool: ToolType, path: Path) {
        val argb = color.toInt()
        strokePaint.color = argb
        strokePaint.alpha = (Color.alpha(argb) * PenProfile.of(tool).opacity).toInt()
        strokePaint.shader = null
        canvas.drawPath(path, strokePaint)
    }

    private val sprayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var sprayRadius = -1f

    /**
     * Each tool's own look (engine v2):
     * - pencil: graphite mesh, darker where pressed, lighter on its side, with paper grain;
     * - airbrush: a soft-edged mist whose density follows pressure;
     * - highlighter: multiplied over the page, so the ink and print under it stay crisp and
     *   overlapping passes deepen the colour like a real highlighter;
     * - pens, brush and marker: solid ink (their character is in the geometry).
     */
    fun drawStroke(canvas: Canvas, art: StrokeArt) {
        if (!StrokeGeometry.useV2) return drawStroke(canvas, art.color, art.tool, art.path)
        val argb = art.color.toInt()
        val grain = StrokeArt.grainOf(art.tool)
        when {
            grain != null && art.colors != null && !forPdf -> {
                meshPaint.shader = grain
                canvas.drawVertices(
                    Canvas.VertexMode.TRIANGLES, art.verts!!.size, art.verts, 0, art.texs, 0, art.colors, 0, null, 0, 0, meshPaint,
                )
            }
            grain != null -> {
                // PDF (no meshes): a flat fill at the stroke's mean darkness.
                strokePaint.color = argb
                strokePaint.alpha = (Color.alpha(argb) * art.meanShade).toInt()
                strokePaint.shader = null
                canvas.drawPath(art.path, strokePaint)
            }
            art.tool == ToolType.AIRBRUSH -> {
                sprayPaint.color = argb
                sprayPaint.alpha = (Color.alpha(argb) * SPRAY_ALPHA * art.meanShade).toInt()
                // The blur is in page units (it follows the canvas matrix), so the mist scales
                // with zoom. PDF gets the plain soft-coloured fill.
                val radius = BrushSpec.of(ToolType.AIRBRUSH).soften * SPRAY_BLUR_OF_SOFTEN * art.width
                if (forPdf) sprayPaint.maskFilter = null
                else if (radius != sprayRadius || sprayPaint.maskFilter == null) {
                    sprayPaint.maskFilter = BlurMaskFilter(maxOf(0.5f, radius), BlurMaskFilter.Blur.NORMAL)
                    sprayRadius = radius
                }
                canvas.drawPath(art.path, sprayPaint)
            }
            art.tool == ToolType.HIGHLIGHTER -> {
                highlightPaint.color = argb
                highlightPaint.alpha = (Color.alpha(argb) * HIGHLIGHTER_ALPHA).toInt()
                canvas.drawPath(art.path, highlightPaint)
            }
            else -> {
                strokePaint.color = argb
                strokePaint.shader = null
                canvas.drawPath(art.path, strokePaint)
            }
        }
    }

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val missingPaint = Paint().apply { color = 0xFFE3E1DC.toInt() }
    private val textPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)

    /** A placed picture, stretched to its frame. */
    fun drawImage(canvas: Canvas, bitmap: Bitmap, img: app.folio.core.model.PageImage) {
        dst.set(img.x, img.y, img.x + img.width, img.y + img.height)
        canvas.drawBitmap(bitmap, null, dst, imagePaint)
    }

    /** Placeholder while a picture can't be shown (unreadable file). */
    fun drawMissingImage(canvas: Canvas, img: app.folio.core.model.PageImage) {
        canvas.drawRect(img.x, img.y, img.x + img.width, img.y + img.height, missingPaint)
    }

    /** A text box, wrapped at its width (page units; the canvas carries the zoom). */
    fun drawText(canvas: Canvas, t: app.folio.core.model.TextBox) {
        if (t.text.isEmpty()) return
        val layout = layout(t.text, t.width, t.fontSize, t.color)
        canvas.save()
        canvas.translate(t.x, t.y)
        layout.draw(canvas)
        canvas.restore()
    }

    fun layout(text: String, width: Float, fontSize: Float, color: Argb): android.text.StaticLayout {
        textPaint.textSize = fontSize
        textPaint.color = color.toInt()
        return android.text.StaticLayout.Builder.obtain(text, 0, text.length, textPaint, maxOf(1, width.toInt()))
            .setIncludePad(false).build()
    }

    /**
     * A page's own content above its paper/PDF: pictures, then ink, then text. Used for PDF
     * export and thumbnails ([image] decodes an attachment, or null if it can't).
     */
    fun drawContent(canvas: Canvas, page: app.folio.core.model.Page, image: (String) -> Bitmap?) {
        for (img in page.images) {
            val bmp = image(img.attachmentId)
            if (bmp != null) drawImage(canvas, bmp, img) else drawMissingImage(canvas, img)
        }
        for (s in page.strokes) drawStroke(canvas, s)
        for (t in page.texts) drawText(canvas, t)
    }

    fun drawStroke(canvas: Canvas, s: Stroke) = drawStroke(canvas, StrokeArt.of(s))

    companion object {
        /** Highlighter strength under multiply: light enough to read through. */
        const val HIGHLIGHTER_ALPHA = 0.6f
        /** Airbrush mist strength at full pressure. */
        const val SPRAY_ALPHA = 0.8f
        /** Blur radius as a share of the spec's softening (Android blur reaches ~1.5 × radius). */
        const val SPRAY_BLUR_OF_SOFTEN = 0.7f

        fun pathOf(outline: FloatArray): Path = Path().also { fillPath(it, outline) }

        fun fillPath(path: Path, outline: FloatArray) {
            path.rewind()
            if (outline.size < 6) return
            path.moveTo(outline[0], outline[1])
            var i = 2
            while (i < outline.size) { path.lineTo(outline[i], outline[i + 1]); i += 2 }
            path.close()
            path.fillType = Path.FillType.WINDING
        }
    }
}
