package app.folio.core.sync

import java.security.MessageDigest

/**
 * Three-way file sync between this device and a remote store (Google Drive's app folder).
 *
 * Every syncable item is a key (e.g. `n/<notebook>/p/<page>.json`) with a content hash.
 * For each key the engine compares this device's hash (L), the remote hash (R) and the
 * hash both agreed on at the last successful sync (B, the base):
 *
 * | L vs B | R vs B | action |
 * |---|---|---|
 * | same | same | nothing |
 * | changed | same | upload |
 * | same | changed | download |
 * | changed | changed (L ≠ R) | conflict → [LocalSide.resolve] (merge, or keep both) |
 * | deleted | same | delete remote |
 * | deleted | changed | download (the remote edit wins over a delete) |
 * | same | deleted | delete local |
 * | changed | deleted | upload (the local edit wins over a delete) |
 *
 * Nothing is ever silently lost: edits beat deletions, and true conflicts keep both
 * versions (pages) or merge them (notebook details, folders). Attachments transfer first
 * and notebook details last, so another device never sees a notebook whose pages are
 * still missing. The base is saved as it goes, so an interrupted sync resumes safely.
 */
class SyncEngine(private val local: LocalSide, private val remote: RemoteSide, private val state: StateStore) {

    data class Report(
        val uploaded: Int = 0, val downloaded: Int = 0, val deletedRemote: Int = 0, val deletedLocal: Int = 0,
        val conflicts: Int = 0, val skippedBusy: Int = 0,
    ) {
        val changes: Int get() = uploaded + downloaded + deletedRemote + deletedLocal + conflicts
        operator fun plus(o: Report) = Report(
            uploaded + o.uploaded, downloaded + o.downloaded, deletedRemote + o.deletedRemote,
            deletedLocal + o.deletedLocal, conflicts + o.conflicts, maxOf(skippedBusy, o.skippedBusy),
        )
    }

    /** Runs until nothing is left to do (a conflict resolution can create new work, e.g. a page copy). */
    fun sync(): Report {
        var total = Report()
        repeat(MAX_PASSES) {
            val r = pass()
            total += r
            if (r.changes == 0) return total
        }
        return total
    }

    private fun pass(): Report {
        val bases = state.load().toMutableMap()
        val mine = local.snapshot()
        val theirs = remote.list().associateBy { it.key }
        var up = 0; var down = 0; var delR = 0; var delL = 0; var conflicts = 0; var busy = 0
        val keys = (mine.keys + theirs.keys + bases.keys).sortedWith(compareBy<String>({ order(it) }, { it }))
        var sinceSave = 0
        fun saved() { if (++sinceSave >= SAVE_EVERY) { state.save(bases); sinceSave = 0 } }
        try {
            for (key in keys) {
                // A busy item (the notebook open in the editor) still sends its edits. It only
                // receives the other side's version if it can take it live (the editor merges
                // it in); deletions and conflicts wait until it's free again.
                val isBusy = local.busy(key)
                fun held(): Boolean { if (isBusy) busy++; return isBusy }
                fun heldDownload(): Boolean = isBusy && !local.acceptsLive(key) && held()
                val l = mine[key]; val r = theirs[key]; val b = bases[key]
                when {
                    l == null && r == null -> bases.remove(key)
                    l != null && r != null && l == r.hash -> bases[key] = l
                    l != null && r == null && b == null -> { bases[key] = upload(key, null) ?: continue; up++ }
                    l == null && r != null && b == null -> { if (heldDownload()) continue; download(r); bases[key] = r.hash; down++ }
                    l != null && r != null && r.hash == b -> { bases[key] = upload(key, r) ?: continue; up++ }
                    l != null && r != null && l == b -> { if (heldDownload()) continue; download(r); bases[key] = r.hash; down++ }
                    l != null && r != null -> {
                        if (held()) continue
                        // Both changed (or first sync with different content): merge or keep both.
                        val merged = local.resolve(key, local.read(key) ?: continue, remote.download(r))
                        val h = md5(merged)
                        if (h != r.hash) { remote.upload(key, merged, h, r) }
                        bases[key] = h
                        conflicts++
                        // Resolving can change other items (e.g. a page copy added to the notebook's
                        // details); finish this pass and start the next from a fresh snapshot.
                        return Report(up, down, delR, delL, conflicts, busy)
                    }
                    l == null && r != null && r.hash == b -> { remote.delete(r); bases.remove(key); delR++ }
                    l == null && r != null -> { if (heldDownload()) continue; download(r); bases[key] = r.hash; down++ }
                    l != null && r == null && l == b -> { if (held()) continue; local.delete(key); bases.remove(key); delL++ }
                    l != null && r == null -> { bases[key] = upload(key, null) ?: continue; up++ }
                }
                saved()
            }
        } finally {
            state.save(bases)
        }
        return Report(up, down, delR, delL, conflicts, busy)
    }

