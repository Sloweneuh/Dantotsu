package ani.dantotsu.notifications.unread

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ani.dantotsu.util.Logger

class MuUnreadNotificationWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Logger.log("MuUnreadNotificationWorker: doWork")
        if (System.currentTimeMillis() - lastCheck < 60000) {
            Logger.log("MuUnreadNotificationWorker: doWork skipped (too soon)")
            return Result.success()
        }

        // See isResolverReady: avoid the transient post-wake DNS race that fails every host.
        if (!isResolverReady(applicationContext)) {
            Logger.log("MuUnreadNotificationWorker: DNS not ready yet (device likely just woke); retrying later")
            return Result.retry()
        }

        lastCheck = System.currentTimeMillis()
        return if (MuUnreadNotificationTask().execute(applicationContext)) {
            Result.success()
        } else {
            Logger.log("MuUnreadNotificationWorker: doWork failed")
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "ani.dantotsu.notifications.unread.MuUnreadNotificationWorker"
        private var lastCheck = 0L
    }
}
