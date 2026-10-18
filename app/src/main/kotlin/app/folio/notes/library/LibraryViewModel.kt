package app.folio.notes.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.folio.core.model.Cover
import app.folio.core.model.Folder
import app.folio.core.model.LibraryIndex
import app.folio.core.model.NotebookMeta
import app.folio.core.model.PaperTemplate
import app.folio.notes.FolioApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SortOrder { RECENT, TITLE, CREATED }

data class LibraryUiState(
    val index: LibraryIndex = LibraryIndex(),
    val folderId: String? = null,
    val sort: SortOrder = SortOrder.RECENT,
    val query: String = "",
    val loading: Boolean = true,
    val defaults: NotebookDefaults = NotebookDefaults(),
    val message: String? = null,
) {
    val currentFolder: Folder? get() = index.folders.firstOrNull { it.id == folderId }
    val subfolders: List<Folder> get() = if (query.isNotBlank()) emptyList() else index.folders.filter { it.parentId == folderId }.sortedBy { it.name.lowercase() }
    val notebooks: List<NotebookMeta> get() {
        val base = if (query.isNotBlank()) index.notebooks.filter { it.title.contains(query.trim(), ignoreCase = true) }
        else index.notebooks.filter { it.folderId == folderId }
        return when (sort) {
            SortOrder.RECENT -> base.sortedByDescending { it.modifiedAt }
            SortOrder.TITLE -> base.sortedBy { it.title.lowercase() }
            SortOrder.CREATED -> base.sortedByDescending { it.createdAt }
        }.sortedByDescending { it.favorite }
    }
}

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val folio = app as FolioApp
    private val repo = folio.repository
    private val defaultsStore = NotebookDefaultsStore(app)
    private val _state = MutableStateFlow(LibraryUiState(defaults = defaultsStore.load()))
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() = io { /* re-read */ }

    fun openFolder(id: String?) = _state.update { it.copy(folderId = id, query = "") }
    fun goUp() = _state.update { s -> s.copy(folderId = s.currentFolder?.parentId) }
    fun setSort(order: SortOrder) = _state.update { it.copy(sort = order) }
    fun setQuery(q: String) = _state.update { it.copy(query = q) }
    fun clearMessage() = _state.update { it.copy(message = null) }

    fun createFolder(name: String, color: Long) = io { repo.createFolder(name, _state.value.folderId, color) }
    fun renameFolder(id: String, name: String) = io { repo.renameFolder(id, name) }
    fun deleteFolder(id: String) = io { repo.deleteFolder(id) }

    fun saveDefaults(d: NotebookDefaults) {
        defaultsStore.save(d)
        _state.update { it.copy(defaults = d) }
    }

    fun createNotebook(title: String, cover: Cover, paper: PaperTemplate, onCreated: (String) -> Unit = {}) {
        val metrics = getApplication<Application>().resources.displayMetrics
        val (w, h) = _state.value.defaults.pageSize(metrics.widthPixels, metrics.heightPixels)
        io({ onCreated(it.id) }) { repo.createNotebook(title, _state.value.folderId, cover, paper, w, h) }
    }

    fun updateNotebook(meta: NotebookMeta) = io { repo.updateNotebook(meta) }
    fun deleteNotebook(id: String) = io { repo.deleteNotebook(id) }

    /** Runs a repository call on the writer thread, then reloads the index. */
    private fun <T> io(after: (T) -> Unit = {}, block: () -> T) {
        viewModelScope.launch {
            try {
                val (result, index) = withContext(folio.ioWriter) { block() to repo.library() }
                _state.update { it.copy(index = index, loading = false) }
                after(result)
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, message = e.message ?: "Something went wrong") }
            }
        }
    }
}
