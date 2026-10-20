package app.folio.notes.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.folio.core.model.ToolType
import app.folio.notes.ui.GlassDivider
import app.folio.notes.ui.GlassPopover
import app.folio.notes.ui.GlassSlider
import app.folio.notes.ui.GlassSwitch
import app.folio.notes.ui.LocalGlass
import app.folio.notes.ui.androidxClickable
import app.folio.notes.ui.bouncy
import app.folio.notes.ui.glass

/** What a shelf slot holds. */
private enum class Kit(val label: String, val tag: String) {
    FOUNTAIN("Fountain pen", "tool-FOUNTAIN"), BALLPOINT("Ballpoint pen", "tool-BALLPOINT"), FINELINER("Fine liner", "tool-FINELINER"),
    PENCIL("Pencil", "tool-PENCIL"), BRUSH("Brush pen", "tool-BRUSH"),
    AIRBRUSH("Airbrush", "tool-AIRBRUSH"), MARKER("Marker", "tool-MARKER"),
    HIGHLIGHTER("Highlighter", "tool-HIGHLIGHTER"), ERASER("Eraser", "tool-eraser"),
    LASSO("Lasso select", "tool-lasso"), TEXT("Text", "tool-text"), IMAGE("Insert picture", "insert-image"),
}

private val UTILITY_KITS = listOf(Kit.ERASER, Kit.LASSO, Kit.TEXT, Kit.IMAGE)
private val PEN_KITS = Kit.entries - UTILITY_KITS

private fun Kit.tool(): ToolType? = when (this) {
    Kit.FOUNTAIN -> ToolType.FOUNTAIN; Kit.BALLPOINT -> ToolType.BALLPOINT; Kit.PENCIL -> ToolType.PENCIL
    Kit.BRUSH -> ToolType.BRUSH; Kit.HIGHLIGHTER -> ToolType.HIGHLIGHTER
    Kit.FINELINER -> ToolType.FINELINER; Kit.MARKER -> ToolType.MARKER
    Kit.AIRBRUSH -> ToolType.AIRBRUSH; else -> null
}

