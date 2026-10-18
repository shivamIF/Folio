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

data class ExportResult(val uri: android.net.Uri, val fileName: String)

enum class SaveStatus { SAVED, PENDING, SAVING, ERROR }

data class EditorUiState(
    val meta: NotebookMeta? = null,
    val pageIndex: Int = 0,
    val page: Page? = null,
    val strokes: List<Stroke> = emptyList(),
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
    /** Only the debounce timer is ever cancelled; a write that has started always runs to completion. */
    private var debounceJob: Job? = null
    private var retryAttempt = 0

    private val _state = MutableStateFlow(EditorUiState(tools = prefs.load()))
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    init {
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
        val meta = _state.value.meta ?: return
        if (index !in meta.pageIds.indices) return
        flush()
        val pageId = meta.pageIds[index]
        viewModelScope.launch {
            try {
                val editor = editors[pageId] ?: withContext(folio.ioWriter) { repo.loadPage(notebookId, pageId) }
                    .let { PageEditor(it) }.also { editors[pageId] = it; savedRevision[pageId] = it.revision }
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

    fun undo() { currentEditor()?.let { if (it.undo()) changed(it) } }
    fun redo() { currentEditor()?.let { if (it.redo()) changed(it) } }

    // ---- lasso selection ----

    fun onLassoSelected(ids: Set<String>) = _state.update { it.copy(selection = ids) }
    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    private fun selectedStrokes(): List<Stroke> {
        val ids = _state.value.selection
        return currentEditor()?.currentStrokes?.filter { it.id in ids }.orEmpty()
    }

    fun transformSelection(ids: Set<String>, t: app.folio.core.ink.SelectionTransform) {
        val before = currentEditor()?.currentStrokes?.filter { it.id in ids }.orEmpty()
        if (before.isEmpty() || t.isIdentity) return
        edit(PageEdit.ReplaceStrokes(before, before.map(t::apply)))
    }

    fun deleteSelection() {
        val ed = currentEditor() ?: return
        val ids = _state.value.selection
        if (ids.isEmpty()) return
        _state.update { it.copy(selection = emptySet()) }
        edit(PageEdit.RemoveStrokes.of(ed.currentStrokes, ids))
    }

    /** Copies the selection slightly offset and selects the copies, as most note apps do. */
    fun duplicateSelection() {
        val src = selectedStrokes()
        if (src.isEmpty()) return
        val copies = src.map { it.transformed(1f, 0f, 0f, DUPLICATE_OFFSET, DUPLICATE_OFFSET, newId = true) }
        edit(PageEdit.AddStrokes(copies))
        _state.update { it.copy(selection = copies.mapTo(LinkedHashSet()) { c -> c.id }) }
    }

    fun recolorSelection(color: Long) {
        val src = selectedStrokes()
        if (src.isEmpty()) return
        edit(PageEdit.ReplaceStrokes(src, src.map { it.recolored(color) }))
    }

    private fun edit(e: PageEdit) {
        val ed = currentEditor() ?: return
        ed.perform(e)
        changed(ed)
    }

    private fun changed(ed: PageEditor) {
        publish(ed)
        scheduleSave()
    }

    private fun publish(ed: PageEditor) {
        val strokes = ed.currentStrokes.toList()
        val live = strokes.mapTo(HashSet()) { it.id }
        // Undo/redo can remove selected strokes; never keep a selection of ghosts.
        _state.update { it.copy(strokes = strokes, canUndo = ed.canUndo, canRedo = ed.canRedo, selection = it.selection.filterTo(LinkedHashSet()) { id -> id in live }) }
    }

    fun updateTools(transform: (ToolState) -> ToolState) {
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
                savedRevision[id] = rev
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

    override fun onCleared() {
        flush()
        super.onCleared()
    }

    companion object {
        const val SAVE_DEBOUNCE_MS = 800L
        const val DUPLICATE_OFFSET = 24f
    }
}
