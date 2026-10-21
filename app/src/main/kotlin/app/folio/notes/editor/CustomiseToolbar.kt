package app.folio.notes.editor

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.folio.core.model.ToolType
import app.folio.notes.editor.oneui.FIcon
import app.folio.notes.editor.oneui.FolioIcon
import app.folio.notes.editor.oneui.IconAction
import app.folio.notes.editor.oneui.LocalOneUi
import app.folio.notes.editor.oneui.MenuCard
import app.folio.notes.editor.oneui.MenuEntry
import app.folio.notes.editor.oneui.OneUiType
import app.folio.notes.editor.oneui.PopupPlacement
import app.folio.notes.editor.oneui.drawPaletteGlyph
import kotlin.math.roundToInt

/**
 * Customise toolbar (teardown §17): a full-screen page with a live preview of the toolbar and
 * the list of tools. Long-press a row and drag it to reorder; drag it below the line to hide
 * it. Pen colour and Pen thickness choose how many quick slots show. Reset restores the defaults.
 */
@Composable
fun CustomiseToolbarScreen(config: ToolbarConfig, tools: ToolState, display: (Long) -> Long, onChange: (ToolbarConfig) -> Unit, onClose: () -> Unit) {
    val c = LocalOneUi.current
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(c.appBar).testTag("customise-toolbar-screen")) {
            Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars))
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconAction(FIcon.BACK, "Back", onClose, modifier = Modifier.padding(start = 8.dp))
                Text("Customise toolbar", style = OneUiType.appTitle, color = c.text, modifier = Modifier.weight(1f).padding(start = 8.dp))
                Box(
                    Modifier.height(56.dp).clickable { onChange(ToolbarConfig()) }.padding(horizontal = 20.dp).semantics { contentDescription = "Reset toolbar" },
                    contentAlignment = Alignment.Center,
                ) { Text("Reset", style = OneUiType.bodyStrong, color = c.accent) }
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp)) {
                    SectionTitle("Preview")
                    ToolbarPreview(config, tools, display)
                    SectionTitle("Available tools")
                    Text("Drag tools to remove or reorder them.", style = OneUiType.label, color = c.textSoft, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
                    ToolList(config, onChange)
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = OneUiType.bodyStrong, color = LocalOneUi.current.accent, modifier = Modifier.padding(start = 8.dp, top = 20.dp, bottom = 8.dp))
}

/** A live, non-interactive copy of the toolbar strip. */
@Composable
private fun ToolbarPreview(config: ToolbarConfig, tools: ToolState, display: (Long) -> Long) {
    val c = LocalOneUi.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.toolbar).horizontalScroll(rememberScrollState()).height(48.dp).padding(horizontal = 8.dp)
            .semantics { contentDescription = "Toolbar preview" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        val ink = Color(display(tools.colorOf(EditorTool.PEN)))
        for (ic in listOf(FIcon.TEXT_MODE, FIcon.of(tools.pen), FIcon.HIGHLIGHTER, FIcon.ERASER, FIcon.LASSO)) PreviewIcon(ic, ink)
        PreviewDivider()
        for (item in config.shown) when (item) {
            ToolbarItem.PEN_COLOURS -> tools.quickColors.take(config.colourCount).forEach { col ->
                Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(18.dp).clip(CircleShape).background(Color(display(col)))) }
            }
            ToolbarItem.PEN_SIZES -> repeat(config.sizeCount) { i ->
                Canvas(Modifier.size(width = 30.dp, height = 12.dp)) {
                    val th = (1.6f + i * 1.8f) * density
                    drawLine(c.icon, Offset(5.dp.toPx(), size.height / 2), Offset(size.width - 5.dp.toPx(), size.height / 2), th, StrokeCap.Round)
                }
            }
            else -> PreviewIcon(item.icon, ink)
        }
        PreviewDivider()
        PreviewIcon(FIcon.CUSTOMISE, ink)
    }
}

@Composable
private fun PreviewIcon(icon: FIcon, ink: Color) {
    Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) { FolioIcon(icon, null, ink = ink, size = 22.dp) }
}

@Composable
private fun PreviewDivider() {
    Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(16.dp).background(LocalOneUi.current.textSoft.copy(alpha = 0.5f)))
}

