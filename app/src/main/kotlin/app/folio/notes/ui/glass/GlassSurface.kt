package app.folio.notes.ui.glass

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

// ------------------------------------------------------------------ backdrop sources

/**
 * The pixels behind the glass. Two kinds of source can feed it:
 * - Compose content, recorded live into a [GraphicsLayer] ([glassBackdropSource]);
 * - a snapshot bitmap (the editor's page is a SurfaceView, which Compose can't record, so
 *   [SurfaceBackdropSampler] copies it at quarter resolution, macOS's backdropScale 0.25).
 * Glass surfaces sample whichever covers them, in screen coordinates, so a popup window can
 * blur what's in the main window.
 */
@Stable
class GlassBackdrop {
    internal var layer: GraphicsLayer? by mutableStateOf(null)
    internal var layerOrigin by mutableStateOf(Offset.Zero)
    internal var layerSize by mutableStateOf(IntSize.Zero)
    internal var snapshot: ImageBitmap? by mutableStateOf(null)
    internal var snapshotRect by mutableStateOf(Rect.Zero)
    /** Bumped when the snapshot changes, so glass redraws. */
    internal var version by mutableIntStateOf(0)

    val hasContent: Boolean get() = layer != null || snapshot != null

    internal fun setSnapshot(image: ImageBitmap, screenRect: Rect) {
        snapshot = image; snapshotRect = screenRect; version++
    }
}

/** The backdrop glass in this subtree samples; null = no live blur here (frosted tint instead). */
val LocalGlassBackdrop = compositionLocalOf<GlassBackdrop?> { null }

/** Whether glass here is dark glass; null = follow the system theme. */
val LocalGlassDark = staticCompositionLocalOf<Boolean?> { null }

@Composable
fun rememberGlassBackdrop(): GlassBackdrop = remember { GlassBackdrop() }

/**
 * Records this content into [backdrop] (and still draws it). Put it on what glass floats
 * over: the library grid, the wallpaper behind the sidebar.
 */
fun Modifier.glassBackdropSource(backdrop: GlassBackdrop): Modifier = composed {
    val layer = rememberGraphicsLayer()
    this
        .onGloballyPositioned { backdrop.layerOrigin = it.positionOnScreen(); backdrop.layerSize = it.size }
        .drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            if (backdrop.layer !== layer) backdrop.layer = layer
            drawLayer(layer)
        }
}

/**
 * Keeps [backdrop] fed with a quarter-resolution copy of [view] (a SurfaceView Compose can't
 * record). [request] is cheap to call often: copies run on a background thread, at most one
 * in flight and no more than [maxHz] a second, with a trailing copy so the last change shows.
 */
class SurfaceBackdropSampler(
    private val view: SurfaceView,
    private val backdrop: GlassBackdrop,
    private val scale: Float = 0.25f,
    private val maxHz: Int = 24,
) {
    private val thread = HandlerThread("glass-backdrop").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(android.os.Looper.getMainLooper())
    private var buffers = arrayOfNulls<Bitmap>(2)
    private var front = 0
    private var inFlight = false
    private var pending = false
    private var lastAt = 0L
    private var released = false
    private val loc = IntArray(2)

    fun request() {
        if (released) return
        if (inFlight) { pending = true; return }
        val wait = 1000L / maxHz - (SystemClock.uptimeMillis() - lastAt)
        if (wait > 0) {
            if (!pending) { pending = true; main.postDelayed({ pending = false; request() }, wait) }
            return
        }
        val w = view.width; val h = view.height
        if (w <= 0 || h <= 0 || !view.holder.surface.isValid) return
        val bw = max(1, (w * scale).roundToInt()); val bh = max(1, (h * scale).roundToInt())
        val back = 1 - front
        val bmp = buffers[back]?.takeIf { it.width == bw && it.height == bh && !it.isRecycled }
            ?: Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also { buffers[back] = it }
        view.getLocationOnScreen(loc)
        val rect = Rect(loc[0].toFloat(), loc[1].toFloat(), (loc[0] + w).toFloat(), (loc[1] + h).toFloat())
        inFlight = true
        lastAt = SystemClock.uptimeMillis()
        try {
            PixelCopy.request(view, bmp, { result ->
                inFlight = false
                if (released) return@request
                if (result == PixelCopy.SUCCESS) {
                    front = back
                    backdrop.setSnapshot(bmp.asImageBitmap(), rect)
                }
                if (pending) { pending = false; request() }
            }, handler)
        } catch (_: IllegalArgumentException) {
            inFlight = false // surface went away between the check and the copy
        }
    }

    fun release() {
        released = true
        main.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }
}

