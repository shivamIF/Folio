package app.folio.core.storage

import app.folio.core.model.CorruptDocumentException
import app.folio.core.model.Cover
import app.folio.core.model.DocumentCodec
import app.folio.core.model.Folder
import app.folio.core.model.newId
import app.folio.core.model.FolderIcon
import app.folio.core.model.LibraryIndex
import app.folio.core.model.NotebookMeta
import app.folio.core.model.Page
import app.folio.core.model.PageHeader
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
    /** Permanently deletes a notebook and its files. Prefer [trashNotebook] for user-facing deletes. */
    fun deleteNotebook(id: String)
    /** Moves a notebook to Trash; it disappears from the library but keeps all its files. */
    fun trashNotebook(id: String)
    /** Brings a notebook back from Trash (to the root if its folder no longer exists). */
    fun restoreNotebook(id: String)
    /**
     * Permanently deletes notebooks and folders that went to Trash before [cutoff] (epoch ms);
     * a folder takes its subfolders and their notebooks with it. Returns how many items were in Trash.
     */
    fun purgeTrash(cutoff: Long): Int

    /** Changes a folder's name, colour or icon; a null argument leaves that field as it is. */
    fun updateFolder(id: String, name: String? = null, color: Long? = null, icon: FolderIcon? = null): Folder
    /** Stars or unstars notebooks and folders (they show in Favorites). */
    fun setFavorite(notebookIds: Collection<String>, folderIds: Collection<String>, favorite: Boolean)
    /** Moves notebooks and folders into [targetFolderId] (null = top level). A folder can't go inside itself. */
    fun moveItems(notebookIds: Collection<String>, folderIds: Collection<String>, targetFolderId: String?)
    /** Copies a notebook with its pages and attachments; the copy is placed next to the original. */
    fun duplicateNotebook(id: String): NotebookMeta
    /** Moves a folder to Trash; everything inside it goes too and keeps its files. */
    fun trashFolder(id: String)
    /** Brings a folder back from Trash (to the top level if its parent is gone or still in Trash). */
    fun restoreFolder(id: String)
    /** Permanently deletes a folder, its subfolders and every notebook in them. */
    fun deleteFolderForever(id: String)
    fun addPage(notebookId: String, afterIndex: Int): NotebookMeta

    /**
     * Writes [page] and lists it at [index] (clamped), as one change to the notebook: new,
     * duplicated and restored (undone delete) pages. The page id must be new to the notebook.
     */
    fun insertPage(notebookId: String, index: Int, page: Page): NotebookMeta

    /** Unlists a page and deletes its file. A notebook always keeps at least one page. */
    fun deletePage(notebookId: String, pageId: String): NotebookMeta

    /** Moves a page so it ends up at [toIndex] (clamped). */
    fun movePage(notebookId: String, pageId: String, toIndex: Int): NotebookMeta

    /**
     * Changes whenever the page's saved file changes; null if it has none. Cheap (no read),
     * for caches of rendered pages.
     */
    fun pageStamp(notebookId: String, pageId: String): String?

    /**
     * The page's size and paper, read from the start of its file without decoding the strokes
     * (continuous scrolling lays the pages out with these). Null if there's no file or the
     * header can't be read; the notebook's default size then stands in until the page loads.
     */
    fun pageHeader(notebookId: String, pageId: String): PageHeader?

    /**
     * Copies an incoming file into a private staging area, enforcing [maxBytes]
     * and checking for a PDF header. Nothing is visible in the library yet.
     */
    fun stagePdf(source: java.io.InputStream, maxBytes: Long = MAX_PDF_BYTES): StagedFile

    /** Copies a picture (PNG, JPEG, WebP, GIF or HEIF, checked by content) into staging. */
    fun stageImage(source: java.io.InputStream, maxBytes: Long = MAX_IMAGE_BYTES): StagedFile

    /** Moves a staged picture into an existing notebook's attachments; returns its attachment id. */
    fun addImage(notebookId: String, staged: StagedFile): String

    /** Discards a staged file that failed validation. */
    fun discardStaged(staged: StagedFile)

    /** Creates a notebook whose pages are backed by the staged PDF, moving the file into the notebook. */
    fun createNotebookFromPdf(title: String, folderId: String?, cover: Cover, staged: StagedFile, pages: List<Page>): NotebookMeta

    /** Creates a notebook from an imported document whose pages may reference several staged PDFs. */
    fun createNotebookFromImport(title: String, folderId: String?, cover: Cover, staged: List<StagedFile>, pages: List<Page>, fromPdf: Boolean = false): NotebookMeta

    /** The on-disk file of a notebook's PDF attachment. */
    fun attachmentFile(notebookId: String, attachmentId: String): File

    /** The on-disk file of a notebook's picture attachment. */
    fun imageFile(notebookId: String, attachmentId: String): File
    fun loadPage(notebookId: String, pageId: String): Page
    fun savePage(notebookId: String, page: Page)
}

