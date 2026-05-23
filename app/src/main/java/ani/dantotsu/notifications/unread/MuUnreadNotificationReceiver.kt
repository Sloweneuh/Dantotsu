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

class MuUnreadNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Logger.log("MuUnreadNotificationReceiver: onReceive called")

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                PrefManager.init(context)
                MuUnreadNotificationTask().execute(context)
                Logger.log("MuUnreadNotificationReceiver: Task completed")
            } catch (e: Exception) {
                Logger.log("MuUnreadNotificationReceiver: Error - ${e.message}")
            } finally {
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
                pendingResult.finish()
            }
        }
    }
}
