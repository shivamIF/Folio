package app.folio.notes.library

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.ViewSidebar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.folio.core.model.Folder
import app.folio.core.model.NotebookMeta
import app.folio.notes.ui.LocalLibraryColors
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import app.folio.notes.ui.glass.GlassDropdownMenu
import app.folio.notes.ui.glass.GlassMath
import app.folio.notes.ui.glass.GlassRole
import app.folio.notes.ui.glass.GlassShader
import app.folio.notes.ui.glass.LocalGlassBackdrop
import app.folio.notes.ui.glass.LocalGlassSettings
import app.folio.notes.ui.glass.ProvideBackdropColors
import app.folio.notes.ui.glass.ProvideGlassColors
import app.folio.notes.ui.glass.glassBackdropSource
import app.folio.notes.ui.glass.glassMenuItemColors
import app.folio.notes.ui.glass.glassPressable
import app.folio.notes.ui.glass.glassSpec
import app.folio.notes.ui.glass.glassSpring
import app.folio.notes.ui.glass.glassSurface
import app.folio.notes.ui.glass.rememberGlassBackdrop
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private sealed interface Dialog {
    data object NewNotebook : Dialog
    data object NewFolder : Dialog
    data object Settings : Dialog
    data object EmptyTrash : Dialog
    data class EditNotebook(val meta: NotebookMeta) : Dialog
    data class CustomizeFolder(val folder: Folder) : Dialog
    data class Move(val keys: Set<String>) : Dialog
    data class DeleteForever(val keys: Set<String>) : Dialog
}

/** Below this width the sidebar always shows as the narrow icon rail. */
private val CompactWidth = 600.dp
private val SidebarWide = 264.dp
private val SidebarRail = 76.dp

