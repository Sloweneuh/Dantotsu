package ani.dantotsu.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import ani.dantotsu.notifications.TaskScheduler.TaskType
import ani.dantotsu.notifications.anilist.AnilistNotificationReceiver
import ani.dantotsu.notifications.comment.CommentNotificationReceiver
import ani.dantotsu.notifications.subscription.SubscriptionNotificationReceiver
import ani.dantotsu.notifications.unread.UnreadChapterNotificationReceiver
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import java.util.concurrent.TimeUnit

class AlarmManagerScheduler(private val context: Context) : TaskScheduler {
    override fun scheduleRepeatingTask(taskType: TaskType, interval: Long) {
        Logger.log("AlarmManagerScheduler: scheduleRepeatingTask called for $taskType with interval $interval minutes")

        if (interval <= 0) {
            Logger.log("AlarmManagerScheduler: Interval is 0 or negative, canceling task for $taskType")
            cancelTask(taskType)
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Check if we can schedule exact alarms on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) {
                Logger.log("AlarmManagerScheduler: Cannot schedule exact alarms - permission not granted!")
                return
            }
        }

        val intent = when {
            taskType == TaskType.COMMENT_NOTIFICATION && PrefManager.getVal<Int>(PrefName.CommentsEnabled) == 1 ->
                Intent(context, CommentNotificationReceiver::class.java)

            taskType == TaskType.ANILIST_NOTIFICATION ->
                Intent(context, AnilistNotificationReceiver::class.java)

            taskType == TaskType.SUBSCRIPTION_NOTIFICATION ->
                Intent(context, SubscriptionNotificationReceiver::class.java)

            taskType == TaskType.UNREAD_CHAPTER_NOTIFICATION ->
                Intent(context, UnreadChapterNotificationReceiver::class.java)

            else -> return
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            taskType.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerAtMillis = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(interval)
        val triggerDate = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(triggerAtMillis))
        Logger.log("AlarmManagerScheduler: Scheduling alarm for $taskType to trigger at $triggerDate (in $interval minutes)")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                Logger.log("AlarmManagerScheduler: Alarm scheduled successfully using setExactAndAllowWhileIdle")
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                Logger.log("AlarmManagerScheduler: Alarm scheduled successfully using setExact")
            }
        } catch (e: SecurityException) {
            Logger.log("AlarmManagerScheduler: SecurityException - Cannot schedule exact alarms! Error: ${e.message}")
            PrefManager.setVal(PrefName.UseAlarmManager, false)
            TaskScheduler.create(context, true).cancelAllTasks()
            TaskScheduler.create(context, false).scheduleAllTasks(context)
        } catch (e: Exception) {
            Logger.log("AlarmManagerScheduler: Exception scheduling alarm: ${e.message}")
        }
    }

    override fun cancelTask(taskType: TaskType) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = when {
            taskType == TaskType.COMMENT_NOTIFICATION && PrefManager.getVal<Int>(PrefName.CommentsEnabled) == 1 ->
                Intent(context, CommentNotificationReceiver::class.java)

            taskType == TaskType.ANILIST_NOTIFICATION ->
                Intent(context, AnilistNotificationReceiver::class.java)

            taskType == TaskType.SUBSCRIPTION_NOTIFICATION ->
                Intent(context, SubscriptionNotificationReceiver::class.java)

            taskType == TaskType.UNREAD_CHAPTER_NOTIFICATION ->
                Intent(context, UnreadChapterNotificationReceiver::class.java)

            else -> return
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            taskType.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }
}