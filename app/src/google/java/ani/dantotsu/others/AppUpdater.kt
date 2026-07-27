package ani.dantotsu.others

import android.content.Context
import android.os.Environment
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import ani.dantotsu.BuildConfig
import ani.dantotsu.Mapper
import ani.dantotsu.R
import ani.dantotsu.client
import ani.dantotsu.connections.comments.CommentsAPI
import ani.dantotsu.currContext
import ani.dantotsu.decodeBase64ToString
import ani.dantotsu.px
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.snackString
import ani.dantotsu.tryWithSuspend
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

    private suspend fun fetchUpdateInfo(repo: String, isDebug: Boolean): Triple<String, String, String?>? {
        return try {
            fetchFromGithub(repo, isDebug)
        } catch (e: Exception) {
            Logger.log("Github fetch failed, trying fallback: ${e.message}")
            try {
                val (md, version) = fetchFromFallback(isDebug)
                Triple(md, version, null)
            } catch (e: Exception) {
                Logger.log("Fallback fetch failed: ${e.message}")
                null
            }
        }
    }

    private suspend fun fetchFromGithub(repo: String, isDebug: Boolean): Triple<String, String, String?> {
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
            Triple(r.body ?: "", v.ifEmpty { throw Exception("Weird Version : ${r.tagName}") }, r.name)
        } else {
            val r = res.filter { !it.prerelease }.filter { !it.tagName.contains("fdroid") }
                .maxByOrNull {
                    it.timeStamp()
                } ?: throw Exception("No Stable Release Found")
            val v = r.tagName.removePrefix("v")
            Triple(r.body ?: "", v.ifEmpty { throw Exception("Weird Version : ${r.tagName}") }, r.name)
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

    suspend fun check(activity: FragmentActivity, post: Boolean = false) {
        // Only stable (release) builds get update prompts. Beta (debug) and alpha builds are never
        // published to GitHub releases, so checking only yields spurious popups (and would ping the
        // fallback server). Bail early — a manual check still gets a "no update" reply.
        if (!TEST_UPDATE_FLOW && BuildConfig.BUILD_TYPE != "release") {
            if (post) snackString(currContext()?.getString(R.string.no_update_found))
            return
        }
        if (post) snackString(currContext()?.getString(R.string.checking_for_update))
        val repo = activity.getString(R.string.repo)
        tryWithSuspend {
            val (md, version, releaseName) = fetchUpdateInfo(repo, isDebugChannel) ?: return@tryWithSuspend

            Logger.log("Git Version : $version")
            val dontShow = PrefManager.getCustomVal("dont_ask_for_update_$version", false)
            val shouldShow = TEST_UPDATE_FLOW || (compareVersion(version) && !dontShow)
            if (shouldShow && !activity.isDestroyed) activity.runOnUiThread {
                // Re-checked on the UI thread: the check now runs early enough to race activity
                // startup, and committing the sheet against a saved state would throw.
                if (activity.isDestroyed || activity.isFinishing ||
                    activity.supportFragmentManager.isStateSaved
                ) return@runOnUiThread
                CustomBottomDialog.newInstance().apply {
                    val updateLabel = "${if (BuildConfig.DEBUG) "Beta " else ""}Update " + currContext()!!.getString(R.string.available)
                    setTitleText(if (!releaseName.isNullOrBlank()) "$updateLabel — $releaseName" else updateLabel)
                    // Patch notes live in AppUpdateActivity now, so the sheet has to spell out both
                    // what's changing and what the button is about to do.
                    addView(
                        TextView(activity).apply {
                            text = activity.getString(
                                R.string.update_sheet_message,
                                BuildConfig.VERSION_NAME,
                                version
                            )
                            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
                            setPadding(0, 16f.px, 0, 16f.px)
                        }
                    )

                    setCheck(
                        currContext()!!.getString(R.string.dont_show_again, version),
                        false
                    ) { isChecked ->
                        if (isChecked) {
                            PrefManager.setCustomVal("dont_ask_for_update_$version", true)
                        }
                    }
                    setPositiveButton(currContext()!!.getString(R.string.lets_go)) {
                        activity.startActivity(
                            AppUpdateActivity.newIntent(activity, version, md, repo)
                        )
                        dismiss()
                    }
                    setNegativeButton(currContext()!!.getString(R.string.cope)) {
                        dismiss()
                    }
                    show(activity.supportFragmentManager, "dialog")
                }
            } else {
                if (post) snackString(currContext()?.getString(R.string.no_update_found))
            }
        }
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