package app.folio.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A device: a map of files plus its own sync state. Conflicts keep both (the copy gets a "-copy" key). */
private class Device(val remote: RemoteSide) {
    val files = LinkedHashMap<String, String>()
    var bases: Map<String, String> = emptyMap()
    var busyPrefix: String? = null
    var live = false
    val resolved = ArrayList<String>()

    val local = object : LocalSide {
        override fun snapshot() = files.mapValues { SyncEngine.md5(it.value.toByteArray()) }
        override fun read(key: String) = files[key]?.toByteArray()
        override fun write(key: String, bytes: ByteArray) { files[key] = String(bytes) }
        override fun delete(key: String) { files.remove(key) }
        override fun busy(key: String) = busyPrefix?.let { key.startsWith(it) } ?: false
        override fun acceptsLive(key: String) = live
        override fun resolve(key: String, mine: ByteArray, theirs: ByteArray): ByteArray {
            resolved += key
            files["$key-copy"] = String(mine) // keep mine as a separate item
            files[key] = String(theirs)
            return theirs
        }
    }
    private val store = object : StateStore {
        override fun load() = bases
        override fun save(bases: Map<String, String>) { this@Device.bases = HashMap(bases) }
    }
    fun sync() = SyncEngine(local, remote, store).sync()
}

class SyncEngineTest {
    private val drive = MemoryRemote()
    private val a = Device(drive)
    private val b = Device(drive)

    @Test fun newWorkReachesTheOtherDevice() {
        a.files["n/1/p/x.json"] = "page x"
        a.files["n/1/meta.json"] = "meta 1"
        a.sync(); b.sync()
        assertEquals(a.files, b.files)
    }

    @Test fun editsToDifferentItemsBothSurvive() {
        a.files["n/1/p/x.json"] = "x0"; a.files["n/1/p/y.json"] = "y0"
        a.sync(); b.sync()
        a.files["n/1/p/x.json"] = "x by A"
        b.files["n/1/p/y.json"] = "y by B"
        a.sync(); b.sync(); a.sync()
        assertEquals(mapOf("n/1/p/x.json" to "x by A", "n/1/p/y.json" to "y by B"), a.files)
        assertEquals(a.files, b.files)
    }

    @Test fun sameItemEditedOnBothKeepsBothVersions() {
        a.files["n/1/p/x.json"] = "base"
        a.sync(); b.sync()
        a.files["n/1/p/x.json"] = "A's edit"
        b.files["n/1/p/x.json"] = "B's edit"
        a.sync(); b.sync(); a.sync()
        assertEquals(listOf("n/1/p/x.json"), b.resolved)
        assertEquals("A's edit", b.files["n/1/p/x.json"])
        assertEquals("B's edit", b.files["n/1/p/x.json-copy"])
        assertEquals(a.files, b.files, "both devices converge, with nothing lost")
    }

    @Test fun deleteSpreadsButNeverBeatsAnEdit() {
        a.files["n/1/p/x.json"] = "x"; a.files["n/1/p/y.json"] = "y"
        a.sync(); b.sync()
        // x: deleted on A, untouched on B → gone everywhere.
        a.files.remove("n/1/p/x.json")
        // y: deleted on A, edited on B → the edit survives.
        a.files.remove("n/1/p/y.json")
        b.files["n/1/p/y.json"] = "y edited on B"
        a.sync(); b.sync(); a.sync()
        assertEquals(mapOf("n/1/p/y.json" to "y edited on B"), a.files)
        assertEquals(a.files, b.files)
    }

    @Test fun editBeatsADeleteMadeOnTheOtherSideToo() {
        a.files["n/1/p/x.json"] = "x"
        a.sync(); b.sync()
        b.files.remove("n/1/p/x.json"); b.sync()   // B deletes and syncs first
        a.files["n/1/p/x.json"] = "x edited on A"   // A edited meanwhile
        a.sync(); b.sync()
        assertEquals("x edited on A", b.files["n/1/p/x.json"])
    }

