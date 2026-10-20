package app.folio.notes.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.folio.notes.FolioApp
import java.util.concurrent.TimeUnit

/**
 * Syncs with Google Drive while Folio isn't on screen: every [PERIOD_MIN] minutes (Android's
 * shortest period; the system may stretch it to save battery) and once right after the app
 * goes to the background, so the last edits go out even if the app is then closed.
 * Both only run with a network connection.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when ((applicationContext as FolioApp).sync.syncInBackground()) {
        SyncManager.Outcome.DONE -> Result.success()
        SyncManager.Outcome.RETRY -> if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
    }

    companion object {
        private const val PERIODIC = "folio-sync-periodic"
        private const val ONCE = "folio-sync-once"
        const val PERIOD_MIN = 15L
        private const val MAX_RETRIES = 5

        private fun online() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Turns the periodic sync on or off (auto sync on and an account linked). */
        fun schedule(context: Context, on: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (on) wm.enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<SyncWorker>(PERIOD_MIN, TimeUnit.MINUTES).setConstraints(online()).build(),
            ) else {
                wm.cancelUniqueWork(PERIODIC)
                wm.cancelUniqueWork(ONCE)
            }
        }

        /** One sync as soon as there's a network (e.g. when the app leaves the screen). */
        fun runOnce(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONCE, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(online()).build(),
            )
        }
    }
}
