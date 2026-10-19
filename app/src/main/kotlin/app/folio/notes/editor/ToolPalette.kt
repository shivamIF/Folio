package app.folio.notes.editor

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import app.folio.core.ink.PenProfile
import app.folio.core.model.Argb
import app.folio.core.model.ToolType
import app.folio.notes.R
import kotlin.math.PI
import kotlin.math.sin

/*
 * Writing-tool slots and their option dropdowns. The section order (style, thickness, colour,
 * then feel) follows the common note-app pattern; icons are Material Symbols (Apache-2.0) and
 * the palette is Folio's own.
 */

@DrawableRes
fun toolIcon(t: ToolType): Int = when (t) {
    ToolType.FOUNTAIN -> R.drawable.ms_ink_pen
    ToolType.BALLPOINT -> R.drawable.ms_stylus
    ToolType.BRUSH -> R.drawable.ms_brush
    ToolType.PENCIL -> R.drawable.ms_edit
    ToolType.HIGHLIGHTER -> R.drawable.ms_ink_highlighter
}

fun toolLabel(t: ToolType): String = when (t) {
    ToolType.FOUNTAIN -> "Fountain pen"
    ToolType.BALLPOINT -> "Ballpoint pen"
    ToolType.BRUSH -> "Brush pen"
    ToolType.PENCIL -> "Pencil"
    ToolType.HIGHLIGHTER -> "Highlighter"
}

/** The toolbar's writing slots: one pen slot (fountain/ballpoint/brush), pencil, highlighter. */
enum class ToolSlot(val title: String) {
    PEN("Pen"), PENCIL("Pencil"), HIGHLIGHTER("Highlighter");

    fun toolIn(s: ToolState): ToolType = when (this) {
        PEN -> s.lastPen; PENCIL -> ToolType.PENCIL; HIGHLIGHTER -> ToolType.HIGHLIGHTER
    }

    companion object {
        fun of(t: ToolType): ToolSlot = when (t) {
            ToolType.PENCIL -> PENCIL; ToolType.HIGHLIGHTER -> HIGHLIGHTER; else -> PEN
        }
    }
}

