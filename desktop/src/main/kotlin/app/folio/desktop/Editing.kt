package app.folio.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AutoFixNormal
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.folio.core.model.LineStyle
import app.folio.core.model.Stroke
import app.folio.core.model.StrokeLook
import app.folio.desktop.PageArt.drawStroke
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import app.folio.core.model.Page
import app.folio.core.model.ToolType
import app.folio.core.storage.FileNotebookStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** What a click-and-drag on the page does. VIEW only scrolls, so a notebook can't be marked by accident. */
enum class MacTool(val label: String) { VIEW("View"), PEN("Pen"), HIGHLIGHTER("Highlighter"), ERASER("Eraser"), TEXT("Text") }

/** The pen popup's line pills, as on the tablet. */
enum class MacLine(val label: String) { VARIABLE("Variable"), FIXED("Fixed"), DASHED("Dashed"), DOTTED("Dotted") }

/**
 * Editing an open notebook on the Mac: the tool in use and its settings (the tablet's pen
 * types, line styles, widths and defaults), the pages as edited, and undo/redo.
 * Every edit replaces a page with a new [Page] value (pages are immutable), is saved at once
 * through the store's own atomic writes, in order on one writer thread, and then [onSaved]
 * runs (it asks sync to send the change).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditState(private val store: FileNotebookStore, val notebookId: String, private val onSaved: () -> Unit) {
    var tool by mutableStateOf(MacTool.VIEW)
    /** The tool whose settings popup is open, if any (click the selected tool again). */
    var popup by mutableStateOf<MacTool?>(null)
    /** The Pen tool's type. */
    var pen by mutableStateOf(ToolType.FOUNTAIN)
    var line by mutableStateOf(MacLine.VARIABLE)
    var penColor by mutableStateOf(QUICK_COLOURS[0])
    var highlighterColor by mutableStateOf(0xFFF5D547L)
    /** Highlighter opacity, percent. */
    var highlighterOpacity by mutableIntStateOf(40)
    var highlighterRound by mutableStateOf(false)
    /** Eraser radius in screen pixels. */
    var eraserRadius by mutableStateOf(9f)
    /** Width per tool type, page units (A4 width = 1000). */
    val widths = mutableStateMapOf<ToolType, Float>().apply { putAll(DEFAULT_WIDTHS) }
    /** Pages as they are now, by id (filled as pages load). */
    val pages = mutableStateMapOf<String, Page>()
    /** Shown when a save fails; the edit stays on screen. */
    var error by mutableStateOf<String?>(null)

    private class Step(val pageId: String, val before: Page, val after: Page)
    private val undone = mutableStateListOf<Step>()
    private val done = mutableStateListOf<Step>()
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    val canUndo get() = done.isNotEmpty()
    val canRedo get() = undone.isNotEmpty()

    /** The tool type new ink is stored as. */
    val inkTool: ToolType get() = if (tool == MacTool.HIGHLIGHTER) ToolType.HIGHLIGHTER else pen
    /** The colour the toolbar's dots show and set: the highlighter's own, else the pen's. */
    var color: Long
        get() = if (tool == MacTool.HIGHLIGHTER) highlighterColor else penColor
        set(v) { if (tool == MacTool.HIGHLIGHTER) highlighterColor = v else penColor = v }
    /** Ink colour as stored (the highlighter's carries its opacity). */
    val inkColor: Long get() = if (tool == MacTool.HIGHLIGHTER)
        (highlighterColor and 0xFFFFFF) or (((highlighterOpacity.coerceIn(1, 100) * 255 + 50) / 100).toLong() shl 24)
    else penColor or 0xFF000000
    var inkWidth: Float
        get() = widths[inkTool] ?: 3f
        set(v) { widths[inkTool] = v.coerceIn(sizeRange(inkTool)) }
    /** How new ink looks, from the pen's line pill or the highlighter's tip. */
    val look: StrokeLook get() = when (tool) {
        MacTool.HIGHLIGHTER -> if (highlighterRound) StrokeLook(roundTip = true) else StrokeLook.DEFAULT
        else -> when (line) {
            MacLine.VARIABLE -> StrokeLook.DEFAULT
            MacLine.FIXED -> StrokeLook(fixedWidth = true)
            MacLine.DASHED -> StrokeLook(line = LineStyle.DASHED, fixedWidth = true)
            MacLine.DOTTED -> StrokeLook(line = LineStyle.DOTTED, fixedWidth = true)
        }
    }

    /** A finished stroke with the current look. */
    fun styled(s: Stroke): Stroke = look.let { if (it.isDefault) s else s.withLook(it) }

    fun commit(pageId: String, after: Page) {
        val before = pages[pageId] ?: return
        if (after == before) return
        pages[pageId] = after
        done += Step(pageId, before, after); undone.clear()
        save(after)
    }

    fun undo() { val s = done.removeLastOrNull() ?: return; pages[s.pageId] = s.before; undone += s; save(s.before) }
    fun redo() { val s = undone.removeLastOrNull() ?: return; pages[s.pageId] = s.after; done += s; save(s.after) }

    private fun save(page: Page) {
        writer.launch {
            try { store.savePage(notebookId, page); error = null; onSaved() }
            catch (e: Exception) { error = "Couldn't save the page: ${e.message}. Your change is still on screen; edit again to retry." }
        }
    }

    companion object {
        /** The tablet's default quick colours, pen types, widths and width ranges. */
        val QUICK_COLOURS = listOf(0xFF1B1B1FL, 0xFFD92D20L, 0xFF2F6FE0L, 0xFF23864FL, 0xFFE8701AL)
        val PENS = listOf(ToolType.FOUNTAIN, ToolType.BALLPOINT, ToolType.FINELINER, ToolType.BRUSH, ToolType.MARKER, ToolType.AIRBRUSH, ToolType.PENCIL)
        val DEFAULT_WIDTHS = mapOf(
            ToolType.FOUNTAIN to 3.5f, ToolType.BALLPOINT to 2.5f, ToolType.PENCIL to 3f, ToolType.BRUSH to 8f,
            ToolType.HIGHLIGHTER to 18f, ToolType.FINELINER to 1.8f, ToolType.MARKER to 6f, ToolType.AIRBRUSH to 14f,
        )
        fun sizeRange(tool: ToolType): ClosedFloatingPointRange<Float> = when (tool) {
            ToolType.HIGHLIGHTER -> 6f..40f
            ToolType.AIRBRUSH -> 3f..48f
            else -> 0.8f..16f
        }
        /** Three one-tap sizes per tool: fine, medium (the default width), bold. */
        fun quickSizes(tool: ToolType): List<Float> = (DEFAULT_WIDTHS[tool] ?: 3f).let { d -> listOf(d * 0.6f, d, d * 1.8f).map { it.coerceIn(sizeRange(tool)) } }
        fun penName(t: ToolType) = when (t) {
            ToolType.FOUNTAIN -> "Fountain"; ToolType.BALLPOINT -> "Ballpoint"; ToolType.FINELINER -> "Fineliner"; ToolType.BRUSH -> "Brush"
            ToolType.MARKER -> "Marker"; ToolType.AIRBRUSH -> "Airbrush"; ToolType.PENCIL -> "Pencil"; else -> t.name
        }
    }
}

