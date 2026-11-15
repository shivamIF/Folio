package app.folio.notes.editor.oneui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.folio.core.model.ToolType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Folio's editor icons, drawn in code on a 24-unit grid with 1.6-unit round strokes (the
 * weight of the reference toolbar, see the teardown §3). Original artwork: every path below
 * was designed for Folio. Tool icons have two layers: an outline in the icon colour and an
 * "ink" part tinted with the tool's current colour (a pen's nib, a highlighter's tip, tape).
 */
enum class FIcon {
    TEXT_MODE, FOUNTAIN, BALLPOINT, FINELINER, BRUSH, MARKER, AIRBRUSH, PENCIL, HIGHLIGHTER, HIGHLIGHTER_ROUND,
    ERASER, LASSO, RECT_SELECT, UNDO, REDO, SPEN_TO_TEXT, CHANGE_STYLE, TAPE, EASY_PAD, LASER, CUSTOMISE,
    READING_MODE, INSERT, PAGE_SORTER, FULL_SCREEN, EXIT_FULL_SCREEN, MORE, BACK, BOOKMARK, BOOKMARK_FILLED,
    CLOSE, MINUS, PLUS, SHRINK, EXPAND, FAV_PENS, STAR, STAR_FILLED, EYEDROPPER, PALETTE_GRID, CLOCK,
    AUTO_FIX_SHAPES, LOCK_CANVAS, UNLOCK_CANVAS, CLEAN_UP, IMAGE, CAMERA, PDF, STICKY, DRAWING, TABLE, MIC, AUDIO,
    SCAN, GEAR, SPACE, BACKSPACE, ENTER, CHEVRON_LEFT, CHEVRON_RIGHT, CHEVRON_DOWN, CHECK, EYE, EYE_OFF, SEARCH,
    NOTE_STYLE, PAGE_SETTINGS, FOLDER, TAG, SAVE_AS, FINGER, SHARE, PRINT, DELETE, CUT, COPY, PASTE,
    MOVE_FORWARD, MOVE_BACKWARD, DUPLICATE, RECOLOUR, ROTATE, SELECT, ADD_PAGE, FIT, DRAG_HANDLE, PLAY, PAUSE,
    STOP, RECORD, MINIMISE, PAGES, EXPORT_PDF, SYNC, HAND, ERASE_ALL, PAGE_NAV, ;

    companion object {
        /** The toolbar icon of a writing tool. */
        fun of(tool: ToolType, roundHighlighter: Boolean = false): FIcon = when (tool) {
            ToolType.FOUNTAIN -> FOUNTAIN
            ToolType.BALLPOINT -> BALLPOINT
            ToolType.FINELINER -> FINELINER
            ToolType.BRUSH -> BRUSH
            ToolType.MARKER -> MARKER
            ToolType.AIRBRUSH -> AIRBRUSH
            ToolType.PENCIL -> PENCIL
            ToolType.HIGHLIGHTER -> if (roundHighlighter) HIGHLIGHTER_ROUND else HIGHLIGHTER
            else -> TAPE
        }
    }
}

/**
 * Draws [icon] at [size] in [tint]; the tool-colour layer uses [ink] (or [inkBrush], e.g. the
 * laser's rainbow). [contentDescription] null = decorative.
 */
@Composable
fun FolioIcon(
    icon: FIcon,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalOneUi.current.icon,
    ink: Color = tint,
    inkBrush: Brush? = null,
    size: Dp = 24.dp,
) {
    val m = if (contentDescription != null) modifier.semantics { this.contentDescription = contentDescription } else modifier
    Canvas(m.size(size)) { drawFolioIcon(icon, tint, inkBrush ?: SolidColor(ink)) }
}

/** Draws [icon] filling this draw scope (square), for use inside other canvases. */
fun DrawScope.drawFolioIcon(icon: FIcon, tint: Color, ink: Brush) {
    val s = size.minDimension / 24f
    val ox = (size.width - 24f * s) / 2f; val oy = (size.height - 24f * s) / 2f
    val line = SolidColor(tint)
    translateBy(ox, oy) {
        scale(s, s, pivot = Offset.Zero) {
            for (layer in IconBook.layers(icon)) {
                val draw: DrawScope.() -> Unit = {
                    when (layer.kind) {
                        Kind.LINE -> drawPath(layer.path, line, style = layer.stroke)
                        Kind.FILL -> drawPath(layer.path, line, style = Fill)
                        Kind.SOFT -> drawPath(layer.path, line, alpha = 0.32f, style = Fill)
                        Kind.INK -> drawPath(layer.path, ink, style = Fill)
                        Kind.INK_LINE -> drawPath(layer.path, ink, style = layer.stroke)
                        Kind.DASH -> drawPath(layer.path, line, style = layer.stroke)
                    }
                }
                if (layer.rotate != 0f) rotate(layer.rotate, pivot = Offset(12f, 12f), block = draw) else draw()
            }
        }
    }
}

