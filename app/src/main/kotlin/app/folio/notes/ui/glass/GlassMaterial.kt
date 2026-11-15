package app.folio.notes.ui.glass

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * How a glass material treats what's behind it, in the order macOS applies it (Folio's own
 * reading of the CoreMaterial recipes, docs/research/macos-glass-dock.md §2): the backdrop is
 * sampled at [backdropScale], its saturation multiplied by [saturation], [brightness] added,
 * its luminance pushed through a cubic curve ([luminance], four Bézier control values over
 * L = 0…1) by [luminanceAmount], and then blurred by [blurRadius] (dp).
 *
 * The luminance curve is what keeps text legible: it squeezes any backdrop into a light band
 * (light materials) or a dark band (dark materials), so the text colour always has contrast.
 */
data class MaterialRecipe(
    val blurRadius: Float,
    val saturation: Float,
    val brightness: Float,
    val luminance: List<Float>,
    val luminanceAmount: Float,
    val backdropScale: Float = 0.25f,
) {
    /** The luminance curve at [l] (0…1): cubic Bézier through the four control values. */
    fun curve(l: Float): Float {
        val t = l.coerceIn(0f, 1f); val u = 1 - t
        val (p0, p1, p2, p3) = luminance
        return u * u * u * p0 + 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t * p3
    }

    /** Output luminance for a backdrop of luminance [l]. */
    fun mapLuminance(l: Float): Float = (l + (curve(l) - l) * luminanceAmount + brightness).coerceIn(0f, 1f)

    fun lerp(o: MaterialRecipe, t: Float) = MaterialRecipe(
        blurRadius + (o.blurRadius - blurRadius) * t, saturation + (o.saturation - saturation) * t,
        brightness + (o.brightness - brightness) * t, luminance.zip(o.luminance) { a, b -> a + (b - a) * t },
        luminanceAmount + (o.luminanceAmount - luminanceAmount) * t, backdropScale + (o.backdropScale - backdropScale) * t,
    )

    companion object {
        // Values read from macOS 27 CoreMaterial recipes with REA (evidence ids in the research note).
        val DockLight = MaterialRecipe(30f, 1.8f, 0.08f, listOf(0.3f, 0.5f, 1f, 0.77f), 0.5f)
        val DockDark = MaterialRecipe(30f, 1.6f, 0f, listOf(0.29f, -0.2f, 0.375f, 0.65f), 0.5f)
        val ChromeLight = MaterialRecipe(22.5f, 1.1f, 0.1f, listOf(0.8f, 0.9f, 1.1f, 0.825f), 0.75f)
        val ChromeDark = MaterialRecipe(22.5f, 2f, -0.1f, listOf(0.23f, 0.52f, 0.27f, 0.255f), 0.75f)

        /** Thickness ladder: ultra thin, thin, regular, thick. */
        val LadderLight = listOf(
            MaterialRecipe(22.5f, 1.1f, 0.12f, listOf(0.45f, 0.55f, 0.65f, 0.68f), 0.5f),
            MaterialRecipe(30f, 1.35f, 0.12f, listOf(0.725f, 0.825f, 0.76f, 0.73f), 0.6f),
            MaterialRecipe(30f, 1.5f, 0.1f, listOf(0.9f, 0.83f, 0.925f, 0.815f), 0.75f),
            MaterialRecipe(45f, 1.5f, 0.045f, listOf(0.99f, 0.95f, 0.98f, 0.905f), 0.88f),
        )
        val LadderDark = listOf(
            MaterialRecipe(22.5f, 1.1f, 0f, listOf(0.24f, 0.24f, 0.3f, 0.39f), 0.5f),
            MaterialRecipe(30f, 1.35f, 0f, listOf(0.2f, 0.21f, 0.1f, 0.15f), 0.6f),
            MaterialRecipe(30f, 1.5f, 0f, listOf(0.16f, 0.26f, 0.1f, 0.1f), 0.75f),
            MaterialRecipe(45f, 1.5f, 0f, listOf(0.14f, 0.16f, 0.1f, 0.03f), 0.88f),
        )

        fun ladder(dark: Boolean, pos: Float): MaterialRecipe {
            val l = if (dark) LadderDark else LadderLight
            val p = pos.coerceIn(0f, (l.size - 1).toFloat())
            val i = min(p.toInt(), l.size - 2)
            return l[i].lerp(l[i + 1], p - i)
        }
    }
}

