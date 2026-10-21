package app.folio.notes.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The maths of a magnifying dock row (macOS Dock behaviour), free of Compose so it is
 * unit-tested. Widths are in pixels at rest; the pointer is in rest coordinates (0 = the
 * row's left edge at rest).
 */
object DockMath {
    /** Raised cosine: 1 under the pointer, easing to 0 at [reach] px away. */
    fun falloff(distance: Float, reach: Float): Float {
        if (reach <= 0f) return 0f
        val d = abs(distance) / reach
        return if (d >= 1f) 0f else (0.5 + 0.5 * cos(PI * d)).toFloat()
    }

    class Row(
        /** Visual scale of each cell (≥ 1 for magnified cells). */
        val scales: FloatArray,
        /** Left edge of each cell in the magnified row (0 = the magnified row's left edge). */
        val lefts: FloatArray,
        val width: Float,
        /** Magnified row's left edge relative to the rest row's: keeps the point under the pointer still. */
        val shift: Float,
    )

    fun row(rest: FloatArray, magnifies: BooleanArray, pointer: Float, strength: Float, maxScale: Float, reach: Float): Row {
        val n = rest.size
        val scales = FloatArray(n) { 1f }
        val lefts = FloatArray(n)
        var restLeft = 0f
        var x = 0f
        var anchor = Float.NaN
        val k = (maxScale - 1f) * strength.coerceIn(0f, 1f)
        for (i in 0 until n) {
            val w = rest[i]
            if (magnifies[i] && k > 0f) scales[i] = 1f + k * falloff(restLeft + w / 2 - pointer, reach)
            val m = w * scales[i]
            lefts[i] = x
            if (anchor.isNaN() && pointer < restLeft + w) anchor = if (pointer < restLeft) pointer - restLeft else x + (pointer - restLeft) / w * m
            restLeft += w
            x += m
        }
        if (anchor.isNaN()) anchor = x + (pointer - restLeft)
        return Row(scales, lefts, x, pointer - anchor)
    }
}

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

private class DockLayoutState {
    var rest = FloatArray(0)
    var magnifies = BooleanArray(0)
    var labels = arrayOfNulls<String>(0)
    var restWidth = 0f
    var fit = 1f
    /** The magnified row's left in the dock's coordinates when last placed (for pointer mapping). */
    var rowLeft = 0f
    var restLeft = 0f
}

/**
 * A floating glass toolbar that behaves like the macOS Dock: the cells under the S Pen (hover),
 * mouse or a finger sliding along it magnify with a raised-cosine falloff, pushing their
 * neighbours aside while the cell under the pointer stays put. It shows the hovered cell's name,
 * and, with Auto-hide, collapses into a small handle that opens on hover or tap.
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
    val spec = glassSpec(if (fullWidth) GlassRole.CHROME else GlassRole.DOCK)
    val st = remember { DockLayoutState() }
    val pointer = remember { Animatable(0f) }
    val strength = remember { Animatable(0f) }
    val open = remember { Animatable(if (s.autoHide) 0f else 1f) }
    val scope = rememberCoroutineScope()
    var hovering by remember { mutableStateOf(false) }
    var activity by remember { mutableIntStateOf(0) }
    var labelIndex by remember { mutableIntStateOf(-1) }
    var labelShown by remember { mutableStateOf(false) }
    val holdNow by rememberUpdatedState(hold)
    val settings by rememberUpdatedState(s)

    val cellPx = with(density) { s.dockIconSize.dp.toPx() }
    val maxScale = if (s.magnify) s.magnification else 1f
    val padH = with(density) { (if (fullWidth) 8.dp else 10.dp).toPx() }
    val padV = with(density) { 5.dp.toPx() }
    val top = position == DockPosition.TOP

    // Auto-hide: open while hovered, used or holding a popup; collapse after the delay.
    LaunchedEffect(s.autoHide, hovering, hold, activity) {
        if (!s.autoHide || hovering || hold) { open.animateTo(1f, GlassMotion.spring(s, Spring.StiffnessMedium)); return@LaunchedEffect }
        delay((s.autoHideDelay * 1000).toLong())
        open.animateTo(0f, GlassMotion.spring(s, Spring.StiffnessLow))
    }
    LaunchedEffect(controller?.writingTick) {
        if (controller == null || controller.writingTick == 0) return@LaunchedEffect
        val cur = settings
        if (cur.autoHide && cur.hideWhileWriting && !holdNow) { hovering = false; open.animateTo(0f, GlassMotion.spring(cur, Spring.StiffnessMediumLow)) }
    }
    // The name tag appears after the pen rests on a cell for a moment.
    LaunchedEffect(labelIndex, hovering) {
        labelShown = false
        if (labelIndex >= 0 && hovering && s.dockLabels) { delay(LABEL_DELAY_MS); labelShown = true }
    }
    LaunchedEffect(hold) { if (!hold && !hovering) strength.animateTo(0f, GlassMotion.spring(s, Spring.StiffnessMediumLow)) }

    fun track(localX: Float, kind: PointerType) {
        val restX = (localX + st.rowLeft - st.restLeft) / st.fit
        if (holdNow) return
        hovering = true
        scope.launch { pointer.animateTo(restX, spring(dampingRatio = 1f, stiffness = Spring.StiffnessHigh)) }
        if (strength.targetValue != 1f) scope.launch { strength.animateTo(1f, GlassMotion.follow(settings)) }
        val i = indexAt(st, restX)
        labelIndex = if (kind == PointerType.Touch) -1 else i
    }
    fun leave() {
        hovering = false
        labelIndex = -1
        activity++
        if (!holdNow) scope.launch { strength.animateTo(0f, GlassMotion.spring(settings, Spring.StiffnessMediumLow)) }
    }

    val dockShapeRadius = with(density) { ((cellPx + 2 * padV) * 0.42f).toDp() }
    val pill = RoundedCornerShape(if (fullWidth) 0.dp else dockShapeRadius)

    Layout(
        contents = listOf(
            // 0: the glass under the cells
            { Box(Modifier.glassSurface(pill, if (fullWidth) GlassRole.CHROME else GlassRole.DOCK)) },
            // 1: the cells
            content,
            // 2: the name tag
            {
                val label = st.labels.getOrNull(labelIndex)
                if (labelShown && label != null) DockLabel(label)
            },
            // 3: the collapsed handle
            { if (s.autoHide) DockHandle(onOpen = { activity++; hovering = true; scope.launch { open.animateTo(1f, GlassMotion.spring(settings)) } }) },
        ),
        modifier = modifier
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = e.changes.firstOrNull() ?: continue
                        when (e.type) {
                            PointerEventType.Enter, PointerEventType.Move, PointerEventType.Press ->
                                if (open.value > 0.5f) track(ch.position.x, ch.type)
                            PointerEventType.Exit -> leave()
                            PointerEventType.Release -> { activity++; if (ch.type == PointerType.Touch) leave() }
                            else -> Unit
                        }
                    }
                }
            },
    ) { (bgM, cellMs, labelM, handleM), c ->
        val maxW = if (c.hasBoundedWidth) c.maxWidth else (cellPx * 30).roundToInt()
        val cells = cellMs.map { it.measure(Constraints()) }
        val n = cells.size
        if (st.rest.size != n) { st.rest = FloatArray(n); st.magnifies = BooleanArray(n); st.labels = arrayOfNulls(n) }
        var restW = 0f
        var cellH = 0
        for (i in 0 until n) {
            val cell = cellMs[i].layoutId as? DockCell
            st.rest[i] = cells[i].width.toFloat(); st.magnifies[i] = cell?.magnifies ?: true; st.labels[i] = cell?.label
            restW += cells[i].width; cellH = max(cellH, cells[i].height)
        }
        // Like the Dock: when the row doesn't fit, every cell shrinks until it does.
        val avail = maxW - 2 * padH - (if (fullWidth) 0f else 2 * padH)
        st.fit = if (restW > avail && restW > 0f) max(0.55f, avail / restW) else 1f
        st.restWidth = restW * st.fit
        val e = open.value
        val str = strength.value
        val fitted = FloatArray(n) { st.rest[it] * st.fit }
        val row = DockMath.row(fitted, st.magnifies, pointer.value * st.fit, str, maxScale, cellPx * st.fit * s.magnifySpread)
        val barH = (cellH * st.fit + 2 * padV)
        val overflow = (cellH * st.fit) * ((row.scales.maxOrNull() ?: 1f) - 1f)
        val height = (barH + overflow).roundToInt()
        val totalW = if (fullWidth) maxW.toFloat() else row.width + 2 * padH
        st.restLeft = (maxW - (st.restWidth + 2 * padH)) / 2f
        val wantLeft = if (fullWidth) 0f else st.restLeft + row.shift
        val left = if (fullWidth) 0f else wantLeft.coerceIn(min(0f, maxW - totalW), max(0f, maxW - totalW))
        st.rowLeft = if (fullWidth) st.restLeft + padH + row.shift else left + padH
        val barTop = if (top) 0f else height - barH
        val bg = bgM.first().measure(Constraints.fixed(totalW.roundToInt().coerceAtLeast(1), barH.roundToInt().coerceAtLeast(1)))
        val label = labelM.firstOrNull()?.measure(Constraints())
        val handle = handleM.firstOrNull()?.measure(Constraints())
        layout(maxW, max(height, handle?.height ?: 0)) {
            if (e > 0.01f) {
                val slide = (1f - e) * barH * 0.9f * (if (top) -1f else 1f)
                bg.placeWithLayer(left.roundToInt(), barTop.roundToInt()) {
                    alpha = e; translationY = slide
                    scaleX = 0.6f + 0.4f * e; scaleY = 0.6f + 0.4f * e
                    transformOrigin = TransformOrigin(0.5f, if (top) 0f else 1f)
                }
                for (i in 0 until n) {
                    val sc = row.scales[i] * st.fit
                    val w = cells[i].width; val h = cells[i].height
                    val cx = st.rowLeft + row.lefts[i] + fitted[i] * row.scales[i] / 2f
                    val cy = if (top) barTop + padV + h * st.fit / 2f else barTop + barH - padV - h * st.fit / 2f
                    cells[i].placeWithLayer((cx - w / 2f).roundToInt(), (cy - h / 2f).roundToInt()) {
                        scaleX = sc; scaleY = sc
                        // Grow away from the edge the dock sits on, like Dock icons rising off the bar.
                        translationY = (if (top) 1f else -1f) * (h * sc - h * st.fit) / 2f + slide
                        alpha = e
                    }
                }
                if (label != null && labelIndex in 0 until n) {
                    val i = labelIndex
                    val sc = row.scales[i] * st.fit
                    val cx = st.rowLeft + row.lefts[i] + fitted[i] * row.scales[i] / 2f
                    val gap = 8.dp.roundToPx()
                    val y = if (top) barTop + padV + cells[i].height * sc + gap else barTop + barH - padV - cells[i].height * sc - gap - label.height
                    label.place((cx - label.width / 2f).roundToInt().coerceIn(0, max(0, maxW - label.width)), y.roundToInt())
                }
            }
            if (handle != null && e < 0.99f) {
                handle.placeWithLayer((maxW - handle.width) / 2, if (top) 0 else height - handle.height) { alpha = 1f - e }
            }
        }
    }
}

private const val LABEL_DELAY_MS = 380L

private fun indexAt(st: DockLayoutState, restX: Float): Int {
    var x = 0f
    for (i in st.rest.indices) {
        x += st.rest[i]
        if (restX < x) return if (st.labels[i] != null) i else -1
    }
    return -1
}

/** The Dock's name tag: a small glass capsule with the cell's name. */
@Composable
private fun DockLabel(text: String) {
    val spec = glassSpec(GlassRole.CONTROL)
    Box(Modifier.glassSurface(RoundedCornerShape(12.dp), GlassRole.CONTROL).padding(horizontal = 12.dp, vertical = 5.dp)) {
        Text(text, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium), color = spec.textPrimary, maxLines = 1)
    }
}

/** What's left of an auto-hidden dock: a glass grab bar. Tap or hover to open. */
@Composable
private fun DockHandle(onOpen: () -> Unit) {
    val spec = glassSpec(GlassRole.CONTROL)
    val onOpenNow by rememberUpdatedState(onOpen)
    Box(
        Modifier.size(width = 84.dp, height = 26.dp)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type == PointerEventType.Enter || e.type == PointerEventType.Move) onOpenNow()
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

/** Size of one dock cell (tool button) for the current settings. */
@Composable
fun dockCellSize(): Dp = LocalGlassSettings.current.dockIconSize.dp