@Composable
fun LibraryScreen(
    onOpenNotebook: (String) -> Unit,
    importRequest: android.net.Uri? = null,
    onImportHandled: () -> Unit = {},
    vm: LibraryViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    // Any file: the type is detected from its contents (PDF, GoodNotes, Samsung Notes).
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importDocument(uri, onCreated = onOpenNotebook)
    }
    // A file opened or shared from another app.
    LaunchedEffect(importRequest) {
        if (importRequest != null) { vm.importDocument(importRequest, onCreated = onOpenNotebook); onImportHandled() }
    }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val q = state.query

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    LaunchedEffect(state.message) {
        val m = state.message ?: return@LaunchedEffect
        // Undo stays up longer so there is time to reach it.
        val result = snackbar.showSnackbar(
            m.text, actionLabel = if (m.undo != null) "Undo" else null,
            duration = if (m.undo != null) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) m.undo?.invoke()
        vm.clearMessage()
    }
    BackHandler(enabled = state.selecting || state.searching || q.folderId != null || q.section != Section.DOCUMENTS) {
        when {
            state.selecting -> vm.stopSelecting()
            state.searching -> vm.setSearching(false)
            q.folderId != null -> vm.goUp()
            else -> vm.showSection(Section.DOCUMENTS)
        }
    }

    val actions = remember(q.section, state.defaults) {
        val open: (LibraryItem) -> Unit = { item ->
            when {
                q.section == Section.TRASH -> vm.startSelecting(item.key) // trashed items open via Restore
                item is LibraryItem.FolderItem -> vm.openFolder(item.folder.id)
                item is LibraryItem.NotebookItem -> onOpenNotebook(item.meta.id)
            }
        }
        ItemActions(
            open = open,
            toggleFavorite = { vm.setFavorite(listOf(it.key), !it.favorite) },
            toggleSelected = { vm.toggleSelected(it.key) },
            showsPreview = state.defaults::showsPreview,
            menu = { item ->
                if (q.section == Section.TRASH) listOf(
                    MenuAction("Restore", Icons.Outlined.RestoreFromTrash) { vm.restore(listOf(item.key)) },
                    MenuAction("Delete forever", Icons.Outlined.DeleteForever, destructive = true) { dialog = Dialog.DeleteForever(setOf(item.key)) },
                ) else buildList {
                    add(MenuAction("Open", Icons.AutoMirrored.Outlined.OpenInNew) { open(item) })
                    when (item) {
                        is LibraryItem.NotebookItem -> {
                            add(MenuAction("Rename & cover", Icons.Outlined.Edit) { dialog = Dialog.EditNotebook(item.meta) })
                            add(MenuAction("Duplicate", Icons.Outlined.ContentCopy) { vm.duplicate(item.meta.id) })
                        }
                        is LibraryItem.FolderItem -> add(MenuAction("Customize", Icons.Outlined.Palette) { dialog = Dialog.CustomizeFolder(item.folder) })
                    }
                    add(MenuAction("Move to…", Icons.AutoMirrored.Outlined.DriveFileMove) { dialog = Dialog.Move(setOf(item.key)) })
                    add(
                        MenuAction(
                            if (item.favorite) "Remove from Favorites" else "Add to Favorites",
                            if (item.favorite) Icons.Outlined.StarOutline else Icons.Filled.Star,
                        ) { vm.setFavorite(listOf(item.key), !item.favorite) },
                    )
                    add(MenuAction("Select", Icons.Outlined.CheckCircle) { vm.startSelecting(item.key) })
                    add(MenuAction("Move to Trash", Icons.Outlined.Delete, destructive = true, dividerBefore = true) { vm.trash(listOf(item.key)) })
                }
            },
        )
    }

    val glass = LocalGlassSettings.current
    // Glass chrome: the sidebar and the bars float as glass over a wallpaper, and the grid scrolls
    // under the bars. Off, the library is laid out as it always was.
    val glassChrome = glass.enabled && glass.glassSidebar
    val dark = isSystemInDarkTheme()
    // The library as glass sees it: the chrome, menus and dialogs blur it live.
    val backdrop = rememberGlassBackdrop()
    val record = if (glass.seeThrough && glass.liveBlur && GlassShader.supported) Modifier.glassBackdropSource(backdrop) else Modifier
    val motion = remember { ContentMotion() }
    val wallpaper = remember { Wallpaper() }

    CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
    // A Surface so text picks up the theme's content colour (dark mode) without a Scaffold.
    Surface(color = if (glassChrome) Color.Transparent else LocalLibraryColors.current.content, contentColor = MaterialTheme.colorScheme.onSurface) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < CompactWidth
        val expanded = state.sidebarExpanded && !compact
        val target = if (expanded) SidebarWide else SidebarRail
        val sidebarSpec = if (glassChrome) glassSpring<Dp>(stiffness = 500f) else spring<Dp>(dampingRatio = 0.9f, stiffness = 500f)
        val sidebarWidth = animateDpAsState(target, sidebarSpec, label = "sidebar")
        // Only the sidebar animates. The content takes its new width once, when the sidebar has
        // finished opening (it slides over the content) or right away when closing, so the grid
        // isn't re-laid out on every animation frame. Once there it stays, even if a bouncy
        // spring swings back a little.
        val inset by remember(target) {
            var arrived = false
            derivedStateOf {
                if (target == SidebarWide && !arrived && sidebarWidth.value < SidebarWide - 1.dp) SidebarRail
                else { arrived = true; target }
            }
        }
        val bars: @Composable () -> Unit = {
            if (state.selecting) SelectionBar(
                state, onDone = vm::stopSelecting, onSelectAll = vm::selectAll,
                onFavorite = { keys, fav -> vm.setFavorite(keys, fav) }, onMove = { dialog = Dialog.Move(it) },
                onTrash = vm::trash, onRestore = { vm.restore(it) }, onDeleteForever = { dialog = Dialog.DeleteForever(it) },
            ) else TopBar(
                state, onBack = vm::goUp, onHome = { vm.showSection(Section.DOCUMENTS) },
                onSearch = vm::setSearching, onSearchText = vm::setSearch,
            )
            Header(state, onNavigate = vm::openFolder)
            Toolbar(
                state, onFilter = vm::setFilter, onSort = vm::setSort, onDescending = vm::setDescending,
                onViewMode = vm::setViewMode, onSelect = { vm.startSelecting() },
                onNewNotebook = { dialog = Dialog.NewNotebook }, onNewFolder = { dialog = Dialog.NewFolder },
                onImport = { pickFile.launch(arrayOf("*/*")) },
                onEmptyTrash = { dialog = Dialog.EmptyTrash },
            )
            if (state.importing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp).semantics { contentDescription = "Importing" })
        }
        val content: @Composable (underBar: (() -> Dp)?) -> Unit = { underBar ->
            Content(
                state, actions, underBar, motion.takeIf { underBar != null },
                onNewNotebook = { dialog = Dialog.NewNotebook }, onShowAll = { vm.setFilter(ItemFilter.ALL) },
            )
        }
        val sidebar: @Composable () -> Unit = {
            Sidebar(
                width = sidebarWidth, glass = glassChrome, expanded = expanded, canExpand = !compact, section = q.section, trashCount = state.trashCount,
                onToggle = vm::toggleSidebar, onSection = vm::showSection, onSettings = { dialog = Dialog.Settings },
            )
        }
        if (glassChrome) {
            val density = LocalDensity.current
            // Measured below; starts near the usual height so the first frame doesn't jump.
            val barHeight = remember { mutableIntStateOf(with(density) { 176.dp.roundToPx() }) }
            // Read when the content is measured, never in composition.
            val underBar = remember(density) { { with(density) { barHeight.intValue.toDp() } - PanelRadius + 12.dp } }
            // The bars' panel and the content share one frame right of the sidebar.
            val frame = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(start = inset + GlassGap * 2, top = GlassGap, end = GlassGap)
            // Recorded for the glass: the wallpaper and the content. The glass is drawn after it, never inside.
            Box(Modifier.fillMaxSize().then(record).drawBehind { wallpaper.draw(this, dark) }) {
                // Content is clipped below the panel's rounded top, so nothing peeks round its corners.
                Box(frame.padding(top = PanelRadius).fillMaxSize()) {
                    WallpaperColors(dark) {
                        content(underBar)
                    }
                }
            }
            CompositionLocalProvider(LocalGlassChrome provides true) {
                ProvideGlassColors(GlassRole.CHROME) {
                    Column(
                        frame.fillMaxWidth()
                            .onSizeChanged { barHeight.intValue = it.height }
                            // The glass samples the content while drawing, so redraw it as the content moves.
                            .drawBehind { motion.read() }
                            .glassSurface(PanelShape, GlassRole.CHROME),
                    ) { bars() }
                    sidebar()
                }
            }
        } else {
            Box(Modifier.fillMaxSize().then(record)) {
                Column(Modifier.fillMaxSize().padding(start = inset).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End))) {
                    bars()
                    Box(Modifier.weight(1f).fillMaxWidth()) { content(null) }
                }
                sidebar()
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 8.dp))
    }
    }

    when (val d = dialog) {
        null -> Unit
        Dialog.NewNotebook -> NotebookDialog(null, state.defaults, onDismiss = { dialog = null }) { title, cover, paper ->
            dialog = null
            vm.createNotebook(title, cover, paper, onCreated = onOpenNotebook)
        }
        is Dialog.EditNotebook -> NotebookDialog(d.meta, state.defaults, onDismiss = { dialog = null }) { title, cover, _ ->
            dialog = null
            vm.updateNotebook(d.meta.copy(title = title.ifBlank { d.meta.title }, cover = cover))
        }
        Dialog.NewFolder -> FolderDialog(null, onDismiss = { dialog = null }) { name, color, icon ->
            dialog = null; vm.createFolder(name, color, icon)
        }
        is Dialog.CustomizeFolder -> FolderDialog(d.folder, onDismiss = { dialog = null }) { name, color, icon ->
            dialog = null; vm.customizeFolder(d.folder.id, name, color, icon)
        }
        is Dialog.Move -> MoveDialog(state.index, d.keys, q.folderId.takeIf { q.section == Section.DOCUMENTS && !state.searching }, onDismiss = { dialog = null }) {
            dialog = null; vm.moveTo(d.keys, it)
        }
        is Dialog.DeleteForever -> ConfirmDialog(
            if (d.keys.size == 1) "Delete forever?" else "Delete ${d.keys.size} items forever?",
            "This can't be undone. Folders are deleted with everything inside them.", "Delete",
            onDismiss = { dialog = null }, onConfirm = { dialog = null; vm.deleteForever(d.keys) },
        )
        Dialog.EmptyTrash -> ConfirmDialog(
            "Empty Trash?", "Everything in Trash (${state.trashCount} items) will be deleted forever. This can't be undone.", "Empty Trash",
            onDismiss = { dialog = null }, onConfirm = { dialog = null; vm.emptyTrash() },
        )
        Dialog.Settings -> SettingsDialog(state.defaults, onDismiss = { dialog = null }) { dialog = null; vm.saveDefaults(it) }
    }
    }
}

