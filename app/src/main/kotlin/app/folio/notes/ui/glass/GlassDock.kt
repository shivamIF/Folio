package app.folio.notes.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The macOS Dock's magnification, as recovered with REA (docs/research/macos-dock-magnification.md),
 * free of Compose so it is unit-tested. It is a warp of *positions* along the bar: a point u px
 * from the pointer moves by D(u) = R·sin(πu / 2F), saturating at ±R beyond F. Cells take their
 * new size from where their edges land, so the cell under the pointer grows to exactly L, its
 * neighbours less, and the bar widens by up to 2R while the point under the pointer stays put.
 */
object DockMath {
    /** Reach F of the warp: the Dock uses 3 cell widths; Folio's "spread" setting scales it (2.5 → 3). */
    fun reach(cell: Float, spread: Float): Float = cell * spread * 1.2f

    /** Amplitude R so that a cell [cell] wide centred under the pointer is exactly [large] wide at full strength. */
    fun swing(cell: Float, large: Float, reach: Float, strength: Float): Float {
        val k = sin(PI * cell / (4 * reach)).toFloat()
        return if (k <= 0f || large <= cell) 0f else (large - cell) / 2f / k * strength.coerceIn(0f, 1f)
    }

    fun displacement(u: Float, r: Float, f: Float): Float = when {
        r == 0f -> 0f
        u >= f -> r
        u <= -f -> -r
        else -> r * sin(PI * u / (2 * f)).toFloat()
    }

    /** Warped edges (n + 1 of them) of cells with rest widths [rest], in rest-row coordinates. */
    fun edges(rest: FloatArray, pointer: Float, r: Float, f: Float): FloatArray {
        val e = FloatArray(rest.size + 1)
        var x = 0f
        for (i in 0..rest.size) {
            e[i] = x + displacement(x - pointer, r, f)
            if (i < rest.size) x += rest[i]
        }
        return e
    }

    /** The Dock's ramp time for a size change of [gapDp]: floor(42·ln(gap/2 + 1) + 1) ms (≈160 ms for 87 pt). */
    fun rampMillis(gapDp: Float): Int = floor(42.0 * ln(0.5 * gapDp + 1.0) + 1.0).toInt().coerceIn(60, 260)
}

/** The Dock's easing for every bar animation: cosine ease-in-out. */
val DockEase = Easing { p -> (0.5 - 0.5 * cos(PI * p)).toFloat() }

/** Marks a dock cell: its hover [label] (the Dock's name tag) and whether it magnifies. */
data class DockCell(val label: String?, val magnifies: Boolean = true)

fun Modifier.dockCell(label: String?, magnifies: Boolean = true): Modifier = layoutId(DockCell(label, magnifies))

/** Which edge the editor's toolbar dock sits on, for popups that must open away from it. */
val LocalDockEdge = staticCompositionLocalOf<DockPosition?> { null }

/** Signals from outside the dock (the page) that an auto-hiding dock reacts to. */
@Stable
class DockController {
    internal var writingTick by mutableIntStateOf(0)
    /** The pen (or a finger, writing) went down on the page. */
    fun onWriting() { writingTick++ }
}

/**
 * Everything that moves every frame lives here, read only in layout/placement and in effects,
 * never in composition. So hovering re-places cells, but never recomposes the toolbar.
 */
@Stable
private class DockState {
    /** Pointer in fitted rest-row coordinates. */
    val pointer = mutableFloatStateOf(0f)
    val strength = Animatable(0f)
    val open = Animatable(1f)
    var hovering by mutableStateOf(false)
    var labelIndex by mutableIntStateOf(-1)
    var activity by mutableIntStateOf(0)
    // Plain fields written by the layout pass (not snapshot state).
    var rest = FloatArray(0)
    var labels = arrayOfNulls<String>(0)
    var restWidth = 0f
    var reach = 0f
    var swing0 = 0f
    /** Local x of the pointer's rest point minus the pointer, in the layout in effect: rest = local − originX. */
    var originX = 0f
    /** How far the centred dock is shifted (pill style). */
    var offsetX = 0f
}

/**
 * A glass toolbar that behaves like the macOS Dock: the cells under the S Pen (hover), mouse or a
 * finger on it magnify (the Dock's sine warp, cosine-eased in and out over ~120–160 ms), pushing
 * neighbours aside while the cell under the pointer stays put, and the hovered cell's name shows
 * above it at once. With Auto-hide it collapses into a small handle that opens on hover or tap.
 *
 * Each direct child of [content] is one cell; tag it with [dockCell]. [hold] keeps the dock open
 * and magnified as it is (a popup from one of its cells is showing). [fullWidth] draws the glass
 * across the whole width (the "bar" style) instead of as a pill.
 */
