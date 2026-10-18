package app.folio.notes.editor

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.AutoFixNormal
import androidx.compose.material.icons.outlined.Draw
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
                    IconButton(onClick = vm::addPage) { Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = "Add page after this one") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            ToolBar(state.tools, vm::updateTools)
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
                                }
                            }
                        },
                        update = { v ->
                            v.tool = tools.tool; v.color = tools.color; v.penWidth = tools.width
                            v.stabilization = tools.stabilization; v.eraserSelected = tools.eraser
                            v.pressureCurve = PressureCurve.fromSensitivity(tools.pressureSensitivity)
                            v.penOnlyPreference = tools.penOnly
                            v.setPage(state.strokes, page.paper, page.width, page.height)
                        },
                        modifier = Modifier.fillMaxSize().clipToBounds().testTag("canvas"),
                    )
                }
            }
        }
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

@Composable
private fun ToolBar(tools: ToolState, update: ((ToolState) -> ToolState) -> Unit) {
    var settings by remember { mutableStateOf(false) }
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TOOL_BUTTONS.forEach { b ->
                val on = !tools.eraser && tools.tool == b.tool
                IconToggleButton(on, onCheckedChange = { update { it.copy(tool = b.tool, eraser = false) } },
                    modifier = Modifier.testTag("tool-${b.tool.name}").semantics { selected = on }) {
                    Icon(b.icon, contentDescription = b.label, tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconToggleButton(tools.eraser, onCheckedChange = { e -> update { it.copy(eraser = e) } }, modifier = Modifier.testTag("tool-eraser")) {
                Icon(Icons.Outlined.AutoFixNormal, contentDescription = "Stroke eraser", tint = if (tools.eraser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(Modifier.width(12.dp))
            ToolState.PALETTE.forEach { c ->
                val sel = c == tools.color
                Box(
                    Modifier.padding(3.dp).size(28.dp).clip(CircleShape).background(Color(c))
                        .border(if (sel) 3.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else Color(0x33000000), CircleShape)
                        .clickable { update { it.copy(color = c, eraser = false) } }
                        .semantics { contentDescription = "Ink colour ${"%06X".format(c and 0xFFFFFF)}"; selected = sel },
                )
            }
            Box(Modifier.width(12.dp))
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