/** Room between the floating glass panels and the screen edges. */
private val GlassGap = 10.dp
private val PanelRadius = 24.dp
private val PanelShape = RoundedCornerShape(PanelRadius)

/** True inside the library's glass chrome: buttons there squish on press and lift under the S Pen. */
private val LocalGlassChrome = staticCompositionLocalOf { false }

@Composable
private fun Modifier.chromePress(pressed: Float = 0.92f, hover: Float = 1.06f): Modifier =
    if (LocalGlassChrome.current) glassPressable(pressed, hover) else this

/**
 * Lets the glass over the content redraw as the content moves (the glass samples it while it
 * draws). Read in the draw phase only, so scrolling never recomposes.
 */
@Stable
private class ContentMotion {
    var probe: (() -> Int)? by mutableStateOf(null)
    /** Bumped after items settle (re-sorted, filtered), when no scroll redraws the glass. */
    var settled by mutableIntStateOf(0)
    fun read() { probe?.invoke(); settled }
}

/** Content padding that clears the glass bar; [top] is read when the list is measured, so the bar's height never recomposes it. */
@Stable
private class UnderBar(private val start: Dp, private val end: Dp, private val bottom: Dp, private val top: () -> Dp) : PaddingValues {
    override fun calculateLeftPadding(layoutDirection: LayoutDirection) = if (layoutDirection == LayoutDirection.Ltr) start else end
    override fun calculateTopPadding() = top().coerceAtLeast(0.dp)
    override fun calculateRightPadding(layoutDirection: LayoutDirection) = if (layoutDirection == LayoutDirection.Ltr) end else start
    override fun calculateBottomPadding() = bottom
}

