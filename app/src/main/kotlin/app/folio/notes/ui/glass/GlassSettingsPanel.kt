package app.folio.notes.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.folio.notes.editor.oneui.DottedDivider
import app.folio.notes.editor.oneui.LocalOneUi
import app.folio.notes.editor.oneui.OneUiType
import app.folio.notes.editor.oneui.PillTabs
import app.folio.notes.editor.oneui.PopupHeader
import app.folio.notes.editor.oneui.ProvideOneUi
import app.folio.notes.editor.oneui.SectionLabel
import app.folio.notes.editor.oneui.StepSlider
import app.folio.notes.editor.oneui.SwitchRow
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Settings › Glass & toolbar: every control over the glass look and the toolbar dock
 * ([GlassSettings]), with a live preview. Opened from the library's Settings and from the
 * editor.
 *
 * One glass sheet in a dialog. Its top is a preview band (a colourful backdrop with ink on it,
 * a sample card, and the [preview] slot, where the editor plugs in its working toolbar dock) that
 * stays put while the controls scroll underneath, so every change can be judged as it's made.
 * Everything is written live through [GlassPrefs]; nothing here has an Apply button.
 */
@Composable
fun GlassSettingsPanel(onDismiss: () -> Unit, preview: @Composable () -> Unit = {}) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ProvideOneUi(dark = LocalGlassDark.current ?: isSystemInDarkTheme()) {
            PanelSheet(onDismiss, preview)
        }
    }
}

// ------------------------------------------------------------------ the sheet

/** Text colours for the panel's own labels: the glass vibrancy colours, or One UI's when the sheet is plain. */
@Immutable
private class PanelInk(val primary: Color, val secondary: Color, val separator: Color, val accent: Color)

private val LocalPanelInk = staticCompositionLocalOf { PanelInk(Color.Black, Color.Gray, Color.LightGray, Color.Blue) }

private val SheetShape = RoundedCornerShape(28.dp)

/** Below this much window height the preview scrolls with the controls instead of staying pinned. */
private val PinPreviewMinHeight = 560.dp

@Composable
private fun PanelSheet(onDismiss: () -> Unit, preview: @Composable () -> Unit) {
    val s = LocalGlassSettings.current
    val c = LocalOneUi.current
    val glassy = s.enabled && s.glassDialogs
    val spec = glassSpec(GlassRole.DIALOG)
    val ink = remember(glassy, spec, c) {
        if (glassy) PanelInk(spec.textPrimary, spec.textSecondary, spec.separator, c.accent)
        else PanelInk(c.text, c.textSoft, c.divider, c.accent)
    }

    // The sheet settles in on the settings' own spring; nothing animates once it is up.
    val appear = remember { Animatable(0f) }
    val enterSpring = glassSpring<Float>()
    LaunchedEffect(Unit) { appear.animateTo(1f, enterSpring) }

    // Reset keeps a one-step undo: right after a reset, the same button restores what was there.
    val defaults = remember { GlassSettings() }
    var undo by remember { mutableStateOf<GlassSettings?>(null) }
    LaunchedEffect(s) { if (s != defaults) undo = null }
    val restorable = undo?.takeIf { s == defaults }

    CompositionLocalProvider(LocalPanelInk provides ink) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().pointerInput(onDismiss) { detectTapGestures { onDismiss() } }
                .padding(horizontal = 16.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            val pinned = maxHeight >= PinPreviewMinHeight
            val previewBand = @Composable { PreviewBand(dark = c.dark, glassCard = s.enabled && s.glassPopups, preview = preview) }
            Column(
                Modifier
                    .graphicsLayer {
                        val t = appear.value
                        alpha = t.coerceIn(0f, 1f)
                        scaleX = 0.94f + 0.06f * t; scaleY = scaleX
                    }
                    .widthIn(max = 640.dp).fillMaxWidth().heightIn(max = maxHeight)
                    .panelSurface(glassy, SheetShape, GlassRole.DIALOG)
                    .clip(SheetShape)
                    // Taps on the sheet itself must not fall through to the dismiss area behind it.
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                PopupHeader(
                    title = "Glass & toolbar",
                    onClose = onDismiss,
                    trailing = {
                        HeaderTextButton(
                            label = if (restorable != null) "Undo" else "Reset",
                            enabled = restorable != null || s != defaults,
                        ) {
                            if (restorable != null) { GlassPrefs.update { restorable }; undo = null }
                            else { undo = s; GlassPrefs.reset() }
                        }
                    },
                )
                if (pinned) previewBand()
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
                    if (!pinned) previewBand()
                    PanelControls(s, panelEnabled(s))
                }
            }
        }
    }
}

