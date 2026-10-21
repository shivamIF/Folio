package app.folio.notes.editor.oneui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.folio.notes.ui.glass.DockPosition
import app.folio.notes.ui.glass.GlassMotion
import app.folio.notes.ui.glass.GlassRole
import app.folio.notes.ui.glass.glassSpec
import androidx.compose.animation.core.Spring
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import app.folio.notes.ui.glass.LocalDockEdge
import app.folio.notes.ui.glass.LocalGlassSettings
import app.folio.notes.ui.glass.glassSurface
import kotlin.math.roundToInt

// ------------------------------------------------------------------ popup frame

/** Where a popup goes relative to the composable it's attached to. */
enum class PopupPlacement { BELOW_CENTRED, BELOW_END, BELOW_START, ABOVE_CENTRED }

private class AnchoredPosition(
    private val placement: PopupPlacement,
    private val gapPx: Int,
    private val marginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val maxX = maxOf(marginPx, windowSize.width - popupContentSize.width - marginPx)
        val x = when (placement) {
            PopupPlacement.BELOW_END -> anchorBounds.right - popupContentSize.width
            PopupPlacement.BELOW_START -> anchorBounds.left
            else -> anchorBounds.center.x - popupContentSize.width / 2
        }.coerceIn(marginPx, maxX)
        val y = if (placement == PopupPlacement.ABOVE_CENTRED) (anchorBounds.top - gapPx - popupContentSize.height).coerceAtLeast(marginPx)
        else anchorBounds.bottom + gapPx
        return IntOffset(x, y)
    }
}

/**
 * A popup card attached to the composable it's called from: [gap] below it, horizontally
 * centred on it and clamped to the window's edges (teardown §4). An outside tap or Back
 * closes it. Content scrolls when the card would run off the bottom of the window.
 */
@Composable
fun OneUiPopup(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 300.dp,
    gap: Dp = 21.dp,
    placement: PopupPlacement = PopupPlacement.BELOW_CENTRED,
    anchorBottomPx: Int? = null,
    focusable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalOneUi.current
    val density = LocalDensity.current
    val gapPx = with(density) { gap.roundToPx() }
    val marginPx = with(density) { 12.dp.roundToPx() }
    // From a toolbar dock at the bottom of the screen, popups open upwards (anchorBottomPx is then the dock's top).
    val fromBottomDock = LocalDockEdge.current == DockPosition.BOTTOM && placement != PopupPlacement.ABOVE_CENTRED
    val place = if (fromBottomDock) PopupPlacement.ABOVE_CENTRED else placement
    val provider = remember(place, gapPx, marginPx) { AnchoredPosition(place, gapPx, marginPx) }
    val window = LocalWindowInfo.current.containerSize
    // Height left under the anchor (or above a bottom dock); when unknown, most of the window.
    val maxH = with(density) {
        if (fromBottomDock) ((anchorBottomPx ?: window.height) - gapPx - marginPx).coerceAtLeast(160.dp.roundToPx()).toDp()
        else {
            val top = (anchorBottomPx ?: (window.height / 6)) + gapPx
            (window.height - top - marginPx).coerceAtLeast(160.dp.roundToPx()).toDp()
        }
    }
    val glass = LocalGlassSettings.current
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(170, easing = FastOutSlowInEasing)) }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = focusable)) {
        Column(
            modifier
                .graphicsLayer {
                    alpha = appear.value
                    val s = 0.94f + 0.06f * appear.value
                    scaleX = s; scaleY = s
                    transformOrigin = TransformOrigin(0.5f, if (place == PopupPlacement.ABOVE_CENTRED) 1f else 0f)
                }
                .padding(6.dp) // room for the shadow
                .then(
                    if (glass.enabled && glass.glassPopups) Modifier.glassSurface(RoundedCornerShape(26.dp), GlassRole.POPUP).clip(RoundedCornerShape(26.dp))
                    else Modifier.shadow(14.dp, RoundedCornerShape(26.dp), ambientColor = c.shadow, spotColor = c.shadow)
                        .clip(RoundedCornerShape(26.dp)).background(c.popup).border(1.dp, c.popupRim, RoundedCornerShape(26.dp)),
                )
                .width(width)
                .heightIn(max = maxH)
                .verticalScroll(rememberScrollState()),
            content = content,
        )
    }
}

