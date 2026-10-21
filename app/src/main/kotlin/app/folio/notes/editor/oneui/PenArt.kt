package app.folio.notes.editor.oneui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.folio.core.model.ToolType
import kotlin.math.PI
import kotlin.math.sin

/**
 * Pen illustrations for the tool popups, drawn procedurally: a shaded body (a cylinder lit from
 * the upper left, with a soft specular streak), metal or wood parts, and the parts that take
 * the ink colour (band, nib, tip) tinted live. Folio's own artwork, built the way layered pen
 * art is built (greyscale body + ink-coloured effect layer, see
 * docs/research/samsung-notes-ink-techniques.md) without using anyone's images.
 */
enum class PenArt { FOUNTAIN, BALLPOINT, FINELINER, BRUSH, MARKER, AIRBRUSH, PENCIL, HIGHLIGHTER, HIGHLIGHTER_ROUND, TAPE;

    companion object {
        fun of(tool: ToolType, round: Boolean = false): PenArt = when (tool) {
            ToolType.FOUNTAIN -> FOUNTAIN
            ToolType.BALLPOINT -> BALLPOINT
            ToolType.FINELINER -> FINELINER
            ToolType.BRUSH -> BRUSH
            ToolType.MARKER -> MARKER
            ToolType.AIRBRUSH -> AIRBRUSH
            ToolType.PENCIL -> PENCIL
            ToolType.HIGHLIGHTER -> if (round) HIGHLIGHTER_ROUND else HIGHLIGHTER
            else -> TAPE
        }
    }
}

/**
 * One pen standing in the popup's pen row (36 × 67 dp art in a 46 × 100 dp cell). The selected
 * pen rises 12 dp and shows a squiggle of its stroke; a hovering S Pen lifts it a little.
 */
@Composable
fun PenStand(art: PenArt, ink: Color, selected: Boolean, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var hovered by remember { mutableStateOf(false) }
    val lift = remember { Animatable(if (selected) 1f else 0f) }
    LaunchedEffect(selected, hovered) {
        lift.animateTo(if (selected) 1f else if (hovered) 0.35f else 0f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow))
    }
    Box(
        modifier.width(46.dp).height(100.dp)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type == PointerEventType.Enter) hovered = true
                        if (e.type == PointerEventType.Exit) hovered = false
                    }
                }
            }
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label; this.selected = selected },
    ) {
        Canvas(Modifier.size(46.dp, 100.dp)) {
            val rise = 12.dp.toPx() * lift.value
            val artTop = size.height - 67.dp.toPx() + 14.dp.toPx() - rise
            if (lift.value > 0.6f) drawSquiggle(art, ink, Offset(size.width / 2, artTop), (lift.value - 0.6f) / 0.4f)
            val dx = (size.width - 36.dp.toPx()) / 2
            drawContext.transform.translate(dx, artTop)
            drawPenArt(art, ink, Size(36.dp.toPx(), 67.dp.toPx()))
            drawContext.transform.translate(-dx, -artTop)
        }
    }
}

/** A short sample of the pen's line above its tip, in its colour. */
private fun DrawScope.drawSquiggle(art: PenArt, ink: Color, tip: Offset, alpha: Float) {
    val u = density
    val p = Path()
    val x0 = tip.x + 2f * u; val y0 = tip.y - 4f * u
    p.moveTo(x0, y0)
    val n = 24
    for (i in 1..n) {
        val t = i / n.toFloat()
        p.lineTo(x0 + 20f * u * t, y0 - 9f * u * t + 3.2f * u * sin(t * 2.4f * PI.toFloat()))
    }
    val (w, a, cap) = when (art) {
        PenArt.MARKER -> Triple(3.4f, 1f, StrokeCap.Square)
        PenArt.HIGHLIGHTER -> Triple(5f, 0.55f, StrokeCap.Square)
        PenArt.HIGHLIGHTER_ROUND, PenArt.TAPE -> Triple(5f, 0.55f, StrokeCap.Round)
        PenArt.BRUSH -> Triple(2.6f, 1f, StrokeCap.Round)
        PenArt.AIRBRUSH -> Triple(4f, 0.45f, StrokeCap.Round)
        PenArt.PENCIL -> Triple(1.6f, 0.8f, StrokeCap.Round)
        PenArt.FINELINER -> Triple(1.1f, 1f, StrokeCap.Round)
        else -> Triple(1.6f, 1f, StrokeCap.Round)
    }
    drawPath(p, ink.copy(alpha = ink.alpha * a * alpha), style = Stroke(w * u, cap = cap, join = StrokeJoin.Round))
}

// ------------------------------------------------------------------ the art itself