@Composable
fun GlassDock(
    position: DockPosition,
    modifier: Modifier = Modifier,
    hold: Boolean = false,
    fullWidth: Boolean = false,
    controller: DockController? = null,
    content: @Composable () -> Unit,
) {
    val s = LocalGlassSettings.current
    val density = LocalDensity.current
    val st = remember { DockState() }
    val scope = rememberCoroutineScope()
    val holdNow = rememberUpdatedState(hold)
    val settings = rememberUpdatedState(s)
    val top = position == DockPosition.TOP

    val cellPx = with(density) { s.dockIconSize.dp.toPx() }
    val maxScale = if (s.magnify) s.magnification else 1f
    val rampMs = DockMath.rampMillis(s.dockIconSize * (maxScale - 1f))
    val padH = with(density) { (if (fullWidth) 8.dp else 10.dp).toPx() }
    val padV = with(density) { 5.dp.toPx() }

    // Auto-hide, without recomposing: open while hovered, used or holding a popup; hide after the delay.
    LaunchedEffect(st) {
        if (!settings.value.autoHide) st.open.snapTo(1f) else st.open.snapTo(0f)
        combine(snapshotFlow { settings.value.autoHide }, snapshotFlow { st.hovering }, snapshotFlow { holdNow.value }, snapshotFlow { st.activity }) { a, h, k, _ -> Triple(a, h, k) }
            .collectLatest { (auto, hov, held) ->
                if (!auto || hov || held) { st.open.animateTo(1f, tween(SHOW_MS, easing = DockEase)); return@collectLatest }
                delay((settings.value.autoHideDelay * 1000).toLong())
                st.open.animateTo(0f, tween(HIDE_MS, easing = DockEase))
            }
    }
    LaunchedEffect(st, controller) {
        if (controller == null) return@LaunchedEffect
        snapshotFlow { controller.writingTick }.drop(1).collect {
            val cur = settings.value
            if (cur.autoHide && cur.hideWhileWriting && !holdNow.value) {
                st.hovering = false; st.labelIndex = -1
                st.open.animateTo(0f, tween(HIDE_MS, easing = DockEase))
            }
        }
    }
    // When a held popup closes and the pen isn't on the dock any more, let the magnification go.
    LaunchedEffect(st) {
        snapshotFlow { holdNow.value }.drop(1).collect { held ->
            if (!held && !st.hovering) st.strength.animateTo(0f, tween(rampMs, easing = DockEase))
        }
    }

    fun track(localX: Float, kind: PointerType) {
        if (holdNow.value) return
        // The point under the pointer never moves, so its rest coordinate is its local x minus the row's origin.
        st.pointer.floatValue = (localX - st.originX).coerceIn(0f, st.restWidth)
        if (!st.hovering) st.hovering = true
        if (st.strength.targetValue != 1f) scope.launch { st.strength.animateTo(1f, tween(rampMs, easing = DockEase)) }
        st.labelIndex = if (kind == PointerType.Touch) -1 else indexAt(st, st.pointer.floatValue)
    }
    fun leave() {
        st.hovering = false
        st.labelIndex = -1
        st.activity++
        if (!holdNow.value) scope.launch { st.strength.animateTo(0f, tween(rampMs, easing = DockEase)) }
    }

    val pillRadius = with(density) { ((cellPx + 2 * padV) * 0.42f).toDp() }
    val shape = remember(fullWidth, pillRadius) { RoundedCornerShape(if (fullWidth) 0.dp else pillRadius) }
    val role = if (fullWidth) GlassRole.CHROME else GlassRole.DOCK
    val bg = remember(shape, role) { @Composable { Box(Modifier.glassSurface(shape, role)) } }
    val label = remember(st) { @Composable { DockLabelSlot(st) } }
    val handle = remember(st) {
        @Composable { DockHandle(onOpen = { st.activity++; st.hovering = true }) }
    }
    val contents = remember(bg, content, label, handle) { listOf(bg, content, label, handle) }

    Layout(
        contents = contents,
        modifier = modifier
            .then(
                // The parent centres the dock; the pill grows around the pointer, so the layout pass
                // works out how far to shift it (read here after that pass, every frame it moves).
                if (fullWidth) Modifier else Modifier.offset {
                    st.strength.value; st.pointer.floatValue; st.open.value
                    IntOffset(st.offsetX.roundToInt(), 0)
                },
            )
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = e.changes.firstOrNull() ?: continue
                        when (e.type) {
                            PointerEventType.Enter, PointerEventType.Move, PointerEventType.Press ->
                                if (st.open.value > 0.5f) track(ch.position.x, ch.type)
                            PointerEventType.Exit -> leave()
                            PointerEventType.Release -> { st.activity++; if (ch.type == PointerType.Touch) leave() }
                            else -> Unit
                        }
                    }
                }
            },
    ) { (bgM, cellMs, labelM, handleM), c ->
        val maxW = if (c.hasBoundedWidth) c.maxWidth else (cellPx * 30).roundToInt()
        val cells = cellMs.map { it.measure(Constraints()) }
        val n = cells.size
        if (st.rest.size != n) { st.rest = FloatArray(n); st.labels = arrayOfNulls(n) }
        var restW = 0f
        var cellH = 0
        for (i in 0 until n) {
            restW += cells[i].width; cellH = max(cellH, cells[i].height)
            st.labels[i] = (cellMs[i].layoutId as? DockCell)?.label
        }
        // Like the Dock: when the row doesn't fit, every cell shrinks until it does.
        val avail = maxW - 4 * padH
        val fit = if (restW > avail && restW > 0f) max(0.55f, avail / restW) else 1f
        for (i in 0 until n) st.rest[i] = cells[i].width * fit
        st.restWidth = restW * fit
        val t = cellPx * fit
        st.reach = DockMath.reach(t, s.magnifySpread)
        st.swing0 = DockMath.swing(t, t * maxScale, st.reach, 1f)

        val e = st.open.value
        val r = st.swing0 * st.strength.value
        val p = st.pointer.floatValue
        val edges = DockMath.edges(st.rest, p, r, st.reach)
        val rowW = edges[n] - edges[0]
        val barH = cellH * fit + 2 * padV
        var peak = 1f
        val magnifies = BooleanArray(n) { (cellMs[it].layoutId as? DockCell)?.magnifies ?: true }
        val scales = FloatArray(n) { i ->
            val w = st.rest[i]
            if (!magnifies[i] || w <= 0f) 1f else ((edges[i + 1] - edges[i]) / w).coerceAtLeast(1f)
        }
        for (i in 0 until n) if (scales[i] > peak) peak = scales[i]
        val overflow = cellH * fit * (peak - 1f)
        val totalW = if (fullWidth) maxW.toFloat() else rowW + 2 * padH
        val height = (barH + overflow).roundToInt()
        val handle = handleM.firstOrNull()?.measure(Constraints())
        val collapsed = e <= 0.01f && handle != null
        // Pill: where the magnified pill wants to be in the parent (the rest pill, centred, moved by
        // the warp of its left edge), kept on screen. Cells pushed past the edge slide off it.
        val margin = 8.dp.toPx()
        val wantLeft = (maxW - (st.restWidth + 2 * padH)) / 2f + edges[0]
        val visLeft = if (fullWidth) 0f else max(margin, wantLeft)
        val visRight = if (fullWidth) maxW.toFloat() else min(maxW - margin, wantLeft + totalW)
        val layoutW = if (collapsed) handle.width else (visRight - visLeft).roundToInt().coerceAtLeast(1)
        val layoutH = if (collapsed) handle.height else max(height, handle?.height ?: 0)
        st.offsetX = if (fullWidth || collapsed) 0f else visLeft - (maxW - layoutW) / 2f
        // Where the warped row starts, in this layout's coordinates.
        val rowLeft = if (fullWidth) (maxW - st.restWidth) / 2f + edges[0] else wantLeft - visLeft + padH
        st.originX = rowLeft - edges[0]
        val barTop = if (top) 0f else height - barH
        val bgP = if (collapsed) null else bgM.first().measure(Constraints.fixed(layoutW, barH.roundToInt().coerceAtLeast(1)))
        val labelP = labelM.firstOrNull()?.measure(Constraints())
        layout(layoutW, layoutH) {
            if (!collapsed) {
                val slide = (1f - e) * barH * 0.9f * (if (top) -1f else 1f)
                bgP?.placeWithLayer(0, barTop.roundToInt()) {
                    alpha = e; translationY = slide
                    val sc = 0.6f + 0.4f * e
                    scaleX = sc; scaleY = sc
                    transformOrigin = TransformOrigin(0.5f, if (top) 0f else 1f)
                }
                for (i in 0 until n) {
                    val sc = scales[i] * fit
                    val w = cells[i].width; val h = cells[i].height
                    val cx = rowLeft + (edges[i] + edges[i + 1]) / 2f - edges[0]
                    val cy = if (top) barTop + padV + h * fit / 2f else barTop + barH - padV - h * fit / 2f
                    cells[i].placeWithLayer((cx - w / 2f).roundToInt(), (cy - h / 2f).roundToInt()) {
                        scaleX = sc; scaleY = sc
                        // Grow away from the edge the dock sits on, like Dock icons rising off the bar.
                        translationY = (if (top) 1f else -1f) * (h * sc - h * fit) / 2f + slide
                        alpha = e
                    }
                }
                val li = st.labelIndex
                if (labelP != null && li in 0 until n) {
                    val sc = scales[li] * fit
                    val cx = rowLeft + (edges[li] + edges[li + 1]) / 2f - edges[0]
                    val gap = 5.dp.roundToPx()
                    val y = if (top) barTop + padV + cells[li].height * sc + gap else barTop + barH - padV - cells[li].height * sc - gap - labelP.height
                    labelP.place((cx - labelP.width / 2f).roundToInt(), y.roundToInt())
                }
            }
            if (handle != null && e < 0.99f) {
                handle.placeWithLayer((layoutW - handle.width) / 2, if (top) 0 else layoutH - handle.height) { alpha = 1f - e }
            }
        }
    }
}