/** A popup's title row (60 dp): [leading] (title text or header icons), [trailing], then the close button. */
@Composable
fun PopupHeader(
    title: String? = null,
    onClose: () -> Unit,
    leading: @Composable RowScope.() -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = LocalOneUi.current
    Row(Modifier.fillMaxWidth().height(60.dp).padding(start = 22.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (title != null) Text(title, style = OneUiType.popupTitle, color = c.text, modifier = Modifier.padding(end = 8.dp))
        leading()
        Spacer(Modifier.weight(1f))
        trailing()
        CloseButton(onClose)
    }
}

@Composable
fun CloseButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalOneUi.current
    Box(
        modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Close", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(c.closeFill), contentAlignment = Alignment.Center) {
            FolioIcon(FIcon.CLOSE, "Close", size = 18.dp)
        }
    }
}

/** A plain icon button (40 dp target) for popup headers and bars. */
@Composable
fun IconAction(
    icon: FIcon,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    tint: Color? = null,
    ink: Color? = null,
    size: Dp = 40.dp,
    iconSize: Dp = 24.dp,
) {
    val c = LocalOneUi.current
    Box(
        modifier.size(size).clip(CircleShape)
            .background(if (selected) c.toolSelected else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        val t = tint ?: if (enabled) c.icon else c.iconDisabled
        FolioIcon(icon, description, tint = t, ink = ink ?: t, size = iconSize)
    }
}

// ------------------------------------------------------------------ sliders

/**
 * One UI-style stepped slider (teardown §4): "−" │ rounded track with ten step dots │ "+",
 * the thumb a circle showing the value. Tap or drag anywhere on the track; − and + step by
 * one and disable at the ends. [track] replaces the plain track (e.g. the opacity gradient);
 * [thumbFill] tints the thumb.
 */
@Composable
fun StepSlider(
    value: Int,
    range: IntRange,
    onValue: (Int) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    track: (DrawScope.(trackSize: Size) -> Unit)? = null,
    thumbFill: Color? = null,
    label: (Int) -> String = { it.toString() },
    onDragEnd: () -> Unit = {},
) {
    val c = LocalOneUi.current
    val glass = LocalGlassSettings.current.enabled
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(value)
    // Glass: the thumb swells into a lens while held and stretches with drag speed.
    val grow = remember { Animatable(1f) }
    var stretch by remember { mutableFloatStateOf(1f) }
    val scope = rememberCoroutineScope()
    val springs = LocalGlassSettings.current
    val glassSpec = glassSpec(GlassRole.CONTROL)
    val set = { v: Int ->
        val nv = v.coerceIn(range.first, range.last)
        if (nv != current) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onValue(nv) }
    }
    Row(modifier.fillMaxWidth().height(56.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconAction(FIcon.MINUS, "Decrease $description", { set(value - 1) }, enabled = value > range.first, size = 36.dp, iconSize = 22.dp)
        var widthPx by remember { mutableFloatStateOf(0f) }
        val thumbR = with(LocalDensity.current) { 14.dp.toPx() }
        fun valueAt(x: Float): Int {
            val span = (widthPx - 2 * thumbR).coerceAtLeast(1f)
            val f = ((x - thumbR) / span).coerceIn(0f, 1f)
            return (range.first + f * (range.last - range.first)).roundToInt()
        }
        val frac = if (range.last > range.first) (value - range.first).toFloat() / (range.last - range.first) else 0f
        val anim by animateFloatAsState(frac, if (glass) GlassMotion.follow(springs) else tween(90), label = "thumb")
        Box(
            Modifier.weight(1f).height(40.dp)
                .onSizeChanged { widthPx = it.width.toFloat() }
                .pointerInput(range) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        set(valueAt(down.position.x))
                        down.consume()
                        if (glass) scope.launch { grow.animateTo(1.32f, GlassMotion.spring(springs, Spring.StiffnessMedium)) }
                        var lastX = down.position.x; var lastT = down.uptimeMillis
                        while (true) {
                            val e = awaitPointerEvent()
                            val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            if (glass) {
                                val speed = kotlin.math.abs(ch.position.x - lastX) / (ch.uptimeMillis - lastT).coerceAtLeast(1)
                                stretch = 1f + (speed / 5f).coerceAtMost(0.3f)
                                lastX = ch.position.x; lastT = ch.uptimeMillis
                            }
                            set(valueAt(ch.position.x)); ch.consume()
                        }
                        stretch = 1f
                        if (glass) scope.launch { grow.animateTo(1f, GlassMotion.spring(springs)) }
                        onDragEnd()
                    }
                }
                .semantics {
                    contentDescription = description
                    stateDescription = label(value)
                    progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), range.first.toFloat()..range.last.toFloat(), range.last - range.first - 1)
                    setProgress { t -> set(t.roundToInt()); true }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                val r = size.height / 2
                if (track != null) track(size) else drawRoundRect(if (glass) glassSpec.separator.copy(alpha = 0.45f) else c.sliderTrack, cornerRadius = CornerRadius(r))
                if (glass) drawGlassGroove(size, r, glassSpec.dark)
                if (track == null) {
                    val span = size.width - 2 * thumbR
                    for (i in 0 until 10) drawCircle(if (glass) glassSpec.textTertiary else c.sliderDot, 1.5.dp.toPx(), Offset(thumbR + span * (i + 0.5f) / 10f, r))
                }
            }
            val thumbX = with(LocalDensity.current) { (thumbR + (widthPx - 2 * thumbR) * anim - thumbR).toDp() }
            if (!glass) Box(
                Modifier.offset(x = thumbX).size(28.dp).shadow(2.dp, CircleShape).clip(CircleShape)
                    .background(thumbFill ?: c.thumb).border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(value), style = OneUiType.thumb, color = if (thumbFill != null && ColorMath.prefersDarkContent(thumbFill.toArgbLong())) Color.Black else c.thumbText)
            } else Box(
                Modifier.offset(x = thumbX).size(28.dp)
                    .graphicsLayer {
                        scaleX = grow.value * stretch; scaleY = grow.value / (1f + (stretch - 1f) * 0.6f)
                        transformOrigin = TransformOrigin.Center
                    }
                    .glassSurface(CircleShape, GlassRole.CONTROL)
                    .then(if (thumbFill != null) Modifier.clip(CircleShape).background(thumbFill.copy(alpha = thumbFill.alpha * (1.2f - 0.55f * (grow.value - 1f) / 0.32f).coerceIn(0.45f, 0.92f))) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                val onFill = thumbFill != null && !ColorMath.prefersDarkContent(thumbFill.toArgbLong())
                Text(label(value), style = OneUiType.thumb, color = if (onFill) Color.White else if (thumbFill != null) Color.Black else glassSpec.textPrimary)
            }
        }
        IconAction(FIcon.PLUS, "Increase $description", { set(value + 1) }, enabled = value < range.last, size = 36.dp, iconSize = 22.dp)
    }
}

