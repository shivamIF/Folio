package app.folio.notes

import android.app.Application
import app.folio.core.storage.DocumentRepository
import app.folio.core.storage.FileNotebookStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import java.io.File

class FolioApp : Application() {
    lateinit var repository: DocumentRepository
        private set

    /** Outlives screens so a save started on exit still finishes. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** One writer thread keeps saves ordered, so an older snapshot never overwrites a newer one. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val ioWriter = Dispatchers.IO.limitedParallelism(1)

    override fun onCreate() {
        super.onCreate()
        repository = FileNotebookStore(File(filesDir, "library"))
    }
}
