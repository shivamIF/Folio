package app.folio.notes.editor

import android.app.Activity
import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.folio.core.ink.PressureCurve
import app.folio.core.ink.SelectionTransform
import app.folio.core.model.LineStyle
import app.folio.core.model.Stroke
import app.folio.core.model.StrokeLook
import app.folio.core.model.ToolType
import app.folio.notes.editor.oneui.ColorMath
import app.folio.notes.editor.oneui.FIcon
import app.folio.notes.editor.oneui.FolioIcon
import app.folio.notes.editor.oneui.IconAction
import app.folio.notes.editor.oneui.LocalOneUi
import app.folio.notes.editor.oneui.MenuCard
import app.folio.notes.editor.oneui.MenuEntry
import app.folio.notes.editor.oneui.OneUiDialog
import app.folio.notes.editor.oneui.OneUiType
import app.folio.notes.editor.oneui.ProvideOneUi
import app.folio.notes.ui.glass.LocalGlassBackdrop
import app.folio.notes.ui.glass.SurfaceBackdropSampler
import app.folio.notes.ui.glass.rememberGlassBackdrop
import app.folio.notes.export.PageExportSpec
import kotlinx.coroutines.launch

@Composable
fun EditorScreen(notebookId: String, onClose: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: EditorViewModel = viewModel(key = "editor-$notebookId", factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = EditorViewModel(app, notebookId) as T
    })
    val state by vm.state.collectAsStateWithLifecycle()
    // Glass over the page blurs a quarter-resolution copy of it (the page is a SurfaceView).
    val glassBackdrop = rememberGlassBackdrop()
    androidx.compose.runtime.CompositionLocalProvider(LocalGlassBackdrop provides glassBackdrop) {
        ProvideOneUi { EditorContent(vm, state, onClose) }
    }
}

