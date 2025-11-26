package ani.dantotsu.notifications.unread

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ani.dantotsu.util.Logger

class UnreadChapterNotificationWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Logger.log("UnreadChapterNotificationWorker: doWork")
        if (System.currentTimeMillis() - lastCheck < 60000) {
            Logger.log("UnreadChapterNotificationWorker: doWork skipped (too soon)")
            return Result.success()
        }
        lastCheck = System.currentTimeMillis()
        return if (UnreadChapterNotificationTask().execute(applicationContext)) {
            Result.success()
        } else {
            Logger.log("UnreadChapterNotificationWorker: doWork failed")
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "ani.dantotsu.notifications.unread.UnreadChapterNotificationWorker"
        private var lastCheck = 0L
    }
}

