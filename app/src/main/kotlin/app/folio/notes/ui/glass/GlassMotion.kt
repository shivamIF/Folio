package app.folio.notes.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch

/**
 * Folio's fluid motion: every glass element moves on springs, and Settings › Glass ›
 * Springiness sets how lively they are (0 = critically damped, no overshoot; 1 = bouncy).
 */
object GlassMotion {
    /** Damping for a springiness 0…1. */
    fun damping(springiness: Float) = 1f - 0.55f * springiness.coerceIn(0f, 1f)

    fun <T> spring(s: GlassSettings, stiffness: Float = Spring.StiffnessMediumLow): SpringSpec<T> =
        spring(dampingRatio = damping(s.springiness), stiffness = stiffness)

    /** A firmer spring for things that must keep up with the finger (magnification, drags). */
    fun <T> follow(s: GlassSettings): SpringSpec<T> =
        spring(dampingRatio = (damping(s.springiness) + 0.25f).coerceAtMost(1f), stiffness = Spring.StiffnessMedium)
}

/** The settings' spring, for `animate*AsState(animationSpec = glassSpring())`. */
@Composable
fun <T> glassSpring(stiffness: Float = Spring.StiffnessMediumLow): SpringSpec<T> {
    val s = LocalGlassSettings.current
    return remember(s.springiness, stiffness) { GlassMotion.spring(s, stiffness) }
}

/**
 * Squish on press and lift on hover (S Pen, mouse), springing back with the settings'
 * bounciness. It only observes pointers (never consumes them), so clicks still work.
 */
fun Modifier.glassPressable(pressedScale: Float = 0.92f, hoverScale: Float = 1.04f, onHover: ((Boolean) -> Unit)? = null): Modifier = composed {
    val s = LocalGlassSettings.current
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    var hovered by remember { mutableStateOf(false) }
    val hoverCb by rememberUpdatedState(onHover)
    val settings by rememberUpdatedState(s)
    this
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    val spec = GlassMotion.spring<Float>(settings, Spring.StiffnessMedium)
                    when (e.type) {
                        PointerEventType.Enter -> { hovered = true; hoverCb?.invoke(true); scope.launch { scale.animateTo(hoverScale, spec) } }
                        PointerEventType.Exit -> { hovered = false; hoverCb?.invoke(false); scope.launch { scale.animateTo(1f, spec) } }
                        PointerEventType.Press -> scope.launch { scale.animateTo(pressedScale, GlassMotion.follow(settings)) }
                        PointerEventType.Release -> scope.launch { scale.animateTo(if (hovered) hoverScale else 1f, spec) }
                        else -> Unit
                    }
                }
            }
        }
        .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}
