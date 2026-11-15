package app.folio.notes.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.folio.core.model.ToolType
import app.folio.notes.editor.oneui.FIcon
import app.folio.notes.editor.oneui.FolioIcon
import app.folio.notes.editor.oneui.IconAction
import app.folio.notes.editor.oneui.LocalOneUi
import app.folio.notes.editor.oneui.OneUiType
import app.folio.notes.editor.oneui.RAINBOW
import app.folio.notes.ui.glass.DockController
import app.folio.notes.ui.glass.DockPosition
import app.folio.notes.ui.glass.GlassDock
import app.folio.notes.ui.glass.GlassRole
import app.folio.notes.ui.glass.LocalDockEdge
import app.folio.notes.ui.glass.LocalGlassSettings
import app.folio.notes.ui.glass.dockCell
import app.folio.notes.ui.glass.glassSpec
import androidx.compose.foundation.border
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.delay

/** Which popup is open, and for which quick slot. */
enum class PopupKind { PEN, HIGHLIGHTER, ERASER, SELECTION, TEXT, CHANGE_STYLE, TAPE, LASER, QUICK_COLOR, QUICK_SIZE, SPEN_SETTINGS }

data class OpenPopup(val kind: PopupKind, val slot: Int = -1)

/** What the toolbar's buttons do, implemented by the editor screen. */
interface ToolbarActions {
    fun selectTool(t: EditorTool)
    fun quickColor(slot: Int)
    fun quickSize(slot: Int)
    fun undo()
    fun redo()
    fun toggleSpenToText()
    fun toggleEasyPad()
    fun toggleAutoFix()
    fun toggleLockCanvas()
    fun cleanUp()
    fun insertPicture()
    fun addPage()
    fun togglePages()
    fun exportPdf()
    fun customise()
}

/** Toggle/flag state the toolbar shows besides the tools. */
data class ToolbarFlags(
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val spenToText: Boolean = false,
    val easyPad: Boolean = false,
    val pagesOpen: Boolean = false,
    val hasSelection: Boolean = false,
)

// ------------------------------------------------------------------ app bar

/**
 * The editor's app bar (teardown §18): Back, the title (tap to rename), then Reading mode,
 * Insert, Page sorter, Full screen and More. In Reading mode the page sorter gives way to the
 * laser pointer switch.
 */
@Composable
fun EditorAppBar(
    title: String,
    saveLabel: String?,
    reading: Boolean,
    laserOn: Boolean,
    pagesOpen: Boolean,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onReading: () -> Unit,
    onLaser: () -> Unit,
    insert: @Composable () -> Unit,
    onPages: () -> Unit,
    onFullScreen: () -> Unit,
    more: @Composable () -> Unit,
) {
    val c = LocalOneUi.current
    Row(Modifier.fillMaxWidth().height(56.dp).background(c.appBar), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.width(60.dp).fillMaxHeight().clickable(role = Role.Button, onClick = onBack)
                .semantics { contentDescription = "Back to library" }.testTag("back"),
            contentAlignment = Alignment.Center,
        ) { FolioIcon(FIcon.BACK, null, size = 24.dp) }
        Box(
            Modifier.weight(1f).fillMaxHeight().clickable(role = Role.Button, onClickLabel = "Rename note", onClick = onRename),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = OneUiType.appTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (saveLabel != null) Text(
                    saveLabel, style = OneUiType.small, color = c.textSoft,
                    modifier = Modifier.padding(start = 10.dp).testTag("save-status").semantics { contentDescription = saveLabel },
                )
            }
        }
        AppBarAction(FIcon.READING_MODE, if (reading) "Exit reading mode" else "Reading mode", onReading, selected = reading)
        if (reading) AppBarAction(FIcon.LASER, if (laserOn) "Laser pointer off" else "Laser pointer on", onLaser, selected = laserOn)
        insert()
        if (!reading) AppBarAction(FIcon.PAGE_SORTER, if (pagesOpen) "Hide page sorter" else "Page sorter", onPages, selected = pagesOpen, tag = "toggle-pages")
        AppBarAction(FIcon.FULL_SCREEN, "Full screen", onFullScreen)
        more()
        Spacer(Modifier.width(6.dp))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
}