/** Opacity slider: a checkerboard fading into [color]; the thumb wears the colour at that opacity. */
@Composable
fun OpacitySlider(percent: Int, color: Color, onValue: (Int) -> Unit, modifier: Modifier = Modifier, range: IntRange = 1..100) {
    StepSlider(
        percent, range, onValue, "Opacity", modifier,
        track = { s -> drawCheckerTrack(s, color) },
        thumbFill = color.copy(alpha = (percent / 100f).coerceIn(0.15f, 1f)).compositeOver(Color(0xFF6E6E72)),
    )
}

/** The glass groove: a soft inner shadow along the top and a bright lip along the bottom. */
private fun DrawScope.drawGlassGroove(s: Size, r: Float, dark: Boolean) {
    drawRoundRect(
        Brush.verticalGradient(0f to Color.Black.copy(alpha = if (dark) 0.35f else 0.16f), 0.5f to Color.Transparent, 1f to Color.White.copy(alpha = if (dark) 0.10f else 0.55f)),
        cornerRadius = CornerRadius(r), size = s,
    )
}

fun DrawScope.drawCheckerTrack(s: Size, color: Color) {
    val r = s.height / 2
    val cell = s.height / 2
    clipRoundRect(s, r) {
        var y = 0f; var row = 0
        while (y < s.height) {
            var x = 0f; var col = 0
            while (x < s.width) {
                drawRect(if ((row + col) % 2 == 0) Color(0xFF9A9A9E) else Color(0xFF3A3A3E), Offset(x, y), Size(cell, cell))
                x += cell; col++
            }
            y += cell; row++
        }
        drawRect(Brush.horizontalGradient(listOf(color.copy(alpha = 0f), color)), size = s)
    }
}

private inline fun DrawScope.clipRoundRect(s: Size, r: Float, block: DrawScope.() -> Unit) {
    val p = androidx.compose.ui.graphics.Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, s.width, s.height, CornerRadius(r))) }
    drawContext.canvas.save()
    drawContext.canvas.clipPath(p)
    block()
    drawContext.canvas.restore()
}

fun Color.compositeOver(bg: Color): Color {
    val a = alpha
    return Color(red * a + bg.red * (1 - a), green * a + bg.green * (1 - a), blue * a + bg.blue * (1 - a), 1f)
}