private val Bar = Color(0xFF1C1C1F)
private val Blue = Color(0xFF2F8BFF)

/**
 * The editor toolbar, laid out like the tablet's: tools, quick colours, quick thicknesses, undo
 * and redo. Clicking the tool that is already selected opens its settings popup under the bar.
 */
@Composable
fun MacToolbar(edit: EditState, modifier: Modifier = Modifier) {
    Box(modifier) {
        Row(
            Modifier.clip(RoundedCornerShape(22.dp)).background(Bar).border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(22.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            @Composable
            fun button(icon: ImageVector, label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) = Box(
                Modifier.padding(horizontal = 2.dp).size(36.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (selected) Color.White.copy(alpha = 0.16f) else Color.Transparent).clickable(enabled = enabled, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, label, Modifier.size(21.dp), tint = Color.White.copy(alpha = if (enabled) 0.92f else 0.3f)) }

            @Composable
            fun tool(icon: ImageVector, t: MacTool, hasPopup: Boolean = true) = button(icon, t.label, edit.tool == t) {
                if (edit.tool == t && hasPopup) edit.popup = if (edit.popup == t) null else t
                else { edit.tool = t; edit.popup = null }
            }

            @Composable
            fun divider() = Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(18.dp).background(Color.White.copy(alpha = 0.18f)))

            tool(Icons.Outlined.PanTool, MacTool.VIEW, hasPopup = false)
            tool(Icons.Outlined.TextFields, MacTool.TEXT, hasPopup = false)
            tool(Icons.Outlined.Edit, MacTool.PEN)
            tool(Icons.Outlined.BorderColor, MacTool.HIGHLIGHTER)
            tool(Icons.Outlined.AutoFixNormal, MacTool.ERASER)
            divider()
            for (col in EditState.QUICK_COLOURS) {
                val on = edit.color == col
                Box(
                    Modifier.padding(horizontal = 4.dp).size(26.dp).clip(CircleShape).clickable {
                        if (edit.tool != MacTool.HIGHLIGHTER && edit.tool != MacTool.TEXT) edit.tool = MacTool.PEN
                        edit.color = col
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(if (on) 22.dp else 18.dp).clip(CircleShape).background(Color(col.toInt())).border(if (on) 2.dp else 1.dp, Color.White.copy(alpha = if (on) 0.9f else 0.35f), CircleShape))
                }
            }
            divider()
            val sizes = EditState.quickSizes(edit.inkTool)
            for (i in 0..2) {
                val on = abs(edit.inkWidth - sizes[i]) < 0.01f
                Box(
                    Modifier.padding(horizontal = 2.dp).size(34.dp).clip(RoundedCornerShape(10.dp)).background(if (on) Color.White.copy(alpha = 0.16f) else Color.Transparent).clickable { edit.inkWidth = sizes[i] },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(20.dp)) { drawLine(Color.White.copy(alpha = 0.9f), Offset(1f, size.height / 2), Offset(size.width - 1f, size.height / 2), (1.5f + 2.5f * i).dp.toPx(), StrokeCap.Round) }
                }
            }
            divider()
            button(Icons.AutoMirrored.Outlined.Undo, "Undo", false, edit.canUndo) { edit.undo() }
            button(Icons.AutoMirrored.Outlined.Redo, "Redo", false, edit.canRedo) { edit.redo() }
            Spacer(Modifier.width(2.dp))
        }
        val open = edit.popup
        if (open != null) Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, 62), onDismissRequest = { edit.popup = null }, properties = PopupProperties(focusable = true)) {
            Column(
                Modifier.width(430.dp).clip(RoundedCornerShape(26.dp)).background(Color(0xFF26262B)).border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(26.dp)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (open) {
                    MacTool.PEN -> PenPanel(edit)
                    MacTool.HIGHLIGHTER -> HighlighterPanel(edit)
                    MacTool.ERASER -> LabelledSlider("Eraser size", edit.eraserRadius, 4f..40f, "${edit.eraserRadius.roundToInt()}") { edit.eraserRadius = it }
                    else -> Unit
                }
            }
        }
    }
}