/**
 * The tool shelf: pens, fine liner, pencil, brush pen, airbrush, marker, highlighter, eraser, lasso, text and picture standing in a glass
 * tray. A hovering S Pen makes a tool grow and rise; the chosen one pops out of the tray;
 * tapping the chosen one again opens its options in a glass popover.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolShelf(tools: ToolState, update: ((ToolState) -> ToolState) -> Unit, onColorWithSelection: ((Long) -> Unit)?, onInsertImage: () -> Unit) {
    val g = LocalGlass.current
    var options by remember { mutableStateOf<Kit?>(null) }
    fun selected(k: Kit) = when (k) {
        Kit.ERASER -> tools.eraser
        Kit.LASSO -> tools.lasso
        Kit.TEXT -> tools.text
        Kit.IMAGE -> false
        else -> !tools.eraser && !tools.lasso && !tools.text && tools.tool == k.tool()
    }
    fun tap(k: Kit) {
        if (k == Kit.IMAGE) { onInsertImage(); return }
        if (selected(k)) { options = k; return }
        update {
            when (k) {
                Kit.ERASER -> it.copy(eraser = true, lasso = false, text = false)
                Kit.LASSO -> it.copy(lasso = true, eraser = false, text = false)
                Kit.TEXT -> it.copy(text = true, eraser = false, lasso = false)
                else -> it.copy(tool = k.tool()!!, eraser = false, lasso = false, text = false)
            }
        }
    }
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
        itemVerticalAlignment = Alignment.Bottom,
    ) {
        // Two trays: pens and brushes, then the utilities. They wrap onto separate rows on a
        // narrow screen, and the pen tray scrolls sideways if it's still wider than the screen,
        // so no tool is ever out of reach.
        for (kits in listOf(PEN_KITS, UTILITY_KITS)) Box(contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.matchParentSize().padding(top = 56.dp).glass(RoundedCornerShape(18.dp), elevation = 10.dp))
            Row(
                // Top room so a chosen tool can rise and grow without the scroll clip cutting it.
                Modifier.horizontalScroll(rememberScrollState()).padding(start = 10.dp, end = 10.dp, top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom,
            ) {
                kits.forEach { k ->
                    Box {
                        ShelfTool(k, selected(k), Color(tools.color), onTap = { tap(k) })
                        if (options == k) ToolOptions(k, tools, update, onDismiss = { options = null })
                    }
                }
            }
            // The tray's front lip sits over the tools' bases.
            Box(
                Modifier.matchParentSize().padding(top = 76.dp).clip(RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp))
                    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.25f)))),
            )
        }
        // Ink colours as glass beads.
        Row(Modifier.height(44.dp).glass(CircleShape, elevation = 8.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolState.PALETTE.forEach { c ->
                val sel = c == tools.color
                val pop = remember { Animatable(if (sel) 1f else 0f) }
                LaunchedEffect(sel) { pop.animateTo(if (sel) 1f else 0f, bouncy()) }
                Box(
                    Modifier.size(34.dp)
                        .graphicsLayer { val s = 0.8f + 0.25f * pop.value; scaleX = s; scaleY = s }
                        .androidxClickable("Ink colour ${"%06X".format(c and 0xFFFFFF)}") {
                            if (onColorWithSelection != null) onColorWithSelection(c) else update { it.copy(color = c, eraser = false, lasso = false) }
                        }
                        .semantics { selected = sel }
                        .padding(4.dp),
                ) {
                    Canvas(Modifier.size(26.dp)) {
                        drawCircle(Color(c))
                        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.7f), Color.Transparent), center = Offset(size.width * 0.35f, size.height * 0.3f), radius = size.width * 0.45f))
                        if (sel) drawCircle(g.accent, radius = size.width / 2 + 3.dp.toPx(), style = Stroke(2.dp.toPx()))
                    }
                }
            }
        }
        // Quick size.
        Row(Modifier.height(44.dp).glass(CircleShape, elevation = 8.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(18.dp)) { drawCircle(Color(tools.color), radius = (tools.width / 16f * size.width / 2).coerceIn(1.5f, size.width / 2)) }
            GlassSlider(
                tools.width, { w -> update { it.copy(widths = it.widths + (it.tool to w)) } },
                valueRange = ToolState.sizeRange(tools.tool),
                description = "Pen size", modifier = Modifier.width(150.dp), accent = Color(tools.color),
            )
        }
    }
}

/** One tool standing in the tray: rises on hover, pops out when chosen, squishes on press. */
@Composable
private fun ShelfTool(k: Kit, selected: Boolean, ink: Color, onTap: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    val lift = remember { Animatable(if (selected) 1f else 0f) }
    LaunchedEffect(selected, hovered) { lift.animateTo(if (selected) 1f else if (hovered) 0.55f else 0f, bouncy()) }
    Box(
        Modifier.width(40.dp).height(78.dp)
            .testTag(k.tag)
            .semantics { this.selected = selected }
            .hover { hovered = it }
            .androidxClickable(k.label, onTap)
            .graphicsLayer {
                translationY = (1f - lift.value) * 26.dp.toPx()
                val s = 1f + 0.12f * lift.value
                scaleX = s; scaleY = s
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
            },
    ) {
        Canvas(Modifier.width(40.dp).height(78.dp)) { drawTool(k, ink) }
    }
}

/** Reports when a pen or mouse hovers over this (S Pen hover arrives as Enter/Exit). */
private fun Modifier.hover(onHover: (Boolean) -> Unit): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent()
            when (e.type) {
                PointerEventType.Enter -> onHover(true)
                PointerEventType.Exit -> onHover(false)
                else -> Unit
            }
        }
    }
}

// ---------------------------------------------------------------------- tool artwork

