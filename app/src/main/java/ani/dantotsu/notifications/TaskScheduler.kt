package ani.dantotsu.notifications

import android.content.Context
import ani.dantotsu.connections.sync.AutoListSyncWorker
import ani.dantotsu.notifications.anilist.AnilistNotificationWorker
import ani.dantotsu.notifications.comment.CommentNotificationWorker
import ani.dantotsu.notifications.subscription.SubscriptionNotificationWorker
import ani.dantotsu.notifications.unread.MuUnreadNotificationWorker
import ani.dantotsu.notifications.unread.UnreadChapterNotificationWorker
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

interface TaskScheduler {
    fun scheduleRepeatingTask(taskType: TaskType, interval: Long)
    fun cancelTask(taskType: TaskType)

    fun cancelAllTasks() {
        for (taskType in TaskType.entries) {
            cancelTask(taskType)
        }
    }

    fun scheduleAllTasks(context: Context) {
        for (taskType in TaskType.entries) {
            val interval = when (taskType) {
                TaskType.COMMENT_NOTIFICATION -> CommentNotificationWorker.checkIntervals[PrefManager.getVal(
                    PrefName.CommentNotificationInterval
                )]

                TaskType.ANILIST_NOTIFICATION -> AnilistNotificationWorker.checkIntervals[PrefManager.getVal(
                    PrefName.AnilistNotificationInterval
                )]

                TaskType.SUBSCRIPTION_NOTIFICATION -> PrefManager.getVal(
                    PrefName.SubscriptionNotificationIntervalMinutes
                )

                TaskType.UNREAD_CHAPTER_NOTIFICATION -> PrefManager.getVal(
                    PrefName.UnreadChapterNotificationInterval
                )

                // Only worth a schedule while a tracker is switched on for sync — the task skips
                // every section whose switch is off, so with both off it would wake on its
                // interval, find nothing it may touch and go back to sleep. Read off the switches
                // rather than the logins: tokens are restored lazily and may not be loaded yet at
                // the point this runs, which would cancel the schedule of a signed-in user.
                TaskType.AUTO_LIST_SYNC ->
                    if (PrefManager.getVal<Boolean>(PrefName.MalListSyncEnabled) ||
                        PrefManager.getVal<Boolean>(PrefName.MangaBakaListSyncEnabled)
                    ) PrefManager.getVal(PrefName.AutoListSyncInterval) else 0L

                TaskType.MU_NOTIFICATION -> {
                    val unreadInterval = PrefManager.getVal<Long>(PrefName.UnreadChapterNotificationInterval)
                    if (unreadInterval > 0L) {
                        // MU is already checked inside UnreadChapterNotificationTask; cancel standalone
                        0L
                    } else {
                        PrefManager.getVal(PrefName.MangaUpdatesNotificationInterval)
                    }
                }
            }
            scheduleRepeatingTask(taskType, interval)
        }
    }

    companion object {
        /** The worker a task runs, shared by its repeating schedule and any one-off run of it. */
        fun workerFor(taskType: TaskType): Class<out androidx.work.ListenableWorker> =
            when (taskType) {
                TaskType.COMMENT_NOTIFICATION -> CommentNotificationWorker::class.java
                TaskType.ANILIST_NOTIFICATION -> AnilistNotificationWorker::class.java
                TaskType.SUBSCRIPTION_NOTIFICATION -> SubscriptionNotificationWorker::class.java
                TaskType.UNREAD_CHAPTER_NOTIFICATION -> UnreadChapterNotificationWorker::class.java
                TaskType.MU_NOTIFICATION -> MuUnreadNotificationWorker::class.java
                TaskType.AUTO_LIST_SYNC -> AutoListSyncWorker::class.java
            }

        /** The unique-work name a task's repeating schedule is filed under. */
        fun workNameFor(taskType: TaskType): String = when (taskType) {
            TaskType.COMMENT_NOTIFICATION -> CommentNotificationWorker.WORK_NAME
            TaskType.ANILIST_NOTIFICATION -> AnilistNotificationWorker.WORK_NAME
            TaskType.SUBSCRIPTION_NOTIFICATION -> SubscriptionNotificationWorker.WORK_NAME
            TaskType.UNREAD_CHAPTER_NOTIFICATION -> UnreadChapterNotificationWorker.WORK_NAME
            TaskType.MU_NOTIFICATION -> MuUnreadNotificationWorker.WORK_NAME
            TaskType.AUTO_LIST_SYNC -> AutoListSyncWorker.WORK_NAME
        }

        /**
         * Runs [taskType] once, as soon as its constraints allow — used to make up a run whose
         * moment passed while the device was off, asleep or force-stopped.
         *
         * Filed under its own unique name so it can't disturb the repeating schedule, and KEEP so
         * a second caller finding the same overdue task joins the run already queued rather than
         * stacking another one behind it.
         */
        fun runNow(context: Context, taskType: TaskType) {
            androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                workNameFor(taskType) + "_catchup",
                androidx.work.ExistingWorkPolicy.KEEP,
                androidx.work.OneTimeWorkRequest.Builder(workerFor(taskType)).build()
            )
        }

        fun create(context: Context, useAlarmManager: Boolean): TaskScheduler {
            return if (useAlarmManager) {
                AlarmManagerScheduler(context)
            } else {
                WorkManagerScheduler(context)
            }
        }

        fun scheduleSingleWork(context: Context) {
            val workManager = androidx.work.WorkManager.getInstance(context)
            workManager.enqueueUniqueWork(
                CommentNotificationWorker.WORK_NAME + "_single",
                androidx.work.ExistingWorkPolicy.REPLACE,
                androidx.work.OneTimeWorkRequest.Builder(CommentNotificationWorker::class.java)
                    .build()
            )
            workManager.enqueueUniqueWork(
                AnilistNotificationWorker.WORK_NAME + "_single",
                androidx.work.ExistingWorkPolicy.REPLACE,
                androidx.work.OneTimeWorkRequest.Builder(AnilistNotificationWorker::class.java)
                    .build()
            )
            workManager.enqueueUniqueWork(
                SubscriptionNotificationWorker.WORK_NAME + "_single",
                androidx.work.ExistingWorkPolicy.REPLACE,
                androidx.work.OneTimeWorkRequest.Builder(SubscriptionNotificationWorker::class.java)
                    .build()
            )
            workManager.enqueueUniqueWork(
                UnreadChapterNotificationWorker.WORK_NAME + "_single",
                androidx.work.ExistingWorkPolicy.REPLACE,
                androidx.work.OneTimeWorkRequest.Builder(UnreadChapterNotificationWorker::class.java)
                    .build()
            )
        }
    }

    enum class TaskType {
        COMMENT_NOTIFICATION,
        ANILIST_NOTIFICATION,
        SUBSCRIPTION_NOTIFICATION,
        UNREAD_CHAPTER_NOTIFICATION,
        MU_NOTIFICATION,
        AUTO_LIST_SYNC
    }
}

interface Task {
    suspend fun execute(context: Context): Boolean
}
