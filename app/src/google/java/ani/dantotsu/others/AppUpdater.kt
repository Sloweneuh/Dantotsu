package ani.dantotsu.others

import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import java.util.concurrent.TimeUnit
import android.os.Environment
import androidx.fragment.app.FragmentActivity
import ani.dantotsu.BuildConfig
import ani.dantotsu.Mapper
import ani.dantotsu.R
import ani.dantotsu.client
import ani.dantotsu.connections.comments.CommentsAPI
import ani.dantotsu.currContext
import ani.dantotsu.decodeBase64ToString
import ani.dantotsu.snackString
import ani.dantotsu.tryWithSuspend
import ani.dantotsu.util.AppNotices
import ani.dantotsu.util.Logger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

object AppUpdater {
    /**
     * TEMPORARY test switch — set back to `false` before shipping.
     *
     * With it on, a non-release build gets the full update flow: the prompt shows regardless of
     * build type and regardless of whether the release is actually newer, and everything resolves
     * against the latest *stable* release so there's a real APK to download and install. Trigger it
     * by long-pressing the logo in Settings (it also fires on launch when "Check for Updates" is on).
     */
    const val TEST_UPDATE_FLOW = false

    /** Release channel to resolve against; the test switch forces the stable one. */
    internal val isDebugChannel: Boolean get() = BuildConfig.DEBUG && !TEST_UPDATE_FLOW

    private val fallbackStableUrl: String
        get() = "aHR0cHM6Ly9hcGkuZGFudG90c3UuYXBwL3VwZGF0ZXMvc3RhYmxl".decodeBase64ToString()
    private val fallbackBetaUrl: String
        get() = "aHR0cHM6Ly9hcGkuZGFudG90c3UuYXBwL3VwZGF0ZXMvYmV0YQ==".decodeBase64ToString()

    @Serializable
    data class FallbackResponse(
        val version: String,
        val changelog: String,
        val downloadUrl: String? = null
    )

    /** A release's notes, version and name, and when it came out (0 when the source doesn't say). */
    private data class UpdateInfo(
        val changelog: String,
        val version: String,
        val releaseName: String?,
        val releasedAt: Long = 0,
    )

    private suspend fun fetchUpdateInfo(repo: String, isDebug: Boolean): UpdateInfo? {
        return try {
            fetchFromGithub(repo, isDebug)
        } catch (e: Exception) {
            Logger.log("Github fetch failed, trying fallback: ${e.message}")
            try {
                val (md, version) = fetchFromFallback(isDebug)
                UpdateInfo(md, version, null)
            } catch (e: Exception) {
                Logger.log("Fallback fetch failed: ${e.message}")
                null
            }
        }
    }

    private suspend fun fetchFromGithub(repo: String, isDebug: Boolean): UpdateInfo {
        val res = client.get("https://api.github.com/repos/$repo/releases")
            .parsed<JsonArray>().map {
                Mapper.json.decodeFromJsonElement<GithubResponse>(it)
            }
        return if (isDebug) {
            val r = res.filter { it.prerelease }.filter { !it.tagName.contains("fdroid") }
                .maxByOrNull {
                    it.timeStamp()
                } ?: throw Exception("No Pre Release Found")
            val v = r.tagName.removePrefix("v")
            UpdateInfo(r.body ?: "", v.ifEmpty { throw Exception("Weird Version : ${r.tagName}") }, r.name, r.timeStamp())
        } else {
            val r = res.filter { !it.prerelease }.filter { !it.tagName.contains("fdroid") }
                .maxByOrNull {
                    it.timeStamp()
                } ?: throw Exception("No Stable Release Found")
            val v = r.tagName.removePrefix("v")
            UpdateInfo(r.body ?: "", v.ifEmpty { throw Exception("Weird Version : ${r.tagName}") }, r.name, r.timeStamp())
        }
    }

    private suspend fun fetchFromFallback(isDebug: Boolean): Pair<String, String> {
        val url = if (isDebug) fallbackBetaUrl else fallbackStableUrl
        val response = CommentsAPI.requestBuilder().get(url).parsed<FallbackResponse>()
        return response.changelog to response.version
    }

    /*
     * The three APK sources, in the order [AppUpdateService] tries them. They're kept separate so
     * the service can fall through to the next one only when a download actually fails — the first
     * costs nothing, the other two each cost a network round trip.
     */