/**
 * One writing slot. Tapping an inactive slot selects it; tapping the active slot (or long-pressing
 * any slot) opens its dropdown.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ToolSlotButton(slot: ToolSlot, tools: ToolState, update: ((ToolState) -> ToolState) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val tool = slot.toolIn(tools)
    val active = !tools.eraser && ToolSlot.of(tools.tool) == slot
    val ink = Color(tools.color)
    Box {
        Column(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                .combinedClickable(
                    onClick = { if (active) open = true else update { it.select(tool) } },
                    onLongClick = { update { it.select(tool) }; open = true },
                )
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .testTag("tool-${tool.name}")
                .semantics {
                    contentDescription = toolLabel(tool); selected = active; role = Role.Tab
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painterResource(toolIcon(tool)), contentDescription = null,
                // The active tool wears its ink colour, unless that colour would vanish on the chip.
                tint = when {
                    !active -> MaterialTheme.colorScheme.onSurfaceVariant
                    ink.luminance() > 0.85f -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> ink
                },
                modifier = Modifier.size(26.dp).offset(y = if (active) (-1).dp else 0.dp),
            )
            Icon(
                Icons.Default.ArrowDropDown, contentDescription = null,
                tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer else Color.Transparent,
                modifier = Modifier.size(14.dp).offset(y = (-3).dp),
            )
        }
        DropdownMenu(
            open, onDismissRequest = { open = false }, offset = DpOffset(0.dp, 6.dp),
            shape = RoundedCornerShape(16.dp), modifier = Modifier.testTag("tool-dropdown-${slot.name}"),
        ) {
            ToolOptions(slot, tools, update)
        }
    }
}

@Composable
private fun ToolOptions(slot: ToolSlot, tools: ToolState, update: ((ToolState) -> ToolState) -> Unit) {
    val tool = slot.toolIn(tools)
    Column(Modifier.width(312.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(slot.title, style = MaterialTheme.typography.titleMedium)
        StrokePreview(tool, tools.widths[tool] ?: 3f, tools.color)

        if (slot == ToolSlot.PEN) {
            Section("PEN STYLE")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ToolState.PENS.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = tools.tool == t, onClick = { update { it.select(t) } },
                        shape = SegmentedButtonDefaults.itemShape(i, ToolState.PENS.size),
                        icon = { Icon(painterResource(toolIcon(t)), contentDescription = null, modifier = Modifier.size(18.dp)) },
                        modifier = Modifier.testTag("pen-style-${t.name}"),
                    ) { Text(toolLabel(t).removeSuffix(" pen"), maxLines = 1) }
                }
            }
        }

        Section("THICKNESS")
        val width = tools.widths[tool] ?: 3f
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SizePresets(tool, tools, update)
            Slider(
                width, { w -> update { it.copy(widths = it.widths + (tool to w)) } }, valueRange = ToolState.sizeRange(tool),
                modifier = Modifier.weight(1f).semantics { contentDescription = "${toolLabel(tool)} size" },
            )
            Text("%.1f".format(width), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(32.dp))
        }

        Section("COLOUR")
        var recent by remember { mutableStateOf(false) }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(!recent, { recent = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Palette") }
            SegmentedButton(recent, { recent = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Recent") }
        }
        if (!recent) {
            ToolState.SWATCHES.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    row.forEach { c -> Swatch(c, tools.color == c, 34.dp) { update { it.withColor(c) } } }
                }
            }
        } else if (tools.recentColors.isEmpty()) {
            Text(
                "Colours you pick show up here.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                tools.recentColors.forEach { c -> Swatch(c, tools.color == c, 34.dp) { update { it.withColor(c) } } }
            }
        }

        if (tool != ToolType.HIGHLIGHTER) {
            Section("PRESSURE SENSITIVITY")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Light", style = MaterialTheme.typography.bodySmall)
                Slider(
                    tools.pressureSensitivity, { v -> update { it.copy(pressureSensitivity = v) } }, valueRange = 0f..1f,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = "Pen pressure sensitivity" },
                )
                Text("Firm", style = MaterialTheme.typography.bodySmall)
            }
        }
        Section("STROKE STABILISATION")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                tools.stabilization, { s -> update { it.copy(stabilization = s) } }, valueRange = 0f..1f,
                modifier = Modifier.weight(1f).semantics { contentDescription = "Stroke stabilisation" },
            )
            Text("${(tools.stabilization * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp).width(36.dp))
        }
        Text(
            "Pen settings apply to new strokes.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/** Fine / medium / bold one-tap sizes for [tool]; each dot is drawn at its relative size. */
@Composable
fun SizePresets(tool: ToolType, tools: ToolState, update: ((ToolState) -> ToolState) -> Unit) {
    val presets = ToolState.SIZE_PRESETS.getValue(tool)
    val current = tools.widths[tool] ?: 3f
    val names = listOf("Fine", "Medium", "Bold")
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        presets.forEachIndexed { i, w ->
            val sel = kotlin.math.abs(current - w) < 0.05f
            Box(
                Modifier.size(32.dp).clip(CircleShape)
                    .background(if (sel) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable { update { it.copy(widths = it.widths + (tool to w), eraser = false) } }
                    .semantics { contentDescription = "${names[i]} size"; selected = sel },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size((6 + i * 5).dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface))
            }
        }
    }
}

@Composable
fun Swatch(c: Argb, sel: Boolean, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .border(2.dp, if (sel) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
            .padding(4.dp).clip(CircleShape).background(Color(c))
            .border(1.dp, Color(0x22000000), CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Ink colour ${"%06X".format(c and 0xFFFFFF)}"; selected = sel },
    )
}

/** A short wave drawn with the tool's width and opacity, so changes show before you write. */
@Composable
private fun StrokePreview(tool: ToolType, width: Float, color: Argb) {
    val profile = PenProfile.of(tool)
    val ink = Color(color).copy(alpha = Color(color).alpha * profile.opacity)
    Canvas(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).semantics { contentDescription = "Stroke preview" },
    ) {
        val pad = 24.dp.toPx()
        val w = size.width - 2 * pad
        val mid = size.height / 2
        val amp = size.height * 0.22f
        val path = Path()
        val n = 48
        for (i in 0..n) {
            val x = pad + w * i / n
            val y = mid - amp * sin(i.toFloat() / n * 2f * PI.toFloat())
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val px = (width * density * 0.75f).coerceAtLeast(1f)
        drawPath(
            path, ink,
            style = Stroke(width = px, cap = if (tool == ToolType.HIGHLIGHTER) StrokeCap.Square else StrokeCap.Round),
        )
    }
}
