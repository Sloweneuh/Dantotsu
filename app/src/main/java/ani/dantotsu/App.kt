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
import ani.dantotsu.aniyomi.anime.custom.AppModule
import ani.dantotsu.aniyomi.anime.custom.PreferenceModule
import ani.dantotsu.connections.comments.CommentsAPI
import ani.dantotsu.connections.crashlytics.CrashlyticsInterface
import ani.dantotsu.notifications.TaskScheduler
import ani.dantotsu.notifications.WorkManagerScheduler
import ani.dantotsu.notifications.AlarmManagerScheduler
import ani.dantotsu.notifications.firebase.FirebaseBackgroundScheduler
import ani.dantotsu.others.DisabledReports
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
        private var resumeCount = 0

        override fun onActivityCreated(p0: Activity, p1: Bundle?) {
            lastActivity = p0.javaClass.simpleName
        }

        override fun onActivityStarted(p0: Activity) {
            currentActivity = p0
        }

        override fun onActivityResumed(p0: Activity) {
            currentActivity = p0
            resumeCount++

            // Check for unread chapters when app comes to foreground
            // Only check if:
            // 1. It's been more than the configured interval since last check
            // 2. This isn't the very first activity resume (skip initial app launch)
            val now = System.currentTimeMillis()
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

                // Run the check in background
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        ani.dantotsu.notifications.unread.UnreadChapterNotificationTask()
                            .execute(this@App)
                    } catch (e: Exception) {
                        Logger.log("FTActivityLifecycleCallbacks: Error checking unread chapters: ${e.message}")
                    }
                }
            }
        }

        override fun onActivityPaused(p0: Activity) {}
        override fun onActivityStopped(p0: Activity) {}
        override fun onActivitySaveInstanceState(p0: Activity, p1: Bundle) {}
        override fun onActivityDestroyed(p0: Activity) {}
    }

    companion object {
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