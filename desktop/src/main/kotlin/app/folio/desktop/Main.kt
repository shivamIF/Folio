package app.folio.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.folio.core.ink.StrokeBuilder
import app.folio.core.ink.StrokeGeometry
import app.folio.core.model.Stroke
import app.folio.core.model.TextBox
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import app.folio.core.model.LibraryIndex
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.folio.core.model.NotebookMeta
import app.folio.core.model.Page
import app.folio.core.storage.FileNotebookStore
import app.folio.desktop.PageArt.drawPaper
import app.folio.desktop.sync.GoogleSignIn
import app.folio.desktop.sync.MacSync
import app.folio.desktop.PageArt.drawStroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/** Where the Mac keeps the library: the same on-disk format as the tablet and phone. */
fun libraryDir(): File =
    (System.getProperty("folio.library") ?: System.getenv("FOLIO_LIBRARY"))?.let(::File) ?: File(System.getProperty("user.home"), "Library/Application Support/Folio/library")

private val Desk = Color(0xFF16181D)
private val Panel = Color(0xFF20232A)
private val Accent = Color(0xFF2F8BFF)
/** The tablet library's wallpaper: deep blue-violet falling to teal and a warm corner. */
internal val Wallpaper = Brush.linearGradient(listOf(Color(0xFF232A52), Color(0xFF1A1B33), Color(0xFF14201F), Color(0xFF241A18)), Offset.Zero, Offset(1400f, 2200f))
/** A floating pane in the tablet's glass style (frosted: Compose Desktop can't blur what's behind a pane). */
private fun Modifier.glassPane(radius: Dp) = this
    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.13f), Color.White.copy(alpha = 0.06f))), RoundedCornerShape(radius))
    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.06f))), RoundedCornerShape(radius))

private enum class Section(val label: String) { DOCUMENTS("Documents"), FAVORITES("Favorites"), TRASH("Trash") }

fun main() = application {
    val store = remember { FileNotebookStore(libraryDir().also { it.mkdirs() }) }
    val pdfs = remember { PdfPages() }
    val sync = remember { MacSync(store, GoogleSignIn(libraryDir().parentFile)).also { it.start() } }
    DisposableEffect(Unit) { onDispose { pdfs.close() } }
    // Cmd +/−/0 reach the open notebook through this.
    var zoomKey by remember { mutableStateOf<((Int) -> Unit)?>(null) }
    Window(
        onCloseRequest = ::exitApplication, title = "Folio", state = rememberWindowState(width = 1180.dp, height = 860.dp),
        onPreviewKeyEvent = { e ->
            val z = zoomKey
            if (z == null || e.type != KeyEventType.KeyDown || !e.isMetaPressed) false
            else when (e.key) {
                Key.Equals, Key.Plus -> { z(1); true }
                Key.Minus -> { z(-1); true }
                Key.Zero -> { z(0); true }
                Key.Z -> { z(if (e.isShiftPressed) 3 else 2); true }
                else -> false
            }
        },
    ) {
        DisposableEffect(Unit) { Trackpad.install(window.rootPane); onDispose { } }
        MaterialTheme(colorScheme = darkColorScheme(background = Desk, surface = Panel, primary = Accent)) {
            Box(Modifier.fillMaxSize().background(Desk).background(Wallpaper)) {
                // FOLIO_OPEN=<part of a title> opens that notebook at launch (used when testing).
                var open by remember {
                    mutableStateOf(System.getenv("FOLIO_OPEN")?.let { t -> runCatching { store.library().notebooks.firstOrNull { it.deletedAt == null && it.title.contains(t, ignoreCase = true) } }.getOrNull() })
                }
                val nb = open
                if (nb == null) Library(store, pdfs, sync) { open = it } else Viewer(store, pdfs, nb, sync, { zoomKey = it }) { open = null }
            }
        }
    }
}

