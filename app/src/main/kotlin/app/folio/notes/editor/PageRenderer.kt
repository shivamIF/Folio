package app.folio.notes.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import app.folio.core.ink.PenProfile
import app.folio.core.ink.StrokeGeometry
import app.folio.core.model.Argb
import app.folio.core.model.PaperTemplate
import app.folio.core.model.Stroke
import app.folio.core.model.ToolType

/**
 * Draws a page in page units onto any Canvas: the screen, a bitmap, or a PDF
 * export, so they always match. Holds Paint objects: one instance per thread.
 */
class PageRenderer {
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
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

    fun drawStroke(canvas: Canvas, color: Argb, tool: ToolType, path: Path) {
        val argb = color.toInt()
        strokePaint.color = argb
        strokePaint.alpha = (Color.alpha(argb) * PenProfile.of(tool).opacity).toInt()
        canvas.drawPath(path, strokePaint)
    }

    fun drawStroke(canvas: Canvas, s: Stroke) = drawStroke(canvas, s.color, s.tool, pathOf(StrokeGeometry.outline(s)))

    companion object {
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
