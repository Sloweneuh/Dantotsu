package ani.dantotsu.others

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

/**
 * The background half of "Check for Updates": looks for a newer release while the app is closed and
 * posts a notification for it (see [AppUpdater.notifyUpdate]).
 *
 * Each release is notified once. A check every twelve hours would otherwise repeat the same
 * notification twice a day for as long as the update sits uninstalled; the in-app banner already
 * brings it up again on every launch.
 */
class AppUpdateCheckWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!PrefManager.getVal<Boolean>(PrefName.CheckUpdate) ||
            !PrefManager.getVal<Boolean>(PrefName.CheckUpdateBackground)
        ) return Result.success()

        val update = AppUpdater.findUpdate(applicationContext) ?: return Result.success()
        // The banner too, for when the app is open, or opened before the notification is acted on.
        AppUpdateNotice.offer(update.version) {
            it.startActivity(AppUpdater.openIntent(it, update))
        }
        if (PrefManager.getCustomVal(NOTIFIED_KEY, "") == update.version) return Result.success()
        AppUpdater.notifyUpdate(applicationContext, update)
        PrefManager.setCustomVal(NOTIFIED_KEY, update.version)
        return Result.success()
    }

    private companion object {
        const val NOTIFIED_KEY = "app_update_notified_version"
    }
}