/** One soft colour field of the wallpaper: a glow at ([x], [y]) with radius [r], as fractions of the screen. */
private class Field(val x: Float, val y: Float, val r: Float, val color: Color)
private class WallpaperPalette(val base: Color, val fields: List<Field>) {
    /** The colour text on the wallpaper contrasts least with. */
    fun hardest(dark: Boolean): Color = (fields.map { it.color } + base).let { c -> if (dark) c.maxBy { it.luminance() } else c.minBy { it.luminance() } }
}

private val WallpaperLight = WallpaperPalette(
    Color(0xFFEEF1F7),
    listOf(
        Field(0.08f, 0.02f, 0.75f, Color(0xFFD2E0FB)), // sky
        Field(0.98f, 0.12f, 0.60f, Color(0xFFE7DCF8)), // lilac
        Field(0.92f, 1.00f, 0.70f, Color(0xFFF9E1D6)), // peach
        Field(0.12f, 0.96f, 0.60f, Color(0xFFD8EFE6)), // mint
    ),
)
private val WallpaperDark = WallpaperPalette(
    Color(0xFF0C0E13),
    listOf(
        Field(0.08f, 0.02f, 0.75f, Color(0xFF1A2846)),
        Field(0.98f, 0.12f, 0.60f, Color(0xFF261B38)),
        Field(0.92f, 1.00f, 0.70f, Color(0xFF2B1D19)),
        Field(0.12f, 0.96f, 0.60f, Color(0xFF0F2A28)),
    ),
)

/**
 * The library wallpaper behind the glass: soft colour fields, painted once per size and theme
 * at a quarter of the screen's resolution and stretched (it's all gentle gradients), so each
 * frame costs one bitmap draw.
 */
private class Wallpaper {
    private var image: ImageBitmap? = null
    private var key: Triple<Int, Int, Boolean>? = null

    fun draw(scope: DrawScope, dark: Boolean) = with(scope) {
        val w = (size.width / 4).roundToInt().coerceAtLeast(1)
        val h = (size.height / 4).roundToInt().coerceAtLeast(1)
        val k = Triple(w, h, dark)
        val img = image.takeIf { key == k } ?: paint(if (dark) WallpaperDark else WallpaperLight, w, h).also { image = it; key = k }
        drawImage(img, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Low)
    }

