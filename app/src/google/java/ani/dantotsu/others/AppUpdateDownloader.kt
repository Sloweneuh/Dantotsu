package ani.dantotsu.others

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import ani.dantotsu.okHttpClient
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

enum class UpdateStatus { IDLE, DOWNLOADING, READY, FAILED }

data class UpdateDownload(
    val status: UpdateStatus = UpdateStatus.IDLE,
    val version: String = "",
    val percent: Int = 0,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val speedBps: Long = 0,
    val etaMs: Long = -1,
)

/**
 * Process-wide state of the app update download, in the same spirit as
 * [ani.dantotsu.download.DownloadTracker]: [AppUpdateService] reports into it and
 * [AppUpdateActivity] renders it. The download lives in the service, so closing the screen leaves
 * it running behind its notification.
 */
object AppUpdateDownloader {
    private val _state = MutableStateFlow(UpdateDownload())
    val state: StateFlow<UpdateDownload> = _state.asStateFlow()

    /** Where update APKs are kept — cleared on launch by [AppUpdater.cleanupDownloadedApkFiles]. */
    fun updateDir(context: Context): File = File(context.cacheDir, "updates")

    fun apkFile(context: Context, version: String): File =
        File(updateDir(context), "Dantotsu $version.apk")

    /**
     * State as it applies to [version]: the live download when that's the one running, otherwise
     * read off disk so an APK fetched on an earlier visit is still offered for install.
     */
    fun stateOf(context: Context, version: String): UpdateDownload {
        val current = _state.value
        if (current.version == version && current.status != UpdateStatus.IDLE) return current
        return UpdateDownload(
            status = if (apkFile(context, version).exists()) UpdateStatus.READY
            else UpdateStatus.IDLE,
            version = version
        )
    }

    fun start(context: Context, version: String, changelog: String, repo: String) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, AppUpdateService::class.java)
                .putExtra(AppUpdateService.EXTRA_VERSION, version)
                .putExtra(AppUpdateService.EXTRA_CHANGELOG, changelog)
                .putExtra(AppUpdateService.EXTRA_REPO, repo)
        )
    }

    fun cancel(context: Context) {
        context.sendBroadcast(
            Intent(AppUpdateService.ACTION_CANCEL).setPackage(context.packageName)
        )
    }

    /**
     * The release APK's sources, in the order they're tried. The first costs nothing; the other
     * two each cost a network round trip, so they're only resolved once an earlier one has failed.
     */
    internal fun sources(repo: String, version: String): List<suspend () -> String?> = listOf(
        { AppUpdater.constructedApkUrl(repo, version, AppUpdater.isDebugChannel) },
        { AppUpdater.githubApkUrl(repo, version) },
        { AppUpdater.fallbackApkUrl(version, AppUpdater.isDebugChannel) }
    )

    /**
     * Size of the APK ahead of downloading it, so the screen can show it next to the button.
     * Returns 0 when no source answers with a length — the download itself still works, the UI
     * just goes without the up-front size.
     */
    suspend fun probeSize(repo: String, version: String): Long = withContext(Dispatchers.IO) {
        for (source in sources(repo, version)) {
            val url = try {
                source()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: continue
            try {
                okHttpClient.newCall(Request.Builder().url(url).head().build()).execute()
                    .use { response ->
                        val length = response.header("Content-Length")?.toLongOrNull() ?: 0L
                        if (response.isSuccessful && length > 0) return@withContext length
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.log("APK size probe for $url failed: ${e.message}")
            }
        }
        0L
    }

    /** Hands a downloaded APK to the package installer. */
    fun installIntent(context: Context, file: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        }
    }

    internal fun setDownloading(version: String) {
        _state.value = UpdateDownload(status = UpdateStatus.DOWNLOADING, version = version)
    }

    internal fun setProgress(
        version: String,
        percent: Int,
        bytesDone: Long,
        bytesTotal: Long,
        speedBps: Long,
        etaMs: Long
    ) {
        _state.value = UpdateDownload(
            status = UpdateStatus.DOWNLOADING,
            version = version,
            percent = percent,
            bytesDone = bytesDone,
            bytesTotal = bytesTotal,
            speedBps = speedBps,
            etaMs = etaMs
        )
    }

    internal fun setReady(version: String, size: Long) {
        _state.value = UpdateDownload(
            status = UpdateStatus.READY,
            version = version,
            percent = 100,
            bytesDone = size,
            bytesTotal = size
        )
    }

    internal fun setFailed(version: String) {
        _state.value = UpdateDownload(status = UpdateStatus.FAILED, version = version)
    }

    internal fun reset() {
        _state.value = UpdateDownload()
    }
}