/** What a glass surface is for; each sits at its own place on the thickness ladder. */
enum class GlassRole(internal val ladderBias: Float) {
    /** The editor toolbar: the Dock's own material. */
    DOCK(0f),
    /** Tool popups and pickers (pen settings, colours, sizes). */
    POPUP(0.35f),
    /** Menus and dropdowns. */
    MENU(0.5f),
    /** Library sidebar and bars (window chrome). */
    CHROME(-0.25f),
    /** Dialogs and sheets: the thickest, they hold the most text. */
    DIALOG(0.8f),
    /** Small controls (slider thumbs, chips): thin. */
    CONTROL(-0.6f),
}

/**
 * Text and icon colours on a glass surface, from the material's own colour through the
 * vibrancy colour matrices macOS uses for labels (platformFillLight/Dark, §3 of the research
 * note): light glass gets a darkened, saturated version of what's behind, dark glass a
 * lightened one. Then pushed until it meets the contrast target.
 */
data class VibrantColors(
    val primary: Int,
    val secondary: Int,
    val tertiary: Int,
    val separator: Int,
    /** The surface colour these were made for (opaque). */
    val surface: Int,
)

/** A 4×5 colour matrix applied to RGB (rows R, G, B; columns R, G, B, A, offset). */
class VibrancyMatrix(private val m: FloatArray) {
    init { require(m.size == 15) }
    fun apply(r: Float, g: Float, b: Float): FloatArray = floatArrayOf(
        (m[0] * r + m[1] * g + m[2] * b + m[3] + m[4]).coerceIn(0f, 1f),
        (m[5] * r + m[6] * g + m[7] * b + m[8] + m[9]).coerceIn(0f, 1f),
        (m[10] * r + m[11] * g + m[12] * b + m[13] + m[14]).coerceIn(0f, 1f),
    )

    companion object {
        // Rows as (m11 m12 m13 m14 m15 / m21 … / m31 …), alpha column folded (m14/m24/m34 are 0).
        val LightPrimary = VibrancyMatrix(floatArrayOf(1.514125f, -0.954751f, -0.184374f, 0f, 0.0625f, -0.485603f, 1.046185f, -0.185582f, 0f, 0.0625f, -0.486811f, -0.952939f, 1.81475f, 0f, 0.0625f))
        val LightSecondary = VibrancyMatrix(floatArrayOf(1.23555f, -0.3234631f, -0.06208679f, 0f, 0f, -0.1642596f, 1.077192f, -0.06293241f, 0f, 0f, -0.1651052f, -0.3221948f, 1.3373f, 0f, 0f))
        val LightTertiary = VibrancyMatrix(floatArrayOf(1.177642f, -0.1426014f, -0.02704049f, 0f, -0.084f, -0.07218799f, 1.107983f, -0.02779550f, 0f, -0.084f, -0.07294299f, -0.141469f, 1.222412f, 0f, -0.084f))
        val LightSeparator = VibrancyMatrix(floatArrayOf(1.407578f, -0.5748306f, -0.1107474f, 0f, 0.019f, -0.2921908f, 1.125965f, -0.1117742f, 0f, 0.019f, -0.2932176f, -0.5732903f, 1.588508f, 0f, 0.019f))
        val DarkPrimary = VibrancyMatrix(floatArrayOf(1.37625f, -0.7345164f, -0.1417335f, 0f, 0.5f, -0.373512f, 1.016302f, -0.1427905f, 0f, 0.5f, -0.374569f, -0.732931f, 1.6075f, 0f, 0.5f))
        val DarkSecondary = VibrancyMatrix(floatArrayOf(1.22658f, -0.3410732f, -0.06550679f, 0f, 0.18f, -0.1732296f, 1.059582f, -0.06635241f, 0f, 0.18f, -0.1740752f, -0.3398048f, 1.33388f, 0f, 0.18f))
        val DarkTertiary = VibrancyMatrix(floatArrayOf(1.060532f, -0.07796578f, -0.0145662f, 0f, 0.12f, -0.03931839f, 1.022549f, -0.01523062f, 0f, 0.12f, -0.03998281f, -0.07696921f, 1.084952f, 0f, 0.12f))
        val DarkSeparator = VibrancyMatrix(floatArrayOf(1.396216f, -0.5971366f, -0.1150794f, 0f, 0.298f, -0.3035528f, 1.103659f, -0.1161062f, 0f, 0.298f, -0.3045796f, -0.5955964f, 1.584176f, 0f, 0.298f))
    }
}

/** Colour maths for glass, kept free of Android and Compose types so it is unit-tested on the JVM. */
object GlassMath {
    /** Rec. 601 luma, the weighting the macOS colour matrices are built on. */
    fun luma(r: Float, g: Float, b: Float) = 0.299f * r + 0.587f * g + 0.114f * b

