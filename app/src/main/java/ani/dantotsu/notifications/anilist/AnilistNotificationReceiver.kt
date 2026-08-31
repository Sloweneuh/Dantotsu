package ani.dantotsu.notifications.anilist

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

class AnilistNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Logger.log("AnilistNotificationReceiver: onReceive called")
        PrefManager.init(context)

        // Same reasoning as UnreadChapterNotificationReceiver: never run the network-bound check
        // inline in a receiver. goAsync() does not extend the ~10s foreground broadcast deadline,
        // so a slow or retrying AniList call would ANR-kill the process. Hand off to WorkManager
        // and keep only fast scheduling work here.
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                AnilistNotificationWorker.WORK_NAME + "_alarm",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequest.Builder(AnilistNotificationWorker::class.java).build()
            )
            Logger.log("AnilistNotificationReceiver: enqueued worker")
        } catch (e: Exception) {
            Logger.log("AnilistNotificationReceiver: failed to enqueue worker - ${e.message}")
        }

        // Reschedule the next alarm (fast, no network). The alarms are one-shot
        // (setExactAndAllowWhileIdle), so skipping this stops the schedule until the next launch.
        try {
            val useAlarmManager = PrefManager.getVal<Boolean>(PrefName.UseAlarmManager)
            if (useAlarmManager) {
                // Index-based, as the settings screen writes it. getOrElse rather than [] because
                // this pref is carried by backup and cloud sync, so a payload written against a
                // different preset list would otherwise crash the receiver on restore.
                val index = PrefManager.getVal<Int>(PrefName.AnilistNotificationInterval)
                val interval = AnilistNotificationWorker.checkIntervals.getOrElse(index) { 0L }
                if (interval > 0) {
                    TaskScheduler.create(context, true)
                        .scheduleRepeatingTask(TaskType.ANILIST_NOTIFICATION, interval)
                    Logger.log("AnilistNotificationReceiver: rescheduled for $interval minutes")
                } else {
                    Logger.log("AnilistNotificationReceiver: interval is 0, not rescheduling")
                }
            } else {
                Logger.log("AnilistNotificationReceiver: not using AlarmManager, not rescheduling")
            }
        } catch (e: Exception) {
            Logger.log("AnilistNotificationReceiver: reschedule error - ${e.message}")
        }
    }
}