    @Test fun theOpenNotebookIsLeftAloneUntilClosed() {
        a.files["n/1/p/x.json"] = "x"; a.files["n/2/p/y.json"] = "y"
        a.sync(); b.sync()
        a.files["n/1/p/x.json"] = "x2"
        b.busyPrefix = "n/1/"
        a.sync()
        val r = b.sync()
        assertTrue(r.skippedBusy > 0)
        assertEquals("x", b.files["n/1/p/x.json"])
        b.busyPrefix = null; b.sync()
        assertEquals("x2", b.files["n/1/p/x.json"])
    }

    @Test fun anOpenNotebookThatTakesLiveUpdatesGetsEditsButNotDeletesOrConflicts() {
        a.files["n/1/p/x.json"] = "x"; a.files["n/1/p/y.json"] = "y"; a.files["n/1/p/z.json"] = "z"
        a.sync(); b.sync()
        b.busyPrefix = "n/1/"; b.live = true
        a.files["n/1/p/x.json"] = "x2"; a.files["n/1/p/new.json"] = "new"; a.files.remove("n/1/p/y.json")
        a.files["n/1/p/z.json"] = "z by A"; b.files["n/1/p/z.json"] = "z by B"
        a.sync(); b.sync()
        assertEquals("x2", b.files["n/1/p/x.json"], "an edit arrives while open")
        assertEquals("new", b.files["n/1/p/new.json"], "a new page arrives while open")
        assertEquals("y", b.files["n/1/p/y.json"], "a deletion waits")
        assertEquals("z by B", b.files["n/1/p/z.json"], "a conflict waits")
        b.busyPrefix = null; b.sync(); a.sync()
        assertTrue("n/1/p/y.json" !in b.files)
        assertEquals(setOf("z by A", "z by B"), setOf(b.files["n/1/p/z.json"], b.files["n/1/p/z.json-copy"]))
    }

    @Test fun theOpenNotebookStillSendsItsEdits() {
        a.files["n/1/p/x.json"] = "x"
        a.sync(); b.sync()
        a.busyPrefix = "n/1/"
        a.files["n/1/p/x.json"] = "written while open"
        a.files["n/1/p/new.json"] = "new page while open"
        a.sync()
        b.sync()
        assertEquals(a.files, b.files, "edits in the open notebook reach the other device without closing it")
        // And a change from elsewhere waits until the notebook is closed.
        b.files["n/1/p/x.json"] = "edited on B"
        b.sync()
        a.sync()
        assertEquals("written while open", a.files["n/1/p/x.json"])
        a.busyPrefix = null; a.sync()
        assertEquals("edited on B", a.files["n/1/p/x.json"])
    }

    @Test fun anInterruptedSyncResumesWithoutLoss() {
        for (i in 0 until 50) a.files["n/1/p/$i.json"] = "page $i"
        drive.failAfter = 20
        assertFailsWith<java.io.IOException> { a.sync() }
        drive.failAfter = Int.MAX_VALUE
        a.sync(); b.sync()
        assertEquals(a.files, b.files)
        assertEquals(50, drive.files.size)
    }

    @Test fun attachmentsThenPagesThenDetails() {
        val order = ArrayList<String>()
        val spy = object : RemoteSide by drive {
            override fun upload(key: String, bytes: ByteArray, hash: String, existing: RemoteSide.Entry?) = drive.upload(key, bytes, hash, existing).also { order += key }
        }
        val c = Device(spy)
        c.files["n/1/meta.json"] = "m"; c.files["n/1/p/x.json"] = "p"; c.files["n/1/a/z.pdf"] = "pdf"; c.files["f/folders.json"] = "f"
        c.sync()
        assertEquals(listOf("n/1/a/z.pdf", "n/1/p/x.json"), order.take(2))
        assertTrue(order.indexOf("n/1/meta.json") > order.indexOf("n/1/p/x.json"))
    }

    @Test fun firstSyncOfTwoDevicesWithDifferentCopiesKeepsBoth() {
        a.files["f/folders.json"] = "A folders"
        b.files["f/folders.json"] = "B folders"
        a.sync(); b.sync(); a.sync()
        assertEquals(a.files, b.files)
        assertEquals(setOf("A folders", "B folders"), setOf(a.files["f/folders.json"], a.files["f/folders.json-copy"]))
    }
}