@Composable
private fun EditorContent(vm: EditorViewModel, state: EditorUiState, onClose: () -> Unit) {
    val c = LocalOneUi.current
    val context = LocalContext.current

    // Page navigator: dialogs open for a page id (kept across rotation), notices with Undo, sharing.
    var deleteFor by rememberSaveable { mutableStateOf<String?>(null) }
    var moveFor by rememberSaveable { mutableStateOf<String?>(null) }
    var exportFor by rememberSaveable { mutableStateOf<String?>(null) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var customising by rememberSaveable { mutableStateOf(false) }
    var popup by remember { mutableStateOf<OpenPopup?>(null) }
    var penMini by rememberSaveable { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var moreSheet by remember { mutableStateOf<MoreSheet?>(null) }
    var insertMenu by remember { mutableStateOf(false) }
    var zoomPercent by remember { mutableIntStateOf(100) }
    var zoomTick by remember { mutableIntStateOf(0) }
    val pageActions = remember { PageMenuActions(onMove = { moveFor = it }, onDelete = { deleteFor = it }, onExport = { exportFor = it }) }
    val snackbar = remember { SnackbarHostState() }
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
                    vm.reportError("No app on this device can receive the file.")
                }
                is PageEvent.Print -> context.getSystemService(android.print.PrintManager::class.java)
                    ?.print(e.title, app.folio.notes.pdf.PdfPrintAdapter(e.file, e.title), null)
                    ?: vm.reportError("Printing isn't available on this device.")
            }
        }
    }

    // Make acknowledged edits durable as soon as the app leaves the foreground.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.flush() }
    val close = { vm.flush(); onClose() }
    BackHandler {
        when {
            state.eyedropper != null -> vm.cancelEyedropper()
            state.fullScreen -> vm.setFullScreen(false)
            state.reading -> vm.setReading(false)
            else -> close()
        }
    }
    FullScreenEffect(state.fullScreen)

    val tools = state.tools
    val meta = state.meta
    // Dark pages with lightened ink while the app is dark, unless the note turned it off.
    val darkPages = c.dark && (meta?.darkAdjust ?: true)
    val display: (Long) -> Long = remember(darkPages) { { col -> if (darkPages) ColorMath.adaptForDark(col) else col } }

    var canvasRef by remember { mutableStateOf<InkCanvasView?>(null) }
    var viewportTick by remember { mutableIntStateOf(0) }
    GlassPageBackdrop(canvasRef, viewportTick, state.pages, popup, insertMenu, moreMenu)
    val pickImage = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri -> val area = canvasRef?.visiblePageArea(); if (uri != null && area != null) vm.insertImage(uri, area.first, area.second) }
    val insertPicture = {
        pickImage.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    val insert = rememberInsertHost(vm, { canvasRef }, insertPicture)
    val actions = remember(vm) {
        object : ToolbarActions {
            override fun selectTool(t: EditorTool) {
                val s = vm.state.value.tools
                if (s.active == t) {
                    // Tapping the selected tool opens its popup.
                    val kind = when (t) {
                        EditorTool.TEXT -> PopupKind.TEXT; EditorTool.PEN -> PopupKind.PEN; EditorTool.HIGHLIGHTER -> PopupKind.HIGHLIGHTER
                        EditorTool.ERASER -> PopupKind.ERASER; EditorTool.SELECTION -> PopupKind.SELECTION; EditorTool.CHANGE_STYLE -> PopupKind.CHANGE_STYLE
                        EditorTool.TAPE -> PopupKind.TAPE; EditorTool.LASER -> PopupKind.LASER
                    }
                    if (t == EditorTool.PEN && penMini) return
                    popup = OpenPopup(kind)
                } else {
                    popup = null
                    vm.updateTools { it.activate(t) }
                }
            }
            override fun quickColor(slot: Int) {
                val s = vm.state.value.tools
                val col = s.quickColors.getOrNull(slot) ?: return
                val activeSlot = s.quickColors.indexOfFirst { it == s.color }
                if (activeSlot == slot && s.active !in setOf(EditorTool.ERASER, EditorTool.SELECTION, EditorTool.TEXT)) {
                    popup = OpenPopup(PopupKind.QUICK_COLOR, slot)
                    return
                }
                popup = null
                if (vm.state.value.selection.isNotEmpty()) { vm.recolorSelection(col); vm.updateTools { it.withColorFor(EditorTool.PEN, col) }; return }
                vm.updateTools { it.withColor(col) }
            }
            override fun quickSize(slot: Int) {
                val s = vm.state.value.tools
                val restyle = s.active == EditorTool.CHANGE_STYLE
                val t = if (restyle) s.pen else s.sizeTool
                val w = s.quickSizesOf(t).getOrNull(slot) ?: return
                val current = if (restyle) s.restyleWidth else s.width
                if (kotlin.math.abs(current - w) < 0.01f) { popup = OpenPopup(PopupKind.QUICK_SIZE, slot); return }
                popup = null
                vm.updateTools { if (restyle) it.copy(restyleWidth = w) else it.withWidth(t, w) }
            }
            override fun undo() = vm.undo()
            override fun redo() = vm.redo()
            override fun toggleSpenToText() = vm.toggleSpenToText()
            override fun toggleEasyPad() = vm.toggleEasyPad()
            override fun toggleAutoFix() = vm.updateTools { it.copy(autoFixShapes = !it.autoFixShapes) }
            override fun toggleLockCanvas() = vm.updateTools { it.copy(lockCanvas = !it.lockCanvas) }
            override fun cleanUp() = vm.cleanUpHandwriting()
            override fun insertPicture() = insertPicture()
            override fun addPage() = vm.addPage()
            override fun togglePages() = vm.toggleSidebar()
            override fun exportPdf() = vm.exportPdf()
            override fun customise() { popup = null; customising = true }
        }
    }

    val host = PopupHost(
        tools = tools, update = vm::updateTools, display = display, close = { popup = null },
        eyedropper = { t, slot -> vm.startEyedropper(t, slot) },
        eraseAll = vm::eraseAllHandwriting, removeAllTape = vm::removeAllTape,
        shrink = { penMini = true }, anchorBottomPx = 0,
        recolorSelection = if (state.selection.isNotEmpty()) vm::recolorSelection else null,
    )

    Box(Modifier.fillMaxSize().background(c.desk)) {
        Column(Modifier.fillMaxSize()) {
            if (!state.fullScreen) {
                // The status bar shows over the app bar's colour.
                Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(c.appBar))
                EditorAppBar(
                    title = meta?.title ?: "",
                    saveLabel = when (state.save) { SaveStatus.SAVED -> null; SaveStatus.PENDING -> null; SaveStatus.SAVING -> null; SaveStatus.ERROR -> "Not saved" },
                    reading = state.reading, laserOn = state.readingLaser, pagesOpen = state.sidebarOpen,
                    onBack = close, onRename = { renaming = true }, onReading = { popup = null; vm.setReading(!state.reading) },
                    onLaser = vm::toggleReadingLaser,
                    insert = {
                        AppBarAction(FIcon.INSERT, "Insert", { insertMenu = true }, tag = "insert") {
                            if (insertMenu) InsertMenu(onDismiss = { insertMenu = false }, onImage = insertPicture, more = insert.actions)
                        }
                    },
                    onPages = vm::toggleSidebar, onFullScreen = { popup = null; vm.setFullScreen(true) },
                    more = {
                        AppBarAction(FIcon.MORE, "More options", { moreMenu = true }, tag = "more") {
                            if (moreMenu) MoreMenu(vm, state, onDismiss = { moreMenu = false }, onSheet = { moreSheet = it })
                            MoreSheetHost(moreSheet, vm, state, onDismiss = { moreSheet = null }, onTrashed = onClose)
                        }
                    },
                )
            } else Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars))
            if (!state.reading) EditorToolbar(
                tools, state.toolbar,
                ToolbarFlags(state.canUndo, state.canRedo, state.spenToText, state.easyPad, state.sidebarOpen, state.selection.isNotEmpty()),
                actions, display,
                popupFor = { open, bottom -> ToolPopup(open, PopupHost(host.tools, host.update, host.display, host.close, host.eyedropper, host.eraseAll, host.removeAllTape, host.shrink, bottom, host.recolorSelection)) },
                open = popup,
            )
            Row(Modifier.fillMaxSize()) {
                // Slides and fades without animating its width, so the canvas is resized once
                // (PageViewport.resize keeps zoom and position) instead of on every frame.
                AnimatedVisibility(
                    visible = state.sidebarOpen && meta != null && !state.reading,
                    enter = slideInHorizontally { -it } + fadeIn(), exit = slideOutHorizontally { -it } + fadeOut(),
                ) {
                    if (meta != null) PageSidebar(
                        vm = vm, pageIds = meta.pageIds, current = state.pageIndex, defaultAspect = meta.pageHeight / meta.pageWidth,
                        actions = pageActions,
                        modifier = Modifier.width(SIDEBAR_WIDTH).fillMaxHeight().padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
                    )
                }
                // Pixels from the canvas's bottom to the window's: the keyboard covers what it rises past that.
                var canvasGap by remember { mutableIntStateOf(0) }
                val covered = (WindowInsets.ime.getBottom(LocalDensity.current) - canvasGap).coerceAtLeast(0)
                BoxWithConstraints(
                    Modifier.weight(1f).fillMaxHeight().clipToBounds()
                        .onGloballyPositioned { canvasGap = (it.findRootCoordinates().size.height - it.boundsInRoot().bottom).toInt() },
                ) {
                    val stack = state.stack
                    if (stack != null) {
                        val pageLook = PageLook(
                            dark = darkPages, pageColor = meta?.pageColor, density = meta?.density ?: app.folio.core.model.PaperDensity.NORMAL,
                            hideTape = tools.tapeHidden, desk = c.desk.toArgb(),
                        )
                        LaunchedEffect(pageLook) { vm.setPageLook(pageLook.copy(desk = 0)) }
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
                                        override fun onStrokesReplaced(pageId: String, after: List<Stroke>) = vm.onStrokesReplaced(pageId, after)
                                        override fun onZoomChanged(percent: Int) { zoomPercent = percent; zoomTick++ }
                                        override fun onColorPicked(color: Long) = vm.onColorPicked(color)
                                        override fun onStickyStroke(pageId: String, stickyId: String, stroke: Stroke) = vm.onStickyStroke(pageId, stickyId, stroke)
                                        override fun onObjectTap(pageId: String, objectId: String, x: Float, y: Float) = vm.onObjectTap(pageId, objectId, x, y)
                                        override fun onPenDown() = vm.onPenDown()
                                        override fun onPenLifted() = vm.onPenLifted()
                                        override fun onStickyTapeTap(pageId: String, stickyId: String) = vm.collapseSticky(pageId, stickyId)
                                        override fun onPadWindowChanged(pageId: String, rect: app.folio.core.model.Rect) = vm.setPadWindow(pageId, rect)
                                    }
                                    paperOf = vm::paperOf
                                    previewOf = { id -> vm.thumbnails.peekAny(id)?.bitmap }
                                    state.meta?.pageIds?.getOrNull(state.pageIndex)?.let { restoreTo(it, state.jump?.seq) }
                                }.also { canvasRef = it })
                            },
                            update = { hostView ->
                                val v = hostView.ink
                                v.tool = tools.tool; v.color = tools.strokeColor; v.penWidth = tools.width
                                v.look = when (tools.active) {
                                    EditorTool.PEN -> when (tools.penLine) {
                                        PenLine.FIXED -> StrokeLook(fixedWidth = true)
                                        PenLine.VARIABLE -> StrokeLook.DEFAULT
                                        PenLine.DASHED -> StrokeLook(line = LineStyle.DASHED, fixedWidth = true)
                                        PenLine.DOTTED -> StrokeLook(line = LineStyle.DOTTED, fixedWidth = true)
                                    }
                                    EditorTool.HIGHLIGHTER -> if (tools.highlighterRound) StrokeLook(roundTip = true) else StrokeLook.DEFAULT
                                    EditorTool.TAPE -> StrokeLook(pattern = tools.tapePattern)
                                    else -> StrokeLook.DEFAULT
                                }
                                v.stabilization = tools.stabilization; v.eraserSelected = tools.eraser
                                v.eraserPartial = tools.eraserArea; v.eraserSizeDp = tools.eraserSizeDp
                                v.eraseHighlighterOnly = tools.eraseHighlighterOnly; v.eraseTapeOnly = tools.eraseTapeOnly
                                v.textMode = tools.text && !state.reading
                                v.straighten = tools.straighten; v.adjustToText = tools.adjustToText
                                v.autoFixShapes = tools.autoFixShapes; v.lockCanvas = tools.lockCanvas
                                v.selectPartial = tools.selectPartial
                                v.readOnly = state.reading
                                v.laserMode = (tools.active == EditorTool.LASER && !state.reading) || (state.reading && state.readingLaser)
                                v.laserStyle = tools.laserStyle; v.laserRainbow = tools.laserRainbow; v.laserColor = tools.colorOf(EditorTool.LASER)
                                v.restyleMode = tools.active == EditorTool.CHANGE_STYLE && !state.reading
                                v.restyleColor = tools.colorOf(EditorTool.CHANGE_STYLE); v.restyleWidth = tools.restyleWidth
                                v.eyedropperActive = state.eyedropper != null
                                v.pageLook = pageLook
                                v.padWindow = if (state.easyPad) state.padWindow else null
                                v.hiddenIds = state.editingText?.let { setOf(it.box.id) } ?: emptySet()
                                v.reportViewport = state.editingText != null || state.selection.isNotEmpty() || state.editingCell != null
                                v.lassoMode = tools.lasso && !state.reading; v.lassoRectangle = tools.lassoShape == LassoShape.RECTANGLE
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
                        // While typing, the page scrolls past the keyboard and the box being typed shows above it.
                        LaunchedEffect(covered, cv) { cv?.setBottomInset(covered.toFloat()) }
                        val cell = state.editingCell
                        LaunchedEffect(covered, editing?.box?.id, editing?.box?.text, cell?.tableId, cell?.row, cell?.col) {
                            if (cv == null || covered == 0) return@LaunchedEffect
                            if (editing != null) {
                                val b = editing.box
                                val lines = b.text.count { it == '\n' } + 1
                                cv.revealRows(editing.pageId, b.y, b.y + maxOf(b.height, lines * b.fontSize * 1.35f))
                            } else if (cell != null) vm.tableOf(cell.pageId, cell.tableId)?.let { t ->
                                val rh = t.height / t.rows
                                cv.revealRows(cell.pageId, t.y + cell.row * rh, t.y + (cell.row + 1) * rh)
                            }
                        }
                        if (editing != null && cv != null) {
                            // The tick re-places the field when the page zooms or scrolls (its other inputs don't change).
                            TextBoxField(editing, cv, viewportTick, onChange = vm::updateEditingText)
                            TextEditBar(
                                onDelete = { vm.updateEditingText(""); vm.commitText() }, onDone = vm::commitText,
                                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                            )
                        }
                        if (cell != null && cv != null) {
                            CellField(vm, cell, cv, viewportTick)
                            CellEditBar(vm, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
                        }
                        if (state.selection.isNotEmpty() && cv != null && !state.reading) {
                            SelectionMenu(vm, state, cv, maxWidth, maxHeight, viewportTick)
                        }
                    }
                    ZoomPill(zoomPercent, zoomTick, onFit = { canvasRef?.fitToWidth() }, modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = if (state.fullScreen) 70.dp else 12.dp))
                    if (state.fullScreen) ExitFullScreenButton({ vm.setFullScreen(false) }, Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 12.dp))
                    if (penMini && !state.reading) PenMiniStrip(
                        host, onExpand = { penMini = false; vm.updateTools { it.activate(EditorTool.PEN) }; popup = OpenPopup(PopupKind.PEN) },
                        modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 8.dp),
                    )
                    if (state.eyedropper != null) EyedropperHint(vm::cancelEyedropper, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
                    SpenToTextBar(vm, state, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
                    EasyWritingPad(vm, state, canvasRef, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp))
    }

    if (penMini && popup?.kind == PopupKind.PEN) popup = null

    // ---- dialogs ----
    if (renaming) RenameDialog(meta?.title ?: "", onRename = vm::rename, onDismiss = { renaming = false })
    if (customising) CustomiseToolbarScreen(state.toolbar, tools, display, onChange = vm::updateToolbar, onClose = { customising = false })
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
        title = { Text("Exporting…") },
        text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
    )
    if (state.exporting) AlertDialog(
        onDismissRequest = {}, confirmButton = {},
        title = { Text("Exporting PDF…") },
        text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
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

/** Full screen hides the system bars (swipe from an edge shows them for a moment). */
@Composable
private fun FullScreenEffect(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(on) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (on) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else controller?.show(WindowInsetsCompat.Type.systemBars())
        onDispose { if (on) controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

@Composable
private fun EyedropperHint(onCancel: () -> Unit, modifier: Modifier) {
    val c = LocalOneUi.current
    Row(
        modifier.shadow(6.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).background(c.popup).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FolioIcon(FIcon.EYEDROPPER, null, size = 20.dp)
        Text("Tap the page to pick a colour", style = OneUiType.label, color = c.text, modifier = Modifier.padding(horizontal = 10.dp))
        IconAction(FIcon.CLOSE, "Cancel eyedropper", onCancel, size = 40.dp, iconSize = 18.dp)
    }
}

/** Rename the note from its title. */
@Composable
private fun RenameDialog(title: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    val c = LocalOneUi.current
    var text by remember { mutableStateOf(title) }
    val focus = remember { FocusRequester() }
    OneUiDialog(onDismiss, "Rename note", confirmLabel = "Rename", onConfirm = { onRename(text) }) {
        BasicTextField(
            text, { text = it.take(120) }, singleLine = true,
            textStyle = TextStyle(color = c.text, fontSize = OneUiType.body.fontSize), cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp)
                .clip(RoundedCornerShape(12.dp)).background(c.field).padding(14.dp).focusRequester(focus)
                .semantics { contentDescription = "Note title" },
        )
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}

/**
 * The floating menu above a selection (teardown §8): Cut · Copy · Paste · Delete · Change style ·
 * Move forwards · ⋮ (Move backwards, Duplicate, Clean up, Deselect).
 */
@Composable
private fun SelectionMenu(
    vm: EditorViewModel, state: EditorUiState, canvas: InkCanvasView, maxW: androidx.compose.ui.unit.Dp, maxH: androidx.compose.ui.unit.Dp,
    @Suppress("UNUSED_PARAMETER") viewportTick: Int,
) {
    val c = LocalOneUi.current
    val density = LocalDensity.current
    val rect = canvas.selectionViewRect() ?: return
    var size by remember { mutableStateOf(IntSize.Zero) }
    var more by remember { mutableStateOf(false) }
    val gap = with(density) { 46.dp.toPx() } // clears the rotate handle
    val w = with(density) { maxW.toPx() }; val h = with(density) { maxH.toPx() }
    val x = (rect.centerX() - size.width / 2f).coerceIn(8f, (w - size.width - 8f).coerceAtLeast(8f))
    val above = rect.top - gap - size.height
    val y = if (above >= 8f) above else (rect.bottom + with(density) { 16.dp.toPx() }).coerceAtMost(h - size.height - 8f)
    Row(
        Modifier.offset { IntOffset(x.toInt(), y.toInt()) }.onSizeChanged { size = it }
            .shadow(8.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(Color(0xFF26262A)).padding(horizontal = 6.dp)
            .testTag("selection-menu"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val count = state.selection.size
        Text("$count selected", style = OneUiType.small, color = Color(0xFFB0B0B6), modifier = Modifier.padding(horizontal = 8.dp))
        vm.selectedSticky()?.let { StickyActions(vm, it.color) }
        SelAction("Cut", vm::cutSelection)
        SelAction("Copy", vm::copySelection)
        if (state.canPaste) SelAction("Paste", vm::paste)
        SelAction("Delete", vm::deleteSelection, description = "Delete selection")
        SelAction("Change style", vm::restyleSelection)
        SelAction("Move forwards", { vm.reorderSelection(true) })
        Box {
            IconAction(FIcon.MORE, "More selection options", { more = true }, tint = Color.White, size = 40.dp, iconSize = 20.dp)
            if (more) MenuCard(
                listOf(
                    listOf(
                        MenuEntry("Move backwards", FIcon.MOVE_BACKWARD) { vm.reorderSelection(false) },
                        MenuEntry("Duplicate", FIcon.DUPLICATE) { vm.duplicateSelection() },
                        MenuEntry("Clean up handwriting", FIcon.CLEAN_UP) { vm.cleanUpHandwriting() },
                    ),
                    listOf(MenuEntry("Deselect", FIcon.CLOSE) { vm.clearSelection() }),
                ),
                onDismiss = { more = false }, width = 240.dp,
            )
        }
    }
}

@Composable
private fun SelAction(label: String, onClick: () -> Unit, description: String = label) {
    Box(
        Modifier.height(44.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Text(label, style = OneUiType.label, color = Color.White) }
}

/** The box being typed, drawn in place over the page at the current zoom. */
@Composable
private fun TextBoxField(edit: TextEdit, canvas: InkCanvasView, @Suppress("UNUSED_PARAMETER") viewportTick: Int, onChange: (String) -> Unit) {
    val c = LocalOneUi.current
    val density = LocalDensity.current
    val b = edit.box
    val scale = canvas.pageScale
    val (ox, oy) = canvas.pageOrigin(edit.pageId) ?: return
    val focus = remember { FocusRequester() }
    val shown = if (canvas.pageLook.dark) ColorMath.adaptForDark(b.color) else b.color
    // The field keeps its own text (a round trip through the view model per key drops fast typing).
    var value by remember(b.id) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(b.text, androidx.compose.ui.text.TextRange(b.text.length))) }
    BasicTextField(
        value = value,
        onValueChange = { v -> value = v; onChange(v.text) },
        textStyle = TextStyle(color = Color(shown.toInt()), fontSize = with(density) { (b.fontSize * scale).toSp() }),
        cursorBrush = SolidColor(c.accent),
        modifier = Modifier
            .offset { IntOffset((ox + b.x * scale).toInt(), (oy + b.y * scale).toInt()) }
            .width(with(density) { (b.width * scale).toDp() })
            .background(Color(0x223E91FF))
            .focusRequester(focus)
            .testTag("text-box-field")
            .semantics { contentDescription = "Text box" },
    )
    LaunchedEffect(edit.box.id) { focus.requestFocus() }
}

@Composable
private fun TextEditBar(onDelete: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalOneUi.current
    Row(
        modifier.shadow(8.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(c.popup).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Text", style = OneUiType.label, color = c.text, modifier = Modifier.padding(horizontal = 10.dp))
        IconAction(FIcon.DELETE, "Delete text box", onDelete)
        IconAction(FIcon.CHECK, "Done typing", onDone)
    }
}

/** Keeps the glass backdrop fed with the page as it scrolls, changes or a popup opens over it. */
@Composable
private fun GlassPageBackdrop(canvas: InkCanvasView?, vararg keys: Any?) {
    val backdrop = LocalGlassBackdrop.current ?: return
    val sampler = remember(canvas) { canvas?.let { SurfaceBackdropSampler(it, backdrop) } }
    androidx.compose.runtime.DisposableEffect(sampler) { onDispose { sampler?.release() } }
    LaunchedEffect(sampler, *keys) { sampler?.request() }
}
