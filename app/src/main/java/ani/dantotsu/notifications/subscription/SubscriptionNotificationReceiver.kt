package ani.dantotsu.notifications.subscription

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

class SubscriptionNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Logger.log("SubscriptionNotificationReceiver: onReceive called")
        PrefManager.init(context)

        // Same reasoning as UnreadChapterNotificationReceiver: never run the long network-bound
        // check inline in a receiver. This one fans out one source request per subscribed media,
        // so it blows through the ~10s foreground broadcast deadline on any real library, and
        // goAsync() does not extend that deadline — the system would ANR-kill the process. Hand
        // off to WorkManager and keep only fast scheduling work here.
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                SubscriptionNotificationWorker.WORK_NAME + "_alarm",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequest.Builder(SubscriptionNotificationWorker::class.java).build()
            )
            Logger.log("SubscriptionNotificationReceiver: enqueued worker")
        } catch (e: Exception) {
            Logger.log("SubscriptionNotificationReceiver: failed to enqueue worker - ${e.message}")
        }

        // Reschedule the next alarm (fast, no network)
        try {
            val useAlarmManager = PrefManager.getVal<Boolean>(PrefName.UseAlarmManager)
            if (useAlarmManager) {
                // The interval the settings screen actually writes. The legacy index-based
                // SubscriptionNotificationInterval it superseded is no longer written by
                // anything, so re-arming from it pinned every run after the first to that pref's
                // default (12h), silently overriding whatever the user had picked.
                val interval =
                    PrefManager.getVal<Long>(PrefName.SubscriptionNotificationIntervalMinutes)
                if (interval > 0) {
                    TaskScheduler.create(context, true)
                        .scheduleRepeatingTask(TaskType.SUBSCRIPTION_NOTIFICATION, interval)
                    Logger.log("SubscriptionNotificationReceiver: rescheduled for $interval minutes")
                } else {
                    Logger.log("SubscriptionNotificationReceiver: interval is 0, not rescheduling")
                }
            } else {
                Logger.log("SubscriptionNotificationReceiver: not using AlarmManager, not rescheduling")
            }
        } catch (e: Exception) {
            Logger.log("SubscriptionNotificationReceiver: reschedule error - ${e.message}")
        }
    }
}
