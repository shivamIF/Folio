package app.folio.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as StrokeStyle
import app.folio.core.ink.PenProfile
import app.folio.core.ink.StrokeGeometry
import app.folio.core.model.LineStyle
import app.folio.core.model.PaperTemplate
import app.folio.core.model.Stroke
import app.folio.core.model.ToolType

/**
 * How one stored stroke is drawn on the Mac, built once per stroke. The geometry comes from the
 * same portable engine as on Android ([StrokeGeometry.mesh]); only the drawing calls differ.
 */
class StrokeArt(val path: Path, val color: Color, /** Null: fill the outline. Otherwise stroke the centreline this wide. */ val lineWidth: Float?, val effect: PathEffect?, val cap: StrokeCap)

object PageArt {
    fun of(s: Stroke): StrokeArt {
        val look = s.look
        val base = Color(s.color.toInt())
        val color = when {
            s.tool == ToolType.TAPE && look.peeled -> base.copy(alpha = 0.22f)
            s.tool == ToolType.PENCIL -> base.copy(alpha = base.alpha * 0.82f)
            else -> base
        }
        val stroked = s.tool == ToolType.TAPE || (s.tool == ToolType.HIGHLIGHTER && look.roundTip) || look.line != LineStyle.SOLID
        if (stroked) {
            val w = if (s.tool == ToolType.TAPE || s.tool == ToolType.HIGHLIGHTER || look.fixedWidth || s.size == 0) s.width
            else PenProfile.of(s.tool).widthAt(s.width, s.pressure.average().toFloat(), 0f)
            val effect = when (look.line) {
                LineStyle.DASHED -> PathEffect.dashPathEffect(floatArrayOf(w * 3f, w * 2.2f))
                LineStyle.DOTTED -> PathEffect.dashPathEffect(floatArrayOf(0.01f, w * 2f))
                else -> null
            }
            return StrokeArt(centreline(s), color, w, effect, if (s.tool == ToolType.TAPE) StrokeCap.Butt else StrokeCap.Round)
        }
        val path = Path()
        val outline = StrokeGeometry.mesh(s)?.outline()
        if (outline != null && outline.size >= 6) {
            path.moveTo(outline[0], outline[1])
            var i = 2
            while (i + 1 < outline.size) { path.lineTo(outline[i], outline[i + 1]); i += 2 }
            path.close()
        }
        return StrokeArt(path, color, null, null, StrokeCap.Round)
    }

    private fun centreline(s: Stroke): Path {
        val p = Path(); val n = s.size
        if (n == 0) return p
        p.moveTo(s.x[0], s.y[0])
        if (n == 1) { p.lineTo(s.x[0] + 0.01f, s.y[0]); return p }
        if (n == 2) { p.lineTo(s.x[1], s.y[1]); return p }
        for (i in 1 until n - 1) p.quadraticTo(s.x[i], s.y[i], (s.x[i] + s.x[i + 1]) / 2f, (s.y[i] + s.y[i + 1]) / 2f)
        p.lineTo(s.x[n - 1], s.y[n - 1])
        return p
    }

    fun DrawScope.drawStroke(a: StrokeArt) {
        if (a.lineWidth == null) drawPath(a.path, a.color)
        else drawPath(a.path, a.color, style = StrokeStyle(a.lineWidth, cap = a.cap, join = StrokeJoin.Round, pathEffect = a.effect))
    }

    /** Paper and its ruling, in page units (same measures as the Android renderer). */
    fun DrawScope.drawPaper(paper: PaperTemplate, w: Float, h: Float, hasPdf: Boolean) {
        drawRect(Color.White, size = androidx.compose.ui.geometry.Size(w, h))
        if (hasPdf) return
        val line = Color(0xFFC9D6E3); val step = 40f
        when (paper) {
            PaperTemplate.BLANK -> Unit
            PaperTemplate.RULED -> {
                var y = 120f
                while (y < h - 40f) { drawLine(line, Offset(40f, y), Offset(w - 40f, y), 1.2f); y += step }
                drawLine(Color(0xFFF2B8B5), Offset(110f, 0f), Offset(110f, h), 1.5f)
            }
            PaperTemplate.GRID -> {
                var x = step; while (x < w) { drawLine(line, Offset(x, 0f), Offset(x, h), 1.2f); x += step }
                var y = step; while (y < h) { drawLine(line, Offset(0f, y), Offset(w, y), 1.2f); y += step }
            }
            PaperTemplate.DOTTED -> {
                var y = step
                while (y < h) { var x = step; while (x < w) { drawCircle(Color(0xFFB0B8C4), 1.6f, Offset(x, y)); x += step }; y += step }
            }
        }
    }
}
