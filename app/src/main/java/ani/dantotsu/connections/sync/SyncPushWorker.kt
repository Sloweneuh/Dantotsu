package ani.dantotsu.connections.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.util.Logger
import java.util.concurrent.TimeUnit

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
        // Before any push reads a baseline: the account or the install may have changed since the
        // last one, in which case those baselines describe a node this device no longer uses.
        SyncIdentity.reconcileIdentity()
        Logger.log("SyncPushWorker: pushing")

        val results = listOf(
            push("CloudSync") { CloudSync.pushNow() },
            push("ProgressSync") { ProgressSync.pushNow() },
            push("ExtensionSync") { ExtensionSync.pushNow() },
            push("ExtensionSettingsSync") { ExtensionSettingsSync.pushNow() },
        )

        // The network constraint only governs when this *starts*; a write that fails after that —
        // the connection drops mid-upload, the request times out — was previously reported as a
        // success and the session's changes were simply lost. Ask for the retry the backoff exists
        // to provide. The pushes are individually idempotent, so re-running the ones that already
        // succeeded costs a hash comparison each.
        if (results.none { it == PushResult.Failed }) return Result.success()
        if (runAttemptCount >= MAX_ATTEMPTS) {
            // Whatever is wrong isn't clearing up. Give up rather than hold a work request open
            // indefinitely: the next foreground session pushes anyway, and the local state that
            // failed to upload is still here, still marked as unsynced.
            Logger.log("SyncPushWorker: giving up after $runAttemptCount attempts")
            return Result.success()
        }
        Logger.log("SyncPushWorker: push failed, retrying (attempt $runAttemptCount)")
        return Result.retry()
    }

    private suspend fun push(name: String, block: suspend () -> PushResult): PushResult =
        runCatching { block() }.getOrElse {
            Logger.log("SyncPushWorker: $name threw: ${it.message}")
            Logger.log(it)
            PushResult.Failed
        }

    companion object {
        const val WORK_NAME = "ani.dantotsu.connections.sync.SyncPushWorker"

        /**
         * Retries to allow before the upload is left to the next session. [runAttemptCount] counts
         * the attempts *already* made, so this permits five: ~30s, 1m, 2m, 4m and 8m of backoff.
         */
        private const val MAX_ATTEMPTS = 5

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
                        .setBackoffCriteria(
                            BackoffPolicy.EXPONENTIAL,
                            30, TimeUnit.SECONDS,
                        )
                        .build()
                )
            }.onFailure { Logger.log("SyncPushWorker: enqueue failed: ${it.message}") }
        }
    }
}
