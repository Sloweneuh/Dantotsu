package ani.dantotsu.notifications

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import ani.dantotsu.connections.discord.RPCManager
import ani.dantotsu.notifications.TaskScheduler.TaskType
import ani.dantotsu.notifications.anilist.AnilistNotificationWorker
import ani.dantotsu.notifications.comment.CommentNotificationWorker
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            PrefManager.init(context)
            Logger.init(context)
            Logger.log("BootCompletedReceiver: Starting Dantotsu notification services on boot")
            // A status left by a session the shutdown cut short. Not awaited: boot is usually
            // too early for the network, and a delete that can't go out waits for the next start.
            RPCManager.cleanupStaleSession(context)

            if (PrefManager.getVal(PrefName.UseAlarmManager)) {
                Logger.log("BootCompletedReceiver: Using AlarmManager, scheduling all tasks")
                val scheduler = TaskScheduler.create(context, true)
                scheduler.scheduleAllTasks(context)
            } else {
                Logger.log("BootCompletedReceiver: Using WorkManager, scheduling all tasks")
                val scheduler = TaskScheduler.create(context, false)
                scheduler.scheduleAllTasks(context)
            }
        }
    }
}

/**
 * Runs once the app has been updated, so a Discord status the replaced process left showing is
 * deleted without waiting for the app to be opened — see [RPCManager.cleanupStaleSession]. Holds
 * the broadcast open until the delete has gone out, or the process could be dropped before it.
 */
class PresenceCleanupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        PrefManager.init(context)
        val pending = goAsync()
        RPCManager.cleanupStaleSession(context).invokeOnCompletion { pending.finish() }
    }
}

class AlarmPermissionStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) {
            PrefManager.init(context)
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val canScheduleExactAlarms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarmManager.canScheduleExactAlarms()
            } else {
                true
            }
            if (canScheduleExactAlarms) {
                TaskScheduler.create(context, false).cancelAllTasks()
                TaskScheduler.create(context, true).scheduleAllTasks(context)
            } else {
                TaskScheduler.create(context, true).cancelAllTasks()
                TaskScheduler.create(context, false).scheduleAllTasks(context)
            }
            PrefManager.setVal(PrefName.UseAlarmManager, canScheduleExactAlarms)
        }
    }
}
