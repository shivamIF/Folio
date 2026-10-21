package app.folio.notes.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.folio.core.model.ToolType
import app.folio.notes.editor.oneui.ChoicePill
import app.folio.notes.editor.oneui.CloseButton
import app.folio.notes.editor.oneui.ColorPickerPanel
import app.folio.notes.editor.oneui.DottedDivider
import app.folio.notes.editor.oneui.FIcon
import app.folio.notes.editor.oneui.FolioIcon
import app.folio.notes.editor.oneui.IconAction
import app.folio.notes.editor.oneui.LocalOneUi
import app.folio.notes.editor.oneui.MenuCard
import app.folio.notes.editor.oneui.MenuEntry
import app.folio.notes.editor.oneui.OneUiDialog
import app.folio.notes.editor.oneui.OneUiPopup
import app.folio.notes.editor.oneui.OneUiType
import app.folio.notes.editor.oneui.OpacitySlider
import app.folio.notes.editor.oneui.PalettePager
import app.folio.notes.editor.oneui.PenArt
import app.folio.notes.editor.oneui.PenStand
import app.folio.notes.editor.oneui.PillTabs
import app.folio.notes.editor.oneui.PopupHeader
import app.folio.notes.editor.oneui.PopupPlacement
import app.folio.notes.editor.oneui.RAINBOW
import app.folio.notes.editor.oneui.RadioRow
import app.folio.notes.editor.oneui.SectionLabel
import app.folio.notes.editor.oneui.StepSlider
import app.folio.notes.editor.oneui.SwitchRow
import app.folio.notes.editor.oneui.drawPenArt
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Everything a tool popup needs from the editor. */
class PopupHost(
    val tools: ToolState,
    val update: ((ToolState) -> ToolState) -> Unit,
    /** How a stored colour is shown (dark-mode adaptation). */
    val display: (Long) -> Long,
    val close: () -> Unit,
    /** Start the eyedropper; the picked colour goes to [tool]'s colour (and quick slot [slot], if any). */
    val eyedropper: (tool: EditorTool, slot: Int?) -> Unit,
    val eraseAll: () -> Unit,
    val removeAllTape: () -> Unit,
    /** Pen popup → the mini strip at the left edge. */
    val shrink: () -> Unit,
    val anchorBottomPx: Int,
    /** A selection is active: colours and widths restyle it too. */
    val recolorSelection: ((Long) -> Unit)?,
)

@Composable
fun ToolPopup(open: OpenPopup, host: PopupHost) {
    when (open.kind) {
        PopupKind.PEN -> PenPopup(host)
        PopupKind.HIGHLIGHTER -> HighlighterPopup(host)
        PopupKind.ERASER -> EraserPopup(host)
        PopupKind.SELECTION -> SelectionPopup(host)
        PopupKind.TEXT -> TextPopup(host)
        PopupKind.CHANGE_STYLE -> ChangeStylePopup(host)
        PopupKind.TAPE -> TapePopup(host)
        PopupKind.LASER -> LaserPopup(host)
        PopupKind.QUICK_SIZE -> QuickSizeCard(open.slot, host)
        PopupKind.QUICK_COLOR -> QuickColorPicker(open.slot, host)
        PopupKind.SPEN_SETTINGS -> Unit // drawn by the S Pen to text bar
    }
}

/** Shared size slider in 1–50 steps (tape 1–10) for [t]. */
@Composable
private fun SizeSlider(t: ToolType, host: PopupHost) {
    val tools = host.tools
    StepSlider(
        SizeScale.stepOf(t, tools.widthOf(t)), 1..SizeScale.stepsOf(t),
        { step -> host.update { it.withWidth(t, SizeScale.widthOf(t, step)) } }, "${toolLabel(t)} size",
    )
}

