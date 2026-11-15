package app.folio.notes.editor

import android.app.Application
import app.folio.core.model.PdfBackground
import app.folio.notes.pdf.PdfFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.folio.core.ink.StrokeIndex
import app.folio.core.model.NotebookHistory
import app.folio.core.model.NotebookMeta
import app.folio.core.model.Page
import app.folio.core.model.PageEdit
import app.folio.core.model.PageEditor
import app.folio.core.model.PageHeader
import app.folio.core.model.PageOps
import app.folio.core.model.PaperTemplate
import app.folio.core.model.Stroke
import app.folio.core.model.TextBox
import app.folio.notes.input.PageStack
import app.folio.notes.FolioApp
import app.folio.notes.export.PageExportSpec
import app.folio.notes.export.PageExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A text box being edited on [pageId]: [original] is null for a new one; [index] is its place in the page's list. */
data class TextEdit(val pageId: String, val box: app.folio.core.model.TextBox, val original: app.folio.core.model.TextBox?, val index: Int)

/** Scroll [pageId] to the top of the canvas; [seq] is new for every request. */
data class PageJump(val pageId: String, val seq: Long)

data class ExportResult(val uri: android.net.Uri, val fileName: String)

enum class SaveStatus { SAVED, PENDING, SAVING, ERROR }

/** One-off messages from the page navigator to the screen. */
sealed interface PageEvent {
    /** A short notice, optionally with Undo. */
    class Notice(val message: String, val undo: (() -> Unit)? = null) : PageEvent
    /** An exported page is ready for the share sheet. */
    class Share(val uri: android.net.Uri, val mime: String, val fileName: String) : PageEvent
    /** The notebook was written as a PDF for the print dialog. */
    class Print(val file: java.io.File, val title: String) : PageEvent
}

data class EditorUiState(
    val meta: NotebookMeta? = null,
    /** The page in view (under the canvas's reading line): "Page X of N", the sidebar highlight, Previous/Next. */
    val pageIndex: Int = 0,
    /** Every page of the notebook in one scrolling column; null until the notebook is open. */
    val stack: PageStack? = null,
    /** Content of the pages loaded around the screen, by id (the others show as placeholders). */
    val pages: Map<String, LoadedPage> = emptyMap(),
    /** The canvas scrolls this page to the top (Previous/Next, the sidebar, a new page). */
    val jump: PageJump? = null,
    /** Undo/Redo of the notebook's latest edit, on whichever page it was made. */
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** Lasso selection, on [selectionPage]. */
    val selection: Set<String> = emptySet(),
    val selectionPage: String? = null,
    val exporting: Boolean = false,
    /** Set after a successful export: where the PDF was saved. */
    val exported: ExportResult? = null,
    val tools: ToolState = ToolState(),
    /** The text box being typed (hidden on the page while its field is shown). */
    val editingText: TextEdit? = null,
    val save: SaveStatus = SaveStatus.SAVED,
    val error: String? = null,
    /** The page sidebar is shown (remembered across sessions). */
    val sidebarOpen: Boolean = false,
    /** A single page is being exported. */
    val pageExporting: Boolean = false,
    /** Reading mode: toolbar hidden, the pen scrolls instead of writing. */
    val reading: Boolean = false,
    /** Reading mode's laser pointer switch. */
    val readingLaser: Boolean = false,
    /** Full screen: system bars and the app bar hidden. */
    val fullScreen: Boolean = false,
    /** The toolbar's arrangement (Customise toolbar). */
    val toolbar: ToolbarConfig = ToolbarConfig(),
    /** Eyedropper waiting for a tap: the colour goes to this tool (and quick slot, when set). */
    val eyedropper: Pair<EditorTool, Int?>? = null,
    /** Something was cut or copied (Paste is offered). */
    val canPaste: Boolean = false,
    /** S Pen to text is on: handwriting turns into typed text after a pause. */
    val spenToText: Boolean = false,
    /** The easy writing pad is open. */
    val easyPad: Boolean = false,
    /** A table cell being typed. */
    val editingCell: CellEdit? = null,
    /** S Pen to text: status shown on its bar (model download, recognising), or null. */
    val spenStatus: String? = null,
    /** S Pen to text's language tag. */
    val spenLanguage: String = "en-US",
    /** The text box S Pen to text made last (its bar's Space, Backspace and Enter edit it). */
    val spenBox: Pair<String, String>? = null,
    /** The easy writing pad's window on the page, while the pad is open. */
    val padWindow: PadWindow? = null,
)

/** Table cell ([row], [col]) of table [tableId] on [pageId] being typed; [text] is the draft. */
data class CellEdit(val pageId: String, val tableId: String, val row: Int, val col: Int, val text: String)

/** Recordings are played by the screen (it owns the media player). */
fun interface ObjectTapHandler { fun audio(pageId: String, audio: app.folio.core.model.PageAudio) }

/**
 * Owns the notebook being edited. Edits run on the main thread against each page's
 * [PageEditor]; saves take an immutable snapshot and write it on the app's
 * single writer thread, debounced while the user writes, and flushed at once on stop.
 *
 * Continuous scrolling (ADR-10): the canvas reports which pages are on screen; the pages from
 * one above to one below them are loaded (one at a time, nearest first, off the writer thread)
 * and published in [EditorUiState.pages]. Pages that scroll away stay in memory within a
 * budget ([MAX_IDLE_PAGES], [MAX_IDLE_STROKES]) once saved. A [NotebookHistory] orders edits
 * across pages for the top-bar Undo/Redo.
 */
class EditorViewModel(app: Application, private val notebookId: String) : AndroidViewModel(app) {
    private val folio = app as FolioApp
    private val repo = folio.repository
    private val prefs = ToolPrefs(app)
    private val editors = HashMap<String, PageEditor>()
    /** Order of content edits across pages, for one Undo/Redo. */
    private val history = NotebookHistory()
    /** Size and paper of pages not loaded yet, read from the start of their files. */
    private val headers = HashMap<String, PageHeader>()
    /** Low-resolution PDF page under each loaded PDF page's ink. */
    private val backgrounds = HashMap<String, android.graphics.Bitmap>()
    /** Each loaded page as last published to the canvas. */
    private val shown = HashMap<String, LoadedPage>()
    /** Ink written on a page before its content loaded, applied in order when it arrives. */
    private val queued = HashMap<String, MutableList<PageEdit>>()
    /** Pages whose load failed (not retried until reopened). */
    private val failed = HashSet<String>()
    /** The page in view, the pages on screen, and those kept loaded around them. */
    private var currentPageId: String? = null
    private var visibleIds: List<String> = emptyList()
    private var window: List<String> = emptyList()
    /** When each page was last on screen (for evicting the least recently viewed). */
    private val lastSeen = HashMap<String, Long>()
    private var seenClock = 0L
    private var jumpSeq = 0L
    private var loader: Job? = null
    private val savedRevision = HashMap<String, Long>()
    /** Each loaded page as last read from or written to disk: the base for merging edits from other devices. */
    private val savedPages = HashMap<String, Page>()
    /** Only the debounce timer is ever cancelled; a write that has started always runs to completion. */
    private var debounceJob: Job? = null
    private var retryAttempt = 0

    private val _state = MutableStateFlow(EditorUiState(tools = prefs.load(), sidebarOpen = prefs.sidebarOpen, toolbar = prefs.toolbar, spenLanguage = prefs.spenLanguage))
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val _events = Channel<PageEvent>(Channel.BUFFERED)
    val events: Flow<PageEvent> = _events.receiveAsFlow()

    /**
     * Content version of each page's thumbnail (absent = 0). Bumped only when a page's
     * content changes, debounced while writing, so the sidebar re-renders just that page.
     */
    private val _thumbVersions = MutableStateFlow<Map<String, Long>>(emptyMap())
    val thumbVersions: StateFlow<Map<String, Long>> = _thumbVersions.asStateFlow()
    private val thumbsDue = HashSet<String>()
    private var thumbJob: Job? = null
    /** Page operations run one at a time, each against the notebook as the previous one left it. */
    private val pageOps = Mutex()

    /** Lets sync hand this editor pages edited on other devices. */
    private val live = object : app.folio.notes.sync.SyncManager.LiveEditor {
        override val notebookId get() = this@EditorViewModel.notebookId
        override fun incomingPage(page: Page): Page = receive(page)
    }

    init {
        folio.sync.liveEditor = live
        viewModelScope.launch { folio.sync.changes.collect { refreshMeta() } }
        viewModelScope.launch {
            try {
                val meta = withContext(folio.ioWriter) { repo.library().notebooks.first { it.id == notebookId } }
                _state.update { it.copy(meta = meta) }
                rebuildStack()
                openPage(0)
                readHeaders(meta.pageIds)
            } catch (e: Exception) {
                _state.update { it.copy(error = "Couldn't open this notebook: ${e.message}") }
            }
        }
    }

    // ---- the column of pages and the window loaded around the screen ----

    /** Scrolls to page [index] (Previous/Next, the sidebar, a new page). */
    fun openPage(index: Int) {
        commitText()
        val meta = _state.value.meta ?: return
        if (index !in meta.pageIds.indices) return
        val id = meta.pageIds[index]
        currentPageId = id
        _state.update { it.copy(pageIndex = index, jump = PageJump(id, ++jumpSeq)) }
        updateWindow()
    }

    /** The canvas scrolled: [visible] pages are on screen (top to bottom), [current] is in view. */
    fun onPagesInView(visible: List<String>, current: String) {
        val meta = _state.value.meta ?: return
        visibleIds = visible
        currentPageId = current
        for (id in visible) lastSeen[id] = ++seenClock
        val at = meta.pageIds.indexOf(current)
        if (at >= 0 && at != _state.value.pageIndex) _state.update { it.copy(pageIndex = at) }
        updateWindow()
    }

    /** Paper of a page (for its placeholder while it loads). */
    fun paperOf(pageId: String): PaperTemplate =
        editors[pageId]?.paper ?: savedPages[pageId]?.paper ?: headers[pageId]?.paper ?: _state.value.meta?.paper ?: PaperTemplate.RULED

    private fun pageSize(pageId: String): Pair<Float, Float> {
        savedPages[pageId]?.let { return it.width to it.height }
        headers[pageId]?.let { return it.width to it.height }
        val meta = _state.value.meta
        return (meta?.pageWidth ?: NotebookMeta.PAGE_WIDTH) to (meta?.pageHeight ?: NotebookMeta.PAGE_HEIGHT)
    }

