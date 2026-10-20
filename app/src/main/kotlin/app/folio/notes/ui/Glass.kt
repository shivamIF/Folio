package app.folio.notes.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Folio's "glass" look: translucent frosted surfaces with a bright rim and a soft top
 * sheen, floating over a gently coloured backdrop, and spring physics on everything that
 * moves (presses squish, hovered things lift, popovers bounce in, slider thumbs swell
 * into a lens and stretch with speed).
 */
data class GlassPalette(
    val fill: Color, val fillTop: Color, val rim: Color, val rimLow: Color, val shadow: Color,
    val ink: Color, val inkSoft: Color, val accent: Color, val backdrop: List<Color>,
)

val LocalGlass = staticCompositionLocalOf { lightGlass }

private val lightGlass = GlassPalette(
    fill = Color(0x8CFFFFFF), fillTop = Color(0xD9FFFFFF), rim = Color(0xF2FFFFFF), rimLow = Color(0x33FFFFFF),
    shadow = Color(0x33203050), ink = Color(0xFF1C2433), inkSoft = Color(0xFF5B6475), accent = Color(0xFF3D6FD9),
    backdrop = listOf(Color(0xFFE9EEF8), Color(0xFFF6EBF2), Color(0xFFE6F3F0)),
)
private val darkGlass = GlassPalette(
    fill = Color(0x2EFFFFFF), fillTop = Color(0x47FFFFFF), rim = Color(0x66FFFFFF), rimLow = Color(0x14FFFFFF),
    shadow = Color(0x99000000), ink = Color(0xFFF1F3F8), inkSoft = Color(0xFFAAB2C2), accent = Color(0xFF8DB3FF),
    backdrop = listOf(Color(0xFF141A26), Color(0xFF1E1628), Color(0xFF10201F)),
)

@Composable
fun ProvideGlass(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGlass provides if (isSystemInDarkTheme()) darkGlass else lightGlass, content = content)
}

/** Springs used across the app: a bouncy one for pops, a firmer one for follow-through. */
fun <T> bouncy() = spring<T>(dampingRatio = 0.48f, stiffness = Spring.StiffnessMediumLow)
fun <T> snappy() = spring<T>(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium)

/** A frosted glass surface in [shape]. */
fun Modifier.glass(shape: Shape = RoundedCornerShape(24.dp), elevation: Dp = 14.dp, tint: Color? = null): Modifier = composed {
    val g = LocalGlass.current
    this
        .shadow(elevation, shape, clip = false, ambientColor = g.shadow, spotColor = g.shadow)
        .clip(shape)
        .background(Brush.verticalGradient(listOf(tint?.copy(alpha = 0.35f)?.compositeOver(g.fillTop) ?: g.fillTop, g.fill)))
        .drawWithContent {
            drawContent()
            // Specular sheen across the top, and a rim that's bright above and fades below.
            drawRect(
                Brush.verticalGradient(0f to Color.White.copy(alpha = 0.22f), 0.45f to Color.Transparent),
                size = Size(size.width, size.height * 0.5f),
            )
            val outline = shape.createOutline(size, layoutDirection, this)
            drawOutline(outline, Brush.verticalGradient(listOf(g.rim, g.rimLow)), style = Stroke(width = 1.2.dp.toPx()))
        }
}

private fun Color.compositeOver(bg: Color): Color {
    val a = alpha + bg.alpha * (1 - alpha)
    if (a == 0f) return Color.Transparent
    return Color(
        (red * alpha + bg.red * bg.alpha * (1 - alpha)) / a,
        (green * alpha + bg.green * bg.alpha * (1 - alpha)) / a,
        (blue * alpha + bg.blue * bg.alpha * (1 - alpha)) / a, a,
    )
}

/** Soft colour fields behind the glass (it needs something to be glass over). */
@Composable
fun GlassBackdrop(modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.linearGradient(g.backdrop, start = Offset.Zero, end = Offset(size.width, size.height)))
        drawCircle(Brush.radialGradient(listOf(g.accent.copy(alpha = 0.18f), Color.Transparent), center = Offset(size.width * 0.15f, size.height * 0.1f), radius = size.minDimension * 0.6f), radius = size.minDimension * 0.6f, center = Offset(size.width * 0.15f, size.height * 0.1f))
        drawCircle(Brush.radialGradient(listOf(Color(0x33EE6C4D), Color.Transparent), center = Offset(size.width * 0.9f, size.height * 0.35f), radius = size.minDimension * 0.55f), radius = size.minDimension * 0.55f, center = Offset(size.width * 0.9f, size.height * 0.35f))
        drawCircle(Brush.radialGradient(listOf(Color(0x332A9D8F), Color.Transparent), center = Offset(size.width * 0.35f, size.height * 0.95f), radius = size.minDimension * 0.6f), radius = size.minDimension * 0.6f, center = Offset(size.width * 0.35f, size.height * 0.95f))
    }
}