/** The sheet or card surface: glass when [glass], otherwise the plain One UI popup it replaces. */
@Composable
private fun Modifier.panelSurface(glass: Boolean, shape: Shape, role: GlassRole): Modifier {
    if (glass) return glassSurface(shape, role)
    val c = LocalOneUi.current
    return shadow(if (role == GlassRole.DIALOG) 20.dp else 12.dp, shape, ambientColor = c.shadow, spotColor = c.shadow)
        .background(c.popup, shape).border(1.dp, c.popupRim, shape)
}

@Composable
private fun HeaderTextButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val accent = LocalPanelInk.current.accent
    Box(
        Modifier.height(40.dp).glassPressable().clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = OneUiType.bodyStrong, color = accent.copy(alpha = if (enabled) 1f else 0.4f)) }
}

// ------------------------------------------------------------------ the preview band

/**
 * A colourful backdrop with ink on it, a sample popup card, and the caller's [preview] (the live
 * toolbar dock). The backdrop is recorded into its own [GlassBackdrop]; the card and the dock are
 * its siblings, drawn over it, so they blur and tint what's under them but never themselves.
 */
@Composable
private fun PreviewBand(dark: Boolean, glassCard: Boolean, preview: @Composable () -> Unit) {
    val backdrop = rememberGlassBackdrop()
    Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp).clip(RoundedCornerShape(20.dp))) {
        PreviewArt(backdrop, dark, Modifier.matchParentSize())
        CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
            Column(
                Modifier.fillMaxWidth().heightIn(min = 180.dp).padding(14.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                SampleCard(glassCard)
                Box(Modifier.fillMaxWidth().heightIn(min = 56.dp), contentAlignment = Alignment.Center) { preview() }
            }
        }
    }
}

