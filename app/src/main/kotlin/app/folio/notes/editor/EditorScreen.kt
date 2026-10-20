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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import app.folio.notes.ui.GlassIconButton
import app.folio.notes.ui.ProvideGlass
import app.folio.notes.ui.LocalGlass
import app.folio.notes.ui.GlassBackdrop
import app.folio.notes.ui.GlassDivider
import app.folio.notes.ui.glass
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.folio.core.ink.PressureCurve
import app.folio.core.ink.SelectionTransform
import app.folio.core.model.Stroke
import app.folio.notes.R
import app.folio.core.model.ToolType
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.automirrored.outlined.ViewSidebar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.saveable.rememberSaveable
import app.folio.notes.export.PageExportSpec
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(notebookId: String, onClose: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: EditorViewModel = viewModel(key = "editor-$notebookId", factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = EditorViewModel(app, notebookId) as T
    })
    val state by vm.state.collectAsStateWithLifecycle()

    // Page navigator: dialogs open for a page id (kept across rotation), notices with Undo, sharing.
    var deleteFor by rememberSaveable { mutableStateOf<String?>(null) }
    var moveFor by rememberSaveable { mutableStateOf<String?>(null) }
    var exportFor by rememberSaveable { mutableStateOf<String?>(null) }
    val pageActions = remember { PageMenuActions(onMove = { moveFor = it }, onDelete = { deleteFor = it }, onExport = { exportFor = it }) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val saveDocument = androidx.activity.compose.rememberLauncherForActivityResult(CreateDocumentAs()) { uri -> vm.savePageTo(uri) }
    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                is PageEvent.Notice -> launch {
                    snackbar.currentSnackbarData?.dismiss() // the newest notice replaces the last one
                    val r = snackbar.showSnackbar(
                        e.message, actionLabel = if (e.undo != null) "Undo" else null, withDismissAction = true,
                        duration = if (e.undo != null) SnackbarDuration.Long else SnackbarDuration.Short,
                    )
                    if (r == SnackbarResult.ActionPerformed) e.undo?.invoke()
                }
                is PageEvent.Share -> try {
                    sharePage(context, e)
                } catch (_: android.content.ActivityNotFoundException) {
                    vm.reportError("No app on this device can receive the page.")
                }
            }
        }
    }

    // Make acknowledged edits durable as soon as the app leaves the foreground.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.flush() }
    val close = { vm.flush(); onClose() }
    BackHandler(onBack = close)

    ProvideGlass {
    val g = LocalGlass.current
    Box(Modifier.fillMaxSize()) {
        GlassBackdrop()
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            // Glass top bar: back, title and save state, then undo/redo and page actions.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                    .glass(RoundedCornerShape(26.dp), elevation = 12.dp).padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back to library", close)
                GlassIconButton(
                    Icons.AutoMirrored.Outlined.ViewSidebar, if (state.sidebarOpen) "Hide pages" else "Show pages", vm::toggleSidebar,
                    tint = if (state.sidebarOpen) g.accent else null, modifier = Modifier.testTag("toggle-pages"),
                )
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(state.meta?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, color = g.ink, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Text(
                        "Page ${state.pageIndex + 1} of ${state.meta?.pageIds?.size ?: 1} · " + when (state.save) {
                            SaveStatus.SAVED -> "Saved"; SaveStatus.PENDING -> "Editing…"; SaveStatus.SAVING -> "Saving…"; SaveStatus.ERROR -> "Not saved"
                        },
                        style = MaterialTheme.typography.labelMedium, color = g.inkSoft,
                        modifier = Modifier.testTag("save-status"),
                    )
                }
                GlassIconButton(Icons.AutoMirrored.Filled.Undo, "Undo", vm::undo, enabled = state.canUndo, modifier = Modifier.testTag("undo"))
                GlassIconButton(Icons.AutoMirrored.Filled.Redo, "Redo", vm::redo, enabled = state.canRedo, modifier = Modifier.testTag("redo"))
                GlassDivider(Modifier.padding(horizontal = 4.dp))
                GlassIconButton(Icons.AutoMirrored.Filled.NavigateBefore, "Previous page", { vm.openPage(state.pageIndex - 1) }, enabled = state.pageIndex > 0)
                GlassIconButton(Icons.AutoMirrored.Filled.NavigateNext, "Next page", { vm.openPage(state.pageIndex + 1) }, enabled = state.pageIndex < (state.meta?.pageIds?.size ?: 1) - 1)
                GlassIconButton(Icons.AutoMirrored.Filled.NoteAdd, "Add page after this one", vm::addPage)
                GlassIconButton(Icons.Default.PictureAsPdf, "Export notebook as PDF", vm::exportPdf, enabled = !state.exporting)
            }
            var canvasRef by remember { mutableStateOf<InkCanvasView?>(null) }
            var viewportTick by remember { mutableStateOf(0) }
            val pickImage = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
            ) { uri -> val area = canvasRef?.visiblePageArea(); if (uri != null && area != null) vm.insertImage(uri, area.first, area.second) }
            ToolBar(
                state.tools, vm::updateTools, onColorWithSelection = if (state.selection.isNotEmpty()) vm::recolorSelection else null,
                onInsertImage = {
                    pickImage.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            )
            Row(Modifier.fillMaxSize()) {
            // Slides and fades without animating its width, so the canvas is resized once
            // (PageViewport.resize keeps zoom and position) instead of on every frame.
            val meta = state.meta
            AnimatedVisibility(
                visible = state.sidebarOpen && meta != null,
                enter = slideInHorizontally { -it } + fadeIn(), exit = slideOutHorizontally { -it } + fadeOut(),
            ) {
                if (meta != null) PageSidebar(
                    vm = vm, pageIds = meta.pageIds, current = state.pageIndex, defaultAspect = meta.pageHeight / meta.pageWidth,
                    actions = pageActions,
                    modifier = Modifier.width(SIDEBAR_WIDTH).fillMaxHeight().padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
                )
            }
            Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                val stack = state.stack
                if (stack != null) {
                    val tools = state.tools
                    AndroidView(
                        factory = { ctx ->
                            InkCanvasHost(ctx, InkCanvasView(ctx).apply {
                                listener = object : InkCanvasView.Listener {
                                    override fun onStrokeFinished(pageId: String, stroke: Stroke) = vm.onStrokeFinished(pageId, stroke)
                                    override fun onStrokesErased(pageId: String, ids: Set<String>) = vm.onStrokesErased(pageId, ids)
                                    override fun onLassoSelected(pageId: String, ids: Set<String>) = vm.onLassoSelected(pageId, ids)
                                    override fun onSelectionTransformed(pageId: String, ids: Set<String>, transform: SelectionTransform) =
                                        vm.transformSelection(pageId, ids, transform)
                                    override fun onStrokesSplit(pageId: String, pieces: Map<String, List<Stroke>>) = vm.onStrokesSplit(pageId, pieces)
                                    override fun onTextTap(pageId: String, x: Float, y: Float, textId: String?) = vm.onTextTap(pageId, x, y, textId)
                                    override fun onViewportChanged() { viewportTick++ }
                                    override fun onPagesInView(visible: List<String>, current: String) = vm.onPagesInView(visible, current)
                                }
                                paperOf = vm::paperOf
                                previewOf = { id -> vm.thumbnails.peekAny(id)?.bitmap }
                                state.meta?.pageIds?.getOrNull(state.pageIndex)?.let { restoreTo(it, state.jump?.seq) }
                            }.also { canvasRef = it })
                        },
                        update = { host ->
                            val v = host.ink
                            v.tool = tools.tool; v.color = tools.color; v.penWidth = tools.width
                            v.stabilization = tools.stabilization; v.eraserSelected = tools.eraser
                            v.eraserPartial = tools.eraserPartial; v.eraserSizeDp = tools.eraserSizeDp
                            v.eraseHighlighterOnly = tools.eraseHighlighterOnly
                            v.textMode = tools.text
                            v.hiddenIds = state.editingText?.let { setOf(it.box.id) } ?: emptySet()
                            v.reportViewport = state.editingText != null
                            v.lassoMode = tools.lasso; v.lassoRectangle = tools.lassoShape == LassoShape.RECTANGLE
                            v.pressureCurve = PressureCurve.fromSensitivity(tools.pressureSensitivity)
                            v.penOnlyPreference = tools.penOnly
                            v.assets = vm.assets
                            v.setDocument(stack, state.pages)
                            v.setSelection(state.selectionPage, state.selection)
                            state.jump?.let { v.jumpTo(it.seq, it.pageId) }
                        },
                        modifier = Modifier.fillMaxSize().clipToBounds().testTag("canvas"),
                    )
                    val editing = state.editingText
                    val cv = canvasRef
                    if (editing != null && cv != null) {
                        @Suppress("UNUSED_EXPRESSION") viewportTick // re-place the field when the page zooms or scrolls
                        TextBoxField(editing, cv, onChange = vm::updateEditingText)
                        TextEditBar(
                            onDelete = { vm.updateEditingText(""); vm.commitText() }, onDone = vm::commitText,
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                        )
                    }
                    if (state.selection.isNotEmpty()) SelectionBar(
                        count = state.selection.size,
                        onDuplicate = vm::duplicateSelection, onDelete = vm::deleteSelection, onDone = vm::clearSelection,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                    )
                }
            }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(bottom = 16.dp))
    }
    }

    // ---- page navigator dialogs ----
    val pageNumber = { id: String -> (state.meta?.pageIds?.indexOf(id) ?: 0) + 1 }
    deleteFor?.let { id ->
        DeletePageDialog(pageNumber(id), onDelete = { vm.deletePage(id) }, onDismiss = { deleteFor = null })
    }
    moveFor?.let { id ->
        MovePageDialog(pageNumber(id), state.meta?.pageIds?.size ?: 1, onMove = { to -> vm.movePage(id, to) }, onDismiss = { moveFor = null })
    }
    exportFor?.let { id ->
        val size = vm.thumbnails.peekAny(id)?.let { it.pageWidth to it.pageHeight } ?: state.meta?.let { it.pageWidth to it.pageHeight }
        ExportPageDialog(
            pageNumber(id), size,
            onSave = { spec ->
                vm.pendingSave = id to spec
                try {
                    saveDocument.launch(spec.format.mime to vm.exportFileName(id, spec))
                } catch (e: android.content.ActivityNotFoundException) {
                    vm.pendingSave = null
                    vm.reportError("No app on this device can save files. Use Share instead.")
                }
            },
            onShare = { spec -> vm.sharePage(id, spec) },
            onDismiss = { exportFor = null },
        )
    }
    if (state.pageExporting) AlertDialog(
        onDismissRequest = {}, confirmButton = {},
        title = { Text("Exporting page…") },
        text = { androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth()) },
    )

    if (state.exporting) AlertDialog(
        onDismissRequest = {}, confirmButton = {},
        title = { Text("Exporting PDF…") },
        text = { androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth()) },
    )
    state.exported?.let { result ->
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolBar(tools: ToolState, update: ((ToolState) -> ToolState) -> Unit, onColorWithSelection: ((Long) -> Unit)?, onInsertImage: () -> Unit) {
    var settings by remember { mutableStateOf(false) }
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        // Three groups (tools · colours · size/settings) that wrap onto extra rows
        // on narrow screens, so no tool is ever hidden off-screen in portrait.
        FlowRow(
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
          // Tools wrap too, so narrow screens never push a tool (e.g. the eraser) off the edge.
          FlowRow(itemVerticalAlignment = Alignment.CenterVertically) {
            ToolSlot.entries.forEach { ToolSlotButton(it, tools, update) }
            Box {
                var lassoMenu by remember { mutableStateOf(false) }
                // Like the eraser: the first tap picks the lasso (last shape used); a second tap offers the shapes.
                IconToggleButton(
                    tools.lasso,
                    onCheckedChange = { if (tools.lasso) lassoMenu = true else update { it.copy(lasso = true, eraser = false, text = false) } },
                    modifier = Modifier.testTag("tool-lasso"),
                ) {
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
                            onClick = { lassoMenu = false; update { it.copy(lasso = true, eraser = false, text = false, lassoShape = shape) } },
                        )
                    }
                }
            }
            Box {
                var textMenu by remember { mutableStateOf(false) }
                IconToggleButton(
                    tools.text,
                    onCheckedChange = { if (tools.text) textMenu = true else update { it.copy(text = true, eraser = false, lasso = false) } },
                    modifier = Modifier.testTag("tool-text"),
                ) {
                    Icon(Icons.Outlined.TextFields, contentDescription = "Text", tint = if (tools.text) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(textMenu, onDismissRequest = { textMenu = false }) {
                    listOf(20f to "Small", 28f to "Medium", 40f to "Large", 56f to "Extra large").forEach { (size, label) ->
                        DropdownMenuItem(
                            text = { Text("$label text") },
                            trailingIcon = { if (tools.textSize == size) Icon(Icons.Default.Check, contentDescription = "Selected") },
                            onClick = { textMenu = false; update { it.copy(textSize = size) } },
                        )
                    }
                }
            }
            IconButton(onClick = onInsertImage, modifier = Modifier.testTag("insert-image")) {
                Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = "Insert picture", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                var eraserMenu by remember { mutableStateOf(false) }
                // First tap picks the eraser with the type used last time; tapping it again
                // while it's selected opens the options (type, size, highlighter only).
                IconToggleButton(
                    tools.eraser,
                    onCheckedChange = { if (tools.eraser) eraserMenu = true else update { it.copy(eraser = true, lasso = false, text = false) } },
                    modifier = Modifier.testTag("tool-eraser"),
                ) {
                    Icon(painterResource(R.drawable.ms_ink_eraser), contentDescription = "Eraser", tint = if (tools.eraser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
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
                            onClick = { eraserMenu = false; update { it.copy(eraser = true, eraserPartial = partial, lasso = false, text = false) } },
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
                // With a selection, a colour tap recolours it instead of changing the pen.
                Swatch(c, c == tools.color, 36.dp) { if (onColorWithSelection != null) onColorWithSelection(c) else update { it.withColor(c) } }
            }
          }
          Row(verticalAlignment = Alignment.CenterVertically) {
            SizePresets(tools.tool, tools, update)
            Box {
                IconButton(onClick = { settings = true }) { Icon(Icons.Default.Tune, contentDescription = "Pen settings") }
                DropdownMenu(settings, onDismissRequest = { settings = false }) {
                    Column(Modifier.padding(16.dp).width(260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Only the pen writes (fingers scroll and zoom)", modifier = Modifier.weight(1f))
                            Switch(tools.penOnly, { p -> update { it.copy(penOnly = p) } })
                        }
                        Text(
                            "Hold the S Pen button while writing to erase. Tap the selected pen again for its style, size and colour.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
          }
        }
    }
}

/** The box being typed, drawn in place over the page at the current zoom. */
@Composable
private fun TextBoxField(edit: TextEdit, canvas: InkCanvasView, onChange: (String) -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val b = edit.box
    val scale = canvas.pageScale
    val (ox, oy) = canvas.pageOrigin(edit.pageId) ?: return
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.foundation.text.BasicTextField(
        value = b.text,
        onValueChange = onChange,
        textStyle = androidx.compose.ui.text.TextStyle(
            color = Color(b.color.toInt()),
            fontSize = with(density) { (b.fontSize * scale).toSp() },
        ),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier
            .offset { androidx.compose.ui.unit.IntOffset((ox + b.x * scale).toInt(), (oy + b.y * scale).toInt()) }
            .width(with(density) { (b.width * scale).toDp() })
            .background(Color(0x143D5A80))
            .focusRequester(focus)
            .testTag("text-box-field")
            .semantics { contentDescription = "Text box" },
    )
    LaunchedEffect(edit.box.id) { focus.requestFocus() }
}

@Composable
private fun TextEditBar(onDelete: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    ProvideGlass {
    Box(modifier.glass(RoundedCornerShape(26.dp))) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Text", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp))
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Delete text box") }
            IconButton(onClick = onDone) { Icon(Icons.Default.Check, contentDescription = "Done typing") }
        }
    }
    }
}

@Composable
private fun SelectionBar(count: Int, onDuplicate: () -> Unit, onDelete: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    ProvideGlass {
    Box(modifier.glass(RoundedCornerShape(26.dp))) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$count selected", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp))
            IconButton(onClick = onDuplicate) { Icon(Icons.Outlined.ContentCopy, contentDescription = "Duplicate selection") }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Delete selection") }
            IconButton(onClick = onDone) { Icon(Icons.Default.Check, contentDescription = "Clear selection") }
        }
    }
    }
}
