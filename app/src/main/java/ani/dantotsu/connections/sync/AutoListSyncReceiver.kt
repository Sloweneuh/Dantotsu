package ani.dantotsu.connections.sync

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

/**
 * Alarm entry point for [AutoListSyncTask], for users on the exact-alarm scheduler.
 *
 * Like the unread receivers, this does no work of its own: a full list comparison is minutes of
 * network, and a broadcast receiver has about ten seconds before the process is killed.
 */
class AutoListSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Logger.log("AutoListSyncReceiver: onReceive")
        PrefManager.init(context)

        try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                AutoListSyncWorker.WORK_NAME + "_alarm",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequest.Builder(AutoListSyncWorker::class.java).build()
            )
        } catch (e: Exception) {
            Logger.log("AutoListSyncReceiver: failed to enqueue worker - ${e.message}")
        }

        // AlarmManager alarms are one-shot, so each firing schedules the next one.
        try {
            if (PrefManager.getVal<Boolean>(PrefName.UseAlarmManager)) {
                val interval = PrefManager.getVal<Long>(PrefName.AutoListSyncInterval)
                if (interval > 0) {
                    TaskScheduler.create(context, true)
                        .scheduleRepeatingTask(TaskType.AUTO_LIST_SYNC, interval)
                }
            }
        } catch (e: Exception) {
            Logger.log("AutoListSyncReceiver: reschedule error - ${e.message}")
        }
    }
}