@Composable
private fun SampleCard(glass: Boolean) {
    val c = LocalOneUi.current
    val spec = glassSpec(GlassRole.POPUP)
    val primary = if (glass) spec.textPrimary else c.text
    val secondary = if (glass) spec.textSecondary else c.textSoft
    val separator = if (glass) spec.separator else c.divider
    Column(
        Modifier.widthIn(max = 300.dp).panelSurface(glass, RoundedCornerShape(20.dp), GlassRole.POPUP)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text("Meeting notes", style = OneUiType.bodyStrong, color = primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.padding(vertical = 6.dp).fillMaxWidth().height(0.5.dp).background(separator))
        Text("Edited today · 3 pages", style = OneUiType.label, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The recorded backdrop: gradient fields, a sheet of ruled paper and some ink. Drawn once per size. */
@Composable
private fun PreviewArt(backdrop: GlassBackdrop, dark: Boolean, modifier: Modifier) {
    Spacer(
        modifier.glassBackdropSource(backdrop).drawWithCache {
            val w = size.width
            val h = size.height
            val field = Brush.linearGradient(
                if (dark) listOf(Color(0xFF1B1F4B), Color(0xFF5B2A7B), Color(0xFF0E6A78))
                else listOf(Color(0xFFFFB46B), Color(0xFFFF6FA3), Color(0xFF58A6FF)),
                start = Offset.Zero, end = Offset(w, h),
            )
            fun glow(color: Color, cx: Float, cy: Float, r: Float) =
                Brush.radialGradient(listOf(color, Color.Transparent), center = Offset(cx, cy), radius = r)
            val glows = if (dark) listOf(
                glow(Color(0xCCFF7A3C), w * 0.14f, h * 0.30f, h), glow(Color(0xCC2DD4BF), w * 0.82f, h * 0.18f, h * 0.9f),
                glow(Color(0xCCE11D74), w * 0.58f, h * 1.05f, h),
            ) else listOf(
                glow(Color(0xDDFFE066), w * 0.14f, h * 0.30f, h), glow(Color(0xDD5EEAD4), w * 0.82f, h * 0.18f, h * 0.9f),
                glow(Color(0xDDFF4F9A), w * 0.58f, h * 1.05f, h),
            )
            val paperFill = if (dark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.6f)
            val ruleColor = if (dark) Color.White.copy(alpha = 0.12f) else Color(0xFF3E7BFA).copy(alpha = 0.22f)
            val paperLeft = w * 0.05f
            val paperTop = h * 0.10f
            val paperW = w * 0.46f
            val paperH = h * 0.82f
            val rule = 20.dp.toPx()
            val strokes = PreviewInk.lines.map { PreviewStroke(smoothPath(it.xy, w, h), it.pen, it.widthDp.dp.toPx(), dark) }
            onDrawBehind {
                drawRect(field)
                glows.forEach { drawRect(it) }
                rotate(-2.5f, pivot = Offset(paperLeft + paperW / 2, paperTop + paperH / 2)) {
                    drawRoundRect(paperFill, Offset(paperLeft, paperTop), Size(paperW, paperH), CornerRadius(10.dp.toPx()))
                    var y = paperTop + rule * 1.6f
                    while (y < paperTop + paperH - 6.dp.toPx()) {
                        drawLine(ruleColor, Offset(paperLeft + 12.dp.toPx(), y), Offset(paperLeft + paperW - 12.dp.toPx(), y), 1.dp.toPx())
                        y += rule
                    }
                }
                strokes.forEach {
                    drawPath(it.path, it.color, alpha = it.alpha, style = Stroke(it.width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        },
    )
}

private class PreviewStroke(val path: Path, pen: InkPen, val width: Float, dark: Boolean) {
    val color: Color = when (pen) {
        InkPen.INK -> if (dark) Color(0xFFF5F5F7) else Color(0xFF1B2440)
        InkPen.RED -> if (dark) Color(0xFFFF6B5E) else Color(0xFFE5392F)
        InkPen.BLUE -> if (dark) Color(0xFF6CB0FF) else Color(0xFF2F7DF6)
        InkPen.HIGHLIGHT -> Color(0xFFFFE04A)
    }
    val alpha: Float = if (pen == InkPen.HIGHLIGHT) 0.55f else 1f
}

/** A smooth curve through the normalised [xy] points, scaled to [w] × [h]. */
private fun smoothPath(xy: FloatArray, w: Float, h: Float): Path {
    val n = xy.size / 2
    val p = Path()
    p.moveTo(xy[0] * w, xy[1] * h)
    for (i in 1 until n - 1) {
        val cx = xy[2 * i] * w
        val cy = xy[2 * i + 1] * h
        p.quadraticTo(cx, cy, (cx + xy[2 * i + 2] * w) / 2f, (cy + xy[2 * i + 3] * h) / 2f)
    }
    p.lineTo(xy[2 * (n - 1)] * w, xy[2 * (n - 1) + 1] * h)
    return p
}

// ------------------------------------------------------------------ the controls

@Composable
private fun PanelControls(s: GlassSettings, en: GlassPanelEnabled) {
    val sc = GlassPanelScales
    val tx = GlassPanelText
    fun set(change: (GlassSettings) -> GlassSettings) = GlassPrefs.update(change)

    SectionLabel("Glass")
    SwitchRow("Glass", s.enabled, { v -> set { it.copy(enabled = v) } }, subtitle = "Frosted, see-through surfaces. Off gives plain, solid ones.")
    ScaleRow("Amount of glass", sc.percent, s.frost, { v -> set { it.copy(frost = v) } }, tx::percent, tx::bare, en.glass, "Thin" to "Thick")
    ScaleRow("Transparency", sc.percent, s.transparency, { v -> set { it.copy(transparency = v) } }, tx::percent, tx::bare, en.glass, "Milky" to "Clear")
    ScaleRow("Colour boost", sc.boost, s.saturation, { v -> set { it.copy(saturation = v) } }, tx::times, tx::times, en.glass, "Natural" to "Vivid")
    ScaleRow("Edge lensing", sc.percent, s.lensing, { v -> set { it.copy(lensing = v) } }, tx::amount, tx::bare, en.glass, "Flat" to "Curved")
    ScaleRow("Highlight", sc.percent, s.highlight, { v -> set { it.copy(highlight = v) } }, tx::amount, tx::bare, en.glass, "Soft" to "Bright")
    TintRow(s.tint, { t -> set { it.copy(tint = t) } }, en.glass)
    ToggleRow(
        "Live blur", s.liveBlur, { v -> set { it.copy(liveBlur = v) } }, en.glass && GlassShader.supported,
        if (GlassShader.supported) "Blurs what's really behind the glass. Off is lighter on the battery." else "Needs Android 12 or newer.",
    )

    SectionDivider()
    SectionLabel("Text & accessibility")
    ToggleRow("Vibrant text", s.vibrantText, { v -> set { it.copy(vibrantText = v) } }, en.glass, "Text and icons take their colour from the glass behind them.")
    ToggleRow("Increase contrast", s.increaseContrast, { v -> set { it.copy(increaseContrast = v) } }, en.glass, "Stronger text and visible borders on glass.")
    ToggleRow("Reduce transparency", s.reduceTransparency, { v -> set { it.copy(reduceTransparency = v) } }, en.glass, "Solid surfaces instead of see-through glass.")

    SectionDivider()
    SectionLabel("Toolbar")
    ChoiceRow("Style", StyleLabels, s.dockStyle.ordinal, { i -> set { it.copy(dockStyle = DockStyle.entries[i]) } }, en.glass)
    ChoiceRow("Position", PositionLabels, s.dockPosition.ordinal, { i -> set { it.copy(dockPosition = DockPosition.entries[i]) } }, en.glass)
    ScaleRow("Icon size", sc.iconSize, s.dockIconSize, { v -> set { it.copy(dockIconSize = v) } }, tx::dp, tx::bare, en.glass, "Small" to "Large")
    ToggleRow("Magnify", s.magnify, { v -> set { it.copy(magnify = v) } }, en.glass, "Icons grow under the S Pen, mouse or finger, like the macOS Dock.")
    ScaleRow("Magnification", sc.magnification, s.magnification, { v -> set { it.copy(magnification = v) } }, tx::times, tx::times, en.magnify, "Subtle" to "Big")
    ScaleRow("Spread", sc.spread, s.magnifySpread, { v -> set { it.copy(magnifySpread = v) } }, tx::icons, tx::halves, en.magnify, "Tight" to "Wide")
    ToggleRow("Name labels", s.dockLabels, { v -> set { it.copy(dockLabels = v) } }, en.glass, "Show the tool's name above the icon under the pen.")
    ToggleRow("Auto-hide", s.autoHide, { v -> set { it.copy(autoHide = v) } }, en.glass, "Collapse into a small handle that opens on hover or tap.")
    ScaleRow("Hide delay", sc.hideDelay, s.autoHideDelay, { v -> set { it.copy(autoHideDelay = v) } }, tx::seconds, tx::secondsShort, en.autoHide, "Quick" to "Slow")
    ToggleRow("Hide while writing", s.hideWhileWriting, { v -> set { it.copy(hideWhileWriting = v) } }, en.autoHide, "Collapse as soon as the pen touches the page.")

    SectionDivider()
    SectionLabel("Where glass is used")
    ToggleRow("Popups", s.glassPopups, { v -> set { it.copy(glassPopups = v) } }, en.glass, "Pen, colour and size pickers.")
    ToggleRow("Menus", s.glassMenus, { v -> set { it.copy(glassMenus = v) } }, en.glass, "Dropdowns and context menus.")
    ToggleRow("Sidebar", s.glassSidebar, { v -> set { it.copy(glassSidebar = v) } }, en.glass, "The library sidebar and bars.")
    ToggleRow("Dialogs", s.glassDialogs, { v -> set { it.copy(glassDialogs = v) } }, en.glass, "Confirmations and sheets, like this one.")

    SectionDivider()
    SectionLabel("Motion")
    ScaleRow(
        "Springiness", sc.percent, s.springiness, { v -> set { it.copy(springiness = v) } },
        { tx.springiness(sc.percent.value(it)) }, tx::bare, en.glass, "Calm" to "Lively",
    )
}

private val StyleLabels = DockStyle.entries.map { it.label }
private val PositionLabels = DockPosition.entries.map { it.label }

@Composable
private fun SectionDivider() = DottedDivider(Modifier.padding(top = 10.dp, bottom = 6.dp))

/**
 * Greys a row out and ignores touches on it while [enabled] is false. The dimming eases on the
 * settings' spring; the touch blocker doesn't consume events, so a drag that starts on a
 * disabled row still scrolls the sheet.
 */
@Composable
private fun Gate(enabled: Boolean, content: @Composable () -> Unit) {
    val alpha = animateFloatAsState(if (enabled) 1f else 0.4f, glassSpring(), label = "gate")
    Box(
        Modifier.graphicsLayer { this.alpha = alpha.value }
            .then(if (enabled) Modifier else Modifier.semantics(mergeDescendants = true) { disabled() }),
    ) {
        content()
        if (!enabled) Box(Modifier.matchParentSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } })
    }
}

@Composable
private fun ToggleRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean, subtitle: String) {
    Gate(enabled) { SwitchRow(text, checked, { if (enabled) onChange(it) }, subtitle = subtitle) }
}

/** A title on the left and its current value, spelled out, on the right. */
@Composable
private fun ValueHeader(title: String, value: String) {
    val ink = LocalPanelInk.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = OneUiType.body, color = ink.primary, modifier = Modifier.weight(1f))
        Text(value, style = OneUiType.bodyStrong, color = ink.secondary)
    }
}

/** A slider over a [GlassPanelScale]: [text] spells the value out in the header, [thumb] is the short form in the thumb. */
@Composable
private fun ScaleRow(
    title: String,
    scale: GlassPanelScale,
    value: Float,
    onValue: (Float) -> Unit,
    text: (Int) -> String,
    thumb: (Int) -> String,
    enabled: Boolean,
    ends: Pair<String, String>? = null,
) {
    val ink = LocalPanelInk.current
    val step = scale.step(value)
    Gate(enabled) {
        Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            ValueHeader(title, text(step))
            StepSlider(step, scale.range, { if (enabled) onValue(scale.value(it)) }, title, label = thumb)
            if (ends != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 50.dp).offset(y = (-8).dp)) {
                    Text(ends.first, style = OneUiType.small, color = ink.secondary)
                    Spacer(Modifier.weight(1f))
                    Text(ends.second, style = OneUiType.small, color = ink.secondary)
                }
            }
        }
    }
}

