package app.folio.notes.sync

import android.content.Context
import android.util.Log
import app.folio.core.storage.FileNotebookStore
import app.folio.core.storage.StoreSync
import app.folio.core.sync.DriveException
import app.folio.core.sync.DriveRemote
import app.folio.core.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Runs Google Drive sync for the app: one sync at a time, in the background.
 *
 * When: app start/return (`onForeground`), [PUSH_DELAY_MS] after any local change (page
 * saves included, so edits go out while the notebook is still open), every [PERIOD_MS]
 * while the app is on screen, and the moment Drive's change feed (checked every second
 * while a notebook is open) shows another device's edit, and on "Sync now".
 * Pages edited elsewhere arrive in the open notebook too: the editor merges them with what's
 * on screen ([LiveEditor]). Deletions and same-page conflicts there wait until it's closed.
 * When content arrives, [changes] fires so the library and editor reload.
 */
class SyncManager(
    private val context: Context,
    private val store: FileNotebookStore,
    private val scope: CoroutineScope,
    /** The app's single disk writer (the editor saves on it too). */
    private val ioWriter: kotlinx.coroutines.CoroutineDispatcher,
) {

    /** The open editor, which merges pages arriving from other devices into what's on screen. */
    interface LiveEditor {
        val notebookId: String
        /** Main thread. Returns the page to store: [page] merged with unsaved edits on screen (or [page] itself). */
        fun incomingPage(page: app.folio.core.model.Page): app.folio.core.model.Page
    }


    data class Status(
        val autoSync: Boolean = false,
        val account: String? = null,
        val running: Boolean = false,
        val lastSyncAt: Long? = null,
        val lastResult: String? = null,
        val error: String? = null,
        /** The user has to approve Google access again (open the Drive panel). */
        val needsSignIn: Boolean = false,
    )

    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val _status = MutableStateFlow(
        Status(
            autoSync = prefs.getBoolean("autoSync", false),
            account = prefs.getString("account", null),
            lastSyncAt = prefs.getLong("lastSyncAt", 0L).takeIf { it > 0 },
            lastResult = prefs.getString("lastResult", null),
        ),
    )
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits after a sync changed local content (the library should reload). */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    /** The notebook open in the editor: its pages arrive through [liveEditor]; deletions and conflicts wait until it's closed. */
    @Volatile var openNotebook: String? = null
    @Volatile var liveEditor: LiveEditor? = null

    /** One instance for the app's life: it caches page hashes, so a sync only re-reads files that changed. */
    private val side = StoreSync(store, { openNotebook }) { nb, page, storePage ->
        val editor = liveEditor?.takeIf { it.notebookId == nb }
        val result = if (editor == null) page else kotlinx.coroutines.runBlocking(Dispatchers.Main) { editor.incomingPage(page) }
        // On the editor's own writer, queued before any save of edits made after the merge.
        kotlinx.coroutines.runBlocking(ioWriter) { storePage(result) }
    }

    private val running = Mutex()
    private var periodic: Job? = null
    private var pushJob: Job? = null
    /** A local change happened during a sync; run again once it ends. */
    @Volatile private var dirty = false
    /** Set on the thread doing a sync, so the sync's own writes don't schedule another one. */
    private val syncThread = ThreadLocal<Boolean>()

    init {
        store.onChange = { if (syncThread.get() != true) onLocalChange() }
        scheduleBackground()
    }

    /** Background sync follows the setting: on while auto sync is on and an account is linked. */
    private fun scheduleBackground() {
        val s = _status.value
        runCatching { SyncWorker.schedule(context, s.autoSync && s.account != null) }
            .onFailure { Log.w(TAG, "couldn't schedule background sync", it) }
    }

    enum class Outcome { DONE, RETRY }

    /**
     * For [SyncWorker]: one full sync, waiting for a running one to finish first. RETRY when
     * it failed for a reason that may pass (no connection, Google busy); a needed sign-in or a
     * setup problem is shown in the app instead.
     */
    suspend fun syncInBackground(): Outcome {
        val s = _status.value
        val account = s.account ?: return Outcome.DONE
        if (!s.autoSync) return Outcome.DONE
        running.lock()
        val retry = try {
            syncThread.set(true)
            try { run(account) } finally { syncThread.set(false) }
        } finally { running.unlock() }
        return if (retry) Outcome.RETRY else Outcome.DONE
    }

    /** Debounced push: many quick saves become one sync. */
    private fun onLocalChange() {
        Log.d(TAG, "local change")
        val s = _status.value
        if (s.account == null || !s.autoSync) return
        if (running.isLocked) { dirty = true; return }
        pushJob?.cancel()
        pushJob = scope.launch { delay(PUSH_DELAY_MS); requestSync() }
    }

    fun setAutoSync(on: Boolean) {
        prefs.edit().putBoolean("autoSync", on).apply()
        _status.update { it.copy(autoSync = on) }
        scheduleBackground()
        if (on) requestSync()
    }

    /** Links (or changes) the Google account whose Drive holds the notebooks; null unlinks. */
    fun setAccount(account: String?) {
        prefs.edit().putString("account", account).remove("lastSyncAt").remove("lastResult").apply()
        // A different Drive means a different remote: forget the old sync bases.
        side.reset()
        _status.update { it.copy(account = account, needsSignIn = false, error = null, lastSyncAt = null, lastResult = null) }
        scheduleBackground()
    }

    fun onForeground() {
        requestSync()
        if (periodic?.isActive == true) return
        periodic = scope.launch(Dispatchers.IO) {
            var last = System.currentTimeMillis()
            var feed: Pair<String, String>? = null // account → change-feed position
            while (isActive) {
                delay(TICK_MS)
                val s = _status.value
                val account = s.account ?: continue
                if (!s.autoSync) continue
                // With a notebook open, ask Drive's change feed every second (a tiny request)
                // and sync the moment another device's edit lands.
                if (openNotebook != null && s.error == null && !s.needsSignIn) {
                    try {
                        val remote = DriveRemote({ force -> DriveAuth.token(context, account, force) })
                        val from = feed?.takeIf { it.first == account }?.second ?: remote.startPageToken()
                        val (changed, next) = remote.changedSince(from)
                        feed = account to next
                        // (Our own uploads show up here too; that costs one quick "up to date" pass.)
                        if (changed) { last = System.currentTimeMillis(); requestSync() }
                    } catch (e: Exception) {
                        feed = null // the timed sync below still runs and reports problems
                    }
                }
                if (System.currentTimeMillis() - last >= PERIOD_MS) { last = System.currentTimeMillis(); requestSync() }
            }
        }
    }

    fun onBackground() {
        periodic?.cancel(); periodic = null
        // Send anything still pending even if Android closes the app soon.
        val s = _status.value
        if (s.autoSync && s.account != null) runCatching { SyncWorker.runOnce(context) }
    }

    /** Starts a sync unless one is running, auto sync is off, or no account is linked. [force] syncs even with auto sync off ("Sync now"). */
    fun requestSync(force: Boolean = false, delayMs: Long = 0) {
        val s = _status.value
        if (s.account == null || (!s.autoSync && !force)) return
        scope.launch(Dispatchers.IO) {
            if (delayMs > 0) delay(delayMs)
            if (!running.tryLock()) { dirty = true; return@launch }
            try {
                do {
                    dirty = false
                    syncThread.set(true)
                    try { run(s.account) } finally { syncThread.set(false) }
                } while (dirty && _status.value.error == null && _status.value.account == s.account)
            } finally { running.unlock() }
        }
    }

    /** One sync. Returns true if it failed for a reason that may pass (no connection, Google busy). */
    private fun run(account: String): Boolean {
        _status.update { it.copy(running = true, error = null) }
        try {
            val remote = DriveRemote({ force -> DriveAuth.token(context, account, force) }, trace = { Log.d(TAG, it) })
            val t0 = android.os.SystemClock.elapsedRealtime()
            val r = SyncEngine(side, remote, side).sync()
            val now = System.currentTimeMillis()
            if (r.changes > 0) Log.i(TAG, "sync took ${android.os.SystemClock.elapsedRealtime() - t0} ms: $r")
            val summary = when {
                r.changes == 0 -> "Up to date"
                else -> listOfNotNull(
                    "${r.uploaded} sent".takeIf { r.uploaded > 0 }, "${r.downloaded} received".takeIf { r.downloaded > 0 },
                    "${r.deletedLocal + r.deletedRemote} removed".takeIf { r.deletedLocal + r.deletedRemote > 0 },
                    "${r.conflicts} kept as copies".takeIf { r.conflicts > 0 },
                ).joinToString(", ")
            }
            prefs.edit().putLong("lastSyncAt", now).putString("lastResult", summary).apply()
            _status.update { it.copy(running = false, lastSyncAt = now, lastResult = summary, needsSignIn = false) }
            if (r.downloaded + r.deletedLocal + r.conflicts > 0) _changes.tryEmit(Unit)
            return false
        } catch (e: DriveAuth.NeedsSignIn) {
            _status.update { it.copy(running = false, needsSignIn = true, error = "Sign in again to keep syncing") }
            return false
        } catch (e: Exception) {
            Log.w(TAG, "sync failed", e)
            val msg = when {
                e is DriveException && e.code == 403 -> "Google refused access (check the Google Cloud setup)"
                e is java.net.UnknownHostException || e is java.net.SocketTimeoutException || e is java.net.ConnectException -> "No connection; will retry"
                (e.cause as? com.google.android.gms.common.api.ApiException) != null || e is com.google.android.gms.common.api.ApiException ->
                    "Google sign-in isn't set up for this app yet (see SYNC_SETUP)"
                else -> e.message ?: e.javaClass.simpleName
            }
            _status.update { it.copy(running = false, error = msg) }
            return e is java.io.IOException && (e !is DriveException || e.code == 429 || e.code >= 500)
        }
    }

    companion object {
        private const val TAG = "FolioSync"
        /** How often to look for other devices' changes in the library (one small Drive listing). */
        const val PERIOD_MS = 30_000L
        /** With a notebook open, Drive's change feed is checked this often. */
        private const val TICK_MS = 1_000L
        /** Wait after a local change before sending it, so a burst of saves goes out together. */
        const val PUSH_DELAY_MS = 250L
    }
}