private fun DrawScope.drawTool(k: Kit, ink: Color) {
    val h = size.height
    val cx = size.width / 2
    fun gloss(left: Float, top: Float, width: Float, height: Float) = drawRect(
        Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.55f), Color.Transparent), startX = left, endX = left + width * 0.6f),
        Offset(left, top), Size(width * 0.6f, height),
    )
    fun body(color: Color, top: Float, width: Float = 16f * density, dark: Color = color.darken()) {
        drawRoundRect(Brush.horizontalGradient(listOf(color, dark), startX = cx - width / 2, endX = cx + width / 2), Offset(cx - width / 2, top), Size(width, h - top), CornerRadius(4f))
        gloss(cx - width / 2, top, width, h - top)
    }
    fun cone(top: Float, bottom: Float, width: Float, color: Color) {
        val p = Path().apply { moveTo(cx - width / 2, bottom); lineTo(cx + width / 2, bottom); lineTo(cx + width * 0.12f, top); lineTo(cx - width * 0.12f, top); close() }
        drawPath(p, Brush.horizontalGradient(listOf(color, color.darken()), startX = cx - width / 2, endX = cx + width / 2))
    }
    when (k) {
        Kit.FOUNTAIN -> {
            body(Color(0xFF1F2A44), 26f * density)
            drawRect(Color(0xFFD9B45A), Offset(cx - 7f * density, 30f * density), Size(14f * density, 3f * density))
            cone(8f * density, 26f * density, 14f * density, Color(0xFFD9B45A))
            drawLine(ink, Offset(cx, 4f * density), Offset(cx, 14f * density), strokeWidth = 2f * density)
        }
        Kit.BALLPOINT -> {
            body(Color(0xFFE9ECF2), 24f * density, dark = Color(0xFFB7BECC))
            drawRect(ink, Offset(cx + 3f * density, 28f * density), Size(3f * density, 22f * density))
            cone(8f * density, 24f * density, 12f * density, Color(0xFFC9CED8))
            drawCircle(ink, 1.8f * density, Offset(cx, 7f * density))
        }
        Kit.PENCIL -> {
            body(Color(0xFFF2C14E), 26f * density, dark = Color(0xFFD99A2B))
            cone(10f * density, 26f * density, 14f * density, Color(0xFFF1D7B0))
            val tip = Path().apply { moveTo(cx - 2.8f * density, 13f * density); lineTo(cx + 2.8f * density, 13f * density); lineTo(cx, 5f * density); close() }
            drawPath(tip, ink.darken())
        }
        Kit.BRUSH -> {
            body(Color(0xFF2B2B30), 30f * density)
            drawRect(Color(0xFF8C8F99), Offset(cx - 6f * density, 26f * density), Size(12f * density, 6f * density))
            val tuft = Path().apply { moveTo(cx - 5f * density, 26f * density); quadraticTo(cx - 6f * density, 12f * density, cx, 4f * density); quadraticTo(cx + 6f * density, 12f * density, cx + 5f * density, 26f * density); close() }
            drawPath(tuft, ink)
        }
        Kit.FINELINER -> {
            body(Color(0xFFDDE1E8), 26f * density, width = 13f * density, dark = Color(0xFFAEB5C2))
            drawRect(ink, Offset(cx - 6.5f * density, 30f * density), Size(13f * density, 4f * density))
            cone(11f * density, 26f * density, 11f * density, Color(0xFFBFC5CF))
            drawLine(ink, Offset(cx, 4f * density), Offset(cx, 12f * density), strokeWidth = 1.4f * density)
        }
        Kit.MARKER -> {
            body(ink, 30f * density, width = 20f * density)
            drawRect(Color.White.copy(alpha = 0.85f), Offset(cx - 10f * density, 34f * density), Size(20f * density, 4f * density))
            cone(14f * density, 30f * density, 16f * density, Color(0xFF2A2E35))
            drawRoundRect(ink, Offset(cx - 4f * density, 5f * density), Size(8f * density, 11f * density), CornerRadius(4f * density))
        }
        Kit.AIRBRUSH -> {
            // A spray pen: chrome body, a trigger lever, a fine nozzle with a puff of mist.
            body(Color(0xFFD7DCE4), 24f * density, width = 14f * density, dark = Color(0xFF9AA2B0))
            drawRoundRect(Color(0xFF2A2E35), Offset(cx + 2f * density, 30f * density), Size(6f * density, 4f * density), CornerRadius(2f * density))
            cone(12f * density, 24f * density, 14f * density, Color(0xFFBFC5CF))
            drawCircle(Brush.radialGradient(listOf(ink.copy(alpha = 0.7f), Color.Transparent), center = Offset(cx, 7f * density), radius = 8f * density), 8f * density, Offset(cx, 7f * density))
        }
        Kit.HIGHLIGHTER -> {
            body(ink.copy(alpha = 0.85f).compositeWhite(), 28f * density)
            drawRoundRect(Color(0xFF2A2E35).copy(alpha = 0.8f), Offset(cx - 9f * density, 24f * density), Size(18f * density, 6f * density), CornerRadius(3f))
            val chisel = Path().apply { moveTo(cx - 7f * density, 24f * density); lineTo(cx + 7f * density, 24f * density); lineTo(cx + 5f * density, 10f * density); lineTo(cx - 5f * density, 14f * density); close() }
            drawPath(chisel, ink)
        }
        Kit.ERASER -> {
            body(Color(0xFF5B8DEF), 34f * density, dark = Color(0xFF3B64B8))
            drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFFFE4EA), Color(0xFFF5A8B8))), Offset(cx - 9f * density, 10f * density), Size(18f * density, 26f * density), CornerRadius(5f * density))
            gloss(cx - 9f * density, 10f * density, 18f * density, 26f * density)
        }
        Kit.LASSO -> {
            body(Color(0xFF6C5CE7), 34f * density)
            drawCircle(ink.takeIf { it.luminance() < 0.9f } ?: Color(0xFF6C5CE7), 11f * density, Offset(cx, 18f * density),
                style = Stroke(2.2f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f * density, 3.5f * density))))
        }
        Kit.TEXT -> {
            body(Color(0xFF2A9D8F), 30f * density)
            drawRoundRect(Color.White.copy(alpha = 0.95f), Offset(cx - 11f * density, 6f * density), Size(22f * density, 22f * density), CornerRadius(5f * density))
            drawRect(Color(0xFF1C2433), Offset(cx - 7f * density, 10f * density), Size(14f * density, 3f * density))
            drawRect(Color(0xFF1C2433), Offset(cx - 1.5f * density, 10f * density), Size(3f * density, 14f * density))
        }
        Kit.IMAGE -> {
            drawRoundRect(Color.White, Offset(cx - 15f * density, 12f * density), Size(30f * density, h - 12f * density), CornerRadius(4f * density))
            drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF9AD0F5), Color(0xFFE7F4FD))), Offset(cx - 12f * density, 15f * density), Size(24f * density, 24f * density), CornerRadius(2f * density))
            val hill = Path().apply { moveTo(cx - 12f * density, 39f * density); lineTo(cx - 3f * density, 26f * density); lineTo(cx + 3f * density, 33f * density); lineTo(cx + 6f * density, 29f * density); lineTo(cx + 12f * density, 39f * density); close() }
            drawPath(hill, Color(0xFF2A9D8F))
            drawCircle(Color(0xFFF2C14E), 3f * density, Offset(cx + 6f * density, 20f * density))
        }
    }
}