// ------------------------------------------------------------------ the material for a surface

/** Resolved look of one glass role under the current settings and theme. */
@Stable
class GlassSpec internal constructor(
    val settings: GlassSettings,
    val role: GlassRole,
    val dark: Boolean,
    val recipe: MaterialRecipe,
    val blur: Dp,
    val saturation: Float,
    val luminanceAmount: Float,
    /** Mixed over the shaded backdrop (milkiness + colour tint), straight alpha. */
    val overlay: Color,
    /** Opaque colour of the surface over a typical page: what text is measured against. */
    val surface: Color,
    /** Fill used when nothing is blurred (no backdrop, live blur off, Android ≤ 11). */
    val fallbackFill: Color,
    val elevation: Dp,
    val lens: Dp,
    val lensWidth: Dp,
    val colors: VibrantColors,
) {
    val live: Boolean get() = settings.seeThrough && settings.liveBlur && GlassShader.supported
    val textPrimary get() = Color(colors.primary)
    val textSecondary get() = Color(colors.secondary)
    val textTertiary get() = Color(colors.tertiary)
    val separator get() = Color(colors.separator)
    /** Selected-item platter (selected tool, chosen row). */
    val selection: Color get() = if (dark) Color.White.copy(alpha = 0.16f + 0.06f * settings.frost) else Color.White.copy(alpha = 0.62f)
    /** Hairline that separates glass from a similar backdrop (stronger with Increase contrast). */
    val outline: Color get() = when {
        settings.increaseContrast -> if (dark) Color.White.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.45f)
        dark -> Color.White.copy(alpha = 0.10f)
        else -> Color.Black.copy(alpha = 0.07f)
    }

    companion object {
        /** A typical backdrop for each theme: the page and desk Folio shows. */
        private val lightBackdrop = floatArrayOf(0.93f, 0.93f, 0.94f)
        private val darkBackdrop = floatArrayOf(0.10f, 0.10f, 0.11f)

        fun resolve(s: GlassSettings, role: GlassRole, dark: Boolean): GlassSpec {
            var recipe = MaterialRecipe.ladder(dark, s.frost * 3f + role.ladderBias)
            when (role) {
                GlassRole.DOCK -> recipe = recipe.lerp(if (dark) MaterialRecipe.DockDark else MaterialRecipe.DockLight, 0.6f)
                GlassRole.CHROME -> recipe = recipe.lerp(if (dark) MaterialRecipe.ChromeDark else MaterialRecipe.ChromeLight, 0.5f)
                else -> Unit
            }
            val t = s.transparency
            val blur = (recipe.blurRadius * (0.25f + 0.95f * s.frost)).dp
            val sat = (s.saturation * recipe.saturation / 1.5f).coerceIn(1f, 2.4f)
            val lumAmount = (recipe.luminanceAmount * (0.4f + 1.2f * (1f - t))).coerceIn(0f, 1f)
            val milk = if (dark) Color(0xFF1C1C1E) else Color.White
            val milkA = (1f - t) * (1f - t) * 0.42f
            val tint = Color(s.tint.argb.toInt())
            val tintA = if (s.tint == GlassTint.CLEAR) 0f else 0.10f + 0.14f * (1f - t)
            val overlay = blendOverlay(milk, milkA, tint, tintA)

            val shaded = GlassMath.material(recipe.copy(luminanceAmount = lumAmount), if (dark) darkBackdrop else lightBackdrop, sat)
            val withOverlay = GlassMath.mix(shaded, floatArrayOf(overlay.red, overlay.green, overlay.blue), overlay.alpha)
            val surface = Color(GlassMath.argb(withOverlay))
            val fallbackAlpha = if (!s.seeThrough) 1f else (0.74f + 0.24f * (1f - t)).coerceAtMost(0.98f)
            val colors = GlassMath.vibrant(surface.toArgb(), dark, s.increaseContrast, plain = !s.vibrantText || !s.enabled)
            val elevation = when (role) {
                GlassRole.DOCK -> 14.dp; GlassRole.POPUP -> 16.dp; GlassRole.MENU -> 12.dp
                GlassRole.CHROME -> 2.dp; GlassRole.DIALOG -> 24.dp; GlassRole.CONTROL -> 3.dp
            }
            val lensBase = when (role) { GlassRole.CONTROL -> 3.dp; GlassRole.CHROME -> 5.dp; else -> 9.dp }
            return GlassSpec(
                s, role, dark, recipe, blur, sat, lumAmount, overlay, surface,
                surface.copy(alpha = fallbackAlpha), elevation, lensBase * s.lensing, lensBase * 2.2f, colors,
            )
        }

        private fun blendOverlay(a: Color, aA: Float, b: Color, bA: Float): Color {
            val outA = aA + bA * (1 - aA)
            if (outA <= 0f) return Color.Transparent
            val w = bA / outA
            return Color(a.red + (b.red - a.red) * w, a.green + (b.green - a.green) * w, a.blue + (b.blue - a.blue) * w, outA)
        }
    }
}

