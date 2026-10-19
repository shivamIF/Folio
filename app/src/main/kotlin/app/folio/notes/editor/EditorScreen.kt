package app.folio.notes.editor

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.outlined.AutoFixNormal
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.HighlightAlt
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.folio.core.ink.PressureCurve
import app.folio.core.ink.SelectionTransform
import app.folio.core.model.Stroke
import app.folio.core.model.ToolType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(notebookId: String, onClose: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: EditorViewModel = viewModel(key = "editor-$notebookId", factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = EditorViewModel(app, notebookId) as T
    })
    val state by vm.state.collectAsStateWithLifecycle()

    // Make acknowledged edits durable as soon as the app leaves the foreground.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.flush() }
    val close = { vm.flush(); onClose() }
    BackHandler(onBack = close)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.meta?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "Page ${state.pageIndex + 1} of ${state.meta?.pageIds?.size ?: 1} · " + when (state.save) {
                                SaveStatus.SAVED -> "Saved"; SaveStatus.PENDING -> "Editing…"; SaveStatus.SAVING -> "Saving…"; SaveStatus.ERROR -> "Not saved"
                            },
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("save-status"),
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to library") } },
                actions = {
                    IconButton(onClick = vm::undo, enabled = state.canUndo, modifier = Modifier.testTag("undo")) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo") }
                    IconButton(onClick = vm::redo, enabled = state.canRedo, modifier = Modifier.testTag("redo")) { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo") }
                    IconButton(onClick = { vm.openPage(state.pageIndex - 1) }, enabled = state.pageIndex > 0) {
                        Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = "Previous page")
                    }
                    IconButton(onClick = { vm.openPage(state.pageIndex + 1) }, enabled = state.pageIndex < (state.meta?.pageIds?.size ?: 1) - 1) {
                        Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = "Next page")
                    }
                    IconButton(onClick = vm::exportPdf, enabled = !state.exporting) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = "Export as PDF")
                    }
                    IconButton(onClick = vm::addPage) { Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = "Add page after this one") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            ToolBar(state.tools, vm::updateTools, onColorWithSelection = if (state.selection.isNotEmpty()) vm::recolorSelection else null)
            Box(Modifier.fillMaxSize().clipToBounds()) {
                val page = state.page
                if (page != null) {
                    val tools = state.tools
                    AndroidView(
                        factory = { ctx ->
                            InkCanvasView(ctx).apply {
                                listener = object : InkCanvasView.Listener {
                                    override fun onStrokeFinished(stroke: Stroke) = vm.onStrokeFinished(stroke)
                                    override fun onStrokesErased(ids: Set<String>) = vm.onStrokesErased(ids)
                                    override fun onLassoSelected(ids: Set<String>) = vm.onLassoSelected(ids)
                                    override fun onSelectionTransformed(ids: Set<String>, transform: SelectionTransform) =
                                        vm.transformSelection(ids, transform)
                                    override fun onStrokesSplit(pieces: Map<String, List<Stroke>>) = vm.onStrokesSplit(pieces)
                                }
                            }
                        },
                        update = { v ->
                            v.tool = tools.tool; v.color = tools.color; v.penWidth = tools.width
                            v.stabilization = tools.stabilization; v.eraserSelected = tools.eraser
                            v.pdfBackground = state.background
                            v.eraserPartial = tools.eraserPartial; v.eraserSizeDp = tools.eraserSizeDp
                            v.eraseHighlighterOnly = tools.eraseHighlighterOnly
                            v.lassoMode = tools.lasso; v.lassoRectangle = tools.lassoShape == LassoShape.RECTANGLE; v.selection = state.selection
                            v.pressureCurve = PressureCurve.fromSensitivity(tools.pressureSensitivity)
                            v.penOnlyPreference = tools.penOnly
                            v.setPage(page.id, state.strokes, page.paper, page.width, page.height)
                        },
                        modifier = Modifier.fillMaxSize().clipToBounds().testTag("canvas"),
                    )
                    if (state.selection.isNotEmpty()) SelectionBar(
                        count = state.selection.size,
                        onDuplicate = vm::duplicateSelection, onDelete = vm::deleteSelection, onDone = vm::clearSelection,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                    )
                }
            }
        }
    }

    if (state.exporting) AlertDialog(
        onDismissRequest = {}, confirmButton = {},
        title = { Text("Exporting PDF…") },
        text = { androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth()) },
    )
    state.exported?.let { result ->
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = vm::dismissExport,
            title = { Text("PDF exported") },
            text = { Text("Saved to Downloads/Folio/${result.fileName}") },
            confirmButton = {
                TextButton(onClick = {
                    vm.dismissExport()
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(android.content.Intent.EXTRA_STREAM, result.uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(android.content.Intent.createChooser(send, "Share PDF"))
                }) { Text("Share") }
            },
            dismissButton = { TextButton(onClick = vm::dismissExport) { Text("OK") } },
        )
    }
    state.error?.let { msg ->
        AlertDialog(onDismissRequest = vm::dismissError, title = { Text("Problem") }, text = { Text(msg) },
            confirmButton = { TextButton(onClick = vm::dismissError) { Text("OK") } })
    }
}

