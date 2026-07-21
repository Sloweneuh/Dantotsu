package ani.dantotsu.connections.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.util.Logger

/**
 * Runs the cloud-sync uploads when the app goes to the background.
 *
 * These used to be fired straight into a plain `CoroutineScope(Dispatchers.IO)` from
 * `onActivityStopped`. That is exactly the moment the process is most likely to be killed — a
 * swipe-away tears it down while the Firebase writes are still in flight — so a session's settings
 * changes were regularly lost, which looked like "sync just doesn't work sometimes". WorkManager
 * persists the request, so the upload survives the process going away and retries when the network
 * comes back.
 *
 * Each individual push is already a no-op when nothing changed, when sync is disabled, or when the
 * cloud diverged, so running all four unconditionally is cheap.
 */
class SyncPushWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // The worker can be revived in a fresh process, where PrefManager was never initialised.
        PrefManager.init(applicationContext)
        Logger.log("SyncPushWorker: pushing")
        runCatching { CloudSync.pushNow() }.onFailure { Logger.log(it) }
        runCatching { ProgressSync.pushNow() }.onFailure { Logger.log(it) }
        runCatching { ExtensionSync.pushNow() }.onFailure { Logger.log(it) }
        runCatching { ExtensionSettingsSync.pushNow() }.onFailure { Logger.log(it) }
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "ani.dantotsu.connections.sync.SyncPushWorker"

        /**
         * Enqueues a push. REPLACE rather than KEEP: backgrounding twice in quick succession should
         * upload the *latest* state, and since each push is a no-op when unchanged, replacing a
         * still-pending request costs nothing.
         */
        fun enqueue(context: Context) {
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequest.Builder(SyncPushWorker::class.java)
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build()
                        )
                        .build()
                )
            }.onFailure { Logger.log("SyncPushWorker: enqueue failed: ${it.message}") }
        }
    }
}
