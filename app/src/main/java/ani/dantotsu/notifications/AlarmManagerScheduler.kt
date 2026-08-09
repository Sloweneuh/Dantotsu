package ani.dantotsu.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import ani.dantotsu.connections.sync.AutoListSyncReceiver
import ani.dantotsu.notifications.TaskScheduler.TaskType
import ani.dantotsu.notifications.anilist.AnilistNotificationReceiver
import ani.dantotsu.notifications.comment.CommentNotificationReceiver
import ani.dantotsu.notifications.subscription.SubscriptionNotificationReceiver
import ani.dantotsu.notifications.unread.MuUnreadNotificationReceiver
import ani.dantotsu.notifications.unread.UnreadChapterNotificationReceiver
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import java.util.concurrent.TimeUnit

class AlarmManagerScheduler(private val context: Context) : TaskScheduler {

    private companion object {
        const val ALARM_DUE_PREFIX = "alarm_due_"
        const val ALARM_INTERVAL_PREFIX = "alarm_interval_"

        /**
         * Ceiling on how late an alarm may be before its slot counts as missed rather than merely
         * delayed. The grace is a whole interval where that is shorter — see [missedGraceFor].
         */
        val MISSED_GRACE_CAP_MS = TimeUnit.MINUTES.toMillis(15)
    }

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

            taskType == TaskType.MU_NOTIFICATION ->
                Intent(context, MuUnreadNotificationReceiver::class.java)

            taskType == TaskType.AUTO_LIST_SYNC ->
                Intent(context, AutoListSyncReceiver::class.java)

            else -> return
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            taskType.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerAtMillis = nextTriggerFor(taskType, interval)
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

    /**
     * How overdue an alarm has to be before the run counts as missed, for a task on [interval].
     *
     * Never longer than one interval: past that, a run has demonstrably been skipped, so a flat
     * allowance would have swallowed real misses on any task set to less than it — which the
     * interval pickers do reach, both through their shorter presets and through a custom value
     * carried in from a backup.
     *
     * Erring short only risks the opposite case — a late-but-delivered alarm read as a miss — and
     * that costs one redundant run, which the workers' own "ran less than a minute ago" check
     * discards anyway. Erring long risks a task quietly not running, which is the bug this exists
     * to fix.
     */
    private fun missedGraceFor(interval: Long): Long =
        minOf(TimeUnit.MINUTES.toMillis(interval), MISSED_GRACE_CAP_MS)

    /**
     * When [taskType]'s alarm is next due, kept across calls so re-arming isn't the same as
     * starting the wait over.
     *
     * An alarm is one-shot, so every path that wants the task scheduled calls this — and
     * [ani.dantotsu.App.onCreate] is one of them, on every launch. Computing `now + interval` each
     * time meant a task whose interval was longer than the gap between two app launches had its
     * alarm pushed forward before it could ever fire; at the 12-hour minimum the auto list
     * comparison runs on, simply opening the app twice a day was enough to stop it for good.
     *
     * A due time only restarts when there is nothing usable to keep: no stored time, one already
     * past (the alarm fired, or was missed), or an interval the user has since changed. Re-arming
     * at a stored future time is what makes the boot receiver and the repeated startup calls
     * harmless — and it restores an alarm the system dropped without moving it.
     */
    private fun nextTriggerFor(taskType: TaskType, interval: Long): Long {
        val dueKey = "$ALARM_DUE_PREFIX${taskType.name}"
        val intervalKey = "$ALARM_INTERVAL_PREFIX${taskType.name}"
        val now = System.currentTimeMillis()
        val due = PrefManager.getCustomVal(dueKey, 0L)
        val sameInterval = PrefManager.getCustomVal(intervalKey, 0L) == interval
        if (sameInterval && due > now) return due

        // A due time this far past is a run the device was off, force-stopped or otherwise
        // unreachable through — so make it up now instead of waiting out another whole interval,
        // which on a twelve-hour task means a day between runs for one missed alarm. The grace
        // period keeps an ordinary firing out of this: the receiver reschedules seconds after the
        // alarm goes off, and Doze can hold an allow-while-idle alarm back several minutes on its
        // own. Neither of those missed anything.
        if (sameInterval && due > 0L && now - due > missedGraceFor(interval)) {
            Logger.log("AlarmManagerScheduler: $taskType was overdue, running it now")
            TaskScheduler.runNow(context, taskType)
        }

        val next = now + TimeUnit.MINUTES.toMillis(interval)
        PrefManager.setCustomVal(dueKey, next)
        PrefManager.setCustomVal(intervalKey, interval)
        return next
    }

    override fun cancelTask(taskType: TaskType) {
        // Dropped along with the alarm, so switching a task back on starts its interval fresh
        // rather than inheriting a due time from whenever it was last on.
        PrefManager.removeCustomVal("$ALARM_DUE_PREFIX${taskType.name}")
        PrefManager.removeCustomVal("$ALARM_INTERVAL_PREFIX${taskType.name}")
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

            taskType == TaskType.MU_NOTIFICATION ->
                Intent(context, MuUnreadNotificationReceiver::class.java)

            taskType == TaskType.AUTO_LIST_SYNC ->
                Intent(context, AutoListSyncReceiver::class.java)

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