/** PNG, JPEG, WebP, GIF or HEIF/AVIF, by magic bytes. */
internal fun isImage(h: ByteArray): Boolean {
    fun at(i: Int, vararg b: Int) = h.size >= i + b.size && b.indices.all { (h[i + it].toInt() and 0xFF) == b[it] }
    return at(0, 0x89, 0x50, 0x4E, 0x47) || at(0, 0xFF, 0xD8, 0xFF) ||
        (at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50)) || at(0, 0x47, 0x49, 0x46, 0x38) ||
        at(4, 0x66, 0x74, 0x79, 0x70) // ISO-BMFF "ftyp" (HEIC/HEIF/AVIF)
}

/** A file copied into staging, not yet part of any notebook. */
class StagedFile internal constructor(val id: String, val file: File, val kind: AttachmentKind = AttachmentKind.PDF)

enum class AttachmentKind(val ext: String) { PDF("pdf"), IMAGE("img") }

class InvalidImportException(message: String) : Exception(message)

const val MAX_PDF_BYTES = 300L * 1024 * 1024
const val MAX_IMAGE_BYTES = 60L * 1024 * 1024

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
    internal val root: File,
    internal val clock: () -> Long = System::currentTimeMillis,
) : DocumentRepository {
    internal val lock = Any()
    private val indexFile = AtomicFile(File(root, "library.json"))
    private var cached: LibraryIndex? = null

    /** Set when the index was recovered from backup or reset; the UI can tell the user. */
    @Volatile var lastRecovery: String? = null
        private set

    /** Called after every change to the library (page saves included), e.g. to schedule a sync. Runs under the store's lock: keep it quick. */
    @Volatile var onChange: (() -> Unit)? = null

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

    override fun trashNotebook(id: String) = synchronized(lock) {
        val idx = loadIndex()
        require(idx.notebooks.any { it.id == id }) { "notebook not found" }
        saveIndex(idx.copy(notebooks = idx.notebooks.map { if (it.id == id) it.copy(deletedAt = clock()) else it }))
    }

    override fun restoreNotebook(id: String) = synchronized(lock) {
        val idx = loadIndex()
        // A folder that is itself in Trash doesn't count: the notebook would stay hidden.
        val folders = idx.folders.mapTo(HashSet()) { it.id } - idx.trashedFolderIds()
        saveIndex(
            idx.copy(
                notebooks = idx.notebooks.map {
                    if (it.id == id) it.copy(deletedAt = null, folderId = it.folderId?.takeIf { f -> f in folders }) else it
                },
            ),
        )
    }

    override fun purgeTrash(cutoff: Long): Int = synchronized(lock) {
        val idx = loadIndex()
        val trashed = idx.trashedFolderIds()
        val expiredFolders = idx.folders.filter { (it.deletedAt ?: Long.MAX_VALUE) < cutoff && it.parentId !in trashed }
        val doomedFolders = expiredFolders.flatMap { idx.descendantFolderIds(it.id) }.toSet()
        val doomed = idx.notebooks.filter { (it.deletedAt ?: Long.MAX_VALUE) < cutoff || it.folderId in doomedFolders }
        if (doomed.isEmpty() && doomedFolders.isEmpty()) return@synchronized 0
        removeForever(idx, doomed.mapTo(HashSet()) { it.id }, doomedFolders)
        doomed.count { it.deletedAt != null } + expiredFolders.size
    }

    override fun updateFolder(id: String, name: String?, color: Long?, icon: FolderIcon?): Folder = synchronized(lock) {
        val idx = loadIndex()
        val old = idx.folders.firstOrNull { it.id == id } ?: throw IllegalArgumentException("folder not found")
        val updated = old.copy(
            name = name?.trim()?.ifEmpty { null } ?: old.name,
            color = color ?: old.color,
            icon = icon ?: old.icon,
            modifiedAt = clock(),
        )
        saveIndex(idx.copy(folders = idx.folders.map { if (it.id == id) updated else it }))
        updated
    }

    override fun setFavorite(notebookIds: Collection<String>, folderIds: Collection<String>, favorite: Boolean) = synchronized(lock) {
        val idx = loadIndex()
        val now = clock()
        saveIndex(
            idx.copy(
                folders = idx.folders.map { if (it.id in folderIds) it.copy(favorite = favorite, modifiedAt = now) else it },
                notebooks = idx.notebooks.map { if (it.id in notebookIds) it.copy(favorite = favorite, modifiedAt = now) else it },
            ),
        )
    }

    override fun moveItems(notebookIds: Collection<String>, folderIds: Collection<String>, targetFolderId: String?) = synchronized(lock) {
        val idx = loadIndex()
        if (targetFolderId != null) {
            require(idx.folders.any { it.id == targetFolderId }) { "destination folder not found" }
            require(targetFolderId !in idx.trashedFolderIds()) { "destination folder is in Trash" }
            for (f in folderIds) require(targetFolderId !in idx.descendantFolderIds(f)) { "a folder can't be moved inside itself" }
        }
        val now = clock()
        saveIndex(
            idx.copy(
                folders = idx.folders.map {
                    when {
                        it.id in folderIds -> it.copy(parentId = targetFolderId, modifiedAt = now)
                        it.id == targetFolderId -> it.copy(modifiedAt = now)
                        else -> it
                    }
                },
                notebooks = idx.notebooks.map { if (it.id in notebookIds) it.copy(folderId = targetFolderId, modifiedAt = now) else it },
            ),
        )
    }

    override fun duplicateNotebook(id: String): NotebookMeta = synchronized(lock) {
        val idx = loadIndex()
        val src = idx.notebooks.firstOrNull { it.id == id } ?: throw IllegalArgumentException("notebook not found")
        val now = clock()
        val titles = idx.notebooks.mapTo(HashSet()) { it.title }
        var title = "${src.title} (copy)"
        var n = 2
        while (title in titles) title = "${src.title} (copy ${n++})"
        val copyId = newId()
        // One page at a time: an imported page can be large, and the heap is small on tablets.
        val pageIds = src.pageIds.map { pid ->
            val page = readWithFallback(pageFile(src.id, pid), DocumentCodec::decodePage)
                ?: Page(id = pid, width = src.pageWidth, height = src.pageHeight, paper = src.paper)
            val copy = page.copy(id = newId())
            writePage(copyId, copy)
            copy.id
        }
        // Pages refer to attachments by id, so the files keep their names.
        File(notebookDir(src.id), "attachments").takeIf { it.isDirectory }
            ?.copyRecursively(File(notebookDir(copyId), "attachments"), overwrite = true)
        val copy = src.copy(id = copyId, title = title, pageIds = pageIds, createdAt = now, modifiedAt = now, favorite = false, deletedAt = null)
        val list = idx.notebooks.toMutableList().apply { add(indexOfFirst { it.id == id } + 1, copy) }
        saveIndex(idx.copy(notebooks = list))
        copy
    }

    override fun trashFolder(id: String) = synchronized(lock) {
        val idx = loadIndex()
        require(idx.folders.any { it.id == id }) { "folder not found" }
        val now = clock()
        saveIndex(idx.copy(folders = idx.folders.map { if (it.id == id) it.copy(deletedAt = now, modifiedAt = now) else it }))
    }

    override fun restoreFolder(id: String) = synchronized(lock) {
        val idx = loadIndex()
        val now = clock()
        val restored = idx.copy(folders = idx.folders.map { if (it.id == id) it.copy(deletedAt = null, modifiedAt = now) else it })
        val home = restored.folders.mapTo(HashSet()) { it.id } - restored.trashedFolderIds() - restored.descendantFolderIds(id)
        saveIndex(restored.copy(folders = restored.folders.map { if (it.id == id && it.parentId !in home) it.copy(parentId = null) else it }))
    }

    override fun deleteFolderForever(id: String) = synchronized(lock) {
        val idx = loadIndex()
        val doomedFolders = idx.descendantFolderIds(id)
        removeForever(idx, idx.notebooks.filter { it.folderId in doomedFolders }.mapTo(HashSet()) { it.id }, doomedFolders)
    }

    /** Index first, then files: a crash in between leaves unreferenced files on disk, never a broken entry. */
    private fun removeForever(idx: LibraryIndex, notebookIds: Set<String>, folderIds: Set<String>) {
        saveIndex(idx.copy(folders = idx.folders.filter { it.id !in folderIds }, notebooks = idx.notebooks.filter { it.id !in notebookIds }))
        notebookIds.forEach { notebookDir(it).deleteRecursively() }
    }

    override fun deleteNotebook(id: String) = synchronized(lock) {
        val idx = loadIndex()
        saveIndex(idx.copy(notebooks = idx.notebooks.filter { it.id != id }))
        notebookDir(id).deleteRecursively()
        Unit
    }

    override fun addPage(notebookId: String, afterIndex: Int): NotebookMeta = synchronized(lock) {
        val meta = notebook(notebookId)
        insertPage(notebookId, afterIndex + 1, Page(width = meta.pageWidth, height = meta.pageHeight, paper = meta.paper))
    }

    override fun insertPage(notebookId: String, index: Int, page: Page): NotebookMeta = synchronized(lock) {
        val meta = notebook(notebookId)
        require(page.id !in meta.pageIds) { "page is already in this notebook" }
        // The file first: a crash in between leaves an unlisted file, never a listed page without one.
        writePage(notebookId, page)
        val ids = meta.pageIds.toMutableList().apply { add(index.coerceIn(0, size), page.id) }
        putNotebook(meta.copy(pageIds = ids, modifiedAt = clock()))
    }

    override fun deletePage(notebookId: String, pageId: String): NotebookMeta = synchronized(lock) {
        val meta = notebook(notebookId)
        require(pageId in meta.pageIds) { "page not found" }
        require(meta.pageIds.size > 1) { "a notebook needs at least one page" }
        // Unlist first: a crash in between leaves an orphan file, never a listed page without one.
        val updated = putNotebook(meta.copy(pageIds = meta.pageIds - pageId, modifiedAt = clock()))
        pageFile(notebookId, pageId).delete()
        updated
    }

    override fun movePage(notebookId: String, pageId: String, toIndex: Int): NotebookMeta = synchronized(lock) {
        val meta = notebook(notebookId)
        val from = meta.pageIds.indexOf(pageId)
        require(from >= 0) { "page not found" }
        val ids = app.folio.core.model.PageOps.moved(meta.pageIds, from, toIndex)
        if (ids === meta.pageIds) meta else putNotebook(meta.copy(pageIds = ids, modifiedAt = clock()))
    }

    override fun pageStamp(notebookId: String, pageId: String): String? = synchronized(lock) {
        val f = pageFile(notebookId, pageId).file
        if (f.exists()) "${f.lastModified()}-${f.length()}" else null
    }

    private fun notebook(id: String): NotebookMeta = loadIndex().notebooks.firstOrNull { it.id == id } ?: error("notebook not found")

    private fun putNotebook(meta: NotebookMeta): NotebookMeta {
        val idx = loadIndex()
        saveIndex(idx.copy(notebooks = idx.notebooks.map { if (it.id == meta.id) meta else it }))
        return meta
    }

    override fun stagePdf(source: java.io.InputStream, maxBytes: Long): StagedFile =
        stage(source, maxBytes, AttachmentKind.PDF) { head ->
            // The PDF spec allows the header anywhere in the first 1024 bytes.
            if (!head.decodeToString().contains("%PDF-")) throw InvalidImportException("Not a PDF file")
        }

    override fun stageImage(source: java.io.InputStream, maxBytes: Long): StagedFile =
        stage(source, maxBytes, AttachmentKind.IMAGE) { head ->
            if (!isImage(head)) throw InvalidImportException("Not a supported picture (PNG, JPEG, WebP, GIF or HEIF)")
        }

    private fun stage(source: java.io.InputStream, maxBytes: Long, kind: AttachmentKind, check: (ByteArray) -> Unit): StagedFile {
        val id = app.folio.core.model.newId()
        val dir = File(root, "staging").apply { mkdirs() }
        val out = File(dir, "$id.${kind.ext}")
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
            check(head.toByteArray())
            return StagedFile(id, out, kind)
        } catch (e: Exception) {
            out.delete()
            throw e
        }
    }

    override fun addImage(notebookId: String, staged: StagedFile): String = synchronized(lock) {
        require(staged.kind == AttachmentKind.IMAGE) { "not a staged picture" }
        require(loadIndex().notebooks.any { it.id == notebookId }) { "unknown notebook" }
        moveInto(staged, imageFile(notebookId, staged.id))
        staged.id
    }

    private fun moveInto(st: StagedFile, target: File) {
        target.parentFile.mkdirs()
        try {
            java.nio.file.Files.move(st.file.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(st.file.toPath(), target.toPath())
        }
    }

    override fun discardStaged(staged: StagedFile) { staged.file.delete() }

    override fun createNotebookFromPdf(title: String, folderId: String?, cover: Cover, staged: StagedFile, pages: List<Page>): NotebookMeta {
        require(pages.all { it.pdf?.attachmentId == staged.id }) { "pages must reference the staged PDF" }
        return createNotebookFromImport(title.trim().ifEmpty { "Imported PDF" }, folderId, cover, listOf(staged), pages, fromPdf = true)
    }

    override fun createNotebookFromImport(
        title: String, folderId: String?, cover: Cover, staged: List<StagedFile>, pages: List<Page>, fromPdf: Boolean,
    ): NotebookMeta = synchronized(lock) {
        require(pages.isNotEmpty()) { "an imported notebook needs at least one page" }
        val pdfIds = staged.filter { it.kind == AttachmentKind.PDF }.mapTo(HashSet()) { it.id }
        val imageIds = staged.filter { it.kind == AttachmentKind.IMAGE }.mapTo(HashSet()) { it.id }
        require(pages.all { p -> p.pdf.let { it == null || it.attachmentId in pdfIds } && p.images.all { it.attachmentId in imageIds } }) {
            "pages may only reference staged attachments"
        }
        require(staged.all { it.file.exists() }) { "staged file is gone" }
        val idx = loadIndex()
        val now = clock()
        val first = pages.first()
        val meta = NotebookMeta(
            title = title.trim().ifEmpty { "Imported notebook" }, folderId = folderId, cover = cover, paper = PaperTemplate.BLANK, fromPdf = fromPdf,
            pageWidth = first.width, pageHeight = first.height, pageIds = pages.map { it.id }, createdAt = now,
        )
        try {
            for (st in staged) {
                moveInto(st, if (st.kind == AttachmentKind.PDF) attachmentFile(meta.id, st.id) else imageFile(meta.id, st.id))
            }
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

    override fun imageFile(notebookId: String, attachmentId: String): File {
        require(SAFE_ID.matches(attachmentId)) { "invalid id" }
        return File(File(notebookDir(notebookId), "attachments"), "$attachmentId.img")
    }

    /**
     * Loads a page, falling back to the backup copy if the main file is corrupt.
     * A page that is listed but missing on disk comes back blank rather than crashing the editor.
     */
    override fun loadPage(notebookId: String, pageId: String): Page {
        // The file is read under the lock (a consistent copy) but decoded outside it: a dense
        // page takes seconds to decode, and saves (or sync) mustn't wait for a page being loaded
        // ahead of the scroll position.
        val f = pageFile(notebookId, pageId)
        val main = synchronized(lock) { if (!f.exists()) return Page(id = pageId); f.readMain() }
        if (main != null) {
            try { return DocumentCodec.decodePage(main.decodeToString()) } catch (e: CorruptDocumentException) {
                lastRecovery = "${f.file.name} was damaged; restored the previous version"
            }
        }
        val bak = synchronized(lock) { f.readBackup() } ?: run {
            if (main != null) throw CorruptDocumentException("${f.file.name} is damaged and has no backup")
            return Page(id = pageId)
        }
        return DocumentCodec.decodePage(bak.decodeToString())
    }

    override fun pageHeader(notebookId: String, pageId: String): PageHeader? {
        val f = pageFile(notebookId, pageId)
        val head = synchronized(lock) { f.readPrefix(PAGE_HEADER_BYTES) } ?: return null
        return DocumentCodec.pageHeaderOf(head.decodeToString())
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

    internal fun loadIndex(): LibraryIndex {
        cached?.let { return it }
        val idx = readWithFallback(indexFile, DocumentCodec::decodeLibrary) ?: LibraryIndex()
        cached = idx
        return idx
    }

    internal fun saveIndex(idx: LibraryIndex) {
        indexFile.write(DocumentCodec.encodeLibrary(idx).toByteArray())
        cached = idx
        onChange?.invoke()
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

    internal fun notebookDir(id: String): File {
        require(SAFE_ID.matches(id)) { "invalid id" }
        return File(File(root, "notebooks"), id)
    }

    internal fun pageFile(notebookId: String, pageId: String): AtomicFile {
        require(SAFE_ID.matches(pageId)) { "invalid id" }
        return AtomicFile(File(notebookDir(notebookId), "$pageId.page.json"))
    }

    internal companion object {
        /** Ids become file names; restrict them so a crafted index can't escape [root]. */
        val SAFE_ID = Regex("[A-Za-z0-9-]{1,64}")
        /** Enough of a page file for its size and paper (they come before the strokes). */
        const val PAGE_HEADER_BYTES = 1024
    }
}