private const val SHOW_MS = 240
private const val HIDE_MS = 300

private fun indexAt(st: DockState, restX: Float): Int {
    var x = 0f
    for (i in st.rest.indices) {
        x += st.rest[i]
        if (restX < x) return if (st.labels[i] != null) i else -1
    }
    return -1
}

/** The name tag slot: the only part that recomposes as the pen moves from cell to cell. */
@Composable
private fun DockLabelSlot(st: DockState) {
    val i = st.labelIndex
    var last by remember { mutableStateOf<String?>(null) }
    val text = st.labels.getOrNull(i)
    if (text != null) last = text
    // Shows at once (the Dock has no delay); fades out over 300 ms when the pen leaves.
    val a by animateFloatAsState(if (text != null && st.hovering && LocalGlassSettings.current.dockLabels) 1f else 0f, tween(if (text != null) 0 else 300, easing = DockEase), label = "dock-label")
    val shown = last
    if (a > 0.01f && shown != null) DockLabel(shown, Modifier.alpha(a))
}

/** The Dock's name tag: a small glass capsule with the cell's name. */
@Composable
private fun DockLabel(text: String, modifier: Modifier = Modifier) {
    val spec = glassSpec(GlassRole.CONTROL)
    Box(modifier.glassSurface(RoundedCornerShape(12.dp), GlassRole.CONTROL).padding(horizontal = 12.dp, vertical = 5.dp)) {
        Text(text, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium), color = spec.textPrimary, maxLines = 1)
    }
}

