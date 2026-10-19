package app.folio.notes.editor

import android.app.Application
import app.folio.core.model.PdfBackground
import app.folio.notes.pdf.PdfFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.folio.core.model.NotebookMeta
import app.folio.core.model.Page
import app.folio.core.model.PageEdit
import app.folio.core.model.PageEditor
import app.folio.core.model.Stroke
import app.folio.notes.FolioApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A text box being edited: [original] is null for a new one; [index] is its place in the page's list. */
data class TextEdit(val box: app.folio.core.model.TextBox, val original: app.folio.core.model.TextBox?, val index: Int)

data class ExportResult(val uri: android.net.Uri, val fileName: String)

enum class SaveStatus { SAVED, PENDING, SAVING, ERROR }

data class EditorUiState(
    val meta: NotebookMeta? = null,
    val pageIndex: Int = 0,
    val page: Page? = null,
    val strokes: List<Stroke> = emptyList(),
    val texts: List<app.folio.core.model.TextBox> = emptyList(),
    val images: List<app.folio.core.model.PageImage> = emptyList(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** Lasso selection on the current page. */
    val selection: Set<String> = emptySet(),
    /** Rendered PDF page under the ink, when this page comes from an imported PDF. */
    val background: android.graphics.Bitmap? = null,
    val exporting: Boolean = false,
    /** Set after a successful export: where the PDF was saved. */
    val exported: ExportResult? = null,
    val tools: ToolState = ToolState(),
    /** The text box being typed (hidden on the page while its field is shown). */
    val editingText: TextEdit? = null,
    val save: SaveStatus = SaveStatus.SAVED,
    val error: String? = null,
)

/**
 * Owns the notebook being edited. Edits run on the main thread against a
 * [PageEditor]; saves take an immutable snapshot and write it on the app's
 * single writer thread, debounced while the user writes, and flushed at once on stop.
 */
class EditorViewModel(app: Application, private val notebookId: String) : AndroidViewModel(app) {
    private val folio = app as FolioApp
    private val repo = folio.repository
    private val prefs = ToolPrefs(app)
    private val editors = HashMap<String, PageEditor>()
    private val savedRevision = HashMap<String, Long>()
    /** Each loaded page as last read from or written to disk: the base for merging edits from other devices. */
    private val savedPages = HashMap<String, Page>()
    /** Only the debounce timer is ever cancelled; a write that has started always runs to completion. */
    private var debounceJob: Job? = null
    private var retryAttempt = 0

    private val _state = MutableStateFlow(EditorUiState(tools = prefs.load()))
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

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
                openPage(0)
            } catch (e: Exception) {
                _state.update { it.copy(error = "Couldn't open this notebook: ${e.message}") }
            }
        }
    }

    private fun currentEditor(): PageEditor? = _state.value.page?.let { editors[it.id] }

    fun openPage(index: Int) {
        commitText()
        val meta = _state.value.meta ?: return
        if (index !in meta.pageIds.indices) return
        flush()
        val pageId = meta.pageIds[index]
        viewModelScope.launch {
            try {
                val editor = editors[pageId] ?: withContext(folio.ioWriter) {
                    val t0 = System.nanoTime()
                    repo.loadPage(notebookId, pageId).also {
                        android.util.Log.d("FolioPerf", "page load ms=%.1f strokes=%d".format((System.nanoTime() - t0) / 1e6, it.strokes.size))
                    }
                }.let { page -> savedPages[pageId] = page; PageEditor(page) }.also { editors[pageId] = it; savedRevision[pageId] = it.revision }
                val snap = editor.snapshot()
                _state.update { it.copy(pageIndex = index, page = snap, selection = emptySet(), background = null) }
                publish(editor)
                snap.pdf?.let { loadBackground(snap.id, it) }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Page couldn't be loaded: ${e.message}") }
            }
        }
    }

    /** Renders the PDF page off the main thread; cached so flipping pages back is instant. */
    private fun loadBackground(pageId: String, pdf: PdfBackground) {
        val app = getApplication<android.app.Application>()
        val metrics = app.resources.displayMetrics
        // Sharp at fit-width on this screen and moderately zoomed in.
        val widthPx = (maxOf(metrics.widthPixels, metrics.heightPixels) * 1.5f).toInt().coerceAtMost(PdfFile.MAX_RENDER_PX)
        val key = "$notebookId/${pdf.attachmentId}/${pdf.pageIndex}/$widthPx"
        folio.pdfBitmaps.get(key)?.let { bmp -> _state.update { if (it.page?.id == pageId) it.copy(background = bmp) else it }; return }
        viewModelScope.launch {
            try {
                val bmp = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    PdfFile(repo.attachmentFile(notebookId, pdf.attachmentId)).use { it.render(pdf.pageIndex, widthPx) }
                }
                folio.pdfBitmaps.put(key, bmp)
                _state.update { if (it.page?.id == pageId) it.copy(background = bmp) else it }
            } catch (e: Exception) {
                _state.update { it.copy(error = "The PDF page couldn't be displayed: ${e.message}. Your notes on it are safe.") }
            }
        }
    }

    fun addPage() {
        val idx = _state.value.pageIndex
        viewModelScope.launch {
            flushNow()
            val meta = withContext(folio.ioWriter) { repo.addPage(notebookId, idx) }
            _state.update { it.copy(meta = meta) }
            openPage(idx + 1)
        }
    }

    fun onStrokeFinished(stroke: Stroke) = edit(PageEdit.AddStroke(stroke))

    fun onStrokesErased(ids: Set<String>) {
        val ed = currentEditor() ?: return
        edit(PageEdit.RemoveStrokes.of(ed.currentStrokes, ids))
    }

    fun onStrokesSplit(pieces: Map<String, List<Stroke>>) {
        val ed = currentEditor() ?: return
        val t0 = System.nanoTime()
        val e = PageEdit.SplitStrokes.of(ed.currentStrokes, pieces)
        if (ed.currentStrokes.size > 1000) android.util.Log.d("FolioPerf", "vm split-of ms=%.1f".format((System.nanoTime() - t0) / 1e6))
        edit(e)
    }

    fun undo() { commitText(); currentEditor()?.let { if (it.undo()) changed(it) } }
    fun redo() { commitText(); currentEditor()?.let { if (it.redo()) changed(it) } }

    // ---- text boxes ----

    /** Text tool tap: finish the box being typed, then edit the tapped box or start a new one there. */
    fun onTextTap(x: Float, y: Float, textId: String?) {
        commitText()
        val ed = currentEditor() ?: return
        if (textId != null) {
            val i = ed.currentTexts.indexOfFirst { it.id == textId }
            if (i >= 0) { _state.update { it.copy(editingText = TextEdit(ed.currentTexts[i], ed.currentTexts[i], i)) }; return }
        }
        val page = _state.value.page ?: return
        val tools = _state.value.tools
        val size = tools.textSize
        val w = minOf(520f, page.width - 16f).coerceAtLeast(120f)
        val left = x.coerceAtMost(page.width - w - 8f).coerceAtLeast(8f)
        val box = app.folio.core.model.TextBox(
            x = left, y = (y - size * 0.6f).coerceAtLeast(4f), width = w, height = size * 1.4f,
            text = "", fontSize = size, color = tools.color,
        )
        _state.update { it.copy(editingText = TextEdit(box, null, ed.currentTexts.size)) }
    }

    fun updateEditingText(text: String) = _state.update { s -> s.copy(editingText = s.editingText?.let { it.copy(box = it.box.copy(text = text)) }) }

    /** Saves the box being typed (one undo step); an emptied box is removed. */
    fun commitText() {
        val e = _state.value.editingText ?: return
        _state.update { it.copy(editingText = null) }
        val text = e.box.text.trimEnd()
        if (text.isBlank()) {
            if (e.original != null) edit(PageEdit.PutText(e.original, null, e.index))
            return
        }
        val height = PageRenderer().layout(text, e.box.width, e.box.fontSize, e.box.color).height.toFloat()
        val box = e.box.copy(text = text, height = maxOf(height, e.box.fontSize))
        when {
            e.original == null -> edit(PageEdit.PutText(null, box, e.index))
            box != e.original -> edit(PageEdit.PutText(e.original, box, e.index))
        }
    }

    // ---- pictures ----

    /** Copies a picture into the notebook, places it in the middle of [area] (page units) and selects it. */
    fun insertImage(uri: android.net.Uri, area: app.folio.core.model.Rect) {
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
                edit(PageEdit.PutImage(null, img))
                _state.update { it.copy(selection = setOf(img.id), tools = it.tools.copy(lasso = true, eraser = false, text = false)) }
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

    fun onLassoSelected(ids: Set<String>) = _state.update { it.copy(selection = ids) }
    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    private fun selectedStrokes(): List<Stroke> {
        val ids = _state.value.selection
        return currentEditor()?.currentStrokes?.filter { it.id in ids }.orEmpty()
    }

    fun transformSelection(ids: Set<String>, t: app.folio.core.ink.SelectionTransform) {
        val ed = currentEditor() ?: return
        if (t.isIdentity) return
        val edits = ArrayList<PageEdit>()
        val before = ed.currentStrokes.filter { it.id in ids }
        if (before.isNotEmpty()) edits += PageEdit.ReplaceStrokes(before, before.map(t::apply))
        for (x in ed.currentTexts) if (x.id in ids) edits += PageEdit.PutText(x, x.transformed(t.scale, t.anchorX, t.anchorY, t.dx, t.dy))
        for (x in ed.currentImages) if (x.id in ids) edits += PageEdit.PutImage(x, x.transformed(t.scale, t.anchorX, t.anchorY, t.dx, t.dy))
        commit(edits)
    }

    fun deleteSelection() {
        val ed = currentEditor() ?: return
        val ids = _state.value.selection
        if (ids.isEmpty()) return
        _state.update { it.copy(selection = emptySet()) }
        val edits = ArrayList<PageEdit>()
        if (ed.currentStrokes.any { it.id in ids }) edits += PageEdit.RemoveStrokes.of(ed.currentStrokes, ids)
        // Highest index first, so undo (which runs in reverse) puts each back in its place.
        ed.currentTexts.withIndex().filter { it.value.id in ids }.sortedByDescending { it.index }
            .forEach { edits += PageEdit.PutText(it.value, null, it.index) }
        ed.currentImages.withIndex().filter { it.value.id in ids }.sortedByDescending { it.index }
            .forEach { edits += PageEdit.PutImage(it.value, null, it.index) }
        commit(edits)
    }

    /** Copies the selection slightly offset and selects the copies, as most note apps do. */
    fun duplicateSelection() {
        val ed = currentEditor() ?: return
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
        commit(edits)
        _state.update { it.copy(selection = (strokeCopies.map { c -> c.id } + textCopies.map { c -> c.id } + imageCopies.map { c -> c.id }).toCollection(LinkedHashSet())) }
    }

    fun recolorSelection(color: Long) {
        val ed = currentEditor() ?: return
        val ids = _state.value.selection
        val edits = ArrayList<PageEdit>()
        val src = selectedStrokes()
        if (src.isNotEmpty()) edits += PageEdit.ReplaceStrokes(src, src.map { it.recolored(color) })
        for (x in ed.currentTexts) if (x.id in ids && x.color != color) edits += PageEdit.PutText(x, x.copy(color = color))
        commit(edits)
    }

    /** One undo step for one or several edits. */
    private fun commit(edits: List<PageEdit>) {
        when (edits.size) {
            0 -> Unit
            1 -> edit(edits[0])
            else -> edit(PageEdit.Composite(edits))
        }
    }

    private fun edit(e: PageEdit) {
        val ed = currentEditor() ?: return
        val t0 = System.nanoTime()
        ed.perform(e)
        val t1 = System.nanoTime()
        changed(ed)
        if (ed.currentStrokes.size > 1000) android.util.Log.d("FolioPerf", "vm edit perform_ms=%.1f publish_ms=%.1f".format((t1 - t0) / 1e6, (System.nanoTime() - t1) / 1e6))
    }

    private fun changed(ed: PageEditor) {
        publish(ed)
        scheduleSave()
    }

    private fun publish(ed: PageEditor) {
        val strokes = ed.currentStrokes.toList()
        // Undo/redo can remove selected strokes; never keep a selection of ghosts.
        // (Only build the id set when something is selected: pages can hold thousands of strokes.)
        _state.update {
            val selection = if (it.selection.isEmpty()) it.selection else {
                val live = strokes.mapTo(HashSet()) { s -> s.id }
                ed.currentTexts.mapTo(live) { t -> t.id }; ed.currentImages.mapTo(live) { i -> i.id }
                it.selection.filterTo(LinkedHashSet()) { id -> id in live }
            }
            it.copy(
                strokes = strokes, texts = ed.currentTexts.toList(), images = ed.currentImages.toList(),
                canUndo = ed.canUndo, canRedo = ed.canRedo, selection = selection,
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
            val stillDirty = editors.any { (id, ed) -> savedRevision[id] != ed.revision }
            _state.update { it.copy(save = if (stillDirty) SaveStatus.PENDING else SaveStatus.SAVED) }
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
        savedPages[remote.id] = merged
        savedRevision[remote.id] = fresh.revision
        if (_state.value.page?.id == remote.id) {
            _state.update { it.copy(page = fresh.snapshot()) }
            publish(fresh)
        }
        if (editors.none { (id, e) -> savedRevision[id] != e.revision }) _state.update { if (it.save == SaveStatus.ERROR) it else it.copy(save = SaveStatus.SAVED) }
        return merged
    }

    /** Pages added or reordered on another device. */
    private fun refreshMeta() {
        viewModelScope.launch {
            val meta = withContext(folio.ioWriter) { repo.library().notebooks.firstOrNull { it.id == notebookId } } ?: return@launch
            _state.update { s ->
                val at = s.page?.let { meta.pageIds.indexOf(it.id) } ?: -1
                s.copy(meta = meta, pageIndex = if (at >= 0) at else s.pageIndex)
            }
        }
    }

    override fun onCleared() {
        if (folio.sync.liveEditor === live) folio.sync.liveEditor = null
        assets.release()
        flush()
        super.onCleared()
    }

    companion object {
        const val SAVE_DEBOUNCE_MS = 800L
        const val DUPLICATE_OFFSET = 24f
    }
}