/** A title over a row of equal pills, for choices of two or three. */
@Composable
private fun ChoiceRow(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit, enabled: Boolean) {
    val ink = LocalPanelInk.current
    Gate(enabled) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(title, style = OneUiType.body, color = ink.primary, modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp))
            PillTabs(options, selected, { if (enabled) onSelect(it) }, Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
        }
    }
}

// ------------------------------------------------------------------ tint chips

@Composable
private fun TintRow(selected: GlassTint, onSelect: (GlassTint) -> Unit, enabled: Boolean) {
    Gate(enabled) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            ValueHeader("Tint", selected.label)
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                val cell = (maxWidth / GlassTint.entries.size).coerceIn(48.dp, 84.dp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    GlassTint.entries.forEach { t -> TintChip(t, t == selected, cell) { if (enabled) onSelect(t) } }
                }
            }
        }
    }
}

@Composable
private fun TintChip(tint: GlassTint, selected: Boolean, width: Dp, onClick: () -> Unit) {
    val c = LocalOneUi.current
    val ink = LocalPanelInk.current
    val sel = animateFloatAsState(if (selected) 1f else 0f, glassSpring(), label = "tint-selected")
    Column(
        Modifier.width(width).glassPressable().clip(RoundedCornerShape(16.dp))
            .selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.size(42.dp)) {
            val t = sel.value
            val centre = Offset(size.width / 2, size.height / 2)
            val r = 15.dp.toPx() * (1f - 0.1f * t)
            if (tint == GlassTint.CLEAR) {
                drawCircle(c.pill, r, centre)
                drawCircle(ink.secondary, r, centre, style = Stroke(1.5.dp.toPx()))
                val d = r * 0.7f
                drawLine(ink.secondary, Offset(centre.x - d, centre.y + d), Offset(centre.x + d, centre.y - d), 1.5.dp.toPx(), StrokeCap.Round)
            } else {
                val base = Color(tint.argb)
                drawCircle(base, r, centre)
                drawCircle(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.38f), Color.Transparent), centre.y - r, centre.y + r), r, centre)
                drawCircle(Color.White.copy(alpha = 0.5f), r, centre, style = Stroke(1.dp.toPx()))
            }
            if (t > 0.01f) {
                drawCircle(ink.accent.copy(alpha = t.coerceIn(0f, 1f)), r + 2.dp.toPx() + 3.dp.toPx() * t, centre, style = Stroke(2.dp.toPx()))
            }
        }
        Text(
            tint.label, style = OneUiType.small, color = if (selected) ink.primary else ink.secondary,
            maxLines = 1, overflow = TextOverflow.Clip,
        )
    }
}

