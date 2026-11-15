package app.folio.desktop.sync

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.folio.core.storage.FileNotebookStore
import app.folio.core.storage.StoreSync
import app.folio.core.sync.DriveRemote
import app.folio.core.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Google Drive sync on the Mac: the same engine ([SyncEngine]) and store adapter ([StoreSync])
 * as the Android app, with the Mac's own sign-in. One sync at a time: at launch, every
 * [PERIOD_MS] while the app is open, and on "Sync now".
 */
class MacSync(store: FileNotebookStore, val google: GoogleSignIn) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** The notebook open for editing: its deletions and conflicts wait until it's closed. */
    @Volatile var openNotebook: String? = null
    private val side = StoreSync(store, { openNotebook })
    private var push: kotlinx.coroutines.Job? = null

    /** A page was saved on this Mac: send it soon (edits made close together go out in one sync). */
    fun changed() {
        if (!signedIn) return
        push?.cancel()
        push = scope.launch { delay(PUSH_DELAY_MS); syncOnce() }
    }
    private val running = Mutex()

    var busy by mutableStateOf(false); private set
    /** One line for the settings pane: what happened last, or what is needed. */
    var status by mutableStateOf(""); private set
    var signedIn by mutableStateOf(google.signedIn); private set
    val configured: Boolean get() = google.configured
    /** Goes up whenever a sync changed something on this Mac: the library reloads. */
    var libraryVersion by mutableIntStateOf(0); private set

    fun start() = scope.launch { while (true) { if (signedIn) syncOnce(); delay(PERIOD_MS) } }

    fun signIn() = scope.launch {
        ui { busy = true; status = "Waiting for you to approve in the browser…" }
        try { google.signIn(); ui { signedIn = true; status = "Signed in." } }
        catch (e: Exception) { ui { status = e.message ?: "Sign-in failed." } }
        finally { ui { busy = false } }
        if (signedIn) syncOnce()
    }

    fun signOut() { google.signOut(); signedIn = false; status = "Signed out. Notes on this Mac stay." }

    fun syncNow() = scope.launch { syncOnce() }

    private suspend fun syncOnce() {
        if (!running.tryLock()) return
        ui { busy = true; status = "Syncing…" }
        try {
            val r = SyncEngine(side, DriveRemote({ force -> google.token(force) }), side).sync()
            ui {
                status = if (r.changes == 0) "Up to date." else
                    "Synced: ${r.downloaded} received, ${r.uploaded} sent" + (if (r.conflicts > 0) ", ${r.conflicts} conflicts kept as copies" else "") + "."
                if (r.downloaded + r.deletedLocal + r.conflicts > 0) libraryVersion++
            }
        } catch (e: NeedsSignIn) {
            ui { signedIn = google.signedIn; status = e.message ?: "Sign in to Google to sync." }
        } catch (e: Exception) {
            ui { status = "Sync failed: ${e.message ?: e.javaClass.simpleName}. It will try again." }
        } finally {
            ui { busy = false }
            running.unlock()
        }
    }

    private suspend fun ui(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    companion object { const val PERIOD_MS = 60_000L; const val PUSH_DELAY_MS = 3_000L }
}