/** What's left of an auto-hidden dock: a glass grab bar. Hover it (0.15 s, the Dock's reveal delay) or tap to open. */
@Composable
private fun DockHandle(onOpen: () -> Unit) {
    val spec = glassSpec(GlassRole.CONTROL)
    val onOpenNow by rememberUpdatedState(onOpen)
    Box(
        Modifier.size(width = 96.dp, height = 28.dp)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    var enteredAt = -1L
                    while (true) {
                        val e = awaitPointerEvent()
                        val now = e.changes.firstOrNull()?.uptimeMillis ?: 0L
                        when (e.type) {
                            PointerEventType.Enter -> enteredAt = now
                            PointerEventType.Move -> if (enteredAt >= 0 && now - enteredAt >= REVEAL_MS) { enteredAt = -1; onOpenNow() }
                            PointerEventType.Exit -> enteredAt = -1
                            else -> Unit
                        }
                    }
                }
            }
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = "Show toolbar" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(width = 64.dp, height = 12.dp).glassSurface(RoundedCornerShape(6.dp), GlassRole.CONTROL)) {
            Canvas(Modifier.size(width = 64.dp, height = 12.dp)) {
                val w = 36.dp.toPx(); val h = 4.dp.toPx()
                drawRoundRect(spec.textSecondary, Offset((size.width - w) / 2, (size.height - h) / 2), Size(w, h), CornerRadius(h / 2))
            }
        }
    }
}

private const val REVEAL_MS = 150L

/** Size of one dock cell (tool button) for the current settings. */
@Composable
fun dockCellSize(): Dp = LocalGlassSettings.current.dockIconSize.dp