/**
 * Spring physics for a touchable thing: squishes when pressed, lifts and grows when a pen or
 * mouse hovers it ([hoverScale]), springs back with a little overshoot on release.
 */
fun Modifier.springy(pressedScale: Float = 0.9f, hoverScale: Float = 1.08f, onHover: ((Boolean) -> Unit)? = null): Modifier = composed {
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    var hovered by remember { mutableStateOf(false) }
    val hoverCb by rememberUpdatedState(onHover)
    this
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent()
                    when (e.type) {
                        PointerEventType.Enter -> { hovered = true; hoverCb?.invoke(true); scope.launch { scale.animateTo(hoverScale, bouncy()) } }
                        PointerEventType.Exit -> { hovered = false; hoverCb?.invoke(false); scope.launch { scale.animateTo(1f, bouncy()) } }
                        PointerEventType.Press -> scope.launch { scale.animateTo(pressedScale, snappy()) }
                        PointerEventType.Release -> scope.launch { scale.animateTo(if (hovered) hoverScale else 1f, bouncy()) }
                        else -> Unit
                    }
                }
            }
        }
        .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}

/** A round glass button with an icon. */
@Composable
fun GlassIconButton(
    icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, size: Dp = 44.dp, tint: Color? = null,
) {
    val g = LocalGlass.current
    Box(
        modifier.size(size).springy()
            .clip(CircleShape)
            .then(if (enabled) Modifier.androidxClickable(description, onClick) else Modifier.semantics { contentDescription = description })
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = (tint ?: g.ink).copy(alpha = if (enabled) 1f else 0.3f))
    }
}

/** Clickable with a button role and label, without the Material ripple (glass uses springs). */
fun Modifier.androidxClickable(description: String, onClick: () -> Unit): Modifier = composed {
    this.clickable(
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        indication = null, role = Role.Button, onClick = onClick,
    ).semantics { contentDescription = description }
}

/**
 * A liquid-glass slider: a glass capsule track with a coloured fill and a bead thumb that
 * swells into a clear lens while dragged, stretches with speed and springs into place.
 */