fun Color.toArgbLong(): Long = (((alpha * 255).roundToInt().toLong() shl 24) or ((red * 255).roundToInt().toLong() shl 16) or
    ((green * 255).roundToInt().toLong() shl 8) or (blue * 255).roundToInt().toLong())

// ------------------------------------------------------------------ switches, radios, pills

@Composable
fun OneUiSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val c = LocalOneUi.current
    val t by animateFloatAsState(if (checked) 1f else 0f, tween(160), label = "switch")
    Canvas(modifier.size(width = 40.dp, height = 24.dp)) {
        val h = 20.dp.toPx(); val w = 36.dp.toPx()
        val left = (size.width - w) / 2; val top = (size.height - h) / 2
        val track = lerp(c.switchOff, c.accent, t)
        drawRoundRect(track, Offset(left, top), Size(w, h), CornerRadius(h / 2))
        val r = 8.dp.toPx()
        val cx = left + h / 2 + (w - h) * t
        drawCircle(lerp(c.switchThumbOff, Color.White, t), r, Offset(cx, top + h / 2))
    }
}

private fun lerp(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, a.alpha + (b.alpha - a.alpha) * t,
)

/** A 48 dp row with a label and a switch; the whole row toggles. */
@Composable
fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, subtitle: String? = null) {
    val c = LocalOneUi.current
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 22.dp).alpha(if (enabled) 1f else 0.4f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(text, style = OneUiType.body, color = c.text)
            if (subtitle != null) Text(subtitle, style = OneUiType.small, color = c.textSoft)
        }
        OneUiSwitch(checked)
    }
}

@Composable
fun RadioDot(selected: Boolean, modifier: Modifier = Modifier) {
    val c = LocalOneUi.current
    Canvas(modifier.size(22.dp)) {
        val r = size.minDimension / 2 - 1.dp.toPx()
        drawCircle(if (selected) c.accent else c.textSoft, r, style = Stroke(2.dp.toPx()))
        if (selected) drawCircle(c.accent, r * 0.52f)
    }
}

/** A 48 dp radio row. */
@Composable
fun RadioRow(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = null, trailing: @Composable () -> Unit = {}) {
    val c = LocalOneUi.current
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.RadioButton, onClick = onClick).semantics { this.selected = selected }
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioDot(selected)
        Column(Modifier.weight(1f).padding(start = 14.dp, top = 6.dp, bottom = 6.dp)) {
            Text(text, style = OneUiType.body, color = c.text)
            if (subtitle != null) Text(subtitle, style = OneUiType.small, color = c.textSoft)
        }
        trailing()
    }
}

/** Equal-width pill tabs (Swatches | Spectrum, Colour | Pattern). */
@Composable
fun PillTabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalOneUi.current
    Row(modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).fillMaxWidth().height(44.dp).clip(RoundedCornerShape(22.dp))
                    .background(if (on) c.pillSelected else Color.Transparent)
                    .border(1.dp, if (on) c.pillBorder.copy(alpha = 0.55f) else Color.Transparent, RoundedCornerShape(22.dp))
                    .clickable(role = Role.Tab) { onSelect(i) }.semantics { this.selected = on },
                contentAlignment = Alignment.Center,
            ) { Text(label, style = if (on) OneUiType.bodyStrong else OneUiType.body, color = c.text) }
        }
    }
}