    /**
     * Canonical GitHub releases download URL, derived from the version alone. Stable releases
     * publish the APK under the predictable `app-google-release.apk` name used by CI; pre-releases
     * don't, so they have to go through the releases API.
     */
    internal fun constructedApkUrl(repo: String, version: String, isDebug: Boolean): String? =
        if (isDebug) null
        else "https://github.com/$repo/releases/download/$version/app-google-release.apk"

    internal suspend fun githubApkUrl(repo: String, version: String): String? {
        // Always use the releases list endpoint and filter by tag_name to find the matching release
        try {
            val res = client.get("https://api.github.com/repos/$repo/releases")
                .parsed<JsonArray>().map { Mapper.json.decodeFromJsonElement<GithubResponse>(it) }

            // Find exact match for tag (either with or without a leading 'v')
            val match = res.firstOrNull { it.tagName == version || it.tagName == "v$version" }
                ?: res.firstOrNull { it.tagName.equals(version, ignoreCase = true) || it.tagName.equals("v$version", ignoreCase = true) }

            val apk = match?.assets?.firstOrNull { it.browserDownloadURL.endsWith(".apk") }?.browserDownloadURL
            if (!apk.isNullOrBlank()) return apk

        } catch (e: Exception) {
            Logger.log("Github releases list fetch failed: ${e.message}")
        }

        return null
    }

    internal suspend fun fallbackApkUrl(version: String, isDebug: Boolean): String? {
        val url = if (isDebug) fallbackBetaUrl else fallbackStableUrl
        return CommentsAPI.requestBuilder().get("$url/$version").parsed<FallbackResponse>().downloadUrl
    }

    /** A release newer than this build, with what the update screen needs to show it. */
    data class Update(val version: String, val changelog: String, val repo: String, val releasedAt: Long)

    // Only stable (release) builds get update prompts. Beta (debug) and alpha builds are never
    // published to GitHub releases, so checking only yields spurious prompts (and would ping the
    // fallback server).
    private val checksForUpdates: Boolean
        get() = TEST_UPDATE_FLOW || BuildConfig.BUILD_TYPE == "release"

    /** The newest release if it's newer than this build; null when it isn't, or on any failure. */
    suspend fun findUpdate(context: Context): Update? {
        if (!checksForUpdates) return null
        val repo = context.getString(R.string.repo)
        return tryWithSuspend {
            val info = fetchUpdateInfo(repo, isDebugChannel) ?: return@tryWithSuspend null
            Logger.log("Git Version : ${info.version}")
            if (!TEST_UPDATE_FLOW && !compareVersion(info.version)) return@tryWithSuspend null
            Update(info.version, info.changelog, repo, info.releasedAt)
        }
    }

    fun openIntent(context: Context, update: Update): Intent = AppUpdateActivity.newIntent(
        context, update.version, update.changelog, update.repo, update.releasedAt
    )

    suspend fun check(activity: FragmentActivity, post: Boolean = false) {
        if (!checksForUpdates) {
            if (post) snackString(currContext()?.getString(R.string.no_update_found))
            return
        }
        if (post) snackString(currContext()?.getString(R.string.checking_for_update))
        val update = findUpdate(activity)
        if (update == null) {
            if (post) snackString(currContext()?.getString(R.string.no_update_found))
            return
        }
        // A manual check asks to see it, even if the banner was already swiped away.
        AppUpdateNotice.offer(update.version, again = post) { it.startActivity(openIntent(it, update)) }
        // Raised here rather than on the next screen change; the check runs early enough to race
        // activity startup, so whether there is still a screen is asked again.
        activity.runOnUiThread {
            if (!activity.isDestroyed && !activity.isFinishing) AppNotices.showPending(activity)
        }
    }

    // ---- background checks ----

    private const val BACKGROUND_WORK = "app_update_check"
    private const val BACKGROUND_INTERVAL_HOURS = 12L

