package ani.dantotsu.notifications.comment

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

class CommentNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Logger.log("CommentNotificationReceiver: onReceive called")
        PrefManager.init(context)

        // Same reasoning as UnreadChapterNotificationReceiver: never run the network-bound check
        // inline in a receiver. goAsync() does not extend the ~10s foreground broadcast deadline,
        // so a slow backend call would ANR-kill the process. Hand off to WorkManager and keep
        // only fast scheduling work here.
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                CommentNotificationWorker.WORK_NAME + "_alarm",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequest.Builder(CommentNotificationWorker::class.java).build()
            )
            Logger.log("CommentNotificationReceiver: enqueued worker")
        } catch (e: Exception) {
            Logger.log("CommentNotificationReceiver: failed to enqueue worker - ${e.message}")
        }

        // Reschedule the next alarm (fast, no network). This receiver previously did not, and the
        // alarms are one-shot (setExactAndAllowWhileIdle), so on the AlarmManager path comment
        // checks ran once and then not again until something called scheduleAllTasks — an app
        // launch or a reboot. Re-arming here restores the configured interval.
        //
        // Safe when the user has since turned comments off: AlarmManagerScheduler builds no
        // intent unless CommentsEnabled == 1 and returns without scheduling.
        try {
            val useAlarmManager = PrefManager.getVal<Boolean>(PrefName.UseAlarmManager)
            if (useAlarmManager) {
                // Index-based, as the settings screen writes it. getOrElse rather than [] because
                // this pref is carried by backup and cloud sync, so a payload written against a
                // different preset list would otherwise crash the receiver on restore.
                val index = PrefManager.getVal<Int>(PrefName.CommentNotificationInterval)
                val interval = CommentNotificationWorker.checkIntervals.getOrElse(index) { 0L }
                if (interval > 0) {
                    TaskScheduler.create(context, true)
                        .scheduleRepeatingTask(TaskType.COMMENT_NOTIFICATION, interval)
                    Logger.log("CommentNotificationReceiver: rescheduled for $interval minutes")
                } else {
                    Logger.log("CommentNotificationReceiver: interval is 0, not rescheduling")
                }
            } else {
                Logger.log("CommentNotificationReceiver: not using AlarmManager, not rescheduling")
            }
        } catch (e: Exception) {
            Logger.log("CommentNotificationReceiver: reschedule error - ${e.message}")
        }
    }
}