    /** Applies [r] to an sRGB colour (0…1 channels): saturation around luma, then the luminance curve. */
    fun material(r: MaterialRecipe, rgb: FloatArray, saturation: Float = r.saturation): FloatArray {
        val l = luma(rgb[0], rgb[1], rgb[2])
        val s = FloatArray(3) { (l + (rgb[it] - l) * saturation) }
        val target = r.mapLuminance(l)
        return FloatArray(3) { (s[it] + (target - l)).coerceIn(0f, 1f) }
    }

    fun relativeLuminance(argb: Int): Float {
        fun ch(c: Int): Float { val v = c / 255f; return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f) }
        return 0.2126f * ch((argb shr 16) and 0xFF) + 0.7152f * ch((argb shr 8) and 0xFF) + 0.0722f * ch(argb and 0xFF)
    }

    fun contrast(a: Int, b: Int): Float {
        val la = relativeLuminance(a); val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    fun argb(rgb: FloatArray, alpha: Float = 1f): Int =
        ((alpha.coerceIn(0f, 1f) * 255 + 0.5f).toInt() shl 24) or
            ((rgb[0].coerceIn(0f, 1f) * 255 + 0.5f).toInt() shl 16) or
            ((rgb[1].coerceIn(0f, 1f) * 255 + 0.5f).toInt() shl 8) or
            (rgb[2].coerceIn(0f, 1f) * 255 + 0.5f).toInt()

    fun rgb(argb: Int) = floatArrayOf(((argb shr 16) and 0xFF) / 255f, ((argb shr 8) and 0xFF) / 255f, (argb and 0xFF) / 255f)

    fun mix(a: FloatArray, b: FloatArray, t: Float) = FloatArray(3) { a[it] + (b[it] - a[it]) * t }

    /** Moves [fg] towards black or white (whichever [bg] contrasts with) until the contrast reaches [target]. */
    fun ensureContrast(fg: Int, bg: Int, target: Float): Int {
        if (contrast(fg, bg) >= target) return fg
        val toward = if (relativeLuminance(bg) > 0.18f) floatArrayOf(0f, 0f, 0f) else floatArrayOf(1f, 1f, 1f)
        val start = rgb(fg)
        var lo = 0f; var hi = 1f
        repeat(14) {
            val mid = (lo + hi) / 2
            if (contrast(argb(mix(start, toward, mid)), bg) >= target) hi = mid else lo = mid
        }
        return argb(mix(start, toward, hi), ((fg ushr 24) and 0xFF) / 255f)
    }

    /**
     * The vibrant label colours for glass whose (opaque) surface colour is [surface].
     * [plain] skips vibrancy and uses neutral greys with the same contrast targets.
     */
    fun vibrant(surface: Int, dark: Boolean, increaseContrast: Boolean, plain: Boolean = false): VibrantColors {
        val s = rgb(surface)
        fun through(m: VibrancyMatrix) = argb(m.apply(s[0], s[1], s[2]))
        val primary: Int; val secondary: Int; val tertiary: Int; val separator: Int
        if (plain) {
            primary = if (dark) 0xFFFFFFFF.toInt() else 0xFF111114.toInt()
            secondary = if (dark) 0xFFB4B4BA.toInt() else 0xFF5E5E66.toInt()
            tertiary = if (dark) 0xFF7C7C82.toInt() else 0xFF9A9AA2.toInt()
            separator = if (dark) 0x33FFFFFF else 0x22000000
        } else if (dark) {
            primary = through(VibrancyMatrix.DarkPrimary); secondary = through(VibrancyMatrix.DarkSecondary)
            tertiary = through(VibrancyMatrix.DarkTertiary); separator = through(VibrancyMatrix.DarkSeparator) and 0x66FFFFFF
        } else {
            primary = through(VibrancyMatrix.LightPrimary); secondary = through(VibrancyMatrix.LightSecondary)
            tertiary = through(VibrancyMatrix.LightTertiary); separator = through(VibrancyMatrix.LightSeparator) and 0x55FFFFFF
        }
        val (p, q, t) = if (increaseContrast) Triple(7f, 4.5f, 3f) else Triple(4.5f, 3f, 1.6f)
        // Secondary text must stay visibly softer than primary, even after the contrast push.
        return VibrantColors(
            primary = ensureContrast(primary, surface, p),
            secondary = ensureContrast(secondary, surface, q),
            tertiary = ensureContrast(tertiary, surface, t),
            separator = separator,
            surface = surface,
        )
    }
}