    /** Lays the pages out again (a new stack only when something moved or changed size). */
    private fun rebuildStack() {
        val meta = _state.value.meta ?: return
        if (meta.pageIds.isEmpty()) return
        val s = PageStack.of(meta.pageIds, ::pageSize)
        _state.update { if (s.sameLayout(it.stack)) it else it.copy(stack = s) }
    }

    /** Reads the sizes of pages not loaded yet (a small read each), then lays the column out with them. */
    private fun readHeaders(ids: List<String>) {
        val todo = ids.filter { it !in headers && it !in savedPages }
        if (todo.isEmpty()) return
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                todo.mapNotNull { id -> runCatching { repo.pageHeader(notebookId, id) }.getOrNull()?.let { id to it } }
            }
            headers.putAll(found)
            rebuildStack()
        }
    }

    /** Pages kept loaded: the visible ones and one either side (or around the page in view). */
    private fun windowIds(meta: NotebookMeta): List<String> {
        val ids = meta.pageIds
        val at = visibleIds.map { ids.indexOf(it) }.filter { it >= 0 }
            .ifEmpty { listOf(currentPageId?.let { ids.indexOf(it) }?.takeIf { it >= 0 } ?: _state.value.pageIndex.coerceIn(0, ids.lastIndex)) }
        val first = (at.min() - 1).coerceAtLeast(0)
        val last = (at.max() + 1).coerceAtMost(ids.lastIndex)
        return ids.subList(first, last + 1).toList()
    }

    /** Loads what the window lacks (nearest the page in view first), publishes it, evicts idle pages. */
    private fun updateWindow() {
        val meta = _state.value.meta ?: return
        if (meta.pageIds.isEmpty()) return
        window = windowIds(meta)
        publishWindow()
        startLoading()
        evictIdle()
    }

    private fun publishWindow() {
        val pages = LinkedHashMap<String, LoadedPage>()
        for (id in window) {
            val ed = editors[id] ?: continue
            pages[id] = shown[id] ?: loadedPage(id, ed).also { shown[id] = it }
        }
        val old = _state.value.pages
        if (old.size == pages.size && pages.all { (k, v) -> old[k] === v }) return
        _state.update { it.copy(pages = pages) }
    }

    /** The next page to load: the one in view, then the rest of the window nearest it first. */
    private fun nextToLoad(): String? {
        val meta = _state.value.meta ?: return null
        val cur = meta.pageIds.indexOf(currentPageId).takeIf { it >= 0 } ?: _state.value.pageIndex
        return window.filter { it !in editors && it !in failed && it in meta.pageIds }
            .minByOrNull { kotlin.math.abs(meta.pageIds.indexOf(it) - cur) }
    }

    /** One page at a time, off the main and writer threads; the stroke index is built there too. */
    private fun startLoading() {
        if (loader?.isActive == true || nextToLoad() == null) return
        loader = viewModelScope.launch {
            while (true) {
                val pageId = nextToLoad() ?: break
                try {
                    val loaded = withContext(Dispatchers.IO) {
                        // A page stored by sync while it was being read is read again.
                        var attempt = 0
                        while (true) {
                            val stamp = repo.pageStamp(notebookId, pageId)
                            val t0 = System.nanoTime()
                            val page = repo.loadPage(notebookId, pageId)
                            val index = StrokeIndex(page.strokes)
                            android.util.Log.d("FolioPerf", "page load ms=%.1f strokes=%d".format((System.nanoTime() - t0) / 1e6, page.strokes.size))
                            if (repo.pageStamp(notebookId, pageId) == stamp || ++attempt >= 3) return@withContext page to index
                        }
                        @Suppress("UNREACHABLE_CODE") error("unreachable")
                    }
                    // Created here meanwhile (a new page) or gone (deleted): nothing to adopt.
                    if (editors[pageId] != null || _state.value.meta?.pageIds?.contains(pageId) != true) continue
                    val (page, index) = loaded
                    val ed = adopt(page)
                    val waiting = queued.remove(pageId)
                    if (waiting != null) {
                        for (e in waiting) ed.perform(e)
                        scheduleSave()
                        thumbChanged(pageId)
                    }
                    rebuildStack() // its real size, if the header couldn't be read
                    publish(pageId, ed, index)
                    page.pdf?.let { loadBackground(pageId, it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed += pageId
                    val n = (_state.value.meta?.pageIds?.indexOf(pageId) ?: 0) + 1
                    _state.update { it.copy(error = "Page $n couldn't be loaded: ${e.message}") }
                }
            }
        }
    }

    /**
     * Keeps memory bounded however far the user scrolls: beyond the window, unsaved pages, pages
     * with queued ink and the selection's page, at most [MAX_IDLE_PAGES] pages or
     * [MAX_IDLE_STROKES] strokes stay loaded, most recently viewed first. A dropped page loses
     * its undo history (it's reloaded from its saved file when it comes back).
     */
    private fun evictIdle() {
        val keep = HashSet(window)
        _state.value.selectionPage?.let { keep += it }
        _state.value.editingText?.let { keep += it.pageId }
        val idle = editors.keys.filter { id -> id !in keep && id !in queued && savedRevision[id] == editors[id]?.revision }
            .sortedByDescending { lastSeen[it] ?: 0L }
        var strokes = 0; var pages = 0
        var dropped = false
        for (id in idle) {
            strokes += editors[id]?.currentStrokes?.size ?: 0
            pages++
            if (pages > MAX_IDLE_PAGES || strokes > MAX_IDLE_STROKES) {
                editors.remove(id); savedPages.remove(id); savedRevision.remove(id); shown.remove(id); backgrounds.remove(id)
                dropped = true
            }
        }
        if (dropped) refreshUndo()
    }

    /** A page's content for the canvas (a fresh copy of its lists). */
    private fun loadedPage(pageId: String, ed: PageEditor, index: StrokeIndex? = null): LoadedPage {
        val info = savedPages[pageId] ?: Page(id = pageId)
        return LoadedPage(
            pageId, info.width, info.height, ed.paper, info.pdf,
            ed.currentStrokes.toList(), ed.currentTexts.toList(), ed.currentImages.toList(), backgrounds[pageId], index,
            PageObjects(ed.currentStickies.toList(), ed.currentTables.toList(), ed.currentAudio.toList(), playingAudio),
        )
    }

    /** Renders the PDF page off the main thread; cached so flipping pages back is instant. */
    private fun loadBackground(pageId: String, pdf: PdfBackground) {
        val app = getApplication<android.app.Application>()
        val metrics = app.resources.displayMetrics
        // Only a stand-in until the page's tiles render the PDF sharp at their own scale, so keep
        // it small (~4 MB a page). It used to be 1.5x the screen's long side: ~50 MB for every
        // loaded page, over 500 MB after scrolling a PDF, which made scrolling and zooming stutter.
        val widthPx = (minOf(metrics.widthPixels, metrics.heightPixels) * 0.75f).toInt().coerceIn(1, PdfFile.MAX_RENDER_PX)
        val key = "$notebookId/${pdf.attachmentId}/${pdf.pageIndex}/$widthPx"
        folio.pdfBitmaps.get(key)?.let { bmp -> setBackground(pageId, bmp); return }
        viewModelScope.launch {
            try {
                val bmp = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    PdfFile(repo.attachmentFile(notebookId, pdf.attachmentId)).use { it.render(pdf.pageIndex, widthPx) }
                }
                folio.pdfBitmaps.put(key, bmp)
                setBackground(pageId, bmp)
            } catch (e: Exception) {
                _state.update { it.copy(error = "The PDF page couldn't be displayed: ${e.message}. Your notes on it are safe.") }
            }
        }
    }

    private fun setBackground(pageId: String, bmp: android.graphics.Bitmap) {
        if (backgrounds[pageId] === bmp) return
        backgrounds[pageId] = bmp
        editors[pageId]?.let { publish(pageId, it) }
    }

    /** Toolbar: a new page after the one in view. */
    fun addPage() {
        val meta = _state.value.meta ?: return
        (currentPageId ?: meta.pageIds.getOrNull(_state.value.pageIndex))?.let { addPage(it, before = false) }
    }

    fun onStrokeFinished(pageId: String, stroke: Stroke) {
        edit(pageId, PageEdit.AddStroke(stroke))
        spenCollect(pageId, stroke)
    }

    fun onStrokesErased(pageId: String, ids: Set<String>) {
        val ed = editors[pageId] ?: return
        edit(pageId, PageEdit.RemoveStrokes.of(ed.currentStrokes, ids))
    }

    fun onStrokesSplit(pageId: String, pieces: Map<String, List<Stroke>>) {
        val ed = editors[pageId] ?: return
        val t0 = System.nanoTime()
        val e = PageEdit.SplitStrokes.of(ed.currentStrokes, pieces)
        if (ed.currentStrokes.size > 1000) android.util.Log.d("FolioPerf", "vm split-of ms=%.1f".format((System.nanoTime() - t0) / 1e6))
        edit(pageId, e)
    }

    /** Reverts the notebook's latest edit, on whichever page; scrolls there if it's off screen. */
    fun undo() { commitText(); history.undo(editors::get, ::loading)?.let(::historyMoved); refreshUndo() }
    fun redo() { commitText(); history.redo(editors::get)?.let(::historyMoved); refreshUndo() }

    private fun historyMoved(pageId: String) {
        val ed = editors[pageId] ?: return
        changed(pageId, ed)
        if (pageId !in visibleIds) _state.value.meta?.pageIds?.indexOf(pageId)?.takeIf { it >= 0 }?.let(::openPage)
    }

    /** Ink was written on [pageId] but its content hasn't arrived: its edits wait. */
    private fun loading(pageId: String) = editors[pageId] == null && pageId in queued

    private fun refreshUndo() = _state.update {
        it.copy(canUndo = history.canUndo(editors::get, ::loading), canRedo = history.canRedo(editors::get))
    }

    // ---- text boxes ----

    /** Text tool tap: finish the box being typed, then edit the tapped box or start a new one there. */
    fun onTextTap(pageId: String, x: Float, y: Float, textId: String?) {
        commitText()
        val ed = editors[pageId] ?: return
        if (textId != null) {
            val i = ed.currentTexts.indexOfFirst { it.id == textId }
            if (i >= 0) { _state.update { it.copy(editingText = TextEdit(pageId, ed.currentTexts[i], ed.currentTexts[i], i)) }; return }
        }
        val page = savedPages[pageId] ?: return
        val tools = _state.value.tools
        val size = tools.textSize
        val w = minOf(520f, page.width - 16f).coerceAtLeast(120f)
        val left = x.coerceAtMost(page.width - w - 8f).coerceAtLeast(8f)
        val box = app.folio.core.model.TextBox(
            x = left, y = (y - size * 0.6f).coerceAtLeast(4f), width = w, height = size * 1.4f,
            text = "", fontSize = size, color = tools.color,
        )
        _state.update { it.copy(editingText = TextEdit(pageId, box, null, ed.currentTexts.size)) }
    }

    fun updateEditingText(text: String) = _state.update { s -> s.copy(editingText = s.editingText?.let { it.copy(box = it.box.copy(text = text)) }) }

    /** Saves the box being typed (one undo step); an emptied box is removed. */
    fun commitText() {
        val e = _state.value.editingText ?: return
        _state.update { it.copy(editingText = null) }
        val text = e.box.text.trimEnd()
        if (text.isBlank()) {
            if (e.original != null) edit(e.pageId, PageEdit.PutText(e.original, null, e.index))
            return
        }
        val height = PageRenderer().layout(text, e.box.width, e.box.fontSize, e.box.color).height.toFloat()
        val box = e.box.copy(text = text, height = maxOf(height, e.box.fontSize))
        when {
            e.original == null -> edit(e.pageId, PageEdit.PutText(null, box, e.index))
            box != e.original -> edit(e.pageId, PageEdit.PutText(e.original, box, e.index))
        }
    }

    // ---- pictures ----

    /** Copies a picture into the notebook, places it in the middle of [area] (units of [pageId]) and selects it. */
    fun insertImage(uri: android.net.Uri, pageId: String, area: app.folio.core.model.Rect) {
        commitText()
        val ctx = getApplication<android.app.Application>()
        viewModelScope.launch {
            try {
                val (id, w, h) = withContext(folio.ioWriter) {
                    val staged = (ctx.contentResolver.openInputStream(uri) ?: throw java.io.IOException("can't open the picture")).use { repo.stageImage(it) }
                    uprightImage(staged.file)
                    val id = repo.addImage(notebookId, staged)
                    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(repo.imageFile(notebookId, id).path, o)
                    Triple(id, o.outWidth, o.outHeight)
                }
                if (w <= 0 || h <= 0) throw java.io.IOException("unreadable picture")
                val f = minOf(area.width * 0.7f / w, area.height * 0.6f / h)
                val iw = w * f; val ih = h * f
                val cx = (area.left + area.right) / 2f; val cy = (area.top + area.bottom) / 2f
                val img = app.folio.core.model.PageImage(attachmentId = id, x = cx - iw / 2f, y = cy - ih / 2f, width = iw, height = ih)
                edit(pageId, PageEdit.PutImage(null, img))
                _state.update { it.copy(selection = setOf(img.id), selectionPage = pageId, tools = it.tools.activate(EditorTool.SELECTION)) }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Couldn't add the picture: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    /** Camera photos are often stored sideways with an EXIF rotation; bake it in once so every renderer shows it upright. */
    private fun uprightImage(file: java.io.File) {
        val degrees = runCatching {
            when (android.media.ExifInterface(file.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (degrees == 0f) return
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 4096) sample *= 2
        val src = android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
        val m = android.graphics.Matrix().apply { postRotate(degrees) }
        val up = android.graphics.Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        java.io.FileOutputStream(file).use { up.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it); it.fd.sync() }
    }

    // ---- lasso selection ----

    fun onLassoSelected(pageId: String, ids: Set<String>) =
        _state.update { it.copy(selection = ids, selectionPage = if (ids.isEmpty()) null else pageId) }
    fun clearSelection() = _state.update { it.copy(selection = emptySet(), selectionPage = null) }

    /** The selection's page and its editor, or null. */
    private fun selectionEditor(): Pair<String, PageEditor>? {
        val id = _state.value.selectionPage ?: return null
        return editors[id]?.let { id to it }
    }

    private fun selectedStrokes(): List<Stroke> {
        val ids = _state.value.selection
        return selectionEditor()?.second?.currentStrokes?.filter { it.id in ids }.orEmpty()
    }

    fun transformSelection(pageId: String, ids: Set<String>, t: app.folio.core.ink.SelectionTransform) {
        val ed = editors[pageId] ?: return
        if (t.isIdentity) return
        val edits = ArrayList<PageEdit>()
        val before = ed.currentStrokes.filter { it.id in ids }
        if (before.isNotEmpty()) edits += PageEdit.ReplaceStrokes(before, before.map(t::apply))
        // Boxes don't turn: a rotation moves them so their middle follows the selection.
        fun moved(r: app.folio.core.model.Rect): Pair<Float, Float> {
            if (t.rotation == 0f) return 0f to 0f
            val (cx, cy) = t.map((r.left + r.right) / 2, (r.top + r.bottom) / 2)
            val sx = t.anchorX + ((r.left + r.right) / 2 - t.anchorX) * t.scale + t.dx
            val sy = t.anchorY + ((r.top + r.bottom) / 2 - t.anchorY) * t.scale + t.dy
            return (cx - sx) to (cy - sy)
        }
        for (x in ed.currentTexts) if (x.id in ids) { val (mx, my) = moved(x.bounds); edits += PageEdit.PutText(x, x.transformed(t.scale, t.anchorX, t.anchorY, t.dx + mx, t.dy + my)) }
        for (x in ed.currentImages) if (x.id in ids) { val (mx, my) = moved(x.bounds); edits += PageEdit.PutImage(x, x.transformed(t.scale, t.anchorX, t.anchorY, t.dx + mx, t.dy + my)) }
        for (x in ed.currentStickies) if (x.id in ids) { val (mx, my) = moved(x.bounds); edits += PageEdit.PutSticky(x, x.transformed(t.scale, t.anchorX, t.anchorY, t.dx + mx, t.dy + my)) }
        for (x in ed.currentTables) if (x.id in ids) { val (mx, my) = moved(x.bounds); edits += PageEdit.PutTable(x, x.transformed(t.scale, t.anchorX, t.anchorY, t.dx + mx, t.dy + my)) }
        for (x in ed.currentAudio) if (x.id in ids) { val (mx, my) = moved(x.bounds); edits += PageEdit.PutAudio(x, x.transformed(t.scale, t.anchorX, t.anchorY, t.dx + mx, t.dy + my)) }
        commit(pageId, edits)
    }

    fun deleteSelection() {
        val (pageId, ed) = selectionEditor() ?: return
        val ids = _state.value.selection
        if (ids.isEmpty()) return
        _state.update { it.copy(selection = emptySet(), selectionPage = null) }
        val edits = ArrayList<PageEdit>()
        if (ed.currentStrokes.any { it.id in ids }) edits += PageEdit.RemoveStrokes.of(ed.currentStrokes, ids)
        // Highest index first, so undo (which runs in reverse) puts each back in its place.
        ed.currentTexts.withIndex().filter { it.value.id in ids }.sortedByDescending { it.index }
            .forEach { edits += PageEdit.PutText(it.value, null, it.index) }
        ed.currentImages.withIndex().filter { it.value.id in ids }.sortedByDescending { it.index }
            .forEach { edits += PageEdit.PutImage(it.value, null, it.index) }
        ed.currentStickies.withIndex().filter { it.value.id in ids }.sortedByDescending { it.index }
            .forEach { edits += PageEdit.PutSticky(it.value, null, it.index) }
        ed.currentTables.withIndex().filter { it.value.id in ids }.sortedByDescending { it.index }
            .forEach { edits += PageEdit.PutTable(it.value, null, it.index) }
        ed.currentAudio.withIndex().filter { it.value.id in ids }.sortedByDescending { it.index }
            .forEach { edits += PageEdit.PutAudio(it.value, null, it.index) }
        commit(pageId, edits)
    }

    /** Copies the selection slightly offset and selects the copies, as most note apps do. */
    fun duplicateSelection() {
        val (pageId, ed) = selectionEditor() ?: return
        val ids = _state.value.selection
        val d = DUPLICATE_OFFSET
        val strokeCopies = selectedStrokes().map { it.transformed(1f, 0f, 0f, d, d, newId = true) }
        val textCopies = ed.currentTexts.filter { it.id in ids }.map { it.transformed(1f, 0f, 0f, d, d, newId = true) }
        val imageCopies = ed.currentImages.filter { it.id in ids }.map { it.transformed(1f, 0f, 0f, d, d, newId = true) }
        val edits = ArrayList<PageEdit>()
        if (strokeCopies.isNotEmpty()) edits += PageEdit.AddStrokes(strokeCopies)
        textCopies.forEach { edits += PageEdit.PutText(null, it) }
        imageCopies.forEach { edits += PageEdit.PutImage(null, it) }
        if (edits.isEmpty()) return
        commit(pageId, edits)
        _state.update { it.copy(selection = (strokeCopies.map { c -> c.id } + textCopies.map { c -> c.id } + imageCopies.map { c -> c.id }).toCollection(LinkedHashSet())) }
    }

    fun recolorSelection(color: Long) {
        val (pageId, ed) = selectionEditor() ?: return
        val ids = _state.value.selection
        val edits = ArrayList<PageEdit>()
        val src = selectedStrokes()
        if (src.isNotEmpty()) edits += PageEdit.ReplaceStrokes(src, src.map { it.recolored(color) })
        for (x in ed.currentTexts) if (x.id in ids && x.color != color) edits += PageEdit.PutText(x, x.copy(color = color))
        commit(pageId, edits)
    }

    /** One undo step for one or several edits. */
    private fun commit(pageId: String, edits: List<PageEdit>) {
        when (edits.size) {
            0 -> Unit
            1 -> edit(pageId, edits[0])
            else -> edit(pageId, PageEdit.Composite(edits))
        }
    }

    /** Applies [e] to [pageId] (queued if the page is still loading) and records it for Undo. */
    private fun edit(pageId: String, e: PageEdit) {
        if (_state.value.meta?.pageIds?.contains(pageId) != true) return // the page was deleted meanwhile
        val ed = editors[pageId]
        history.record(pageId, e)
        if (ed == null) {
            queued.getOrPut(pageId) { ArrayList() } += e
            _state.update { it.copy(save = SaveStatus.PENDING) }
            refreshUndo()
            return
        }
        val t0 = System.nanoTime()
        ed.perform(e)
        val t1 = System.nanoTime()
        changed(pageId, ed)
        if (ed.currentStrokes.size > 1000) android.util.Log.d("FolioPerf", "vm edit perform_ms=%.1f publish_ms=%.1f".format((t1 - t0) / 1e6, (System.nanoTime() - t1) / 1e6))
    }

    private fun changed(pageId: String, ed: PageEditor) {
        publish(pageId, ed)
        scheduleSave()
        thumbChanged(pageId)
    }

    /** Hands the page's current content to the canvas (if it's in the window) and refreshes Undo. */
    private fun publish(pageId: String, ed: PageEditor, index: StrokeIndex? = null) {
        val page = loadedPage(pageId, ed, index)
        shown[pageId] = page
        // Undo/redo can remove selected strokes; never keep a selection of ghosts.
        // (Only build the id set when something is selected: pages can hold thousands of strokes.)
        _state.update {
            val selection = if (it.selection.isEmpty() || it.selectionPage != pageId) it.selection else {
                val live = page.strokes.mapTo(HashSet()) { s -> s.id }
                ed.currentTexts.mapTo(live) { t -> t.id }; ed.currentImages.mapTo(live) { i -> i.id }
                it.selection.filterTo(LinkedHashSet()) { id -> id in live }
            }
            it.copy(
                pages = if (pageId in window) it.pages + (pageId to page) else it.pages,
                canUndo = history.canUndo(editors::get, ::loading), canRedo = history.canRedo(editors::get), selection = selection,
            )
        }
    }

    fun updateTools(transform: (ToolState) -> ToolState) {
        if (!transform(_state.value.tools).text) commitText()
        _state.update {
            val tools = transform(it.tools)
            it.copy(tools = tools, selection = if (tools.lasso) it.selection else emptySet())
        }
        prefs.save(_state.value.tools)
    }

    fun dismissError() = _state.update { it.copy(error = null) }
    fun reportError(message: String) = _state.update { it.copy(error = message) }
    fun dismissExport() = _state.update { it.copy(exported = null) }

    /** Saves everything, then writes the whole notebook as a PDF to Downloads/Folio. */
    fun exportPdf() {
        val meta = _state.value.meta ?: return
        if (_state.value.exporting) return
        _state.update { it.copy(exporting = true) }
        viewModelScope.launch {
            try {
                flushNow()
                val uri = withContext(folio.ioWriter) {
                    app.folio.notes.pdf.NotebookPdfExporter.exportToDownloads(getApplication(), repo, meta)
                }
                _state.update { it.copy(exporting = false, exported = ExportResult(uri, app.folio.notes.pdf.NotebookPdfExporter.fileName(meta.title))) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(exporting = false, error = "Export failed: ${e.message}") }
            }
        }
    }

    private fun scheduleSave(delayMs: Long = SAVE_DEBOUNCE_MS) {
        _state.update { if (it.save == SaveStatus.ERROR) it else it.copy(save = SaveStatus.PENDING) }
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            delay(delayMs)
            folio.appScope.launch { flushNow() }
        }
    }

    /** Starts saving every dirty page immediately; survives this ViewModel being cleared. */
    fun flush() {
        debounceJob?.cancel()
        folio.appScope.launch { flushNow() }
    }

    private suspend fun flushNow() {
        val dirty = editors.filter { (id, ed) -> savedRevision[id] != ed.revision }
        if (dirty.isEmpty()) return
        _state.update { it.copy(save = SaveStatus.SAVING) }
        try {
            for ((id, ed) in dirty) {
                val rev = ed.revision
                val snapshot = ed.snapshot()
                withContext(folio.ioWriter) { repo.savePage(notebookId, snapshot) }
                // A page replaced meanwhile by one from another device has its own save coming.
                if (editors[id] === ed) { savedRevision[id] = rev; savedPages[id] = snapshot }
            }
            retryAttempt = 0
            val stillDirty = queued.isNotEmpty() || editors.any { (id, ed) -> savedRevision[id] != ed.revision }
            _state.update { it.copy(save = if (stillDirty) SaveStatus.PENDING else SaveStatus.SAVED) }
            evictIdle() // saved pages that scrolled away can go now
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Edits stay in memory; retry with exponential backoff (2s, 4s … 60s).
            val wait = (2_000L shl retryAttempt.coerceAtMost(5)).coerceAtMost(60_000L)
            val first = retryAttempt == 0
            retryAttempt++
            _state.update {
                it.copy(save = SaveStatus.ERROR, error = if (first) "Saving failed: ${e.message}. Your changes are kept in memory and saving will be retried." else it.error)
            }
            folio.appScope.launch { delay(wait); flushNow() }
        }
    }

    /** Pictures and PDFs of this notebook, shared with the canvas's tile renderer. */
    internal val assets = PageAssets({ repo.imageFile(notebookId, it) }, { repo.attachmentFile(notebookId, it) })

    /** Sidebar thumbnails; drawn with [assets] so pictures and PDF files are opened once. */
    internal val thumbnails = PageThumbnails(app.cacheDir, notebookId, assets)

    /**
     * A page edited on another device arrived: merge it with any unsaved edits here, show
     * it, and return it for sync to store (that counts as this page's save). The page's
     * undo history starts over.
     */
    private fun receive(remote: Page): Page {
        val ed = editors[remote.id] ?: return remote // not loaded: nothing to merge
        val clean = savedRevision[remote.id] == ed.revision
        val merged = if (clean) remote
        else app.folio.core.model.PageMerge.merge(savedPages[remote.id] ?: Page(id = remote.id), ed.snapshot(), remote)
        val fresh = PageEditor(merged)
        editors[remote.id] = fresh
        thumbChanged(remote.id, now = true)
        savedPages[remote.id] = merged
        savedRevision[remote.id] = fresh.revision
        rebuildStack()
        publish(remote.id, fresh) // its undo history starts over (NotebookHistory skips the old entries)
        if (editors.none { (id, e) -> savedRevision[id] != e.revision }) _state.update { if (it.save == SaveStatus.ERROR) it else it.copy(save = SaveStatus.SAVED) }
        return merged
    }

    /** Pages added or reordered on another device. */
    private fun refreshMeta() {
        viewModelScope.launch {
            val meta = withContext(folio.ioWriter) { repo.library().notebooks.firstOrNull { it.id == notebookId } } ?: return@launch
            applyMeta(meta)
        }
    }

    override fun onCleared() {
        recognizer?.close()
        if (folio.sync.liveEditor === live) folio.sync.liveEditor = null
        thumbnails.release()
        assets.release()
        flush()
        super.onCleared()
    }

    // ---- page navigator: sidebar, page menu, single-page export ----

    fun toggleSidebar() {
        val open = !_state.value.sidebarOpen
        prefs.sidebarOpen = open
        _state.update { it.copy(sidebarOpen = open) }
    }

    /** The page's thumbnail at [version] (see [thumbVersions]), [widthPx] wide. */
    internal suspend fun thumbnail(pageId: String, version: Long, widthPx: Int): PageThumbnails.Thumb? {
        thumbnails.peek(pageId, version)?.takeIf { it.bitmap.width == widthPx }?.let { return it }
        // Loaded pages are drawn from memory (the editor may hold unsaved edits); a snapshot
        // is taken here because editors belong to the main thread.
        val ed = editors[pageId]
        val dirty = ed != null && savedRevision[pageId] != ed.revision
        val snapshot = ed?.snapshot()
        return thumbnails.get(pageId, version, widthPx, object : PageThumbnails.Source {
            override fun stamp() = if (dirty) null else repo.pageStamp(notebookId, pageId)
            override fun page() = snapshot ?: repo.loadPage(notebookId, pageId)
        })
    }

    /** Whether the page has ink, text or pictures; null if not known yet (not loaded or drawn). */
    internal fun pageHasContent(pageId: String): Boolean? =
        editors[pageId]?.let { PageOps.hasContent(it.currentStrokes, it.currentTexts, it.currentImages) }
            ?: thumbnails.peekAny(pageId)?.hasContent

    /** Marks a page's thumbnail stale: at once, or once the user pauses (while writing). */
    private fun thumbChanged(pageId: String, now: Boolean = false) {
        if (now) { bumpThumbs(setOf(pageId)); return }
        thumbsDue += pageId
        thumbJob?.cancel()
        thumbJob = viewModelScope.launch {
            delay(THUMB_DEBOUNCE_MS)
            val due = thumbsDue.toSet()
            thumbsDue.clear()
            bumpThumbs(due)
        }
    }

    /** The pages' look changed (dark mode, page colour, ruling): every thumbnail is drawn again. */
    fun setPageLook(look: PageLook) {
        if (thumbnails.look == look) return
        thumbnails.look = look
        bumpThumbs(_state.value.meta?.pageIds?.toSet().orEmpty())
    }

    private fun bumpThumbs(ids: Set<String>) {
        if (ids.isEmpty()) return
        _thumbVersions.update { m -> m + ids.associateWith { (m[it] ?: 0L) + 1 } }
    }

    /** Adds a blank page before or after [refPageId], with its size and paper, and scrolls to it. */
    fun addPage(refPageId: String, before: Boolean): Unit = pageOp {
        commitText()
        val at = _state.value.meta?.pageIds?.indexOf(refPageId) ?: -1
        if (at < 0) return@pageOp
        val page = PageOps.blankLike(pageShape(refPageId))
        val updated = withContext(folio.ioWriter) { repo.insertPage(notebookId, if (before) at else at + 1, page) }
        adopt(page)
        applyMeta(updated)
        openPage(updated.pageIds.indexOf(page.id))
    }

    /** Inserts an independent copy of the page right after it and opens the copy. */
    fun duplicatePage(pageId: String): Unit = pageOp {
        commitText()
        val at = _state.value.meta?.pageIds?.indexOf(pageId) ?: -1
        if (at < 0) return@pageOp
        val source = editors[pageId]?.snapshot() ?: withContext(folio.ioWriter) { repo.loadPage(notebookId, pageId) }
        val copy = PageOps.duplicate(source)
        val updated = withContext(folio.ioWriter) { repo.insertPage(notebookId, at + 1, copy) }
        adopt(copy)
        applyMeta(updated)
        openPage(updated.pageIds.indexOf(copy.id))
    }

    /** Moves the page to [toIndex]; the open page stays open. Offers Undo. */
    fun movePage(pageId: String, toIndex: Int, offerUndo: Boolean = true): Unit = pageOp {
        commitText()
        val meta = _state.value.meta ?: return@pageOp
        val from = meta.pageIds.indexOf(pageId)
        if (from < 0 || from == toIndex.coerceIn(0, meta.pageIds.lastIndex)) return@pageOp
        val updated = withContext(folio.ioWriter) { repo.movePage(notebookId, pageId, toIndex) }
        applyMeta(updated)
        val to = updated.pageIds.indexOf(pageId)
        if (offerUndo) _events.trySend(PageEvent.Notice("Page ${from + 1} moved to position ${to + 1}") { movePage(pageId, from, offerUndo = false) })
    }

    /** Deletes the page (the screen asks first). A notebook keeps at least one page. Offers Undo. */
    fun deletePage(pageId: String): Unit = pageOp {
        commitText()
        flushNow()
        val meta = _state.value.meta ?: return@pageOp
        val at = meta.pageIds.indexOf(pageId)
        if (at < 0 || meta.pageIds.size <= 1) return@pageOp
        // Kept in memory for Undo, with its undo history.
        val editor = editors[pageId] ?: withContext(folio.ioWriter) { PageEditor(repo.loadPage(notebookId, pageId)) }
        val snapshot = editor.snapshot()
        editors.remove(pageId); savedRevision.remove(pageId); savedPages.remove(pageId); thumbsDue.remove(pageId)
        shown.remove(pageId); queued.remove(pageId)
        if (_state.value.selectionPage == pageId) clearSelection()
        val updated = try {
            withContext(folio.ioWriter) { repo.deletePage(notebookId, pageId) }
        } catch (e: Exception) {
            adopt(snapshot, editor)
            throw e
        }
        // The canvas keeps its place: the next page slides up where the deleted one was.
        applyMeta(updated)
        _events.trySend(PageEvent.Notice("Page ${at + 1} deleted") { restorePage(at, snapshot, editor) })
    }

    private fun restorePage(index: Int, page: Page, editor: PageEditor): Unit = pageOp {
        if (_state.value.meta?.pageIds?.contains(page.id) != false) return@pageOp
        val updated = withContext(folio.ioWriter) { repo.insertPage(notebookId, index, page) }
        adopt(page, editor)
        applyMeta(updated)
        openPage(updated.pageIds.indexOf(page.id))
    }

    /** Removes the page's ink, text and pictures (paper and PDF stay) as one undoable edit. Offers Undo. */
    fun erasePage(pageId: String): Unit = pageOp {
        commitText()
        val at = _state.value.meta?.pageIds?.indexOf(pageId) ?: -1
        if (at < 0) return@pageOp
        val ed = editors[pageId] ?: adopt(withContext(Dispatchers.IO) { repo.loadPage(notebookId, pageId) })
        val edit = PageOps.erase(ed.currentStrokes, ed.currentTexts, ed.currentImages) ?: return@pageOp
        ed.perform(edit)
        history.record(pageId, edit)
        pageEdited(pageId, ed)
        _events.trySend(PageEvent.Notice("Page ${at + 1} erased") {
            // Only while the erase is still the page's latest edit (Undo in the top bar covers the rest).
            if (editors[pageId] === ed && history.undoEdit(pageId, edit, editors::get)) pageEdited(pageId, ed)
        })
    }

    /** An edit made from the page menu, possibly on a page that isn't open. */
    private fun pageEdited(pageId: String, ed: PageEditor) {
        publish(pageId, ed)
        scheduleSave()
        thumbChanged(pageId, now = true)
    }

    /** Suggested file name for exporting the page. */
    fun exportFileName(pageId: String, spec: PageExportSpec): String {
        val meta = _state.value.meta
        val number = (meta?.pageIds?.indexOf(pageId) ?: 0) + 1
        return PageExportSpec.fileName(meta?.title ?: "", number, spec.format)
    }

    /** Export waiting for the system file picker: page id and format. Kept here so it survives rotation. */
    var pendingSave: Pair<String, PageExportSpec>? = null

    /** Writes the page to the document the user created in the file picker (null: they cancelled). */
    fun savePageTo(uri: android.net.Uri?) {
        val (pageId, spec) = pendingSave ?: return
        pendingSave = null
        if (uri == null) return
        pageExport { meta ->
            withContext(Dispatchers.IO) {
                PageExporter.saveTo(getApplication(), uri) { PageExporter.write(repo, meta, pageId, spec, it) }
            }
            _events.trySend(PageEvent.Notice("Page ${meta.pageIds.indexOf(pageId) + 1} saved as ${spec.format.label}"))
        }
    }

    /** Writes the page to a private file and hands it to the share sheet. */
    fun sharePage(pageId: String, spec: PageExportSpec): Unit = pageExport { meta ->
        val name = exportFileName(pageId, spec)
        val uri = withContext(Dispatchers.IO) {
            PageExporter.shareFile(getApplication(), name) { PageExporter.write(repo, meta, pageId, spec, it) }
        }
        _events.trySend(PageEvent.Share(uri, spec.format.mime, name))
    }

    /** Saves everything first (exports read the store), then runs [block] with progress and errors handled. */
    private fun pageExport(block: suspend (NotebookMeta) -> Unit) {
        val meta = _state.value.meta ?: return
        if (_state.value.pageExporting) return
        _state.update { it.copy(pageExporting = true) }
        viewModelScope.launch {
            try {
                commitText()
                flushNow()
                block(_state.value.meta ?: meta)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "The export failed: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                _state.update { it.copy(pageExporting = false) }
            }
        }
    }

    /** Runs a page operation after the previous one finished; failures become a message. */
    private fun pageOp(block: suspend () -> Unit) {
        viewModelScope.launch {
            pageOps.withLock {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(error = "That didn't work: ${e.message ?: e.javaClass.simpleName}") }
                }
            }
        }
    }

    /** Size and paper of a page, without loading it when it's open or already drawn. */
    private suspend fun pageShape(pageId: String): Page =
        savedPages[pageId]
            ?: thumbnails.peekAny(pageId)?.let { Page(id = pageId, width = it.pageWidth, height = it.pageHeight, paper = it.paper) }
            ?: withContext(folio.ioWriter) { repo.loadPage(notebookId, pageId) }

    /** Takes a page just written to disk as loaded and saved, so opening it is instant. */
    private fun adopt(page: Page, editor: PageEditor = PageEditor(page)): PageEditor {
        editors[page.id] = editor
        savedPages[page.id] = page
        savedRevision[page.id] = editor.revision
        return editor
    }

    /** New notebook details: the column is laid out again; the page in view keeps its place on screen. */
    private fun applyMeta(meta: NotebookMeta) {
        _state.update { s ->
            val at = currentPageId?.let { meta.pageIds.indexOf(it) } ?: -1
            s.copy(meta = meta, pageIndex = if (at >= 0) at else s.pageIndex.coerceIn(0, maxOf(0, meta.pageIds.lastIndex)))
        }
        rebuildStack()
        readHeaders(meta.pageIds)
        updateWindow()
    }

    // ---- Samsung Notes-style editor: modes, toolbar, tools (SAMSUNG_EDITOR_PLAN T3–T6) ----

    /** Which recording is playing (drawn as playing on its chip). */
    private var playingAudio: String? = null

    fun setReading(on: Boolean) {
        commitText()
        _state.update { it.copy(reading = on, readingLaser = false, selection = if (on) emptySet() else it.selection, selectionPage = if (on) null else it.selectionPage) }
    }
    fun toggleReadingLaser() = _state.update { it.copy(readingLaser = !it.readingLaser) }
    fun setFullScreen(on: Boolean) = _state.update { it.copy(fullScreen = on) }

    fun updateToolbar(config: ToolbarConfig) {
        val c = config.normalized()
        prefs.toolbar = c
        _state.update { it.copy(toolbar = c) }
    }

    /** Renames the notebook (the title in the app bar). */
    fun rename(title: String) {
        val t = title.trim().take(120)
        val meta = _state.value.meta ?: return
        if (t.isEmpty() || t == meta.title) return
        updateMeta { it.copy(title = t) }
    }

    /** Changes notebook settings (title, bookmarks, page colour, dark adjust, paper density) and saves them. */
    fun updateMeta(change: (NotebookMeta) -> NotebookMeta) {
        val meta = _state.value.meta ?: return
        val updated = change(meta).copy(modifiedAt = System.currentTimeMillis())
        _state.update { it.copy(meta = updated) }
        viewModelScope.launch {
            try {
                withContext(folio.ioWriter) { repo.updateNotebook(updated) }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Couldn't save the note's settings: ${e.message}") }
            }
        }
    }

    // ---- More menu (SAMSUNG_EDITOR_PLAN T10) ----

    fun toggleFavorite() {
        val fav = !(_state.value.meta?.favorite ?: return)
        updateMeta { it.copy(favorite = fav) }
        _events.trySend(PageEvent.Notice(if (fav) "Added to favourites" else "Removed from favourites"))
    }

    /** Live folders for Move to folder, each with its depth in the tree, in tree order. */
    suspend fun folderTree(): List<Pair<app.folio.core.model.Folder, Int>> = withContext(Dispatchers.IO) {
        val idx = repo.library()
        val live = idx.folders.filter { it.deletedAt == null }
        val byParent = live.groupBy { it.parentId }
        val out = ArrayList<Pair<app.folio.core.model.Folder, Int>>()
        fun walk(parent: String?, depth: Int) {
            for (f in byParent[parent].orEmpty().sortedBy { it.name.lowercase() }) { out += f to depth; walk(f.id, depth + 1) }
        }
        walk(null, 0)
        out
    }

    /** Moves the notebook into [folderId] (null = Documents, the top level). */
    fun moveToFolder(folderId: String?, folderName: String) {
        if (_state.value.meta?.folderId == folderId) return
        viewModelScope.launch {
            try {
                withContext(folio.ioWriter) { repo.moveItems(listOf(notebookId), emptyList(), folderId) }
                _state.update { s -> s.copy(meta = s.meta?.copy(folderId = folderId)) }
                _events.trySend(PageEvent.Notice("Moved to $folderName"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "Couldn't move the note: ${e.message}") }
            }
        }
    }

    /** Saves everything, moves the notebook to Trash (restorable from the library), then [onDone] closes the editor. */
    fun moveToTrash(onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                commitText()
                flushNow()
                withContext(folio.ioWriter) { repo.trashNotebook(notebookId) }
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "Couldn't move the note to Trash: ${e.message}") }
            }
        }
    }

    /** The whole notebook as a PDF, for the share sheet. */
    fun shareNotebook(): Unit = pageExport { meta ->
        val name = app.folio.notes.pdf.NotebookPdfExporter.fileName(meta.title)
        val uri = withContext(Dispatchers.IO) {
            PageExporter.shareFile(getApplication(), name) { app.folio.notes.pdf.NotebookPdfExporter.write(repo, meta, it) }
        }
        _events.trySend(PageEvent.Share(uri, "application/pdf", name))
    }

    /** The whole notebook as a PDF, handed to the system print dialog. */
    fun printNotebook(): Unit = pageExport { meta ->
        val name = app.folio.notes.pdf.NotebookPdfExporter.fileName(meta.title)
        val file = withContext(Dispatchers.IO) {
            val dir = java.io.File(getApplication<android.app.Application>().cacheDir, "print").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            java.io.File(dir, name).also { f -> f.outputStream().use { app.folio.notes.pdf.NotebookPdfExporter.write(repo, meta, it) } }
        }
        _events.trySend(PageEvent.Print(file, meta.title))
    }

    /**
     * Note style: every page takes [paper] (PDF pages keep their PDF) and the notebook's ruling
     * takes [density]; new pages follow. A setting of the note, so it isn't undone with Undo.
     */
    fun applyNoteStyle(paper: PaperTemplate, density: app.folio.core.model.PaperDensity) {
        val meta = _state.value.meta ?: return
        updateMeta { it.copy(paper = paper, density = density) }
        viewModelScope.launch {
            for (id in meta.pageIds) {
                val ed = editors[id]
                if (ed != null) {
                    if (savedPages[id]?.pdf == null && ed.paper != paper) { ed.setPaper(paper); changed(id, ed) }
                    continue
                }
                try {
                    val restyled = withContext(folio.ioWriter) {
                        val page = repo.loadPage(notebookId, id)
                        if (page.pdf != null || page.paper == paper) false else { repo.savePage(notebookId, page.copy(paper = paper)); true }
                    }
                    if (!restyled) continue
                    headers[id]?.let { h -> headers[id] = h.copy(paper = paper) }
                    // Loaded while this page was being rewritten: it read the old paper.
                    editors[id]?.let { e -> e.setPaper(paper); changed(id, e) }
                    thumbChanged(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(error = "Couldn't change the paper of every page: ${e.message}") }
                    return@launch
                }
            }
        }
    }

    // ---- eyedropper ----

    /** The next tap on the page picks a colour for [tool] (and quick slot [slot]). */
    fun startEyedropper(tool: EditorTool, slot: Int?) = _state.update { it.copy(eyedropper = tool to slot) }
    fun cancelEyedropper() = _state.update { it.copy(eyedropper = null) }

    fun onColorPicked(color: Long) {
        val (tool, slot) = _state.value.eyedropper ?: (_state.value.tools.colorTool to null)
        _state.update { it.copy(eyedropper = null) }
        updateTools { t -> (if (slot != null) t.withQuickColor(slot, color) else t).withColorFor(tool, color) }
        if (_state.value.selection.isNotEmpty()) recolorSelection(color)
    }

    // ---- change style, tape ----

    /** Strokes restyled in place (Change style sweep, tape peeled or covered): one undo step. */
    fun onStrokesReplaced(pageId: String, after: List<Stroke>) {
        val ed = editors[pageId] ?: return
        val ids = after.associateBy { it.id }
        val before = ed.currentStrokes.filter { it.id in ids }
        if (before.isEmpty()) return
        edit(pageId, PageEdit.ReplaceStrokes(before, before.mapNotNull { ids[it.id] }))
    }

    /** The page in view's id. */
    private fun currentId(): String? = currentPageId ?: _state.value.meta?.pageIds?.getOrNull(_state.value.pageIndex)

    /** Eraser popup: every stroke on the page in view (not text or pictures), one undo step. */
    fun eraseAllHandwriting() {
        val id = currentId() ?: return
        val ed = editors[id] ?: return
        val t = _state.value.tools
        val ids = ed.currentStrokes.filter {
            when {
                t.eraseHighlighterOnly -> it.tool == app.folio.core.model.ToolType.HIGHLIGHTER
                t.eraseTapeOnly -> it.tool == app.folio.core.model.ToolType.TAPE
                else -> true
            }
        }.mapTo(HashSet()) { it.id }
        if (ids.isEmpty()) return
        edit(id, PageEdit.RemoveStrokes.of(ed.currentStrokes, ids))
        _events.trySend(PageEvent.Notice("Handwriting erased") { undo() })
    }

    /** Tape popup: all tape on the page in view, one undo step. */
    fun removeAllTape() {
        val id = currentId() ?: return
        val ed = editors[id] ?: return
        val ids = ed.currentStrokes.filter { it.tool == app.folio.core.model.ToolType.TAPE }.mapTo(HashSet()) { it.id }
        if (ids.isEmpty()) return
        edit(id, PageEdit.RemoveStrokes.of(ed.currentStrokes, ids))
    }

    // ---- sticky notes and other page objects ----

    /** Ink written on a sticky note joins the note (its own coordinates); one undo step. */
    fun onStickyStroke(pageId: String, stickyId: String, stroke: Stroke) {
        val ed = editors[pageId] ?: return
        val i = ed.currentStickies.indexOfFirst { it.id == stickyId }
        if (i < 0) { edit(pageId, PageEdit.AddStroke(stroke)); return }
        val n = ed.currentStickies[i]
        edit(pageId, PageEdit.PutSticky(n, n.copy(strokes = n.strokes + stroke), i))
    }

    /** The tape on top of a sticky note was tapped: the note collapses to its tab (one undo step). */
    fun collapseSticky(pageId: String, stickyId: String) {
        val ed = editors[pageId] ?: return
        val i = ed.currentStickies.indexOfFirst { it.id == stickyId }
        if (i < 0) return
        val n = ed.currentStickies[i]
        if (stickyId in _state.value.selection) clearSelection()
        if (n.strokes.isEmpty()) {
            // Nothing written on it: it's removed rather than kept as an empty tab (Undo brings it back).
            val e = PageEdit.PutSticky(n, null, i)
            edit(pageId, e)
            _events.trySend(PageEvent.Notice("Empty sticky note removed") { if (editors[pageId]?.lastEdit === e) undo() })
            return
        }
        if (!n.minimized) edit(pageId, PageEdit.PutSticky(n, n.copy(minimized = true), i))
    }

    /** A tap on a page object: play/pause a recording, edit a table, open a minimised note. */
    fun onObjectTap(pageId: String, objectId: String, x: Float, y: Float): Boolean {
        val ed = editors[pageId] ?: return false
        ed.currentStickies.withIndex().firstOrNull { it.value.id == objectId }?.let { (i, n) ->
            if (n.minimized) edit(pageId, PageEdit.PutSticky(n, n.copy(minimized = false), i))
            return true
        }
        ed.currentAudio.firstOrNull { it.id == objectId }?.let { a -> objectTapHandler?.audio(pageId, a); return true }
        ed.currentTables.firstOrNull { it.id == objectId }?.let { t ->
            val col = ((x - t.x) / (t.width / t.cols)).toInt().coerceIn(0, t.cols - 1)
            val row = ((y - t.y) / (t.height / t.rows)).toInt().coerceIn(0, t.rows - 1)
            _state.update { it.copy(editingCell = CellEdit(pageId, t.id, row, col, t.cell(row, col))) }
            return true
        }
        return false
    }

    /** Set by the screen: plays recordings. */
    var objectTapHandler: ObjectTapHandler? = null

    // ---- S Pen to text (T9) ----

    private var recognizer: HandwritingRecognizer? = null
    /** Strokes written since the last recognition, on [spenPage]. */
    private val spenGroup = ArrayList<String>()
    private var spenPage: String? = null
    /** The pause timer; only it is cancelled by more writing, never a recognition under way. */
    private var spenTimer: Job? = null
    /** Groups are read one at a time, in order, so the next words can join the box the last ones became. */
    private val spenLock = Mutex()
    /** The recogniser's model is downloaded and loaded. */
    private var modelReady = false

    fun toggleSpenToText() {
        val on = !_state.value.spenToText
        if (!on) { recognizeNow(); spenRun = null; _state.update { it.copy(spenToText = false, spenStatus = null, spenBox = null) }; return }
        _state.update { it.copy(spenToText = true) }
        ensureModel()
    }

    fun setSpenLanguage(tag: String) {
        if (tag == _state.value.spenLanguage) return
        prefs.spenLanguage = tag
        recognizer?.close(); recognizer = null
        _state.update { it.copy(spenLanguage = tag) }
        if (_state.value.spenToText) ensureModel()
    }

    /** Loads the recogniser, downloading its language model the first time (needs a connection once). */
    private fun ensureModel() {
        val tag = _state.value.spenLanguage
        modelReady = false
        _state.update { it.copy(spenStatus = "Preparing handwriting recognition…") }
        viewModelScope.launch {
            try {
                val r = recognizer?.takeIf { it.languageTag == tag } ?: HandwritingRecognizer(tag).also { recognizer = it }
                if (!r.supported) { _state.update { it.copy(spenStatus = "Handwriting in this language isn't available") }; return@launch }
                val ready = withContext(Dispatchers.IO) { r.isDownloaded() }
                if (!ready) {
                    _state.update { it.copy(spenStatus = "Downloading the handwriting model (once)…") }
                    withContext(Dispatchers.IO) { r.download() }
                }
                modelReady = true
                _state.update { it.copy(spenStatus = null) }
                // Words written while the model was on its way are read now.
                if (spenGroup.isNotEmpty()) recognizeNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(spenStatus = "Couldn't get the handwriting model. Check the connection and try again.") }
            }
        }
    }

    /** A pen stroke was added while S Pen to text is on: it joins the words being written. */
    private fun spenCollect(pageId: String, stroke: Stroke) {
        if (!_state.value.spenToText || !SpenText.recognisable(stroke)) return
        if (spenPage != pageId) { recognizeNow(); spenPage = pageId }
        spenGroup += stroke.id
    }

    /** Recognises the words written so far, right away. */
    private fun recognizeNow() {
        spenTimer?.cancel(); spenTimer = null
        startRecognition()
    }

    private fun startRecognition() {
        val pageId = spenPage ?: return
        // Not ready yet: the words wait (they're read when the model arrives).
        if (!modelReady || spenGroup.isEmpty()) return
        val ids = spenGroup.toSet()
        spenGroup.clear()
        viewModelScope.launch { spenLock.withLock { recognizeGroup(pageId, ids) } }
    }

    private suspend fun recognizeGroup(pageId: String, ids: Set<String>) {
        val ed = editors[pageId] ?: return
        val strokes = ed.currentStrokes.filter { it.id in ids }
        if (strokes.isEmpty()) return
        val r = recognizer ?: return
        val written = SpenText.bounds(strokes) ?: return
        // Writing that carries on the line of the box just made joins it (as one undo step).
        val run = spenRun?.takeIf { it.pageId == pageId && _state.value.spenBox == pageId to it.boxId }
        val prev = run?.let { carriesOn(ed.currentTexts, it, written) }
        val joiner = if (run != null && prev != null && !prev.text.endsWith(" ")) SpenText.joiner(run.ink, written, prev.fontSize) else ""
        val pre = prev?.let { it.text + joiner }
            ?: _state.value.spenBox?.let { (p, id) -> editors[p]?.currentTexts?.firstOrNull { it.id == id }?.text }.orEmpty()
        _state.update { it.copy(spenStatus = it.spenStatus ?: "Recognising…") }
        val text = try {
            withContext(Dispatchers.Default) { r.recognize(strokes, pre) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        _state.update { if (it.spenStatus == "Recognising…") it.copy(spenStatus = null) else it }
        if (text == null) {
            if (_state.value.spenStatus == null) _events.trySend(PageEvent.Notice("Couldn't read that handwriting"))
            return
        }
        // The page may have changed meanwhile: only the strokes still there are replaced.
        val current = editors[pageId] ?: return
        val still = current.currentStrokes.filter { it.id in ids }
        if (still.isEmpty()) return
        val ink = SpenText.bounds(still) ?: return
        val pageW = savedPages[pageId]?.width ?: NotebookMeta.PAGE_WIDTH
        val removal = PageEdit.RemoveStrokes.of(current.currentStrokes, still.mapTo(HashSet()) { it.id })
        val prevNow = run?.let { carriesOn(current.currentTexts, it, ink) }
        val laid = if (prevNow != null) {
            val i = current.currentTexts.indexOfFirst { it.id == prevNow.id }
            val grown = SpenText.appended(prevNow, joiner + text.trimStart(), ink, pageW)
            val h = PageRenderer().layout(grown.text, grown.width, grown.fontSize, grown.color).height.toFloat()
            grown.copy(height = maxOf(h, prevNow.fontSize * 1.3f)).also { commit(pageId, listOf(removal, PageEdit.PutText(prevNow, it, i))) }
        } else {
            val box = SpenText.boxFor(text, ink, pageW, still.first().color or 0xFF000000)
            box.copy(height = maxOf(PageRenderer().layout(box.text, box.width, box.fontSize, box.color).height.toFloat(), box.height))
                .also { commit(pageId, listOf(removal, PageEdit.PutText(null, it))) }
        }
        spenRun = SpenRun(pageId, laid.id, ink, laid.x, laid.y)
        _state.update { it.copy(spenBox = pageId to laid.id) }
    }

    /** Where the last S Pen to text words went: their box (where it was put) and the handwriting they were read from. */
    private class SpenRun(val pageId: String, val boxId: String, val ink: app.folio.core.model.Rect, val x: Float, val y: Float)
    private var spenRun: SpenRun? = null

    /** The run's box, if it's still where it was put, its line isn't ended and [ink] carries on that line. */
    private fun carriesOn(texts: List<TextBox>, run: SpenRun, ink: app.folio.core.model.Rect): TextBox? =
        texts.firstOrNull { it.id == run.boxId }
            ?.takeIf { it.x == run.x && it.y == run.y && !it.text.endsWith("\n") && SpenText.continues(run.ink, ink, it.fontSize) }

    /** The S Pen to text bar: Space, Backspace and Enter edit the newest box (one undo step each). */
    fun spenKey(key: String) {
        val (pageId, id) = _state.value.spenBox ?: return
        val ed = editors[pageId] ?: return
        val i = ed.currentTexts.indexOfFirst { it.id == id }
        if (i < 0) return
        val b = ed.currentTexts[i]
        val text = when (key) { " " -> b.text + " "; "\n" -> b.text + "\n"; "\b" -> b.text.dropLast(1); else -> b.text + key }
        if (text.isEmpty()) { edit(pageId, PageEdit.PutText(b, null, i)); _state.update { it.copy(spenBox = null) }; return }
        val h = PageRenderer().layout(text, b.width, b.fontSize, b.color).height.toFloat()
        edit(pageId, PageEdit.PutText(b, b.copy(text = text, height = maxOf(h, b.fontSize)), i))
    }

    /** The bar's Keyboard: type into the newest box. */
    fun spenKeyboard() {
        val (pageId, id) = _state.value.spenBox ?: return
        val ed = editors[pageId] ?: return
        val i = ed.currentTexts.indexOfFirst { it.id == id }
        if (i >= 0) _state.update { it.copy(editingText = TextEdit(pageId, ed.currentTexts[i], ed.currentTexts[i], i)) }
    }
    fun toggleEasyPad() = _state.update { it.copy(easyPad = !it.easyPad, padWindow = null) }

    private var padAspect = 4f

    /** Opens the pad's window at the left margin near the top of the visible part of [pageId]. */
    fun placePad(pageId: String, visible: app.folio.core.model.Rect, aspect: Float) {
        padAspect = aspect
        val (w, h) = pageSize(pageId)
        _state.update { it.copy(padWindow = PadWindow(pageId, PadMoves.initial(w, h, visible, aspect))) }
    }

    /** The pad changed shape (rotation, its height dragged): the window keeps its width and takes the new shape. */
    fun reshapePad(aspect: Float) {
        padAspect = aspect
        val win = _state.value.padWindow ?: return
        val r = win.rect
        val h = r.width / aspect
        _state.update { it.copy(padWindow = win.copy(rect = app.folio.core.model.Rect(r.left, r.top, r.right, r.top + h))) }
    }

    fun movePad(step: PadStep) {
        val win = _state.value.padWindow ?: return
        val (w, h) = pageSize(win.pageId)
        val r = when (step) {
            PadStep.NEXT -> PadMoves.next(win.rect, w, h)
            PadStep.PREVIOUS -> PadMoves.previous(win.rect, w, h)
            PadStep.ENTER -> PadMoves.enter(win.rect, w, h)
        }
        _state.update { it.copy(padWindow = win.copy(rect = r)) }
    }

    /** The window was dragged or resized on the page. */
    fun setPadWindow(pageId: String, rect: app.folio.core.model.Rect) = _state.update { it.copy(padWindow = PadWindow(pageId, rect)) }

    /** Ink written in the pad: it goes onto the page, and the window moves on when it passed the end marker. */
    fun onPadStroke(pageId: String, stroke: Stroke) {
        onStrokeFinished(pageId, stroke)
        onPenLifted()
        val win = _state.value.padWindow ?: return
        if (win.pageId != pageId || stroke.size == 0) return
        val (w, h) = pageSize(pageId)
        val right = stroke.x.max()
        val moved = PadMoves.advance(win.rect, right, w, h)
        if (moved != win.rect) _state.update { it.copy(padWindow = win.copy(rect = moved)) }
    }

    /** A writing stroke began: S Pen to text waits for the pen to lift again before reading. */
    fun onPenDown() { spenTimer?.cancel(); spenTimer = null }

    /** A writing stroke was committed (S Pen to text restarts its pause timer). */
    fun onPenLifted() {
        penLiftListener?.invoke()
        if (!_state.value.spenToText || spenGroup.isEmpty()) return
        spenTimer?.cancel()
        spenTimer = viewModelScope.launch { delay(SpenText.PAUSE_MS); spenTimer = null; startRecognition() }
    }
    var penLiftListener: (() -> Unit)? = null

    // ---- Insert menu (SAMSUNG_EDITOR_PLAN T8) ----

    /** Adds the pages of a PDF after the page in view (each page shows one PDF page) and scrolls to the first. */
    fun insertPdf(uri: android.net.Uri) = pageOp {
        commitText()
        val ctx = getApplication<android.app.Application>()
        val at = (_state.value.meta?.pageIds?.indexOf(currentId()) ?: -1) + 1
        val (added, updated) = withContext(folio.ioWriter) {
            val staged = (ctx.contentResolver.openInputStream(uri) ?: throw java.io.IOException("can't open the PDF")).use { repo.stagePdf(it) }
            try {
                val pages = PdfFile(staged.file).use { pdf ->
                    val n = pdf.pageCount
                    if (n !in 1..PdfFile.MAX_PAGES) throw java.io.IOException("the PDF has $n pages; up to ${PdfFile.MAX_PAGES} are supported")
                    (0 until n).map { i ->
                        val (w, h) = pdf.pageSize(i)
                        Page(
                            width = (w / app.folio.notes.pdf.NotebookPdfExporter.POINTS_PER_UNIT).coerceIn(100f, 10_000f),
                            height = (h / app.folio.notes.pdf.NotebookPdfExporter.POINTS_PER_UNIT).coerceIn(100f, 10_000f),
                            paper = PaperTemplate.BLANK, pdf = PdfBackground(staged.id, i),
                        )
                    }
                }
                repo.addAttachment(notebookId, staged)
                pages to repo.insertPages(notebookId, at, pages)
            } catch (e: Exception) {
                repo.discardStaged(staged); throw e
            }
        }
        added.forEach { adopt(it) }
        applyMeta(updated)
        added.firstOrNull()?.let { first -> openPage(updated.pageIds.indexOf(first.id)) }
        _events.trySend(PageEvent.Notice("${added.size} PDF page${if (added.size == 1) "" else "s"} added"))
    }

    /** A sticky note in the middle of [area] on [pageId], selected so it can be moved or written on. */
    fun insertSticky(pageId: String, area: app.folio.core.model.Rect) {
        commitText()
        val size = minOf(area.width, area.height) * 0.42f
        val s = minOf(maxOf(size, 220f), 460f)
        val note = app.folio.core.model.StickyNote(
            x = (area.left + area.right) / 2 - s / 2, y = (area.top + area.bottom) / 2 - s / 2, width = s, height = s, color = STICKY_COLORS[0],
        )
        edit(pageId, PageEdit.PutSticky(null, note))
        _state.update { it.copy(selection = setOf(note.id), selectionPage = pageId, tools = it.tools.activate(EditorTool.SELECTION)) }
    }

    /** Changes a selected sticky note (colour, minimised); one undo step. */
    fun updateSticky(change: (app.folio.core.model.StickyNote) -> app.folio.core.model.StickyNote) {
        val (pageId, ed) = selectionEditor() ?: return
        val i = ed.currentStickies.indexOfFirst { it.id in _state.value.selection }
        if (i < 0) return
        val n = ed.currentStickies[i]
        val after = change(n)
        if (after != n) edit(pageId, PageEdit.PutSticky(n, after, i))
        if (after.minimized) clearSelection()
    }

    /** The one sticky note selected (alone), for its colour and minimise actions. */
    fun selectedSticky(): app.folio.core.model.StickyNote? {
        val sel = _state.value.selection
        if (sel.size != 1) return null
        return selectionEditor()?.second?.currentStickies?.firstOrNull { it.id in sel }
    }

    /** A [rows] × [cols] table across the middle of [area]. */
    fun insertTable(pageId: String, area: app.folio.core.model.Rect, rows: Int, cols: Int) {
        commitText()
        val w = minOf(area.width * 0.8f, 760f)
        val h = rows * 64f
        val t = app.folio.core.model.PageTable(
            x = (area.left + area.right) / 2 - w / 2, y = (area.top + area.bottom) / 2 - h / 2, width = w, height = h,
            rows = rows.coerceIn(1, app.folio.core.model.PageTable.MAX), cols = cols.coerceIn(1, app.folio.core.model.PageTable.MAX),
            color = _state.value.tools.colorOf(EditorTool.PEN),
        )
        edit(pageId, PageEdit.PutTable(null, t))
        _state.update { it.copy(editingCell = CellEdit(pageId, t.id, 0, 0, "")) }
    }

    fun updateCellText(text: String) = _state.update { s -> s.copy(editingCell = s.editingCell?.copy(text = text)) }

    /** Saves the cell being typed (one undo step) and, with [next], moves on to the next cell. */
    fun commitCell(next: Boolean = false) {
        val e = _state.value.editingCell ?: return
        _state.update { it.copy(editingCell = null) }
        val ed = editors[e.pageId] ?: return
        val i = ed.currentTables.indexOfFirst { it.id == e.tableId }
        if (i < 0) return
        val t = ed.currentTables[i]
        if (t.cell(e.row, e.col) != e.text) edit(e.pageId, PageEdit.PutTable(t, t.withCell(e.row, e.col, e.text), i))
        if (next) {
            val k = e.row * t.cols + e.col + 1
            if (k < t.rows * t.cols) _state.update { it.copy(editingCell = CellEdit(e.pageId, t.id, k / t.cols, k % t.cols, t.cell(k / t.cols, k % t.cols))) }
        }
    }

    /** Table being typed in, for placing the field over its cell. */
    fun tableOf(pageId: String, tableId: String): app.folio.core.model.PageTable? = editors[pageId]?.currentTables?.firstOrNull { it.id == tableId }

    /** Copies a recording or audio file into the notebook and puts its chip near the top-left of [area]. */
    fun insertAudio(uri: android.net.Uri, pageId: String, area: app.folio.core.model.Rect, title: String, durationMs: Long, deleteSource: Boolean = false) {
        commitText()
        val ctx = getApplication<android.app.Application>()
        viewModelScope.launch {
            try {
                val (id, duration) = withContext(folio.ioWriter) {
                    val staged = (ctx.contentResolver.openInputStream(uri) ?: throw java.io.IOException("can't open the audio")).use { repo.stageAudio(it) }
                    val d = if (durationMs > 0) durationMs else runCatching {
                        android.media.MediaMetadataRetriever().use { r -> r.setDataSource(staged.file.path); r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L }
                    }.getOrDefault(0L)
                    repo.addAttachment(notebookId, staged) to d
                }
                if (deleteSource) uri.path?.let { java.io.File(it).delete() }
                val a = app.folio.core.model.PageAudio(
                    attachmentId = id, x = (area.left + 24f).coerceAtMost(area.right - app.folio.core.model.PageAudio.CHIP_WIDTH),
                    y = area.top + 24f, title = title.take(80), durationMs = duration,
                )
                edit(pageId, PageEdit.PutAudio(null, a))
            } catch (e: Exception) {
                _state.update { it.copy(error = "Couldn't add the audio: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    fun audioFile(attachmentId: String): java.io.File = repo.audioFile(notebookId, attachmentId)

    /** The recording now playing (its chip shows a pause sign), or null. */
    fun setPlaying(id: String?) {
        if (playingAudio == id) return
        playingAudio = id
        shown.clear()
        for ((pid, ed) in editors) if (pid in window) publish(pid, ed)
    }

    // ---- selection menu: cut, copy, paste, order, change style ----

    /** What Cut/Copy took, in page units of the page it came from. */
    private var clipboard: Clip? = null
    private class Clip(
        val strokes: List<Stroke>, val texts: List<app.folio.core.model.TextBox>, val images: List<app.folio.core.model.PageImage>,
        val stickies: List<app.folio.core.model.StickyNote>, val tables: List<app.folio.core.model.PageTable>,
        val audio: List<app.folio.core.model.PageAudio>,
    )

    fun copySelection() {
        val (_, ed) = selectionEditor() ?: return
        val ids = _state.value.selection
        clipboard = Clip(
            ed.currentStrokes.filter { it.id in ids }, ed.currentTexts.filter { it.id in ids }, ed.currentImages.filter { it.id in ids },
            ed.currentStickies.filter { it.id in ids }, ed.currentTables.filter { it.id in ids }, ed.currentAudio.filter { it.id in ids },
        )
        _state.update { it.copy(canPaste = true) }
        _events.trySend(PageEvent.Notice("Copied"))
    }

    fun cutSelection() { copySelection(); deleteSelection() }

    /** Pastes onto the page in view: where it was if that's on screen, offset a little, and selects it. */
    fun paste() {
        val clip = clipboard ?: return
        val pageId = _state.value.selectionPage ?: currentId() ?: return
        val d = DUPLICATE_OFFSET
        val strokes = clip.strokes.map { it.transformed(1f, 0f, 0f, d, d, newId = true) }
        val texts = clip.texts.map { it.transformed(1f, 0f, 0f, d, d, newId = true) }
        val images = clip.images.map { it.transformed(1f, 0f, 0f, d, d, newId = true) }
        val stickies = clip.stickies.map { it.transformed(1f, 0f, 0f, d, d, newId = true) }
        val tables = clip.tables.map { it.transformed(1f, 0f, 0f, d, d, newId = true) }
        val audio = clip.audio.map { it.transformed(1f, 0f, 0f, d, d, newId = true) }
        val edits = ArrayList<PageEdit>()
        if (strokes.isNotEmpty()) edits += PageEdit.AddStrokes(strokes)
        texts.forEach { edits += PageEdit.PutText(null, it) }
        images.forEach { edits += PageEdit.PutImage(null, it) }
        stickies.forEach { edits += PageEdit.PutSticky(null, it) }
        tables.forEach { edits += PageEdit.PutTable(null, it) }
        audio.forEach { edits += PageEdit.PutAudio(null, it) }
        if (edits.isEmpty()) return
        commit(pageId, edits)
        // The next paste lands a little further on.
        clipboard = Clip(strokes, texts, images, stickies, tables, audio)
        val ids = LinkedHashSet<String>()
        strokes.mapTo(ids) { it.id }; texts.mapTo(ids) { it.id }; images.mapTo(ids) { it.id }
        stickies.mapTo(ids) { it.id }; tables.mapTo(ids) { it.id }; audio.mapTo(ids) { it.id }
        _state.update { it.copy(selection = ids, selectionPage = pageId, tools = it.tools.activate(EditorTool.SELECTION)) }
    }

    /** Move forwards / backwards in the stacking order, past the next stroke the selection overlaps. */
    fun reorderSelection(forward: Boolean) {
        val (pageId, ed) = selectionEditor() ?: return
        PageEdit.Reorder.step(ed.currentStrokes, _state.value.selection, forward)?.let { edit(pageId, it) }
    }

    /** The selection menu's Change style: the change-style colour and width on every selected stroke. */
    fun restyleSelection() {
        val (pageId, ed) = selectionEditor() ?: return
        val t = _state.value.tools
        val color = t.colorOf(EditorTool.CHANGE_STYLE)
        val src = selectedStrokes()
        if (src.isEmpty()) return
        val after = src.map { if (it.tool == app.folio.core.model.ToolType.HIGHLIGHTER || it.tool == app.folio.core.model.ToolType.TAPE) it.restyled(color, it.width) else it.restyled(color, t.restyleWidth) }
        edit(pageId, PageEdit.ReplaceStrokes(src, after))
    }

    /**
     * Clean up handwriting: each selected stroke (or, with nothing selected, every pen stroke on
     * the page in view) that is close to a line or a shape becomes that clean line or shape.
     * One undo step.
     */
    fun cleanUpHandwriting() {
        val sel = _state.value.selection
        val pageId = if (sel.isNotEmpty()) _state.value.selectionPage else currentId()
        val ed = pageId?.let { editors[it] } ?: return
        val src = ed.currentStrokes.filter {
            (sel.isEmpty() || it.id in sel) && it.tool != app.folio.core.model.ToolType.HIGHLIGHTER && it.tool != app.folio.core.model.ToolType.TAPE && it.size >= 3
        }
        val before = ArrayList<Stroke>(); val after = ArrayList<Stroke>()
        for (st in src) {
            val shape = app.folio.core.ink.ShapeRecognizer.recognize(st.x, st.y) ?: continue
            val clean = shape.toStroke(st.tool, st.color, st.width, st.pressure.average().toFloat().coerceIn(0.15f, 1f), st.startedAt)
            before += st
            after += Stroke(st.id, clean.tool, clean.color, clean.width, clean.x, clean.y, clean.pressure, clean.tilt, clean.orientation, clean.t, clean.startedAt, st.look)
        }
        if (before.isEmpty()) { _events.trySend(PageEvent.Notice("Nothing to clean up: no lines or shapes found")); return }
        edit(pageId, PageEdit.ReplaceStrokes(before, after))
        _events.trySend(PageEvent.Notice("${before.size} stroke${if (before.size == 1) "" else "s"} cleaned up") { undo() })
    }

    companion object {
        /** Sticky note colours: warm yellow, mint, shell pink, light blue (Folio's own values). */
        val STICKY_COLORS = listOf(0xFFFFE9A8, 0xFFC9F0DA, 0xFFFFD6DC, 0xFFCFE5FF)
        const val SAVE_DEBOUNCE_MS = 800L
        const val DUPLICATE_OFFSET = 24f
        /** A page's thumbnail refreshes once writing pauses this long. */
        const val THUMB_DEBOUNCE_MS = 1_000L
        /** Saved pages kept in memory after scrolling away (besides the window around the screen). */
        const val MAX_IDLE_PAGES = 8
        /** …and at most this many strokes among them (a dense imported page holds ~7,000, ~20 MB). */
        const val MAX_IDLE_STROKES = 20_000
    }
}
