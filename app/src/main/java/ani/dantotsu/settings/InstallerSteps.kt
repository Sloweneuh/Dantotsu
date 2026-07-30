package ani.dantotsu.settings

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import ani.dantotsu.R
import ani.dantotsu.connections.crashlytics.CrashlyticsInterface
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.extension.InstallStep
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class InstallerSteps(
    private val notificationManager: NotificationManager,
    private val context: Context
) {

    /**
     * Last step seen. The install stream also terminates on cancellation and failure, so
     * [onComplete] needs this to tell those apart from an actual install.
     */
    private var lastStep: InstallStep? = null

    fun onInstallStep(installStep: InstallStep, extra: () -> Unit) {
        lastStep = installStep
        val builder = NotificationCompat.Builder(
            context,
            Notifications.CHANNEL_DOWNLOADER_PROGRESS
        )
            .setSmallIcon(R.drawable.ic_round_sync_24)
            .setContentTitle(context.getString(R.string.installing_extension))
            .setContentText(context.getString(R.string.install_step, installStep))
            .setPriority(NotificationCompat.PRIORITY_LOW)
        notificationManager.notify(1, builder.build())
        extra()
    }

    fun onError(error: Throwable, extra: () -> Unit) {
        Injekt.get<CrashlyticsInterface>().logException(error)
        Logger.log(error)
        val builder = NotificationCompat.Builder(
            context,
            Notifications.CHANNEL_DOWNLOADER_ERROR
        )
            .setSmallIcon(R.drawable.ic_round_info_24)
            .setContentTitle(context.getString(R.string.installation_failed, error.message))
            .setContentText(context.getString(R.string.error_message, error.message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        notificationManager.notify(1, builder.build())
        snackString(context.getString(R.string.installation_failed, error.message))
        extra()
    }

    fun onComplete(extra: () -> Unit) {
        notificationManager.cancel(1)
        // Completion on its own means nothing: the stream is taken until the first completed step,
        // which is Installed on success, Idle when the user cancels and Error on failure.
        when (lastStep) {
            InstallStep.Installed -> snackString(context.getString(R.string.extension_installed))
            InstallStep.Idle -> snackString(context.getString(R.string.installation_cancelled))
            InstallStep.Error -> snackString(context.getString(R.string.installation_failed_short))
            else -> {}
        }
        extra()
    }
}