    /** Uploads the current local bytes; returns their hash, or null if the item vanished meanwhile. */
    private fun upload(key: String, existing: RemoteSide.Entry?): String? {
        val bytes = local.read(key) ?: return null
        val h = md5(bytes)
        remote.upload(key, bytes, h, existing)
        return h
    }

    private fun download(r: RemoteSide.Entry) = local.write(r.key, remote.download(r))

    companion object {
        private const val MAX_PASSES = 12
        private const val SAVE_EVERY = 20

        /** Attachments, then pages, then folders and notebook details. */
        fun order(key: String): Int = when {
            "/a/" in key -> 0
            "/p/" in key -> 1
            else -> 2
        }

        fun md5(b: ByteArray): String = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }
    }
}

/** This device's side of the sync. */
interface LocalSide {
    /** Every syncable key with the MD5 of its current content. */
    fun snapshot(): Map<String, String>
    fun read(key: String): ByteArray?
    /** Atomically replaces (or creates) the item. */
    fun write(key: String, bytes: ByteArray)
    fun delete(key: String)
    /** True for items that mustn't change on this device right now (e.g. the notebook open in the editor); their local edits are still sent. */
    fun busy(key: String): Boolean = false
    /** For a [busy] item: can [write] still take the other side's version now (e.g. merged into the open editor)? */
    fun acceptsLive(key: String): Boolean = false
    /**
     * Both sides changed [key] differently. Returns the content both sides should now have
     * (and writes it locally), e.g. merged notebook details, or the remote page with the
     * local version kept as a separate copy.
     */
    fun resolve(key: String, mine: ByteArray, theirs: ByteArray): ByteArray
}

/** The remote store (Drive app folder). Entries carry the hash of the content as it is locally. */
interface RemoteSide {
    data class Entry(val key: String, val id: String, val hash: String)
    fun list(): List<Entry>
    fun download(e: Entry): ByteArray
    fun upload(key: String, bytes: ByteArray, hash: String, existing: Entry?): Entry
    fun delete(e: Entry)
}

/** Where the bases (hash per key at the last sync) are kept between runs. */
interface StateStore {
    fun load(): Map<String, String>
    fun save(bases: Map<String, String>)
}

/** In-memory remote, for tests and as the reference behaviour of [RemoteSide]. */
class MemoryRemote : RemoteSide {
    val files = LinkedHashMap<String, Pair<String, ByteArray>>() // key → (hash, bytes)
    var nextId = 0
    var failAfter = Int.MAX_VALUE
    private var calls = 0
    private fun tick() { if (++calls > failAfter) throw java.io.IOException("network down (simulated)") }

    override fun list(): List<RemoteSide.Entry> { tick(); return files.map { (k, v) -> RemoteSide.Entry(k, "id-$k", v.first) } }
    override fun download(e: RemoteSide.Entry): ByteArray { tick(); return files[e.key]?.second ?: throw java.io.FileNotFoundException(e.key) }
    override fun upload(key: String, bytes: ByteArray, hash: String, existing: RemoteSide.Entry?): RemoteSide.Entry {
        tick(); files[key] = hash to bytes.copyOf(); return RemoteSide.Entry(key, "id-$key", hash)
    }
    override fun delete(e: RemoteSide.Entry) { tick(); files.remove(e.key) }
}
