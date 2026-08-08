package ani.dantotsu.connections.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ani.dantotsu.notifications.unread.isResolverReady
import ani.dantotsu.util.Logger

class AutoListSyncWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Logger.log("AutoListSyncWorker: doWork")
        // The shortest interval this can be scheduled at is 12 hours, so anything arriving within a
        // minute of the last run is a duplicate trigger (an alarm and a queued one-off, say) rather
        // than a run that's genuinely due.
        if (System.currentTimeMillis() - lastRun < 60_000) {
            Logger.log("AutoListSyncWorker: skipped (too soon)")
            return Result.success()
        }

        // Same wake-up race the unread workers guard against: the network reports VALIDATED before
        // the system resolver answers, which would fail every list fetch at once.
        if (!isResolverReady(applicationContext)) {
            Logger.log("AutoListSyncWorker: DNS not ready yet; retrying later")
            return Result.retry()
        }

        lastRun = System.currentTimeMillis()
        return if (AutoListSyncTask().execute(applicationContext)) Result.success()
        else Result.retry()
    }

    companion object {
        const val WORK_NAME = "ani.dantotsu.connections.sync.AutoListSyncWorker"
        private var lastRun = 0L
    }
}