    private fun paint(p: WallpaperPalette, w: Int, h: Int): ImageBitmap {
        val img = ImageBitmap(w, h)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(w.toFloat(), h.toFloat())) {
            drawRect(p.base)
            val m = max(w, h).toFloat()
            p.fields.forEach { f ->
                drawRect(
                    Brush.radialGradient(
                        0f to f.color, 0.4f to f.color.copy(alpha = 0.6f), 0.75f to f.color.copy(alpha = 0.18f), 1f to f.color.copy(alpha = 0f),
                        center = Offset(f.x * w, f.y * h), radius = f.r * m,
                    ),
                )
            }
        }
        return img
    }
}

/** Colours for content on the wallpaper: secondary text darkened (or lightened) until it reads on every field. */
@Composable
private fun WallpaperColors(dark: Boolean, content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val scheme = remember(base, dark) {
        val hardest = (if (dark) WallpaperDark else WallpaperLight).hardest(dark)
        base.copy(onSurfaceVariant = Color(GlassMath.ensureContrast(base.onSurfaceVariant.toArgb(), hardest.toArgb(), 4.5f)))
    }
    ProvideBackdropColors(scheme, content)
}

/**
 * Collapsible sidebar: full width with labels, or a narrow rail of icons. The width change is
 * animated. With [glass] it's a floating glass panel inset from the screen edges.
 */
@Composable
private fun Sidebar(
    width: State<Dp>, glass: Boolean, expanded: Boolean, canExpand: Boolean, section: Section, trashCount: Int,
    onToggle: () -> Unit, onSection: (Section) -> Unit, onSettings: () -> Unit,
) {
    // Read in the layout phase: animation frames re-measure the sidebar without recomposing.
    val animatedWidth = Modifier.layout { measurable, constraints ->
        val w = width.value.roundToPx()
        val placeable = measurable.measure(Constraints.fixed(w, constraints.maxHeight))
        layout(w, constraints.maxHeight) { placeable.place(0, 0) }
    }
    val frame = if (glass) {
        Modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))
            .padding(GlassGap)
            .then(animatedWidth)
            .glassSurface(PanelShape, GlassRole.CHROME)
            .clip(PanelShape)
    } else {
        animatedWidth
            .clipToBounds()
            .background(LocalLibraryColors.current.sidebar)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))
    }
    Column(frame.padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (canExpand) IconButton(onClick = onToggle, modifier = Modifier.padding(start = 2.dp).chromePress()) {
            Icon(Icons.Outlined.ViewSidebar, contentDescription = if (expanded) "Collapse sidebar" else "Expand sidebar")
        } else Spacer(Modifier.height(48.dp))
        Box(Modifier.height(64.dp).padding(start = 10.dp), contentAlignment = Alignment.CenterStart) {
            if (expanded) Text("Folio", fontSize = 34.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
        }
        SidebarItem(Icons.Filled.Folder, Section.DOCUMENTS.label, section == Section.DOCUMENTS, expanded) { onSection(Section.DOCUMENTS) }
        SidebarItem(
            if (section == Section.FAVORITES) Icons.Filled.Star else Icons.Outlined.StarOutline,
            Section.FAVORITES.label, section == Section.FAVORITES, expanded,
        ) { onSection(Section.FAVORITES) }
        SidebarItem(Icons.Outlined.Delete, Section.TRASH.label, section == Section.TRASH, expanded, badge = trashCount) { onSection(Section.TRASH) }
        Spacer(Modifier.weight(1f))
        SidebarItem(Icons.Outlined.Settings, "Settings", false, expanded, onClick = onSettings)
    }
}

