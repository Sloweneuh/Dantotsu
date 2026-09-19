package ani.dantotsu.notifications.extension

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import java.util.concurrent.TimeUnit

/**
 * Schedules [ExtensionUpdateWorker].
 *
 * Deliberately not a [ani.dantotsu.notifications.TaskScheduler.TaskType]: that abstraction can put
 * a task on either WorkManager or AlarmManager, and this one only works on the first. An alarm
 * fires a receiver, a receiver may not start a foreground service from the background, and the
 * install path needs one — so the alarm form of this task would exist and never install anything.
 */
object ExtensionUpdateScheduler {

    /** Call whenever the auto-update preferences change, and once at startup. */
    fun apply(context: Context) {
        val workManager = WorkManager.getInstance(context)

        if (!PrefManager.getVal<Boolean>(PrefName.AutoUpdateExtensions)) {
            workManager.cancelUniqueWork(ExtensionUpdateWorker.WORK_NAME)
            return
        }

        val intervalMinutes = PrefManager.getVal<Long>(PrefName.AutoUpdateExtensionsInterval)
            .coerceAtLeast(MINIMUM_INTERVAL_MINUTES)
        val wifiOnly = PrefManager.getVal<Boolean>(PrefName.AutoUpdateExtensionsWifiOnly)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        workManager.enqueueUniquePeriodicWork(
            ExtensionUpdateWorker.WORK_NAME,
            // UPDATE, not REPLACE, so the scheduling pass on every launch doesn't keep restarting
            // the countdown and push the first run out indefinitely.
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequest.Builder(
                ExtensionUpdateWorker::class.java,
                intervalMinutes,
                TimeUnit.MINUTES,
            ).setConstraints(constraints).build(),
        )
        Logger.log("ExtensionUpdateScheduler: every $intervalMinutes min, wifiOnly=$wifiOnly")
    }

    /** Runs the update pass once, now, regardless of the schedule. */
    fun runNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            ExtensionUpdateWorker.WORK_NAME + "_once",
            androidx.work.ExistingWorkPolicy.KEEP,
            androidx.work.OneTimeWorkRequest.Builder(ExtensionUpdateWorker::class.java).build(),
        )
    }

    /** WorkManager refuses anything shorter, whatever the preference says. */
    private const val MINIMUM_INTERVAL_MINUTES = 15L
}