@Composable
fun GlassSlider(
    value: Float, onValueChange: (Float) -> Unit, valueRange: ClosedFloatingPointRange<Float>,
    description: String, modifier: Modifier = Modifier, accent: Color? = null,
) {
    val g = LocalGlass.current
    val fillColor = accent ?: g.accent
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val shown = remember { Animatable(fraction) }
    val grow = remember { Animatable(1f) }
    var stretch by remember { mutableFloatStateOf(1f) }
    val scope = rememberCoroutineScope()
    val onChange by rememberUpdatedState(onValueChange)
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(fraction) { if (!dragging) shown.animateTo(fraction, snappy()) }
    BoxWithConstraints(
        modifier.height(40.dp)
            .semantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange)
                setProgress { v -> onChange(v.coerceIn(valueRange.start, valueRange.endInclusive)); true }
            }
            .pointerInput(valueRange) {
                val thumb = 26.dp.toPx()
                fun toValue(x: Float): Float {
                    val f = ((x - thumb / 2) / (size.width - thumb)).coerceIn(0f, 1f)
                    return valueRange.start + f * span
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    dragging = true
                    scope.launch { grow.animateTo(1.45f, bouncy()) }
                    var lastX = down.position.x; var lastT = down.uptimeMillis
                    onChange(toValue(lastX))
                    scope.launch { shown.animateTo(((lastX - thumb / 2) / (size.width - thumb)).coerceIn(0f, 1f), snappy()) }
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull() ?: break
                        if (!c.pressed) break
                        val x = c.position.x
                        val dt = (c.uptimeMillis - lastT).coerceAtLeast(1)
                        val speed = abs(x - lastX) / dt // px per ms
                        stretch = 1f + (speed / 6f).coerceAtMost(0.35f)
                        lastX = x; lastT = c.uptimeMillis
                        c.consume()
                        onChange(toValue(x))
                        scope.launch { shown.snapTo(((x - thumb / 2) / (size.width - thumb)).coerceIn(0f, 1f)) }
                    }
                    dragging = false
                    stretch = 1f
                    scope.launch { grow.animateTo(1f, bouncy()) }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val density = LocalDensity.current
        val thumbDp = 26.dp
        val travel = maxWidth - thumbDp
        // Track.
        Box(Modifier.padding(horizontal = thumbDp / 2).fillMaxWidth().height(8.dp).glass(CircleShape, elevation = 2.dp))
        Box(
            Modifier.padding(start = thumbDp / 2).width(travel * shown.value).height(8.dp).clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(fillColor.copy(alpha = 0.55f), fillColor))),
        )
        // Thumb: a glass bead, clearer and bigger while held, stretched by speed.
        val lens = (grow.value - 1f) / 0.45f
        Box(
            Modifier.offset { IntOffset(with(density) { (travel * shown.value).toPx() }.roundToInt(), 0) }
                .size(thumbDp)
                .graphicsLayer {
                    scaleX = grow.value * stretch; scaleY = grow.value / (1f + (stretch - 1f) * 0.6f)
                    transformOrigin = TransformOrigin.Center
                }
                .shadow(6.dp, CircleShape, ambientColor = g.shadow, spotColor = g.shadow)
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.98f - 0.6f * lens), Color.White.copy(alpha = 0.85f - 0.6f * lens))))
                .drawWithContent {
                    drawContent()
                    drawCircle(Brush.verticalGradient(listOf(Color.White, Color.White.copy(alpha = 0.2f))), style = Stroke(1.2.dp.toPx()))
                    if (lens > 0f) drawCircle(fillColor.copy(alpha = 0.18f * lens), radius = size.minDimension * 0.28f)
                },
        )
    }
}

/** A glass switch: the knob springs across with a squash. */
@Composable
fun GlassSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    val pos = remember { Animatable(if (checked) 1f else 0f) }
    LaunchedEffect(checked) { pos.animateTo(if (checked) 1f else 0f, bouncy()) }
    Box(
        modifier.width(52.dp).height(30.dp)
            .then(if (onCheckedChange != null) Modifier.androidxClickable(if (checked) "On" else "Off") { onCheckedChange(!checked) } else Modifier)
            .semantics { role = Role.Switch; toggleableState = ToggleableState(checked); stateDescription = if (checked) "On" else "Off" }
            .glass(CircleShape, elevation = 2.dp, tint = if (checked) g.accent else null),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier.offset(x = 3.dp + 22.dp * pos.value).size(24.dp)
                .graphicsLayer { val squash = 1f + 0.25f * (1f - abs(pos.value * 2f - 1f)); scaleX = squash; scaleY = 1f / squash }
                .shadow(4.dp, CircleShape).clip(CircleShape).background(Color.White),
        )
    }
}

/**
 * A glass popover that bounces in from its anchor. Shown while [visible]; tapping outside
 * calls [onDismiss].
 */
@Composable
fun GlassPopover(
    visible: Boolean, onDismiss: () -> Unit, offset: IntOffset = IntOffset(0, 0),
    alignment: Alignment = Alignment.TopStart, modifier: Modifier = Modifier, content: @Composable () -> Unit,
) {
    if (!visible) return
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, bouncy()) }
    Popup(alignment = alignment, offset = offset, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Box(
            modifier
                .graphicsLayer {
                    val s = 0.7f + 0.3f * pop.value
                    scaleX = s; scaleY = s; alpha = pop.value.coerceIn(0f, 1f)
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }
                .padding(12.dp)
                .glass(RoundedCornerShape(28.dp), elevation = 24.dp)
                .padding(18.dp),
        ) { content() }
    }
}

/** A thin glass divider line. */
@Composable
fun GlassDivider(modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    Canvas(modifier.width(1.dp).height(36.dp)) {
        drawRoundRect(Brush.verticalGradient(listOf(Color.Transparent, g.inkSoft.copy(alpha = 0.4f), Color.Transparent)), cornerRadius = CornerRadius(1f))
    }
}