/** A wavy sample line drawn with the real ink engine, so each pen type shows what it writes like. */
private fun sample(tool: ToolType, color: Long, width: Float, look: StrokeLook = StrokeLook.DEFAULT): Stroke {
    val n = 40
    val s = Stroke(
        tool = tool, color = color, width = width,
        x = FloatArray(n) { 6f + it * 88f / (n - 1) }, y = FloatArray(n) { 18f + 9f * sin(it * 2 * PI.toFloat() / (n - 1)) },
        pressure = FloatArray(n) { 0.25f + 0.65f * sin(it * PI.toFloat() / (n - 1)) }, tilt = FloatArray(n), orientation = FloatArray(n), t = IntArray(n) { it * 8 },
    )
    return if (look.isDefault) s else s.withLook(look)
}

@Composable
private fun PenPanel(edit: EditState) {
    Text("Pen", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    // Pen types, in the tablet's order.
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (t in EditState.PENS) {
            val on = edit.pen == t
            val art = remember(t, edit.penColor) { PageArt.of(sample(t, edit.penColor or 0xFF000000, (EditState.DEFAULT_WIDTHS[t] ?: 3f).coerceAtMost(9f))) }
            Column(
                Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) Color.White.copy(alpha = 0.14f) else Color.Transparent).clickable { edit.pen = t }.padding(horizontal = 2.dp, vertical = 6.dp).width(50.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Canvas(Modifier.size(50.dp, 26.dp).clip(RoundedCornerShape(6.dp)).background(Color.White)) { scale(size.width / 100f, size.width / 100f, pivot = Offset.Zero) { drawStroke(art) } }
                Text(EditState.penName(t), Modifier.padding(top = 4.dp), color = Color.White.copy(alpha = if (on) 1f else 0.65f), fontSize = 10.sp, maxLines = 1)
            }
        }
    }
    // Line style pills.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (l in MacLine.entries) Text(
            l.label,
            Modifier.clip(RoundedCornerShape(50)).background(if (edit.line == l) Blue else Color.White.copy(alpha = 0.10f)).clickable { edit.line = l }.padding(horizontal = 14.dp, vertical = 7.dp),
            color = Color.White, fontSize = 13.sp,
        )
    }
    val r = EditState.sizeRange(edit.pen)
    LabelledSlider("Thickness", edit.widths[edit.pen] ?: 3f, r, "%.1f".format(edit.widths[edit.pen] ?: 3f)) { edit.widths[edit.pen] = it }
}

@Composable
private fun HighlighterPanel(edit: EditState) {
    Text("Highlighter", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    val w = edit.widths[ToolType.HIGHLIGHTER] ?: 18f
    LabelledSlider("Thickness", w, EditState.sizeRange(ToolType.HIGHLIGHTER), "${w.roundToInt()}") { edit.widths[ToolType.HIGHLIGHTER] = it }
    LabelledSlider("Opacity", edit.highlighterOpacity.toFloat(), 10f..100f, "${edit.highlighterOpacity}%") { edit.highlighterOpacity = it.roundToInt() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Round tip", Modifier.weight(1f), color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
        Switch(edit.highlighterRound, { edit.highlighterRound = it })
    }
}

@Composable
private fun LabelledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: String, onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(label, Modifier.weight(1f), color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
            Text(shown, color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp)
        }
        Slider(value.coerceIn(range), onChange, valueRange = range, colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Blue, inactiveTrackColor = Color.White.copy(alpha = 0.16f)))
    }
}