private fun Color.darken(f: Float = 0.72f) = Color(red * f, green * f, blue * f, alpha)
private fun Color.compositeWhite() = Color(red * alpha + (1 - alpha), green * alpha + (1 - alpha), blue * alpha + (1 - alpha), 1f)
private fun Color.luminance() = 0.2126f * red + 0.7152f * green + 0.0722f * blue

// ---------------------------------------------------------------------- options popovers

@Composable
private fun ToolOptions(k: Kit, tools: ToolState, update: ((ToolState) -> ToolState) -> Unit, onDismiss: () -> Unit) {
    val g = LocalGlass.current
    GlassPopover(true, onDismiss, offset = IntOffset(-120, 220)) {
        Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(k.label, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = g.ink)
            when (k) {
                Kit.ERASER -> {
                    listOf(false to "Stroke eraser", true to "Partial eraser").forEach { (partial, label) ->
                        Choice(label, if (partial) "Erases only where you rub" else "Deletes each whole stroke it touches", tools.eraserPartial == partial) {
                            update { it.copy(eraser = true, eraserPartial = partial, lasso = false, text = false) }; onDismiss()
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().androidxClickable("Erase highlighter only") { update { it.copy(eraseHighlighterOnly = !it.eraseHighlighterOnly) } }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Erase highlighter only", color = g.ink, modifier = Modifier.weight(1f))
                        GlassSwitch(tools.eraseHighlighterOnly, null)
                    }
                    Label("Eraser size")
                    GlassSlider(tools.eraserSizeDp, { v -> update { it.copy(eraserSizeDp = v) } }, 4f..60f, "Eraser size", Modifier.fillMaxWidth())
                }
                Kit.LASSO -> LassoShape.entries.forEach { shape ->
                    Choice(if (shape == LassoShape.FREEFORM) "Freeform lasso" else "Rectangle select", null, tools.lassoShape == shape) {
                        update { it.copy(lasso = true, eraser = false, text = false, lassoShape = shape) }; onDismiss()
                    }
                }
                Kit.TEXT -> listOf(20f to "Small", 28f to "Medium", 40f to "Large", 56f to "Extra large").forEach { (size, label) ->
                    Choice("$label text", null, tools.textSize == size) { update { it.copy(textSize = size) }; onDismiss() }
                }
                else -> {
                    Label("Size")
                    GlassSlider(
                        tools.width, { w -> update { it.copy(widths = it.widths + (it.tool to w)) } },
                        ToolState.sizeRange(tools.tool), "Tool size", Modifier.fillMaxWidth(), accent = Color(tools.color),
                    )
                    Label("Stabilisation ${(tools.stabilization * 100).toInt()}%")
                    GlassSlider(tools.stabilization, { s -> update { it.copy(stabilization = s) } }, 0f..1f, "Stroke stabilisation", Modifier.fillMaxWidth())
                    Label("Pen pressure: light hand ↔ firm hand")
                    GlassSlider(tools.pressureSensitivity, { v -> update { it.copy(pressureSensitivity = v) } }, 0f..1f, "Pen pressure sensitivity", Modifier.fillMaxWidth())
                    Row(
                        Modifier.fillMaxWidth().androidxClickable("Only the pen writes") { update { it.copy(penOnly = !it.penOnly) } },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Only the pen writes (fingers scroll)", color = g.ink, modifier = Modifier.weight(1f))
                        GlassSwitch(tools.penOnly, null)
                    }
                    Text("Hold the S Pen button while writing to erase.", color = g.inkSoft, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, color = LocalGlass.current.inkSoft, fontSize = 13.sp, fontWeight = FontWeight.Medium)

@Composable
private fun Choice(title: String, subtitle: String?, on: Boolean, onClick: () -> Unit) {
    val g = LocalGlass.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .then(if (on) Modifier.glass(RoundedCornerShape(16.dp), elevation = 2.dp, tint = g.accent) else Modifier)
            .androidxClickable(title, onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = g.ink, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, color = g.inkSoft, fontSize = 12.sp)
        }
        if (on) Box(Modifier.size(10.dp).clip(CircleShape).background(g.accent).border(1.dp, Color.White, CircleShape))
    }
}
