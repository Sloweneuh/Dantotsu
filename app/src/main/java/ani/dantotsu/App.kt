package ani.dantotsu

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.multidex.MultiDex
import androidx.multidex.MultiDexApplication
import ani.dantotsu.addons.download.DownloadAddonManager
import ani.dantotsu.addons.torrent.TorrentAddonManager
import ani.dantotsu.connections.handoff.GlobalHandoffReceiver
import ani.dantotsu.aniyomi.anime.custom.AppModule
import ani.dantotsu.aniyomi.anime.custom.PreferenceModule
import ani.dantotsu.connections.comments.CommentsAPI
import ani.dantotsu.connections.crashlytics.CrashlyticsInterface
import ani.dantotsu.notifications.TaskScheduler
import ani.dantotsu.notifications.WorkManagerScheduler
import ani.dantotsu.notifications.AlarmManagerScheduler
import ani.dantotsu.notifications.firebase.FirebaseBackgroundScheduler
import ani.dantotsu.others.DisabledReports
import ani.dantotsu.others.AppUpdater
import ani.dantotsu.parsers.AnimeSources
import ani.dantotsu.parsers.MangaSources
import ani.dantotsu.parsers.NovelSources
import ani.dantotsu.parsers.novel.NovelExtensionManager
import ani.dantotsu.settings.SettingsActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.FinalExceptionHandler
import ani.dantotsu.util.Logger
import com.google.android.material.color.DynamicColors
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import logcat.AndroidLogcatLogger
import logcat.LogPriority
import logcat.LogcatLogger
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get


@SuppressLint("StaticFieldLeak")
class App : MultiDexApplication() {
    private lateinit var animeExtensionManager: AnimeExtensionManager
    private lateinit var mangaExtensionManager: MangaExtensionManager
    private lateinit var novelExtensionManager: NovelExtensionManager
    private lateinit var torrentAddonManager: TorrentAddonManager
    private lateinit var downloadAddonManager: DownloadAddonManager

    override fun attachBaseContext(base: Context?) {
        // Apply language before anything else
        val context = base?.let { ani.dantotsu.util.LanguageHelper.applyLanguageToContext(it) } ?: base
        super.attachBaseContext(context)
        MultiDex.install(this)
    }

    init {
        instance = this
    }

    val mFTActivityLifecycleCallbacks = FTActivityLifecycleCallbacks()

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        PrefManager.init(this)
        AppUpdater.cleanupDownloadedApkFiles(this)

        val crashlytics =
            ani.dantotsu.connections.crashlytics.CrashlyticsFactory.createCrashlytics()
        Injekt.addSingletonFactory<CrashlyticsInterface> { crashlytics }
        crashlytics.initialize(this)
        Logger.init(this)
        Thread.setDefaultUncaughtExceptionHandler(FinalExceptionHandler())
        Logger.log(Log.WARN, "App: Logging started")

        Injekt.importModule(AppModule(this))
        Injekt.importModule(PreferenceModule(this))