// ------------------------------------------------------------------ settings ↔ slider steps

/**
 * How a Float setting maps onto the whole-number steps of a [StepSlider]: the slider works in
 * steps of 1 / [perUnit] of the setting's own unit (percent, tenths of ×, half-seconds…).
 */
internal class GlassPanelScale(val range: IntRange, private val perUnit: Int) {
    fun step(value: Float): Int = (value * perUnit).roundToInt().coerceIn(range.first, range.last)
    fun value(step: Int): Float = step.coerceIn(range.first, range.last) / perUnit.toFloat()
    val min: Float get() = range.first / perUnit.toFloat()
    val max: Float get() = range.last / perUnit.toFloat()
}

/** One scale per kind of control; each covers exactly what [GlassSettings.clamped] allows. */
internal object GlassPanelScales {
    /** 0–1 settings, in whole percent. */
    val percent = GlassPanelScale(0..100, 100)
    /** Colour boost, 1.0×–2.2× in tenths. */
    val boost = GlassPanelScale(10..22, 10)
    /** Icon size, 32–64 dp in whole dp. */
    val iconSize = GlassPanelScale(GlassSettings.MIN_ICON.roundToInt()..GlassSettings.MAX_ICON.roundToInt(), 1)
    /** Magnification, 1.0×–3.0× in tenths. */
    val magnification = GlassPanelScale(10..(GlassSettings.MAX_MAGNIFICATION * 10).roundToInt(), 10)
    /** Magnify spread, 1–5 icons in halves. */
    val spread = GlassPanelScale(2..10, 2)
    /** Auto-hide delay, 0.5–10 s in halves. */
    val hideDelay = GlassPanelScale(1..20, 2)
}