@Composable
internal fun Library(store: FileNotebookStore, pdfs: PdfPages, sync: MacSync?, onOpen: (NotebookMeta) -> Unit) {
    // Read off the main thread; null while loading, an error message if the library can't be read.
    val loaded by produceState<Result<LibraryIndex>?>(null, sync?.libraryVersion) { value = withContext(Dispatchers.IO) { runCatching { store.library() } } }
    var section by remember { mutableStateOf(Section.DOCUMENTS) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize().padding(14.dp)) {
        // The icon rail, as on the tablet.
        Column(Modifier.width(76.dp).fillMaxHeight().glassPane(34.dp).padding(vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(64.dp))
            for (s in Section.entries) {
                val icon = when (s) { Section.DOCUMENTS -> Icons.Filled.Folder; Section.FAVORITES -> Icons.Outlined.StarBorder; Section.TRASH -> Icons.Outlined.Delete }
                Box(
                    Modifier.padding(vertical = 6.dp).size(52.dp).clip(RoundedCornerShape(16.dp))
                        .background(if (section == s) Color.White.copy(alpha = 0.16f) else Color.Transparent).clickable { section = s },
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, s.label, tint = if (section == s) Accent else Color.White.copy(alpha = 0.6f)) }
            }
            Spacer(Modifier.weight(1f))
            // Settings, at the foot of the rail as on the tablet.
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).clickable { settings = true }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Settings, "Settings", tint = Color.White.copy(alpha = 0.6f))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            // The header pane: section title, then the filter row.
            Column(Modifier.fillMaxWidth().glassPane(34.dp).padding(start = 34.dp, end = 26.dp, top = 22.dp, bottom = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    if (searching) BasicTextField(
                        query, { query = it }, singleLine = true, textStyle = TextStyle(Color.White, 15.sp), cursorBrush = SolidColor(Accent),
                        modifier = Modifier.width(240.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.10f)).padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                    Icon(Icons.Outlined.Search, "Search", Modifier.padding(start = 10.dp).clip(RoundedCornerShape(10.dp)).clickable { searching = !searching; if (!searching) query = "" }.padding(6.dp), tint = Accent)
                }
                Text(section.label, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.62f))
            }
            Spacer(Modifier.height(10.dp))
            val r = loaded
            val shown = remember(r, section, query) {
                r?.getOrNull()?.let { index ->
                    val gone = index.trashedFolderIds()
                    index.notebooks.filter { nb ->
                        val trashed = nb.deletedAt != null || nb.folderId in gone
                        when (section) { Section.DOCUMENTS -> !trashed; Section.FAVORITES -> !trashed && nb.favorite; Section.TRASH -> trashed } &&
                            (query.isBlank() || nb.title.contains(query.trim(), ignoreCase = true))
                    }.sortedByDescending { it.modifiedAt }
                }
            }
            when {
                r == null -> Text("Loading…", Modifier.padding(24.dp), color = Color.White.copy(alpha = 0.6f))
                r.isFailure -> Text("The library couldn't be read: ${r.exceptionOrNull()?.message}", Modifier.padding(24.dp), color = Color(0xFFFF8A80))
                shown.isNullOrEmpty() -> Text(
                    if (section == Section.DOCUMENTS && query.isBlank()) "No notebooks on this Mac yet.\nLibrary folder: ${libraryDir()}" else "Nothing here.",
                    Modifier.padding(24.dp), color = Color.White.copy(alpha = 0.6f),
                )
                else -> LazyVerticalGrid(
                    GridCells.Adaptive(190.dp), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 36.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(30.dp),
                ) {
                    items(shown, key = { it.id }) { nb ->
                        Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable { onOpen(nb) }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Cover(store, pdfs, nb)
                            Spacer(Modifier.height(12.dp))
                            Text(nb.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                            Text(SimpleDateFormat("d MMM yyyy 'at' h:mm a").format(Date(nb.modifiedAt)), color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
    if (settings && sync != null) SettingsPane(sync) { settings = false }
    }
}

/** Settings: Google Drive sync for this Mac. */
@Composable
private fun SettingsPane(sync: MacSync, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(460.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFF2A2D3A)).border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(28.dp))
                .clickable(enabled = false) {}.padding(28.dp),
        ) {
            Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Spacer(Modifier.height(18.dp))
            Text("Google Drive sync", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White)
            Spacer(Modifier.height(6.dp))
            val line = when {
                !sync.configured -> "Not set up on this Mac yet. Put your Google \"Desktop app\" sign-in file at:\n${sync.google.credentialsFile}"
                sync.status.isNotBlank() -> sync.status
                sync.signedIn -> "Signed in. Notebooks sync with your tablet and phone."
                else -> "Sign in with the same Google account as your tablet and phone."
            }
            Text(line, color = if (!sync.configured) Color(0xFFFFB4A9) else Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (sync.configured && !sync.signedIn) TextButton({ sync.signIn() }, enabled = !sync.busy) { Text("Sign in to Google") }
                if (sync.signedIn) {
                    TextButton({ sync.syncNow() }, enabled = !sync.busy) { Text(if (sync.busy) "Syncing…" else "Sync now") }
                    TextButton({ sync.signOut() }, enabled = !sync.busy) { Text("Sign out") }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClose) { Text("Done") }
            }
        }
    }
}