@Composable
fun AppBarAction(icon: FIcon, description: String, onClick: () -> Unit, selected: Boolean = false, tag: String? = null, content: @Composable () -> Unit = {}) {
    val c = LocalOneUi.current
    Box(
        Modifier.width(46.dp).height(56.dp).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description; this.selected = selected }
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(38.dp).clip(CircleShape).background(c.toolSelected))
        FolioIcon(icon, null, tint = if (selected) c.accent else c.icon, size = 24.dp)
        content()
    }
}

// ------------------------------------------------------------------ toolbar strip

/**
 * The toolbar strip under the app bar (teardown §3): fixed tools │ customisable items │
 * Customise. Centred when it fits; scrolls sideways when it doesn't (portrait). First tap
 * selects a tool; tapping the selected tool opens its popup ([popupFor] draws popups in place,
 * anchored under their button).
 */
@Composable
fun EditorToolbar(
    tools: ToolState,
    config: ToolbarConfig,
    flags: ToolbarFlags,
    actions: ToolbarActions,
    display: (Long) -> Long,
    popupFor: @Composable (OpenPopup, anchorBottomPx: Int) -> Unit,
    open: OpenPopup?,
    /** Glass on: draw as the floating dock over the page (else the glass bar under the app bar). */
    floating: Boolean = false,
    controller: DockController? = null,
    modifier: Modifier = Modifier,
) {
    val c = LocalOneUi.current
    val glass = LocalGlassSettings.current
    var bottomPx by remember { mutableIntStateOf(0) }
    if (!glass.enabled) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
        BoxWithConstraints(
            modifier.fillMaxWidth().height(42.dp).background(c.toolbar)
                .onGloballyPositioned { bottomPx = (it.localToWindow(Offset(0f, it.size.height.toFloat())).y).toInt() },
        ) {
            Row(
                Modifier.fillMaxHeight().horizontalScroll(rememberScrollState()).widthIn(min = maxWidth).padding(horizontal = 6.dp)
                    .testTag("toolbar"),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) { ToolbarItems(tools, config, flags, actions, display, popupFor, open, bottomPx, flat = false) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
        return
    }
    // Glass: the macOS-Dock toolbar. Popups open away from the edge it sits on. The dock moves
    // every frame while it magnifies, so its edge is kept in a plain holder (read when a popup
    // opens), never in state: writing state here would recompose every button each frame.
    val edgePx = remember { IntArray(1) }
    val bottomDock = floating && glass.dockPosition == DockPosition.BOTTOM
    val cell = glass.dockIconSize.dp
    CompositionLocalProvider(
        LocalToolCell provides ToolCell(cell, cell, cell * 0.56f, cell * 0.8f),
        LocalDockEdge provides if (bottomDock) DockPosition.BOTTOM else DockPosition.TOP,
    ) {
        GlassDock(
            position = if (bottomDock) DockPosition.BOTTOM else DockPosition.TOP,
            hold = open != null, fullWidth = !floating, controller = controller,
            modifier = modifier.testTag("toolbar").onGloballyPositioned {
                edgePx[0] = if (bottomDock) it.localToWindow(Offset.Zero).y.toInt()
                else it.localToWindow(Offset(0f, it.size.height.toFloat())).y.toInt()
            },
        ) { ToolbarItems(tools, config, flags, actions, display, popupFor, open, edgePx[0], flat = true) }
    }
}

/**
 * A working sample of the toolbar dock for Settings › Glass & toolbar: hover it with the S Pen
 * to feel the magnification. Its buttons do nothing.
 */
@Composable
fun DockPreview(modifier: Modifier = Modifier) {
    val glass = LocalGlassSettings.current
    val cell = glass.dockIconSize.dp
    val c = LocalOneUi.current
    CompositionLocalProvider(LocalToolCell provides ToolCell(cell, cell, cell * 0.56f, cell * 0.8f)) {
        GlassDock(DockPosition.TOP, modifier.padding(vertical = 8.dp)) {
            ToolButton(FIcon.TEXT_MODE, "Text", false, {})
            ToolButton(FIcon.of(ToolType.FOUNTAIN), "Fountain pen", true, {}, ink = c.accent)
            ToolButton(FIcon.of(ToolType.HIGHLIGHTER, false), "Highlighter", false, {}, ink = Color(0xFFF2C230))
            ToolButton(FIcon.ERASER, "Eraser", false, {})
            ToolButton(FIcon.LASSO, "Selection", false, {})
            ToolbarDivider()
            ToolButton(FIcon.UNDO, "Undo", false, {})
            ToolButton(FIcon.REDO, "Redo", false, {})
            ToolButton(FIcon.TAPE, "Tape", false, {}, ink = Color(0xFF8DB3FF))
            ToolButton(FIcon.IMAGE, "Insert picture", false, {})
        }
    }
}

/** Size of a toolbar cell: the One UI strip's 43 × 42 dp, or the dock's icon size. */
data class ToolCell(val width: androidx.compose.ui.unit.Dp, val height: androidx.compose.ui.unit.Dp, val icon: androidx.compose.ui.unit.Dp, val selected: androidx.compose.ui.unit.Dp)

val LocalToolCell = staticCompositionLocalOf { ToolCell(43.dp, 42.dp, 24.dp, 34.dp) }

/** The toolbar's items in order (teardown §3). [flat] emits each quick colour/size as its own cell (the dock magnifies cells). */
@Composable
private fun ToolbarItems(
    tools: ToolState, config: ToolbarConfig, flags: ToolbarFlags, actions: ToolbarActions, display: (Long) -> Long,
    popupFor: @Composable (OpenPopup, anchorBottomPx: Int) -> Unit, open: OpenPopup?, bottomPx: Int, flat: Boolean,
) {
    val ink = Color(display(tools.colorOf(EditorTool.PEN)))
    ToolButton(FIcon.TEXT_MODE, "Text", tools.active == EditorTool.TEXT, { actions.selectTool(EditorTool.TEXT) }, tag = "tool-text") {
        if (open?.kind == PopupKind.TEXT) popupFor(open, bottomPx)
    }
    ToolButton(FIcon.of(tools.pen), toolLabel(tools.pen), tools.active == EditorTool.PEN, { actions.selectTool(EditorTool.PEN) }, ink = ink, tag = "tool-pen") {
        if (open?.kind == PopupKind.PEN) popupFor(open, bottomPx)
    }
    ToolButton(
        FIcon.of(ToolType.HIGHLIGHTER, tools.highlighterRound), "Highlighter", tools.active == EditorTool.HIGHLIGHTER,
        { actions.selectTool(EditorTool.HIGHLIGHTER) }, ink = Color(display(tools.colorOf(EditorTool.HIGHLIGHTER))), tag = "tool-HIGHLIGHTER",
    ) { if (open?.kind == PopupKind.HIGHLIGHTER) popupFor(open, bottomPx) }
    ToolButton(FIcon.ERASER, "Eraser", tools.active == EditorTool.ERASER, { actions.selectTool(EditorTool.ERASER) }, tag = "tool-eraser") {
        if (open?.kind == PopupKind.ERASER) popupFor(open, bottomPx)
    }
    ToolButton(
        if (tools.lassoShape == LassoShape.RECTANGLE) FIcon.RECT_SELECT else FIcon.LASSO, "Selection",
        tools.active == EditorTool.SELECTION, { actions.selectTool(EditorTool.SELECTION) }, tag = "tool-lasso",
    ) { if (open?.kind == PopupKind.SELECTION) popupFor(open, bottomPx) }
    ToolbarDivider()
    for (item in config.shown) when (item) {
        ToolbarItem.PEN_COLOURS -> QuickColors(tools, config.colourCount, flags.hasSelection, actions, display, flat, open) {
            if (open?.kind == PopupKind.QUICK_COLOR) popupFor(open, bottomPx)
        }
        ToolbarItem.PEN_SIZES -> QuickSizes(tools, config.sizeCount, actions, display, flat, open) {
            if (open?.kind == PopupKind.QUICK_SIZE) popupFor(open, bottomPx)
        }
        ToolbarItem.UNDO -> ToolButton(FIcon.UNDO, "Undo", false, actions::undo, enabled = flags.canUndo, tag = "undo")
        ToolbarItem.REDO -> ToolButton(FIcon.REDO, "Redo", false, actions::redo, enabled = flags.canRedo, tag = "redo")
        ToolbarItem.SPEN_TO_TEXT -> ToolButton(FIcon.SPEN_TO_TEXT, "S Pen to text", flags.spenToText, actions::toggleSpenToText, toggle = true, tag = "spen-to-text") {
            if (open?.kind == PopupKind.SPEN_SETTINGS) popupFor(open, bottomPx)
        }
        ToolbarItem.CHANGE_STYLE -> ToolButton(
            FIcon.CHANGE_STYLE, "Change style", tools.active == EditorTool.CHANGE_STYLE, { actions.selectTool(EditorTool.CHANGE_STYLE) },
            ink = Color(display(tools.colorOf(EditorTool.CHANGE_STYLE))), tag = "tool-change-style",
        ) { if (open?.kind == PopupKind.CHANGE_STYLE) popupFor(open, bottomPx) }
        ToolbarItem.TAPE -> ToolButton(
            FIcon.TAPE, "Tape", tools.active == EditorTool.TAPE, { actions.selectTool(EditorTool.TAPE) },
            ink = Color(tools.colorOf(EditorTool.TAPE)), tag = "tool-tape",
        ) { if (open?.kind == PopupKind.TAPE) popupFor(open, bottomPx) }
        ToolbarItem.EASY_PAD -> ToolButton(FIcon.EASY_PAD, "Easy writing pad", flags.easyPad, actions::toggleEasyPad, toggle = true, ink = ink, tag = "easy-pad")
        ToolbarItem.LASER -> ToolButton(
            FIcon.LASER, "Laser pointer", tools.active == EditorTool.LASER, { actions.selectTool(EditorTool.LASER) },
            ink = Color(tools.colorOf(EditorTool.LASER)), inkBrush = if (tools.laserRainbow) Brush.sweepGradient(RAINBOW) else null, tag = "tool-laser",
        ) { if (open?.kind == PopupKind.LASER) popupFor(open, bottomPx) }
        ToolbarItem.AUTO_FIX_SHAPES -> ToolButton(FIcon.AUTO_FIX_SHAPES, "Auto-fix shapes", tools.autoFixShapes, actions::toggleAutoFix, toggle = true)
        ToolbarItem.LOCK_CANVAS -> ToolButton(if (tools.lockCanvas) FIcon.LOCK_CANVAS else FIcon.UNLOCK_CANVAS, "Lock canvas", tools.lockCanvas, actions::toggleLockCanvas, toggle = true)
        ToolbarItem.CLEAN_UP -> ToolButton(FIcon.CLEAN_UP, "Clean up handwriting", false, actions::cleanUp)
        ToolbarItem.INSERT_PICTURE -> ToolButton(FIcon.IMAGE, "Insert picture", false, actions::insertPicture, tag = "insert-image")
        ToolbarItem.ADD_PAGE -> ToolButton(FIcon.ADD_PAGE, "Add page after this one", false, actions::addPage)
        ToolbarItem.PAGE_NAVIGATION -> ToolButton(FIcon.PAGE_NAV, if (flags.pagesOpen) "Hide pages" else "Show pages", flags.pagesOpen, actions::togglePages, toggle = true)
        ToolbarItem.EXPORT_PDF -> ToolButton(FIcon.EXPORT_PDF, "Export notebook as PDF", false, actions::exportPdf)
    }
    ToolbarDivider()
    ToolButton(FIcon.CUSTOMISE, "Customise toolbar", false, actions::customise, tag = "customise-toolbar")
}

@Composable
private fun ToolbarDivider() {
    val c = LocalOneUi.current
    Box(Modifier.dockCell(null, magnifies = false).padding(horizontal = 6.dp).width(1.dp).height(18.dp).background(c.textSoft.copy(alpha = 0.55f)))
}

/** A 43 dp toolbar cell: the selected tool sits on a 32 dp circle. [anchor] hosts its popup. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ToolButton(
    icon: FIcon,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    toggle: Boolean = false,
    ink: Color? = null,
    inkBrush: Brush? = null,
    tag: String? = null,
    onLongClick: (() -> Unit)? = null,
    anchor: @Composable () -> Unit = {},
) {
    val c = LocalOneUi.current
    val cell = LocalToolCell.current
    Box(
        Modifier.dockCell(label).width(cell.width).height(cell.height)
            .combinedClickable(enabled = enabled, role = if (toggle) Role.Switch else Role.Button, onClick = onClick, onLongClick = onLongClick)
            .semantics {
                contentDescription = label; this.selected = selected
                if (toggle) stateDescription = if (selected) "On" else "Off"
            }
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) SelectedPlatter(cell.selected)
        val t = if (enabled) c.icon else c.iconDisabled
        FolioIcon(icon, null, tint = t, ink = ink ?: t, inkBrush = inkBrush, size = cell.icon)
        anchor()
    }
}

/** The selected-cell circle: One UI grey, or a glass platter with a soft rim when glass is on. */
@Composable
private fun SelectedPlatter(size: androidx.compose.ui.unit.Dp) {
    val c = LocalOneUi.current
    if (!LocalGlassSettings.current.enabled) { Box(Modifier.size(size).clip(CircleShape).background(c.toolSelected)); return }
    val spec = glassSpec(GlassRole.DOCK)
    Box(Modifier.size(size).clip(CircleShape).background(spec.selection).border(0.8.dp, Color.White.copy(alpha = if (spec.dark) 0.18f else 0.7f), CircleShape))
}

/** The quick colours: 34 dp cells with a 20 dp dot; the active colour's slot has a ring. */
@Composable
private fun QuickColors(
    tools: ToolState, count: Int, hasSelection: Boolean, actions: ToolbarActions, display: (Long) -> Long,
    flat: Boolean, open: OpenPopup?, anchor: @Composable () -> Unit,
) {
    val c = LocalOneUi.current
    val cellSize = LocalToolCell.current
    val current = tools.color
    val activeSlot = if (tools.active == EditorTool.ERASER || tools.active == EditorTool.SELECTION && !hasSelection) -1
    else tools.quickColors.take(count).indexOfFirst { it == current }
    val cells = @Composable {
        tools.quickColors.take(count).forEachIndexed { i, col ->
            val sel = i == activeSlot
            val label = "Ink colour ${"%06X".format(col and 0xFFFFFF)}"
            Box(
                Modifier.dockCell(label).width(cellSize.width * 0.79f).height(cellSize.height).clickable(role = Role.RadioButton) { actions.quickColor(i) }
                    .semantics { contentDescription = label; selected = sel }
                    .testTag("quick-colour-$i"),
                contentAlignment = Alignment.Center,
            ) {
                if (sel) SelectedPlatter(cellSize.selected * 0.94f)
                Canvas(Modifier.size(cellSize.icon * 0.83f)) {
                    drawCircle(Color(display(col)))
                    drawCircle(if (c.dark) Color(0x33FFFFFF) else Color(0x26000000), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                }
                // In the dock each colour is its own cell: the popup hangs off the tapped one.
                if (flat && open?.kind == PopupKind.QUICK_COLOR && open.slot == i) Box(Modifier.align(Alignment.BottomCenter)) { anchor() }
            }
        }
    }
    if (flat) { cells(); return }
    Box {
        Row(verticalAlignment = Alignment.CenterVertically) { cells() }
        Box(Modifier.align(Alignment.BottomCenter)) { anchor() }
    }
}

/** The quick thicknesses: 34 dp cells showing a short bar of each weight. */
@Composable
private fun QuickSizes(
    tools: ToolState, count: Int, actions: ToolbarActions, display: (Long) -> Long,
    flat: Boolean, open: OpenPopup?, anchor: @Composable () -> Unit,
) {
    val c = LocalOneUi.current
    val cellSize = LocalToolCell.current
    val t = tools.sizeTool
    val sizes = tools.quickSizesOf(t).take(count)
    val enabled = tools.inks || tools.active == EditorTool.CHANGE_STYLE
    val current = if (tools.active == EditorTool.CHANGE_STYLE) tools.restyleWidth else tools.width
    val activeSlot = if (!enabled) -1 else sizes.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
    val range = ToolState.sizeRange(t)
    val cells = @Composable {
        sizes.forEachIndexed { i, w ->
            val sel = i == activeSlot
            Box(
                Modifier.dockCell("Thickness ${i + 1}").width(cellSize.width * 0.79f).height(cellSize.height).alpha(if (enabled) 1f else 0.4f)
                    .clickable(enabled = enabled, role = Role.RadioButton) { actions.quickSize(i) }
                    .semantics { contentDescription = "Thickness ${i + 1}"; selected = sel }.testTag("quick-size-$i"),
                contentAlignment = Alignment.Center,
            ) {
                if (sel) SelectedPlatter(cellSize.selected * 0.94f)
                Canvas(Modifier.size(width = cellSize.icon * 0.92f, height = cellSize.icon / 2)) {
                    val f = ((w - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                    val th = (1.6f + 4.4f * kotlin.math.sqrt(f)) * density
                    drawLine(c.icon, Offset(th / 2, size.height / 2), Offset(size.width - th / 2, size.height / 2), th, cap = StrokeCap.Round)
                }
                if (flat && open?.kind == PopupKind.QUICK_SIZE && open.slot == i) Box(Modifier.align(Alignment.BottomCenter)) { anchor() }
            }
        }
    }
    if (flat) { cells(); return }
    Box {
        Row(verticalAlignment = Alignment.CenterVertically) { cells() }
        Box(Modifier.align(Alignment.BottomCenter)) { anchor() }
    }
}

// ------------------------------------------------------------------ canvas overlays

/** "125%" │ fit, at the top-right of the canvas while zooming (fades out after 1.5 s). */
@Composable
fun ZoomPill(percent: Int, tick: Int, onFit: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalOneUi.current
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(tick) {
        if (tick == 0) return@LaunchedEffect
        visible = true
        delay(1500)
        visible = false
    }
    AnimatedVisibility(visible, modifier, enter = fadeIn(), exit = fadeOut()) {
        Row(
            Modifier.shadow(6.dp, RoundedCornerShape(23.dp)).clip(RoundedCornerShape(23.dp)).background(c.popup).height(46.dp).padding(start = 18.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$percent%", style = OneUiType.bodyStrong, color = c.text, modifier = Modifier.semantics { contentDescription = "Zoom $percent percent" })
            Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(18.dp).background(c.divider))
            IconAction(FIcon.FIT, "Fit to screen", onFit, size = 38.dp, iconSize = 22.dp)
        }
    }
}

/** The small round button that leaves full screen (top-right, under the toolbar). */
@Composable
fun ExitFullScreenButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalOneUi.current
    Box(
        modifier.size(44.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(c.popup.copy(alpha = 0.92f))
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = "Exit full screen" },
        contentAlignment = Alignment.Center,
    ) { FolioIcon(FIcon.EXIT_FULL_SCREEN, null, size = 22.dp) }
}

fun toolLabel(t: ToolType): String = when (t) {
    ToolType.FOUNTAIN -> "Fountain pen"
    ToolType.BALLPOINT -> "Ballpoint pen"
    ToolType.BRUSH -> "Brush pen"
    ToolType.FINELINER -> "Fine liner"
    ToolType.MARKER -> "Marker"
    ToolType.AIRBRUSH -> "Airbrush"
    ToolType.PENCIL -> "Pencil"
    ToolType.HIGHLIGHTER -> "Highlighter"
    ToolType.TAPE -> "Tape"
}