    /**
     * Puts [AppUpdateCheckWorker] on its schedule when both update switches are on, or takes it off.
     * Call whenever either changes, and once at startup; the unique work is UPDATEd, so a launch with
     * nothing changed keeps the countdown it is already on.
     */
    fun scheduleBackgroundCheck(context: Context) {
        val workManager = WorkManager.getInstance(context)
        val on = PrefManager.getVal<Boolean>(PrefName.CheckUpdate) &&
            PrefManager.getVal<Boolean>(PrefName.CheckUpdateBackground)
        if (!on || !checksForUpdates) {
            workManager.cancelUniqueWork(BACKGROUND_WORK)
            return
        }
        workManager.enqueueUniquePeriodicWork(
            BACKGROUND_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequest.Builder(
                AppUpdateCheckWorker::class.java, BACKGROUND_INTERVAL_HOURS, TimeUnit.HOURS
            ).setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            ).build(),
        )
    }

    /**
     * The system notification for [update]: tapping it, or View, opens the update screen; the other
     * action downloads it in [AppUpdateService], or installs it once that is done.
     */
    fun notifyUpdate(context: Context, update: Update) {
        val open = PendingIntent.getActivity(
            context, 0, openIntent(context, update),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val apk = AppUpdateDownloader.apkFile(context, update.version)
        val downloaded = AppUpdateDownloader.stateOf(context, update.version).status == UpdateStatus.READY
        val second = if (downloaded) {
            NotificationCompat.Action(
                R.drawable.ic_round_system_update_24,
                context.getString(R.string.install_update),
                PendingIntent.getActivity(
                    context, 1, AppUpdateDownloader.installIntent(context, apk),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        } else {
            NotificationCompat.Action(
                R.drawable.ic_download_arrow_24,
                context.getString(R.string.download_update),
                // Straight into the download service: a notification action may start one.
                PendingIntent.getForegroundService(
                    context, 2,
                    AppUpdateDownloader.serviceIntent(context, update.version, update.changelog, update.repo),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }
        context.notify(
            Notifications.ID_APP_UPDATE_PROMPT,
            context.notificationBuilder(Notifications.CHANNEL_APP_UPDATE) {
                setSmallIcon(R.drawable.notification_icon)
                setContentTitle(context.getString(R.string.app_update_available))
                setContentText(
                    context.getString(R.string.app_update_notice_subtitle, update.version, BuildConfig.VERSION_NAME)
                )
                setContentIntent(open)
                setAutoCancel(true)
                addAction(R.drawable.ic_round_new_releases_24, context.getString(R.string.view), open)
                addAction(second)
            }.build()
        )
    }

    // Only reached by release builds (see check()). Plain semantic compare; VERSION_NAME has no suffix.
    private fun compareVersion(version: String): Boolean {
        fun parseVersionSegments(ver: String): List<Int> =
            ver.split(".").mapNotNull { it.toIntOrNull() }

        val newSegments = parseVersionSegments(version)
        val currSegments = parseVersionSegments(BuildConfig.VERSION_NAME)
        // Compare segment by segment (proper semantic versioning).
        for (i in 0 until maxOf(newSegments.size, currSegments.size)) {
            val newSeg = newSegments.getOrNull(i) ?: 0
            val currSeg = currSegments.getOrNull(i) ?: 0
            if (newSeg != currSeg) return newSeg > currSeg
        }
        // Versions are equal.
        return false
    }


    fun cleanupDownloadedApkFiles(context: Context) {
        // Public Downloads is where older versions parked their APKs via DownloadManager.
        deleteApksIn(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
        deleteApksIn(AppUpdateDownloader.updateDir(context))
    }

    private fun deleteApksIn(dir: File) {
        try {
            dir.listFiles { file ->
                file.isFile && file.name.startsWith("Dantotsu ") && file.name.contains(".apk")
            }?.forEach { apk ->
                try {
                    apk.delete()
                } catch (e: Exception) {
                    Logger.log("Failed to delete stale APK ${apk.name}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Logger.log("Failed to cleanup downloaded APK files: ${e.message}")
        }
    }

    val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)

    @Serializable
    data class GithubResponse(
        @SerialName("html_url")
        val htmlUrl: String,
        @SerialName("tag_name")
        val tagName: String,
        val name: String? = null,
        val prerelease: Boolean,
        @SerialName("created_at")
        val createdAt: String,
        val body: String? = null,
        val assets: List<Asset>? = null
    ) {
        @Serializable
        data class Asset(
            @SerialName("browser_download_url")
            val browserDownloadURL: String
        )

        fun timeStamp(): Long {
            return dateFormat.parse(createdAt)!!.time
        }
    }
}