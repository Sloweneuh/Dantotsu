package ani.dantotsu.notifications.extension

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import ani.dantotsu.R
import ani.dantotsu.parsers.novel.NovelExtensionManager
import ani.dantotsu.settings.ExtensionUpdateRunner
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.extension.ExtensionUpdateNotifier
import eu.kanade.tachiyomi.extension.InstallStep
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.util.system.notificationBuilder
import kotlinx.coroutines.flow.collect
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Installs pending extension updates on a schedule, without asking.
 *
 * Runs as a foreground worker, and not for the progress notification's sake: starting a foreground
 * service from the background is barred from Android 12, and a running foreground worker is the
 * exemption that lets [eu.kanade.tachiyomi.extension.util.ExtensionInstallService] start at all
 * from here. Without it every install in this path dies on ForegroundServiceStartNotAllowedException.
 *
 * Extensions the system will not replace silently are counted, not forced: see
 * [ExtensionUpdateNotifier.notifyAutoUpdateResult].
 */
class ExtensionUpdateWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null, 0, 0)

    private fun foregroundInfo(name: String?, index: Int, total: Int): ForegroundInfo {
        val notification = applicationContext.notificationBuilder(
            Notifications.CHANNEL_EXTENSIONS_UPDATE
        ) {
            setSmallIcon(R.drawable.ic_round_sync_24)
            setContentTitle(applicationContext.getString(R.string.updating_extensions))
            if (name != null) {
                setContentText("$name (${index + 1}/$total)")
                setProgress(total, index, false)
            } else {
                setProgress(0, 0, true)
            }
            setOngoing(true)
            setShowWhen(false)
        }.build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                Notifications.ID_EXTENSION_AUTO_UPDATE_PROGRESS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(Notifications.ID_EXTENSION_AUTO_UPDATE_PROGRESS, notification)
        }
    }

    override suspend fun doWork(): Result {
        if (!PrefManager.getVal<Boolean>(PrefName.AutoUpdateExtensions)) {
            Logger.log("ExtensionUpdateWorker: auto-update off, nothing to do")
            return Result.success()
        }

        // Checked before the repo fetch, not after: if this is not a safe moment there is nothing
        // to be gained from the network round trip, and retry means WorkManager backs off and asks
        // again rather than skipping this cycle entirely.
        val verdict = ExtensionUpdateGate.check()
        if (verdict is ExtensionUpdateGate.Verdict.Blocked) {
            Logger.log("ExtensionUpdateWorker: deferred, ${verdict.reason}")
            return Result.retry()
        }

        // hasUpdate is only as fresh as the last repo fetch, and the point of a scheduled run is to
        // catch what appeared since. A repo that fails to answer leaves its extensions looking
        // up to date, which is the right way to be wrong here.
        runCatching {
            Injekt.get<AnimeExtensionManager>().findAvailableExtensions()
            Injekt.get<MangaExtensionManager>().findAvailableExtensions()
            Injekt.get<NovelExtensionManager>().findAvailableExtensions()
        }.onFailure { Logger.log("ExtensionUpdateWorker: refresh failed: $it") }

        val pending = ExtensionUpdateRunner.pendingUpdates()
        if (pending.isEmpty()) {
            Logger.log("ExtensionUpdateWorker: no updates pending")
            return Result.success()
        }

        // Anything already known to need confirmation at this version cannot succeed here, so
        // running it again would only produce the same notification every cycle.
        val toRun = RefusedExtensionUpdates.withoutAlreadyRefused(pending)
        if (toRun.isEmpty()) {
            Logger.log("ExtensionUpdateWorker: ${pending.size} pending, all awaiting confirmation")
            return Result.success()
        }

        runCatching { setForeground(getForegroundInfo()) }.onFailure {
            // Nothing can be installed from the background without it, so stop rather than run the
            // whole sequence into an exception per extension.
            Logger.log("ExtensionUpdateWorker: could not go foreground, retrying later: $it")
            return Result.retry()
        }

        val updated = mutableListOf<String>()
        val needsConfirmation = mutableListOf<String>()
        val failed = mutableListOf<String>()

        val refused = mutableListOf<ani.dantotsu.settings.UpdateItem>()

        ExtensionUpdateRunner.run(toRun, unattended = true).collect { progress ->
            when (progress) {
                is ExtensionUpdateRunner.Progress.Started ->
                    runCatching {
                        setForeground(
                            foregroundInfo(progress.item.name, progress.index, progress.total)
                        )
                    }

                is ExtensionUpdateRunner.Progress.Finished -> when (progress.step) {
                    InstallStep.Installed -> updated += progress.item.name
                    InstallStep.RequiresUserAction -> {
                        needsConfirmation += progress.item.name
                        refused += progress.item
                    }
                    else -> failed += progress.item.name
                }

                is ExtensionUpdateRunner.Progress.Failed -> {
                    Logger.log("ExtensionUpdateWorker: ${progress.item.name} failed: ${progress.error}")
                    failed += progress.item.name
                }
            }
        }

        RefusedExtensionUpdates.record(refused, pending)

        Logger.log(
            "ExtensionUpdateWorker: ${updated.size} updated, " +
                "${needsConfirmation.size} need confirmation, ${failed.size} failed"
        )
        ExtensionUpdateNotifier(applicationContext).notifyAutoUpdateResult(updated, needsConfirmation)
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "extension_auto_update"
    }
}