/** Draws [art] into a box of [box] size (36 × 67 dp proportions), tip at the top. */
fun DrawScope.drawPenArt(art: PenArt, ink: Color, box: Size) {
    val u = box.width / 36f // one design unit = 1 dp at the reference size
    val cx = box.width / 2
    val h = box.height
    fun y(v: Float) = v * u
    fun x(v: Float) = cx + v * u
    when (art) {
        PenArt.FOUNTAIN -> {
            barrel(x(-8.5f), y(31f), 17f * u, h - y(31f), LACQUER_NAVY)
            barrel(x(-8.5f), y(52f), 17f * u, 6f * u, inkShades(ink))
            barrel(x(-8.5f), y(30f), 17f * u, 2.6f * u, GOLD)
            taper(y(22f), y(30f), 11f * u, 13f * u, cx, GLOSS_BLACK)
            val nib = Path().apply {
                moveTo(x(-5.6f), y(22.4f))
                cubicTo(x(-5.6f), y(15f), x(-2.6f), y(9f), cx, y(3f))
                cubicTo(x(2.6f), y(9f), x(5.6f), y(15f), x(5.6f), y(22.4f)); close()
            }
            drawPath(nib, Brush.horizontalGradient(STEEL, startX = x(-5.6f), endX = x(5.6f)))
            drawPath(nib, Color(0x33000000), style = Stroke(0.6f * u))
            drawLine(Color(0xFF3C3E44), Offset(cx, y(5.4f)), Offset(cx, y(15.6f)), 0.8f * u)
            drawCircle(Color(0xFF3C3E44), 1.15f * u, Offset(cx, y(16.6f)))
            val tip = Path().apply { moveTo(x(-1.4f), y(7.2f)); lineTo(cx, y(3f)); lineTo(x(1.4f), y(7.2f)); close() }
            drawPath(tip, ink)
        }
        PenArt.BALLPOINT -> {
            barrel(x(-7.5f), y(26f), 15f * u, h - y(26f), PEARL)
            barrel(x(-7.5f), y(26f), 15f * u, 12f * u, inkShades(ink, 0.92f))
            for (k in 0 until 5) drawLine(Color(0x29000000), Offset(x(-7.5f), y(27.6f + k * 2.2f)), Offset(x(7.5f), y(27.6f + k * 2.2f)), 0.6f * u)
            barrel(x(-7.5f), y(60f), 15f * u, 3f * u, inkShades(ink))
            taper(y(10f), y(26f), 4.6f * u, 15f * u, cx, CHROME)
            barrel(x(-1.3f), y(5.2f), 2.6f * u, 5f * u, CHROME)
            drawCircle(ink.darken(0.7f), 1.35f * u, Offset(cx, y(4.6f)))
        }
        PenArt.FINELINER -> {
            barrel(x(-7f), y(24f), 14f * u, h - y(24f), SATIN_GREY)
            barrel(x(-7f), y(24f), 14f * u, 6f * u, inkShades(ink))
            taper(y(13f), y(24f), 4.4f * u, 14f * u, cx, PLASTIC_GREY)
            barrel(x(-0.9f), y(4f), 1.8f * u, 9.2f * u, CHROME)
            drawCircle(ink, 1f * u, Offset(cx, y(3.8f)))
        }
        PenArt.BRUSH -> {
            barrel(x(-7.5f), y(34f), 15f * u, h - y(34f), MATTE_BLACK)
            taper(y(26f), y(34f), 13f * u, 15f * u, cx, CHROME)
            val tuft = Path().apply {
                moveTo(x(-6f), y(26.2f))
                cubicTo(x(-6.8f), y(18f), x(-3f), y(9f), cx, y(2f))
                cubicTo(x(3f), y(9f), x(6.8f), y(18f), x(6f), y(26.2f)); close()
            }
            drawPath(tuft, Brush.verticalGradient(0f to ink, 0.62f to ink, 1f to Color(0xFF26262B), startY = y(2f), endY = y(26.2f)))
            clipPath(tuft) {
                drawRect(Brush.horizontalGradient(listOf(Color(0x40000000), Color(0x00000000), Color(0x2EFFFFFF), Color(0x00000000), Color(0x4D000000)), startX = x(-6.5f), endX = x(6.5f)))
                for (k in -3..3) drawLine(Color(0x1AFFFFFF), Offset(x(k * 1.4f), y(25f)), Offset(cx + k * 0.3f * u, y(6f)), 0.5f * u)
            }
        }
        PenArt.MARKER -> {
            barrel(x(-10f), y(30f), 20f * u, h - y(30f), inkShades(ink))
            barrel(x(-10f), y(46f), 20f * u, 6f * u, LABEL_WHITE)
            taper(y(24f), y(30f), 15f * u, 16f * u, cx, PLASTIC_DARK)
            val chisel = Path().apply { moveTo(x(-4f), y(24.2f)); lineTo(x(-4f), y(11.4f)); lineTo(x(4f), y(6f)); lineTo(x(4f), y(24.2f)); close() }
            drawPath(chisel, Brush.horizontalGradient(listOf(ink.darken(0.72f), ink, ink.darken(0.82f)), startX = x(-4f), endX = x(4f)))
        }
        PenArt.AIRBRUSH -> {
            barrel(x(-7f), y(22f), 14f * u, h - y(22f), CHROME)
            drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFF34363C), Color(0xFF5A5D65), Color(0xFF2A2C31))), Offset(x(6.2f), y(30f)), Size(4.6f * u, 3.6f * u), CornerRadius(1.6f * u))
            drawRoundRect(Brush.horizontalGradient(listOf(ink.darken(0.75f), ink, ink.darken(0.65f))), Offset(x(6.6f), y(38f)), Size(7f * u, 10f * u), CornerRadius(2.4f * u))
            drawRoundRect(Color(0x33FFFFFF), Offset(x(7.6f), y(39f)), Size(1.4f * u, 8f * u), CornerRadius(0.7f * u))
            taper(y(10f), y(22f), 4f * u, 14f * u, cx, CHROME)
            barrel(x(-1.25f), y(6f), 2.5f * u, 4.4f * u, CHROME)
            drawCircle(Brush.radialGradient(listOf(ink.copy(alpha = 0.55f), ink.copy(alpha = 0f)), center = Offset(cx, y(3.4f)), radius = 6.5f * u), 6.5f * u, Offset(cx, y(3.4f)))
        }
        PenArt.PENCIL -> {
            val w = 17f * u; val left = x(-8.5f)
            // Three visible faces of a hexagonal barrel, lit from the left.
            drawRect(ink.darken(0.8f), Offset(left, y(26f)), Size(w * 0.25f, h - y(26f)))
            drawRect(Brush.horizontalGradient(listOf(ink, ink.lighten(0.22f), ink), startX = left + w * 0.25f, endX = left + w * 0.75f), Offset(left + w * 0.25f, y(26f)), Size(w * 0.5f, h - y(26f)))
            drawRect(ink.darken(0.62f), Offset(left + w * 0.75f, y(26f)), Size(w * 0.25f, h - y(26f)))
            val wood = Path().apply {
                moveTo(left, y(26.6f))
                quadraticTo(left + w * 0.125f, y(24.4f), left + w * 0.25f, y(26.6f))
                quadraticTo(left + w * 0.5f, y(23.4f), left + w * 0.75f, y(26.6f))
                quadraticTo(left + w * 0.875f, y(24.4f), left + w, y(26.6f))
                lineTo(x(2.5f), y(9f)); lineTo(x(-2.5f), y(9f)); close()
            }
            drawPath(wood, Brush.horizontalGradient(listOf(Color(0xFFD6AE7C), Color(0xFFF3D9B2), Color(0xFFE2BF8F), Color(0xFFB98C58)), startX = left, endX = left + w))
            val lead = Path().apply { moveTo(x(-2.5f), y(9.1f)); lineTo(cx, y(2.6f)); lineTo(x(2.5f), y(9.1f)); close() }
            drawPath(lead, Brush.horizontalGradient(listOf(ink.darken(0.55f), ink.darken(0.85f), ink.darken(0.5f)), startX = x(-2.5f), endX = x(2.5f)))
        }
        PenArt.HIGHLIGHTER, PenArt.HIGHLIGHTER_ROUND -> {
            barrel(x(-10f), y(30f), 20f * u, h - y(30f), LIGHT_GREY)
            barrel(x(-10f), y(40f), 20f * u, 16f * u, inkShades(ink.copy(alpha = 1f), 0.95f))
            drawRect(Color(0x26FFFFFF), Offset(x(-10f), y(40f)), Size(20f * u, 1.2f * u))
            taper(y(24f), y(30f), 15f * u, 17f * u, cx, PLASTIC_GREY)
            val tip = if (art == PenArt.HIGHLIGHTER) Path().apply {
                moveTo(x(-5f), y(24.2f)); lineTo(x(-5f), y(12.6f)); lineTo(x(5f), y(7.4f)); lineTo(x(5f), y(24.2f)); close()
            } else Path().apply {
                moveTo(x(-3.6f), y(24.2f)); lineTo(x(-3.6f), y(12.4f))
                cubicTo(x(-3.6f), y(7.4f), x(3.6f), y(7.4f), x(3.6f), y(12.4f))
                lineTo(x(3.6f), y(24.2f)); close()
            }
            drawPath(tip, Brush.horizontalGradient(listOf(ink.darken(0.78f), ink.lighten(0.12f), ink.darken(0.85f)), startX = x(-5f), endX = x(5f)))
        }
        PenArt.TAPE -> {
            // A roll seen from the side: the band in the ink colour round a cardboard core.
            val c = Offset(cx, h - 30f * u)
            drawCircle(Brush.radialGradient(listOf(ink.lighten(0.15f), ink, ink.darken(0.7f)), center = c, radius = 17f * u), 17f * u, c)
            drawCircle(Color(0xFFD9C3A0), 9f * u, c)
            drawCircle(Color(0xFF2A2A2E), 6.2f * u, c)
        }
    }
}

