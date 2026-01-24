package ani.dantotsu.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.PeriodicWorkRequest
import ani.dantotsu.notifications.TaskScheduler.TaskType
import ani.dantotsu.notifications.anilist.AnilistNotificationWorker
import ani.dantotsu.notifications.comment.CommentNotificationWorker
import ani.dantotsu.notifications.subscription.SubscriptionNotificationWorker

class WorkManagerScheduler(private val context: Context) : TaskScheduler {
    override fun scheduleRepeatingTask(taskType: TaskType, interval: Long) {
        // WorkManager minimum is 15 minutes, so use that if interval is less
        val actualInterval = if (interval <= 0) {
            cancelTask(taskType)
            return
        } else if (interval < 15) {
            15L // Minimum 15 minutes for WorkManager
        } else {
            interval
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
            .build()

        when (taskType) {
            TaskType.COMMENT_NOTIFICATION -> {
                val recurringWork = PeriodicWorkRequest.Builder(
                    CommentNotificationWorker::class.java,
                    actualInterval,
                    java.util.concurrent.TimeUnit.MINUTES,
                    PeriodicWorkRequest.MIN_PERIODIC_FLEX_MILLIS,
                    java.util.concurrent.TimeUnit.MILLISECONDS
                )
                    .setConstraints(constraints)
                    .build()
                androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    CommentNotificationWorker.WORK_NAME,
                    androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
                    recurringWork
                )
            }

            TaskType.ANILIST_NOTIFICATION -> {
                val recurringWork = PeriodicWorkRequest.Builder(
                    AnilistNotificationWorker::class.java,
                    actualInterval,
                    java.util.concurrent.TimeUnit.MINUTES,
                    PeriodicWorkRequest.MIN_PERIODIC_FLEX_MILLIS,
                    java.util.concurrent.TimeUnit.MILLISECONDS
                )
                    .setConstraints(constraints)
                    .build()
                androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    AnilistNotificationWorker.WORK_NAME,
                    androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
                    recurringWork
                )
            }

            TaskType.SUBSCRIPTION_NOTIFICATION -> {
                val recurringWork = PeriodicWorkRequest.Builder(
                    SubscriptionNotificationWorker::class.java,
                    actualInterval,
                    java.util.concurrent.TimeUnit.MINUTES,
                    PeriodicWorkRequest.MIN_PERIODIC_FLEX_MILLIS,
                    java.util.concurrent.TimeUnit.MILLISECONDS
                )
                    .setConstraints(constraints)
                    .build()
                androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    SubscriptionNotificationWorker.WORK_NAME,
                    androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
                    recurringWork
                )
            }

            TaskType.UNREAD_CHAPTER_NOTIFICATION -> {
                val recurringWork = PeriodicWorkRequest.Builder(
                    ani.dantotsu.notifications.unread.UnreadChapterNotificationWorker::class.java,
                    actualInterval,
                    java.util.concurrent.TimeUnit.MINUTES,
                    PeriodicWorkRequest.MIN_PERIODIC_FLEX_MILLIS,
                    java.util.concurrent.TimeUnit.MILLISECONDS
                )
                    .setConstraints(constraints)
                    .build()
                androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    ani.dantotsu.notifications.unread.UnreadChapterNotificationWorker.WORK_NAME,
                    androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
                    recurringWork
                )
            }
        }
    }

    override fun cancelTask(taskType: TaskType) {
        when (taskType) {
            TaskType.COMMENT_NOTIFICATION -> {
                androidx.work.WorkManager.getInstance(context)
                    .cancelUniqueWork(CommentNotificationWorker.WORK_NAME)
            }

            TaskType.ANILIST_NOTIFICATION -> {
                androidx.work.WorkManager.getInstance(context)
                    .cancelUniqueWork(AnilistNotificationWorker.WORK_NAME)
            }

            TaskType.SUBSCRIPTION_NOTIFICATION -> {
                androidx.work.WorkManager.getInstance(context)
                    .cancelUniqueWork(SubscriptionNotificationWorker.WORK_NAME)
            }

            TaskType.UNREAD_CHAPTER_NOTIFICATION -> {
                androidx.work.WorkManager.getInstance(context)
                    .cancelUniqueWork(ani.dantotsu.notifications.unread.UnreadChapterNotificationWorker.WORK_NAME)
            }
        }
    }
}