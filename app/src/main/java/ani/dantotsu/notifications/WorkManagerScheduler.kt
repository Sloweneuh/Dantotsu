package ani.dantotsu.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import ani.dantotsu.notifications.TaskScheduler.TaskType
import java.util.concurrent.TimeUnit

class WorkManagerScheduler(private val context: Context) : TaskScheduler {
    override fun scheduleRepeatingTask(taskType: TaskType, interval: Long) {
        // WorkManager minimum is 15 minutes, so use that if interval is less
        val actualInterval = if (interval <= 0) {
            cancelTask(taskType)
            return
        } else if (interval < 15) {
            15L // Minimum 15 minutes for WorkManager
        } else {
            interval
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // No flex window. A flex period confines the work to the *end* of each interval, and these
        // were all built with MIN_PERIODIC_FLEX_MILLIS — five minutes, whatever the interval. At
        // anything but the shortest settings that is a sliver of eligibility per cycle: miss it to
        // Doze or to the network constraint and the run is put off for another full period. Without
        // one the whole interval is fair game, which is what a "roughly every N hours" task wants.
        val recurringWork = PeriodicWorkRequest.Builder(
            TaskScheduler.workerFor(taskType),
            actualInterval,
            TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            TaskScheduler.workNameFor(taskType),
            // UPDATE rather than REPLACE: an unchanged request keeps the schedule it is already on,
            // so the scheduling pass every app launch makes doesn't restart the countdown.
            ExistingPeriodicWorkPolicy.UPDATE,
            recurringWork
        )
    }

    override fun cancelTask(taskType: TaskType) {
        WorkManager.getInstance(context).cancelUniqueWork(TaskScheduler.workNameFor(taskType))
    }
}