private inline fun DrawScope.translateBy(x: Float, y: Float, block: DrawScope.() -> Unit) {
    drawContext.transform.translate(x, y)
    block()
    drawContext.transform.translate(-x, -y)
}

internal enum class Kind { LINE, FILL, SOFT, INK, INK_LINE, DASH }

internal class IconLayer(val d: String, val kind: Kind, val rotate: Float = 0f, val width: Float = IconBook.STROKE) {
    val path: Path = PathParser().parsePathString(d).toPath()
    fun rotated(deg: Float) = IconLayer(d, kind, deg, width)
    val stroke = Stroke(
        width = width, cap = StrokeCap.Round, join = StrokeJoin.Round,
        pathEffect = if (kind == Kind.DASH) PathEffect.dashPathEffect(floatArrayOf(2.2f, 2.1f)) else null,
    )
}

/** The artwork. Paths are parsed once, on first use. */
internal object IconBook {
    const val STROKE = 1.6f
    private const val THIN = 1.1f
    private const val PEN_TILT = 45f
    private val cache = HashMap<FIcon, List<IconLayer>>()

    fun layers(icon: FIcon): List<IconLayer> = cache.getOrPut(icon) { build(icon) }

    // ---- small path builders (output in SVG path syntax) ----
    private fun n(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else "%.2f".format(java.util.Locale.US, v)
    fun rr(l: Float, t: Float, r: Float, b: Float, rad: Float): String {
        val k = minOf(rad, (r - l) / 2f, (b - t) / 2f)
        if (k <= 0f) return "M${n(l)} ${n(t)}H${n(r)}V${n(b)}H${n(l)}Z"
        return "M${n(l + k)} ${n(t)}H${n(r - k)}A${n(k)} ${n(k)} 0 0 1 ${n(r)} ${n(t + k)}V${n(b - k)}" +
            "A${n(k)} ${n(k)} 0 0 1 ${n(r - k)} ${n(b)}H${n(l + k)}A${n(k)} ${n(k)} 0 0 1 ${n(l)} ${n(b - k)}" +
            "V${n(t + k)}A${n(k)} ${n(k)} 0 0 1 ${n(l + k)} ${n(t)}Z"
    }
    fun circ(cx: Float, cy: Float, r: Float) =
        "M${n(cx - r)} ${n(cy)}A${n(r)} ${n(r)} 0 1 0 ${n(cx + r)} ${n(cy)}A${n(r)} ${n(r)} 0 1 0 ${n(cx - r)} ${n(cy)}Z"
    private fun poly(vararg p: Float): String = buildString {
        for (i in p.indices step 2) append(if (i == 0) "M" else "L").append(n(p[i])).append(' ').append(n(p[i + 1]))
        append('Z')
    }
    private fun star(cx: Float, cy: Float, ro: Float, ri: Float): String {
        val pts = FloatArray(20)
        for (i in 0 until 10) {
            val a = -PI / 2 + i * PI / 5
            val r = if (i % 2 == 0) ro else ri
            pts[2 * i] = cx + r * cos(a).toFloat(); pts[2 * i + 1] = cy + r * sin(a).toFloat()
        }
        return poly(*pts)
    }
    private fun gear(cx: Float, cy: Float, ro: Float, ri: Float, teeth: Int): String {
        val pts = ArrayList<Float>()
        val step = 2 * PI / teeth
        for (k in 0 until teeth) {
            val a = k * step
            for ((da, r) in listOf(-0.36 to ri, -0.2 to ro, 0.2 to ro, 0.36 to ri)) {
                val ang = a + da * step * 1.1
                pts += cx + r * cos(ang).toFloat(); pts += cy + r * sin(ang).toFloat()
            }
        }
        return poly(*pts.toFloatArray())
    }
    private fun dots(vararg p: Float, r: Float) = buildString { for (i in p.indices step 2) append(circ(p[i], p[i + 1], r)) }

    private fun line(d: String, w: Float = STROKE) = IconLayer(d, Kind.LINE, width = w)
    private fun fill(d: String) = IconLayer(d, Kind.FILL)
    private fun soft(d: String) = IconLayer(d, Kind.SOFT)
    private fun ink(d: String) = IconLayer(d, Kind.INK)
    private fun inkLine(d: String, w: Float = STROKE) = IconLayer(d, Kind.INK_LINE, width = w)
    private fun dash(d: String) = IconLayer(d, Kind.DASH)
    /** Layers of an upright pen (tip down), tilted so the tip points to the lower left. */
    private fun tilted(vararg l: IconLayer) = l.map { it.rotated(PEN_TILT) }

    private fun build(icon: FIcon): List<IconLayer> = when (icon) {
        FIcon.TEXT_MODE -> listOf(
            line(rr(2.5f, 5.5f, 21.5f, 18.5f, 2.2f)),
            fill(dots(6.2f, 9.3f, 9.4f, 9.3f, 12.6f, 9.3f, 15.8f, 9.3f, 19f, 9.3f, 6.2f, 12.2f, 9.4f, 12.2f, 12.6f, 12.2f, 15.8f, 12.2f, 19f, 12.2f, r = 0.85f)),
            line("M8 15.4H16"),
        )
        FIcon.FOUNTAIN -> tilted(
            ink("M10 15H14V16.6Q13.6 19.2 12 22Q10.4 19.2 10 16.6Z"),
            line(rr(9.25f, 2f, 14.75f, 12.5f, 1.6f)),
            line("M9.75 12.5L10.25 15H13.75L14.25 12.5"),
            line("M10 15H14V16.6Q13.6 19.2 12 22Q10.4 19.2 10 16.6Z"),
            line("M12 17.3V20.4", THIN),
        )
        FIcon.BALLPOINT -> tilted(
            ink("M9.5 11.4H14.5V15H9.5Z"),
            ink(circ(12f, 21.3f, 0.8f)),
            line(rr(9.5f, 2f, 14.5f, 15f, 2.5f)),
            line("M14.5 4.4H15.8V10.4", THIN),
            line("M9.5 15L11.3 20.2H12.7L14.5 15"),
        )
        FIcon.FINELINER -> tilted(
            ink("M9.75 4.4H14.25V6.6H9.75Z"),
            inkLine("M12 17.6V22", 1.3f),
            line(rr(9.75f, 2f, 14.25f, 14.5f, 1.3f)),
            line("M9.75 14.5L11.2 17.6H12.8L14.25 14.5"),
        )
        FIcon.BRUSH -> tilted(
            ink("M10.4 14.5C10 17 11 19.6 12 22C13 19.6 14 17 13.6 14.5Z"),
            line(rr(9.75f, 2f, 14.25f, 11.5f, 1.6f)),
            line("M9.75 11.5H14.25L13.6 14.5H10.4Z"),
            line("M10.4 14.5C10 17 11 19.6 12 22C13 19.6 14 17 13.6 14.5Z"),
        )
        FIcon.MARKER -> tilted(
            ink("M8.75 9.4H15.25V13.5H8.75Z"),
            ink("M10.4 15.5V19.8L13.6 21.8V15.5Z"),
            line(rr(8.75f, 2f, 15.25f, 13.5f, 1.8f)),
            line("M9.5 13.5V15.5H14.5V13.5"),
            line("M10.4 15.5V19.8L13.6 21.8V15.5Z"),
        )
        FIcon.AIRBRUSH -> tilted(
            ink(dots(12f, 20.4f, 10.4f, 21.8f, 13.6f, 21.8f, 12f, 23f, r = 0.62f)),
            line(rr(9.5f, 4.6f, 14.5f, 15f, 2.5f)),
            line("M10.4 4.6V2.6H13.6V4.6"),
            line("M14.5 7.6H16.6V10.4H14.5"),
            line("M10.6 15L11.4 18H12.6L13.4 15"),
        )
        FIcon.PENCIL -> tilted(
            ink("M9.25 4.4H14.75V14.5H9.25Z"),
            ink("M11 19.3L12 22L13 19.3Z"),
            line("M9.25 2H14.75V14.5H9.25Z"),
            line("M9.25 4.4H14.75", THIN),
            line("M9.25 14.5L12 22L14.75 14.5"),
        )
        FIcon.HIGHLIGHTER -> tilted(
            ink("M8.5 8.2H15.5V12.5H8.5Z"),
            ink("M10.2 15V19.3L13.8 21.3V15Z"),
            line(rr(8.5f, 2f, 15.5f, 12.5f, 2f)),
            line("M9.3 12.5L9.8 15H14.2L14.7 12.5"),
            line("M10.2 15V19.3L13.8 21.3V15Z"),
        )
        FIcon.HIGHLIGHTER_ROUND -> tilted(
            ink("M8.5 8.2H15.5V12.5H8.5Z"),
            ink("M10.4 15V19.4A1.6 1.6 0 0 0 13.6 19.4V15Z"),
            line(rr(8.5f, 2f, 15.5f, 12.5f, 2f)),
            line("M9.3 12.5L9.8 15H14.2L14.7 12.5"),
            line("M10.4 15V19.4A1.6 1.6 0 0 0 13.6 19.4V15Z"),
        )
        FIcon.ERASER -> tilted(
            soft("M8 12.2H16V19A2 2 0 0 1 14 21H10A2 2 0 0 1 8 19Z"),
            line(rr(8f, 3f, 16f, 21f, 2f)),
            line("M8 12.2H16"),
        ) + line("M13.5 21.2H20.5", THIN)
        FIcon.LASSO -> listOf(
            dash("M12 3.8C16.7 3.8 20.2 6.1 20.2 9.2S16.7 14.6 12 14.6 3.8 12.3 3.8 9.2 7.3 3.8 12 3.8Z"),
            line("M7.4 13.8C6.4 15.2 6.7 16.8 8.2 17.6C9.5 18.3 10.3 19.3 10.2 20.8"),
        )
        FIcon.RECT_SELECT -> listOf(
            dash(rr(3.5f, 3.5f, 18f, 15.5f, 1.5f)),
            line("M13.2 11.4L20.4 14.2L17.3 15.5L16 18.6Z"),
        )
        FIcon.UNDO -> listOf(line("M9 5.2L4.4 9.8L9 14.4"), line("M4.4 9.8H14.6A5 5 0 0 1 14.6 19.8H11.2"))
        FIcon.REDO -> listOf(line("M15 5.2L19.6 9.8L15 14.4"), line("M19.6 9.8H9.4A5 5 0 0 0 9.4 19.8H12.8"))
        FIcon.SPEN_TO_TEXT -> listOf(
            line("M2.8 18.8C4.1 15.9 5.8 14.5 6.6 15.8C7.4 17.1 6.3 19 7.6 19.2C8.7 19.3 9.3 17.7 10 16.5"),
            line("M13.2 5H21.2"), line("M17.2 5V15.8"), line("M14.2 19.6H20.2", THIN),
        )
        FIcon.CHANGE_STYLE -> listOf(
            ink(rr(3f, 3.5f, 16.5f, 9.2f, 1.8f)),
            line(rr(3f, 3.5f, 16.5f, 9.2f, 1.8f)),
            line("M16.5 6.35H19A1.5 1.5 0 0 1 20.5 7.85V11.1A1.5 1.5 0 0 1 19 12.6H11.75V15"),
            line(rr(10.25f, 15f, 13.25f, 21.5f, 1.2f)),
        )
        FIcon.TAPE -> tilted(
            ink(TAPE_STRIP),
            line(TAPE_STRIP),
            line("M10.6 6.2V17.8", THIN),
        )
        FIcon.EASY_PAD -> listOf(
            line(rr(3.5f, 2.8f, 11.5f, 8.4f, 1.2f)),
            line("M3.5 8.4L2.6 12M11.5 8.4L21.4 12", THIN),
            line(rr(2.5f, 12f, 21.5f, 21.2f, 2f)),
            inkLine("M5.6 17.6C6.8 15.6 8.1 15.1 8.6 16.3S8.8 18.3 9.8 18.1 11.4 15.5 12.7 15.5 13.7 17.4 14.9 17.4 16.5 16.4 18.4 15.9"),
        )
        FIcon.LASER -> tilted(
            ink(circ(12f, 20f, 1.9f)),
            inkLine(circ(12f, 20f, 3.4f), 0.8f),
            line(rr(9.75f, 2f, 14.25f, 13.5f, 2.2f)),
            line("M9.75 13.5L11.3 16.2H12.7L14.25 13.5"),
            line("M9.75 4.6H14.25", THIN),
        )
        FIcon.CUSTOMISE -> listOf(
            line(circ(7.4f, 7.4f, 3.1f)), line(circ(16.6f, 7.4f, 3.1f)), line(circ(7.4f, 16.6f, 3.1f)),
            line("M16.6 13.2V20M13.2 16.6H20"),
        )
        FIcon.READING_MODE -> listOf(
            line("M12 6.4C10 4.9 7 4.4 3.4 4.9V18.4C7 17.9 10 18.4 12 19.9C14 18.4 17 17.9 20.6 18.4V4.9C17 4.4 14 4.9 12 6.4Z"),
            line("M12 6.4V19.9"),
        )
        FIcon.INSERT, FIcon.PLUS -> listOf(line("M12 4.2V19.8M4.2 12H19.8"))
        FIcon.PAGE_SORTER -> listOf(
            line(rr(4f, 3.2f, 10.8f, 11.2f, 1.4f)), line(rr(13.2f, 3.2f, 20f, 11.2f, 1.4f)),
            line(rr(4f, 12.8f, 10.8f, 20.8f, 1.4f)), line(rr(13.2f, 12.8f, 20f, 20.8f, 1.4f)),
        )
        FIcon.FULL_SCREEN -> listOf(line(
            "M4 9.2V5.6A1.6 1.6 0 0 1 5.6 4H9.2M14.8 4H18.4A1.6 1.6 0 0 1 20 5.6V9.2M20 14.8V18.4A1.6 1.6 0 0 1 18.4 20H14.8M9.2 20H5.6A1.6 1.6 0 0 1 4 18.4V14.8",
        ))
        FIcon.EXIT_FULL_SCREEN -> listOf(line(
            "M9.2 4V7.6A1.6 1.6 0 0 1 7.6 9.2H4M20 9.2H16.4A1.6 1.6 0 0 1 14.8 7.6V4M14.8 20V16.4A1.6 1.6 0 0 1 16.4 14.8H20M4 14.8H7.6A1.6 1.6 0 0 1 9.2 16.4V20",
        ))
        FIcon.MORE -> listOf(fill(dots(12f, 5.4f, 12f, 12f, 12f, 18.6f, r = 1.6f)))
        FIcon.BACK, FIcon.CHEVRON_LEFT -> listOf(line("M15 4.6L7.6 12L15 19.4"))
        FIcon.CHEVRON_RIGHT -> listOf(line("M9 4.6L16.4 12L9 19.4"))
        FIcon.CHEVRON_DOWN -> listOf(line("M5.2 9L12 15.6L18.8 9"))
        FIcon.BOOKMARK -> listOf(line("M7 3.4H17A1 1 0 0 1 18 4.4V20.6L12 16.6L6 20.6V4.4A1 1 0 0 1 7 3.4Z"))
        FIcon.BOOKMARK_FILLED -> listOf(
            ink("M7 3.4H17A1 1 0 0 1 18 4.4V20.6L12 16.6L6 20.6V4.4A1 1 0 0 1 7 3.4Z"),
            inkLine("M7 3.4H17A1 1 0 0 1 18 4.4V20.6L12 16.6L6 20.6V4.4A1 1 0 0 1 7 3.4Z"),
        )
        FIcon.CLOSE -> listOf(line("M6.4 6.4L17.6 17.6M17.6 6.4L6.4 17.6"))
        FIcon.MINUS -> listOf(line("M4.6 12H19.4"))
        FIcon.SHRINK -> listOf(line("M4.2 19.8L10 14M10 14V18.4M10 14H5.6M19.8 4.2L14 10M14 10V5.6M14 10H18.4"))
        FIcon.EXPAND -> listOf(line("M10 14L4.2 19.8M4.2 19.8V15.4M4.2 19.8H8.6M14 10L19.8 4.2M19.8 4.2V8.6M19.8 4.2H15.4"))
        FIcon.FAV_PENS -> listOf(
            line(star(8.6f, 9.4f, 6.4f, 2.7f)),
            line("M14.6 13.2H21M12.8 16.8H21M10.6 20.4H21"),
        )
        FIcon.STAR -> listOf(line(star(12f, 12.8f, 9f, 3.9f)))
        FIcon.STAR_FILLED -> listOf(ink(star(12f, 12.8f, 9f, 3.9f)), inkLine(star(12f, 12.8f, 9f, 3.9f)))
        FIcon.EYEDROPPER -> tilted(
            line(rr(9f, 2f, 15f, 7.4f, 3f)),
            line("M8.4 7.4H15.6"),
            line("M10 7.4V17.2L12 21.4L14 17.2V7.4"),
        )
        FIcon.PALETTE_GRID -> emptyList() // multicoloured; drawn by [drawPaletteGlyph]
        FIcon.CLOCK -> listOf(line(circ(12f, 12f, 8.6f)), line("M12 7.2V12.2L15.4 14.2"))
        FIcon.AUTO_FIX_SHAPES -> listOf(
            line("M3.6 20L8.4 11.4L13.2 20Z"),
            line(circ(16f, 8.2f, 4.2f)),
            fill("M5.6 3.2L6.2 4.8L7.8 5.4L6.2 6L5.6 7.6L5 6L3.4 5.4L5 4.8Z"),
        )
        FIcon.LOCK_CANVAS -> listOf(line(rr(5f, 10.6f, 19f, 20.8f, 2f)), line("M8.2 10.6V7.6A3.8 3.8 0 0 1 15.8 7.6V10.6"), fill(circ(12f, 15.6f, 1.4f)))
        FIcon.UNLOCK_CANVAS -> listOf(line(rr(5f, 10.6f, 19f, 20.8f, 2f)), line("M8.2 10.6V7.6A3.8 3.8 0 0 1 15.6 6.4"), fill(circ(12f, 15.6f, 1.4f)))
        FIcon.CLEAN_UP -> listOf(
            line("M3 9.6C4.4 7.6 5.6 7.2 6.4 8.4S7.2 10.6 8.4 10.2 10.2 7.6 11.6 8.2"),
            line("M3 16.6H13.6"),
            fill("M18.2 3.4L19 5.6L21.2 6.4L19 7.2L18.2 9.4L17.4 7.2L15.2 6.4L17.4 5.6Z"),
            fill("M18.6 13.6L19.1 15L20.5 15.5L19.1 16L18.6 17.4L18.1 16L16.7 15.5L18.1 15Z"),
        )
        FIcon.IMAGE -> listOf(line(rr(3.2f, 4.6f, 20.8f, 19.4f, 2.2f)), line("M3.6 17.4L9 12.2L12.6 15.6L15.2 13.2L20.4 17.8"), line(circ(15.6f, 8.8f, 1.6f)))
        FIcon.CAMERA -> listOf(
            line("M4.6 7.6H8L9.6 5.2H14.4L16 7.6H19.4A1.6 1.6 0 0 1 21 9.2V17.8A1.6 1.6 0 0 1 19.4 19.4H4.6A1.6 1.6 0 0 1 3 17.8V9.2A1.6 1.6 0 0 1 4.6 7.6Z"),
            line(circ(12f, 13.2f, 3.4f)),
        )
        FIcon.PDF -> listOf(line("M6 3H14L19 8V19.6A1.4 1.4 0 0 1 17.6 21H6.4A1.4 1.4 0 0 1 5 19.6V4.4A1.4 1.4 0 0 1 6.4 3Z"), line("M14 3V8H19"), line("M8.4 12.6H15.6M8.4 15.6H15.6M8.4 18.4H12.4", THIN))
        FIcon.STICKY -> listOf(
            line("M4.4 6.2H19.6V14.8L14.4 20H4.4Z"), line("M19.6 14.8H14.4V20"),
            soft("M8.6 3.8H15.4V7.8H8.6Z"),
        )
        FIcon.DRAWING -> listOf(
            line("M12 3.6C6.8 3.6 3.2 7.4 3.2 12S6.8 20.4 11.4 20.4C13.2 20.4 13.6 19 12.8 17.8S12.6 15.4 14.4 15.4H17.4A3.4 3.4 0 0 0 20.8 12C20.8 7.2 16.8 3.6 12 3.6Z"),
            fill(dots(7.6f, 11f, 10.4f, 7.4f, 14.8f, 7.6f, r = 1.25f)),
        )
        FIcon.TABLE -> listOf(line(rr(3.2f, 4.4f, 20.8f, 19.6f, 2f)), line("M3.2 9.6H20.8M3.2 14.6H20.8M9.2 4.4V19.6M15 4.4V19.6", THIN))
        FIcon.MIC -> listOf(line(rr(9f, 3f, 15f, 14.4f, 3f)), line("M5.8 11.2A6.2 6.2 0 0 0 18.2 11.2M12 17.4V21M8.6 21H15.4"))
        FIcon.AUDIO -> listOf(line("M4 10V14M7.6 7V17M11.2 4.4V19.6M14.8 8V16M18.4 10.4V13.6M21.2 11.2V12.8"))
        FIcon.SCAN -> listOf(
            line("M3.6 8.2V5.4A1.8 1.8 0 0 1 5.4 3.6H8.2M15.8 3.6H18.6A1.8 1.8 0 0 1 20.4 5.4V8.2M20.4 15.8V18.6A1.8 1.8 0 0 1 18.6 20.4H15.8M8.2 20.4H5.4A1.8 1.8 0 0 1 3.6 18.6V15.8"),
            line("M3 12H21"),
        )
        FIcon.GEAR -> listOf(line(gear(12f, 12f, 9.2f, 7f, 8)), line(circ(12f, 12f, 2.8f)))
        FIcon.SPACE -> listOf(line("M4 10.6V14.6H20V10.6"))
        FIcon.BACKSPACE -> listOf(line("M8.6 5.4H19.4A1.6 1.6 0 0 1 21 7V17A1.6 1.6 0 0 1 19.4 18.6H8.6L3 12Z"), line("M11.6 9.4L16.6 14.4M16.6 9.4L11.6 14.4"))
        FIcon.ENTER -> listOf(line("M19.4 5V11.4A2 2 0 0 1 17.4 13.4H5"), line("M9 9.4L5 13.4L9 17.4"))
        FIcon.CHECK -> listOf(line("M4.8 12.6L9.6 17.4L19.2 7.4"))
        FIcon.EYE -> listOf(line("M2.6 12C4.8 7.6 8.2 5.4 12 5.4S19.2 7.6 21.4 12C19.2 16.4 15.8 18.6 12 18.6S4.8 16.4 2.6 12Z"), line(circ(12f, 12f, 3.2f)))
        FIcon.EYE_OFF -> listOf(line("M2.6 12C4.8 7.6 8.2 5.4 12 5.4S19.2 7.6 21.4 12C19.2 16.4 15.8 18.6 12 18.6S4.8 16.4 2.6 12Z"), line(circ(12f, 12f, 3.2f)), line("M4 3.6L20 20.4"))
        FIcon.SEARCH -> listOf(line(circ(10.4f, 10.4f, 6.4f)), line("M15.2 15.2L20.6 20.6"))
        FIcon.NOTE_STYLE -> listOf(line(rr(4.6f, 3f, 19.4f, 21f, 2f)), line("M7.6 8H16.4M7.6 11.6H16.4M7.6 15.2H16.4", THIN), soft(rr(4.6f, 3f, 19.4f, 5.6f, 0f)))
        FIcon.PAGE_SETTINGS -> listOf(line("M12.6 21H6.4A1.8 1.8 0 0 1 4.6 19.2V4.8A1.8 1.8 0 0 1 6.4 3H17.6A1.8 1.8 0 0 1 19.4 4.8V11"), line("M7.8 7.6H16.2M7.8 11H12.6", THIN), line(gear(17.6f, 17.6f, 4.2f, 3.1f, 6)))
        FIcon.FOLDER -> listOf(line("M3.4 6.4A1.6 1.6 0 0 1 5 4.8H9.4L11.4 7H19A1.6 1.6 0 0 1 20.6 8.6V17.6A1.6 1.6 0 0 1 19 19.2H5A1.6 1.6 0 0 1 3.4 17.6Z"))
        FIcon.TAG -> listOf(line("M3.6 4.8V11.2L12.8 20.4L20.4 12.8L11.2 3.6H4.8A1.2 1.2 0 0 0 3.6 4.8Z"), fill(circ(7.8f, 7.8f, 1.3f)))
        FIcon.SAVE_AS -> listOf(line("M4.4 15.4V18.6A1.6 1.6 0 0 0 6 20.2H18A1.6 1.6 0 0 0 19.6 18.6V15.4"), line("M12 3.8V15M7.6 10.8L12 15.2L16.4 10.8"))
        FIcon.FINGER -> listOf(line("M9.6 11.2V5.2A1.6 1.6 0 0 1 12.8 5.2V11.4L17 12.4A2 2 0 0 1 18.6 14.6L17.8 18.8A2.6 2.6 0 0 1 15.2 20.8H11.6A2.6 2.6 0 0 1 9.6 19.8L6 15.6A1.6 1.6 0 0 1 8.4 13.4L9.6 14.6"))
        FIcon.HAND -> listOf(line("M9.6 11.2V5.2A1.6 1.6 0 0 1 12.8 5.2V11.4L17 12.4A2 2 0 0 1 18.6 14.6L17.8 18.8A2.6 2.6 0 0 1 15.2 20.8H11.6A2.6 2.6 0 0 1 9.6 19.8L6 15.6A1.6 1.6 0 0 1 8.4 13.4L9.6 14.6"))
        FIcon.SHARE -> listOf(line(circ(17.6f, 5.8f, 2.4f)), line(circ(6.4f, 12f, 2.4f)), line(circ(17.6f, 18.2f, 2.4f)), line("M8.6 10.8L15.4 7M8.6 13.2L15.4 17"))
        FIcon.PRINT -> listOf(line("M6.8 8.4V3.6H17.2V8.4"), line("M6.8 16.6H4.6A1.6 1.6 0 0 1 3 15V10A1.6 1.6 0 0 1 4.6 8.4H19.4A1.6 1.6 0 0 1 21 10V15A1.6 1.6 0 0 1 19.4 16.6H17.2"), line("M6.8 13.4H17.2V20.4H6.8Z"))
        FIcon.DELETE -> listOf(line("M4 6.4H20M9.4 6.4V4.4A1 1 0 0 1 10.4 3.4H13.6A1 1 0 0 1 14.6 4.4V6.4"), line("M6 6.4L7 19.4A1.6 1.6 0 0 0 8.6 20.8H15.4A1.6 1.6 0 0 0 17 19.4L18 6.4"), line("M10.2 10.4V16.8M13.8 10.4V16.8", THIN))
        FIcon.CUT -> listOf(line(circ(6.6f, 17.6f, 2.8f)), line(circ(17.4f, 17.6f, 2.8f)), line("M8.6 15.6L17.6 3.4M15.4 15.6L6.4 3.4"))
        FIcon.COPY -> listOf(line(rr(8.4f, 8.4f, 20.6f, 20.6f, 2f)), line("M15.6 8.4V5A1.6 1.6 0 0 0 14 3.4H5A1.6 1.6 0 0 0 3.4 5V14A1.6 1.6 0 0 0 5 15.6H8.4"))
        FIcon.PASTE -> listOf(line("M8.4 4.8H6.2A1.6 1.6 0 0 0 4.6 6.4V19.4A1.6 1.6 0 0 0 6.2 21H17.8A1.6 1.6 0 0 0 19.4 19.4V6.4A1.6 1.6 0 0 0 17.8 4.8H15.6"), line(rr(8.4f, 3f, 15.6f, 6.6f, 1.2f)))
        FIcon.MOVE_FORWARD -> listOf(soft(rr(8.6f, 3.4f, 20.6f, 15.4f, 2f)), line(rr(8.6f, 3.4f, 20.6f, 15.4f, 2f)), line("M5.6 8.6V18.6A1.6 1.6 0 0 0 7.2 20.2H15.4", THIN))
        FIcon.MOVE_BACKWARD -> listOf(line(rr(3.4f, 8.6f, 15.4f, 20.6f, 2f)), soft(rr(3.4f, 8.6f, 15.4f, 20.6f, 2f)), line("M8.6 5.6H18.6A1.6 1.6 0 0 1 20.2 7.2V15.4", THIN))
        FIcon.DUPLICATE -> listOf(line(rr(8.4f, 8.4f, 20.6f, 20.6f, 2f)), line("M14.5 11.6V17.4M11.6 14.5H17.4"), line("M15.6 5V5A1.6 1.6 0 0 0 14 3.4H5A1.6 1.6 0 0 0 3.4 5V14A1.6 1.6 0 0 0 5 15.6"))
        FIcon.RECOLOUR -> listOf(ink("M12 3.4C12 3.4 5.8 10.2 5.8 14.4A6.2 6.2 0 0 0 18.2 14.4C18.2 10.2 12 3.4 12 3.4Z"), line("M12 3.4C12 3.4 5.8 10.2 5.8 14.4A6.2 6.2 0 0 0 18.2 14.4C18.2 10.2 12 3.4 12 3.4Z"))
        FIcon.ROTATE -> listOf(line("M19.4 12A7.4 7.4 0 1 1 16.6 6.2"), line("M17.4 2.6L17 6.6L13 6.2"))
        FIcon.SELECT -> listOf(line(rr(3.6f, 3.6f, 20.4f, 20.4f, 3f)), line("M7.6 12.2L10.8 15.4L16.6 9"))
        FIcon.ADD_PAGE -> listOf(line("M13 21H6.4A1.4 1.4 0 0 1 5 19.6V4.4A1.4 1.4 0 0 1 6.4 3H14L19 8V12.6"), line("M14 3V8H19"), line("M18 15.2V21.4M14.9 18.3H21.1"))
        FIcon.FIT -> listOf(line(rr(4f, 4f, 20f, 20f, 2f)), line("M8 10.6V8H10.6M13.4 8H16V10.6M16 13.4V16H13.4M10.6 16H8V13.4", THIN))
        FIcon.DRAG_HANDLE -> listOf(line("M5 8.6H19M5 12H19M5 15.4H19"))
        FIcon.PLAY -> listOf(fill("M8 5.2L19 12L8 18.8Z"))
        FIcon.PAUSE -> listOf(fill(rr(6.6f, 5f, 10.2f, 19f, 1f) + rr(13.8f, 5f, 17.4f, 19f, 1f)))
        FIcon.STOP -> listOf(fill(rr(6f, 6f, 18f, 18f, 2f)))
        FIcon.RECORD -> listOf(ink(circ(12f, 12f, 6.4f)))
        FIcon.MINIMISE -> listOf(line("M5.2 14.8L12 8.2L18.8 14.8"))
        FIcon.PAGES -> listOf(line(rr(7.6f, 3f, 20f, 18f, 1.8f)), line("M4 7V19.6A1.4 1.4 0 0 0 5.4 21H15.4"))
        FIcon.EXPORT_PDF -> listOf(line("M13 21H6.4A1.4 1.4 0 0 1 5 19.6V4.4A1.4 1.4 0 0 1 6.4 3H14L19 8V11.4"), line("M14 3V8H19"), line("M18 14V21M15.2 18.4L18 21.2L20.8 18.4"))
        FIcon.SYNC -> listOf(line("M19.6 10.6A7.8 7.8 0 0 0 5.4 8.2M4.4 13.4A7.8 7.8 0 0 0 18.6 15.8"), line("M5 4.2V8.6H9.4M19 19.8V15.4H14.6"))
        FIcon.ERASE_ALL -> listOf(line("M5.2 7H18.8L17.4 20.2H6.6Z"), line("M9 3.6H15"), line("M9.6 11L14.4 16.2M14.4 11L9.6 16.2", THIN))
        FIcon.PAGE_NAV -> listOf(line(rr(3.4f, 3.4f, 9.8f, 20.6f, 1.6f)), line("M13 6.6H20.6M13 12H20.6M13 17.4H20.6"))
    }

    private const val TAPE_STRIP =
        "M8.5 4L9.67 3L10.83 4L12 3L13.17 4L14.33 3L15.5 4V20L14.33 21L13.17 20L12 21L10.83 20L9.67 21L8.5 20Z"
}

/** The palette button: a small grid of coloured dots (Folio's own hues). */
fun DrawScope.drawPaletteGlyph() {
    val s = size.minDimension / 24f
    val colors = listOf(0xFFF0473C, 0xFFF5A623, 0xFFF4D23C, 0xFF35C76B, 0xFF2EB8D9, 0xFF3D6DF2, 0xFF9B5CF0, 0xFFE95BB5, 0xFF8E8E93)
    var k = 0
    for (row in 0 until 3) for (col in 0 until 3) {
        drawCircle(Color(colors[k++]), radius = 2.3f * s, center = Offset((6f + col * 6f) * s, (6f + row * 6f) * s))
    }
}
