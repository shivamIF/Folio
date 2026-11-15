package app.folio.notes.editor.wheel

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.folio.notes.editor.EditorTool
import app.folio.notes.editor.LassoShape
import app.folio.notes.editor.ToolState
import app.folio.notes.editor.oneui.FIcon
import app.folio.notes.editor.oneui.FolioIcon
import app.folio.notes.editor.oneui.LocalOneUi
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Where the pen palette is, which segment the pen is over and which menu is open. Coordinates are the canvas's own pixels. */
@Stable
class PenWheelState {
    var open by mutableStateOf(false); private set
    var x by mutableFloatStateOf(0f); private set
    var y by mutableFloatStateOf(0f); private set
    /** Main segment under the pen while it is still down after opening the palette; -1 for none. */
    var highlighted by mutableIntStateOf(-1)
    /** Main segment whose outer arc (colours, thicknesses) is open; -1 for none. */
    var expanded by mutableIntStateOf(-1)
    /** Set by the palette: view point → main segment; and what choosing a main segment does. */
    internal var hit: (Float, Float) -> Int = { _, _ -> -1 }
    internal var choose: (Int) -> Unit = {}

    fun openAt(vx: Float, vy: Float) { x = vx; y = vy; highlighted = -1; expanded = -1; open = true }
    fun close() { open = false }
    /** The pen moved while still down: light up the segment under it. */
    fun drag(vx: Float, vy: Float) { if (open) highlighted = hit(vx, vy) }
    /** The pen lifted: choose the segment it lifted over, if any. The palette stays open either way. */
    fun release(vx: Float, vy: Float) { if (open) { val i = hit(vx, vy); highlighted = -1; if (i >= 0) choose(i) } }
}

// Two bands around the pen tip: the main arc of bubbles, and outside it the arc a menu opens.
private val R1_IN = 106.dp
private val R1_OUT = 150.dp
private val R2_IN = 158.dp
private val R2_OUT = 202.dp
private val BUBBLE = 40.dp
private val MARGIN = 8.dp

// Motion: bubbles materialise in quick succession along the arc. Each starts small, faint and
// out of focus and snaps clear. Only layer properties animate (no recomposition, no new
// geometry per frame), so it stays smooth.
private const val STAGGER_MS = 26
private const val BUBBLE_MS = 300
private const val CLOSE_MS = 140
/** The palette comes into focus over this long. Kept short: on the Tab S6 Lite each blurred frame costs about 25 ms against 15 ms clear. */
private const val FOCUS_MS = 170
private val BLUR = 10.dp
/** Decelerates all the way in with no bounce. */
private val Butter = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

/**
 * The pen palette overlay: a minimal arc of round frosted-glass bubbles beside the pen,
 * moving on Folio's glass springs.
 * Tap a bubble, or slide the held pen onto it and lift. Colour and thickness open an outer
 * arc with their choices. Choosing keeps the palette open; a tap anywhere else, or the pen's
 * side button, closes it.
 */
