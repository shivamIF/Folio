package app.folio.notes.editor.oneui

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Colour maths for the editor's palettes and picker. Pure Kotlin (no Android types), so it is
 * unit-tested on the JVM. Colours are ARGB packed into a Long, as in the document model.
 */
object ColorMath {
    fun alpha(c: Long) = ((c ushr 24) and 0xFF).toInt()
    fun red(c: Long) = ((c ushr 16) and 0xFF).toInt()
    fun green(c: Long) = ((c ushr 8) and 0xFF).toInt()
    fun blue(c: Long) = (c and 0xFF).toInt()

    fun argb(a: Int, r: Int, g: Int, b: Int): Long =
        (a.coerceIn(0, 255).toLong() shl 24) or (r.coerceIn(0, 255).toLong() shl 16) or
            (g.coerceIn(0, 255).toLong() shl 8) or b.coerceIn(0, 255).toLong()

    fun rgb(r: Int, g: Int, b: Int): Long = argb(255, r, g, b)

    /** Same colour with alpha [a] (0–255). */
    fun withAlpha(c: Long, a: Int): Long = (c and 0xFFFFFF) or (a.coerceIn(0, 255).toLong() shl 24)

    /** Hue 0–360, saturation and value 0–1. */
    fun toHsv(c: Long): FloatArray {
        val r = red(c) / 255f; val g = green(c) / 255f; val b = blue(c) / 255f
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }.let { if (it < 0f) it + 360f else it }
        val s = if (mx == 0f) 0f else d / mx
        return floatArrayOf(h, s, mx)
    }

    fun fromHsv(h: Float, s: Float, v: Float, a: Int = 255): Long {
        val hh = ((h % 360f) + 360f) % 360f
        val ss = s.coerceIn(0f, 1f); val vv = v.coerceIn(0f, 1f)
        val c = vv * ss
        val x = c * (1f - abs((hh / 60f) % 2f - 1f))
        val m = vv - c
        val (r, g, b) = sector(hh, c, x)
        return argb(a, ((r + m) * 255f).roundToInt(), ((g + m) * 255f).roundToInt(), ((b + m) * 255f).roundToInt())
    }

    /** Hue 0–360, saturation and lightness 0–1. */
    fun toHsl(c: Long): FloatArray {
        val r = red(c) / 255f; val g = green(c) / 255f; val b = blue(c) / 255f
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        val l = (mx + mn) / 2f
        val d = mx - mn
        if (d == 0f) return floatArrayOf(0f, 0f, l)
        val s = d / (1f - abs(2f * l - 1f))
        val h = toHsv(c)[0]
        return floatArrayOf(h, s.coerceIn(0f, 1f), l)
    }

    fun fromHsl(h: Float, s: Float, l: Float, a: Int = 255): Long {
        val hh = ((h % 360f) + 360f) % 360f
        val ss = s.coerceIn(0f, 1f); val ll = l.coerceIn(0f, 1f)
        val c = (1f - abs(2f * ll - 1f)) * ss
        val x = c * (1f - abs((hh / 60f) % 2f - 1f))
        val m = ll - c / 2f
        val (r, g, b) = sector(hh, c, x)
        return argb(a, ((r + m) * 255f).roundToInt(), ((g + m) * 255f).roundToInt(), ((b + m) * 255f).roundToInt())
    }

    private fun sector(h: Float, c: Float, x: Float): Triple<Float, Float, Float> = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }

    /** "#RRGGBB" (alpha is not shown; ink colours are opaque). */
    fun toHex(c: Long): String = "#%06X".format(c and 0xFFFFFF)

    /**
     * Parses "#RGB", "RGB", "#RRGGBB", "RRGGBB" or "#AARRGGBB" (case-insensitive, spaces
     * ignored). Null for anything else.
     */
    fun parseHex(text: String): Long? {
        val t = text.trim().removePrefix("#").replace(" ", "")
        if (t.isEmpty() || t.any { it.lowercaseChar() !in "0123456789abcdef" }) return null
        return when (t.length) {
            3 -> rgb(t[0].digit() * 17, t[1].digit() * 17, t[2].digit() * 17)
            6 -> 0xFF000000L or t.toLong(16)
            8 -> t.toLong(16)
            else -> null
        }
    }

    private fun Char.digit() = Character.digit(this, 16)

    /** Relative luminance (sRGB, 0 = black … 1 = white). */
    fun luminance(c: Long): Float {
        fun ch(v: Int): Float { val s = v / 255f; return if (s <= 0.03928f) s / 12.92f else Math.pow(((s + 0.055f) / 1.055f).toDouble(), 2.4).toFloat() }
        return 0.2126f * ch(red(c)) + 0.7152f * ch(green(c)) + 0.0722f * ch(blue(c))
    }

    /** True if dark marks (a check, a number) read better on [c] than light ones. */
    fun prefersDarkContent(c: Long): Boolean = luminance(c) > 0.55f

    /**
     * How ink is shown on a dark page ("Adjust colour in Dark mode"): a colour darker than
     * mid-lightness is mirrored to the same lightness above it (hue and saturation kept), so
     * black ink reads as white and a dark green as a light green; light colours are unchanged.
     * Display only: stored colours never change.
     */
    fun adaptForDark(c: Long): Long {
        val hsl = toHsl(c)
        if (hsl[2] >= 0.5f) return c
        return fromHsl(hsl[0], hsl[1], 1f - hsl[2], alpha(c))
    }

    /**
     * The picker's swatch grid: [ROWS] rows from light to dark. Column 0 is a grey ramp (white
     * to black); the others are [SWATCH_HUES]. Lightness runs evenly from very light to very
     * dark.
     * Generated (Folio's own values), not a copied table.
     */
    fun swatchGrid(): List<List<Long>> = List(ROWS) { r ->
        val f = r / (ROWS - 1f) // 0 = top (light) … 1 = bottom (dark)
        val grey = (255f * (1f - f)).roundToInt()
        // One strong saturation for every row, so each column darkens steadily (tested).
        listOf(rgb(grey, grey, grey)) + SWATCH_HUES.map { h -> fromHsl(h, SWATCH_SATURATION, 0.94f - 0.82f * f) }
    }

    const val ROWS = 13
    private const val SWATCH_SATURATION = 0.88f

    /** Red, orange, gold, yellow, green, spring green, cyan, azure, blue, violet, magenta, pink. */
    val SWATCH_HUES = listOf(2f, 26f, 42f, 56f, 96f, 146f, 174f, 204f, 226f, 266f, 298f, 336f)
}