/** The words next to each control's value. Decimal separators are fixed so the text reads the same everywhere. */
internal object GlassPanelText {
    fun percent(step: Int) = "$step%"
    /** The thumb is only 28 dp wide: just the number. */
    fun bare(step: Int) = step.toString()
    /** A 0-is-off amount. */
    fun amount(step: Int) = if (step == 0) "Off" else percent(step)
    fun tenths(n: Int) = "${n / 10}.${n % 10}"
    fun times(n: Int) = "${tenths(n)}×"
    fun halves(n: Int) = if (n % 2 == 0) "${n / 2}" else "${n / 2}.5"
    fun dp(v: Int) = "$v dp"
    fun seconds(n: Int) = "${halves(n)} s"
    fun secondsShort(n: Int) = "${halves(n)}s"
    fun icons(n: Int) = if (n == 2) "1 icon" else "${halves(n)} icons"

    /** Calm … Lively, in five named steps. */
    fun springiness(f: Float): String = when ((f * 100).roundToInt()) {
        in 0..19 -> "Calm"
        in 20..39 -> "Gentle"
        in 40..59 -> "Balanced"
        in 60..79 -> "Springy"
        else -> "Lively"
    }
}

/** Which groups of rows are usable under the current settings. */
internal class GlassPanelEnabled(
    /** Everything but the Glass switch itself. */
    val glass: Boolean,
    /** Magnification and spread. */
    val magnify: Boolean,
    /** Hide delay and Hide while writing. */
    val autoHide: Boolean,
)

internal fun panelEnabled(s: GlassSettings) = GlassPanelEnabled(
    glass = s.enabled,
    magnify = s.enabled && s.magnify,
    autoHide = s.enabled && s.autoHide,
)

// ------------------------------------------------------------------ the ink in the preview

internal enum class InkPen { INK, RED, BLUE, HIGHLIGHT }

