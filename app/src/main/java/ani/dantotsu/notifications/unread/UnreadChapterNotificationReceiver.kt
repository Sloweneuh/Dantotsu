package ani.dantotsu.notifications.unread

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import ani.dantotsu.notifications.TaskScheduler
import ani.dantotsu.notifications.TaskScheduler.TaskType
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger

class UnreadChapterNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Logger.log("UnreadChapterNotificationReceiver: onReceive called")
        PrefManager.init(context)

        // IMPORTANT: do NOT run the unread check inline here.
        // A BroadcastReceiver must return quickly. When the app is in the foreground the
        // alarm broadcast is dispatched on the ~10s foreground queue, and this check makes
        // network calls across the whole reading list (AniList + MalSync + MangaUpdates),
        // which easily exceeds it. goAsync() does NOT extend that deadline, so the system
        // would declare an ANR ("Broadcast of Intent ...UnreadChapterNotificationReceiver")
        // and kill the entire process (manifesting as a silent splash-restart). Hand the
        // long work to WorkManager, which has no such timeout, and keep only fast scheduling
        // work inline.
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UnreadChapterNotificationWorker.WORK_NAME + "_alarm",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequest.Builder(UnreadChapterNotificationWorker::class.java).build()
            )
            Logger.log("UnreadChapterNotificationReceiver: enqueued worker")
        } catch (e: Exception) {
            Logger.log("UnreadChapterNotificationReceiver: failed to enqueue worker - ${e.message}")
        }

        // Reschedule the next alarm (fast, no network)
        try {
            val useAlarmManager = PrefManager.getVal<Boolean>(PrefName.UseAlarmManager)
            if (useAlarmManager) {
                val interval = PrefManager.getVal<Long>(PrefName.UnreadChapterNotificationInterval)
                if (interval > 0) {
                    TaskScheduler.create(context, true)
                        .scheduleRepeatingTask(TaskType.UNREAD_CHAPTER_NOTIFICATION, interval)
                    Logger.log("UnreadChapterNotificationReceiver: rescheduled for $interval minutes")
                } else {
                    Logger.log("UnreadChapterNotificationReceiver: interval is 0, not rescheduling")
                }
            } else {
                Logger.log("UnreadChapterNotificationReceiver: not using AlarmManager, not rescheduling")
            }
        } catch (e: Exception) {
            Logger.log("UnreadChapterNotificationReceiver: reschedule error - ${e.message}")
        }
    }
}