/** A notebook's cover as on the tablet: the first page for imported PDFs, else the coloured cover with its title label. */
@Composable
private fun Cover(store: FileNotebookStore, pdfs: PdfPages, nb: NotebookMeta) {
    val firstPage by produceState<ImageBitmap?>(null, nb.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val pdf = nb.pageIds.firstOrNull()?.let { store.loadPage(nb.id, it).pdf } ?: return@runCatching null
                pdfs.render(store.attachmentFile(nb.id, pdf.attachmentId), pdf.pageIndex, 420)
            }.getOrNull()
        }
    }
    val bmp = firstPage
    val star = @Composable { Icon(if (nb.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder, null, Modifier.size(20.dp), tint = if (bmp != null) Color(0xFF8A8F98) else Color.White.copy(alpha = 0.85f)) }
    if (bmp != null) Box(Modifier.width(156.dp).aspectRatio(bmp.width.toFloat() / bmp.height).shadow(10.dp, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp)).background(Color.White)) {
        Image(bmp, nb.title, Modifier.fillMaxSize())
        Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { star() }
    } else Box(
        Modifier.width(156.dp).aspectRatio(0.75f).shadow(10.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 14.dp, bottomEnd = 14.dp)).background(Color(nb.cover.color.toInt())),
    ) {
        Box(Modifier.width(12.dp).fillMaxHeight().background(Color.Black.copy(alpha = 0.16f)))
        Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { star() }
        Text(
            nb.title, Modifier.align(Alignment.Center).padding(start = 24.dp, end = 14.dp).clip(RoundedCornerShape(7.dp))
                .background(Color.White.copy(alpha = 0.93f)).padding(horizontal = 9.dp, vertical = 7.dp),
            color = Color(0xFF1B1B1F), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The open notebook. Trackpad: two fingers scroll (both ways when zoomed in), pinch zooms,
 * Cmd + scroll zooms; Cmd +, Cmd − and Cmd 0 zoom from the keyboard.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Viewer(store: FileNotebookStore, pdfs: PdfPages, nb: NotebookMeta, sync: MacSync, bindZoomKeys: (((Int) -> Unit)?) -> Unit, onBack: () -> Unit) {
    var zoom by remember { mutableFloatStateOf(1f) }
    val edit = remember(nb.id) { EditState(store, nb.id, onSaved = sync::changed) }
    fun setZoom(z: Float) { zoom = z.coerceIn(0.4f, 4f) }
    DisposableEffect(Unit) {
        Trackpad.onMagnify = { amount -> setZoom(zoom * (1f + amount)) }
        // Steps: +1 / −1 / 0 zoom; 2 undo; 3 redo (Cmd Z, Shift Cmd Z).
        bindZoomKeys { step -> when (step) { 2 -> edit.undo(); 3 -> edit.redo(); else -> setZoom(if (step == 0) 1f else if (step > 0) zoom * 1.2f else zoom / 1.2f) } }
        sync.openNotebook = nb.id
        onDispose { Trackpad.onMagnify = null; bindZoomKeys(null); sync.openNotebook = null; sync.changed() }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Color(0xFF0B0B0D)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack) { Text("‹", fontSize = 26.sp, color = Color.White) }
            Text(nb.title, Modifier.weight(1f).padding(horizontal = 8.dp), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${nb.pageIds.size} pages", color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp)
            Spacer(Modifier.width(12.dp))
            TextButton({ setZoom(zoom / 1.2f) }) { Text("−") }
            Text("${(zoom * 100).roundToInt()}%", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
            TextButton({ setZoom(zoom * 1.2f) }) { Text("+") }
        }
        Box(Modifier.fillMaxWidth().background(Color(0xFF2B2B2E)).padding(top = 10.dp, bottom = 4.dp), contentAlignment = Alignment.Center) { MacToolbar(edit) }
        edit.error?.let { Text(it, Modifier.fillMaxWidth().background(Color(0xFF5A1F1F)).padding(horizontal = 16.dp, vertical = 6.dp), color = Color.White, fontSize = 13.sp) }
        BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF2B2B2E))) {
            val pageWidth = (maxWidth - 48.dp).coerceAtMost(900.dp) * zoom
            val columnWidth = maxOf(maxWidth, pageWidth + 48.dp)
            Box(
                Modifier.fillMaxSize().horizontalScroll(rememberScrollState())
                    // Cmd + two-finger scroll zooms, as in Preview.
                    .onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { e ->
                        if (e.keyboardModifiers.isMetaPressed) {
                            val dy = e.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            setZoom(zoom * (1f - dy * 0.04f))
                            e.changes.forEach { it.consume() }
                        }
                    },
            ) {
                LazyColumn(
                    Modifier.width(columnWidth).fillMaxHeight(), contentPadding = PaddingValues(vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    itemsIndexed(nb.pageIds, key = { _, id -> id }) { i, id ->
                        PageView(store, pdfs, nb, id, Modifier.width(pageWidth), edit)
                        Text("${i + 1}/${nb.pageIds.size}", Modifier.padding(top = 6.dp, bottom = 10.dp), color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

/**
 * One page: paper, PDF background, pictures, ink, then text boxes (the Android renderer's order).
 * With [edit], a click-and-drag writes, highlights or erases, and the text tool types on the page.
 */
@Composable
internal fun PageView(store: FileNotebookStore, pdfs: PdfPages, nb: NotebookMeta, pageId: String, modifier: Modifier, edit: EditState? = null) {
    val loaded by produceState<Result<Page>?>(null, nb.id, pageId) {
        value = withContext(Dispatchers.IO) { runCatching { store.loadPage(nb.id, pageId) } }
        value?.getOrNull()?.let { if (edit != null && pageId !in edit.pages) edit.pages[pageId] = it }
    }
    val p = edit?.pages?.get(pageId) ?: loaded?.getOrNull()
    val ratio = if (p != null) p.width / p.height else nb.pageWidth / nb.pageHeight
    Box(modifier.aspectRatio(ratio).shadow(8.dp).background(Color.White)) {
        if (loaded?.isFailure == true) {
            Text("This page couldn't be loaded: ${loaded?.exceptionOrNull()?.message}", Modifier.padding(16.dp), color = Color(0xFFB00020))
            return@Box
        }
        if (p == null) return@Box
        val widthPx = with(LocalDensity.current) { 860.dp.toPx() }.toInt()
        val pdf by produceState<ImageBitmap?>(null, p.pdf) {
            val bg = p.pdf ?: return@produceState
            value = withContext(Dispatchers.IO) { pdfs.render(store.attachmentFile(nb.id, bg.attachmentId), bg.pageIndex, (widthPx * 1.5f).toInt()) }
        }
        val pictures by produceState<Map<String, ImageBitmap>>(emptyMap(), p.images) {
            value = withContext(Dispatchers.IO) {
                p.images.mapNotNull { im ->
                    runCatching { javax.imageio.ImageIO.read(store.imageFile(nb.id, im.attachmentId))?.toComposeImageBitmap() }.getOrNull()?.let { im.attachmentId to it }
                }.toMap()
            }
        }
        // Each stroke's geometry is built once and kept by id, so an edit doesn't rebuild the page's ink.
        val artCache = remember { HashMap<String, StrokeArt>() }
        var live by remember { mutableStateOf<Stroke?>(null) }          // the stroke being drawn
        var erasing by remember { mutableStateOf<Set<String>>(emptySet()) } // strokes under the eraser this drag
        var typing by remember { mutableStateOf<TextBox?>(null) }        // the text box being typed
        val current = rememberUpdatedState(p)
        val measurer = rememberTextMeasurer()
        val input = if (edit == null || edit.tool == MacTool.VIEW) Modifier else Modifier.pointerInput(edit.tool) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val k = size.width / current.value.width
                fun at(o: Offset) = Offset(o.x / k, o.y / k)
                down.consume()
                when (edit.tool) {
                    MacTool.PEN, MacTool.HIGHLIGHTER -> {
                        val b = StrokeBuilder(edit.inkTool, edit.inkColor, edit.inkWidth, stabilization = 0.35f, minDistance = StrokeBuilder.minDistanceAt(k), startedAt = System.currentTimeMillis(), pxPerUnit = k)
                        fun add(c: PointerInputChange) { val q = at(c.position); b.add(q.x, q.y, MOUSE_PRESSURE, 0f, 0f, c.uptimeMillis) }
                        add(down)
                        var last = down
                        while (true) {
                            val c = awaitPointerEvent().changes.firstOrNull() ?: break
                            last = c
                            if (!c.pressed) break
                            add(c); c.consume()
                            live = edit.styled(b.build())
                        }
                        val q = at(last.position)
                        b.finishAt(q.x, q.y, MOUSE_PRESSURE, 0f, 0f, last.uptimeMillis)
                        val stroke = edit.styled(b.build())
                        live = null
                        if (stroke.size > 0) edit.commit(pageId, current.value.copy(strokes = current.value.strokes + stroke))
                    }
                    MacTool.ERASER -> {
                        val hit = LinkedHashSet<String>()
                        fun sweep(o: Offset) {
                            val q = at(o)
                            for (st in current.value.strokes) if (st.id !in hit && StrokeGeometry.hits(st, q.x, q.y, edit.eraserRadius / k)) hit += st.id
                            erasing = hit.toSet()
                        }
                        sweep(down.position)
                        while (true) {
                            val c = awaitPointerEvent().changes.firstOrNull() ?: break
                            if (!c.pressed) break
                            sweep(c.position); c.consume()
                        }
                        erasing = emptySet()
                        if (hit.isNotEmpty()) edit.commit(pageId, current.value.copy(strokes = current.value.strokes.filterNot { it.id in hit }))
                    }
                    MacTool.TEXT -> {
                        val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                        val q = at(up.position)
                        typing = current.value.texts.lastOrNull { it.bounds.contains(q.x, q.y) }
                            ?: TextBox(x = q.x, y = q.y - TEXT_SIZE / 2f, width = minOf(TEXT_WIDTH, current.value.width - q.x - 12f).coerceAtLeast(120f), height = TEXT_SIZE * 1.4f, text = "", fontSize = TEXT_SIZE, color = edit.color or 0xFF000000)
                    }
                    MacTool.VIEW -> Unit
                }
            }
        }
        Canvas(Modifier.fillMaxSize().then(input)) {
            val k = size.width / p.width
            pdf?.let { drawImage(it, dstSize = IntSize(size.width.toInt(), size.height.toInt())) }
            scale(k, k, pivot = Offset.Zero) {
                if (pdf == null) drawPaper(p.paper, p.width, p.height, hasPdf = p.pdf != null)
                for (im in p.images) pictures[im.attachmentId]?.let { bmp ->
                    drawImage(bmp, dstOffset = IntOffset(im.x.toInt(), im.y.toInt()), dstSize = IntSize(im.width.toInt(), im.height.toInt()))
                }
                for (st in p.strokes) if (st.id !in erasing) drawStroke(artCache.getOrPut(st.id) { PageArt.of(st) })
                live?.let { drawStroke(PageArt.of(it)) }
                for (t in p.texts) {
                    if (t.id == typing?.id) continue
                    val layout = measurer.measure(
                        t.text, TextStyle(color = Color(t.color.toInt()), fontSize = (t.fontSize / density / fontScale).sp),
                        constraints = Constraints(maxWidth = t.width.toInt().coerceAtLeast(1)),
                    )
                    drawText(layout, topLeft = Offset(t.x, t.y))
                }
            }
        }
        // Typing: a text field sits on the page where the box is; it is stored when the field loses focus.
        val box = typing
        if (box != null && edit != null) BoxWithConstraints(Modifier.fillMaxSize()) {
            val k = constraints.maxWidth / p.width
            val d = LocalDensity.current
            var value by remember(box.id) { mutableStateOf(TextFieldValue(box.text, TextRange(box.text.length))) }
            val focus = remember(box.id) { FocusRequester() }
            var focused by remember(box.id) { mutableStateOf(false) }
            fun finish() {
                val text = value.text.trimEnd()
                val lines = text.count { it == '\n' } + 1
                val done = box.copy(text = text, height = maxOf(box.height, lines * box.fontSize * 1.35f))
                val cur = current.value
                val exists = cur.texts.any { it.id == box.id }
                edit.commit(pageId, when {
                    text.isBlank() -> cur.copy(texts = cur.texts.filterNot { it.id == box.id })
                    exists -> cur.copy(texts = cur.texts.map { if (it.id == box.id) done else it })
                    else -> cur.copy(texts = cur.texts + done)
                })
                typing = null
            }
            BasicTextField(
                value, { value = it },
                textStyle = TextStyle(color = Color(box.color.toInt()), fontSize = with(d) { (box.fontSize * k).toSp() }),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier.offset { IntOffset((box.x * k).roundToInt(), (box.y * k).roundToInt()) }.width(with(d) { (box.width * k).toDp() })
                    .border(1.dp, Accent.copy(alpha = 0.7f)).focusRequester(focus)
                    .onFocusChanged { if (it.isFocused) focused = true else if (focused) finish() }
                    .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { finish(); true } else false },
            )
            LaunchedEffect(box.id) { focus.requestFocus() }
            // Switching tool ends typing.
            LaunchedEffect(edit.tool) { if (edit.tool != MacTool.TEXT) finish() }
        }
    }
}

/** A mouse or trackpad has no pressure: an even, medium press. */
private const val MOUSE_PRESSURE = 0.6f
/** New text boxes, in page units (A4 width = 1000), as on the tablet. */
private const val TEXT_SIZE = 28f
private const val TEXT_WIDTH = 420f
