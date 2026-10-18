package app.folio.core.storage

import app.folio.core.model.CorruptDocumentException
import app.folio.core.model.Cover
import app.folio.core.model.DocumentCodec
import app.folio.core.model.Folder
import app.folio.core.model.LibraryIndex
import app.folio.core.model.NotebookMeta
import app.folio.core.model.Page
import app.folio.core.model.PaperTemplate
import java.io.File

/** Local persistence contract; the sync engine will sit behind the same interface later. */
interface DocumentRepository {
    fun library(): LibraryIndex
    fun createFolder(name: String, parentId: String?, color: Long): Folder
    fun renameFolder(id: String, name: String)
    fun deleteFolder(id: String)
    fun createNotebook(
        title: String, folderId: String?, cover: Cover, paper: PaperTemplate,
        pageWidth: Float = NotebookMeta.PAGE_WIDTH, pageHeight: Float = NotebookMeta.PAGE_HEIGHT,
    ): NotebookMeta
    fun updateNotebook(meta: NotebookMeta)
    fun deleteNotebook(id: String)
    fun addPage(notebookId: String, afterIndex: Int): NotebookMeta
    fun loadPage(notebookId: String, pageId: String): Page
    fun savePage(notebookId: String, page: Page)
}

/**
 * File-system repository. Layout under [root]:
 * ```
 * library.json                       folders + notebook metadata
 * notebooks/<notebookId>/<pageId>.page.json
 * ```
 * Every write goes through [AtomicFile]. Thread-safe: all calls are serialised
 * on one lock, which is fine at notebook scale and keeps the index consistent.
 */