        val useMaterialYou: Boolean = PrefManager.getVal(PrefName.UseMaterialYou)
        if (useMaterialYou) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }
        registerActivityLifecycleCallbacks(mFTActivityLifecycleCallbacks)

        // Check both the hardcoded constant and user preference
        val disableCrashReports = DisabledReports || PrefManager.getVal(PrefName.DisableCrashReports)
        crashlytics.setCrashlyticsCollectionEnabled(!disableCrashReports)

        if (!disableCrashReports) {
            (PrefManager.getVal(PrefName.SharedUserID) as Boolean).let {
                if (!it) return@let
                val dUsername = PrefManager.getVal(PrefName.DiscordUserName, null as String?)
                val aUsername = PrefManager.getVal(PrefName.AnilistUserName, null as String?)
                if (dUsername != null) {
                    crashlytics.setCustomKey("dUsername", dUsername)
                }
                if (aUsername != null) {
                    crashlytics.setCustomKey("aUsername", aUsername)
                }
            }
            crashlytics.setCustomKey("device Info", SettingsActivity.getDeviceInfo())
        }

        initializeNetwork()

        setupNotificationChannels()
        if (!LogcatLogger.isInstalled) {
            LogcatLogger.install(AndroidLogcatLogger(LogPriority.VERBOSE))
        }

        if (PrefManager.getVal<Int>(PrefName.CommentsEnabled) == 0) {
            if (BuildConfig.FLAVOR.contains("fdroid")) {
                PrefManager.setVal(PrefName.CommentsEnabled, 2)
            } else {
                PrefManager.setVal(PrefName.CommentsEnabled, 1)
            }
        }

        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            animeExtensionManager = Injekt.get()
            launch {
                animeExtensionManager.findAvailableExtensions()
            }
            Logger.log("Anime Extensions: ${animeExtensionManager.installedExtensionsFlow.first()}")
            AnimeSources.init(animeExtensionManager.installedExtensionsFlow)
        }
        scope.launch {
            mangaExtensionManager = Injekt.get()
            launch {
                mangaExtensionManager.findAvailableExtensions()
            }
            Logger.log("Manga Extensions: ${mangaExtensionManager.installedExtensionsFlow.first()}")
            MangaSources.init(mangaExtensionManager.installedExtensionsFlow)
        }
        scope.launch {
            novelExtensionManager = Injekt.get()
            launch {
                novelExtensionManager.findAvailableExtensions()
            }
            Logger.log("Novel Extensions: ${novelExtensionManager.installedExtensionsFlow.first()}")
            NovelSources.init(novelExtensionManager.installedExtensionsFlow)
        }
        GlobalScope.launch {
            torrentAddonManager = Injekt.get()
            downloadAddonManager = Injekt.get()
            torrentAddonManager.init()
            downloadAddonManager.init()
            if (PrefManager.getVal<Int>(PrefName.CommentsEnabled) == 1) {
                CommentsAPI.fetchAuthToken(this@App)
            }

            val useAlarmManager = PrefManager.getVal<Boolean>(PrefName.UseAlarmManager)
            val scheduler = TaskScheduler.create(this@App, useAlarmManager)
            try {
                scheduler.scheduleAllTasks(this@App)
                // Initialize Firebase background scheduler for better reliability
                // Only available in Google Play build variant
                if (BuildConfig.FLAVOR == "google") {
                    try {
                        FirebaseBackgroundScheduler.initialize(this@App)
                        Logger.log("Firebase background scheduler initialized")
                    } catch (e: Exception) {
                        Logger.log("Failed to initialize Firebase: ${e.message}")
                    }
                }
                // Ensure unread chapter checks are scheduled on both mechanisms
                // to improve reliability: WorkManager (periodic, survives reboots) and
                // AlarmManager (can wake device and run during Doze with setExactAndAllowWhileIdle).
                try {
                    val unreadInterval = PrefManager.getVal<Long>(PrefName.UnreadChapterNotificationInterval)
                    // Schedule on WorkManager
                    WorkManagerScheduler(this@App).scheduleRepeatingTask(TaskScheduler.TaskType.UNREAD_CHAPTER_NOTIFICATION, unreadInterval)
                    // Schedule on AlarmManager
                    AlarmManagerScheduler(this@App).scheduleRepeatingTask(TaskScheduler.TaskType.UNREAD_CHAPTER_NOTIFICATION, unreadInterval)
                } catch (e: Exception) {
                    Logger.log("Failed to schedule unread checks on both schedulers: ${e.message}")
                }
                // Trigger a single immediate run at startup to ensure user gets timely notifications
                try {
                    TaskScheduler.scheduleSingleWork(this@App)
                } catch (e: Exception) {
                    Logger.log("Failed to enqueue single work at startup: ${e.message}")
                }
            } catch (e: IllegalStateException) {
                Logger.log("Failed to schedule tasks")
                Logger.log(e)
            }
        }
    }

    private fun setupNotificationChannels() {
        try {
            Notifications.createChannels(this)
        } catch (e: Exception) {
            Logger.log("Failed to modify notification channels")
            Logger.log(e)
        }
    }

    inner class FTActivityLifecycleCallbacks : ActivityLifecycleCallbacks {
        var currentActivity: Activity? = null
        var lastActivity: String? = null
        private var lastUnreadChapterCheck = 0L
        private var lastCloudPull = 0L
        private var resumeCount = 0
        private var startedActivities = 0

        /** Every settings screen lives in this package, and none share a base class to hook. */
        private fun isSettingsScreen(activity: Activity) =
            activity.javaClass.name.startsWith("ani.dantotsu.settings.")

        override fun onActivityCreated(p0: Activity, p1: Bundle?) {
            lastActivity = p0.javaClass.simpleName
        }

        override fun onActivityStarted(p0: Activity) {
            currentActivity = p0
            // Stay discoverable for "Continue on another device" while the app is foreground.
            if (startedActivities++ == 0) {
                runCatching { GlobalHandoffReceiver.start(this@App) }
            }
        }

        override fun onActivityResumed(p0: Activity) {
            currentActivity = p0
            resumeCount++
            if (isSettingsScreen(p0)) ani.dantotsu.connections.sync.CloudSync.settingsUiOpen = true
            val now = System.currentTimeMillis()

            // Pull cloud settings when returning to the app, not just on cold start: the pull in
            // getUserId() only runs once per process, so a change made on another device otherwise
            // didn't land until this one was fully restarted. Rate-limited because onActivityResumed
            // fires on every screen change; the in-flight flags coalesce anything that slips past.
            // Skipped on the first resume of a launch, where the cold-start pull already covers us.
            if (resumeCount > 1 && now - lastCloudPull > CLOUD_PULL_INTERVAL_MS) {
                lastCloudPull = now
                runCatching {
                    ani.dantotsu.connections.sync.CloudSync.pullInBackground()
                    ani.dantotsu.connections.sync.ProgressSync.pullInBackground()
                    ani.dantotsu.connections.sync.ExtensionSettingsSync.pullInBackground()
                }
            }

            // Check for unread chapters when app comes to foreground
            // Only check if:
            // 1. It's been more than the configured interval since last check
            // 2. This isn't the very first activity resume (skip initial app launch)
            val timeSinceLastCheck = now - lastUnreadChapterCheck

            // Get user's configured interval (in minutes), with a minimum of 15 minutes
            val intervalMinutes = kotlin.math.max(
                PrefManager.getVal<Long>(PrefName.UnreadChapterNotificationInterval),
                15L
            )
            val intervalMillis = intervalMinutes * 60 * 1000L

            if (resumeCount > 1 && timeSinceLastCheck > intervalMillis) {
                lastUnreadChapterCheck = now
                Logger.log("FTActivityLifecycleCallbacks: Triggering unread chapter check (${intervalMinutes}min interval)")

                // Enqueue the worker rather than running the task inline. The worker carries
                // the DNS-readiness gate (avoids the transient post-wake UnknownHostException
                // spam), the 60s de-dup guard, and WorkManager's retry/backoff.
                try {
                    androidx.work.WorkManager.getInstance(this@App).enqueueUniqueWork(
                        ani.dantotsu.notifications.unread.UnreadChapterNotificationWorker.WORK_NAME + "_resume",
                        androidx.work.ExistingWorkPolicy.KEEP,
                        androidx.work.OneTimeWorkRequest.Builder(
                            ani.dantotsu.notifications.unread.UnreadChapterNotificationWorker::class.java
                        ).build()
                    )
                } catch (e: Exception) {
                    Logger.log("FTActivityLifecycleCallbacks: Error enqueuing unread chapter check: ${e.message}")
                }
            }
        }

        override fun onActivityPaused(p0: Activity) {
            if (isSettingsScreen(p0)) ani.dantotsu.connections.sync.CloudSync.settingsUiOpen = false
        }
        override fun onActivityStopped(p0: Activity) {
            if (--startedActivities <= 0) {
                startedActivities = 0
                runCatching { GlobalHandoffReceiver.stop() }
                // App backgrounded: push anything changed during this session. This coalesces a
                // whole foreground session's edits into a single upload (our "debounce"). Goes
                // through WorkManager so a swipe-away doesn't kill the upload mid-flight.
                ani.dantotsu.connections.sync.SyncPushWorker.enqueue(this@App)
            }
        }
        override fun onActivitySaveInstanceState(p0: Activity, p1: Bundle) {}
        override fun onActivityDestroyed(p0: Activity) {}
    }

    companion object {
        /** How often returning to the app may trigger a cloud pull. */
        private const val CLOUD_PULL_INTERVAL_MS = 5 * 60 * 1000L

        var instance: App? = null

        /** Reference to the application context.
         *
         * USE WITH EXTREME CAUTION!**/
        var context: Context? = null
        fun currentContext(): Context? {
            return instance?.mFTActivityLifecycleCallbacks?.currentActivity ?: context
        }

        fun currentActivity(): Activity? {
            return instance?.mFTActivityLifecycleCallbacks?.currentActivity
        }
    }
}