@Composable
private fun SidebarItem(icon: ImageVector, label: String, selected: Boolean, expanded: Boolean, badge: Int = 0, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val glass = LocalGlassChrome.current
    // On glass, the selected item sits on the glass's selection platter and items lift under the S Pen.
    val platter = if (glass) glassSpec(GlassRole.CHROME).selection else LocalLibraryColors.current.selectedItem
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).chromePress(pressed = 0.96f, hover = 1.03f).height(48.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) platter else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label; this.selected = selected }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        if (expanded) {
            Text(
                label, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
            if (badge > 0) Text("$badge", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun TopBar(state: LibraryUiState, onBack: () -> Unit, onHome: () -> Unit, onSearch: (Boolean) -> Unit, onSearchText: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state.query.section != Section.DOCUMENTS && !state.searching) IconButton(onClick = onHome, modifier = Modifier.chromePress()) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to notebooks")
        } else if (state.query.folderId != null && !state.searching) IconButton(onClick = onBack, modifier = Modifier.chromePress()) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to parent folder")
        }
        if (state.searching) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            TextField(
                value = state.query.search, onValueChange = onSearchText, singleLine = true,
                placeholder = { Text("Search notebooks and folders") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = { IconButton(onClick = { onSearch(false) }) { Icon(Icons.Filled.Close, contentDescription = "Close search") } },
                shape = RoundedCornerShape(12.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier.weight(1f).focusRequester(focus).testTag("search"),
            )
        } else {
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onSearch(true) }, modifier = Modifier.chromePress()) { Icon(Icons.Filled.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
private fun SelectionBar(
    state: LibraryUiState, onDone: () -> Unit, onSelectAll: () -> Unit,
    onFavorite: (Set<String>, Boolean) -> Unit, onMove: (Set<String>) -> Unit, onTrash: (Set<String>) -> Unit,
    onRestore: (Set<String>) -> Unit, onDeleteForever: (Set<String>) -> Unit,
) {
    val sel = state.selection.orEmpty()
    val any = sel.isNotEmpty()
    val error = MaterialTheme.colorScheme.error
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onDone, modifier = Modifier.chromePress()) { Icon(Icons.Filled.Close, contentDescription = "Done selecting") }
        Text(if (any) "${sel.size} selected" else "Select items", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onSelectAll, modifier = Modifier.chromePress()) { Text("Select all") }
        Spacer(Modifier.weight(1f))
        if (state.query.section == Section.TRASH) {
            TextButton(onClick = { onRestore(sel) }, enabled = any, modifier = Modifier.chromePress()) {
                Icon(Icons.Outlined.RestoreFromTrash, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Restore")
            }
            TextButton(onClick = { onDeleteForever(sel) }, enabled = any, modifier = Modifier.chromePress()) {
                Icon(Icons.Outlined.DeleteForever, contentDescription = null, tint = if (any) error else Color.Unspecified)
                Spacer(Modifier.width(6.dp)); Text("Delete", color = if (any) error else Color.Unspecified)
            }
        } else {
            val allFavorite = any && state.items.filter { it.key in sel }.all { it.favorite }
            IconButton(onClick = { onFavorite(sel, !allFavorite) }, enabled = any, modifier = Modifier.chromePress()) {
                Icon(
                    if (allFavorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    contentDescription = if (allFavorite) "Remove from favorites" else "Add to favorites",
                )
            }
            IconButton(onClick = { onMove(sel) }, enabled = any, modifier = Modifier.chromePress()) { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = "Move to") }
            IconButton(onClick = { onTrash(sel) }, enabled = any, modifier = Modifier.chromePress()) {
                Icon(Icons.Outlined.Delete, contentDescription = "Move to Trash", tint = if (any) error else Color.Unspecified)
            }
        }
    }
}

/** Breadcrumb path (inside folders) and the large page title. */
@Composable
private fun Header(state: LibraryUiState, onNavigate: (String?) -> Unit) {
    val q = state.query
    val title = when {
        state.searching -> "Search"
        q.section == Section.DOCUMENTS -> state.currentFolder?.name ?: Section.DOCUMENTS.label
        else -> q.section.label
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp)) {
        if (q.section == Section.DOCUMENTS && state.path.isNotEmpty() && !state.searching) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Crumb(Section.DOCUMENTS.label) { onNavigate(null) }
                state.path.dropLast(1).forEach { f ->
                    Separator()
                    Crumb(f.name) { onNavigate(f.id) }
                }
                Separator()
            }
        }
        Text(title, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Crumb(name: String, onClick: () -> Unit) = Text(
    name, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall, maxLines = 1,
    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 4.dp),
)

@Composable
private fun Separator() = Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 2.dp))