@Composable
fun PenWheel(
    state: PenWheelState, tools: ToolState, canUndo: Boolean, canRedo: Boolean,
    display: (Long) -> Long, onPick: (WheelItem) -> Unit, modifier: Modifier = Modifier,
) {
    val c = LocalOneUi.current
    val items = remember(tools) { PenWheelModel.items(tools) }
    val subs = remember(tools, state.expanded) { items.getOrNull(state.expanded)?.let { PenWheelModel.subItems(tools, it) } ?: emptyList() }
    val haptic = LocalHapticFeedback.current
    // Milliseconds since the palette (and the open menu's arc) began to appear; presence drops on close.
    val clock = remember { Animatable(0f) }
    val subClock = remember { Animatable(0f) }
    val presence = remember { Animatable(0f) }
    LaunchedEffect(state.open, state.x, state.y) {
        if (state.open) {
            val total = STAGGER_MS * (items.size - 1) + BUBBLE_MS
            clock.snapTo(0f); presence.snapTo(1f)
            // Let the frame that cancels the pen's stroke and redraws the page pass first, so the
            // animation's own frames aren't dropped behind it.
            androidx.compose.runtime.withFrameNanos { }; androidx.compose.runtime.withFrameNanos { }
            clock.animateTo(total.toFloat(), tween(total, easing = LinearEasing))
        } else presence.animateTo(0f, tween(CLOSE_MS, easing = LinearEasing))
    }
    LaunchedEffect(state.expanded, state.open) {
        subClock.snapTo(0f)
        if (state.expanded >= 0) (STAGGER_MS * 6 + BUBBLE_MS).let { subClock.animateTo(it.toFloat(), tween(it, easing = LinearEasing)) }
    }
    // Stays composed while closed (drawn fully transparent), so opening never pays for building the glass.
    LaunchedEffect(state.highlighted) { if (state.highlighted >= 0) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }

    BoxWithConstraints(modifier.fillMaxSize().testTag("penWheel")) {
        val d = LocalDensity.current
        val r1i = with(d) { R1_IN.toPx() }; val r1o = with(d) { R1_OUT.toPx() }
        val r2i = with(d) { R2_IN.toPx() }; val r2o = with(d) { R2_OUT.toPx() }
        val bubblePx = with(d) { BUBBLE.toPx() }; val blurPx = with(d) { BLUR.toPx() }
        val pad = with(d) { 6.dp.toPx() }
        val (cx, cy) = PenWheelModel.clampCentre(state.x, state.y, r2o, with(d) { MARGIN.toPx() }, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val start = PenWheelModel.facing(state.x, r2o + with(d) { MARGIN.toPx() }) - PenWheelModel.SWEEP / 2f
        val step = PenWheelModel.SWEEP / items.size
        val m1 = (r1i + r1o) / 2f; val m2 = (r2i + r2o) / 2f
        val (subStart, subSweep) = PenWheelModel.subArc(state.expanded.coerceAtLeast(0), items.size, subs.size, start, m1, m2)
        val subStep = if (subs.isEmpty()) 0f else subSweep / subs.size
        val hitMain = { vx: Float, vy: Float -> PenWheelModel.arcSliceAt(vx - cx, vy - cy, items.size, start, PenWheelModel.SWEEP, r1i - pad, r1o + pad) }
        val hitSub = { vx: Float, vy: Float -> if (subs.isEmpty()) -1 else PenWheelModel.arcSliceAt(vx - cx, vy - cy, subs.size, subStart, subSweep, r2i - pad, r2o + pad * 2) }
        fun enabled(i: WheelItem) = when (i) { WheelItem.Undo -> canUndo; WheelItem.Redo -> canRedo; else -> true }
        fun choose(i: Int) {
            val item = items.getOrNull(i) ?: return
            if (!enabled(item)) return
            if (item == WheelItem.ColourMenu || item == WheelItem.SizeMenu) state.expanded = if (state.expanded == i) -1 else i
            else { state.expanded = -1; onPick(item) }
        }
        state.hit = hitMain; state.choose = ::choose
        if (state.open) Box(
            Modifier.fillMaxSize()
                .pointerInput(items, subs, cx, cy) {
                    detectTapGestures { p ->
                        val j = hitSub(p.x, p.y); val i = hitMain(p.x, p.y)
                        when {
                            j >= 0 -> onPick(subs[j])
                            i >= 0 -> choose(i)
                            else -> state.close()
                        }
                    }
                }
        )

        // The palette's own box (a little larger than the outer arc); bubbles are placed around its centre.
        val edge = with(d) { 16.dp.toPx() }
        val lc = r2o + edge
        val frost = remember(c) { Brush.verticalGradient(listOf(lerp(c.popup, Color.White, if (c.dark) 0.16f else 0.5f).copy(alpha = 0.90f), c.popup.copy(alpha = 0.84f))) }
        val rim = remember(c) { Brush.verticalGradient(listOf(Color.White.copy(alpha = if (c.dark) 0.42f else 0.9f), Color.White.copy(alpha = if (c.dark) 0.06f else 0.35f))) }
        val tint = c.accent.copy(alpha = 0.26f)

        /** One round glass bubble at [deg] on radius [r], materialising [order]-th on [time]. */
        @Composable
        fun Bubble(deg: Float, r: Float, order: Int, time: Animatable<Float, *>, on: Boolean, hot: Boolean, content: @Composable () -> Unit) {
            val a = (deg - 90f) * PI.toFloat() / 180f
            // The bubble under the held pen swells on Folio's own glass spring.
            val swell by androidx.compose.animation.core.animateFloatAsState(if (hot) 1.18f else 1f, app.folio.notes.ui.glass.glassSpring(), label = "swell")
            Box(
                Modifier
                    .offset { IntOffset((lc + cos(a) * r - bubblePx / 2).roundToInt(), (lc + sin(a) * r - bubblePx / 2).roundToInt()) }
                    .size(BUBBLE)
                    .graphicsLayer {
                        val t = Butter.transform(((time.value - STAGGER_MS * order) / BUBBLE_MS).coerceIn(0f, 1f))
                        alpha = (t * 1.4f).coerceIn(0f, 1f)
                        val s = (0.55f + 0.45f * t) * swell
                        scaleX = s; scaleY = s
                    }
                    // Frosted glass without live backdrop sampling: measured on the Tab S6 Lite, live-blur
                    // glass took 40-100 ms a frame while animating; this finish holds the frame rate.
                    .shadow(7.dp, CircleShape, clip = false)
                    .background(frost, CircleShape)
                    .border(1.dp, rim, CircleShape)
                    .clip(CircleShape)
                    .then(if (on) Modifier.background(tint) else Modifier),
                contentAlignment = Alignment.Center,
            ) { content() }
        }

        @Composable
        fun Dot(col: Long, chosen: Boolean) = Canvas(Modifier.size(BUBBLE)) {
            drawCircle(Color(display(col).toInt()), 10.dp.toPx())
            drawCircle(Color.White.copy(alpha = 0.6f), 10.dp.toPx(), style = Stroke(1.dp.toPx()))
            if (chosen) drawCircle(c.accent, 15.dp.toPx(), style = Stroke(2.dp.toPx()))
        }

        @Composable
        fun Bar(w: Float) = Canvas(Modifier.size(BUBBLE)) {
            val range = ToolState.sizeRange(tools.sizeTool)
            val f = ((w - range.start) / (range.endInclusive - range.start).coerceAtLeast(0.01f)).coerceIn(0f, 1f)
            drawLine(c.icon, Offset(center.x - 9.dp.toPx(), center.y), Offset(center.x + 9.dp.toPx(), center.y), (2f + 8f * f).dp.toPx(), StrokeCap.Round)
        }

        // One layer for the whole palette: it arrives out of focus and snaps clear (one blur pass
        // for all bubbles), and blurs away again as it closes.
        Box(
            Modifier.offset { IntOffset((cx - lc).roundToInt(), (cy - lc).roundToInt()) }.size(R2_OUT * 2 + 32.dp).graphicsLayer {
                val p = presence.value
                alpha = p
                val focus = Butter.transform((clock.value / FOCUS_MS).coerceIn(0f, 1f))
                val blur = blurPx * maxOf(1f - focus, 1f - p)
                renderEffect = if (p > 0f && blur > 0.5f && android.os.Build.VERSION.SDK_INT >= 31) BlurEffect(blur, blur, androidx.compose.ui.graphics.TileMode.Decal) else null
            },
        ) {
        items.forEachIndexed { i, item ->
            val on = (item is WheelItem.Tool && item.tool == tools.active) || state.expanded == i
            Bubble(start + (i + 0.5f) * step, m1, i, clock, on, state.highlighted == i) {
                when (item) {
                    WheelItem.ColourMenu -> Dot(tools.color, false)
                    WheelItem.SizeMenu -> Bar(tools.width)
                    else -> {
                        val (icon, label) = when (item) {
                            is WheelItem.Tool -> when (item.tool) {
                                EditorTool.PEN -> FIcon.of(tools.pen) to "Pen"
                                EditorTool.HIGHLIGHTER -> FIcon.of(app.folio.core.model.ToolType.HIGHLIGHTER, tools.highlighterRound) to "Highlighter"
                                EditorTool.ERASER -> FIcon.ERASER to "Eraser"
                                else -> (if (tools.lassoShape == LassoShape.RECTANGLE) FIcon.RECT_SELECT else FIcon.LASSO) to "Selection"
                            }
                            WheelItem.Undo -> FIcon.UNDO to "Undo"
                            else -> FIcon.REDO to "Redo"
                        }
                        val ink = when (item) {
                            WheelItem.Tool(EditorTool.PEN) -> Color(display(tools.colorOf(EditorTool.PEN)).toInt())
                            WheelItem.Tool(EditorTool.HIGHLIGHTER) -> Color(display(tools.colorOf(EditorTool.HIGHLIGHTER)).toInt())
                            else -> c.icon
                        }
                        FolioIcon(icon, label, tint = if (enabled(item)) c.icon else c.iconDisabled, ink = if (enabled(item)) ink else c.iconDisabled, size = 24.dp)
                    }
                }
            }
        }
        subs.forEachIndexed { j, item ->
            val chosen = when (item) {
                is WheelItem.Colour -> (item.color or 0xFF000000) == (tools.color or 0xFF000000)
                is WheelItem.Size -> kotlin.math.abs(item.width - tools.width) < 0.01f
                else -> false
            }
            androidx.compose.runtime.key(state.expanded, j) {
                Bubble(subStart + (j + 0.5f) * subStep, m2, j, subClock, chosen && item is WheelItem.Size, false) {
                    when (item) {
                        is WheelItem.Colour -> Dot(item.color, chosen)
                        is WheelItem.Size -> Bar(item.width)
                        else -> Unit
                    }
                }
            }
        }
        }
    }
}
