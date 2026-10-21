package app.folio.notes.editor

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import app.folio.core.model.Argb
import app.folio.notes.editor.oneui.ColorMath

/**
 * Tape patterns, generated in code (Folio's own designs; patterned tape is built from small
 * repeating tiles, see docs/research/samsung-notes-ink-techniques.md). Each pattern is a
 * seamless tile in the tape's colour with a motif in a contrasting shade, used as a shader in
 * page units so the pattern stays put on the page and scales with zoom.
 */
object TapePatterns {
    /** Names for the picker, by pattern id 1–9. */
    val NAMES = listOf("Diagonal stripes", "Gingham", "Polka dots", "Grid", "Pinstripes", "Hearts", "Waves", "Scallops", "Crosses")

    /** Page units one tile spans. */
    const val TILE_UNITS = 24f
    private const val TILE_PX = 96

    private val cache = object : LinkedHashMap<Long, BitmapShader>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, BitmapShader>?) = size > 48
    }

    /** The shader for [pattern] (1–9) on a tape of [color]; tiles [TILE_UNITS] page units wide. */
    fun shader(pattern: Int, color: Argb): BitmapShader = synchronized(cache) {
        val key = (pattern.toLong() shl 32) xor (color and 0xFFFFFFFFL)
        cache.getOrPut(key) {
            BitmapShader(tile(pattern, color), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
                setLocalMatrix(Matrix().apply { setScale(TILE_UNITS / TILE_PX, TILE_UNITS / TILE_PX) })
            }
        }
    }

    /** Motif colour: white on darker tapes, a deep shade of the tape on light ones. */
    fun motifColor(color: Argb): Int {
        val light = ColorMath.luminance(color) > 0.45f
        return if (light) ColorMath.withAlpha(ColorMath.fromHsl(ColorMath.toHsl(color)[0], 0.55f, 0.32f), 150).toInt()
        else 0xB3FFFFFF.toInt()
    }

    fun tile(pattern: Int, color: Argb): Bitmap {
        val n = TILE_PX
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor((color or 0xFF000000).toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = motifColor(color) }
        val f = n.toFloat()
        when (pattern) {
            1 -> { // diagonal stripes, seamless at 45°
                p.style = Paint.Style.STROKE; p.strokeWidth = f * 0.16f
                for (k in -1..2) c.drawLine(k * f / 2 - f, f, k * f / 2, 0f, p)
                for (k in -1..2) c.drawLine(k * f / 2, f, k * f / 2 + f, 0f, p)
            }
            2 -> { // gingham: translucent bands crossing
                p.alpha = (p.alpha * 0.55f).toInt()
                c.drawRect(0f, 0f, f / 2, f, p); c.drawRect(0f, 0f, f, f / 2, p)
            }
            3 -> { // polka dots on a half-drop grid
                val r = f * 0.11f
                c.drawCircle(f * 0.25f, f * 0.25f, r, p); c.drawCircle(f * 0.75f, f * 0.75f, r, p)
            }
            4 -> { // fine grid
                p.style = Paint.Style.STROKE; p.strokeWidth = f * 0.035f
                c.drawLine(0f, f / 2, f, f / 2, p); c.drawLine(f / 2, 0f, f / 2, f, p)
            }
            5 -> { // vertical pinstripes, two weights
                c.drawRect(f * 0.12f, 0f, f * 0.2f, f, p); c.drawRect(f * 0.62f, 0f, f * 0.66f, f, p)
            }
            6 -> { // small hearts, offset rows
                heart(c, f * 0.27f, f * 0.3f, f * 0.16f, p); heart(c, f * 0.77f, f * 0.8f, f * 0.16f, p)
            }
            7 -> { // waves
                p.style = Paint.Style.STROKE; p.strokeWidth = f * 0.07f; p.strokeCap = Paint.Cap.ROUND
                for (row in 0..1) {
                    val y = f * (0.25f + row * 0.5f)
                    val path = Path().apply {
                        moveTo(0f, y)
                        cubicTo(f * 0.25f, y - f * 0.14f, f * 0.25f, y + f * 0.14f, f * 0.5f, y)
                        cubicTo(f * 0.75f, y - f * 0.14f, f * 0.75f, y + f * 0.14f, f, y)
                    }
                    c.drawPath(path, p)
                }
            }
            8 -> { // scallops (half circles along the bottom of each row)
                p.style = Paint.Style.STROKE; p.strokeWidth = f * 0.06f
                for (row in 0..1) for (col in 0..1) {
                    val cx = f * (0.25f + col * 0.5f); val cy = f * (row * 0.5f + 0.5f)
                    c.drawArc(RectF(cx - f * 0.25f, cy - f * 0.25f, cx + f * 0.25f, cy + f * 0.25f), 180f, 180f, false, p)
                }
            }
            9 -> { // small crosses
                p.style = Paint.Style.STROKE; p.strokeWidth = f * 0.06f; p.strokeCap = Paint.Cap.ROUND
                for ((x, y) in listOf(f * 0.25f to f * 0.25f, f * 0.75f to f * 0.75f)) {
                    c.drawLine(x - f * 0.09f, y, x + f * 0.09f, y, p); c.drawLine(x, y - f * 0.09f, x, y + f * 0.09f, p)
                }
            }
        }
        bmp.prepareToDraw()
        return bmp
    }

    private fun heart(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        val path = Path().apply {
            moveTo(cx, cy + s * 0.9f)
            cubicTo(cx - s * 1.3f, cy, cx - s * 0.7f, cy - s * 0.9f, cx, cy - s * 0.35f)
            cubicTo(cx + s * 0.7f, cy - s * 0.9f, cx + s * 1.3f, cy, cx, cy + s * 0.9f)
            close()
        }
        c.drawPath(path, p)
    }
}