/** One pen stroke: [xy] holds x0, y0, x1, y1… as fractions (0–1) of the preview's width and height. */
internal class InkLine(val pen: InkPen, val widthDp: Float, val xy: FloatArray)

/**
 * A few hand-drawn-looking strokes for the preview backdrop: cursive scribbles, a circled
 * spot, a highlighter swipe and a swoosh. Seeded, so the picture is the same every time.
 */
internal object PreviewInk {
    val lines: List<InkLine> by lazy { build() }

    private fun build(): List<InkLine> = listOf(
        InkLine(InkPen.HIGHLIGHT, 15f, swipe(0.52f, 0.76f, 0.93f, 0.71f, seed = 5)),
        InkLine(InkPen.INK, 2.6f, scribble(seed = 3, x0 = 0.30f, x1 = 0.90f, y = 0.22f, loopW = 0.010f, loopH = 0.07f, loops = 17)),
        InkLine(InkPen.INK, 2.6f, scribble(seed = 11, x0 = 0.07f, x1 = 0.47f, y = 0.55f, loopW = 0.010f, loopH = 0.07f, loops = 11)),
        InkLine(InkPen.RED, 3f, ring(seed = 7, cx = 0.78f, cy = 0.50f, rx = 0.07f, ry = 0.24f)),
        InkLine(InkPen.BLUE, 3.2f, swoosh(seed = 9, x0 = 0.55f, y0 = 0.95f, x1 = 0.75f, y1 = 0.40f, x2 = 0.98f, y2 = 0.12f)),
    )

    private const val STEPS = 96

    private fun unit(v: Float) = v.coerceIn(0f, 1f)
    private fun jitter(r: Random) = (r.nextFloat() - 0.5f) * 0.008f

    /** A row of cursive loops (a trochoid) wobbling along from [x0] to [x1]. */
    private fun scribble(seed: Long, x0: Float, x1: Float, y: Float, loopW: Float, loopH: Float, loops: Int): FloatArray {
        val r = Random(seed)
        val out = FloatArray((STEPS + 1) * 2)
        for (i in 0..STEPS) {
            val t = i / STEPS.toFloat()
            val a = (2.0 * PI * loops * t).toFloat()
            val swell = 0.75f + 0.25f * sin(5f * t)
            out[2 * i] = unit(x0 + (x1 - x0) * t - loopW * sin(a) + jitter(r))
            out[2 * i + 1] = unit(y + loopH * swell * (1f - cos(a)) / 2f + jitter(r))
        }
        return out
    }

    /** A slightly sagging, slightly shaky line. */
    private fun swipe(x0: Float, y0: Float, x1: Float, y1: Float, seed: Long): FloatArray {
        val r = Random(seed)
        val n = 16
        val out = FloatArray((n + 1) * 2)
        for (i in 0..n) {
            val t = i / n.toFloat()
            out[2 * i] = unit(x0 + (x1 - x0) * t + jitter(r))
            out[2 * i + 1] = unit(y0 + (y1 - y0) * t + 0.02f * sin(PI.toFloat() * t) + jitter(r))
        }
        return out
    }

    /** A loop drawn a little more than once round, drifting outwards, like circling something by hand. */
    private fun ring(seed: Long, cx: Float, cy: Float, rx: Float, ry: Float): FloatArray {
        val r = Random(seed)
        val n = 64
        val out = FloatArray((n + 1) * 2)
        for (i in 0..n) {
            val t = i / n.toFloat()
            val a = (2.0 * PI * 1.1 * t - 0.6).toFloat()
            val grow = 1f + 0.06f * t
            out[2 * i] = unit(cx + rx * grow * cos(a) + jitter(r))
            out[2 * i + 1] = unit(cy + ry * grow * sin(a) + jitter(r))
        }
        return out
    }

    /** A quadratic curve through three points. */
    private fun swoosh(seed: Long, x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float): FloatArray {
        val r = Random(seed)
        val n = 40
        val out = FloatArray((n + 1) * 2)
        for (i in 0..n) {
            val t = i / n.toFloat()
            val u = 1f - t
            out[2 * i] = unit(u * u * x0 + 2 * u * t * x1 + t * t * x2 + jitter(r))
            out[2 * i + 1] = unit(u * u * y0 + 2 * u * t * y1 + t * t * y2 + jitter(r))
        }
        return out
    }
}