/** The palette for [tool]'s colour. */
@Composable
private fun ToolPalette(tool: EditorTool, host: PopupHost) {
    var picker by remember { mutableStateOf(false) }
    val current = host.tools.colorOf(tool)
    PalettePager(
        current, host.tools.recentColors,
        onPick = { c -> host.update { it.withColorFor(tool, c) }; host.recolorSelection?.invoke(c) },
        onEyedropper = { host.close(); host.eyedropper(tool, null) },
        onOpenPicker = { picker = true },
        display = host.display,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    if (picker) ColorPickerPanel(
        current, host.tools.recentColors,
        onDone = { c -> picker = false; host.update { it.withColorFor(tool, c) }; host.recolorSelection?.invoke(c) },
        onCancel = { picker = false },
        onEyedropper = { picker = false; host.close(); host.eyedropper(tool, null) },
        display = host.display, anchorBottomPx = host.anchorBottomPx,
    )
}

// ------------------------------------------------------------------ pen

@Composable
private fun PenPopup(host: PopupHost) {
    val c = LocalOneUi.current
    val tools = host.tools
    var favMode by rememberSaveable { mutableStateOf(false) }
    var favMenu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    OneUiPopup(host.close, anchorBottomPx = host.anchorBottomPx, modifier = Modifier.testTag("pen-popup")) {
        PopupHeader(
            onClose = host.close,
            leading = {
                IconAction(FIcon.FAV_PENS, if (favMode) "Back to pens" else "Favourite pens", { favMode = !favMode; deleting = false }, selected = favMode)
                if (!favMode) IconAction(FIcon.STAR, "Add to favourites", {
                    host.update { s -> if (s.currentPreset() in s.favourites) s else s.copy(favourites = s.favourites + s.currentPreset()) }
                })
            },
            trailing = {
                if (favMode) Box {
                    IconAction(FIcon.MORE, "More options", { favMenu = true })
                    if (favMenu) MenuCard(
                        listOf(listOf(
                            MenuEntry("Add current pen", FIcon.PLUS) { host.update { s -> if (s.currentPreset() in s.favourites) s else s.copy(favourites = s.favourites + s.currentPreset()) } },
                            MenuEntry(if (deleting) "Done deleting" else "Delete", FIcon.DELETE, enabled = tools.favourites.isNotEmpty()) { deleting = !deleting },
                        )),
                        onDismiss = { favMenu = false }, width = 220.dp,
                    )
                }
                IconAction(FIcon.SHRINK, "Shrink", { host.close(); host.shrink() })
            },
        )
        if (favMode) {
            FavouritesGrid(tools, deleting, host)
            return@OneUiPopup
        }
        PenRow(ToolState.PENS, tools.pen.takeIf { tools.active == EditorTool.PEN } ?: tools.pen, Color(host.display(tools.colorOf(EditorTool.PEN)))) { t ->
            host.update { it.selectPen(t) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
        Spacer(Modifier.height(4.dp))
        SizeSlider(tools.pen, host)
        LinePills(tools.penLine) { l -> host.update { it.copy(penLine = l) } }
        Spacer(Modifier.height(6.dp))
        SwitchRow("Straighten lines", tools.straightenPen, { on -> host.update { it.copy(straightenPen = on) } })
        // Folio extras, in the same style.
        SectionLabel("Stroke stabilisation")
        StepSlider(
            (tools.stabilization * 10).roundToInt(), 0..10, { v -> host.update { it.copy(stabilization = v / 10f) } }, "Stroke stabilisation",
        )
        if (tools.penLine == PenLine.VARIABLE) {
            SectionLabel("Pressure: light hand ↔ firm hand")
            StepSlider(
                (tools.pressureSensitivity * 10).roundToInt().coerceIn(0, 10), 0..10,
                { v -> host.update { it.copy(pressureSensitivity = v / 10f) } }, "Pen pressure sensitivity",
            )
        }
        ToolPalette(EditorTool.PEN, host)
    }
}

/** The row of pens standing on a line; the selected one is raised. Scrolls when it doesn't fit. */
@Composable
private fun PenRow(pens: List<ToolType>, selected: ToolType, ink: Color, roundHighlighter: Boolean = false, onPick: (ToolType) -> Unit) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(Unit) {
        val i = pens.indexOf(selected)
        if (i > 3) scroll.scrollTo(with(density) { ((i - 3) * 46).dp.roundToPx() })
    }
    Row(
        Modifier.fillMaxWidth().height(100.dp).horizontalScroll(scroll).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        pens.forEach { t ->
            PenStand(PenArt.of(t, roundHighlighter), ink, t == selected, toolLabel(t), { onPick(t) }, Modifier.testTag("pen-style-${t.name}"))
        }
    }
}

@Composable
private fun LinePills(current: PenLine, onPick: (PenLine) -> Unit) {
    val c = LocalOneUi.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        for (l in PenLine.entries) {
            val label = when (l) { PenLine.FIXED -> "Fixed thickness"; PenLine.VARIABLE -> "Variable thickness"; PenLine.DASHED -> "Dashes"; PenLine.DOTTED -> "Dotted line" }
            ChoicePill(current == l, label, { onPick(l) }) {
                val y = size.height / 2; val x0 = size.width * 0.2f; val x1 = size.width * 0.8f
                val w = 2.6.dp.toPx()
                when (l) {
                    PenLine.FIXED -> drawLine(c.text, Offset(x0, y), Offset(x1, y), w, StrokeCap.Round)
                    PenLine.VARIABLE -> {
                        // Swells in the middle, tapers at both ends.
                        val p = androidx.compose.ui.graphics.Path()
                        val n = 16
                        for (i in 0..n) { val t = i / n.toFloat(); val hw = w * (0.25f + 0.75f * sin(t * PI).toFloat()) * 0.6f; val x = x0 + (x1 - x0) * t; if (i == 0) p.moveTo(x, y - hw) else p.lineTo(x, y - hw) }
                        for (i in n downTo 0) { val t = i / n.toFloat(); val hw = w * (0.25f + 0.75f * sin(t * PI).toFloat()) * 0.6f; p.lineTo(x0 + (x1 - x0) * t, y + hw) }
                        p.close(); drawPath(p, c.text)
                    }
                    PenLine.DASHED -> drawLine(c.text, Offset(x0, y), Offset(x1, y), w, StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(w * 2.4f, w * 2f)))
                    PenLine.DOTTED -> drawLine(c.text, Offset(x0, y), Offset(x1, y), w, StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(0.01f, w * 1.9f)))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FavouritesGrid(tools: ToolState, deleting: Boolean, host: PopupHost) {
    val c = LocalOneUi.current
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tools.favourites.forEach { p ->
            val on = tools.active == EditorTool.PEN && tools.currentPreset() == p
            Box(
                Modifier.size(width = 60.dp, height = 84.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (on) c.pillSelected else c.pill)
                    .clickable(role = Role.Button) {
                        if (deleting) host.update { s -> s.copy(favourites = s.favourites - p) } else { host.update { it.apply(p) }; host.close() }
                    }
                    .semantics { contentDescription = "${toolLabel(p.tool)} favourite"; selected = on },
            ) {
                Canvas(Modifier.size(60.dp, 84.dp)) {
                    // A short sample of the preset's line, then its pen lying under it.
                    val ink = Color(host.display(p.color))
                    val path = androidx.compose.ui.graphics.Path()
                    for (i in 0..20) { val t = i / 20f; val x = size.width * (0.15f + 0.7f * t); val yy = size.height * 0.22f + 6.dp.toPx() * sin(t * 2.2f * PI).toFloat(); if (i == 0) path.moveTo(x, yy) else path.lineTo(x, yy) }
                    drawPath(path, ink, style = Stroke((p.width * 0.6f).coerceIn(1.2f, 6f) * density, cap = StrokeCap.Round))
                    val art = Size(16.dp.toPx(), 30.dp.toPx())
                    drawContext.transform.translate((size.width - art.width) / 2, size.height * 0.42f)
                    drawPenArt(PenArt.of(p.tool), ink, art)
                    drawContext.transform.translate(-(size.width - art.width) / 2, -size.height * 0.42f)
                }
                if (deleting) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp).clip(CircleShape).background(c.danger), contentAlignment = Alignment.Center) {
                    FolioIcon(FIcon.MINUS, null, tint = Color.White, size = 14.dp)
                }
            }
        }
        if (!deleting) Box(
            Modifier.size(width = 60.dp, height = 84.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, c.textSoft.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                .clickable(role = Role.Button) { host.update { s -> if (s.currentPreset() in s.favourites) s else s.copy(favourites = s.favourites + s.currentPreset()) } }
                .semantics { contentDescription = "Add current pen to favourites" },
            contentAlignment = Alignment.Center,
        ) { FolioIcon(FIcon.PLUS, null, size = 26.dp) }
    }
    if (tools.favourites.isEmpty()) Text(
        "Tap + to keep the current pen, its colour and size here.", style = OneUiType.small, color = c.textSoft,
        modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
    )
    Spacer(Modifier.height(12.dp))
}

/**
 * The pen popup shrunk to a strip docked at the left edge (teardown §5): favourites, the pen,
 * its colour and its thickness; tapping one of those opens a vertical list of choices.
 */
@Composable
fun PenMiniStrip(host: PopupHost, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalOneUi.current
    val tools = host.tools
    var list by remember { mutableStateOf<String?>(null) }
    val ink = Color(host.display(tools.colorOf(EditorTool.PEN)))
    Column(
        modifier.width(52.dp).clip(RoundedCornerShape(26.dp)).background(c.popup).border(1.dp, c.popupRim, RoundedCornerShape(26.dp))
            .padding(vertical = 8.dp).testTag("pen-mini"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (list) {
            null -> {
                IconAction(FIcon.FAV_PENS, "Favourite pens", { list = "fav" })
                IconAction(FIcon.of(tools.pen), toolLabel(tools.pen), { list = "pen" }, ink = ink)
                Box(Modifier.size(40.dp).clip(CircleShape).clickable { list = "colour" }.semantics { contentDescription = "Pen colour" }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(ink))
                }
                Box(Modifier.size(40.dp).clip(CircleShape).clickable { list = "size" }.semantics { contentDescription = "Pen thickness" }, contentAlignment = Alignment.Center) {
                    val d = (4f + 14f * (SizeScale.stepOf(tools.pen, tools.width) / 50f)).dp
                    Box(Modifier.size(d).clip(CircleShape).background(c.icon))
                }
                Box(Modifier.padding(vertical = 4.dp).width(24.dp).height(1.dp).background(c.divider))
                IconAction(FIcon.EXPAND, "Full pen settings", onExpand)
                IconAction(FIcon.CLOSE, "Close", host.close)
            }
            else -> {
                when (list) {
                    "pen" -> (ToolState.PENS + ToolType.HIGHLIGHTER).forEach { t ->
                        IconAction(FIcon.of(t, tools.highlighterRound), toolLabel(t), { host.update { it.selectPen(t) }; list = null }, selected = tools.tool == t, ink = ink)
                    }
                    "colour" -> (tools.recentColors.take(3) + app.folio.notes.editor.oneui.FolioPalettes.INK).distinct().take(9).forEach { col ->
                        Box(Modifier.size(40.dp).clip(CircleShape).clickable { host.update { it.withColorFor(EditorTool.PEN, col) }; list = null }.semantics { contentDescription = "Colour ${"%06X".format(col and 0xFFFFFF)}" }, contentAlignment = Alignment.Center) {
                            Box(Modifier.size(24.dp).clip(CircleShape).background(Color(host.display(col))).border(1.dp, c.popupRim, CircleShape))
                        }
                    }
                    "size" -> tools.quickSizesOf(tools.pen).forEachIndexed { i, w ->
                        Box(Modifier.size(40.dp).clip(CircleShape).clickable { host.update { it.withWidth(it.pen, w) }; list = null }.semantics { contentDescription = "Thickness ${i + 1}" }, contentAlignment = Alignment.Center) {
                            Box(Modifier.size((5 + i * 5).dp).clip(CircleShape).background(c.icon))
                        }
                    }
                    "fav" -> tools.favourites.forEach { p ->
                        IconAction(FIcon.of(p.tool), "${toolLabel(p.tool)} favourite", { host.update { it.apply(p) }; list = null }, ink = Color(host.display(p.color)))
                    }
                }
                Box(Modifier.padding(vertical = 4.dp).width(24.dp).height(1.dp).background(c.divider))
                IconAction(FIcon.MINIMISE, "Collapse", { list = null })
            }
        }
    }
}

// ------------------------------------------------------------------ highlighter

@Composable
private fun HighlighterPopup(host: PopupHost) {
    val c = LocalOneUi.current
    val tools = host.tools
    val ink = Color(host.display(tools.colorOf(EditorTool.HIGHLIGHTER)))
    OneUiPopup(host.close, anchorBottomPx = host.anchorBottomPx, modifier = Modifier.testTag("highlighter-popup")) {
        PopupHeader("Highlighter", onClose = host.close)
        Row(Modifier.fillMaxWidth().height(100.dp), horizontalArrangement = Arrangement.Center) {
            PenStand(PenArt.HIGHLIGHTER, ink, !tools.highlighterRound, "Highlighter (chisel tip)", { host.update { it.copy(highlighterRound = false, active = EditorTool.HIGHLIGHTER) } })
            Spacer(Modifier.width(40.dp))
            PenStand(PenArt.HIGHLIGHTER_ROUND, ink, tools.highlighterRound, "Marker pen (round tip)", { host.update { it.copy(highlighterRound = true, active = EditorTool.HIGHLIGHTER) } })
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
        Spacer(Modifier.height(4.dp))
        SizeSlider(ToolType.HIGHLIGHTER, host)
        OpacitySlider(tools.highlighterOpacity, ink, { v -> host.update { it.copy(highlighterOpacity = v) } })
        SwitchRow("Straighten lines", tools.straightenHighlighter, { on -> host.update { it.copy(straightenHighlighter = on) } })
        SwitchRow("Adjust thickness to text", tools.adjustHighlighterToText, { on -> host.update { it.copy(adjustHighlighterToText = on) } })
        ToolPalette(EditorTool.HIGHLIGHTER, host)
    }
}

// ------------------------------------------------------------------ eraser

@Composable
private fun EraserPopup(host: PopupHost) {
    val c = LocalOneUi.current
    val tools = host.tools
    var confirm by remember { mutableStateOf(false) }
    OneUiPopup(host.close, anchorBottomPx = host.anchorBottomPx, modifier = Modifier.testTag("eraser-popup")) {
        PopupHeader("Handwriting eraser", onClose = host.close)
        RadioRow("Stroke eraser", !tools.eraserArea, { host.update { it.copy(eraserArea = false, active = EditorTool.ERASER) } }, subtitle = "Deletes each whole stroke it touches")
        RadioRow("Area eraser", tools.eraserArea, { host.update { it.copy(eraserArea = true, active = EditorTool.ERASER) } }, subtitle = "Erases only where you rub")
        if (tools.eraserArea) StepSlider(
            SizeScale.stepIn(SizeScale.ERASER_DP, SizeScale.SHORT_STEPS, tools.eraserSizeDp), 1..SizeScale.SHORT_STEPS,
            { v -> host.update { it.copy(eraserSizeDp = SizeScale.widthIn(SizeScale.ERASER_DP, SizeScale.SHORT_STEPS, v)) } }, "Eraser size",
        )
        SwitchRow("Erase highlighter only", tools.eraseHighlighterOnly, { on -> host.update { it.copy(eraseHighlighterOnly = on, eraseTapeOnly = if (on) false else it.eraseTapeOnly) } })
        SwitchRow("Erase tape only", tools.eraseTapeOnly, { on -> host.update { it.copy(eraseTapeOnly = on, eraseHighlighterOnly = if (on) false else it.eraseHighlighterOnly) } })
        Spacer(Modifier.height(6.dp))
        DottedDivider()
        Box(
            Modifier.fillMaxWidth().height(56.dp).clickable(role = Role.Button) { confirm = true }.padding(horizontal = 22.dp),
            contentAlignment = Alignment.CenterStart,
        ) { Text("Erase all handwriting", style = OneUiType.bodyStrong, color = c.text) }
        Spacer(Modifier.height(6.dp))
    }
    if (confirm) OneUiDialog(
        { confirm = false }, "Erase all handwriting?", "Every stroke on this page will be erased. You can undo this.",
        "Erase", { host.close(); host.eraseAll() }, destructive = true,
    )
}

// ------------------------------------------------------------------ selection and text

@Composable
private fun SelectionPopup(host: PopupHost) {
    val tools = host.tools
    OneUiPopup(host.close, anchorBottomPx = host.anchorBottomPx, modifier = Modifier.testTag("selection-popup")) {
        PopupHeader("Selection", onClose = host.close)
        RadioRow("Lasso", tools.lassoShape == LassoShape.FREEFORM, { host.update { it.copy(lassoShape = LassoShape.FREEFORM, active = EditorTool.SELECTION) }; host.close() })
        RadioRow("Rectangle", tools.lassoShape == LassoShape.RECTANGLE, { host.update { it.copy(lassoShape = LassoShape.RECTANGLE, active = EditorTool.SELECTION) }; host.close() })
        SwitchRow("Include partially selected objects", tools.selectPartial, { on -> host.update { it.copy(selectPartial = on) } })
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun TextPopup(host: PopupHost) {
    val tools = host.tools
    OneUiPopup(host.close, anchorBottomPx = host.anchorBottomPx, modifier = Modifier.testTag("text-popup")) {
        PopupHeader("Text", onClose = host.close)
        listOf(20f to "Small", 28f to "Medium", 40f to "Large", 56f to "Extra large").forEach { (size, label) ->
            RadioRow("$label text", tools.textSize == size, { host.update { it.copy(textSize = size) } })
        }
        SectionLabel("Text colour uses the pen colour.")
        ToolPalette(EditorTool.PEN, host)
    }
}

// ------------------------------------------------------------------ quick sizes and colours

@Composable
private fun QuickSizeCard(slot: Int, host: PopupHost) {
    val tools = host.tools
    val t = tools.sizeTool
    val restyle = tools.active == EditorTool.CHANGE_STYLE
    val w = tools.quickSizesOf(if (restyle) tools.pen else t).getOrElse(slot) { tools.width }
    OneUiPopup(host.close, anchorBottomPx = host.anchorBottomPx, gap = 8.dp, modifier = Modifier.testTag("quick-size-card")) {
        val tt = if (restyle) tools.pen else t
        StepSlider(
            SizeScale.stepOf(tt, w), 1..SizeScale.stepsOf(tt),
            { step ->
                val nw = SizeScale.widthOf(tt, step)
                host.update { s -> s.withQuickSize(tt, slot, nw).let { if (restyle) it.copy(restyleWidth = nw) else it.withWidth(tt, nw) } }
            },
            "Thickness ${slot + 1}",
        )
    }
}

@Composable
private fun QuickColorPicker(slot: Int, host: PopupHost) {
    val tools = host.tools
    val current = tools.quickColors.getOrElse(slot) { tools.color }
    ColorPickerPanel(
        current, tools.recentColors,
        onDone = { c ->
            host.close()
            host.update { it.withQuickColor(slot, c).let { s -> if (s.active in setOf(EditorTool.ERASER, EditorTool.SELECTION, EditorTool.TEXT)) s.withColorFor(EditorTool.PEN, c) else s.withColor(c) } }
            host.recolorSelection?.invoke(c)
        },
        onCancel = host.close,
        onEyedropper = { host.close(); host.eyedropper(tools.colorTool, slot) },
        display = host.display, anchorBottomPx = host.anchorBottomPx,
    )
}

// ------------------------------------------------------------------ change style, tape, laser

@Composable
private fun ChangeStylePopup(host: PopupHost) {
    val tools = host.tools
    OneUiPopup(host.close, anchorBottomPx = host.anchorBottomPx, modifier = Modifier.testTag("change-style-popup")) {
        PopupHeader("Change style", onClose = host.close)
        Text(
            "Sweep over strokes to give them this colour and thickness.", style = OneUiType.small, color = LocalOneUi.current.textSoft,
            modifier = Modifier.padding(horizontal = 22.dp),
        )
        StepSlider(
            SizeScale.stepOf(ToolType.FOUNTAIN, tools.restyleWidth), 1..SizeScale.STEPS,
            { step -> host.update { it.copy(restyleWidth = SizeScale.widthOf(ToolType.FOUNTAIN, step)) } }, "Change style size",
        )
        ToolPalette(EditorTool.CHANGE_STYLE, host)
    }
}

@Composable
private fun TapePopup(host: PopupHost) {
    val c = LocalOneUi.current
    val tools = host.tools
    var tab by rememberSaveable { mutableStateOf(if (tools.tapePattern > 0) 1 else 0) }
    var confirm by remember { mutableStateOf(false) }
    OneUiPopup(host.close, anchorBottomPx = host.anchorBottomPx, modifier = Modifier.testTag("tape-popup")) {
        PopupHeader("Tape", onClose = host.close, trailing = {
            IconAction(if (tools.tapeHidden) FIcon.EYE_OFF else FIcon.EYE, if (tools.tapeHidden) "Show tape" else "Hide tape", { host.update { it.copy(tapeHidden = !it.tapeHidden) } }, selected = tools.tapeHidden)
        })
        // Preview: a strip of the tape as it will look.
        Canvas(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp).height(80.dp).clip(RoundedCornerShape(14.dp)).background(c.page)
                .semantics { contentDescription = "Tape preview" },
        ) {
            val h = (12f + 30f * (SizeScale.stepOf(ToolType.TAPE, tools.widthOf(ToolType.TAPE)) - 1) / 9f).dp.toPx()
            drawTapeStrip(tools.tapePattern, tools.colorOf(EditorTool.TAPE), Offset(18.dp.toPx(), size.height / 2), size.width - 36.dp.toPx(), h)
        }
        SizeSlider(ToolType.TAPE, host)
        SwitchRow("Straighten lines", tools.straightenTape, { on -> host.update { it.copy(straightenTape = on) } })
        SwitchRow("Adjust thickness to text", tools.adjustTapeToText, { on -> host.update { it.copy(adjustTapeToText = on) } })
        DottedDivider(Modifier.padding(vertical = 6.dp))
        PillTabs(listOf("Colour", "Pattern"), tab, { tab = it; if (it == 0) host.update { s -> s.copy(tapePattern = 0) } }, Modifier.padding(horizontal = 22.dp))
        Spacer(Modifier.height(8.dp))
        if (tab == 0) ToolPalette(EditorTool.TAPE, host)
        else PatternGrid(tools.tapePattern, tools.colorOf(EditorTool.TAPE)) { p -> host.update { it.copy(tapePattern = p, active = EditorTool.TAPE) } }
        DottedDivider(Modifier.padding(vertical = 6.dp))
        Box(Modifier.fillMaxWidth().height(52.dp).clickable(role = Role.Button) { confirm = true }.padding(horizontal = 22.dp), contentAlignment = Alignment.CenterStart) {
            Text("Remove all tape", style = OneUiType.bodyStrong, color = c.text)
        }
    }
    if (confirm) OneUiDialog({ confirm = false }, "Remove all tape?", "All tape on this page will be removed. You can undo this.", "Remove", { host.close(); host.removeAllTape() }, destructive = true)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PatternGrid(selected: Int, color: Long, onPick: (Int) -> Unit) {
    val c = LocalOneUi.current
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 22.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (p in 1..9) {
            val on = p == selected
            Box(
                Modifier.size(width = 76.dp, height = 30.dp).clip(RoundedCornerShape(6.dp))
                    .border(if (on) 2.dp else 0.dp, if (on) c.accent else Color.Transparent, RoundedCornerShape(6.dp))
                    .clickable(role = Role.RadioButton) { onPick(p) }
                    .semantics { contentDescription = TapePatterns.NAMES[p - 1]; this.selected = on },
            ) {
                Canvas(Modifier.size(width = 76.dp, height = 30.dp)) { drawTapeStrip(p, color, Offset(4.dp.toPx(), size.height / 2), size.width - 8.dp.toPx(), 22.dp.toPx(), ends = false) }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

/** A tape strip like the page renderer draws it (same pattern tiles), for previews. */
fun DrawScope.drawTapeStrip(pattern: Int, color: Long, start: Offset, length: Float, height: Float, ends: Boolean = true) {
    drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val rect = android.graphics.RectF(start.x, start.y - height / 2, start.x + length, start.y + height / 2)
        val path = android.graphics.Path()
        if (ends) TapeArt.torn(path, rect) else path.addRect(rect, android.graphics.Path.Direction.CW)
        if (pattern in 1..9) {
            nc.save()
            nc.translate(rect.left, rect.top)
            val scale = height / TapePatterns.TILE_UNITS * 0.9f
            nc.scale(scale, scale)
            paint.shader = TapePatterns.shader(pattern, color)
            val local = android.graphics.Path(path).apply {
                transform(android.graphics.Matrix().apply { setTranslate(-rect.left, -rect.top); postScale(1f / scale, 1f / scale) })
            }
            nc.drawPath(local, paint)
            nc.restore()
        } else {
            paint.color = (color or 0xFF000000).toInt()
            nc.drawPath(path, paint)
        }
    }
}

@Composable
private fun LaserPopup(host: PopupHost) {
    val c = LocalOneUi.current
    val tools = host.tools
    OneUiPopup(host.close, anchorBottomPx = host.anchorBottomPx, placement = PopupPlacement.BELOW_CENTRED, modifier = Modifier.testTag("laser-popup")) {
        PopupHeader("Laser pointer", onClose = host.close)
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            for (style in LaserStyle.entries) {
                val on = tools.laserStyle == style
                val label = when (style) { LaserStyle.SOLID -> "Solid"; LaserStyle.OUTLINED -> "Outlined"; LaserStyle.DOT -> "Dot" }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(width = 78.dp, height = 58.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF111114))
                            .border(if (on) 2.dp else 1.dp, if (on) c.accent else c.popupRim, RoundedCornerShape(14.dp))
                            .clickable(role = Role.RadioButton) { host.update { it.copy(laserStyle = style, active = EditorTool.LASER) } }
                            .semantics { contentDescription = "$label laser"; selected = on },
                    ) {
                        Canvas(Modifier.size(width = 78.dp, height = 58.dp)) {
                            val col = if (tools.laserRainbow) null else Color(tools.colorOf(EditorTool.LASER))
                            drawLaserSample(style, col)
                        }
                    }
                    Text(label, style = OneUiType.small, color = c.text, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        RadioRow("Rainbow", tools.laserRainbow, { host.update { it.copy(laserRainbow = true) } })
        RadioRow("Single colour", !tools.laserRainbow, { host.update { it.copy(laserRainbow = false) } })
        if (!tools.laserRainbow) ToolPalette(EditorTool.LASER, host) else Spacer(Modifier.height(10.dp))
    }
}

/** The laser style chips: a short glowing trail in each style. */
private fun DrawScope.drawLaserSample(style: LaserStyle, color: Color?) {
    val p = androidx.compose.ui.graphics.Path()
    val n = 20
    for (i in 0..n) {
        val t = i / n.toFloat()
        val x = size.width * (0.16f + 0.68f * t); val y = size.height * 0.5f + size.height * 0.2f * sin(t * 2f * PI).toFloat()
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    val brush = if (color == null) Brush.linearGradient(RAINBOW) else androidx.compose.ui.graphics.SolidColor(color)
    when (style) {
        LaserStyle.SOLID -> {
            drawPath(p, brush, alpha = 0.35f, style = Stroke(9.dp.toPx(), cap = StrokeCap.Round))
            drawPath(p, brush, style = Stroke(3.5.dp.toPx(), cap = StrokeCap.Round))
        }
        LaserStyle.OUTLINED -> {
            drawPath(p, brush, alpha = 0.55f, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
            drawPath(p, Color.White, style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round))
        }
        LaserStyle.DOT -> {
            val cpos = Offset(size.width / 2, size.height / 2)
            drawCircle(brush, 12.dp.toPx(), cpos, alpha = 0.3f)
            drawCircle(brush, 6.dp.toPx(), cpos)
        }
    }
}

/** Unused helper kept small: the close button for custom popups. */
@Composable
internal fun PopupClose(onClose: () -> Unit) = CloseButton(onClose)

private val Bold = FontWeight.SemiBold
private val Small = 12.sp
