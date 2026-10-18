package app.folio.notes.editor

import android.app.Application
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

enum class SaveStatus { SAVED, PENDING, SAVING, ERROR }

data class EditorUiState(
    val meta: NotebookMeta? = null,
    val pageIndex: Int = 0,
    val page: Page? = null,
    val strokes: List<Stroke> = emptyList(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
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
                _state.update { it.copy(pageIndex = index, page = editor.snapshot()) }
                publish(editor)
            } catch (e: Exception) {
                _state.update { it.copy(error = "Page couldn't be loaded: ${e.message}") }
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
        _state.update { it.copy(strokes = ed.currentStrokes.toList(), canUndo = ed.canUndo, canRedo = ed.canRedo) }
    }

    fun updateTools(transform: (ToolState) -> ToolState) {
        _state.update { it.copy(tools = transform(it.tools)) }
        prefs.save(_state.value.tools)
    }

    fun dismissError() = _state.update { it.copy(error = null) }

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
    }
}