@Composable
private fun Toolbar(
    state: LibraryUiState, onFilter: (ItemFilter) -> Unit, onSort: (SortOrder) -> Unit, onDescending: (Boolean) -> Unit,
    onViewMode: (ViewMode) -> Unit, onSelect: () -> Unit, onNewNotebook: () -> Unit, onNewFolder: () -> Unit,
    onImport: () -> Unit, onEmptyTrash: () -> Unit,
) {
    val q = state.query
    val primary = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        var filterMenu by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { filterMenu = true }, modifier = Modifier.chromePress().semantics { contentDescription = "Filter: ${q.filter.label}" }) {
                Icon(Icons.Outlined.FilterList, contentDescription = null, tint = primary)
                Spacer(Modifier.width(8.dp))
                Text(q.filter.label, color = primary, style = MaterialTheme.typography.titleMedium)
            }
            GlassDropdownMenu(filterMenu, onDismissRequest = { filterMenu = false }) {
                ItemFilter.entries.forEach { f ->
                    CheckedItem(f.label, f == q.filter) { filterMenu = false; onFilter(f) }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (q.section == Section.DOCUMENTS && !state.searching && !state.selecting) {
            var newMenu by remember { mutableStateOf(false) }
            Box {
                Button(
                    onClick = { newMenu = true }, shape = RoundedCornerShape(10.dp),
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding, modifier = Modifier.chromePress().testTag("new-item"),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New", style = MaterialTheme.typography.titleMedium)
                }
                GlassDropdownMenu(newMenu, onDismissRequest = { newMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Notebook") }, leadingIcon = { Icon(Icons.Outlined.NoteAdd, contentDescription = null) },
                        onClick = { newMenu = false; onNewNotebook() }, colors = glassMenuItemColors(),
                    )
                    DropdownMenuItem(
                        text = { Text("Folder") }, leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
                        onClick = { newMenu = false; onNewFolder() }, colors = glassMenuItemColors(),
                    )
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    DropdownMenuItem(
                        text = { Text("Import PDF, GoodNotes or Samsung Notes") },
                        leadingIcon = { Icon(Icons.Outlined.FileOpen, contentDescription = null) },
                        enabled = !state.importing,
                        onClick = { newMenu = false; onImport() }, colors = glassMenuItemColors(),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
        }
        if (q.section == Section.TRASH && state.items.isNotEmpty() && !state.selecting) {
            TextButton(onClick = onEmptyTrash, modifier = Modifier.chromePress()) { Text("Empty Trash", color = MaterialTheme.colorScheme.error) }
        }
        val grid = state.viewMode == ViewMode.GRID
        IconButton(onClick = { onViewMode(if (grid) ViewMode.LIST else ViewMode.GRID) }, modifier = Modifier.chromePress()) {
            Icon(
                if (grid) Icons.Outlined.GridView else Icons.AutoMirrored.Outlined.ViewList,
                contentDescription = if (grid) "Switch to list view" else "Switch to grid view", tint = primary,
            )
        }
        var more by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { more = true }, modifier = Modifier.chromePress()) { Icon(Icons.Filled.MoreHoriz, contentDescription = "Sort and select", tint = primary) }
            GlassDropdownMenu(more, onDismissRequest = { more = false }) {
                MenuHeading("Sort by")
                SortOrder.entries.forEach { s -> CheckedItem(s.label, s == q.sort) { onSort(s) } }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                val dates = q.sort == SortOrder.MODIFIED || q.sort == SortOrder.CREATED
                CheckedItem(if (dates) "Newest first" else "A to Z", if (dates) q.descending else !q.descending) { onDescending(dates) }
                CheckedItem(if (dates) "Oldest first" else "Z to A", if (dates) !q.descending else q.descending) { onDescending(!dates) }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                DropdownMenuItem(
                    text = { Text("Select items") }, leadingIcon = { Icon(Icons.Outlined.CheckCircle, contentDescription = null) },
                    onClick = { more = false; onSelect() }, colors = glassMenuItemColors(),
                )
            }
        }
    }
}

@Composable
private fun MenuHeading(text: String) = Text(
    text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
)

@Composable
private fun CheckedItem(label: String, checked: Boolean, onClick: () -> Unit) = DropdownMenuItem(
    text = { Text(label) },
    trailingIcon = { if (checked) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary) },
    onClick = onClick,
    colors = glassMenuItemColors(),
)