/** The draggable list: shown items, the line, hidden items. */
@Composable
private fun ToolList(config: ToolbarConfig, onChange: (ToolbarConfig) -> Unit) {
    val c = LocalOneUi.current
    val haptics = LocalHapticFeedback.current
    val rowPx = with(LocalDensity.current) { ROW.toPx() }
    val rows = config.rows
    var dragging by remember { mutableStateOf<ToolbarItem?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val current by rememberUpdatedState(config)
    val from = dragging?.let { rows.indexOf(it) } ?: -1
    val target = if (from >= 0) (from + (dragY / rowPx).roundToInt()).coerceIn(0, rows.lastIndex) else -1
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.popup)) {
        rows.forEachIndexed { i, item ->
            // Rows between the dragged row and its target move aside to make room.
            val shift = when {
                from < 0 || i == from -> 0f
                from < target && i in (from + 1)..target -> -rowPx
                from > target && i in target until from -> rowPx
                else -> 0f
            }
            val animShift by animateFloatAsState(shift, label = "shift")
            val lifted = i == from
            Box(
                Modifier.fillMaxWidth().height(ROW)
                    .graphicsLayer { translationY = if (lifted) dragY else animShift; shadowElevation = if (lifted) 12f else 0f }
                    .background(if (lifted) c.pillSelected else c.popup),
            ) {
                if (item == null) LineRow()
                else ToolRow(
                    item, config, onChange,
                    modifier = Modifier.pointerInput(item) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); dragging = item; dragY = 0f },
                            onDrag = { ch, d -> ch.consume(); dragY += d.y },
                            onDragEnd = {
                                val r = current.rows
                                val f = r.indexOf(item)
                                val t = (f + (dragY / rowPx).roundToInt()).coerceIn(0, r.lastIndex)
                                if (t != f) onChange(current.move(item, t))
                                dragging = null; dragY = 0f
                            },
                            onDragCancel = { dragging = null; dragY = 0f },
                        )
                    }.semantics {
                        val r = config.rows
                        val at = r.indexOf(item)
                        customActions = listOfNotNull(
                            CustomAccessibilityAction("Move up") { if (at > 0) onChange(config.move(item, at - 1)); true }.takeIf { at > 0 },
                            CustomAccessibilityAction("Move down") { onChange(config.move(item, at + 1)); true }.takeIf { at < r.lastIndex },
                            if (item in config.shown) CustomAccessibilityAction("Remove from toolbar") { onChange(config.move(item, r.size - 1)); true }
                            else CustomAccessibilityAction("Add to toolbar") { onChange(config.move(item, r.indexOf(null))); true },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun LineRow() {
    val c = LocalOneUi.current
    Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(c.textSoft.copy(alpha = 0.6f)))
        Text("Drag tools below this line to remove them.", style = OneUiType.small, color = c.textSoft, modifier = Modifier.padding(horizontal = 12.dp))
        Box(Modifier.weight(1f).height(1.dp).background(c.textSoft.copy(alpha = 0.6f)))
    }
}

@Composable
private fun ToolRow(item: ToolbarItem, config: ToolbarConfig, onChange: (ToolbarConfig) -> Unit, modifier: Modifier) {
    val c = LocalOneUi.current
    val hidden = item in config.hidden
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxSize().padding(start = 20.dp, end = 12.dp).semantics { contentDescription = item.title + if (hidden) ", hidden" else "" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            when (item) {
                ToolbarItem.PEN_COLOURS -> Canvas(Modifier.size(22.dp)) { drawPaletteGlyph() }
                ToolbarItem.PEN_SIZES -> Canvas(Modifier.size(22.dp)) {
                    for (k in 0..2) drawLine(c.icon, Offset(3.dp.toPx(), (5 + k * 6).dp.toPx()), Offset(size.width - 3.dp.toPx(), (5 + k * 6).dp.toPx()), (1.2f + k * 1.1f) * density, StrokeCap.Round)
                }
                else -> FolioIcon(item.icon, null, tint = if (hidden) c.textSoft else c.icon, size = 24.dp)
            }
        }
        Text(item.title, style = OneUiType.body, color = if (hidden) c.textSoft else c.text, modifier = Modifier.padding(start = 16.dp))
        if (item == ToolbarItem.PEN_COLOURS || item == ToolbarItem.PEN_SIZES) Box {
            val label = if (item == ToolbarItem.PEN_COLOURS) "${config.colourCount} colours" else "${config.sizeCount} thickness${if (config.sizeCount == 1) "" else "es"}"
            Row(
                Modifier.padding(start = 12.dp).clip(RoundedCornerShape(14.dp)).background(c.pill).clickable { menu = true }.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = OneUiType.small, color = c.text)
                FolioIcon(FIcon.CHEVRON_DOWN, null, size = 14.dp, modifier = Modifier.padding(start = 4.dp))
            }
            if (menu) MenuCard(
                listOf(
                    if (item == ToolbarItem.PEN_COLOURS) (ToolbarConfig.MIN_COLOURS..ToolbarConfig.MAX_COLOURS).map { n ->
                        MenuEntry("$n colours", trailing = if (n == config.colourCount) "✓" else null) { onChange(config.copy(colourCount = n)) }
                    } else (ToolbarConfig.MIN_SIZES..ToolbarConfig.MAX_SIZES).map { n ->
                        MenuEntry("$n thickness${if (n == 1) "" else "es"}", trailing = if (n == config.sizeCount) "✓" else null) { onChange(config.copy(sizeCount = n)) }
                    },
                ),
                onDismiss = { menu = false }, width = 200.dp, placement = PopupPlacement.BELOW_START,
            )
        }
        Spacer(Modifier.weight(1f))
        FolioIcon(FIcon.DRAG_HANDLE, "Drag to move ${item.title}", tint = c.textSoft, size = 24.dp)
    }
}

private val ROW = 56.dp

@Suppress("unused") private val unusedTool = ToolType.FOUNTAIN
