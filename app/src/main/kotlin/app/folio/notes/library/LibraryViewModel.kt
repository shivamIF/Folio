package app.folio.notes.library

import android.app.Application
import android.content.Context
import app.folio.core.model.PdfBackground
import app.folio.core.model.Page
import app.folio.core.storage.InvalidImportException
import app.folio.notes.pdf.PdfFile
import app.folio.notes.pdf.NotebookPdfExporter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.folio.core.model.Cover
import app.folio.core.model.Folder
import app.folio.core.model.FolderIcon
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

/** A short message for the snackbar, optionally with an Undo action. */
data class LibraryMessage(val text: String, val undo: (() -> Unit)? = null)

data class LibraryUiState(
    val index: LibraryIndex = LibraryIndex(),
    val query: LibraryQuery = LibraryQuery(),
    val viewMode: ViewMode = ViewMode.GRID,
    val sidebarExpanded: Boolean = true,
    val searching: Boolean = false,
    /** Non-null while multi-select is on: the selected item keys. */
    val selection: Set<String>? = null,
    val loading: Boolean = true,
    val defaults: NotebookDefaults = NotebookDefaults(),
    val importing: Boolean = false,
    val message: LibraryMessage? = null,
) {
    val items: List<LibraryItem> by lazy { query.items(index) }
    val path: List<Folder> by lazy { index.pathTo(query.folderId) }
    val currentFolder: Folder? get() = path.lastOrNull()
    val selecting: Boolean get() = selection != null
    val trashCount: Int by lazy { LibraryQuery(section = Section.TRASH).items(index).size }

    /** Where new notebooks, folders and imports go: the open folder, or the top level outside Documents. */
    val targetFolderId: String? get() = query.folderId.takeIf { query.section == Section.DOCUMENTS && !searching }
}

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val folio = app as FolioApp
    private val repo = folio.repository
    private val defaultsStore = NotebookDefaultsStore(app)
    private val prefs = LibraryPrefs(app)
    private val _state = MutableStateFlow(prefs.load(LibraryUiState(defaults = defaultsStore.load())))
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    init {
        // Trash keeps notebooks for 30 days.
        io { repo.purgeTrash(System.currentTimeMillis() - TRASH_DAYS * 24L * 60 * 60 * 1000) }
        // Notebooks that arrive (or change) through Drive sync show up without reopening the app.
        viewModelScope.launch { folio.sync.changes.collect { refresh() } }
    }

    fun refresh() = io { /* re-read */ }

    // Navigation.
    fun showSection(section: Section) = _state.update {
        it.copy(query = it.query.copy(section = section, folderId = null, search = ""), searching = false, selection = null)
    }
    fun openFolder(id: String?) = _state.update {
        it.copy(query = it.query.copy(section = Section.DOCUMENTS, folderId = id, search = ""), searching = false, selection = null)
    }
    fun goUp() = openFolder(_state.value.currentFolder?.parentId)

    // View options (remembered across launches).
    fun setFilter(f: ItemFilter) = savePrefs { it.copy(query = it.query.copy(filter = f)) }
    fun setSort(order: SortOrder) = savePrefs { it.copy(query = it.query.copy(sort = order, descending = order.descendingByDefault)) }
    fun setDescending(d: Boolean) = savePrefs { it.copy(query = it.query.copy(descending = d)) }
    fun setViewMode(m: ViewMode) = savePrefs { it.copy(viewMode = m) }
    fun toggleSidebar() = savePrefs { it.copy(sidebarExpanded = !it.sidebarExpanded) }
    fun setSearching(on: Boolean) = _state.update { it.copy(searching = on, query = if (on) it.query else it.query.copy(search = "")) }
    fun setSearch(text: String) = _state.update { it.copy(query = it.query.copy(search = text)) }

    // Multi-select.
    fun startSelecting(first: String? = null) = _state.update { it.copy(selection = setOfNotNull(first)) }
    fun stopSelecting() = _state.update { it.copy(selection = null) }
    fun toggleSelected(key: String) = _state.update { s ->
        val cur = s.selection ?: emptySet()
        s.copy(selection = if (key in cur) cur - key else cur + key)
    }
    fun selectAll() = _state.update { s -> s.copy(selection = s.items.map { it.key }.toSet()) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    // Folders.
    fun createFolder(name: String, color: Long, icon: FolderIcon) = io {
        val f = repo.createFolder(name, _state.value.targetFolderId, color)
        if (icon != FolderIcon.NONE) repo.updateFolder(f.id, icon = icon)
    }
    fun customizeFolder(id: String, name: String, color: Long, icon: FolderIcon) = io { repo.updateFolder(id, name, color, icon) }

    // Items of either kind, by key ("n:<id>" or "f:<id>").
    fun setFavorite(keys: Collection<String>, favorite: Boolean) = io { split(keys) { n, f -> repo.setFavorite(n, f, favorite) } }
    fun moveTo(keys: Collection<String>, folderId: String?) = io({ say(movedText(keys.size, folderId)); stopSelecting() }) {
        split(keys) { n, f -> repo.moveItems(n, f, folderId) }
    }
    fun duplicate(id: String) = io({ say("Duplicated as \"${it.title}\"") }) { repo.duplicateNotebook(id) }
    fun trash(keys: Collection<String>) {
        val copy = keys.toList()
        io({
            stopSelecting()
            _state.update { it.copy(message = LibraryMessage(countText(copy.size) + " moved to Trash") { restore(copy, quiet = true) }) }
        }) { split(copy) { n, f -> n.forEach(repo::trashNotebook); f.forEach(repo::trashFolder) } }
    }
    fun restore(keys: Collection<String>, quiet: Boolean = false) = io({ if (!quiet) say(countText(keys.size) + " restored"); stopSelecting() }) {
        // Folders first, so a notebook restored with its folder can go back inside it.
        split(keys) { n, f -> f.forEach(repo::restoreFolder); n.forEach(repo::restoreNotebook) }
    }
    fun deleteForever(keys: Collection<String>) = io({ stopSelecting() }) {
        split(keys) { n, f -> n.forEach(repo::deleteNotebook); f.forEach(repo::deleteFolderForever) }
    }
    fun emptyTrash() = io({ stopSelecting() }) { repo.purgeTrash(Long.MAX_VALUE) }

    private fun countText(n: Int) = if (n == 1) "1 item" else "$n items"
    private fun movedText(n: Int, folderId: String?): String {
        val name = folderId?.let { id -> _state.value.index.folders.firstOrNull { it.id == id }?.name } ?: Section.DOCUMENTS.label
        return "Moved ${countText(n)} to $name"
    }
    private fun say(text: String) = _state.update { it.copy(message = LibraryMessage(text)) }

    private inline fun split(keys: Collection<String>, block: (List<String>, List<String>) -> Unit) {
        block(keys.filter { it.startsWith("n:") }.map { it.substring(2) }, keys.filter { it.startsWith("f:") }.map { it.substring(2) })
    }

    private fun savePrefs(change: (LibraryUiState) -> LibraryUiState) {
        _state.update(change)
        prefs.save(_state.value)
    }

    fun saveDefaults(d: NotebookDefaults) {
        defaultsStore.save(d)
        _state.update { it.copy(defaults = d) }
    }

    fun createNotebook(title: String, cover: Cover, paper: PaperTemplate, onCreated: (String) -> Unit = {}) {
        val metrics = getApplication<Application>().resources.displayMetrics
        val (w, h) = _state.value.defaults.pageSize(metrics.widthPixels, metrics.heightPixels)
        io({ onCreated(it.id) }) { repo.createNotebook(title, _state.value.targetFolderId, cover, paper, w, h) }
    }

    /**
     * Imports a document from a content URI as a new notebook. The type is
     * detected from the file's bytes, not its name:
     * - PDF: one page per PDF page, the PDF shown under the ink
     * - GoodNotes 6 (.goodnotes): pages, order, PDF backgrounds, ink and highlighter
     * Everything is copied and validated before anything appears in the library;
     * content that can't be brought over is reported afterwards.
     */
    fun importDocument(uri: android.net.Uri, onCreated: (String) -> Unit = {}) {
        val app = getApplication<Application>()
        _state.update { it.copy(importing = true) }
        viewModelScope.launch {
            try {
                val (meta, warnings) = withContext(folio.ioWriter) {
                    val name = displayName(uri) ?: "Imported notebook"
                    val tmp = java.io.File(app.cacheDir, "import-${System.nanoTime()}")
                    try {
                        val input = app.contentResolver.openInputStream(uri) ?: throw InvalidImportException("Couldn't open the file")
                        input.use { copyCapped(it, tmp, MAX_IMPORT_BYTES) }
                        val head = tmp.inputStream().use { s -> ByteArray(1024).let { b -> b.copyOf(s.read(b).coerceAtLeast(0)) } }
                        when {
                            String(head, Charsets.ISO_8859_1).contains("%PDF-") -> importPdfFile(tmp, name.substringBeforeLast('.')) to emptyList()
                            head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> importArchive(tmp, name)
                            else -> throw InvalidImportException("Folio can open PDF, GoodNotes and Samsung Notes files. This file is none of these.")
                        }
                    } finally {
                        tmp.delete()
                    }
                }
                _state.update {
                    it.copy(
                        index = withContext(folio.ioWriter) { repo.library() }, importing = false,
                        message = warnings.takeIf { w -> w.isNotEmpty() }?.joinToString(" ", prefix = "Imported. ")?.let(::LibraryMessage),
                    )
                }
                onCreated(meta.id)
            } catch (e: Exception) {
                val msg = when (e) {
                    is SecurityException -> "This PDF is password-protected. Remove the password and try again."
                    is InvalidImportException -> e.message
                    is app.folio.core.interop.FormatException -> "This file couldn't be read: ${e.message}"
                    else -> "This file couldn't be imported (${e.javaClass.simpleName})."
                }
                _state.update { it.copy(importing = false, message = LibraryMessage(msg ?: "This file couldn't be imported.")) }
            }
        }
    }

    /** Same as [importDocument]; kept for callers that only deal in PDFs. */
    fun importPdf(uri: android.net.Uri, onCreated: (String) -> Unit = {}) = importDocument(uri, onCreated)

    private fun copyCapped(input: java.io.InputStream, out: java.io.File, cap: Long) {
        out.outputStream().use { o ->
            val buf = ByteArray(64 * 1024); var total = 0L
            while (true) {
                val n = input.read(buf); if (n < 0) break
                total += n
                if (total > cap) throw InvalidImportException("File is larger than ${cap / (1024 * 1024)} MB")
                o.write(buf, 0, n)
            }
        }
    }

    private fun importPdfFile(file: java.io.File, title: String): NotebookMeta {
        val staged = file.inputStream().use { repo.stagePdf(it) }
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
            return repo.createNotebookFromPdf(title.ifBlank { "Imported PDF" }, _state.value.targetFolderId, Cover(color = 0xFF293241), staged, pages)
        } catch (e: Exception) {
            repo.discardStaged(staged)
            throw e
        }
    }

    /** ZIP-based notebook formats, recognised by their contents. */
    private fun importArchive(file: java.io.File, name: String): Pair<NotebookMeta, List<String>> {
        val names = app.folio.core.interop.SafeZip(file).use { it.names }
        return when {
            "index.events.pb" in names -> importGoodNotes(file)
            names.any { it.endsWith("pageIdInfo.dat") } -> importSamsungNotes(file, name)
            else -> throw InvalidImportException("\"$name\" is an archive Folio doesn't recognise as a notebook.")
        }
    }

    private fun importSamsungNotes(file: java.io.File, name: String): Pair<NotebookMeta, List<String>> =
        app.folio.core.interop.SamsungNotesImporter(file).use { sn ->
            createFromImport(sn.read(name), sn::openAttachment, Cover(color = 0xFF3D5A80))
        }

    private fun importGoodNotes(file: java.io.File): Pair<NotebookMeta, List<String>> =
        app.folio.core.interop.GoodNotesImporter(file).use { gn ->
            createFromImport(gn.read(), gn::openAttachment, Cover(color = 0xFF2A9D8F))
        }

    /**
     * Stages an imported notebook's PDFs and pictures, then creates it with strokes, PDF
     * backgrounds, text boxes and pictures. Nothing is left behind if anything fails.
     */
    private fun createFromImport(
        nb: app.folio.core.interop.ImportedNotebook,
        open: (String) -> java.io.InputStream,
        cover: Cover,
    ): Pair<NotebookMeta, List<String>> {
        val pdfs = LinkedHashMap<String, app.folio.core.storage.StagedFile>()
        val pictures = LinkedHashMap<String, app.folio.core.storage.StagedFile>()
        val warnings = ArrayList(nb.warnings)
        try {
            for (key in nb.attachmentKeys) pdfs[key] = open(key).use { repo.stagePdf(it) }
            var unreadable = 0
            for (key in nb.pages.flatMap { p -> p.images.map { it.key } }.distinct()) {
                runCatching { open(key).use { repo.stageImage(it) } }.onSuccess { pictures[key] = it }.onFailure { unreadable++ }
            }
            if (unreadable > 0) warnings += "$unreadable picture(s) couldn't be read and were left out."
            val pages = nb.pages.map { p ->
                Page(
                    width = p.width, height = p.height, paper = PaperTemplate.BLANK, strokes = p.strokes,
                    pdf = p.pdfKey?.let { k -> pdfs[k]?.let { PdfBackground(it.id, p.pdfPageIndex) } },
                    texts = p.texts,
                    images = p.images.mapNotNull { img ->
                        pictures[img.key]?.let { st -> app.folio.core.model.PageImage(attachmentId = st.id, x = img.x, y = img.y, width = img.width, height = img.height) }
                    },
                )
            }
            val meta = repo.createNotebookFromImport(
                nb.title, _state.value.targetFolderId, cover, (pdfs.values + pictures.values).toList(), pages,
                fromPdf = pages.any { it.pdf != null },
            )
            return meta to warnings
        } catch (e: Exception) {
            (pdfs.values + pictures.values).forEach { repo.discardStaged(it) }
            throw e
        }
    }

    private fun displayName(uri: android.net.Uri): String? = runCatching {
        getApplication<Application>().contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    fun updateNotebook(meta: NotebookMeta) = io { repo.updateNotebook(meta) }

    companion object {
        const val TRASH_DAYS = 30
        const val MAX_IMPORT_BYTES = 500L * 1024 * 1024
    }

    /** Runs a repository call on the writer thread, then reloads the index. */
    private fun <T> io(after: (T) -> Unit = {}, block: () -> T) {
        viewModelScope.launch {
            try {
                val (result, index) = withContext(folio.ioWriter) { block() to repo.library() }
                _state.update { it.copy(index = index, loading = false) }
                after(result)
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, message = LibraryMessage(e.message ?: "Something went wrong")) }
            }
        }
    }
}

/** Remembers how the library was last arranged: sidebar, grid or list, sort and filter. */
private class LibraryPrefs(context: Context) {
    private val sp = context.getSharedPreferences("library_view", Context.MODE_PRIVATE)

    fun load(base: LibraryUiState): LibraryUiState {
        val q = base.query
        val sort = enumOr(sp.getString("sort", null), q.sort)
        return base.copy(
            sidebarExpanded = sp.getBoolean("sidebar", base.sidebarExpanded),
            viewMode = enumOr(sp.getString("view", null), base.viewMode),
            query = q.copy(
                sort = sort,
                descending = sp.getBoolean("descending", sort.descendingByDefault),
                filter = enumOr(sp.getString("filter", null), q.filter),
            ),
        )
    }

    fun save(s: LibraryUiState) {
        sp.edit()
            .putBoolean("sidebar", s.sidebarExpanded)
            .putString("view", s.viewMode.name)
            .putString("sort", s.query.sort.name)
            .putBoolean("descending", s.query.descending)
            .putString("filter", s.query.filter.name)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: fallback
}