private data class ToolButton(val tool: ToolType, val label: String, val icon: ImageVector)

private val TOOL_BUTTONS = listOf(
    ToolButton(ToolType.FOUNTAIN, "Fountain pen", Icons.Default.Create),
    ToolButton(ToolType.BALLPOINT, "Ballpoint pen", Icons.Default.Edit),
    ToolButton(ToolType.PENCIL, "Pencil", Icons.Outlined.Draw),
    ToolButton(ToolType.BRUSH, "Brush pen", Icons.Default.Brush),
    ToolButton(ToolType.HIGHLIGHTER, "Highlighter", Icons.Default.Highlight),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolBar(tools: ToolState, update: ((ToolState) -> ToolState) -> Unit, onColorWithSelection: ((Long) -> Unit)?) {
    var settings by remember { mutableStateOf(false) }
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        // Three groups (tools · colours · size/settings) that wrap onto extra rows
        // on narrow screens, so no tool is ever hidden off-screen in portrait.
        FlowRow(
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            TOOL_BUTTONS.forEach { b ->
                val on = !tools.eraser && !tools.lasso && tools.tool == b.tool
                IconToggleButton(on, onCheckedChange = { update { it.copy(tool = b.tool, eraser = false, lasso = false) } },
                    modifier = Modifier.testTag("tool-${b.tool.name}").semantics { selected = on }) {
                    Icon(b.icon, contentDescription = b.label, tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box {
                var lassoMenu by remember { mutableStateOf(false) }
                // Tapping the lasso always offers both shapes, like the pen chooser in note apps.
                IconToggleButton(tools.lasso, onCheckedChange = { lassoMenu = true }, modifier = Modifier.testTag("tool-lasso")) {
                    Icon(
                        if (tools.lassoShape == LassoShape.RECTANGLE) Icons.Outlined.HighlightAlt else Icons.Outlined.Gesture,
                        contentDescription = "Lasso select",
                        tint = if (tools.lasso) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(lassoMenu, onDismissRequest = { lassoMenu = false }) {
                    LassoShape.entries.forEach { shape ->
                        val label = if (shape == LassoShape.FREEFORM) "Freeform lasso" else "Rectangle select"
                        DropdownMenuItem(
                            text = { Text(label) },
                            leadingIcon = { Icon(if (shape == LassoShape.FREEFORM) Icons.Outlined.Gesture else Icons.Outlined.HighlightAlt, contentDescription = null) },
                            trailingIcon = { if (tools.lasso && tools.lassoShape == shape) Icon(Icons.Default.Check, contentDescription = "Selected") },
                            onClick = { lassoMenu = false; update { it.copy(lasso = true, eraser = false, lassoShape = shape) } },
                        )
                    }
                }
            }
            Box {
                var eraserMenu by remember { mutableStateOf(false) }
                // First tap picks the eraser with the type used last time; tapping it again
                // while it's selected opens the options (type, size, highlighter only).
                IconToggleButton(
                    tools.eraser,
                    onCheckedChange = { if (tools.eraser) eraserMenu = true else update { it.copy(eraser = true, lasso = false) } },
                    modifier = Modifier.testTag("tool-eraser"),
                ) {
                    Icon(Icons.Outlined.AutoFixNormal, contentDescription = "Eraser", tint = if (tools.eraser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(eraserMenu, onDismissRequest = { eraserMenu = false }) {
                    listOf(false to "Stroke eraser", true to "Partial eraser").forEach { (partial, label) ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(label)
                                    Text(
                                        if (partial) "Erases only where you rub, like a real eraser" else "Deletes each whole stroke it touches",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            trailingIcon = { if (tools.eraser && tools.eraserPartial == partial) Icon(Icons.Default.Check, contentDescription = "Selected") },
                            onClick = { eraserMenu = false; update { it.copy(eraser = true, eraserPartial = partial, lasso = false) } },
                        )
                    }
                    // The whole row toggles (not just the small switch).
                    Row(
                        Modifier.width(312.dp).toggleable(
                            value = tools.eraseHighlighterOnly, role = androidx.compose.ui.semantics.Role.Switch,
                            onValueChange = { on -> update { it.copy(eraseHighlighterOnly = on) } },
                        ).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Erase highlighter only", modifier = Modifier.weight(1f))
                        Switch(tools.eraseHighlighterOnly, onCheckedChange = null)
                    }
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).width(280.dp)) {
                        Text("Eraser size", style = MaterialTheme.typography.labelLarge)
                        Slider(
                            tools.eraserSizeDp, { v -> update { it.copy(eraserSizeDp = v) } }, valueRange = 4f..60f,
                            modifier = Modifier.semantics { contentDescription = "Eraser size" },
                        )
                    }
                }
            }
          }
          Row(verticalAlignment = Alignment.CenterVertically) {
            ToolState.PALETTE.forEach { c ->
                val sel = c == tools.color
                // Selected: an outer ring with a gap, so even black stays visible on a dark toolbar.
                Box(
                    Modifier.size(36.dp).clip(CircleShape)
                        .border(if (sel) 2.5.dp else 0.dp, if (sel) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                        .padding(5.dp).clip(CircleShape).background(Color(c))
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), CircleShape)
                        // With a selection, a colour tap recolours it instead of changing the pen.
                        .clickable { if (onColorWithSelection != null) onColorWithSelection(c) else update { it.copy(color = c, eraser = false, lasso = false) } }
                        .semantics { contentDescription = "Ink colour ${"%06X".format(c and 0xFFFFFF)}"; selected = sel },
                )
            }
          }
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Size", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = tools.width, onValueChange = { w -> update { it.copy(widths = it.widths + (it.tool to w)) } },
                valueRange = if (tools.tool == ToolType.HIGHLIGHTER) 6f..40f else 0.8f..16f,
                modifier = Modifier.width(160.dp).semantics { contentDescription = "Pen size" },
            )
            Box {
                IconButton(onClick = { settings = true }) { Icon(Icons.Default.Tune, contentDescription = "Pen settings") }
                DropdownMenu(settings, onDismissRequest = { settings = false }) {
                    Column(Modifier.padding(16.dp).width(260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Stabilisation: ${(tools.stabilization * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                        Slider(tools.stabilization, { s -> update { it.copy(stabilization = s) } }, valueRange = 0f..1f,
                            modifier = Modifier.semantics { contentDescription = "Stroke stabilisation" })
                        Text("Pen pressure", style = MaterialTheme.typography.labelLarge)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Light hand", style = MaterialTheme.typography.bodySmall)
                            Slider(
                                tools.pressureSensitivity, { v -> update { it.copy(pressureSensitivity = v) } }, valueRange = 0f..1f,
                                modifier = Modifier.weight(1f).semantics { contentDescription = "Pen pressure sensitivity" },
                            )
                            Text("Firm hand", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            "Light hand: thick lines with a gentle touch. Applies to new strokes.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Only the pen writes (fingers scroll and zoom)", modifier = Modifier.weight(1f))
                            Switch(tools.penOnly, { p -> update { it.copy(penOnly = p) } })
                        }
                        Text(
                            "Hold the S Pen button while writing to erase.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
          }
        }
    }
}

@Composable
private fun SelectionBar(count: Int, onDuplicate: () -> Unit, onDelete: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp), tonalElevation = 6.dp, shadowElevation = 6.dp, modifier = modifier) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$count selected", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp))
            IconButton(onClick = onDuplicate) { Icon(Icons.Outlined.ContentCopy, contentDescription = "Duplicate selection") }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Delete selection") }
            IconButton(onClick = onDone) { Icon(Icons.Default.Check, contentDescription = "Clear selection") }
        }
    }
}
