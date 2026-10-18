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

    /**
     * Copies an incoming file into a private staging area, enforcing [maxBytes]
     * and checking for a PDF header. Nothing is visible in the library yet.
     */
    fun stagePdf(source: java.io.InputStream, maxBytes: Long = MAX_PDF_BYTES): StagedFile

    /** Discards a staged file that failed validation. */
    fun discardStaged(staged: StagedFile)

    /** Creates a notebook whose pages are backed by the staged PDF, moving the file into the notebook. */
    fun createNotebookFromPdf(title: String, folderId: String?, cover: Cover, staged: StagedFile, pages: List<Page>): NotebookMeta

    /** The on-disk file of a notebook attachment (e.g. an imported PDF). */
    fun attachmentFile(notebookId: String, attachmentId: String): File
    fun loadPage(notebookId: String, pageId: String): Page
    fun savePage(notebookId: String, page: Page)
}

/** A file copied into staging, not yet part of any notebook. */
class StagedFile internal constructor(val id: String, val file: File)

class InvalidImportException(message: String) : Exception(message)

const val MAX_PDF_BYTES = 300L * 1024 * 1024

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

    override fun stagePdf(source: java.io.InputStream, maxBytes: Long): StagedFile {
        val id = app.folio.core.model.newId()
        val dir = File(root, "staging").apply { mkdirs() }
        val out = File(dir, "$id.pdf")
        try {
            var total = 0L
            val head = java.io.ByteArrayOutputStream()
            java.io.FileOutputStream(out).use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = source.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw InvalidImportException("File is larger than ${maxBytes / (1024 * 1024)} MB")
                    if (head.size() < 1024) head.write(buf, 0, minOf(n, 1024 - head.size()))
                    o.write(buf, 0, n)
                }
                o.fd.sync()
            }
            if (total == 0L) throw InvalidImportException("File is empty")
            // The PDF spec allows the header anywhere in the first 1024 bytes.
            if (!head.toByteArray().decodeToString().contains("%PDF-")) throw InvalidImportException("Not a PDF file")
            return StagedFile(id, out)
        } catch (e: Exception) {
            out.delete()
            throw e
        }
    }

    override fun discardStaged(staged: StagedFile) { staged.file.delete() }

    override fun createNotebookFromPdf(title: String, folderId: String?, cover: Cover, staged: StagedFile, pages: List<Page>): NotebookMeta = synchronized(lock) {
        require(pages.isNotEmpty()) { "a PDF notebook needs at least one page" }
        require(pages.all { it.pdf?.attachmentId == staged.id }) { "pages must reference the staged PDF" }
        require(staged.file.exists()) { "staged file is gone" }
        val idx = loadIndex()
        val now = clock()
        val first = pages.first()
        val meta = NotebookMeta(
            title = title.trim().ifEmpty { "Imported PDF" }, folderId = folderId, cover = cover, paper = PaperTemplate.BLANK,
            pageWidth = first.width, pageHeight = first.height, pageIds = pages.map { it.id }, createdAt = now,
        )
        val target = attachmentFile(meta.id, staged.id)
        target.parentFile.mkdirs()
        try {
            java.nio.file.Files.move(staged.file.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(staged.file.toPath(), target.toPath())
        }
        try {
            pages.forEach { writePage(meta.id, it) }
            saveIndex(idx.copy(notebooks = idx.notebooks + meta))
        } catch (e: Exception) {
            notebookDir(meta.id).deleteRecursively() // never leave a half-imported notebook behind
            throw e
        }
        meta
    }

    override fun attachmentFile(notebookId: String, attachmentId: String): File {
        require(SAFE_ID.matches(attachmentId)) { "invalid id" }
        return File(File(notebookDir(notebookId), "attachments"), "$attachmentId.pdf")
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