// ---- materials ----
private val LACQUER_NAVY = listOf(Color(0xFF0E1526), Color(0xFF2B3B63), Color(0xFF52648F), Color(0xFF24345A), Color(0xFF0B1220))
private val GOLD = listOf(Color(0xFF7A5A20), Color(0xFFD9B864), Color(0xFFF6E4A6), Color(0xFFC09243), Color(0xFF6E4F18))
private val STEEL = listOf(Color(0xFF6C6F76), Color(0xFFD8DADF), Color(0xFFF7F8FA), Color(0xFFA8ABB2), Color(0xFF5C5F66))
private val CHROME = listOf(Color(0xFF55585F), Color(0xFFB7BAC1), Color(0xFFF2F3F5), Color(0xFF8D9098), Color(0xFF4A4D53))
private val GLOSS_BLACK = listOf(Color(0xFF0C0C0E), Color(0xFF34353A), Color(0xFF606167), Color(0xFF242428), Color(0xFF09090B))
private val MATTE_BLACK = listOf(Color(0xFF141417), Color(0xFF2E2F34), Color(0xFF3E3F45), Color(0xFF26272B), Color(0xFF111114))
private val PEARL = listOf(Color(0xFFB9BDC6), Color(0xFFE9EBF0), Color(0xFFFFFFFF), Color(0xFFDADDE3), Color(0xFFA9AEB8))
private val SATIN_GREY = listOf(Color(0xFF8C9099), Color(0xFFC9CCD3), Color(0xFFE6E8EC), Color(0xFFB4B8C0), Color(0xFF7D818A))
private val PLASTIC_GREY = listOf(Color(0xFF6E727B), Color(0xFFA8ACB5), Color(0xFFC7CAD0), Color(0xFF959AA3), Color(0xFF61656D))
private val PLASTIC_DARK = listOf(Color(0xFF1E1F23), Color(0xFF43454C), Color(0xFF5B5E66), Color(0xFF393B41), Color(0xFF18191C))
private val LIGHT_GREY = listOf(Color(0xFF8E929B), Color(0xFFC4C7CE), Color(0xFFE2E4E8), Color(0xFFB0B4BC), Color(0xFF80848D))
private val LABEL_WHITE = listOf(Color(0xFFC8CBD1), Color(0xFFF1F2F4), Color(0xFFFFFFFF), Color(0xFFE6E7EA), Color(0xFFBEC1C8))

