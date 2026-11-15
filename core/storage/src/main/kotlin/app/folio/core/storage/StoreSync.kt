package app.folio.core.storage

import app.folio.core.model.DocumentCodec
import app.folio.core.model.Folder
import app.folio.core.model.NotebookMeta
import app.folio.core.model.Page
import app.folio.core.model.newId
import app.folio.core.sync.LocalSide
import app.folio.core.sync.StateStore
import app.folio.core.sync.SyncEngine
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The notebook store as one side of a sync ([SyncEngine]).
 *
 * Keys: `f/folders.json` (all folders), `n/<notebook>/meta.json` (one notebook's details),
 * `n/<notebook>/p/<page>.json` (a page file, as on disk), `n/<notebook>/a/<id>.pdf|.img|.aud`
 * (attachments). The local layout is unchanged; folders and details are cut out of, and
 * merged back into, library.json.
 *
 * Everything coming from the remote is validated before it is written: key shape and ids
 * (no path tricks), pages must decode and carry the id in their key, details must carry
 * their notebook's id. All work happens under the store's lock.
 *
 * Conflicts: folders are united by id (newer wins); notebook details take the newer edit
 * but keep the union of pages; a page keeps the remote version in place and the local one
 * as a new page right after it. A notebook is only removed when none of its pages remain.
 */
class StoreSync(
    private val store: FileNotebookStore,
    private val busyNotebook: () -> String? = { null },
    /**
     * Takes a page arriving for the notebook open in the editor: the editor merges it with
     * what's on screen and calls `store` with the result (the arriving page itself if there
     * was nothing to merge), which writes it to disk. Null: the open notebook waits until
     * it's closed.
     */
    private val livePage: ((notebook: String, page: Page, store: (Page) -> Unit) -> Unit)? = null,
) : LocalSide, StateStore {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val folders = ListSerializer(Folder.serializer())
    private val stateFile = AtomicFile(File(File(store.root, "sync"), "state.json"))
    private val hashCache = HashMap<String, Triple<Long, Long, String>>()

    private sealed class Key {
        object Folders : Key()
        data class Meta(val nb: String) : Key()
        data class Page(val nb: String, val page: String) : Key()
        data class Attachment(val nb: String, val file: String) : Key()
    }

    private fun parse(key: String): Key {
        if (key == FOLDERS) return Key.Folders
        val parts = key.split('/')
        require(parts.size >= 3 && parts[0] == "n" && SAFE.matches(parts[1])) { "unexpected sync key $key" }
        val nb = parts[1]
        return when {
            parts.size == 3 && parts[2] == "meta.json" -> Key.Meta(nb)
            parts.size == 4 && parts[2] == "p" && parts[3].endsWith(".json") && SAFE.matches(parts[3].removeSuffix(".json")) ->
                Key.Page(nb, parts[3].removeSuffix(".json"))
            parts.size == 4 && parts[2] == "a" && ATTACHMENT.matches(parts[3]) -> Key.Attachment(nb, parts[3])
            else -> throw IllegalArgumentException("unexpected sync key $key")
        }
    }

    private fun attachmentsDir(nb: String) = File(store.notebookDir(nb), "attachments")

    // ------------------------------------------------------------------ LocalSide

    override fun snapshot(): Map<String, String> = synchronized(store.lock) {
        val idx = store.loadIndex()
        val out = LinkedHashMap<String, String>()
        out[FOLDERS] = SyncEngine.md5(foldersBytes(idx.folders))
        for (nb in idx.notebooks) {
            out["n/${nb.id}/meta.json"] = SyncEngine.md5(metaBytes(nb))
            for (pid in nb.pageIds) {
                val f = store.pageFile(nb.id, pid).file
                if (f.exists()) out["n/${nb.id}/p/$pid.json"] = fileHash(f)
            }
            attachmentsDir(nb.id).listFiles()?.forEach { f ->
                if (f.isFile && ATTACHMENT.matches(f.name)) out["n/${nb.id}/a/${f.name}"] = fileHash(f)
            }
        }
        out
    }

    override fun read(key: String): ByteArray? = synchronized(store.lock) {
        when (val k = parse(key)) {
            Key.Folders -> foldersBytes(store.loadIndex().folders)
            is Key.Meta -> store.loadIndex().notebooks.firstOrNull { it.id == k.nb }?.let(::metaBytes)
            is Key.Page -> store.pageFile(k.nb, k.page).file.takeIf { it.exists() }?.readBytes()
            is Key.Attachment -> File(attachmentsDir(k.nb), k.file).takeIf { it.exists() }?.readBytes()
        }
    }

    override fun write(key: String, bytes: ByteArray) {
        val k = parse(key)
        val live = livePage
        if (k is Key.Page && live != null && busyNotebook() == k.nb) {
            // Outside the store's lock: the editor may need it to save.
            val page = DocumentCodec.decodePage(bytes.decodeToString())
            require(page.id == k.page) { "page id doesn't match $key" }
            // Stored before this returns, so the sync never mistakes the old file for a local edit.
            live(k.nb, page) { result ->
                writeLocked(key, if (result === page) bytes else DocumentCodec.encodePage(result).toByteArray())
            }
        } else writeLocked(key, bytes)
    }

    private fun writeLocked(key: String, bytes: ByteArray) = synchronized(store.lock) {
        when (val k = parse(key)) {
            Key.Folders -> {
                val list = json.decodeFromString(folders, bytes.decodeToString())
                store.saveIndex(store.loadIndex().copy(folders = list))
            }
            is Key.Meta -> {
                val meta = json.decodeFromString(NotebookMeta.serializer(), bytes.decodeToString())
                require(meta.id == k.nb && meta.pageIds.all { SAFE.matches(it) }) { "notebook details don't match $key" }
                putMeta(meta)
            }
            is Key.Page -> {
                val page = DocumentCodec.decodePage(bytes.decodeToString()) // refuses damaged or too-new pages
                require(page.id == k.page) { "page id doesn't match $key" }
                store.notebookDir(k.nb).mkdirs()
                store.pageFile(k.nb, k.page).write(bytes)
            }
            is Key.Attachment -> {
                val dir = attachmentsDir(k.nb).apply { mkdirs() }
                val tmp = File(dir, "${k.file}.sync-tmp")
                tmp.outputStream().use { it.write(bytes); it.fd.sync() }
                if (!tmp.renameTo(File(dir, k.file))) { tmp.delete(); throw java.io.IOException("couldn't store $key") }
            }
        }
    }

    override fun delete(key: String) = synchronized(store.lock) {
        when (val k = parse(key)) {
            Key.Folders -> Unit // the folder list itself is never deleted
            is Key.Meta -> {
                // Only drop a notebook once all its pages are gone too (an edited page was kept
                // and re-uploaded, so its notebook must stay).
                val dir = store.notebookDir(k.nb)
                val pagesLeft = dir.listFiles()?.any { it.name.endsWith(".page.json") } ?: false
                if (!pagesLeft) {
                    val idx = store.loadIndex()
                    store.saveIndex(idx.copy(notebooks = idx.notebooks.filter { it.id != k.nb }))
                    dir.deleteRecursively()
                }
            }
            is Key.Page -> store.pageFile(k.nb, k.page).delete()
            is Key.Attachment -> { File(attachmentsDir(k.nb), k.file).delete(); Unit }
        }
    }

    override fun busy(key: String): Boolean = busyNotebook()?.let { key.startsWith("n/$it/") } ?: false

    /** Pages, details and attachments of the open notebook can arrive live when the editor takes pages. */
    override fun acceptsLive(key: String): Boolean = livePage != null && runCatching { parse(key) !is Key.Folders }.getOrDefault(false)

    override fun resolve(key: String, mine: ByteArray, theirs: ByteArray): ByteArray = synchronized(store.lock) {
        when (val k = parse(key)) {
            Key.Folders -> {
                val a = json.decodeFromString(folders, mine.decodeToString())
                val b = json.decodeFromString(folders, theirs.decodeToString())
                val byId = LinkedHashMap<String, Folder>()
                for (f in b + a) { val cur = byId[f.id]; if (cur == null || f.modifiedAt > cur.modifiedAt) byId[f.id] = f }
                val merged = foldersBytes(byId.values.toList())
                writeLocked(key, merged)
                merged
            }
            is Key.Meta -> {
                val a = json.decodeFromString(NotebookMeta.serializer(), mine.decodeToString())
                val b = json.decodeFromString(NotebookMeta.serializer(), theirs.decodeToString())
                val newer = if (a.modifiedAt >= b.modifiedAt) a else b
                val other = if (newer === a) b else a
                val merged = newer.copy(
                    pageIds = newer.pageIds + other.pageIds.filter { it !in newer.pageIds },
                    modifiedAt = maxOf(a.modifiedAt, b.modifiedAt),
                )
                val bytes = metaBytes(merged)
                writeLocked(key, bytes)
                bytes
            }
            is Key.Page -> {
                // Theirs stays in place; mine becomes a new page right after it.
                writeLocked(key, theirs)
                val copy = DocumentCodec.decodePage(mine.decodeToString()).copy(id = newId())
                store.pageFile(k.nb, copy.id).write(DocumentCodec.encodePage(copy).toByteArray())
                val idx = store.loadIndex()
                idx.notebooks.firstOrNull { it.id == k.nb }?.let { meta ->
                    val ids = meta.pageIds.toMutableList()
                    val at = ids.indexOf(k.page)
                    ids.add(if (at >= 0) at + 1 else ids.size, copy.id)
                    putMeta(meta.copy(pageIds = ids, modifiedAt = store.clock()))
                }
                theirs
            }
            is Key.Attachment -> { writeLocked(key, theirs); theirs }
        }
    }

    private fun putMeta(meta: NotebookMeta) {
        val idx = store.loadIndex()
        val exists = idx.notebooks.any { it.id == meta.id }
        val list = if (exists) idx.notebooks.map { if (it.id == meta.id) meta else it } else idx.notebooks + meta
        store.notebookDir(meta.id).mkdirs()
        store.saveIndex(idx.copy(notebooks = list))
    }

    private fun foldersBytes(list: List<Folder>) = json.encodeToString(folders, list.sortedBy { it.id }).toByteArray()
    private fun metaBytes(m: NotebookMeta) = json.encodeToString(NotebookMeta.serializer(), m).toByteArray()

    /** MD5 of a file, cached by size and modification time (pages can be tens of MB). */
    private fun fileHash(f: File): String {
        val len = f.length(); val mod = f.lastModified()
        hashCache[f.path]?.let { (l, m, h) -> if (l == len && m == mod) return h }
        val h = SyncEngine.md5(f.readBytes())
        hashCache[f.path] = Triple(len, mod, h)
        return h
    }

    // ------------------------------------------------------------------ StateStore

    private val stateSerializer = MapSerializer(String.serializer(), String.serializer())

    override fun load(): Map<String, String> = synchronized(store.lock) {
        val b = stateFile.readMain() ?: stateFile.readBackup() ?: return emptyMap()
        runCatching { json.decodeFromString(stateSerializer, b.decodeToString()) }.getOrDefault(emptyMap())
    }

    override fun save(bases: Map<String, String>) = synchronized(store.lock) {
        stateFile.file.parentFile.mkdirs()
        stateFile.write(json.encodeToString(stateSerializer, bases).toByteArray())
    }

    /** Forgets what was synced (e.g. after switching to another Google account's Drive). */
    fun reset() = synchronized(store.lock) { stateFile.delete() }

    companion object {
        const val FOLDERS = "f/folders.json"
        private val SAFE = Regex("[A-Za-z0-9-]{1,64}")
        private val ATTACHMENT = Regex("[A-Za-z0-9-]{1,64}\\.(pdf|img|aud)")
    }
}
