package ani.dantotsu.notifications.unread

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ani.dantotsu.notifications.TaskScheduler
import ani.dantotsu.notifications.TaskScheduler.TaskType
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class UnreadChapterNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Logger.log("UnreadChapterNotificationReceiver: onReceive called")

        // Use goAsync() to keep receiver alive during coroutine execution
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Logger.log("UnreadChapterNotificationReceiver: Starting task execution")
                PrefManager.init(context)

                val interval = PrefManager.getVal<Long>(PrefName.UnreadChapterNotificationInterval)
                Logger.log("UnreadChapterNotificationReceiver: Current interval = $interval minutes")

                UnreadChapterNotificationTask().execute(context)
                Logger.log("UnreadChapterNotificationReceiver: Task completed")
            } catch (e: Exception) {
                Logger.log("UnreadChapterNotificationReceiver: Error - ${e.message}")
            } finally {
                // Reschedule the alarm for the next interval
                try {
                    val useAlarmManager = PrefManager.getVal<Boolean>(PrefName.UseAlarmManager)
                    Logger.log("UnreadChapterNotificationReceiver: useAlarmManager = $useAlarmManager")

                    if (useAlarmManager) {
                        val interval = PrefManager.getVal<Long>(PrefName.UnreadChapterNotificationInterval)
                        if (interval > 0) {
                            Logger.log("UnreadChapterNotificationReceiver: Rescheduling for $interval minutes")
                            TaskScheduler.create(context, true)
                                .scheduleRepeatingTask(TaskType.UNREAD_CHAPTER_NOTIFICATION, interval)
                            Logger.log("UnreadChapterNotificationReceiver: Rescheduled successfully")
                        } else {
                            Logger.log("UnreadChapterNotificationReceiver: Interval is 0, not rescheduling")
                        }
                    } else {
                        Logger.log("UnreadChapterNotificationReceiver: Not using AlarmManager, not rescheduling")
                    }
                } catch (e: Exception) {
                    Logger.log("UnreadChapterNotificationReceiver: Reschedule error - ${e.message}")
                }

                // Finish the async operation
                pendingResult.finish()
                Logger.log("UnreadChapterNotificationReceiver: pendingResult finished")
            }
        }
    }
}