/** The spec for [role] under the current settings and theme. */
@Composable
fun glassSpec(role: GlassRole): GlassSpec {
    val s = LocalGlassSettings.current
    val dark = LocalGlassDark.current ?: isSystemInDarkTheme()
    return remember(s, role, dark) { GlassSpec.resolve(s, role, dark) }
}

// ------------------------------------------------------------------ the surface

/**
 * Draws this element as a piece of glass in [shape]: soft shadow, the blurred and shaded
 * backdrop (or a frosted tint when there's no live blur), a milky/colour overlay, the rim
 * highlight, then the content on top. With Reduce transparency it's an opaque tile.
 *
 * Never put it inside the content a [glassBackdropSource] records: it would draw itself.
 */
fun Modifier.glassSurface(
    shape: Shape = RoundedCornerShape(24.dp),
    role: GlassRole = GlassRole.POPUP,
    elevation: Dp? = null,
): Modifier = composed {
    val spec = glassSpec(role)
    val backdrop = LocalGlassBackdrop.current
    val layer = rememberGraphicsLayer()
    val shader = remember { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) GlassShader.newShader() else null }
    var screenPos by remember { mutableStateOf(Offset.Unspecified) }
    val elev = elevation ?: spec.elevation
    this
        .then(if (elev > 0.dp) Modifier.shadow(elev, shape, clip = false, ambientColor = shadowColor(spec), spotColor = shadowColor(spec)) else Modifier)
        .onGloballyPositioned { screenPos = it.positionOnScreen() }
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val clip = outline.toPath()
            val radius = outline.cornerRadius()
            val padPx = (spec.blur.toPx() * 1.2f + spec.lensWidth.toPx()).coerceAtMost(96.dp.toPx())
            val q = spec.recipe.backdropScale
            val layerW = ceil((size.width + 2 * padPx) * q).toInt().coerceAtLeast(1)
            val layerH = ceil((size.height + 2 * padPx) * q).toInt().coerceAtLeast(1)
            var lastParams: GlassShader.Params? = null
            val rimBrush = Brush.linearGradient(
                0f to Color.White.copy(alpha = (if (spec.dark) 0.42f else 0.85f) * spec.settings.highlight),
                0.42f to Color.White.copy(alpha = 0.06f * spec.settings.highlight),
                0.7f to Color.White.copy(alpha = 0.04f * spec.settings.highlight),
                1f to Color.White.copy(alpha = (if (spec.dark) 0.22f else 0.5f) * spec.settings.highlight),
                start = Offset.Zero, end = Offset(size.width, size.height),
            )
            val sheen = Brush.verticalGradient(
                0f to Color.White.copy(alpha = (if (spec.dark) 0.07f else 0.16f) * spec.settings.highlight),
                0.5f to Color.Transparent,
                endY = size.height,
            )
            val rimWidth = 1.1.dp.toPx()
            onDrawWithContent {
                val src = backdrop?.takeIf { spec.live && it.hasContent && screenPos.isSpecified }
                clipPath(clip) {
                    if (src == null) {
                        drawRect(if (spec.settings.seeThrough) spec.fallbackFill else spec.surface)
                    } else {
                        src.version // read so the surface redraws when the snapshot changes
                        val p = GlassShader.Params(
                            blurPx = spec.blur.toPx() * q,
                            rectX = padPx * q, rectY = padPx * q, rectW = size.width * q, rectH = size.height * q, radius = radius * q,
                            saturation = spec.saturation, brightness = spec.recipe.brightness, lum = spec.recipe.luminance,
                            lumAmount = spec.luminanceAmount, lens = spec.lens.toPx() * q, lensWidth = spec.lensWidth.toPx() * q,
                            tintR = spec.overlay.red, tintG = spec.overlay.green, tintB = spec.overlay.blue,
                            tintA = if (shader != null) spec.overlay.alpha else 0f,
                        )
                        if (p != lastParams && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            layer.renderEffect = GlassShader.create(p, shader).asComposeRenderEffect()
                            lastParams = p
                        }
                        val origin = screenPos
                        layer.record(IntSize(layerW, layerH)) {
                            scale(q, q, pivot = Offset.Zero) {
                                translate(padPx - origin.x, padPx - origin.y) { drawBackdrop(src) }
                            }
                        }
                        scale(1f / q, 1f / q, pivot = Offset.Zero) {
                            translate(-padPx * q, -padPx * q) { drawLayer(layer) }
                        }
                        // Android 12 has no shader: lay the overlay on top instead.
                        if (shader == null && spec.overlay.alpha > 0f) drawRect(spec.overlay)
                    }
                    if (spec.settings.enabled && spec.settings.highlight > 0f) drawRect(sheen, size = Size(size.width, size.height * 0.55f))
                }
                if (spec.settings.enabled) {
                    if (spec.settings.highlight > 0f) drawOutline(outline, rimBrush, style = Stroke(rimWidth))
                    drawOutline(outline, spec.outline, style = Stroke(if (spec.settings.increaseContrast) 1.5.dp.toPx() else 0.6.dp.toPx()))
                }
                drawContent()
            }
        }
}

private fun shadowColor(spec: GlassSpec) = if (spec.dark) Color.Black.copy(alpha = 0.75f) else Color(0xFF1B2440).copy(alpha = 0.32f)

/** Draws [src] in screen coordinates (the caller translates so its own top-left is at the origin). */
private fun DrawScope.drawBackdrop(src: GlassBackdrop) {
    val img = src.snapshot
    if (img != null) {
        val r = src.snapshotRect
        drawImage(
            img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
            dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()), dstSize = IntSize(r.width.roundToInt(), r.height.roundToInt()),
            filterQuality = FilterQuality.Low,
        )
    }
    val l = src.layer
    if (l != null) translate(src.layerOrigin.x, src.layerOrigin.y) { drawLayer(l) }
}

private fun Outline.toPath(): Path = when (this) {
    is Outline.Generic -> path
    is Outline.Rectangle -> Path().apply { addRect(rect) }
    is Outline.Rounded -> Path().apply { addRoundRect(roundRect) }
}

private fun Outline.cornerRadius(): Float = when (this) {
    is Outline.Rounded -> roundRect.topLeftCornerRadius.x
    is Outline.Rectangle -> 0f
    is Outline.Generic -> path.getBounds().minDimension / 2f
}