/** A material from a single colour: dark edge, body, highlight, body, deep edge. */
private fun inkShades(c: Color, light: Float = 1f): List<Color> =
    listOf(c.darken(0.55f * light), c.darken(0.92f), c.lighten(0.28f), c, c.darken(0.5f * light))

/** A cylinder section lit from the left, with a specular streak. */
private fun DrawScope.barrel(left: Float, top: Float, width: Float, height: Float, shades: List<Color>) {
    val stops = arrayOf(0f to shades[0], 0.22f to shades[1], 0.36f to shades[2], 0.62f to shades[3], 1f to shades[4])
    drawRect(Brush.horizontalGradient(*stops, startX = left, endX = left + width), Offset(left, top), Size(width, height))
    drawRect(Color.White.copy(alpha = 0.16f), Offset(left + width * 0.3f, top), Size(width * 0.06f, height))
}

/** A frustum (cone section) from [topW] wide at [top] to [bottomW] at [bottom], centred on [cx]. */
private fun DrawScope.taper(top: Float, bottom: Float, topW: Float, bottomW: Float, cx: Float, shades: List<Color>) {
    val p = Path().apply {
        moveTo(cx - bottomW / 2, bottom); lineTo(cx - topW / 2, top); lineTo(cx + topW / 2, top); lineTo(cx + bottomW / 2, bottom); close()
    }
    val stops = arrayOf(0f to shades[0], 0.22f to shades[1], 0.36f to shades[2], 0.62f to shades[3], 1f to shades[4])
    drawPath(p, Brush.horizontalGradient(*stops, startX = cx - bottomW / 2, endX = cx + bottomW / 2))
}

fun Color.darken(f: Float) = Color(red * f, green * f, blue * f, alpha)
fun Color.lighten(f: Float) = Color(red + (1 - red) * f, green + (1 - green) * f, blue + (1 - blue) * f, alpha)