@Composable
/**
 * The grid or list. Under glass chrome it fills the screen behind the bars: [underBar] gives the
 * top padding that clears them, and [motion] lets their glass follow the scrolling.
 */
private fun Content(
    state: LibraryUiState, actions: ItemActions, underBar: (() -> Dp)?, motion: ContentMotion?,
    onNewNotebook: () -> Unit, onShowAll: () -> Unit,
) {
    val q = state.query
    val items = remember(state.index, q) { state.items }
    if (motion != null) LaunchedEffect(items, state.viewMode) { delay(600); motion.settled++ }
    if (state.loading) return
    if (items.isEmpty()) {
        val modifier = Modifier.fillMaxSize().then(if (underBar != null) Modifier.padding(remember(underBar) { UnderBar(0.dp, 0.dp, 0.dp, underBar) }) else Modifier)
        Box(modifier, contentAlignment = Alignment.Center) {
            val filtered = q.filter != ItemFilter.ALL && q.copy(filter = ItemFilter.ALL).items(state.index).isNotEmpty()
            when {
                q.search.isNotBlank() -> EmptyState(Icons.Outlined.SearchOff, "No results", "Nothing in your library matches \"${q.search.trim()}\".")
                filtered -> EmptyState(Icons.Outlined.FilterList, "Nothing matches this filter", "There are no ${q.filter.label.lowercase()} here.") {
                    TextButton(onClick = onShowAll) { Text("Show all") }
                }
                q.section == Section.FAVORITES -> EmptyState(
                    Icons.Outlined.StarOutline, "No favorites yet", "Tap the star on any notebook or folder to keep it here.",
                )
                q.section == Section.TRASH -> EmptyState(
                    Icons.Outlined.Delete, "Trash is empty",
                    "Notebooks and folders you delete wait here for ${LibraryViewModel.TRASH_DAYS} days, then they're deleted for good.",
                )
                q.folderId != null -> EmptyState(Icons.Outlined.FolderOpen, "This folder is empty", "Create a notebook here or move existing ones in.") {
                    NewNotebookButton(onNewNotebook)
                }
                else -> EmptyState(Icons.Outlined.NoteAdd, "Ready to start writing?", "No notebooks yet. Tap New notebook to start writing.") {
                    NewNotebookButton(onNewNotebook)
                }
            }
        }
        return
    }
    val showStar = q.section != Section.TRASH
    when (state.viewMode) {
        ViewMode.GRID -> {
            val scroll = rememberLazyGridState()
            if (motion != null) DisposableEffect(scroll) {
                motion.probe = { scroll.firstVisibleItemIndex + scroll.firstVisibleItemScrollOffset }
                onDispose { motion.probe = null }
            }
            LazyVerticalGrid(
                // Phones: narrower cells, so two notebooks fit beside the icon rail.
                columns = GridCells.Adaptive(if (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600) 128.dp else 176.dp),
                modifier = Modifier.fillMaxSize(),
                state = scroll,
                contentPadding = if (underBar != null) remember(underBar) { UnderBar(12.dp, 12.dp, 96.dp, underBar) }
                else PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                items(items, key = { it.key }, contentType = { it is LibraryItem.FolderItem }) { item ->
                    ItemTile(item, state.selection?.contains(item.key), showStar, actions, Modifier.animateItem())
                }
            }
        }
        ViewMode.LIST -> {
            val scroll = rememberLazyListState()
            if (motion != null) DisposableEffect(scroll) {
                motion.probe = { scroll.firstVisibleItemIndex + scroll.firstVisibleItemScrollOffset }
                onDispose { motion.probe = null }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = scroll,
                contentPadding = if (underBar != null) remember(underBar) { UnderBar(8.dp, 8.dp, 96.dp, underBar) }
                else PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            ) {
                items(items, key = { it.key }, contentType = { it is LibraryItem.FolderItem }) { item ->
                    ItemRow(item, state.selection?.contains(item.key), showStar, actions, Modifier.animateItem())
                }
            }
        }
    }
}

@Composable
private fun NewNotebookButton(onClick: () -> Unit) {
    Button(onClick = onClick, shape = RoundedCornerShape(10.dp), modifier = Modifier.testTag("new-notebook")) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text("New notebook")
    }
}
