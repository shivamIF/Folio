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
)

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

    private val _state = MutableStateFlow(EditorUiState(tools = prefs.load(), sidebarOpen = prefs.sidebarOpen))
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
        savedPages[pageId]?.paper ?: headers[pageId]?.paper ?: _state.value.meta?.paper ?: PaperTemplate.RULED

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
            pageId, info.width, info.height, info.paper, info.pdf,
            ed.currentStrokes.toList(), ed.currentTexts.toList(), ed.currentImages.toList(), backgrounds[pageId], index,
        )
    }

    /** Renders the PDF page off the main thread; cached so flipping pages back is instant. */
    private fun loadBackground(pageId: String, pdf: PdfBackground) {
        val app = getApplication<android.app.Application>()
        val metrics = app.resources.displayMetrics
        // Sharp at fit-width on this screen and moderately zoomed in.
        val widthPx = (maxOf(metrics.widthPixels, metrics.heightPixels) * 1.5f).toInt().coerceAtMost(PdfFile.MAX_RENDER_PX)
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

    fun onStrokeFinished(pageId: String, stroke: Stroke) = edit(pageId, PageEdit.AddStroke(stroke))

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
                _state.update { it.copy(selection = setOf(img.id), selectionPage = pageId, tools = it.tools.copy(lasso = true, eraser = false, text = false)) }
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
        for (x in ed.currentTexts) if (x.id in ids) edits += PageEdit.PutText(x, x.transformed(t.scale, t.anchorX, t.anchorY, t.dx, t.dy))
        for (x in ed.currentImages) if (x.id in ids) edits += PageEdit.PutImage(x, x.transformed(t.scale, t.anchorX, t.anchorY, t.dx, t.dy))
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
                _state.update { it.copy(error = "The page couldn't be exported: ${e.message ?: e.javaClass.simpleName}") }
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

    companion object {
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