class FileNotebookStore(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
) : DocumentRepository {
    private val lock = Any()
    private val indexFile = AtomicFile(File(root, "library.json"))
    private var cached: LibraryIndex? = null

    /** Set when the index was recovered from backup or reset; the UI can tell the user. */
    @Volatile var lastRecovery: String? = null
        private set

    override fun library(): LibraryIndex = synchronized(lock) { loadIndex() }

    override fun createFolder(name: String, parentId: String?, color: Long): Folder = synchronized(lock) {
        val idx = loadIndex()
        require(parentId == null || idx.folders.any { it.id == parentId }) { "parent folder not found" }
        val folder = Folder(name = name.trim().ifEmpty { "Untitled folder" }, parentId = parentId, color = color, createdAt = clock())
        saveIndex(idx.copy(folders = idx.folders + folder))
        folder
    }

    override fun renameFolder(id: String, name: String) = synchronized(lock) {
        val idx = loadIndex()
        saveIndex(idx.copy(folders = idx.folders.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }, modifiedAt = clock()) else it }))
    }

    /** Deletes a folder and its subfolders; notebooks inside move to the parent so nothing is lost. */
    override fun deleteFolder(id: String) = synchronized(lock) {
        val idx = loadIndex()
        val target = idx.folders.firstOrNull { it.id == id } ?: return@synchronized
        val doomed = HashSet<String>().apply {
            add(id)
            var grew = true
            while (grew) { grew = idx.folders.filter { it.parentId in this }.map { it.id }.let { addAll(it) } }
        }
        saveIndex(
            idx.copy(
                folders = idx.folders.filter { it.id !in doomed },
                notebooks = idx.notebooks.map { if (it.folderId in doomed) it.copy(folderId = target.parentId) else it },
            ),
        )
    }

    override fun createNotebook(
        title: String, folderId: String?, cover: Cover, paper: PaperTemplate, pageWidth: Float, pageHeight: Float,
    ): NotebookMeta = synchronized(lock) {
        require(pageWidth in 100f..10_000f && pageHeight in 100f..10_000f) { "unsupported page size" }
        val idx = loadIndex()
        val now = clock()
        val first = Page(width = pageWidth, height = pageHeight, paper = paper)
        val meta = NotebookMeta(
            title = title.trim().ifEmpty { "Untitled notebook" }, folderId = folderId, cover = cover, paper = paper,
            pageWidth = pageWidth, pageHeight = pageHeight,
            pageIds = listOf(first.id), createdAt = now,
        )
        writePage(meta.id, first)
        saveIndex(idx.copy(notebooks = idx.notebooks + meta))
        meta
    }

    override fun updateNotebook(meta: NotebookMeta) = synchronized(lock) {
        val idx = loadIndex()
        require(idx.notebooks.any { it.id == meta.id }) { "notebook not found" }
        saveIndex(idx.copy(notebooks = idx.notebooks.map { if (it.id == meta.id) meta.copy(modifiedAt = clock()) else it }))
    }

    override fun deleteNotebook(id: String) = synchronized(lock) {
        val idx = loadIndex()
        saveIndex(idx.copy(notebooks = idx.notebooks.filter { it.id != id }))
        notebookDir(id).deleteRecursively()
        Unit
    }

    override fun addPage(notebookId: String, afterIndex: Int): NotebookMeta = synchronized(lock) {
        val idx = loadIndex()
        val meta = idx.notebooks.firstOrNull { it.id == notebookId } ?: error("notebook not found")
        val page = Page(width = meta.pageWidth, height = meta.pageHeight, paper = meta.paper)
        writePage(notebookId, page)
        val ids = meta.pageIds.toMutableList().apply { add((afterIndex + 1).coerceIn(0, size), page.id) }
        val updated = meta.copy(pageIds = ids, modifiedAt = clock())
        saveIndex(idx.copy(notebooks = idx.notebooks.map { if (it.id == notebookId) updated else it }))
        updated
    }

    /**
     * Loads a page, falling back to the backup copy if the main file is corrupt.
     * A page that is listed but missing on disk comes back blank rather than crashing the editor.
     */
    override fun loadPage(notebookId: String, pageId: String): Page = synchronized(lock) {
        val f = pageFile(notebookId, pageId)
        readWithFallback(f, DocumentCodec::decodePage) ?: Page(id = pageId)
    }

    override fun savePage(notebookId: String, page: Page) = synchronized(lock) {
        writePage(notebookId, page)
        val idx = loadIndex()
        saveIndex(idx.copy(notebooks = idx.notebooks.map { if (it.id == notebookId) it.copy(modifiedAt = clock()) else it }))
    }

    private fun writePage(notebookId: String, page: Page) {
        require(SAFE_ID.matches(notebookId) && SAFE_ID.matches(page.id)) { "invalid id" }
        pageFile(notebookId, page.id).write(DocumentCodec.encodePage(page).toByteArray())
    }

    private fun loadIndex(): LibraryIndex {
        cached?.let { return it }
        val idx = readWithFallback(indexFile, DocumentCodec::decodeLibrary) ?: LibraryIndex()
        cached = idx
        return idx
    }

    private fun saveIndex(idx: LibraryIndex) {
        indexFile.write(DocumentCodec.encodeLibrary(idx).toByteArray())
        cached = idx
    }

    private fun <T> readWithFallback(f: AtomicFile, decode: (String) -> T): T? {
        if (!f.exists()) return null
        val main = f.readMain()
        if (main != null) {
            try { return decode(main.decodeToString()) } catch (e: CorruptDocumentException) {
                lastRecovery = "${f.file.name} was damaged; restored the previous version"
            }
        }
        val bak = f.readBackup() ?: run {
            if (main != null) throw CorruptDocumentException("${f.file.name} is damaged and has no backup")
            return null
        }
        return decode(bak.decodeToString())
    }

    private fun notebookDir(id: String): File {
        require(SAFE_ID.matches(id)) { "invalid id" }
        return File(File(root, "notebooks"), id)
    }

    private fun pageFile(notebookId: String, pageId: String): AtomicFile {
        require(SAFE_ID.matches(pageId)) { "invalid id" }
        return AtomicFile(File(notebookDir(notebookId), "$pageId.page.json"))
    }

    private companion object {
        /** Ids become file names; restrict them so a crafted index can't escape [root]. */
        val SAFE_ID = Regex("[A-Za-z0-9-]{1,64}")
    }
}
