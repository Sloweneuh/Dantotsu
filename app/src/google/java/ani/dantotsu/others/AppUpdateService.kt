package ani.dantotsu.others

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import ani.dantotsu.R
import ani.dantotsu.formatBytes
import ani.dantotsu.formatDownloadSpeed
import ani.dantotsu.formatEta
import ani.dantotsu.okHttpClient
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.data.notification.Notifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Downloads the update APK in the background so it survives leaving [AppUpdateActivity]. Progress
 * goes to both [AppUpdateDownloader] (for the screen, while it's open) and an ongoing notification,
 * which turns into a "ready to install" notification with an Install action once the file lands.
 */
class AppUpdateService : Service() {

    private lateinit var notificationManager: NotificationManagerCompat
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var downloadJob: Job? = null
    private var currentCall: Call? = null

    private var version = ""
    private var changelog = ""
    private var repo = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = NotificationManagerCompat.from(this)
        ContextCompat.registerReceiver(
            this,
            cancelReceiver,
            IntentFilter(ACTION_CANCEL),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requested = intent?.getStringExtra(EXTRA_VERSION).orEmpty()
        if (requested.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        // A restart for the download already in flight — keep the existing job, don't queue a second.
        if (downloadJob?.isActive == true && requested == version) {
            startForegroundCompat(progressNotification(indeterminate = true))
            return START_NOT_STICKY
        }

        // A download for some other version is still running — it loses, only one at a time.
        downloadJob?.cancel()
        currentCall?.cancel()

        version = requested
        changelog = intent?.getStringExtra(EXTRA_CHANGELOG).orEmpty()
        repo = intent?.getStringExtra(EXTRA_REPO) ?: getString(R.string.repo)

        startForegroundCompat(progressNotification(indeterminate = true))
        AppUpdateDownloader.setDownloading(version)

        downloadJob = scope.launch {
            val target = AppUpdateDownloader.apkFile(this@AppUpdateService, version)
            try {
                target.parentFile?.mkdirs()
                target.delete()
                download(target)
                AppUpdateDownloader.setReady(version, target.length())
                finishWith(readyNotification(target))
            } catch (e: CancellationException) {
                target.delete()
                throw e
            } catch (e: Exception) {
                Logger.log(e)
                target.delete()
                AppUpdateDownloader.setFailed(version)
                finishWith(failedNotification())
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        currentCall?.cancel()
        scope.cancel()
        unregisterReceiver(cancelReceiver)
        super.onDestroy()
    }

    /** Leaves [notification] up after the service is gone, then stops. */
    private fun finishWith(notification: android.app.Notification) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        notify(notification)
        stopSelf()
    }

    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_CANCEL) return
            downloadJob?.cancel()
            currentCall?.cancel()
            AppUpdateDownloader.reset()
            ServiceCompat.stopForeground(this@AppUpdateService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            notificationManager.cancel(NOTIFICATION_ID)
            stopSelf()
        }
    }

    /** Streams the release APK into [target], trying each source until one serves the file. */
    private suspend fun download(target: File) {
        var lastError: Exception? = null
        for (source in AppUpdateDownloader.sources(repo, version)) {
            coroutineContext.ensureActive()
            val url = try {
                source()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.log("Failed to resolve an APK url: ${e.message}")
                null
            } ?: continue

            try {
                downloadTo(url, target)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.log("APK download from $url failed: ${e.message}")
                lastError = e
                target.delete()
            }
        }
        throw lastError ?: IOException(getString(R.string.update_apk_not_found))
    }

    private suspend fun downloadTo(url: String, target: File) {
        val call = okHttpClient.newCall(Request.Builder().url(url).build())
        currentCall = call
        // Written under a temp name so an interrupted download is never mistaken for a usable APK.
        val partial = File(target.parentFile, target.name + ".tmp")
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val total = response.body.contentLength()
                val startTime = System.currentTimeMillis()
                var done = 0L
                var lastReport = 0L

                response.body.byteStream().use { input ->
                    FileOutputStream(partial).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            done += read

                            val now = System.currentTimeMillis()
                            if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                                lastReport = now
                                report(done, total, now - startTime)
                            }
                        }
                    }
                }
                if (total > 0 && done != total) throw IOException("Incomplete download")
            }
            if (!partial.renameTo(target)) throw IOException("Could not move the downloaded APK")
        } finally {
            currentCall = null
            partial.delete()
        }
    }

    /** Publishes progress to the screen and the ongoing notification. */
    private fun report(done: Long, total: Long, elapsedMs: Long) {
        val speed = if (elapsedMs > 0) done * 1000 / elapsedMs else 0
        val etaMs = if (speed > 0 && total > 0) (total - done) * 1000 / speed else -1L
        val percent = if (total > 0) (done * 100 / total).toInt() else 0

        AppUpdateDownloader.setProgress(version, percent, done, total, speed, etaMs)
        notify(progressNotification(percent, done, total, speed, etaMs, total <= 0))
    }

    private fun baseNotification() =
        NotificationCompat.Builder(this, Notifications.CHANNEL_APP_UPDATE)
            .setSmallIcon(R.drawable.ic_download_24)
            .setContentIntent(activityIntent())

    private fun progressNotification(
        percent: Int = 0,
        done: Long = 0,
        total: Long = 0,
        speedBps: Long = 0,
        etaMs: Long = -1,
        indeterminate: Boolean = false
    ): android.app.Notification {
        val parts = mutableListOf<String>()
        if (total > 0) parts.add("${formatBytes(done)} / ${formatBytes(total)}")
        else if (done > 0) parts.add(formatBytes(done))
        formatDownloadSpeed(speedBps).takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        formatEta(etaMs).takeIf { it.isNotEmpty() }?.let { parts.add("ETA $it") }

        return baseNotification()
            .setContentTitle(getString(R.string.downloading_app_version, version))
            .setContentText(
                parts.joinToString(" · ").ifEmpty { getString(R.string.downloading) }
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, indeterminate)
            .setOngoing(true)
            .addAction(
                R.drawable.ic_round_close_24,
                getString(R.string.cancel),
                PendingIntent.getBroadcast(
                    this, 0,
                    Intent(ACTION_CANCEL).setPackage(packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()
    }

    private fun readyNotification(file: File): android.app.Notification =
        baseNotification()
            .setContentTitle(getString(R.string.update_ready_title))
            .setContentText(
                getString(R.string.update_ready_text, version, formatBytes(file.length()))
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .addAction(
                R.drawable.ic_download_24,
                getString(R.string.install_update),
                PendingIntent.getActivity(
                    this, 1,
                    AppUpdateDownloader.installIntent(this, file),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()

    private fun failedNotification(): android.app.Notification =
        baseNotification()
            .setContentTitle(getString(R.string.update_download_failed))
            .setContentText(getString(R.string.update_version_name, version))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

    /** Reopens the update screen, patch notes intact. */
    private fun activityIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        AppUpdateActivity.newIntent(this, version, changelog, repo),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notify(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val EXTRA_VERSION = "version"
        const val EXTRA_CHANGELOG = "changelog"
        const val EXTRA_REPO = "repo"
        const val ACTION_CANCEL = "ani.dantotsu.action.CANCEL_UPDATE_DOWNLOAD"

        private const val NOTIFICATION_ID = 1105
        private const val BUFFER_SIZE = 8 * 1024
        private const val PROGRESS_INTERVAL_MS = 500L
    }
}
