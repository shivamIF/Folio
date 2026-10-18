package app.folio.notes.library

import android.app.Application
import app.folio.core.model.PdfBackground
import app.folio.core.model.Page
import app.folio.core.storage.InvalidImportException
import app.folio.notes.pdf.PdfFile
import app.folio.notes.pdf.NotebookPdfExporter
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
    val importing: Boolean = false,
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

    /**
     * Imports a PDF from a content URI as a new notebook (one page per PDF page,
     * the PDF shown under the ink). The file is staged and validated first, so a
     * bad file never appears in the library.
     */
    fun importPdf(uri: android.net.Uri, onCreated: (String) -> Unit = {}) {
        val app = getApplication<Application>()
        _state.update { it.copy(importing = true) }
        viewModelScope.launch {
            try {
                val meta = withContext(folio.ioWriter) {
                    val name = displayName(uri)?.removeSuffix(".pdf")?.removeSuffix(".PDF") ?: "Imported PDF"
                    val input = app.contentResolver.openInputStream(uri) ?: throw InvalidImportException("Couldn't open the file")
                    val staged = input.use { repo.stagePdf(it) }
                    try {
                        val pages = PdfFile(staged.file).use { pdf ->
                            val n = pdf.pageCount
                            if (n !in 1..PdfFile.MAX_PAGES) throw InvalidImportException("PDF has $n pages; up to ${PdfFile.MAX_PAGES} are supported")
                            (0 until n).map { i ->
                                val (w, h) = pdf.pageSize(i)
                                // Keep the PDF's physical page size (points → page units), so export round-trips exactly.
                                Page(
                                    width = (w / NotebookPdfExporter.POINTS_PER_UNIT).coerceIn(100f, 10_000f),
                                    height = (h / NotebookPdfExporter.POINTS_PER_UNIT).coerceIn(100f, 10_000f),
                                    paper = PaperTemplate.BLANK,
                                    pdf = PdfBackground(staged.id, i),
                                )
                            }
                        }
                        repo.createNotebookFromPdf(name, _state.value.folderId, Cover(color = 0xFF293241), staged, pages)
                    } catch (e: Exception) {
                        repo.discardStaged(staged)
                        throw e
                    }
                }
                _state.update { it.copy(index = withContext(folio.ioWriter) { repo.library() }, importing = false) }
                onCreated(meta.id)
            } catch (e: Exception) {
                val msg = when (e) {
                    is SecurityException -> "This PDF is password-protected. Remove the password and try again."
                    is InvalidImportException -> e.message
                    else -> "This PDF couldn't be read (${e.javaClass.simpleName})."
                }
                _state.update { it.copy(importing = false, message = msg) }
            }
        }
    }

    private fun displayName(uri: android.net.Uri): String? = runCatching {
        getApplication<Application>().contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

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
