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

class MuUnreadNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Logger.log("MuUnreadNotificationReceiver: onReceive called")
        PrefManager.init(context)

        // Same reasoning as UnreadChapterNotificationReceiver: never run the long
        // network-bound check inline in a receiver. goAsync() does not extend the broadcast
        // deadline (~10s on the foreground queue), so it would ANR-kill the process. Hand off
        // to WorkManager and only do fast scheduling work here.
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                MuUnreadNotificationWorker.WORK_NAME + "_alarm",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequest.Builder(MuUnreadNotificationWorker::class.java).build()
            )
            Logger.log("MuUnreadNotificationReceiver: enqueued worker")
        } catch (e: Exception) {
            Logger.log("MuUnreadNotificationReceiver: failed to enqueue worker - ${e.message}")
        }

        // Reschedule the next alarm (fast, no network)
        try {
            val useAlarmManager = PrefManager.getVal<Boolean>(PrefName.UseAlarmManager)
            if (useAlarmManager) {
                val unreadInterval = PrefManager.getVal<Long>(PrefName.UnreadChapterNotificationInterval)
                val interval = if (unreadInterval > 0L) {
                    0L // unread checks are now active; cancel standalone MU schedule
                } else {
                    PrefManager.getVal<Long>(PrefName.MangaUpdatesNotificationInterval)
                }
                if (interval > 0) {
                    TaskScheduler.create(context, true)
                        .scheduleRepeatingTask(TaskType.MU_NOTIFICATION, interval)
                }
            }
        } catch (e: Exception) {
            Logger.log("MuUnreadNotificationReceiver: Reschedule error - ${e.message}")
        }
    }
}