/** A 57 × 32 dp choice pill (pen line styles). */
@Composable
fun ChoicePill(selected: Boolean, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, content: DrawScope.() -> Unit) {
    val c = LocalOneUi.current
    Box(
        modifier.size(width = 57.dp, height = 32.dp).clip(RoundedCornerShape(16.dp))
            .background(if (selected) c.pillSelected else c.pill)
            .border(1.dp, if (selected) c.pillBorder.copy(alpha = 0.75f) else Color.Transparent, RoundedCornerShape(16.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description; this.selected = selected },
    ) { Canvas(Modifier.matchParentSizeCompat(), onDraw = content) }
}

private fun Modifier.matchParentSizeCompat() = this.then(Modifier.fillMaxWidth().height(32.dp))

/** A dotted separator line, inset like the popup's content. */
@Composable
fun DottedDivider(modifier: Modifier = Modifier, inset: Dp = 22.dp) {
    val c = LocalOneUi.current
    Canvas(modifier.fillMaxWidth().padding(horizontal = inset).height(1.dp)) {
        val step = 4.dp.toPx()
        var x = 0f
        while (x < size.width) { drawCircle(c.textSoft.copy(alpha = 0.55f), 0.6.dp.toPx(), Offset(x, size.height / 2)); x += step }
    }
}

/** Small grey caption above a section. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = OneUiType.label, color = LocalOneUi.current.textSoft, modifier = modifier.padding(horizontal = 22.dp, vertical = 6.dp))
}

/** "Cancel | Done" style text buttons separated by a thin bar. */
@Composable
fun TextActions(vararg actions: Pair<String, () -> Unit>, modifier: Modifier = Modifier, emphasise: Int = -1, destructive: Boolean = false) {
    val c = LocalOneUi.current
    Row(modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        actions.forEachIndexed { i, (label, action) ->
            if (i > 0) Box(Modifier.width(1.dp).height(18.dp).background(c.textSoft.copy(alpha = 0.5f)))
            Box(
                Modifier.weight(1f).height(52.dp).clickable(role = Role.Button, onClick = action),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, style = OneUiType.bodyStrong, textAlign = TextAlign.Center,
                    color = if (i == emphasise) (if (destructive) c.danger else c.accent) else c.text,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ menus and dialogs

class MenuEntry(val label: String, val icon: FIcon? = null, val enabled: Boolean = true, val trailing: String? = null, val onClick: () -> Unit)

/**
 * A rounded menu card: icon + text rows (44 dp), groups separated by dotted lines, and an
 * optional [footer] row of icon-only actions (their labels are the descriptions).
 */
@Composable
fun MenuCard(
    groups: List<List<MenuEntry>>, onDismiss: () -> Unit, width: Dp = 260.dp, placement: PopupPlacement = PopupPlacement.BELOW_END,
    anchorBottomPx: Int? = null, gap: Dp = 8.dp, footer: List<MenuEntry> = emptyList(),
) {
    val c = LocalOneUi.current
    OneUiPopup(onDismiss, width = width, placement = placement, anchorBottomPx = anchorBottomPx, gap = gap) {
        Spacer(Modifier.height(8.dp))
        groups.filter { it.isNotEmpty() }.forEachIndexed { gi, group ->
            if (gi > 0) DottedDivider(Modifier.padding(vertical = 6.dp))
            group.forEach { e ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp)
                        .clickable(enabled = e.enabled, role = Role.Button) { onDismiss(); e.onClick() }
                        .padding(horizontal = 20.dp).alpha(if (e.enabled) 1f else 0.4f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (e.icon != null) {
                        FolioIcon(e.icon, null, size = 22.dp)
                        Spacer(Modifier.width(14.dp))
                    }
                    Text(e.label, style = OneUiType.body, color = c.text, modifier = Modifier.weight(1f))
                    if (e.trailing != null) Text(e.trailing, style = OneUiType.label, color = c.textSoft)
                }
            }
        }
        if (footer.isNotEmpty()) {
            DottedDivider(Modifier.padding(vertical = 6.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                footer.forEach { e ->
                    IconAction(e.icon ?: FIcon.MORE, e.label, { onDismiss(); e.onClick() }, enabled = e.enabled)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** A One UI-style dialog card near the bottom of the screen: title, message, then text actions. */
@Composable
fun OneUiDialog(
    onDismiss: () -> Unit,
    title: String,
    message: String? = null,
    confirmLabel: String,
    onConfirm: () -> Unit,
    destructive: Boolean = false,
    dismissLabel: String = "Cancel",
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = LocalOneUi.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier.widthIn(max = 520.dp).fillMaxWidth()
                    .then(
                        if (LocalGlassSettings.current.let { it.enabled && it.glassDialogs }) Modifier.glassSurface(RoundedCornerShape(26.dp), GlassRole.DIALOG).clip(RoundedCornerShape(26.dp))
                        else Modifier.clip(RoundedCornerShape(26.dp)).background(c.popup),
                    )
                    .padding(top = 22.dp, start = 4.dp, end = 4.dp, bottom = 4.dp),
            ) {
                Text(title, style = OneUiType.popupTitle, color = c.text, modifier = Modifier.padding(horizontal = 22.dp))
                if (message != null) Text(message, style = OneUiType.body, color = c.textSoft, modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp))
                content?.invoke(this)
                TextActions(dismissLabel to onDismiss, confirmLabel to { onDismiss(); onConfirm() }, emphasise = 1, destructive = destructive)
            }
        }
    }
}

/** No ripple, for areas that only need to swallow taps. */
@Composable
fun Modifier.consumeTaps(): Modifier = this.clickable(remember { MutableInteractionSource() }, indication = null) {}
