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

/** The AlarmManager side of the standalone Comick check, as [MuUnreadNotificationReceiver]. */
class ComickUnreadNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Logger.log("ComickUnreadNotificationReceiver: onReceive called")
        PrefManager.init(context)

        // Never the network check inline in a receiver: see MuUnreadNotificationReceiver.
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ComickUnreadNotificationWorker.WORK_NAME + "_alarm",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequest.Builder(ComickUnreadNotificationWorker::class.java).build()
            )
            Logger.log("ComickUnreadNotificationReceiver: enqueued worker")
        } catch (e: Exception) {
            Logger.log("ComickUnreadNotificationReceiver: failed to enqueue worker - ${e.message}")
        }

        // Reschedule the next alarm (fast, no network)
        try {
            if (PrefManager.getVal<Boolean>(PrefName.UseAlarmManager)) {
                val interval = TaskScheduler.comickInterval()
                if (interval > 0) {
                    TaskScheduler.create(context, true)
                        .scheduleRepeatingTask(TaskType.COMICK_NOTIFICATION, interval)
                }
            }
        } catch (e: Exception) {
            Logger.log("ComickUnreadNotificationReceiver: Reschedule error - ${e.message}")
        }
    }
}
